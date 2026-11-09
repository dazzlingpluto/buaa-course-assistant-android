package org.buaacourse.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IclassRepositoryTest {
    private class Fixture(
        schoolClock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
        val custom: (Request) -> Response? = { null },
    ) {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            custom(request) ?: when {
                request.url.encodedPath.endsWith("/api/login") -> reply(request, "UC activated")
                request.url.encodedPath.endsWith("/api/uc/status") -> reply(request, """{"code":0,"data":{"schoolid":"123","name":"演示用户"}}""")
                request.url.encodedPath == "/login" && request.method == "GET" -> reply(request,
                    "<form id='loginForm' action='/login'><input name='execution' value='cas-state'><input name='csrf' value='csrf-state'></form>")
                request.url.encodedPath == "/login" && request.method == "POST" ->
                    reply(request, "", 302, "https://sso.buaa.edu.cn/done")
                request.url.encodedPath == "/done" -> reply(request, "<p>Welcome</p>")
                request.url.queryParameter("type") == "jumpMyCenter" -> reply(request, "", 302,
                    "https://iclass.buaa.edu.cn:8346/?loginName=abc%2Bdef%3D")
                request.url.encodedPath.endsWith("/user/login.action") -> reply(request,
                    """{"STATUS":0,"result":{"id":123,"sessionId":"session-secret","realName":"演示用户"}}""")
                request.url.encodedPath.endsWith("get_stu_course_sched.action") -> reply(request, """{"STATUS":2}""")
                request.url.encodedPath.endsWith("get_timestamp.action") -> reply(request, """{"timestamp":"1770000123456"}""")
                request.url.encodedPath.endsWith("stu_scan_sign.action") -> reply(request, """{"STATUS":0}""")
                else -> error("Unexpected offline request path: ${request.url.encodedPath}")
            }
        }).build()
        val repo = IclassRepository(logger = { message, _ -> logs += message }, baseClient = client, schoolClock = schoolClock)
    }

    @Test fun completeLoginUsesHiddenStateAndTemporaryLoginNameWithoutLoggingSecrets() = runBlocking {
        val fixture = Fixture()
        val user = fixture.repo.login("  student  ", "super-secret-password", NetworkMode.DIRECT)
        assertEquals("123", user.userId)
        assertEquals("演示用户", user.userName)
        val form = fixture.requests.first { it.method == "POST" }.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("student", fields["username"])
        assertEquals("super-secret-password", fields["password"])
        assertEquals("csrf-state", fields["csrf"])
        assertEquals("cas-state", fields["execution"])
        assertTrue(fixture.requests.last().url.encodedPath.endsWith("/api/uc/status"))
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            fixture.requests.first().header("User-Agent"))
        assertTrue(fixture.logs.none { it.contains("student") || it.contains("secret") || it.contains("abc+def") })
        fixture.repo.logout()
    }

    @Test fun directCasPortalHandoffDoesNotVisitPortalOrDeclareSuccessBeforeCourseLogin() = runBlocking {
        val targets = listOf(
            "https://app.buaa.edu.cn/portal?ticket=portal-ticket-secret",
            "http://app.buaa.edu.cn/portal?ticket=portal-ticket-secret",
            "https://unrelated.example/portal?ticket=portal-ticket-secret",
        )
        for (code in listOf(302, 303)) for (target in targets) {
            lateinit var fixture: Fixture
            var logsBeforeCourseResponse: List<String>? = null
            fixture = Fixture { request ->
                when {
                    request.url.host == "sso.buaa.edu.cn" && request.method == "POST" ->
                        reply(request, "", code, target)
                    request.url.encodedPath.endsWith("/api/uc/status") -> {
                        logsBeforeCourseResponse = fixture.logs.toList()
                        null
                    }
                    else -> null
                }
            }
            try {
                val user = fixture.repo.login("student", "credential-secret", NetworkMode.DIRECT)
                assertEquals("123", user.userId)
                assertEquals(4, fixture.requests.size)
                assertTrue(fixture.requests.none { it.url.host == target.toHttpUrl().host })
                assertEquals(1, fixture.requests.count { it.method == "POST" })
                assertTrue(fixture.requests.none { it.url.queryParameter("type") == "jumpMyCenter" })
                assertTrue("Success must wait for the course login response", checkNotNull(logsBeforeCourseResponse).none { "成功" in it })
                assertTrue(fixture.logs.any { "登录成功" in it })
                assertTrue(fixture.logs.none { "portal-ticket-secret" in it || "credential-secret" in it })
            } finally { fixture.repo.logout() }
        }
    }

    @Test fun directCasHandoffAlsoHandlesAnIntermediateRedirectOnTheAuthenticationHost() = runBlocking {
        val fixture = Fixture { request ->
            when {
                request.url.encodedPath == "/done" -> reply(request, "", 303,
                    "https://app.buaa.edu.cn/portal?ticket=portal-ticket-secret")
                else -> null
            }
        }
        try {
            assertEquals("123", fixture.repo.login("student", "credential-secret", NetworkMode.DIRECT).userId)
            val middle = fixture.requests.single { it.url.encodedPath == "/done" }
            assertEquals("GET", middle.method)
            assertNull(middle.body)
            assertTrue(fixture.requests.none { it.url.host == "app.buaa.edu.cn" })
            assertEquals(1, fixture.requests.count { it.method == "POST" })
        } finally { fixture.repo.logout() }
    }

    @Test fun portalHandoffWithoutACourseSessionCannotBecomeSuccessfulLogin() = runBlocking {
        val fixture = Fixture { request ->
            when {
                request.url.host == "sso.buaa.edu.cn" && request.method == "POST" ->
                    reply(request, "", 302, "https://app.buaa.edu.cn/portal")
                request.url.encodedPath.endsWith("/api/uc/status") -> reply(request, """{"code":1}""")
                else -> null
            }
        }
        try {
            val error = runCatching { fixture.repo.login("student", "wrong-password", NetworkMode.DIRECT) }.exceptionOrNull()
            assertTrue(error is IclassException)
            assertTrue(fixture.logs.none { "成功" in it })
            assertTrue(fixture.requests.none { it.url.encodedPath.endsWith("/user/login.action") })
            assertTrue(fixture.requests.none { it.url.host == "app.buaa.edu.cn" })
            assertTrue(runCatching { fixture.repo.loadWeek(1, LocalDate.of(2026, 9, 7)) }.exceptionOrNull() is IclassException)
        } finally { fixture.repo.logout() }
    }

    @Test fun returningALoginFormAfterPasswordSubmissionStillFailsBeforeMyCenter() = runBlocking {
        val fixture = Fixture { request ->
            if (request.method == "POST") reply(request,
                "<form id='fm1'><input name='execution' value='new-state'></form>") else null
        }
        val error = runCatching { fixture.repo.login("student", "wrong-password", NetworkMode.DIRECT) }.exceptionOrNull()
        assertTrue(error is IclassException)
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.logs.none { "成功" in it })
        assertFalse(error!!.message.orEmpty().contains("wrong-password"))
        fixture.repo.logout()
    }

    @Test fun rejectedOrIncompleteCourseLoginNeverEmitsSuccessAfterPortalHandoff() = runBlocking {
        for (payload in listOf("""{"STATUS":1}""", """{"STATUS":0,"result":{"id":123}}""")) {
            val fixture = Fixture { request ->
                when {
                    request.url.host == "sso.buaa.edu.cn" && request.method == "POST" ->
                        reply(request, "", 303, "https://app.buaa.edu.cn/portal")
                    request.url.encodedPath.endsWith("/api/uc/status") -> reply(request, payload)
                    else -> null
                }
            }
            try {
                val error = runCatching { fixture.repo.login("student", "credential-secret", NetworkMode.DIRECT) }.exceptionOrNull()
                assertTrue(error is IclassException)
                assertTrue(fixture.logs.none { "成功" in it })
                assertTrue(runCatching { fixture.repo.loadWeek(1, LocalDate.of(2026, 9, 7)) }.exceptionOrNull() is IclassException)
            } finally { fixture.repo.logout() }
        }
    }

    @Test fun rejectedEntryRedirectReportsOnlyOriginDetailsWithoutSendingCredentials() = runBlocking {
        val fixture = Fixture { request -> reply(request, "", 302,
            "https://url-user:url-password@blocked.example:8443/private-path-secret?ticket=ticket-secret&loginName=login-secret#fragment-secret") }
        val error = runCatching { fixture.repo.login("student-secret", "credential-secret", NetworkMode.DIRECT) }.exceptionOrNull()
        assertTrue(error is IclassException)
        assertEquals(1, fixture.requests.size)
        assertEquals("GET", fixture.requests.single().method)
        val diagnostic = fixture.logs.joinToString("\n") + "\n" + error!!.message.orEmpty()
        assertTrue(diagnostic.contains("blocked.example"))
        assertTrue(diagnostic.contains("https", ignoreCase = true))
        assertTrue(diagnostic.contains("8443"))
        for (secret in listOf("url-user", "url-password", "private-path-secret", "ticket-secret", "login-secret", "fragment-secret", "student-secret", "credential-secret")) {
            assertFalse("Diagnostic leaked $secret", diagnostic.contains(secret))
        }
        assertFalse(diagnostic.contains("https://"))
        fixture.repo.logout()
    }

    @Test fun failedAttendanceDaysCannotReplaceAnOfficialTimetable() = runBlocking {
        val fixture = Fixture { request ->
            if (request.url.encodedPath.endsWith("get_stu_course_sched.action")) reply(request, "unavailable", 503) else null
        }
        fixture.repo.login("u", "p", NetworkMode.DIRECT)
        val start = LocalDate.of(2026, 9, 7)
        val course = Course("academic:one", "正式课程", attendanceAvailable = false, scheduleIds = emptyList())
        val official = WeekSchedule(1, start, (0L..6L).map { DaySchedule(start.plusDays(it), if(it == 0L) listOf(course) else emptyList()) }, academic = AcademicComparison(AcademicStatus.VERIFIED))
        val linked = fixture.repo.linkAttendance(official)
        assertEquals(official.days, linked.days)
        assertNotNull(linked.attendanceMessage)
        fixture.repo.logout()
    }

    @Test fun directAttendanceUsesShanghaiWeekAndSignsOnlyPendingIclassIdsWithoutAcademicRequests() = runBlocking {
        val fixture = Fixture(novemberSchoolClock) { request ->
            if (!request.url.encodedPath.endsWith("get_stu_course_sched.action")) null else when (request.url.queryParameter("dateStr")) {
                "20261109" -> reply(request, """{"STATUS":0,"result":[
                    {"id":"complete","courseNum":"C01","courseName":"示例课程","classBeginTime":"08:00","classEndTime":"09:35","signStatus":"1"},
                    {"id":"pending","courseNum":"C01","courseName":"示例课程","classBeginTime":"08:00","classEndTime":"09:35","signStatus":"0"},
                    {"courseNum":"C02","courseName":"无排课编号"}
                ]}""")
                "20261110" -> reply(request, "unavailable", 503)
                else -> null
            }
        }
        fixture.repo.login("fixture-student", "fixture-password", NetworkMode.DIRECT)
        try {
            val schedule = fixture.repo.loadDirectAttendance(0, LocalDate.of(2026, 9, 7))
            assertEquals(10, schedule.week)
            assertEquals(LocalDate.of(2026, 11, 9), schedule.days.first().date)
            assertTrue(schedule.weeks.single { it.number == 10 }.current)
            assertNull(schedule.academic)
            assertEquals(listOf(LocalDate.of(2026, 11, 10)), schedule.days.filter { it.failed }.map { it.date })
            val (course, missingId) = schedule.days.first().courses
            assertFalse(missingId.attendanceAvailable)
            assertEquals(listOf("complete", "pending"), course.scheduleIds)
            assertEquals(setOf("complete"), course.completedIds)
            assertEquals(1, schedule.attendanceTotal)
            assertEquals(0, schedule.attendanceSigned)
            val summary = fixture.repo.signCourses(listOf(course))
            assertEquals(1, summary.success)
            assertEquals(1, summary.skipped)
            val signRequest = fixture.requests.single { it.url.encodedPath.endsWith("stu_scan_sign.action") }
            assertEquals("pending", signRequest.url.queryParameter("courseSchedId"))
            assertEquals("session-secret", signRequest.header("sessionId"))
            val dates = fixture.requests.filter { it.url.encodedPath.endsWith("get_stu_course_sched.action") }
            assertEquals((9..15).map { "202611${it.toString().padStart(2, '0')}" },
                dates.mapNotNull { it.url.queryParameter("dateStr") }.sorted())
            assertTrue(fixture.requests.all { it.url.host in setOf("sso.buaa.edu.cn", "uc.buaa.edu.cn", "iclass.buaa.edu.cn") })
        } finally { fixture.repo.logout() }
    }

    @Test fun directAttendanceKeepsCurrentWeekAccessibleOutsideSemesterAndReportsTotalFailure() = runBlocking {
        val fixture = Fixture(novemberSchoolClock) { request ->
            if (request.url.encodedPath.endsWith("get_stu_course_sched.action") &&
                request.url.queryParameter("dateStr") in (16..22).map { "202611$it" }) {
                reply(request, "unavailable", 503)
            } else null
        }
        fixture.repo.login("fixture-student", "fixture-password", NetworkMode.DIRECT)
        try {
            val current = fixture.repo.loadDirectAttendance(0, LocalDate.of(2027, 2, 22))
            assertEquals(1, current.week)
            assertEquals(LocalDate.of(2026, 11, 9), current.semesterStart)
            assertEquals(current.semesterStart, current.days.first().date)
            assertEquals(LocalDate.of(2026, 11, 16), current.weeks[1].start)
            val error = runCatching { fixture.repo.loadDirectAttendance(2, current.semesterStart) }.exceptionOrNull()
            assertTrue(error is IclassException && error.message.orEmpty().contains("iClass 签到课程读取失败"))
        } finally { fixture.repo.logout() }
    }

    @Test fun vpnLoginAndSigningKeepAllTrafficInsideSchoolWebVpn() = runBlocking {
        val endpoints = IclassProtocol.endpoints(NetworkMode.VPN)
        val fixture = Fixture { request ->
            when {
                request.url.encodedPath == endpoints.login.encodedPath && request.method == "GET" ->
                    reply(request, "<form id='loginForm'><input name='execution' value='vpn-state'></form>")
                request.url.encodedPath == endpoints.login.encodedPath && request.method == "POST" ->
                    reply(request, "", 302, "https://d.buaa.edu.cn/")
                request.url.toString() == "https://d.buaa.edu.cn/" -> reply(request, "<p>VPN ready</p>")
                request.url.encodedPath.endsWith("/api/login") -> reply(request, "UC activated")
                request.url.encodedPath.endsWith("/api/uc/status") -> reply(request, """{"code":0,"data":{"schoolid":"123","name":"演示用户"}}""")
                request.url == endpoints.myCenter -> reply(request, "", 302,
                    endpoints.myCenter.newBuilder().query(null).addQueryParameter("loginName", "vpn+name=").build().toString())
                else -> null
            }
        }
        fixture.repo.login("vpn-user", "vpn-password", NetworkMode.VPN)
        val summary = fixture.repo.signCourses(listOf(Course("vpn-class", "课程")))
        assertEquals(1, summary.success)
        assertTrue(fixture.requests.all { it.url.host == "d.buaa.edu.cn" && it.url.scheme == "https" })
        assertTrue("VPN must complete the portal cookie exchange", fixture.requests.any { it.url.toString() == "https://d.buaa.edu.cn/" })
        assertTrue(fixture.requests.last().url.encodedPath.startsWith("/http-8081/"))
        fixture.repo.logout()
    }

    @Test fun vpnLoginCompletesUcTicketExchangeWhenCasRedirectIsWrappedAsHttp() = runBlocking {
        val endpoints = IclassProtocol.endpoints(NetworkMode.VPN)
        val ucLogin = "https://uc.buaa.edu.cn/api/login".toHttpUrl().newBuilder()
            .addQueryParameter("target", "https://uc.buaa.edu.cn/#/user/login").build()
        val ucCallback = ucLogin.newBuilder().addQueryParameter("ticket", "fixture-ticket").build()
        val casService = "http://sso.buaa.edu.cn/login".toHttpUrl().newBuilder()
            .addQueryParameter("service", ucLogin.toString()).build()
        val wrappedHttp = IclassProtocol.toWebVpnUrl(casService.toString())
        val wrappedHttps = IclassProtocol.toWebVpnUrl(casService.newBuilder().scheme("https").port(443).build().toString())
        var ticketExchanged = false
        val fixture = Fixture { request ->
            when {
                request.url == endpoints.login && request.method == "GET" ->
                    reply(request, "<form id='loginForm'><input name='execution' value='vpn-state'></form>")
                request.url == endpoints.login && request.method == "POST" ->
                    reply(request, "", 302, "https://d.buaa.edu.cn/login?cas_login=true&ticket=vpn-ticket")
                request.url.encodedPath == "/login" && request.url.queryParameter("ticket") == "vpn-ticket" ->
                    reply(request, "<p>VPN ready</p>")
                request.url == IclassProtocol.toWebVpnUrl(ucLogin.toString()) ->
                    reply(request, "", 302, wrappedHttp.encodedPath + "?" + wrappedHttp.encodedQuery)
                request.url == wrappedHttp ->
                    reply(request, "<form id='loginForm'><input name='execution' value='new-state'></form>")
                request.url == wrappedHttps ->
                    reply(request, "", 302, ucCallback.toString())
                request.url == IclassProtocol.toWebVpnUrl(ucCallback.toString()) -> {
                    ticketExchanged = true
                    reply(request, "<p>UC activated</p>")
                }
                request.url.encodedPath.endsWith("/api/uc/status") ->
                    reply(request, if (ticketExchanged) """{"code":0,"data":{"schoolid":"123","name":"演示用户"}}"""
                        else """{"code":10600,"data":null}""")
                else -> null
            }
        }
        try {
            assertEquals("123", fixture.repo.login("vpn-user", "vpn-password", NetworkMode.VPN).userId)
            assertTrue(fixture.requests.all { it.url.host == "d.buaa.edu.cn" && it.url.scheme == "https" })
            assertEquals(listOf(endpoints.login), fixture.requests.filter { it.method == "POST" }.map { it.url })
            assertTrue(fixture.requests.none { it.url == wrappedHttp })
            assertTrue(ticketExchanged)
        } finally { fixture.repo.logout() }
    }

    @Test fun allFailedDaysDoNotBecomeAnEmptySuccessfulTimetable() = runBlocking {
        val fixture = Fixture { if (it.url.encodedPath.endsWith("get_stu_course_sched.action")) reply(it, "<html>login required</html>") else null }
        fixture.repo.login("u", "p", NetworkMode.DIRECT)
        val error = runCatching { fixture.repo.loadWeek(1, LocalDate.of(2026, 9, 7)) }.exceptionOrNull()
        assertTrue(error is IclassException)
        assertTrue(error!!.message!!.contains("课表读取失败"))
        fixture.repo.logout()
    }

    @Test fun mergedCourseSignsEveryUniqueScheduleAndTreatsPlainTextSuccessAsFailure() = runBlocking {
        val fixture = Fixture { request ->
            if (!request.url.encodedPath.endsWith("stu_scan_sign.action")) null else when (request.url.queryParameter("courseSchedId")) {
                "b" -> reply(request, """{"STATUS":1,"ERRMSG":"该课程已签到"}""")
                "c" -> reply(request, "签到未成功")
                else -> null
            }
        }
        fixture.repo.login("u", "p", NetworkMode.DIRECT)
        val summary = fixture.repo.signCourses(listOf(
            Course("a", "合并课程", scheduleIds = listOf("a", "b", "a")),
            Course("c", "失败课程"), Course("d", "已签到课程", signed = true),
        ))
        assertEquals(1, summary.success)
        assertEquals(2, summary.skipped)
        assertEquals(1, summary.failed)
        val posts = fixture.requests.filter { it.url.encodedPath.endsWith("stu_scan_sign.action") }
        assertEquals(listOf("a", "b", "c"), posts.map { it.url.queryParameter("courseSchedId") })
        posts.forEach { request ->
            assertEquals("1770000123456", request.url.queryParameter("timestamp"))
            assertEquals("session-secret", request.header("sessionId"))
            val form = request.body as FormBody
            assertEquals("id", form.name(0)); assertEquals("123", form.value(0))
        }
        fixture.repo.logout()
    }

    @Test fun passwordIsNeverRepostedToAnotherServiceOn307Or308Redirect() = runBlocking {
        for (code in listOf(307, 308)) for (target in listOf(
            "https://iclass.buaa.edu.cn:8347/collect",
            "https://byxt.buaa.edu.cn/collect",
            "https://app.buaa.edu.cn/portal?ticket=redirect-secret",
        )) {
            val fixture = Fixture { request ->
                if (request.method == "POST") reply(request, "", code, target) else null
            }
            val error = runCatching { fixture.repo.login("u", "credential-secret", NetworkMode.DIRECT) }.exceptionOrNull()
            assertTrue(error is IclassException)
            assertEquals(2, fixture.requests.size)
            assertTrue(fixture.requests.all { it.url.host == "sso.buaa.edu.cn" })
            assertFalse(error!!.message.orEmpty().contains("credential-secret"))
            assertFalse(error.message.orEmpty().contains("redirect-secret"))
            fixture.repo.logout()
        }
    }

    @Test fun logoutCancelsAnInFlightLoginAndCannotResurrectSession() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture { request ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            reply(request, "<form id='loginForm'><input name='execution' value='state'></form>")
        }
        val login = async { fixture.repo.login("u", "p", NetworkMode.DIRECT) }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            fixture.repo.logout()
            release.countDown()
            val result = runCatching { login.await() }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { fixture.repo.loadWeek(1, LocalDate.of(2026, 9, 7)) }.exceptionOrNull() is IclassException)
        } finally { release.countDown(); fixture.repo.logout() }
    }

    @Test fun cookieJarRespectsScopeReplacesValuesAndClearsOnLogout() {
        val jar = IclassRepository.SessionCookies()
        val sso = "https://sso.buaa.edu.cn/login".toHttpUrl()
        jar.saveFromResponse(sso, listOf(Cookie.parse(sso, "session=one; Path=/; Secure; HttpOnly")!!))
        assertEquals("one", jar.loadForRequest(sso).single().value)
        assertTrue(jar.loadForRequest("https://iclass.buaa.edu.cn:8347/".toHttpUrl()).isEmpty())
        jar.saveFromResponse(sso, listOf(Cookie.parse(sso, "session=two; Path=/; Secure; HttpOnly")!!))
        assertEquals("two", jar.loadForRequest(sso).single().value)
        jar.clear()
        assertTrue(jar.loadForRequest(sso).isEmpty())
    }

    @Test fun logoutDuringTimetableFetchCancelsAllDaysInsteadOfReturningStaleData() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture { request ->
            if (request.url.encodedPath.endsWith("get_stu_course_sched.action")) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                reply(request, """{"STATUS":0,"result":[{"id":"stale","courseName":"旧账号课程"}]}""")
            } else null
        }
        fixture.repo.login("u", "p", NetworkMode.DIRECT)
        val start = LocalDate.of(2026, 9, 7)
        val week = async { fixture.repo.linkAttendance(WeekSchedule(1, start,
            (0L..6L).map { DaySchedule(start.plusDays(it), emptyList()) }, academic = AcademicComparison(AcademicStatus.VERIFIED))) }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            fixture.repo.logout()
            release.countDown()
            assertTrue(runCatching { week.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { fixture.repo.signCourses(listOf(Course("stale", "课程"))) }.exceptionOrNull() is IclassException)
        } finally { release.countDown(); fixture.repo.logout() }
    }

    companion object {
        private val novemberSchoolClock = Clock.fixed(Instant.parse("2026-11-08T16:30:00Z"), ZoneId.of("Asia/Shanghai"))

        private fun reply(request: Request, body: String, code: Int = 200, location: String? = null): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Offline test")
                .body(body.toResponseBody("text/plain; charset=utf-8".toMediaType()))
                .apply { if (location != null) header("Location", location) }.build()
    }
}
