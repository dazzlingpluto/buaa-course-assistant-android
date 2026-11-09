package org.buaacourse.assistant

import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptchaControllerTest {
    private class Store : SavedCredentialsStore {
        var saved: SavedCredentials? = SavedCredentials("demo-user", "demo-password", NetworkMode.VPN, autoLogin = true)
        var writes = 0
        override fun read() = saved
        override fun save(credentials: SavedCredentials) { saved = credentials; writes++ }
        override fun clear() { saved = null }
    }
    private class Repo : CourseRepository {
        var calls = 0
        var logouts = 0
        var refreshes = 0
        override suspend fun login(username: String, password: String, mode: NetworkMode): UserSession = error("Use challenge-aware login")
        override suspend fun loginWithCaptcha(username: String, password: String, mode: NetworkMode, captcha: String): UserSession {
            calls++
            if (captcha.isBlank()) throw CaptchaRequiredException(LoginCaptcha(CaptchaIntegrationTest.png()))
            return UserSession("demo", "演示用户")
        }
        override suspend fun refreshLoginCaptcha(): LoginCaptcha { refreshes++; return LoginCaptcha(CaptchaIntegrationTest.png()) }
        override suspend fun loadWeek(week: Int, semesterStart: LocalDate) = WeekSchedule(1, semesterStart,
            (0L..6L).map { DaySchedule(semesterStart.plusDays(it), emptyList()) })
        override suspend fun signCourses(courses: List<Course>) = error("No attendance should run")
        override fun logout() { logouts++ }
    }
    @Test fun automaticLoginPausesWithoutLoggingOutRetryingOrSavingChallenge() = runTest {
        val repo = Repo(); val store = Store()
        val controller = AssistantController(repo, store, object : AssistantPreferences {}, this, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(1, repo.calls); assertEquals(0, repo.logouts); assertEquals(0, store.writes)
        assertFalse(controller.state.value.busy); assertNull(controller.state.value.user)
        assertNotNull(controller.state.value.captcha); assertEquals("demo-password", controller.state.value.password)
        assertEquals("统一认证需要验证码，请输入后继续。", controller.state.value.logs.first().message)
        controller.login(); advanceUntilIdle()
        assertEquals(1, repo.calls) // Blank captcha never reaches network.
        assertEquals("请输入验证码。", controller.state.value.logs.first().message)
        controller.setCaptcha("manual-demo-code"); controller.login(); advanceUntilIdle()
        assertEquals(2, repo.calls); assertEquals(1, store.writes)
        assertNotNull(controller.state.value.user); assertNull(controller.state.value.captcha)
        assertEquals("", controller.state.value.captchaInput)
        assertFalse(String(SavedCredentialsCodec.encode(store.saved!!)).contains("manual-demo-code"))
        controller.close()
    }
    @Test fun refreshClearsOldInputAndSwitchingNetworkInvalidatesChallenge() = runTest {
        val repo = Repo(); val controller = AssistantController(repo, Store(), object : AssistantPreferences {}, this, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val old = controller.state.value.captcha
        controller.setCaptcha("old-input"); controller.refreshCaptcha(); advanceUntilIdle()
        assertEquals(1, repo.refreshes); assertEquals("", controller.state.value.captchaInput)
        assertNotSame(old, controller.state.value.captcha)
        controller.setMode(NetworkMode.DIRECT)
        assertNull(controller.state.value.captcha); assertEquals(1, repo.logouts)
        controller.close()
    }
    @Test fun changingAccountOrClearingInputsCannotReusePreviousChallenge() = runTest {
        val repo = Repo(); val controller = AssistantController(repo, Store(), object : AssistantPreferences {}, this, StandardTestDispatcher(testScheduler))
        advanceUntilIdle(); controller.setUsername("other-user")
        assertNull(controller.state.value.captcha); assertEquals(1, repo.logouts)
        controller.login(); advanceUntilIdle(); assertNotNull(controller.state.value.captcha)
        controller.clearSavedCredentials(); advanceUntilIdle()
        assertNull(controller.state.value.captcha); assertEquals("", controller.state.value.password)
        assertEquals("", controller.state.value.username)
        controller.close()
    }
}
