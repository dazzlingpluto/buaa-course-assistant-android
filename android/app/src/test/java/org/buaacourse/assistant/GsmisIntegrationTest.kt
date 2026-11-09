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

/** All replies are synthetic; these tests never connect to a school service. */
class GsmisIntegrationTest {
    private val start = LocalDate.of(2026, 9, 7)

    private class Fixture(val mode: NetworkMode = NetworkMode.DIRECT) {
        var denied = false
        var graduatePortalDetected = true
        var terms = """{"code":0,"datas":{"kfdxnxqcx":{"totalSize":1,"rows":[{"XNXQDM":"20261","XNXQDM_DISPLAY":"2026-2027 学年第一学期"}]}}}"""
        var academic = graduateBody("C001", "有效课程", "class-a")
        var iclassCourses = """{"STATUS":0,"result":[{"id":"active","courseNum":"C001","courseName":"有效课程","classBeginTime":"08:00","classEndTime":"09:35"},{"id":"withdrawn","courseNum":"C002","courseName":"已退课程","classBeginTime":"08:00","classEndTime":"09:35"}]}"""
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        private val endpoints = IclassProtocol.endpoints(mode)
        private fun mapped(url: String) = if (mode == NetworkMode.VPN) IclassProtocol.toWebVpnUrl(url) else url.toHttpUrl()
        val repo = IclassRepository(baseClient = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            requests += r
            val path = r.url.encodedPath
            when {
                path.endsWith("/api/login") -> reply(r, "UC activated")
                path.endsWith("/api/uc/status") -> reply(r, """{"code":0,"data":{"schoolid":"fixture-student","name":"演示用户"}}""")
                path.endsWith("/home/currentUser.do") -> if (graduatePortalDetected)
                    reply(r, "", 302, mapped("https://byxt.buaa.edu.cn/jwapp/sys/byrhmhsy/currentUser.do").toString())
                    else reply(r, "Access denied", 403)
                path.endsWith("/student/schoolCalendars.do") -> reply(r, "Access denied", 403)
                path.endsWith("/byrhmhsy/currentUser.do") -> reply(r, "{}")
                path.endsWith("/wdkbapp/*default/index.do") && denied -> reply(r, "请在校园网环境下访问")
                path.endsWith("/wdkbapp/*default/index.do") && r.url.queryParameter("ticket") == null -> {
                    val sso = "https://sso.buaa.edu.cn/login".toHttpUrl().newBuilder()
                        .addQueryParameter("service", "https://gsmis.buaa.edu.cn/gsapp/sys/wdkbapp/*default/index.do").build()
                    reply(r, "", 302, mapped(sso.toString()).toString())
                }
                path.endsWith("/wdkbapp/*default/index.do") -> reply(r, "<html>Academic timetable</html>")
                path.endsWith("/login") && r.url.queryParameter("service")?.contains("gsmis.buaa.edu.cn") == true ->
                    reply(r, "", 302, mapped("https://gsmis.buaa.edu.cn/gsapp/sys/wdkbapp/*default/index.do?ticket=fixture-ticket").toString())
                path == endpoints.login.encodedPath && r.method == "GET" -> reply(r,
                    "<form id='loginForm'><input name='execution' value='fixture-execution'></form>")
                path == endpoints.login.encodedPath && r.method == "POST" -> reply(r, "<html>Authenticated</html>")
                r.url.queryParameter("type") == "jumpMyCenter" -> reply(r, "?loginName=fixture-name")
                path.endsWith("/user/login.action") -> reply(r, """{"STATUS":0,"result":{"id":"fixture-user","sessionId":"fixture-session"}}""")
                path.endsWith("get_stu_course_sched.action") -> if (r.url.queryParameter("dateStr") == "20260907") reply(r, iclassCourses)
                    else reply(r, """{"STATUS":2}""")
                path.endsWith("kfdxnxqcx.do") -> reply(r, terms)
                path.endsWith("loadXskbData.do") -> reply(r, academic)
                else -> error("Unexpected offline request")
            }
        }.build())
    }

    @Test fun academicSessionIsActivatedBeforeComparingAndNoIclassCredentialIsSentToIt() = runBlocking {
        val f = Fixture()
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val result = f.repo.linkAttendance(f.repo.loadWeek(1, start))
        assertEquals(AcademicStatus.VERIFIED, result.academic?.status)
        assertEquals(listOf("active"), result.days.flatMap { it.courses }.map { it.id })
        assertEquals(listOf("withdrawn"), result.academic!!.excluded.map { it.id })
        assertEquals(setOf("active"), result.academic!!.verifiedScheduleIds)
        val academicRequests = f.requests.filter { it.url.host == "gsmis.buaa.edu.cn" }
        assertTrue(academicRequests.any { it.url.queryParameter("ticket") == "fixture-ticket" })
        academicRequests.forEach {
            assertNull(it.header("sessionId"))
            val body = it.body as? FormBody
            if (body != null) {
                val fields = (0 until body.size).associate { i -> body.name(i) to body.value(i) }
                assertFalse(fields.containsKey("username"))
                assertFalse(fields.containsKey("password"))
            }
        }
        val post = academicRequests.single { it.url.encodedPath.endsWith("loadXskbData.do") }
        val body = post.body as FormBody
        assertEquals(mapOf("ZC" to "", "XNXQDM" to "20261", "XH" to "", "XQDM" to ""),
            (0 until body.size).associate { body.name(it) to body.value(it) })
        f.repo.logout()
    }

    @Test fun vpnAcademicQueriesRemainInsideTheSchoolVpn() = runBlocking {
        val f = Fixture(NetworkMode.VPN)
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val result = f.repo.linkAttendance(f.repo.loadWeek(1, start))
        assertEquals(AcademicStatus.VERIFIED, result.academic?.status)
        assertTrue(f.requests.all { it.url.host == "d.buaa.edu.cn" && it.url.scheme == "https" })
        f.requests.filter { it.url.encodedPath.contains("/gsapp/") }.forEach { assertNull(it.header("sessionId")) }
        f.repo.logout()
    }

    @Test fun offeredFutureSemesterDoesNotOverrideTheCurrentSemesterAfterUndergraduateDenial() = runBlocking {
        val f = Fixture().also {
            it.graduatePortalDetected = false
            it.terms = twoSemesters.replace("2026-2027学年第一学期", "2026-2027-1")
            it.academic = it.academic.replace("100000000000000000", "000000000100000000")
        }
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val result = f.repo.loadWeek(10, start)
        assertEquals(StudentType.GRADUATE, result.studentType)
        assertEquals("20261", result.academic!!.termCode)
        assertEquals(1, result.total)
        assertEquals(LocalDate.of(2026, 11, 9), result.days.first().date)
        assertTrue(f.requests.any { it.url.encodedPath.endsWith("/student/schoolCalendars.do") })
        val post = f.requests.single { it.url.encodedPath.endsWith("loadXskbData.do") }.body as FormBody
        assertEquals("20261", (0 until post.size).associate { post.name(it) to post.value(it) }["XNXQDM"])
        f.repo.logout()
    }

    @Test fun unavailableCurrentSemesterLeavesTheTermPickerUsable() = runBlocking {
        val f = Fixture().also {
            it.terms = """{"code":0,"datas":{"kfdxnxqcx":{"totalSize":1,"rows":[
                {"XNXQDM":"20262","XNXQDM_DISPLAY":"2026-2027学年第二学期"}]}}}"""
            it.academic = """{"code":1,"rwList":[],"jgList":[]}"""
        }
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val initial = f.repo.loadWeek(0, start)
        assertEquals(AcademicStatus.SELECT_TERM, initial.academic?.status)
        assertEquals(listOf("20262"), initial.academic!!.terms.map { it.code })
        assertTrue(initial.days.isEmpty())
        assertTrue(f.requests.none { it.url.encodedPath.endsWith("loadXskbData.do") })

        f.repo.selectAcademicTerm("20262")
        val spring = f.repo.loadWeek(0, start)
        assertEquals(AcademicStatus.UNAVAILABLE, spring.academic?.status)
        assertEquals(listOf("20262"), spring.academic!!.terms.map { it.code })
        assertTrue(spring.days.isEmpty())
        f.repo.logout()
    }

    @Test fun explicitGraduateSemesterChoiceOverridesTheDefaultAndSurvivesRefresh() = runBlocking {
        val f = Fixture().also { it.terms = twoSemesters }
        f.repo.login("fixture-student", "fixture-password", f.mode)
        assertEquals("20261", f.repo.loadWeek(1, start).academic!!.termCode)
        f.repo.selectAcademicTerm("20262")
        f.academic = f.academic.replace("\"KCMC\":\"有效课程\"", "\"KCMC\":\"有效课程\",\"SCSKRQ\":\"2027-02-22\"")
        val spring = f.repo.loadWeek(1, start)
        assertEquals("20262", spring.academic!!.termCode)
        assertEquals(LocalDate.of(2027, 2, 22), spring.semesterStart)
        assertEquals("20262", f.repo.loadWeek(1, start).academic!!.termCode)
        f.repo.logout()
    }

    @Test fun recordsWithDifferentNamesCannotShareAcademicApprovalThroughCardMerging() = runBlocking {
        val f = Fixture().also {
            it.iclassCourses = """{"STATUS":0,"result":[
                {"id":"active","courseNum":"C001","courseName":"有效课程","classBeginTime":"08:00","classEndTime":"09:35","classroomName":"101"},
                {"id":"uncertain","courseNum":"C001","courseName":"残留课程","classBeginTime":"08:00","classEndTime":"09:35","classroomName":"101"}
            ]}"""
        }
        f.repo.login("fixture-student", "fixture-password", f.mode)
        val result = f.repo.linkAttendance(f.repo.loadWeek(1, start))
        assertEquals(1, result.total)
        assertEquals(setOf("active"), result.academic!!.verifiedScheduleIds)
        assertEquals(0, result.academic!!.uncertainCount)
        assertEquals(listOf("uncertain"), result.academic!!.excluded.map { it.id })
        assertEquals(listOf("active"), result.days.first().courses.first().scheduleIds)
        f.repo.logout()
    }

    @Test fun officialFailureNeverSubstitutesIclassForTheSchoolTimetable() = runBlocking {
        for (body in listOf("<html>Login required</html>", "{}")) {
            val f = Fixture().also { it.academic = body }
            f.repo.login("fixture-student", "fixture-password", f.mode)
            assertTrue(runCatching { f.repo.loadWeek(1, start) }.exceptionOrNull() is IclassException)
            assertTrue(f.requests.none { it.url.encodedPath.endsWith("get_stu_course_sched.action") })
            f.repo.logout()
        }
    }

    @Test fun graduateRouteIsAutomaticallyRememberedOnlyWithinTheSuccessfulSession() = runBlocking {
        val f = Fixture()
        f.repo.login("fixture-student", "fixture-password", f.mode)
        assertEquals(StudentType.GRADUATE, f.repo.loadWeek(1, start).studentType)
        f.repo.loadWeek(1, start)
        assertEquals(1, f.requests.count { it.url.encodedPath.endsWith("/home/currentUser.do") })
        f.repo.logout()
    }

    @Test fun refreshReadsTheOfficialTimetableAgainInsteadOfReusingAnOldFilter() = runBlocking {
        val f = Fixture()
        f.repo.login("fixture-student", "fixture-password", f.mode)
        assertEquals(listOf("active"), f.repo.linkAttendance(f.repo.loadWeek(1, start)).days.flatMap { it.courses }.map { it.id })
        f.academic = graduateBody("C002", "已退课程", "class-b")
        val refreshed = f.repo.linkAttendance(f.repo.loadWeek(1, start))
        assertEquals(listOf("withdrawn"), refreshed.days.flatMap { it.courses }.map { it.id })
        assertEquals(2, f.requests.count { it.url.encodedPath.endsWith("loadXskbData.do") })
        f.repo.logout()
    }

    companion object {
        private val twoSemesters = """{"code":0,"datas":{"kfdxnxqcx":{"totalSize":2,"rows":[
            {"XNXQDM":"20262","XNXQDM_DISPLAY":"2026-2027学年第二学期"},
            {"XNXQDM":"20261","XNXQDM_DISPLAY":"2026-2027学年第一学期"}
        ]}}}"""

        private fun graduateBody(code: String, name: String, classId: String) = """{
            "code":1,"rwList":[{"BJDM":"$classId","KCMC":"$name"}],
            "jgList":[{"BJDM":"$classId","KCDM":"$code","XQ":1,"ZCBH":"100000000000000000",
                "KSJCDM":1,"JSJCDM":2,"JCFADM":"scheme","JASMC":"星桥楼 6-107","JGJSXM":"演示教师"}],
            "jcfaList":[{"skjcList":[
                {"JCFADM":"scheme","DM":1,"KSSJ":800,"JSSJ":845},
                {"JCFADM":"scheme","DM":2,"KSSJ":850,"JSSJ":935}]}]
        }"""

        private fun reply(request: Request, body: String, code: Int = 200, location: String? = null): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Offline fixture")
                .body(body.toResponseBody("text/plain; charset=utf-8".toMediaType()))
                .apply { if (location != null) header("Location", location) }.build()
    }
}
