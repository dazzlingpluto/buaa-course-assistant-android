package org.buaacourse.assistant

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Credentials live only in memory and the encrypted store, never saved instance state. */
class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: AssistantController
    val state: StateFlow<AssistantUiState> get() = controller.state

    init {
        var logSink: ((String, String) -> Unit)? = null
        val repository = IclassRepository(logger = { message, level -> logSink?.invoke(message, level) })
        controller = AssistantController(repository, CredentialStore(application),
            AndroidAssistantPreferences(application), viewModelScope)
        logSink = controller::appendLog
    }

    fun setUsername(value: String) = controller.setUsername(value)
    fun setPassword(value: String) = controller.setPassword(value)
    fun setMode(value: NetworkMode) = controller.setMode(value)
    fun setAutoLogin(value: Boolean) = controller.setAutoLogin(value)
    fun setCaptcha(value: String) = controller.setCaptcha(value)
    fun refreshCaptcha() = controller.refreshCaptcha()
    fun toggleLanguage() = controller.toggleLanguage()
    fun setRemember(value: Boolean) = controller.setRemember(value)
    fun clearSavedCredentials() = controller.clearSavedCredentials()
    fun login() = controller.login()
    fun logout() = controller.logout()
    fun selectWeek(value: Int) = controller.selectWeek(value)
    fun selectAcademicTerm(value: String) = controller.selectAcademicTerm(value)
    fun currentWeek() = controller.currentWeek()
    fun refresh() = controller.refresh()
    fun signCourse(value: Course) = controller.signCourse(value)
    fun dismissMessage() = controller.dismissMessage()
    fun dismissError() = controller.dismissError()
    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}

interface AssistantPreferences {
    fun language(): AppLanguage = AppLanguage.ZH
    fun semesterStart(): LocalDate = LocalDate.of(2026, 9, 7)
    fun saveLanguage(value: AppLanguage) {}
    fun saveSemesterStart(value: LocalDate) {}
}

private class AndroidAssistantPreferences(context: Context) : AssistantPreferences {
    private val prefs = context.getSharedPreferences("assistant-ui", Context.MODE_PRIVATE)
    override fun language() = runCatching { AppLanguage.valueOf(prefs.getString("language", "ZH")!!) }
        .getOrDefault(AppLanguage.ZH)
    override fun saveLanguage(value: AppLanguage) {
        prefs.edit { putString("language", value.name) }
    }
}

