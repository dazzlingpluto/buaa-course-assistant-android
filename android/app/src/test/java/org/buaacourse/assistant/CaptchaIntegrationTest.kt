package org.buaacourse.assistant

import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CaptchaIntegrationTest {
    private class Fixture(val mode: NetworkMode) {
        var pages = 0
        var reject = false
        var invalidImage = false
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        fun page(): String {
            pages++
            return """<form id='loginForm'><input name='execution' value='execution-$pages'><input name='csrf' value='csrf-$pages'></form>
                <script>config.captcha = { type: 'image', id: 'image-$pages' };</script>"""
        }
        val repo = IclassRepository(baseClient = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); requests += r
            val body = when {
                r.url.encodedPath.endsWith("/captcha") -> return@addInterceptor response(r,
                    if (invalidImage) "<html>Error</html>".toByteArray() else png(), "image/png")
                r.url.encodedPath.endsWith("/login") && !r.url.encodedPath.endsWith("/api/login") && r.method == "GET" -> page()
                r.url.encodedPath.endsWith("/login") && !r.url.encodedPath.endsWith("/api/login") && r.method == "POST" -> if (reject) page() else "<html>Welcome</html>"
                r.url.encodedPath.endsWith("/api/login") -> "UC activated"
                r.url.encodedPath.endsWith("/api/uc/status") -> """{"code":0,"data":{"schoolid":"fixture-student","name":"演示用户"}}"""
                else -> error("Unexpected offline request")
            }
            response(r, body.toByteArray(), "text/html; charset=utf-8")
        }.build())
    }
    @Test fun captchaChallengeStopsBeforePasswordSubmissionAndManualInputCompletesBothModes() = runBlocking {
        for (mode in NetworkMode.entries) {
            val f = Fixture(mode)
            val challenge = runCatching { f.repo.login("fixture-student", "fixture-password", mode) }.exceptionOrNull()
            assertTrue(challenge is CaptchaRequiredException)
            assertTrue((challenge as CaptchaRequiredException).captcha.imageBytes.contentEquals(png()))
            assertTrue(f.requests.none { it.method == "POST" })
            assertEquals("fixture-student", f.repo.loginWithCaptcha("fixture-student", "fixture-password", mode, "manual-test-input").userId)
            assertEquals(1, f.pages)
            val form = f.requests.single { it.method == "POST" }.body as FormBody
            val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
            assertEquals("manual-test-input", fields["captcha"])
            assertEquals("manual-test-input", fields["captchaResponse"])
            assertEquals("execution-1", fields["execution"])
            assertEquals("csrf-1", fields["csrf"])
            f.requests.filter { it.url.encodedPath.endsWith("/captcha") }.forEach { r ->
                assertEquals("image-1", r.url.queryParameter("captchaId")); assertNull(r.body)
                assertFalse(r.url.toString().contains("fixture-password"))
            }
            if (mode == NetworkMode.VPN) assertTrue(f.requests.all { it.url.host == "d.buaa.edu.cn" && it.url.scheme == "https" })
            f.repo.logout()
        }
    }
    @Test fun refreshReplacesExecutionAndImageWithoutSubmittingPassword() = runBlocking {
        val f = Fixture(NetworkMode.VPN)
        runCatching { f.repo.login("fixture-student", "fixture-password", f.mode) }
        f.repo.refreshLoginCaptcha()
        assertEquals(2, f.pages)
        assertTrue(f.requests.none { it.method == "POST" })
        f.repo.loginWithCaptcha("fixture-student", "fixture-password", f.mode, "manual-test-input")
        val form = f.requests.single { it.method == "POST" }.body as FormBody
        assertEquals("execution-2", (0 until form.size).associate { form.name(it) to form.value(it) }["execution"])
        f.repo.logout()
    }
    @Test fun rejectedCaptchaReturnsNewChallengeAndNeverRetriesAutomatically() = runBlocking {
        val f = Fixture(NetworkMode.DIRECT)
        runCatching { f.repo.login("fixture-student", "fixture-password", f.mode) }
        f.reject = true
        assertTrue(runCatching { f.repo.loginWithCaptcha("fixture-student", "fixture-password", f.mode, "wrong-input") }.exceptionOrNull() is CaptchaRequiredException)
        assertEquals(1, f.requests.count { it.method == "POST" })
        assertEquals(2, f.pages)
        f.reject = false
        f.repo.loginWithCaptcha("fixture-student", "fixture-password", f.mode, "manual-test-input")
        assertEquals(2, f.requests.count { it.method == "POST" })
        f.repo.logout()
    }
    @Test fun logoutInvalidatesCaptchaAndHtmlCannotBeUsedAsImage() = runBlocking {
        val f = Fixture(NetworkMode.DIRECT)
        runCatching { f.repo.login("fixture-student", "fixture-password", f.mode) }
        f.repo.logout()
        assertTrue(runCatching { f.repo.refreshLoginCaptcha() }.exceptionOrNull() is IclassException)
        f.invalidImage = true
        val error = runCatching { f.repo.login("fixture-student", "fixture-password", f.mode) }.exceptionOrNull()
        assertTrue(error is IclassException && error !is CaptchaRequiredException)
        f.repo.logout()
    }
    companion object {
        fun png(): ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=")
        private fun response(r: Request, data: ByteArray, type: String) = Response.Builder().request(r)
            .protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture").body(data.toResponseBody(type.toMediaType())).build()
    }
}
