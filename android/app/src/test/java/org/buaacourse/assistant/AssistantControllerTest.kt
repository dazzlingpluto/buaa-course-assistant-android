package org.buaacourse.assistant

import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantControllerTest {
    private val start = LocalDate.of(2026, 9, 7)

    private class MemoryStore : SavedCredentialsStore {
        var saved: SavedCredentials? = null
        var reads = 0
        var writes = 0
        var failRead = false
        var failSave = false
        var failClear = false
        override fun read(): SavedCredentials? {
            reads++
            if (failRead) throw IOException("secret provider detail")
            return saved
        }
        override fun save(credentials: SavedCredentials) {
            if (failSave) throw IOException("secret provider detail")
            writes++
            saved = credentials
        }
        override fun clear() {
            if (failClear) throw IOException("secret provider detail")
            saved = null
        }
    }

    private class FakeRepository : CourseRepository {
        var loginCalls = 0
        var logoutCalls = 0
        var signCalls = 0
        var loadCalls = 0
        var linkCalls = 0
        var loginFailure = false
        var loadFailure = false
        var linkFailure = false
        var loginGate: CompletableDeferred<Unit>? = null
        var loadGate: CompletableDeferred<Unit>? = null
        var linkGate: CompletableDeferred<Unit>? = null
        var signGate: CompletableDeferred<Unit>? = null
        var lastWeek = 0
        var lastSemesterStart: LocalDate? = null
        var automaticWeek = 2
        var detectedType = StudentType.GRADUATE
        var returnedStart: LocalDate? = null
        var weekNumbers = (1..30).toList()
        val selectedTerms = mutableListOf<String>()
        val selectedStudentTypes = mutableListOf<StudentType>()
        var loginStudentType: StudentType? = null
        var submittedCourses: List<Course> = emptyList()
        var courses = listOf(Course("one", "演示课程", scheduleIds = listOf("one", "teacher-two")))
        var academic: AcademicComparison? = AcademicComparison(
            status = AcademicStatus.VERIFIED,
            termCode = "term-one",
            verifiedScheduleIds = setOf("one", "teacher-two"),
        )
        var signItems = listOf(SignItem("one", "演示课程", SignOutcome.SUCCESS), SignItem("teacher-two", "演示课程", SignOutcome.SUCCESS))
        override suspend fun login(username: String, password: String, mode: NetworkMode): UserSession {
            loginCalls++
            loginStudentType = selectedStudentTypes.lastOrNull()
            loginGate?.await()
            if (loginFailure) throw IOException("登录失败")
            return UserSession("demo", "演示用户")
        }
        override suspend fun loadWeek(week: Int, semesterStart: LocalDate): WeekSchedule {
            loadCalls++
            lastWeek = week
            lastSemesterStart = semesterStart
            loadGate?.await()
            if (loadFailure) throw IOException("课程服务不可用")
            val actualWeek = if (week == 0) automaticWeek else week
            val actualStart = returnedStart ?: semesterStart
            return WeekSchedule(actualWeek, actualStart, (0..6).map {
                DaySchedule(actualStart.plusWeeks((actualWeek - 1).toLong()).plusDays(it.toLong()),
                    if (it == 0) courses else emptyList())
            }, academic, studentType = detectedType, weeks = weekNumbers.map {
                val from = actualStart.plusWeeks((it - 1).toLong())
                AcademicWeek(it, from, from.plusDays(6), it == automaticWeek)
            })
        }
        override suspend fun linkAttendance(schedule: WeekSchedule): WeekSchedule {
            linkCalls++
            linkGate?.await()
            if (linkFailure) throw IOException("private iClass failure")
            return schedule
        }
        override suspend fun signCourses(courses: List<Course>): SignSummary {
            signCalls++
            submittedCourses = courses
            signGate?.await()
            return SignSummary(signItems)
        }
        override fun selectAcademicTerm(code: String) {
            selectedTerms += code
            academic = academic?.copy(termCode = code)
        }
        override fun selectStudentType(type: StudentType) { selectedStudentTypes += type }
        override fun logout() { logoutCalls++ }
    }

    private fun TestScope.controller(repo: FakeRepository = FakeRepository(), store: MemoryStore = MemoryStore(),
        current: LocalDate = start.plusDays(8)) = AssistantController(repo, store, object : AssistantPreferences {},
            this, StandardTestDispatcher(testScheduler), { current })

    private fun enter(controller: AssistantController) {
        controller.setUsername(" demo ")
        controller.setPassword(" password with spaces ")
    }

    @Test fun defaultIsOptOutAndCredentialsSaveOnlyAfterSuccessfulLogin() = runTest {
        val repo = FakeRepository()
        val store = MemoryStore()
        val controller = controller(repo, store)
        advanceUntilIdle()
        assertFalse(controller.state.value.remember)
        assertFalse(controller.state.value.autoLogin)
        enter(controller)
        controller.setRemember(true)
        assertEquals(0, store.writes)
        repo.loginFailure = true
        controller.login()
        advanceUntilIdle()
        assertEquals(0, store.writes)
        assertNull(controller.state.value.user)
        repo.loginFailure = false
        controller.login()
        advanceUntilIdle()
        assertEquals(1, store.writes)
        assertEquals("demo", store.saved!!.username)
        assertEquals(" password with spaces ", store.saved!!.password)
        assertEquals("", controller.state.value.password)
        assertEquals(2, controller.state.value.week)
        assertEquals(7, controller.state.value.schedule!!.days.size)
    }

    @Test fun savedCredentialsRestoreWithoutAutomaticLoginAndSurviveLogout() = runTest {
        val repo = FakeRepository()
        val store = MemoryStore().apply { saved = SavedCredentials("demo", "stored password", NetworkMode.VPN) }
        val controller = controller(repo, store)
        advanceUntilIdle()
        assertEquals(0, repo.loginCalls)
        assertEquals(StudentType.AUTO, controller.state.value.studentType)
        assertFalse(controller.state.value.autoLogin)
        assertEquals("stored password", controller.state.value.password)
        assertEquals(NetworkMode.VPN, controller.state.value.mode)
        controller.login()
        advanceUntilIdle()
        controller.logout()
        advanceUntilIdle()
        assertNull(controller.state.value.user)
        assertNull(controller.state.value.schedule)
        assertTrue(controller.state.value.remember)
        assertEquals("stored password", controller.state.value.password)
        assertEquals(1, repo.logoutCalls)
    }

    @Test fun automaticLoginRunsOnceAtStartupAndNeverSubmitsAttendance() = runTest {
        val repo = FakeRepository().apply { loginGate = CompletableDeferred() }
        val store = MemoryStore().apply {
            saved = SavedCredentials("demo", "saved password", NetworkMode.VPN, StudentType.UNDERGRADUATE, true)
        }
        val controller = controller(repo, store)
        runCurrent()
        controller.login()
        controller.setAutoLogin(false)
        assertTrue(controller.state.value.busy)
        assertEquals(1, repo.loginCalls)
        assertNull(repo.loginStudentType)
        repo.loginGate!!.complete(Unit)
        advanceUntilIdle()
        controller.refresh()
        controller.currentWeek()
        advanceUntilIdle()
        assertNotNull(controller.state.value.user)
        assertEquals(1, repo.loginCalls)
        assertEquals(0, repo.signCalls)
        assertEquals("", controller.state.value.password)
    }

    @Test fun failedAutomaticLoginReturnsEditableFormWithoutRetryLoop() = runTest {
        val repo = FakeRepository().apply { loginFailure = true }
        val store = MemoryStore().apply {
            saved = SavedCredentials("demo", "saved password", NetworkMode.DIRECT, autoLogin = true)
        }
        val controller = controller(repo, store)
        advanceUntilIdle()
        assertNull(controller.state.value.user)
        assertFalse(controller.state.value.busy)
        assertNotNull(controller.state.value.error)
        assertEquals("saved password", controller.state.value.password)
        assertEquals(1, repo.loginCalls)
        assertEquals(0, repo.loadCalls)
        assertEquals(0, repo.signCalls)
        assertEquals(0, store.writes)
        controller.setPassword("corrected password")
        controller.setMode(NetworkMode.VPN)
        advanceUntilIdle()
        assertEquals("corrected password", controller.state.value.password)
        assertEquals(NetworkMode.VPN, controller.state.value.mode)
        assertEquals(1, repo.loginCalls)
        repo.loginFailure = false
        controller.login()
        advanceUntilIdle()
        assertNotNull(controller.state.value.user)
        assertEquals(2, repo.loginCalls)
        assertEquals("corrected password", store.saved!!.password)
    }

    @Test fun enablingAutomaticLoginWaitsForAuthenticationAndDoesNotPersistDetectedStudentType() = runTest {
        val repo = FakeRepository().apply { loginFailure = true; detectedType = StudentType.UNDERGRADUATE }
        val store = MemoryStore()
        val controller = controller(repo, store)
        advanceUntilIdle()
        enter(controller)
        controller.setAutoLogin(true)
        assertTrue(controller.state.value.remember)
        assertTrue(controller.state.value.autoLogin)
        assertNull(store.saved)
        controller.login()
        advanceUntilIdle()
        assertNull(store.saved)
        repo.loginFailure = false
        controller.login()
        advanceUntilIdle()
        assertNull(repo.loginStudentType)
        assertEquals(StudentType.AUTO, store.saved!!.studentType)
        assertEquals(StudentType.UNDERGRADUATE, controller.state.value.studentType)
        assertTrue(store.saved!!.autoLogin)
        controller.close()
        val reopenedRepository = FakeRepository()
        val reopened = controller(reopenedRepository, store)
        advanceUntilIdle()
        assertEquals(1, reopenedRepository.loginCalls)
        assertEquals(StudentType.GRADUATE, reopened.state.value.studentType)
        assertNotNull(reopened.state.value.user)
    }

    @Test fun disablingAutomaticLoginDoesNotPersistUnverifiedEditedInputs() = runTest {
        val repo = FakeRepository().apply { loginFailure = true }
        val store = MemoryStore().apply {
            saved = SavedCredentials("previous", "verified password", NetworkMode.VPN, StudentType.UNDERGRADUATE, true)
        }
        val controller = controller(repo, store)
        advanceUntilIdle()
        controller.setUsername("unverified")
        controller.setPassword("unverified password")
        controller.setMode(NetworkMode.DIRECT)
        controller.setAutoLogin(false)
        advanceUntilIdle()
        assertFalse(controller.state.value.autoLogin)
        assertFalse(store.saved!!.autoLogin)
        assertEquals("previous", store.saved!!.username)
        assertEquals("verified password", store.saved!!.password)
        assertEquals(NetworkMode.VPN, store.saved!!.mode)
        assertEquals(StudentType.AUTO, store.saved!!.studentType)
        assertEquals("unverified", controller.state.value.username)
        assertEquals("unverified password", controller.state.value.password)
        assertEquals(1, repo.loginCalls)
    }

    @Test fun explicitLogoutDisablesAutomaticLoginAcrossRestartButKeepsRememberedDetails() = runTest {
        val repo = FakeRepository()
        val store = MemoryStore().apply {
            saved = SavedCredentials("demo", "saved password", NetworkMode.VPN, StudentType.UNDERGRADUATE, true)
        }
        val controller = controller(repo, store)
        advanceUntilIdle()
        controller.logout()
        advanceUntilIdle()
        assertNull(controller.state.value.user)
        assertFalse(controller.state.value.autoLogin)
        assertFalse(store.saved!!.autoLogin)
        assertEquals("saved password", controller.state.value.password)
        assertEquals(1, repo.loginCalls)
        controller.close()
        val reopenedRepo = FakeRepository()
        val reopened = controller(reopenedRepo, store)
        advanceUntilIdle()
        assertEquals(0, reopenedRepo.loginCalls)
        assertNull(reopened.state.value.user)
        assertEquals(StudentType.AUTO, reopened.state.value.studentType)
        assertTrue(reopened.state.value.remember)
    }

    @Test fun logoutClearsSavedConsentIfUpdatingAutomaticLoginFails() = runTest {
        val store = MemoryStore().apply {
            saved = SavedCredentials("demo", "saved password", NetworkMode.DIRECT, autoLogin = true)
        }
        val controller = controller(store = store)
        advanceUntilIdle()
        store.failSave = true
        controller.logout()
        advanceUntilIdle()
        assertNull(controller.state.value.user)
        assertNull(store.saved)
        assertFalse(controller.state.value.autoLogin)
        assertFalse(controller.state.value.remember)
    }

    @Test fun logoutDoesNotClaimSuccessWhenSavedConsentCannotBeDisabledOrRemoved() = runTest {
        val store = MemoryStore().apply {
            saved = SavedCredentials("demo", "saved password", NetworkMode.DIRECT, autoLogin = true)
        }
        val repo = FakeRepository()
        val controller = controller(repo, store)
        advanceUntilIdle()
        store.failSave = true
        store.failClear = true
        controller.logout()
        advanceUntilIdle()
        assertNotNull(controller.state.value.user)
        assertTrue(store.saved!!.autoLogin)
        assertNotNull(controller.state.value.error)
        assertEquals(0, repo.logoutCalls)
        assertFalse(controller.state.value.error!!.contains("secret provider detail"))
    }

    @Test fun uncheckingRememberAlsoClearsAutomaticLoginConsent() = runTest {
        val repo = FakeRepository().apply { loginFailure = true }
        val store = MemoryStore().apply {
            saved = SavedCredentials("demo", "saved password", NetworkMode.DIRECT, autoLogin = true)
        }
        val controller = controller(repo, store)
        advanceUntilIdle()
        controller.setRemember(false)
        advanceUntilIdle()
        assertNull(store.saved)
        assertFalse(controller.state.value.autoLogin)
        assertFalse(controller.state.value.remember)
        assertEquals("saved password", controller.state.value.password)
    }

    @Test fun logoutWithoutRememberingClearsPasswordAndSession() = runTest {
        val repo = FakeRepository()
        val store = MemoryStore()
        val controller = controller(repo, store)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        controller.logout()
        advanceUntilIdle()
        assertEquals("", controller.state.value.password)
        assertNull(store.saved)
        assertEquals(0, store.writes)
        assertEquals(1, repo.logoutCalls)
    }

    @Test fun uncheckPreservesInputsButExplicitClearEmptiesThem() = runTest {
        val store = MemoryStore().apply { saved = SavedCredentials("demo", "stored password", NetworkMode.DIRECT) }
        val controller = controller(store = store)
        advanceUntilIdle()
        controller.setRemember(false)
        advanceUntilIdle()
        assertFalse(controller.state.value.remember)
        assertNull(store.saved)
        assertEquals("stored password", controller.state.value.password)
        controller.clearSavedCredentials()
        advanceUntilIdle()
        assertEquals("", controller.state.value.username)
        assertEquals("", controller.state.value.password)
    }

    @Test fun failedClearDoesNotClaimThatCredentialsWereForgotten() = runTest {
        val store = MemoryStore().apply {
            saved = SavedCredentials("demo", "stored password", NetworkMode.DIRECT)
            failClear = true
        }
        val controller = controller(store = store)
        advanceUntilIdle()
        controller.setRemember(false)
        advanceUntilIdle()
        assertTrue(controller.state.value.remember)
        assertTrue(controller.state.value.hasSavedCredentials)
        assertNotNull(store.saved)
        assertFalse(controller.state.value.message!!.contains("secret provider detail"))
    }

    @Test fun readFailureLeavesClearActionAvailableAndDoesNotExposeProviderDetails() = runTest {
        val store = MemoryStore().apply { failRead = true }
        val controller = controller(store = store)
        advanceUntilIdle()
        assertTrue(controller.state.value.hasSavedCredentials)
        assertFalse(controller.state.value.remember)
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.message!!.contains("secret provider detail"))
    }

    @Test fun savingFailureDoesNotUndoSuccessfulLoginOrClaimPersistedCredentials() = runTest {
        val store = MemoryStore().apply { failSave = true }
        val controller = controller(store = store)
        advanceUntilIdle()
        enter(controller)
        controller.setRemember(true)
        controller.login()
        advanceUntilIdle()
        assertNotNull(controller.state.value.user)
        assertFalse(controller.state.value.hasSavedCredentials)
        assertNotNull(controller.state.value.message)
        assertFalse(controller.state.value.message!!.contains("secret provider detail"))
    }

    @Test fun duplicateLoginAndFormChangesAreBlockedDuringRequest() = runTest {
        val repo = FakeRepository().apply { loginGate = CompletableDeferred() }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        controller.login()
        controller.setPassword("changed")
        controller.setMode(NetworkMode.VPN)
        runCurrent()
        assertEquals(1, repo.loginCalls)
        assertEquals(" password with spaces ", controller.state.value.password)
        assertEquals(NetworkMode.DIRECT, controller.state.value.mode)
        repo.loginGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(controller.state.value.busy)
    }

    @Test fun failedWeekNavigationCannotDisplayPreviousWeeksCourses() = runTest {
        val repo = FakeRepository()
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        assertNotNull(controller.state.value.schedule)
        repo.loadFailure = true
        controller.selectWeek(5)
        advanceUntilIdle()
        assertEquals(5, controller.state.value.week)
        assertNull(controller.state.value.schedule)
        assertNotNull(controller.state.value.error)
    }

    @Test fun failedSameWeekRefreshKeepsClassesButCannotReusePreviousVerification() = runTest {
        val repo = FakeRepository()
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        val previous = controller.state.value.schedule!!
        assertEquals(AcademicStatus.VERIFIED, previous.academic!!.status)
        repo.loadFailure = true
        repo.loadGate = CompletableDeferred()
        controller.refresh()
        runCurrent()
        assertEquals(previous.days.map { day -> day.copy(courses = day.courses.map { it.copy(attendanceAvailable = false) }) },
            controller.state.value.schedule!!.days)
        assertNull(controller.state.value.schedule!!.academic)
        repo.loadGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(controller.state.value.busy)
        assertNotNull(controller.state.value.error)
        controller.signCourse(previous.days.first().courses.first())
        advanceUntilIdle()
        assertEquals(0, repo.signCalls)
        assertFalse(controller.state.value.schedule!!.days.first().courses.first().attendanceAvailable)
        repo.loadFailure = false
        controller.refresh()
        advanceUntilIdle()
        assertEquals(AcademicStatus.VERIFIED, controller.state.value.schedule!!.academic!!.status)
        assertTrue(controller.state.value.schedule!!.days.first().courses.first().attendanceAvailable)
    }

    @Test fun navigationUsesReturnedWeeksAndDoesNotClampToEighteen() = runTest {
        val repo = FakeRepository()
        val controller = AssistantController(repo, MemoryStore(), object : AssistantPreferences {
            override fun semesterStart(): LocalDate = start.plusYears(1)
        }, this, StandardTestDispatcher(testScheduler), { start.minusDays(1) })
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        assertEquals(2, controller.state.value.week)
        assertEquals(0, repo.lastWeek)
        controller.selectWeek(100)
        advanceUntilIdle()
        assertEquals(2, controller.state.value.week)
        controller.selectWeek(25)
        advanceUntilIdle()
        assertEquals(25, controller.state.value.week)
        assertEquals(25, repo.lastWeek)
        assertEquals(start, controller.state.value.semesterStart)
        assertEquals(start, repo.lastSemesterStart)
    }

    @Test fun officialTimetableIsVisibleBeforeAttendanceAndUsesReturnedCalendarAndIdentity() = runTest {
        val calendarStart = LocalDate.of(2027, 2, 22)
        val repo = FakeRepository().apply {
            automaticWeek = 20
            detectedType = StudentType.UNDERGRADUATE
            returnedStart = calendarStart
            linkGate = CompletableDeferred()
        }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        runCurrent()
        val visible = controller.state.value
        assertNotNull(visible.user)
        assertTrue(visible.busy)
        assertEquals(20, visible.week)
        assertEquals(calendarStart, visible.semesterStart)
        assertEquals(StudentType.UNDERGRADUATE, visible.studentType)
        assertEquals(30, visible.schedule!!.weeks.size)
        assertEquals(1, visible.schedule.total)
        assertFalse(visible.schedule.days.first().courses.single().attendanceAvailable)
        assertEquals(0, repo.lastWeek)
        assertEquals(1, repo.linkCalls)
        assertTrue(repo.selectedStudentTypes.isEmpty())
        repo.linkGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(controller.state.value.schedule!!.days.first().courses.single().attendanceAvailable)
        assertEquals(0, repo.signCalls)
    }

    @Test fun attendanceFailureKeepsOfficialCoursesAndNeverUndoesSuccessfulLogin() = runTest {
        val repo = FakeRepository().apply { linkFailure = true }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        val visible = controller.state.value
        assertNotNull(visible.user)
        assertNull(visible.error)
        assertEquals(1, visible.schedule!!.total)
        assertEquals(AcademicStatus.VERIFIED, visible.schedule.academic!!.status)
        assertTrue(visible.schedule.academic.verifiedScheduleIds.isEmpty())
        assertFalse(visible.schedule.days.first().courses.single().attendanceAvailable)
        assertNotNull(visible.schedule.attendanceMessage)
        assertFalse(visible.schedule.attendanceMessage!!.contains("private"))
        assertEquals(1, repo.loadCalls)
        assertEquals(1, repo.linkCalls)
        assertEquals(0, repo.logoutCalls)
        controller.signCourse(visible.schedule.days.first().courses.single().copy(attendanceAvailable = true))
        advanceUntilIdle()
        assertEquals(0, repo.signCalls)
    }

    @Test fun officialLoadFailureNeverStartsAttendanceOrDisplaysIclassAsTimetable() = runTest {
        val repo = FakeRepository().apply { loadFailure = true }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        assertNotNull(controller.state.value.user)
        assertNull(controller.state.value.schedule)
        assertNotNull(controller.state.value.error)
        assertEquals(0, repo.linkCalls)
        assertEquals(0, repo.signCalls)
    }

    @Test fun currentWeekRequestsCalendarSelectionAndNavigationRejectsMissingWeekNumbers() = runTest {
        val repo = FakeRepository().apply { weekNumbers = listOf(2, 20, 25) }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        controller.selectWeek(20)
        advanceUntilIdle()
        assertEquals(20, repo.lastWeek)
        controller.selectWeek(21)
        advanceUntilIdle()
        assertEquals(20, controller.state.value.week)
        assertEquals(20, repo.lastWeek)
        repo.automaticWeek = 25
        controller.currentWeek()
        advanceUntilIdle()
        assertEquals(0, repo.lastWeek)
        assertEquals(25, controller.state.value.week)
    }

    @Test fun officialClassWithoutIclassMappingCannotSubmitAttendanceEvenThroughAStaleAction() = runTest {
        val repo = FakeRepository().apply { courses = listOf(Course("official", "教务课程", attendanceAvailable = false)) }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        val course = controller.state.value.schedule!!.days.first().courses.first()
        controller.signCourse(course.copy(attendanceAvailable = true))
        advanceUntilIdle()
        assertEquals(0, repo.signCalls)
        assertTrue(controller.state.value.message!!.contains("暂不能签到"))
    }

    @Test fun successfulSignRemainsVisibleWhenRefreshFailsAndCannotBeSubmittedAgain() = runTest {
        val repo = FakeRepository()
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        repo.loadFailure = true
        val selected = controller.state.value.schedule!!.days.first().courses.first()
        controller.signCourse(selected)
        advanceUntilIdle()
        assertEquals(1, controller.state.value.schedule!!.signed)
        assertNotNull(controller.state.value.error)
        controller.signCourse(selected)
        advanceUntilIdle()
        assertEquals(1, repo.signCalls)
    }

    @Test fun failedPostAttendanceRefreshKeepsOtherCoursesUnsignedAndClearsOldComparison() = runTest {
        val repo = FakeRepository().apply {
            courses = courses + Course("remaining", "另一门课程")
            academic = academic!!.copy(verifiedScheduleIds = academic!!.verifiedScheduleIds + "remaining")
        }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        repo.loadFailure = true
        repo.loadGate = CompletableDeferred()
        val beforeRefresh = controller.state.value.schedule!!.days.first().courses
        controller.signCourse(beforeRefresh.first())
        runCurrent()
        assertTrue(controller.state.value.busy)
        assertTrue(controller.state.value.schedule!!.days.flatMap { it.courses }.none { it.attendanceAvailable })
        repo.loadGate!!.complete(Unit)
        advanceUntilIdle()
        val retained = controller.state.value.schedule!!
        assertEquals(2, retained.total)
        assertEquals(1, retained.signed)
        assertNull(retained.academic)
        assertNotNull(controller.state.value.error)
        assertFalse(retained.days.first().courses.last().signed)
        assertTrue(retained.days.flatMap { it.courses }.none { it.attendanceAvailable })
        controller.signCourse(beforeRefresh.last())
        advanceUntilIdle()
        assertEquals(1, repo.signCalls)
        assertEquals(listOf("one"), repo.submittedCourses.map { it.id })
    }

    @Test fun mergedTeacherSuccessAndAlreadySignedResultsUpdateParentCourse() = runTest {
        val repo = FakeRepository().apply { signItems = listOf(SignItem("teacher-two", "演示课程", SignOutcome.SKIPPED)) }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        controller.signCourse(controller.state.value.schedule!!.days.first().courses.first())
        advanceUntilIdle()
        assertEquals(0, controller.state.value.schedule!!.signed)
        assertEquals(setOf("teacher-two"), controller.state.value.schedule!!.days.first().courses.first().completedIds)
    }

    @Test fun singleCourseActionUsesOnlyTheSelectedCoursesCurrentScheduleIds() = runTest {
        val repo = FakeRepository().apply {
            courses = listOf(
                Course("merged", "已核对课程", scheduleIds = listOf("teacher-one", "teacher-two")),
                Course("uncertain", "待核对课程"),
                Course("signed", "已签到课程", signed = true),
            )
            signItems = listOf(SignItem("teacher-two", "已核对课程", SignOutcome.SUCCESS))
        }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        val selected = controller.state.value.schedule!!.days.first().courses.first()
        // Even a stale/tampered callback cannot add another course's ID.
        controller.signCourse(selected.copy(scheduleIds = selected.scheduleIds + "uncertain"))
        advanceUntilIdle()
        assertEquals(listOf(selected), repo.submittedCourses)
        assertEquals(3, controller.state.value.schedule!!.total)
        assertEquals(1, controller.state.value.schedule!!.signed)
    }

    @Test fun busySingleCourseActionCannotBeSubmittedAgainOrSwitchToAnotherCourse() = runTest {
        val repo = FakeRepository().apply {
            courses = courses + Course("other", "另一门课程")
            signGate = CompletableDeferred()
        }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        val courses = controller.state.value.schedule!!.days.first().courses
        controller.signCourse(courses.first())
        controller.signCourse(courses.first())
        controller.signCourse(courses.last())
        runCurrent()
        assertTrue(controller.state.value.busy)
        assertEquals(1, repo.signCalls)
        assertEquals(listOf(courses.first()), repo.submittedCourses)
        repo.signGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repo.signCalls)
        assertEquals(1, controller.state.value.schedule!!.signed)
        assertFalse(controller.state.value.schedule!!.days.first().courses.last().signed)
    }

    @Test fun reusedPrimaryIdCannotSelectAnotherNamedCoursesTeacherSchedules() = runTest {
        val repo = FakeRepository().apply {
            courses = listOf(
                Course("shared", "数学", courseNum = "C1", classBeginTime = "08:00", classEndTime = "09:35",
                    scheduleIds = listOf("shared", "teacher-A")),
                Course("shared", "物理", courseNum = "C1", classBeginTime = "08:00", classEndTime = "09:35",
                    scheduleIds = listOf("shared", "teacher-B")),
            )
            signItems = listOf(SignItem("teacher-B", "物理", SignOutcome.SUCCESS))
        }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        val selected = controller.state.value.schedule!!.days.first().courses.last()
        controller.signCourse(selected)
        advanceUntilIdle()
        assertEquals(listOf(selected), repo.submittedCourses)
        assertEquals(listOf("shared", "teacher-B"), repo.submittedCourses.single().scheduleIds)
        assertEquals(0, controller.state.value.schedule!!.signed)
        assertFalse(controller.state.value.schedule!!.days.first().courses.first().signed)
        assertFalse(controller.state.value.schedule!!.days.first().courses.last().signed)
    }

    @Test fun hiddenCourseCannotBeSubmittedByStaleSingleCourseAction() = runTest {
        val hidden = Course("withdrawn", "已退课程")
        val repo = FakeRepository().apply {
            academic = academic!!.copy(excluded = listOf(hidden))
        }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        controller.signCourse(hidden)
        advanceUntilIdle()
        assertEquals(0, repo.signCalls)
    }

    @Test fun fallbackCourseIsVisibleButCannotSignUntilOfficialTimetableIsAvailable() = runTest {
        for (status in listOf(AcademicStatus.UNAVAILABLE, AcademicStatus.SELECT_TERM)) {
            val repo = FakeRepository().apply {
                academic = AcademicComparison(status)
                courses = courses.map { it.copy(attendanceAvailable = false) }
            }
            val controller = controller(repo)
            advanceUntilIdle()
            enter(controller)
            controller.login()
            advanceUntilIdle()
            val shown = controller.state.value.schedule!!.days.first().courses.first()
            controller.signCourse(shown.copy(attendanceAvailable = true))
            advanceUntilIdle()
            assertEquals(0, repo.signCalls)
            assertFalse(shown.attendanceAvailable)
        }
    }

    @Test fun selectingAcademicTermRequestsItsAutomaticCurrentWeek() = runTest {
        val repo = FakeRepository()
        val controller = controller(repo)
        advanceUntilIdle()
        controller.selectAcademicTerm("term-two")
        advanceUntilIdle()
        assertTrue(repo.selectedTerms.isEmpty())
        enter(controller)
        controller.login()
        advanceUntilIdle()
        controller.selectWeek(6)
        advanceUntilIdle()
        repo.loadGate = CompletableDeferred()
        controller.selectAcademicTerm("term-two")
        runCurrent()
        assertTrue(controller.state.value.busy)
        assertNull(controller.state.value.schedule)
        controller.signCourse(Course("one", "演示课程"))
        controller.selectAcademicTerm("term-three")
        controller.selectWeek(7)
        assertEquals(listOf("term-two"), repo.selectedTerms)
        assertEquals(0, repo.signCalls)
        repo.loadGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, controller.state.value.week)
        assertEquals(0, repo.lastWeek)
        assertEquals(start, repo.lastSemesterStart)
        assertEquals(start, controller.state.value.semesterStart)
        assertEquals("term-two", controller.state.value.schedule!!.academic!!.termCode)
    }

    @Test fun failedAcademicTermReloadCannotReusePreviousVerification() = runTest {
        val repo = FakeRepository()
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        repo.loadFailure = true
        controller.selectAcademicTerm("term-two")
        advanceUntilIdle()
        assertNull(controller.state.value.schedule)
        assertNotNull(controller.state.value.error)
        controller.signCourse(Course("one", "演示课程"))
        advanceUntilIdle()
        assertEquals(0, repo.signCalls)
    }

    @Test fun attendanceRefreshUsesNewFilteredScheduleAndKeepsComparisonMetadata() = runTest {
        val removed = Course("removed", "已退课程")
        val repo = FakeRepository().apply { courses = courses + removed }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        val target = controller.state.value.schedule!!.days.first().courses.first()
        val newComparison = AcademicComparison(AcademicStatus.VERIFIED, termCode = "term-one",
            excluded = listOf(removed), verifiedScheduleIds = setOf("one", "teacher-two"))
        repo.courses = listOf(target)
        repo.academic = newComparison
        controller.signCourse(target)
        advanceUntilIdle()
        val refreshed = controller.state.value.schedule!!
        assertEquals(listOf("one"), refreshed.days.flatMap { it.courses }.map { it.id })
        assertEquals(1, refreshed.signed)
        assertEquals(newComparison, refreshed.academic)
        controller.signCourse(removed)
        advanceUntilIdle()
        assertEquals(1, repo.signCalls)
    }

    @Test fun closeCancelsPendingLoginAndErasesInMemoryCredentials() = runTest {
        val repo = FakeRepository().apply { loginGate = CompletableDeferred() }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        runCurrent()
        controller.close()
        repo.loginGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(controller.state.value.user)
        assertEquals("", controller.state.value.password)
        assertEquals("", controller.state.value.username)
    }

    @Test fun errorDismissalDoesNotConsumeAnIndependentSnackbarMessage() = runTest {
        val repo = FakeRepository().apply { loginFailure = true }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.clearSavedCredentials()
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        assertNotNull(controller.state.value.error)
        assertNotNull(controller.state.value.message)
        controller.dismissError()
        assertNull(controller.state.value.error)
        assertNotNull(controller.state.value.message)
        controller.dismissMessage()
        assertNull(controller.state.value.message)
    }

    @Test fun perCourseFailureReasonIsVisibleWithoutSensitiveLoginInputs() = runTest {
        val reason = "当前不在可签到时段，请核对课程时间"
        val repo = FakeRepository().apply {
            signItems = listOf(SignItem("one", "演示课程", SignOutcome.FAILED, reason))
        }
        val controller = controller(repo)
        advanceUntilIdle()
        enter(controller)
        controller.login()
        advanceUntilIdle()
        controller.signCourse(controller.state.value.schedule!!.days.first().courses.first())
        advanceUntilIdle()
        assertTrue(controller.state.value.logs.any {
            it.level == "warning" && it.message.contains("演示课程") && it.message.contains(reason)
        })
        assertFalse(controller.state.value.logs.any {
            it.message.contains("demo") || it.message.contains("password with spaces")
        })
        assertEquals(0, controller.state.value.schedule!!.signed)
    }

    @Test fun emptyPerCourseMessagesUseSelectedLanguageFallbacks() = runTest {
        for (english in listOf(false, true)) {
            val repo = FakeRepository().apply {
                courses = listOf(Course("one", "演示课程", scheduleIds = listOf("one", "two", "three")))
                signItems = listOf(
                    SignItem("one", "成功课程", SignOutcome.SUCCESS),
                    SignItem("two", "跳过课程", SignOutcome.SKIPPED),
                    SignItem("three", "失败课程", SignOutcome.FAILED),
                )
            }
            val controller = controller(repo)
            advanceUntilIdle()
            if (english) controller.toggleLanguage()
            enter(controller)
            controller.login()
            advanceUntilIdle()
            controller.signCourse(controller.state.value.schedule!!.days.first().courses.first())
            advanceUntilIdle()
            val logs = controller.state.value.logs
            assertTrue(logs.any { it.level == "success" && it.message.contains(if (english) "Attendance submitted" else "签到成功") })
            assertTrue(logs.any { it.level == "info" && it.message.contains(if (english) "Already signed" else "该课程已签到") })
            assertTrue(logs.any { it.level == "warning" && it.message.contains(if (english) "Attendance failed" else "签到失败") })
        }
    }
}