/** Platform-independent state machine: one user operation at a time, cancellation on close. */
internal class AssistantController(
    private val repository: CourseRepository,
    private val credentials: SavedCredentialsStore,
    private val preferences: AssistantPreferences,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val mutableState = MutableStateFlow(AssistantUiState(
        language = runCatching { preferences.language() }.getOrDefault(AppLanguage.ZH),
        // This release uses the announced 2026–2027 semester start, even after an upgrade.
        semesterStart = LocalDate.of(2026, 9, 7),
    ))
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var closed = false
    private val preferenceWrites = Mutex()

    init {
        operation = scope.launch {
            try {
                val saved = restoreSavedCredentials()
                if (saved?.autoLogin == true) {
                    mutableState.update { it.copy(busyMessage = text("正在自动登录…", "Signing in automatically…")) }
                    authenticate(state.value)
                } else appendLog(text("就绪。", "Ready."), "info")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { reportOperationError(error)
            } finally { mutableState.update { it.copy(busy = false, busyMessage = "") } }
        }
    }

    fun setUsername(value: String) {
        if (canEditLogin()) {
            if (value != state.value.username) resetCaptcha()
            mutableState.update { it.copy(username = value.take(256), error = null) }
        }
    }
    fun setPassword(value: String) {
        if (canEditLogin()) mutableState.update { it.copy(password = value.take(4096), error = null) }
    }
    fun setMode(value: NetworkMode) {
        if (canEditLogin()) {
            if (value != state.value.mode) resetCaptcha()
            mutableState.update { it.copy(mode = value, error = null) }
        }
    }
    fun setCaptcha(value: String) {
        if (canEditLogin()) mutableState.update { it.copy(captchaInput = value.take(128), error = null) }
    }
    private fun resetCaptcha() {
        if (state.value.captcha != null) repository.logout()
        mutableState.update { it.copy(captcha = null, captchaInput = "") }
    }
    fun refreshCaptcha() {
        if (!canEditLogin() || state.value.captcha == null) return
        runOperation(text("正在刷新验证码…", "Refreshing verification image…")) {
            mutableState.update { it.copy(captchaInput = "") }
            val challenge = withContext(io) { repository.refreshLoginCaptcha() }
            mutableState.update { it.copy(captcha = challenge) }
        }
    }
    fun setAutoLogin(value: Boolean) {
        if (!canEditLogin()) return
        if (value) {
            // Enabling is only persisted after the current credentials authenticate successfully.
            mutableState.update { it.copy(autoLogin = true, remember = true) }
        } else if (state.value.hasSavedCredentials) {
            runOperation(text("正在关闭自动登录…", "Turning off automatic login…")) {
                disableSavedAutoLogin()
                mutableState.update { it.copy(autoLogin = false) }
            }
        } else mutableState.update { it.copy(autoLogin = false) }
    }
    fun toggleLanguage() {
        if (closed) return
        val next = if (state.value.language == AppLanguage.ZH) AppLanguage.EN else AppLanguage.ZH
        mutableState.update { it.copy(language = next) }
        persist { preferences.saveLanguage(next) }
    }

    fun setRemember(value: Boolean) {
        if (!canEditLogin()) return
        if (value) mutableState.update { it.copy(remember = true) } else clearSaved(false)
    }

    fun clearSavedCredentials() {
        if (canEditLogin()) clearSaved(true)
    }

    private fun clearSaved(clearInputs: Boolean) = runOperation(text("正在清除…", "Removing saved details…")) {
        try {
            withContext(io) { credentials.clear() }
            if (clearInputs) resetCaptcha()
            mutableState.update { it.copy(
                remember = false, autoLogin = false, hasSavedCredentials = false,
                username = if (clearInputs) "" else it.username,
                password = if (clearInputs) "" else it.password,
            ) }
            message(text("已清除保存的账号密码。", "Saved credentials removed."))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // Failed deletion must not visually claim success or reset the previous selection.
            message(text("未能清除已保存信息，请重试。", "Could not remove saved details. Please try again."))
        }
    }

    fun login() {
        if (!canEditLogin()) return
        val snapshot = state.value
        val username = snapshot.username.trim()
        val password = snapshot.password
        if (username.isBlank() || password.isEmpty()) {
            mutableState.update { it.copy(error = text("请输入账号和密码。", "Enter your username and password.")) }
            return
        }
        if (snapshot.captcha != null && snapshot.captchaInput.isBlank()) {
            mutableState.update { it.copy(error = text("请输入验证码。", "Enter the verification code.")) }
            return
        }
        runOperation(text("正在登录…", "Signing in…")) {
            authenticate(snapshot)
        }
    }

    private suspend fun authenticate(snapshot: AssistantUiState) {
        val username = snapshot.username.trim()
        val password = snapshot.password
        appendLog(text("正在登录…", "Signing in…"), "info")
        val user = try {
            withContext(io) {
                repository.loginWithCaptcha(username, password, snapshot.mode, snapshot.captchaInput)
            }
        } catch (error: Exception) {
            if (error is CaptchaRequiredException) {
                mutableState.update { it.copy(captcha = error.captcha, captchaInput = "", error = error.message) }
                appendLog(text("统一认证需要验证码，请输入后继续。", "Verification required. Enter the code to continue."), "info")
                return
            }
            repository.logout()
            mutableState.update { it.copy(captcha = null, captchaInput = "") }
            throw error
        }
        mutableState.update { it.copy(user = user, username = username, password = "", captcha = null, captchaInput = "", studentType = StudentType.AUTO, error = null) }
        appendLog(text("登录成功。", "Signed in."), "success")
        if (snapshot.remember) {
            try {
                withContext(io) { credentials.save(SavedCredentials(username, password, snapshot.mode,
                    StudentType.AUTO, snapshot.autoLogin)) }
                mutableState.update { it.copy(hasSavedCredentials = true) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                message(text("登录成功，但账号密码与自动登录设置未能保存，请稍后重新登录保存。",
                    "Signed in, but credentials and automatic login settings could not be saved. Sign in again later to save them."))
            }
        }
        loadWeekInternal(0)
    }

    fun logout() {
        if (state.value.user == null || state.value.busy) return
        runOperation(text("正在退出…", "Signing out…")) {
            // Persist opt-out before ending this session, so a restart cannot undo explicit logout.
            disableSavedAutoLogin()
            withContext(io) { repository.logout() }
            mutableState.update { it.copy(user = null, schedule = null, password = "", studentType = StudentType.AUTO,
                autoLogin = false, error = null, message = null) }
            restoreSavedCredentials()
            appendLog(text("已退出登录。", "Signed out."), "info")
        }
    }

    private suspend fun disableSavedAutoLogin() {
        try {
            withContext(io) {
                val saved = credentials.read()
                if (saved?.autoLogin == true) credentials.save(SavedCredentials(saved.username, saved.password,
                    saved.mode, StudentType.AUTO, autoLogin = false))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            try {
                // If an encrypted record cannot be updated, remove it rather than retain consent.
                withContext(io) { credentials.clear() }
                mutableState.update { it.copy(remember = false, hasSavedCredentials = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                throw IllegalStateException(text("未能关闭已保存的自动登录，请重试。",
                    "Could not turn off saved automatic login. Please try again."))
            }
        }
    }

    fun selectWeek(value: Int) {
        if (value <= 0) return
        val knownWeeks = state.value.schedule?.weeks.orEmpty()
        if (knownWeeks.isNotEmpty() && knownWeeks.none { it.number == value }) return
        requestWeek(value)
    }
    private fun requestWeek(value: Int) {
        if (state.value.user == null || state.value.busy) return
        runOperation(text("正在加载课表…", "Loading schedule…")) { loadWeekInternal(value) }
    }
    fun currentWeek() = requestWeek(0)
    fun refresh() = selectWeek(state.value.week)

    fun selectAcademicTerm(value: String) {
        if (state.value.user == null || state.value.busy || value.isBlank()) return
        runOperation(text("正在核对教务课表…", "Checking the academic timetable…")) {
            // A failed term change must not leave the previous term's courses visible.
            mutableState.update { it.copy(schedule = null) }
            withContext(io) { repository.selectAcademicTerm(value.trim()) }
            loadWeekInternal(0)
        }
    }

    fun signCourse(value: Course) {
        if (state.value.user == null || state.value.busy) return
        val course = state.value.schedule?.days?.flatMap { it.courses }?.flatMap {
            if (it.attendanceSessions.isEmpty()) listOf(it) else it.attendanceSessions
        }?.find {
            it.id == value.id && it.classBeginTime == value.classBeginTime &&
                it.courseNum == value.courseNum && it.courseName == value.courseName &&
                it.classEndTime == value.classEndTime && it.classroomName == value.classroomName &&
                it.teachBuildName == value.teachBuildName && it.storeyName == value.storeyName
        } ?: return
        if (!course.attendanceAvailable) {
            message(text("该课程暂无有效的签到关联，暂不能签到，请刷新课表。", "This class has no current attendance link. Refresh the timetable before signing."))
            return
        }
        if (course.signed) {
            message(text("该课程已签到。", "This class is already signed."))
            return
        }
        sign(course)
    }

    private fun sign(course: Course) = runOperation(text("正在签到…", "Submitting attendance…")) {
        val result = withContext(io) { repository.signCourses(listOf(course)) }
        result.items.forEach { item ->
            val name = item.name.ifBlank { text("未知课程", "Unknown class") }
            val (fallback, level) = when (item.outcome) {
                SignOutcome.SUCCESS -> text("签到成功", "Attendance submitted") to "success"
                SignOutcome.SKIPPED -> text("该课程已签到", "Already signed") to "info"
                SignOutcome.FAILED -> text("签到失败，请核对出勤情况及官方记录", "Attendance failed; check your attendance and official records") to "warning"
            }
            appendLog("$name：${item.message.ifBlank { fallback }}", level)
        }
        val signedIds = result.items.filter { it.outcome != SignOutcome.FAILED }.map { it.id }.toSet()
        // Keep the submitted attendance result while the refresh runs, but a
        // failed refresh must not present an old academic comparison as current.
        mutableState.update { it.copy(schedule = it.schedule?.applyKnownResults(signedIds)?.withoutVerifiedAttendance()) }
        val summary = text("签到完成：${result.success} 成功，${result.skipped} 已签到，${result.failed} 失败。",
            "Attendance: ${result.success} succeeded, ${result.skipped} already signed, ${result.failed} failed.")
        message(summary)
        appendLog(summary, if (result.failed > 0) "warning" else "success")
        try {
            val current = state.value
            loadWeekInternal(current.week, signedIds)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            val warning = text("签到已处理，但课表刷新失败，请手动刷新。", "Attendance processed, but the schedule could not refresh. Please refresh again.")
            mutableState.update { it.copy(error = warning) }
            appendLog(warning, "warning")
        }
    }

    private suspend fun loadWeekInternal(week: Int, signedIds: Set<String> = emptySet()) {
        val start = state.value.semesterStart
        val knownIds = signedIds + if (week == state.value.week) state.value.schedule?.days.orEmpty().flatMap { it.courses }
            .flatMap { if (it.attendanceSessions.isEmpty()) it.completedIds else it.attendanceSessions.flatMap { slot -> slot.completedIds } }.toSet() else emptySet()
        mutableState.update { it.copy(
            week = if (week > 0) week else it.week,
            // Retaining visible classes after a same-week refresh failure is
            // useful; the previous academic comparison is no longer current.
            schedule = if (week > 0 && it.week == week && it.schedule?.semesterStart == start) it.schedule?.withoutVerifiedAttendance() else null,
        ) }
        val schedule = withContext(io) { repository.loadWeek(week, start) }
        val official = schedule.withoutVerifiedAttendance(clearComparison = false)
        // Publish the official timetable before any optional iClass request.
        mutableState.update { it.copy(schedule = official, week = official.week,
            semesterStart = official.semesterStart, studentType = official.studentType,
            busyMessage = text("正在关联签到…", "Linking attendance…")) }
        reportPartialFailure(schedule)
        val linked = try {
            withContext(io) { repository.linkAttendance(schedule) }.applyKnownResults(knownIds)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            val warning = text("正式课表已加载，签到服务暂不可用；课程仍可查看，请稍后刷新。",
                "The official timetable is loaded. Attendance is temporarily unavailable; refresh later.")
            appendLog(warning, "warning")
            official.copy(attendanceMessage = warning)
        }
        mutableState.update { it.copy(schedule = linked) }
        reportAcademicComparison(linked)
        appendLog(text("第 ${schedule.week} 周课表加载完成，共 ${schedule.total} 门课程。",
            "Week ${schedule.week} loaded: ${schedule.total} classes."), "success")
    }

    private fun reportAcademicComparison(schedule: WeekSchedule) {
        val comparison = schedule.academic
        val (summary, level) = when (comparison?.status) {
            AcademicStatus.VERIFIED -> text(
                "已加载本周正式课表，共 ${schedule.total} 门课程；${comparison.uncertainCount} 门暂未关联 iClass 签到，可查看详情。",
                "Official timetable loaded: ${schedule.total} classes this week; ${comparison.uncertainCount} have no iClass attendance link and remain viewable.",
            ) to "info"
            AcademicStatus.SELECT_TERM -> text("请选择教务学期以加载正式课表。",
                "Select an academic term to load the official timetable.") to "warning"
            AcademicStatus.UNMATCHED -> text("尚未建立有效的正式课表签到关联，当前课程仅供查看，请刷新重试。",
                "No valid official timetable attendance links are available. Classes remain view-only; please refresh.") to "warning"
            AcademicStatus.UNAVAILABLE, null -> text("正式课表暂不可用，请刷新重试；已显示的课程仅供查看。",
                "The official timetable is unavailable. Previously loaded classes remain view-only; please refresh.") to "warning"
        }
        // The schedule card owns detailed comparison errors; avoid a second snackbar/error banner.
        appendLog(summary, level)
    }

    private fun WeekSchedule.withoutVerifiedAttendance(clearComparison: Boolean = true) = copy(
        academic = if (clearComparison) null else academic?.copy(verifiedScheduleIds = emptySet(), uncertainCount = total),
        days = days.map { day -> day.copy(courses = day.courses.map { it.copy(attendanceAvailable = false, attendanceNotRequired = false, attendanceSessions = it.attendanceSessions.map { slot -> slot.copy(attendanceAvailable = false) }) }) },
        unscheduled = unscheduled.map { it.copy(attendanceAvailable = false) },
    )

    private fun WeekSchedule.applyKnownResults(ids: Set<String>) = copy(days = days.map { day ->
        day.copy(courses = day.courses.map { course ->
            course.applyAttendance(ids)
        })
    })

    private fun reportPartialFailure(schedule: WeekSchedule) {
        val count = schedule.days.count { it.failed }
        if (count > 0) {
            val warning = text("有 $count 天课程加载失败，已显示其余结果，请刷新重试。", "$count days could not load. Other days are shown; please refresh.")
            mutableState.update { it.copy(error = warning) }
            appendLog(warning, "warning")
        }
    }

    private suspend fun restoreSavedCredentials(): SavedCredentials? {
        try {
            val saved = withContext(io) { credentials.read() }
            if (saved != null) {
                mutableState.update { it.copy(username = saved.username, password = saved.password,
                    mode = saved.mode, studentType = StudentType.AUTO, autoLogin = saved.autoLogin,
                    remember = true, hasSavedCredentials = true) }
            } else {
                mutableState.update { it.copy(password = "", remember = false, autoLogin = false, hasSavedCredentials = false) }
            }
            return saved
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(password = "", remember = false, autoLogin = false, hasSavedCredentials = true) }
            message(text("无法读取已保存信息，请重新输入账号密码；也可清除已保存信息后重试。",
                "Could not read saved details. Enter your credentials again, or clear the saved details."))
            return null
        }
    }

    private fun canEditLogin() = !closed && !state.value.busy && state.value.user == null
    private fun text(zh: String, en: String) = if (state.value.language == AppLanguage.ZH) zh else en
    private fun message(value: String) { mutableState.update { it.copy(message = value) } }
    fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    fun dismissError() { mutableState.update { it.copy(error = null) } }

    fun appendLog(value: String, level: String = "info") {
        if (closed) return
        val safe = redact(value)
        mutableState.update { it.copy(logs = (listOf(AppLog(safe, level)) + it.logs).take(200)) }
    }

    private fun redact(value: String): String {
        var result = value
        val current = state.value
        if (current.captchaInput.isNotEmpty()) result = result.replace(current.captchaInput, "[redacted]")
        if (current.password.isNotEmpty()) result = result.replace(current.password, "[redacted]")
        if (current.username.isNotEmpty()) result = result.replace(current.username, "[redacted]")
        result = result.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[service]")
        return result.take(500)
    }

    private fun runOperation(label: String, block: suspend () -> Unit) {
        if (closed || state.value.busy) return
        mutableState.update { it.copy(busy = true, busyMessage = label, error = null) }
        operation = scope.launch {
            try { block()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { reportOperationError(error)
            } finally { mutableState.update { it.copy(busy = false, busyMessage = "") } }
        }
    }

    private fun reportOperationError(error: Exception) {
        val detail = redact(error.message.orEmpty()).ifBlank { text("操作失败，请检查网络后重试。", "Operation failed. Check your connection and try again.") }
        mutableState.update { it.copy(error = detail) }
        appendLog(detail, "error")
    }

    private fun persist(block: () -> Unit) {
        scope.launch {
            try { preferenceWrites.withLock { withContext(io) { block() } }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { message(text("设置未能保存，下次打开可能恢复默认值。", "Settings could not be saved and may reset next time.")) }
        }
    }

    fun close() {
        closed = true
        operation?.cancel()
        repository.logout()
        mutableState.update { it.copy(username = "", password = "", captcha = null, captchaInput = "", user = null, schedule = null) }
    }
}
