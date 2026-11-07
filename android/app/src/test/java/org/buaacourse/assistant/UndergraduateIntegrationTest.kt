package org.buaacourse.assistant

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Collections

/** Synthetic school replies only: no school login or real attendance is submitted. */
class UndergraduateIntegrationTest {
    private val start = LocalDate.of(2026, 9, 7)

    private class Fixture(val mode: NetworkMode = NetworkMode.DIRECT) {
        var iclassAvailable = true
        var academic = """{"code":"0","datas":{"code":"fixture-student","arrangedList":[{
            "courseCode":"B001","courseName":"高等数学","courseSerialNo":"section-01",
            "beginTime":"08:00","endTime":"09:35","beginSection":1,"endSection":2,
            "placeName":"主楼101","dayOfWeek":1,"weeksAndTeachers":"1-18周 演示教师"
        }]}}"""
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        private val endpoints = IclassProtocol.endpoints(mode)
        private fun mapped(raw: String) = if (mode == NetworkMode.VPN) IclassProtocol.toWebVpnUrl(raw) else raw.toHttpUrl()
        val repo = IclassRepository(baseClient = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            requests += r
            val path = r.url.encodedPath
            when {
                path.endsWith("/api/login") -> reply(r, "<html>UC activated</html>")
                path.endsWith("/api/uc/status") -> reply(r, """{"code":0,"data":{"schoolid":"fixture-student","name":"演示用户"}}""")
                path.endsWith("getTermWeeks.do") -> reply(r, """{"code":"0","datas":[{"serialNumber":1,"startDate":"2026-09-07","endDate":"2026-09-13","term":"term-undergraduate","curWeek":true}]}""")
                r.url == mapped("https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/currentUser.do") -> {
                    val sso = "https://sso.buaa.edu.cn/login".toHttpUrl().newBuilder()
                        .addQueryParameter("service", "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/currentUser.do").build()
                    reply(r, "", 302, mapped(sso.toString()).toString())
                }
                r.url == mapped("https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/currentUser.do?ticket=fixture-ticket") -> reply(r, """{"code":"0","datas":{"code":"fixture-student"}}""")
                path.endsWith("/login") && r.url.queryParameter("service") == "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/currentUser.do" ->
                    reply(r, "", 302, mapped("https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/currentUser.do?ticket=fixture-ticket").toString())
                path == endpoints.login.encodedPath && r.method == "GET" -> reply(r,
                    "<form id='loginForm'><input name='execution' value='fixture-execution'></form>")
                path == endpoints.login.encodedPath && r.method == "POST" -> reply(r, "<html>Authenticated</html>")
                r.url.queryParameter("type") == "jumpMyCenter" -> reply(r, "?loginName=fixture-name")
                path.endsWith("/user/login.action") -> reply(r, if (iclassAvailable)
                    """{"STATUS":0,"result":{"id":"fixture-user","sessionId":"fixture-session"}}""" else """{"STATUS":1}""")
                path.endsWith("get_stu_course_sched.action") -> reply(r, if (r.url.queryParameter("dateStr") == "20260907")
                    """{"STATUS":0,"result":[{"id":"real-schedule","courseNum":"B001","courseName":"高等数学","classBeginTime":"08:00","classEndTime":"09:35"}]}"""
                    else """{"STATUS":2}""")
                path.endsWith("schoolCalendars.do") -> reply(r,
                    """{"code":"0","datas":[{"itemCode":"term-undergraduate","itemName":"2026-2027学年第一学期","selected":true}]}""")
                path.endsWith("getMyScheduleDetail.do") -> reply(r, academic)
                else -> error("Unexpected offline request: ${r.method} $path")
            }
        }.build())
    }

    @Test fun directUndergraduateScheduleActivatesSsoAndUsesExactWeeklyRequestWithoutSendingCredentials() = runBlocking {
        val f = Fixture()
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val week = f.repo.linkAttendance(f.repo.loadWeek(1, start))
        assertEquals(AcademicStatus.VERIFIED, week.academic?.status)
        val course = week.days.first().courses.single()
        assertEquals("高等数学", course.courseName)
        assertEquals("section-01", course.classCode)
        assertEquals("主楼101", course.classroomName)
        assertEquals(listOf("real-schedule"), course.scheduleIds)
        assertTrue(course.attendanceAvailable)
        val academicRequests = f.requests.filter { it.url.host == "byxt.buaa.edu.cn" }
        assertTrue(academicRequests.any { it.url.queryParameter("ticket") == "fixture-ticket" })
        assertEquals("GET", academicRequests.single { it.url.encodedPath.endsWith("schoolCalendars.do") }.method)
        val query = academicRequests.single { it.url.encodedPath.endsWith("getMyScheduleDetail.do") }
        assertEquals("POST", query.method)
        assertEquals("XMLHttpRequest", query.header("X-Requested-With"))
        assertEquals("https://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.html", query.header("Referer"))
        val body = query.body as FormBody
        assertEquals(mapOf("termCode" to "term-undergraduate", "type" to "week", "week" to "1"),
            (0 until body.size).associate { body.name(it) to body.value(it) })
        academicRequests.forEach { r ->
            assertNull(r.header("sessionId"))
            (r.body as? FormBody)?.let { form ->
                assertFalse((0 until form.size).any { form.name(it) in setOf("username", "password") })
            }
        }
        assertTrue(f.requests.none { it.url.host == "gsmis.buaa.edu.cn" })
        assertEquals(StudentType.UNDERGRADUATE, week.studentType)
        f.repo.logout()
    }

    @Test fun undergraduateVpnRequestsNeverLeaveSchoolVpn() = runBlocking {
        val f = Fixture(NetworkMode.VPN)
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val week = f.repo.linkAttendance(f.repo.loadWeek(1, start))
        assertEquals(AcademicStatus.VERIFIED, week.academic?.status)
        assertTrue(f.requests.all { it.url.scheme == "https" && it.url.host == "d.buaa.edu.cn" })
        val query = f.requests.single { it.url.encodedPath.endsWith("getMyScheduleDetail.do") }
        assertTrue(query.header("Referer")!!.startsWith("https://d.buaa.edu.cn/"))
        assertNull(query.header("sessionId"))
        f.repo.logout()
    }

    @Test fun officialOnlyCourseStaysVisibleButSyntheticIdsCannotBeSubmitted() = runBlocking {
        val f = Fixture().also { it.academic = it.academic.replace("B001", "B002") }
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val week = f.repo.linkAttendance(f.repo.loadWeek(1, start))
        val course = week.days.first().courses.single()
        assertFalse(course.attendanceAvailable)
        assertTrue(course.scheduleIds.isEmpty())
        assertEquals(1, week.academic?.uncertainCount)
        assertEquals(1, f.repo.signCourses(listOf(course)).failed)
        assertTrue(f.requests.none { it.url.encodedPath.endsWith("stu_scan_sign.action") || it.url.encodedPath.endsWith("get_timestamp.action") })
        f.repo.logout()
    }

    @Test fun verifiedEmptyOfficialWeekDoesNotRedisplayIclassLeftovers() = runBlocking {
        val f = Fixture().also { it.academic = """{"code":"0","datas":{"arrangedList":[]}}""" }
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val week = f.repo.linkAttendance(f.repo.loadWeek(1, start))
        assertEquals(AcademicStatus.VERIFIED, week.academic?.status)
        assertEquals(0, week.total)
        assertEquals(1, week.academic?.excluded?.size)
        assertTrue(week.days.none { it.failed })
        f.repo.logout()
    }

    @Test fun malformedOfficialWeekNeverFallsBackToIclassCourses() = runBlocking {
        val f = Fixture().also { it.academic = "<html>Login required</html>" }
        f.repo.login("fixture-student", "fixture-password", f.mode)
        assertTrue(runCatching { f.repo.loadWeek(1, start) }.exceptionOrNull() is IclassException)
        assertTrue(f.requests.none { it.url.encodedPath.endsWith("get_stu_course_sched.action") })
        f.repo.logout()
    }

    @Test fun officialTimetableIsReturnedBeforeAnyIclassRequestAndSurvivesItsFailure() = runBlocking {
        val f = Fixture().also { it.iclassAvailable = false }
        val user = f.repo.login("fixture-student", "fixture-password", f.mode)
        assertEquals("fixture-student", user.userId)
        val official = f.repo.loadWeek(1, start)
        assertEquals(AcademicStatus.VERIFIED, official.academic?.status)
        assertTrue(f.requests.none { "iclass" in it.url.host || it.url.encodedPath.endsWith("/user/login.action") })
        val linked = f.repo.linkAttendance(official)
        assertEquals(official.days, linked.days)
        assertFalse(linked.days.first().courses.single().attendanceAvailable)
        assertNotNull(linked.attendanceMessage)
        f.repo.logout()
    }

    @Test fun optionalMissingTimesDoNotRejectWholeWeekOrInventAttendance() = runBlocking {
        val f = Fixture().also {
            it.academic = """{"code":"0","datas":{"code":"fixture-student","name":"演示课表","arrangedList":[{
                "courseCode":"B002","courseName":"待安排课程","beginTime":null,"endTime":null,"dayOfWeek":null,
                "beginSection":null,"endSection":null,"courseSerialNo":null,"placeName":null
            }]}}"""
        }
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val official = f.repo.loadWeek(1, start)
        assertEquals(AcademicStatus.VERIFIED, official.academic?.status)
        assertEquals(1, official.total)
        assertEquals("待安排课程", official.unscheduled.single().courseName)
        assertFalse(official.unscheduled.single().attendanceAvailable)
        f.repo.logout()
    }

    companion object {
        private fun reply(request: Request, body: String, code: Int = 200, location: String? = null): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Offline fixture")
                .body(body.toResponseBody("text/plain; charset=utf-8".toMediaType()))
                .apply { if (location != null) header("Location", location) }.build()
    }
}
