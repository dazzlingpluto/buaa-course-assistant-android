package org.buaacourse.assistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IclassProtocolTest {
    @Test fun webVpnEncryptionMatchesKnownSchoolEndpointAndPreservesQuery() {
        val endpoints = IclassProtocol.endpoints(NetworkMode.VPN)
        assertEquals("https://d.buaa.edu.cn/http-8081/" +
            "77726476706e69737468656265737421f9f44d9d342326526b0988e29d51367ba018" +
            "/app/course/stu_scan_sign.action", endpoints.sign.toString())
        assertTrue(endpoints.myCenter.toString().contains("/https-8346/"))
        assertEquals("jumpMyCenter", endpoints.myCenter.queryParameter("type"))
        val converted = IclassProtocol.toWebVpnUrl("https://iclass.buaa.edu.cn:8346/?type=jumpMyCenter#section")
        assertEquals("section", converted.fragment)
        assertTrue(IclassProtocol.isAuthenticationTarget(endpoints.login))
        assertEquals("https://d.buaa.edu.cn/login", IclassProtocol.toWebVpnUrl("https://d.buaa.edu.cn/login").toString())
    }

    @Test fun loginTokenPreservesLiteralAndEscapedBase64Plus() {
        assertEquals("abc+def/ghi=", IclassProtocol.extractLoginName("?loginName=abc%2Bdef%2Fghi%3D&type=jump"))
        assertEquals("abc+def=", IclassProtocol.extractLoginName("location='/?loginName=abc+def%3D'"))
        assertNull(IclassProtocol.extractLoginName("loginName=%NOT_VALID"))
    }

    @Test fun formPreservesHiddenStateAndOverridesCredentials() {
        val html = """<form id="loginForm" action="/login?service=x">
            <input name="execution" value="hidden-state"><input name="csrf" value="csrf-state">
            <input name="username" value="old"><input name="password" value="old">
            <input type="checkbox" name="unchecked" value="1">
            <input type="checkbox" name="checked" checked value="yes">
            <input name="disabled" disabled value="no"><input type="submit" name="submit" value="other">
            </form>"""
        val parsed = IclassProtocol.parseLoginForm(html, "https://sso.buaa.edu.cn/login".toHttpUrl(), "  user  ", " secret ")
        assertEquals("hidden-state", parsed.fields["execution"])
        assertEquals("csrf-state", parsed.fields["csrf"])
        assertEquals("user", parsed.fields["username"])
        assertEquals(" secret ", parsed.fields["password"])
        assertEquals("yes", parsed.fields["checked"])
        assertFalse(parsed.fields.containsKey("unchecked"))
        assertFalse(parsed.fields.containsKey("disabled"))
        assertEquals("submit", parsed.fields["_eventId"])
        assertEquals("https://sso.buaa.edu.cn/login?service=x", parsed.action.toString())
    }

    @Test fun refusesCredentialExfiltrationAndTlsDowngrade() {
        val page = "https://sso.buaa.edu.cn/login".toHttpUrl()
        for (action in listOf("https://evil.example/collect", "https://sso.buaa.edu.cn.evil.example/", "http://sso.buaa.edu.cn/login", "https://iclass.buaa.edu.cn:8347/collect", "https://gsmis.buaa.edu.cn/collect", "https://d.buaa.edu.cn/https/evil/collect")) {
            val error = runCatching { IclassProtocol.parseLoginForm("<form action='$action'><input name='execution' value='abc'></form>", page, "user", "password") }.exceptionOrNull()
            assertTrue(action, error is IclassException)
            assertFalse(error!!.message.orEmpty().contains("password"))
        }
        assertFalse(IclassProtocol.isTrustedUrl("http://iclass.buaa.edu.cn:8081/arbitrary".toHttpUrl()))
        assertTrue(IclassProtocol.isTrustedUrl(IclassProtocol.endpoints(NetworkMode.DIRECT).sign))
    }

    @Test fun captchaAndUnsuccessfulCasPageAreExplicitFailures() {
        assertNull(CasLoginProtocol.captcha("<script>config.captcha = {};</script>"))
        assertNull(CasLoginProtocol.captcha("<script>config.captcha = { enabled: false, id: 'old-image' };</script>"))
        assertTrue(runCatching {
            IclassProtocol.parseLoginForm("config.captcha = true", "https://sso.buaa.edu.cn/login".toHttpUrl(), "u", "p")
        }.exceptionOrNull()!!.message!!.contains("验证码"))
        val error = runCatching { IclassProtocol.validateLoginResponse("<form id='fm1'><input name='execution' value='secret'></form>") }.exceptionOrNull()
        assertTrue(error is IclassException)
        assertFalse(error!!.message!!.contains("secret"))
    }

    @Test fun courseMergeRetainsEveryTeacherScheduleAndSignedStatus() {
        val list = IclassProtocol.parseCourses(JSONArray("""[
            {"id":"a","courseName":"数学","courseNum":"M1","classBeginTime":"08:00","classroomName":"101","teacherName":"甲","signStatus":0},
            {"id":"b","courseName":"数学","courseNum":"M1","classBeginTime":"08:00","classroomName":"101","teacherName":"乙","signStatus":"1"},
            {"id":"c","courseName":"物理","classBeginTime":"10:00","classroomName":"202","signStatus":null},
            {"id":"d","courseName":"化学","classBeginTime":"10:00","classroomName":"202","signStatus":0}
        ]"""))
        assertEquals(3, list.size)
        assertEquals(listOf("a", "b"), list[0].scheduleIds)
        assertEquals(listOf("甲", "乙"), list[0].teachers)
        assertFalse(list[0].signed)
        assertEquals(setOf("b"), list[0].completedIds)
        assertFalse(list[1].signed)
    }

    @Test fun explicitStatusWinsOverAmbiguousSuccessWords() {
        assertEquals(SignOutcome.SUCCESS, IclassProtocol.classifySignResponse(JSONObject("""{"STATUS":0}""")).outcome)
        assertEquals(SignOutcome.SKIPPED, IclassProtocol.classifySignResponse(JSONObject("""{"STATUS":1,"ERRMSG":"该课程已签到"}""")).outcome)
        assertEquals(SignOutcome.FAILED, IclassProtocol.classifySignResponse(JSONObject("""{"STATUS":1,"ERRMSG":"签到未成功"}""")).outcome)
        assertEquals(SignOutcome.FAILED, IclassProtocol.classifySignResponse(JSONObject("""{"message":"SUCCESS"}""")).outcome)
        assertEquals("", IclassProtocol.value(JSONObject("""{"id":null}"""), "id"))
        assertEquals("123", IclassProtocol.value(JSONObject("""{"id":123}"""), "id"))
    }

    @Test fun courseMergeKeepsDifferentOrMissingEndTimesSeparate() {
        val courses = IclassProtocol.parseCourses(JSONArray("""[
            {"id":"short","courseName":"数学","courseNum":"M1","classBeginTime":"08:00","classEndTime":"09:35","classroomName":"101"},
            {"id":"long","courseName":"数学","courseNum":"M1","classBeginTime":"08:00","classEndTime":"10:35","classroomName":"101"},
            {"id":"unknown","courseName":"数学","courseNum":"M1","classBeginTime":"08:00","classroomName":"101"},
            {"id":"second-teacher","courseName":"数学","courseNum":"M1","classBeginTime":"08:00","classEndTime":"09:35","classroomName":"101"}
        ]"""))
        assertEquals(3, courses.size)
        assertEquals(listOf("short", "second-teacher"), courses[0].scheduleIds)
        assertEquals("09:35", courses[0].classEndTime)
        assertEquals(listOf("long"), courses[1].scheduleIds)
        assertEquals("10:35", courses[1].classEndTime)
        assertEquals(listOf("unknown"), courses[2].scheduleIds)
        assertEquals("", courses[2].classEndTime)
    }
}
