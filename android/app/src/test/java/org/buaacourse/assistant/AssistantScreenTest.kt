package org.buaacourse.assistant

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captchaInputImageRefreshAndLoginGatingAreAvailable() {
        val state = mutableStateOf(AssistantUiState(busy = false, username = "demo-user", password = "demo-password",
            mode = NetworkMode.VPN, captcha = LoginCaptcha(CaptchaIntegrationTest.png())))
        var refreshes = 0
        var submits = 0
        compose.setContent { AssistantContent(state.value, AssistantActions(
            captcha = { state.value = state.value.copy(captchaInput = it) }, refreshCaptcha = { refreshes++ }, login = { submits++ })) }
        compose.onNodeWithTag("captcha_input").performScrollTo().performTextInput("manual-test-input")
        compose.onNodeWithTag("login").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, submits)
        compose.onNodeWithTag("captcha_refresh").performScrollTo().performClick()
        compose.onNodeWithTag("captcha_image").performScrollTo().performClick()
        assertEquals(2, refreshes)
        compose.runOnIdle { state.value = state.value.copy(captchaInput = "") }
        compose.onNodeWithTag("login").performScrollTo().assertIsNotEnabled()
    }

    @Test fun courseDetailsHideInternalIdsAndUseSmartBuaaReminder() {
        val state = loggedIn()
        val schedule = state.schedule!!
        val courses = schedule.days.first().courses.map { it.copy(classCode = "private-class-code") }
        compose.setContent { AssistantContent(state.copy(schedule = schedule.copy(days = listOf(schedule.days.first().copy(courses = courses))))) }
        compose.onNodeWithTag("course_pending").performClick()
        compose.onNodeWithText("教学班号").assertDoesNotExist()
        compose.onNodeWithText("private-class-code").assertDoesNotExist()
        compose.onNodeWithText("iClass 排课编号").assertDoesNotExist()
        compose.onNodeWithText("来自所选学期的正式课表").assertDoesNotExist()
        compose.onNodeWithText("请根据实际出勤情况操作。\n结果以智慧北航APP“智慧教室”为准。").performScrollTo().assertExists()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-420dpi")
    fun sundayColumnAndCourseFitInsideNarrowScreenWithoutHorizontalScrolling() {
        val state = loggedIn()
        val schedule = state.schedule!!
        val sunday = schedule.days.first().date.plusDays(6)
        val course = schedule.days.first().courses.first().copy(id = "sunday-course")
        val days = (0L..6L).map { DaySchedule(schedule.days.first().date.plusDays(it), if (it == 6L) listOf(course) else emptyList()) }
        compose.setContent { AssistantContent(state.copy(schedule = schedule.copy(days = days))) }
        val grid = compose.onNodeWithTag("weekly_grid").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("day_header_$sunday").fetchSemanticsNode().boundsInRoot
        assertTrue(header.right <= grid.right + 1f)
        compose.onNodeWithTag("course_sunday-course").assertIsDisplayed().performClick()
        compose.onNodeWithTag("course_detail").assertExists()
    }

    @Test fun noAttendanceCourseShowsCheckAndNoSubmitButton() {
        val date = LocalDate.of(2026, 9, 7)
        val course = Course("no-attendance", "无需签到课程", "N1", "08:00", "09:35", attendanceAvailable = false, attendanceNotRequired = true)
        compose.setContent { AssistantContent(AssistantUiState(busy = false, user = UserSession("u", "演示"),
            schedule = WeekSchedule(1, date, listOf(DaySchedule(date, listOf(course))), sections = listOf(AcademicPeriod(1, "08:00", "09:35"))))) }
        compose.onNodeWithTag("course_no-attendance").assertTextContains("✓", substring = true)
        compose.onNodeWithTag("course_no-attendance").performClick()
        compose.onNodeWithText("✓ 无需签到").assertExists()
        compose.onNodeWithTag("attendance_not_required_no-attendance").performScrollTo().assertExists()
        compose.onNodeWithTag("sign_no-attendance").assertDoesNotExist()
    }

    @Test fun splitAttendanceButtonsSubmitOnlyTheirOwnSession() {
        val date = LocalDate.of(2026, 9, 7)
        val first = Course("slot-one", "数学", "M1", "08:50", "10:35", signed = true)
        val second = Course("slot-two", "数学", "M1", "10:40", "12:15")
        val parent = Course("parent", "数学", "M1", "08:50", "12:15", attendanceSessions = listOf(first, second))
        var submitted: Course? = null
        compose.setContent { AssistantContent(AssistantUiState(busy = false, user = UserSession("demo", "演示"),
            schedule = WeekSchedule(1, date, listOf(DaySchedule(date, listOf(parent))))), AssistantActions(sign = { submitted = it })) }
        compose.onNodeWithTag("course_parent").performScrollTo().performClick()
        compose.onNodeWithText("已签 1/2").assertExists()
        compose.onNodeWithTag("sign_slot-one").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("sign_slot-two").performScrollTo().assertIsEnabled().performClick()
        assertEquals("slot-two", submitted?.id)
        compose.onNodeWithTag("sign_parent").assertDoesNotExist()
    }

    @Test fun loginInputAndNetworkChoiceReachActions() {
        val state = mutableStateOf(AssistantUiState(busy = false))
        compose.setContent {
            AssistantContent(
                state.value,
                AssistantActions(
                    username = { state.value = state.value.copy(username = it) },
                    password = { state.value = state.value.copy(password = it) },
                    mode = { state.value = state.value.copy(mode = it) },
                ),
            )
        }
        compose.onNodeWithTag("username").performTextInput("demo-user")
        compose.onNodeWithTag("password").performTextInput("demo-password")
        compose.onNodeWithTag("mode_direct").assertIsSelected()
        compose.onNodeWithTag("mode_vpn").performScrollTo().performClick()
        assertEquals("demo-user", state.value.username)
        assertEquals("demo-password", state.value.password)
        assertEquals(NetworkMode.VPN, state.value.mode)
        compose.onNodeWithTag("mode_vpn").assertIsSelected()
        compose.onNodeWithTag("mode_direct").assertIsNotSelected()
        compose.onNodeWithTag("mode_menu").assertDoesNotExist()
        compose.onNodeWithTag("login").performScrollTo().assertTextContains("VPN 登录")
    }

    @Test fun rememberIsOptionalAndClearIsAnExplicitAction() {
        var remember = false
        var cleared = false
        compose.setContent {
            AssistantContent(
                AssistantUiState(busy = false, hasSavedCredentials = true),
                AssistantActions(remember = { remember = it }, forget = { cleared = true }),
            )
        }
        compose.onNodeWithTag("remember_credentials").assertIsOff().performScrollTo().performClick()
        assertTrue(remember)
        compose.onNodeWithTag("forget_credentials").performScrollTo().performClick()
        assertTrue(cleared)
    }

    @Test fun busyStateDisablesAuthenticationMutations() {
        compose.setContent { AssistantContent(AssistantUiState(busy = true, hasSavedCredentials = true)) }
        listOf("username", "password", "mode_direct", "mode_vpn", "remember_credentials", "auto_login", "forget_credentials", "login").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }

    @Test fun loginButtonCannotBePressedAgainAfterOperationStarts() {
        val state = mutableStateOf(AssistantUiState(username = "demo", password = "test", busy = false))
        var calls = 0
        compose.setContent {
            AssistantContent(state.value, AssistantActions(login = { calls++; state.value = state.value.copy(busy = true) }))
        }
        compose.onNodeWithTag("login").performScrollTo().performClick()
        compose.onNodeWithTag("login").assertIsNotEnabled()
        assertEquals(1, calls)
    }

    @Test fun englishModeTranslatesFormLabelsAndActions() {
        compose.setContent { AssistantContent(AssistantUiState(busy = false, language = AppLanguage.EN)) }
        compose.onNodeWithText("Student ID").assertExists()
        compose.onNodeWithText("Password", substring = false).assertExists()
        compose.onNodeWithTag("login").performScrollTo().assertTextContains("Sign In")
        compose.onNodeWithTag("language").assertTextContains("中")
    }

    @Test fun loginHasInlineNetworkChoicesWithoutIdentityOrSemesterSelection() {
        compose.setContent { AssistantContent(AssistantUiState(busy = false)) }
        compose.onNodeWithTag("mode_direct").assertIsSelected().assertTextContains("校园直连")
        compose.onNodeWithTag("mode_vpn").assertIsNotSelected().assertTextContains("校外网络")
        compose.onNodeWithTag("mode_menu").assertDoesNotExist()
        compose.onNodeWithTag("student_graduate").assertDoesNotExist()
        compose.onNodeWithTag("student_undergraduate").assertDoesNotExist()
        compose.onNodeWithTag("semester_start").assertDoesNotExist()
        compose.onNodeWithText("学期基准日").assertDoesNotExist()
    }

    @Test fun autoLoginIsOptionalAndSendsItsOwnCallback() {
        val state = mutableStateOf(AssistantUiState(busy = false))
        var calls = 0
        compose.setContent {
            AssistantContent(state.value, AssistantActions(autoLogin = {
                calls++
                state.value = state.value.copy(autoLogin = it, remember = it)
            }))
        }
        compose.onNodeWithTag("auto_login").assertIsOff().performScrollTo().performClick()
        compose.onNodeWithTag("auto_login").assertIsOn()
        compose.onNodeWithTag("remember_credentials").assertIsOn()
        compose.onNodeWithTag("auto_login").performClick().assertIsOff()
        assertEquals(2, calls)
    }

    @Test fun failedAutoLoginStillAllowsManualRetryAndPasswordVisibility() {
        var retries = 0
        compose.setContent {
            AssistantContent(AssistantUiState(username = "demo", password = "demo-password", remember = true,
                autoLogin = true, busy = false, error = "登录失败，请重试"), AssistantActions(login = { retries++ }))
        }
        compose.onNodeWithTag("show_password").performScrollTo().performClick().assertTextContains("隐藏")
        compose.onNodeWithTag("show_password").performClick().assertTextContains("显示")
        compose.onNodeWithTag("login").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    @Test fun emptyCredentialsCannotSubmit() {
        val state = mutableStateOf(AssistantUiState(busy = false))
        compose.setContent { AssistantContent(state.value) }
        compose.onNodeWithTag("login").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(username = "demo") }
        compose.onNodeWithTag("login").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(password = "password") }
        compose.onNodeWithTag("login").assertIsEnabled()
    }

    @Test fun weekNavigationUsesLimitsAndSelectedWeek() {
        var selected = 0
        compose.setContent { AssistantContent(loggedIn(), AssistantActions(week = { selected = it })) }
        compose.onNodeWithTag("previous_week").assertIsNotEnabled()
        compose.onNodeWithTag("next_week").performClick()
        assertEquals(2, selected)
        compose.onNodeWithTag("week_select").performClick()
        compose.onNodeWithText("第 3 周").performClick()
        assertEquals(3, selected)
    }

    @Test fun finalWeekDisablesNextButton() {
        compose.setContent { AssistantContent(loggedIn().copy(week = 18)) }
        compose.onNodeWithTag("next_week").assertIsNotEnabled()
        compose.onNodeWithTag("previous_week").assertIsEnabled()
    }

    @Test fun returnedWeeksAllowTwentiethWeekAndNonConsecutiveNavigation() {
        var selected = 0
        val base = loggedIn()
        val start = LocalDate.of(2026, 9, 7)
        val weeks = listOf(1, 3, 20).map { number -> AcademicWeek(number, start.plusWeeks((number - 1).toLong()), start.plusWeeks((number - 1).toLong()).plusDays(6)) }
        val state = mutableStateOf(base.copy(week = 3, schedule = base.schedule!!.copy(weeks = weeks)))
        compose.setContent { AssistantContent(state.value, AssistantActions(week = { selected = it })) }
        compose.onNodeWithTag("previous_week").performClick()
        assertEquals(1, selected)
        compose.onNodeWithTag("next_week").performClick()
        assertEquals(20, selected)
        compose.onNodeWithTag("week_select").performClick()
        compose.onNodeWithTag("week_option_20").performClick()
        assertEquals(20, selected)
        compose.runOnIdle { state.value = state.value.copy(week = 20) }
        compose.onNodeWithTag("next_week").assertIsNotEnabled()
        compose.onNodeWithTag("previous_week").performClick()
        assertEquals(3, selected)
    }

    @Test fun realSectionsWithoutTimesAppearInGridButCannotBeSigned() {
        val state = loggedIn()
        val course = Course("real-section", "新增节次课程", beginSection = 15, endSection = 16)
        compose.setContent { AssistantContent(state.copy(schedule = state.schedule!!.copy(sections = emptyList(),
            days = listOf(DaySchedule(LocalDate.of(2026, 9, 7), listOf(course)))))) }
        compose.onNodeWithTag("course_real-section").performScrollTo().performClick()
        compose.onNodeWithText("第 15–16 节").assertExists()
        compose.onNodeWithText("—").assertExists()
        compose.onNodeWithTag("sign_real-section").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unscheduledCourseHasNoInventedDateAndCanOpenReadOnlyDetails() {
        val state = loggedIn()
        val course = Course("unscheduled", "待排课课程", beginSection = 2, endSection = 3, attendanceAvailable = false)
        compose.setContent { AssistantContent(state.copy(schedule = state.schedule!!.copy(unscheduled = listOf(course)))) }
        compose.onNodeWithTag("unscheduled_heading").assertTextEquals("未安排时间")
        compose.onNodeWithTag("course_unscheduled").performClick()
        compose.onNodeWithText("未安排时间").assertExists()
        compose.onNodeWithText("2026-09-07 · 周一").assertDoesNotExist()
        compose.onNodeWithTag("sign_unscheduled").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unknownIdentityAndAttendanceFailureDoNotMislabelOrHideOfficialCourses() {
        val state = loggedIn().copy(studentType = StudentType.AUTO)
        compose.setContent { AssistantContent(state.copy(schedule = state.schedule!!.copy(attendanceMessage = "签到暂不可用"))) }
        compose.onNodeWithTag("timetable_identity").assertTextContains("身份未识别", substring = true)
        compose.onNodeWithTag("attendance_status").assertTextContains("签到暂不可用", substring = true)
        compose.onNodeWithTag("error_message").assertDoesNotExist()
        compose.onNodeWithTag("course_pending").assertExists()
    }

    @Test fun tappingClassShowsDetailsAndOnlyThatClassCanBeSigned() {
        var selected: Course? = null
        compose.setContent { AssistantContent(loggedIn(), AssistantActions(sign = { selected = it })) }
        compose.onNodeWithTag("weekly_grid").assertExists()
        compose.onNodeWithTag("sign_week").assertDoesNotExist()
        compose.onNodeWithTag("sign_pending").assertDoesNotExist()
        compose.onNodeWithTag("course_pending").performClick()
        compose.onNodeWithTag("course_detail").assertExists()
        compose.onNodeWithText("08:00 — 09:35").assertExists()
        compose.onNodeWithText("教学楼 2层 A101").assertExists()
        compose.onNodeWithText("示例教师").assertExists()
        compose.onNodeWithTag("sign_pending").performScrollTo().performClick()
        assertEquals("pending", selected?.id)
        compose.onNodeWithTag("detail_back").performClick()
        compose.onNodeWithTag("weekly_grid").assertExists()
        compose.onNodeWithTag("course_detail").assertDoesNotExist()
    }

    @Test fun completedCourseAndBusyStateDisableDetailSigning() {
        val state = mutableStateOf(loggedIn())
        compose.setContent { AssistantContent(state.value) }
        compose.onNodeWithTag("course_pending").performClick()
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag("sign_pending").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            val schedule = state.value.schedule!!
            state.value = state.value.copy(busy = false, schedule = schedule.copy(days = schedule.days.map { it.copy(courses = it.courses.map { c -> c.copy(signed = true) }) }))
        }
        compose.onNodeWithTag("sign_pending").assertIsNotEnabled().assertTextContains("已完成签到", substring = true)
    }

    @Test fun officialCourseWithoutIClassMappingShowsDetailsButCannotSign() {
        val state = loggedIn()
        val course = state.schedule!!.days.first().courses.first().copy(id = "official-only", scheduleIds = emptyList(),
            classCode = "2026-001", attendanceAvailable = false)
        var signs = 0
        val schedule = state.schedule.copy(days = listOf(DaySchedule(LocalDate.of(2026, 9, 7), listOf(course))),
            academic = state.schedule.academic!!.copy(uncertainCount = 1, verifiedScheduleIds = emptySet()))
        compose.setContent { AssistantContent(state.copy(schedule = schedule), AssistantActions(sign = { signs++ })) }
        compose.onNodeWithTag("course_official-only").performClick()
        compose.onNodeWithText("2026-001").assertDoesNotExist()
        compose.onNodeWithText("official-only", substring = false).assertDoesNotExist()
        compose.onNodeWithText("iClass 排课编号").assertDoesNotExist()
        compose.onNodeWithTag("academic_pending_official-only").assertDoesNotExist()
        compose.onNodeWithTag("attendance_unavailable_official-only").performScrollTo()
            .assertTextEquals("未找到对应的 iClass 排课，暂不可签到")
        compose.onNodeWithTag("sign_official-only").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, signs)
    }

    @Test fun undergraduateIdentityAndOfficialScheduleStatusAreVisible() {
        val state = loggedIn()
        compose.setContent { AssistantContent(state.copy(studentType = StudentType.UNDERGRADUATE,
            schedule = state.schedule!!.copy(academic = state.schedule.academic!!.copy(uncertainCount = 1)))) }
        compose.onNodeWithTag("timetable_identity").assertTextContains("本科生", substring = true)
        compose.onNodeWithTag("academic_toggle").assertTextContains("正式课表", substring = true).performClick()
        compose.onNodeWithText("本科生教务课表").assertExists()
        compose.onNodeWithTag("academic_status").performScrollTo().assertTextContains("已加载正式课表，1 门暂未关联 iClass 签到。")
    }

    @Test fun changingWeekOrRemovingCourseCannotKeepAnOldDetailAction() {
        val state = mutableStateOf(loggedIn())
        compose.setContent { AssistantContent(state.value) }
        compose.onNodeWithTag("course_pending").performClick()
        compose.runOnIdle { state.value = state.value.copy(week = 2, schedule = null) }
        compose.onNodeWithTag("course_detail").assertDoesNotExist()
        compose.onNodeWithTag("sign_pending").assertDoesNotExist()
        compose.runOnIdle { state.value = loggedIn() }
        compose.onNodeWithTag("course_pending").performClick()
        compose.runOnIdle {
            val schedule = state.value.schedule!!
            state.value = state.value.copy(schedule = schedule.copy(days = schedule.days.map { it.copy(courses = emptyList()) }))
        }
        compose.onNodeWithTag("course_detail").assertDoesNotExist()
    }

    @Test fun logoutRemovesDetailAndRestoresLoginPage() {
        val state = mutableStateOf(loggedIn())
        compose.setContent { AssistantContent(state.value) }
        compose.onNodeWithTag("course_pending").performClick()
        compose.runOnIdle { state.value = AssistantUiState(busy = false) }
        compose.onNodeWithTag("course_detail").assertDoesNotExist()
        compose.onNodeWithTag("username").assertExists()
    }

    @Test fun academicTermSelectionPassesTheOfficialTermCode() {
        var selected = ""
        val state = loggedIn()
        val academic = AcademicComparison(status = AcademicStatus.SELECT_TERM,
            terms = listOf(AcademicTerm("official-a", "2026–2027 第一学期"), AcademicTerm("official-b", "2025–2026 第二学期")))
        compose.setContent { AssistantContent(state.copy(schedule = state.schedule!!.copy(academic = academic)), AssistantActions(academicTerm = { selected = it })) }
        compose.onNodeWithTag("academic_toggle").performClick()
        compose.onNodeWithTag("academic_term").performScrollTo().performClick()
        compose.onNodeWithTag("academic_term_official-b").performClick()
        assertEquals("official-b", selected)
    }

    @Test fun unavailableComparisonPreservesReadOnlyDetail() {
        val state = loggedIn()
        val schedule = state.schedule!!.copy(academic = AcademicComparison(status = AcademicStatus.UNAVAILABLE),
            days = state.schedule.days.map { day -> day.copy(courses = day.courses.map { it.copy(attendanceAvailable = false) }) })
        compose.setContent { AssistantContent(state.copy(schedule = schedule)) }
        compose.onNodeWithTag("academic_toggle").assertTextContains("教务读取失败", substring = true)
        compose.onNodeWithTag("course_pending").performClick()
        compose.onNodeWithTag("academic_pending_pending").performScrollTo().assertExists()
        compose.onNodeWithTag("attendance_unavailable_pending").performScrollTo().assertTextEquals("完成正式课表核对前暂不可签到")
        compose.onNodeWithTag("sign_pending").performScrollTo().assertIsNotEnabled()
    }

    @Test fun academicFailureReasonIsAvailableOnDemand() {
        val state = loggedIn()
        val academic = AcademicComparison(status = AcademicStatus.UNAVAILABLE, message = "研究生系统要求校园网络，请检查网络模式后刷新。")
        compose.setContent { AssistantContent(state.copy(schedule = state.schedule!!.copy(academic = academic))) }
        compose.onNodeWithTag("academic_toggle").performClick()
        compose.onNodeWithTag("academic_reason").assertDoesNotExist()
        compose.onNodeWithTag("academic_reason_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("academic_reason").assertTextEquals("研究生系统要求校园网络，请检查网络模式后刷新。")
    }

    @Test fun excludedEntriesCanBeInspectedWithoutRestoringThem() {
        val state = loggedIn()
        val academic = state.schedule!!.academic!!.copy(excluded = listOf(Course("removed", "残留课程", courseNum = "R100")))
        compose.setContent { AssistantContent(state.copy(schedule = state.schedule.copy(academic = academic))) }
        compose.onNodeWithTag("academic_toggle").performClick()
        compose.onNodeWithTag("academic_excluded").performScrollTo().performClick()
        compose.onNodeWithTag("academic_excluded_list").assertExists()
        compose.onNodeWithText("以下 iClass 排课未与本周正式课程建立唯一对应，未用于本周课表与签到。这不代表已退课。").assertExists()
        compose.onNodeWithText("残留课程").assertExists()
        compose.onNodeWithTag("course_removed").assertDoesNotExist()
        compose.onNodeWithTag("close_academic_excluded").performClick()
        compose.onNodeWithTag("academic_excluded_list").assertDoesNotExist()
    }

    @Test fun failedDayIsNotPresentedAsAnEmptyTimetable() {
        val date = LocalDate.of(2026, 9, 7)
        compose.setContent { AssistantContent(loggedIn().copy(schedule = WeekSchedule(1, date, listOf(DaySchedule(date, emptyList(), failed = true))))) }
        compose.onNodeWithTag("failed_day_$date").assertExists()
        compose.onNodeWithTag("empty_day_$date").assertDoesNotExist()
    }

    @Test fun emptyDayHasAnExplicitNoClassesMessageAndAllSevenDateHeaders() {
        val date = LocalDate.of(2026, 9, 7)
        compose.setContent { AssistantContent(loggedIn().copy(schedule = WeekSchedule(1, date, listOf(DaySchedule(date, emptyList()))))) }
        compose.onNodeWithTag("empty_day_$date").assertExists()
        (0L..6L).forEach { compose.onNodeWithTag("day_header_${date.plusDays(it)}").assertExists() }
    }

    @Test fun overlappingClassesAreAllAccessibleInsteadOfCoveringEachOther() {
        val state = loggedIn()
        val schedule = state.schedule!!
        val course = schedule.days.first().courses.first()
        compose.setContent { AssistantContent(state.copy(schedule = schedule.copy(days = listOf(DaySchedule(schedule.days.first().date,
            listOf(course, course.copy(id = "overlap", courseName = "同时间另一课程"))))))) }
        compose.onNodeWithTag("cluster_2026-09-07_0").performClick()
        compose.onNodeWithTag("overlap_pending").assertExists()
        compose.onNodeWithTag("overlap_overlap").performClick()
        compose.onNodeWithText("同时间另一课程").assertExists()
        compose.onNodeWithTag("sign_overlap").performScrollTo().assertIsEnabled()
    }

    @Test fun nonStandardTimeRemainsAccessibleWithOriginalTime() {
        val state = loggedIn()
        val course = Course("special", "特殊时间课程", classBeginTime = "09:10", classEndTime = "10:20")
        compose.setContent { AssistantContent(state.copy(schedule = state.schedule!!.copy(days = listOf(DaySchedule(LocalDate.of(2026, 9, 7), listOf(course)))))) }
        compose.onNodeWithTag("course_special").performScrollTo().performClick()
        compose.onNodeWithText("09:10 — 10:20").assertExists()
        compose.onNodeWithText("非标准节次 / 时间待确认").assertExists()
        compose.onNodeWithTag("detail_back").performClick()
        compose.onNodeWithTag("course_special").assertIsDisplayed()
    }

    @Test fun sameScheduleIdWithDifferentNamesOpensTheChosenRecord() {
        val state = loggedIn()
        val schedule = state.schedule!!
        val course = schedule.days.first().courses.first()
        compose.setContent { AssistantContent(state.copy(schedule = schedule.copy(days = listOf(DaySchedule(schedule.days.first().date,
            listOf(course, course.copy(courseName = "同号不同名"))))))) }
        compose.onNodeWithTag("cluster_2026-09-07_0").performClick()
        compose.onNodeWithText("同号不同名").performClick()
        compose.onNodeWithTag("course_detail").assertExists()
        compose.onNodeWithText("同号不同名").assertExists()
        compose.onNodeWithText("高等数学").assertDoesNotExist()
    }

    @Test fun overnightCourseDetailsRetainBothDates() {
        val state = loggedIn()
        val course = Course("overnight", "跨日课程", classBeginTime = "2026-09-07 08:00", classEndTime = "2026-09-08 09:35")
        compose.setContent { AssistantContent(state.copy(schedule = state.schedule!!.copy(days = listOf(DaySchedule(LocalDate.of(2026, 9, 7), listOf(course)))))) }
        compose.onNodeWithTag("course_overnight").performScrollTo().performClick()
        compose.onNodeWithText("2026-09-07 08:00 — 2026-09-08 09:35").assertExists()
    }

    @Test fun logTrayShowsNewestEntryAndExpandsHistory() {
        val logs = listOf(AppLog("最新结果", time = LocalTime.of(10, 0)), AppLog("较早结果", time = LocalTime.of(9, 0)))
        compose.setContent { AssistantContent(AssistantUiState(busy = false, logs = logs)) }
        compose.onNodeWithText("[10:00:00] 最新结果").assertExists()
        compose.onNodeWithTag("activity_log").assertDoesNotExist()
        compose.onNodeWithTag("log_toggle").performClick()
        compose.onNodeWithTag("activity_log").assertExists()
        compose.onNodeWithText("较早结果").assertExists()
        compose.onNodeWithTag("log_toggle").performClick()
        compose.onNodeWithTag("activity_log").assertDoesNotExist()
    }

    @Test fun signedInHeaderAllowsSignOut() {
        var loggedOut = false
        compose.setContent { AssistantContent(loggedIn(), AssistantActions(logout = { loggedOut = true })) }
        compose.onNodeWithTag("username").assertDoesNotExist()
        compose.onNodeWithTag("logout").performClick()
        assertTrue(loggedOut)
    }

    private fun loggedIn(): AssistantUiState {
        val start = LocalDate.of(2026, 9, 7)
        val courses = listOf(
            Course("pending", "高等数学", classBeginTime = "2026-09-07 08:00:00", classEndTime = "2026-09-07 09:35:00", classroomName = "A101", teachBuildName = "教学楼", storeyName = "2层", teachers = listOf("示例教师")),
            Course("done", "大学物理实验", classBeginTime = "14:00", classEndTime = "15:35", signed = true),
        )
        val academic = AcademicComparison(
            status = AcademicStatus.VERIFIED,
            terms = listOf(AcademicTerm("term-a", "2026–2027 第一学期")),
            termCode = "term-a", termName = "2026–2027 第一学期",
            verifiedScheduleIds = setOf("pending", "done"),
        )
        return AssistantUiState(busy = false, studentType = StudentType.GRADUATE, user = UserSession("demo", "演示用户"), week = 1,
            schedule = WeekSchedule(1, start, listOf(DaySchedule(start, courses)), academic,
                sections = TimetableLayout.periods.map { AcademicPeriod(it.number, it.start, it.end) }))
    }
}
