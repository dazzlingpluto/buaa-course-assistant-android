package org.buaacourse.assistant

import java.time.LocalDate
import java.time.LocalTime

enum class NetworkMode { DIRECT, VPN }
enum class StudentType { AUTO, UNDERGRADUATE, GRADUATE }
enum class AppLanguage { ZH, EN }
enum class SignOutcome { SUCCESS, SKIPPED, FAILED }
enum class AcademicStatus { VERIFIED, SELECT_TERM, UNAVAILABLE, UNMATCHED }

data class AcademicTerm(val code: String, val name: String, val selected: Boolean = false)
data class AcademicWeek(val number: Int, val start: LocalDate, val end: LocalDate, val current: Boolean = false)
data class AcademicPeriod(val number: Int, val start: String = "", val end: String = "")
data class AcademicCourse(val code: String, val classCode: String, val name: String)
data class AcademicSnapshot(val term: AcademicTerm, val courses: List<AcademicCourse>)
data class AcademicLesson(
    val courseCode: String, val classCode: String, val name: String, val date: LocalDate?,
    val startTime: String, val endTime: String, val location: String = "",
    val teachers: List<String> = emptyList(),
    val beginSection: Int? = null, val endSection: Int? = null,
)
data class AcademicComparison(
    val status: AcademicStatus,
    val terms: List<AcademicTerm> = emptyList(),
    val termCode: String? = null,
    val termName: String = "",
    val excluded: List<Course> = emptyList(),
    val verifiedScheduleIds: Set<String> = emptySet(),
    val uncertainCount: Int = 0,
    val message: String = "",
)

data class UserSession(val userId: String, val userName: String)

data class Course(
    val id: String,
    val courseName: String,
    val courseNum: String = "",
    val classBeginTime: String = "",
    val classEndTime: String = "",
    val classroomName: String = "",
    val teachBuildName: String = "",
    val storeyName: String = "",
    val teachers: List<String> = emptyList(),
    val scheduleIds: List<String> = listOf(id),
    val signed: Boolean = false,
    val classCode: String = "",
    val attendanceAvailable: Boolean = true,
    val beginSection: Int? = null, val endSection: Int? = null,
    val attendanceSessions: List<Course> = emptyList(),
    val signedScheduleIds: Set<String> = emptySet(),
    val attendanceNotRequired: Boolean = false,
) {
    val realIds: List<String> get() = (scheduleIds + id).map(String::trim).filter { it.isNotEmpty() && !it.startsWith("academic:") }.distinct()
    val completedIds: Set<String> get() = if (signed) realIds.toSet() else signedScheduleIds
    val attendanceProgress: String get() = if (attendanceSessions.isEmpty()) "" else
        (if (attendanceSessions.all { it.signed }) "✓ " else "") + "已签 ${attendanceSessions.count { it.signed }}/${attendanceSessions.size}"
    fun applyAttendance(ids: Set<String>): Course {
        if (attendanceSessions.isNotEmpty()) {
            val sessions = attendanceSessions.map { it.applyAttendance(ids) }
            return copy(attendanceSessions = sessions, signed = sessions.all { it.signed },
                signedScheduleIds = sessions.flatMap { it.completedIds }.toSet())
        }
        val completed = completedIds + ids.intersect(realIds.toSet())
        return copy(signedScheduleIds = completed, signed = realIds.isNotEmpty() && completed.containsAll(realIds))
    }
}

data class DaySchedule(val date: LocalDate, val courses: List<Course>, val failed: Boolean = false)
data class WeekSchedule(
    val week: Int,
    val semesterStart: LocalDate,
    val days: List<DaySchedule>,
    val academic: AcademicComparison? = null,
    val studentType: StudentType = StudentType.AUTO,
    val weeks: List<AcademicWeek> = emptyList(),
    val sections: List<AcademicPeriod> = emptyList(),
    val unscheduled: List<Course> = emptyList(),
    val attendanceMessage: String? = null,
) {
    val total: Int get() = days.sumOf { it.courses.size } + unscheduled.size
    val signed: Int get() = days.sumOf { day -> day.courses.count { it.signed } }
    private val attendanceRecords: List<Course> get() = days.flatMap { it.courses }.filter { !it.attendanceNotRequired }
        .flatMap { if (it.attendanceSessions.isNotEmpty()) it.attendanceSessions else if (it.attendanceAvailable) listOf(it) else emptyList() }
    // A UI session may submit several teacher IDs, but still requires one button press.
    private val attendanceSlots: List<Course> get() = attendanceRecords.filter { it.realIds.isNotEmpty() }
        .distinctBy { it.realIds.sorted() }
    val attendanceTotal: Int get() = attendanceSlots.size
    val attendanceSigned: Int get() = attendanceSlots.count { slot -> slot.completedIds.containsAll(slot.realIds) }
    val notRequired: Int get() = days.sumOf { day -> day.courses.count { it.attendanceNotRequired } }
}
data class SignItem(val id: String, val name: String, val outcome: SignOutcome, val message: String = "")
data class SignSummary(val items: List<SignItem>) {
    val success: Int get() = items.count { it.outcome == SignOutcome.SUCCESS }
    val skipped: Int get() = items.count { it.outcome == SignOutcome.SKIPPED }
    val failed: Int get() = items.count { it.outcome == SignOutcome.FAILED }
}
data class AppLog(val message: String, val level: String = "info", val time: LocalTime = LocalTime.now())

interface CourseRepository {
    suspend fun login(username: String, password: String, mode: NetworkMode): UserSession
    suspend fun loginWithCaptcha(username: String, password: String, mode: NetworkMode, captcha: String): UserSession = login(username, password, mode)
    suspend fun refreshLoginCaptcha(): LoginCaptcha = throw IclassException("当前登录无需验证码")
    suspend fun loadWeek(week: Int, semesterStart: LocalDate): WeekSchedule
    suspend fun linkAttendance(schedule: WeekSchedule): WeekSchedule = schedule
    suspend fun signCourses(courses: List<Course>): SignSummary
    fun selectAcademicTerm(code: String) {}
    fun selectStudentType(type: StudentType) {}
    fun logout()
}

class LoginCaptcha(val imageBytes: ByteArray) { override fun toString() = "LoginCaptcha(redacted)" }
class CaptchaRequiredException(val captcha: LoginCaptcha, message: String = "请输入验证码后继续登录") : IclassException(message)

data class AssistantUiState(
    val captcha: LoginCaptcha? = null,
    val captchaInput: String = "",
    val username: String = "",
    val password: String = "",
    val mode: NetworkMode = NetworkMode.DIRECT,
    val studentType: StudentType = StudentType.AUTO,
    val autoLogin: Boolean = false,
    val language: AppLanguage = AppLanguage.ZH,
    val semesterStart: LocalDate = LocalDate.of(2026, 9, 7),
    val remember: Boolean = false,
    val hasSavedCredentials: Boolean = false,
    val busy: Boolean = true,
    val busyMessage: String = "",
    val user: UserSession? = null,
    val week: Int = 1,
    val schedule: WeekSchedule? = null,
    val logs: List<AppLog> = emptyList(),
    val message: String? = null,
    val error: String? = null,
)
