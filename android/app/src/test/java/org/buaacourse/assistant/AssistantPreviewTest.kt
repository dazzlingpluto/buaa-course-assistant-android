package org.buaacourse.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Real Compose rendering with demo data only; no school account or network is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureLoginPreview() {
        compose.setContent { AssistantContent(AssistantUiState(busy = false)) }
        capture("native-login")
    }

    @Test fun captureAutoLoginPreview() {
        compose.setContent { AssistantContent(AssistantUiState(busy = false,
            username = "demo-student", password = "demo-password", remember = true, autoLogin = true, hasSavedCredentials = true)) }
        capture("native-login-auto")
    }

    @Test fun captureCaptchaLoginPreview() {
        val bitmap = Bitmap.createBitmap(240, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(234,245,255))
        canvas.drawText("DEMO", 28f, 62f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(33,115,189); textSize = 48f; isAntiAlias = true })
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle()
        compose.setContent { AssistantContent(AssistantUiState(busy = false, username = "demo-user", password = "demo-password",
            mode = NetworkMode.VPN, captcha = LoginCaptcha(stream.toByteArray()))) }
        compose.onNodeWithTag("captcha_input").performScrollTo()
        capture("native-login-captcha")
    }

    @Test fun captureCoursePreview() {
        compose.setContent { AssistantContent(coursePreviewState()) }
        capture("native-courses")
    }

    @Test fun captureDetailPreview() {
        val base = coursePreviewState()
        val schedule = base.schedule!!
        val updated = schedule.copy(days = schedule.days.map { day -> day.copy(courses = day.courses.map { c ->
            if (c.id != "demo-4") c else c.copy(signed = true, attendanceSessions = listOf(
                c.copy(id = "session-one", classBeginTime = "08:50", classEndTime = "10:35", scheduleIds = listOf("session-one"), signed = true),
                c.copy(id = "session-two", classBeginTime = "10:40", classEndTime = "12:15", scheduleIds = listOf("session-two"), signed = true)))
        }) })
        compose.setContent { AssistantContent(base.copy(schedule = updated)) }
        compose.onNodeWithTag("course_demo-4").performClick()
        capture("native-course-detail")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-420dpi")
    fun captureCompactEnglishPreview() {
        val preview = coursePreviewState()
        compose.setContent {
            AssistantContent(preview.copy(
                language = AppLanguage.EN,
                logs = preview.logs.map { it.copy(message = "Sample timetable loaded.") },
            ))
        }
        capture("native-compact-english")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-420dpi")
    fun captureCompactEnglishLoginPreview() {
        compose.setContent { AssistantContent(AssistantUiState(busy = false, language = AppLanguage.EN)) }
        capture("native-login-compact-english")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Draw the real Compose host into a native Canvas. PixelCopy requires a device
        // window surface and does not complete under Robolectric's JVM window host.
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            check(view.width > 0 && view.height > 0) { "Preview view has not been laid out" }
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }
        val output = File("build/ui-preview/$name.png")
        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        output.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap.recycle()
    }

    private fun coursePreviewState(): AssistantUiState {
        val start = LocalDate.of(2030, 9, 2)
        val week = 4
        val date = start.plusWeeks((week - 1).toLong())
        fun demo(id: Int, name: String, begin: String, end: String, room: String, signed: Boolean = false) =
            Course("demo-$id", name, courseNum = "DEMO-${100 + id}", classBeginTime = begin, classEndTime = end,
                classroomName = room, teachers = listOf("示例教师"), signed = signed, classCode = "DEMO-${100 + id}-01")
        val byDay = mapOf(
            0 to listOf(demo(1, "高等数学", "09:50", "11:25", "星桥楼 6-107").copy(attendanceNotRequired = true, attendanceAvailable = false)),
            1 to listOf(demo(4, "理论物理", "08:50", "12:15", "松石楼 2-208"), demo(6, "程序设计基础", "14:00", "15:35", "云岸楼 1-104")),
            2 to listOf(demo(2, "线性代数", "08:00", "09:35", "星桥楼 3-206", true), demo(7, "大学英语", "15:50", "17:25", "云岸楼 4-312")),
            3 to listOf(demo(3, "概率论与数理统计", "14:00", "16:35", "松石楼 5-103")),
            4 to listOf(demo(5, "数据结构", "10:40", "12:15", "云岸楼 2-215"), demo(8, "普通化学", "15:50", "17:25", "松石楼 1-118")),
            5 to listOf(demo(9, "大学写作", "09:50", "11:25", "星桥楼 4-209")),
        )
        return AssistantUiState(
            busy = false,
            studentType = StudentType.GRADUATE,
            user = UserSession("demo", "演示用户"),
            week = week,
            semesterStart = start,
            schedule = WeekSchedule(
                week, start, (0L..6L).map { DaySchedule(date.plusDays(it), byDay[it.toInt()].orEmpty()) },
                academic = AcademicComparison(
                    status = AcademicStatus.VERIFIED,
                    terms = listOf(AcademicTerm("demo-term", "2030–2031 演示学期")),
                    termCode = "demo-term", termName = "2030–2031 演示学期",
                    verifiedScheduleIds = byDay.values.flatten().map { it.id }.toSet(),
                ),
                sections = TimetableLayout.periods.map { AcademicPeriod(it.number, it.start, it.end) },
            ),
            logs = listOf(AppLog("演示课表已加载。", "success", LocalTime.of(9, 0))),
        )
    }
}
