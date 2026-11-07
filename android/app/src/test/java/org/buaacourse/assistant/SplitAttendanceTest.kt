package org.buaacourse.assistant

import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SplitAttendanceTest {
    private val date = LocalDate.of(2026, 9, 7)
    private val term = AcademicTerm("20261", "2026秋季")
    private val lesson = AcademicLesson("C1", "B1", "数学", date, "08:50", "12:15")
    private val first = Course("first", "数学", "C1", "$date 08:50:00", "$date 10:35:00")
    private val second = Course("second", "数学", "C1", "$date 10:40:00", "$date 12:15:00")
    private fun merged(vararg rows: Course) = AcademicScheduleMapper.merge(
        WeekSchedule(1, date, listOf(DaySchedule(date, rows.toList()))), listOf(lesson), term, listOf(term))

    @Test fun fourPeriodsHaveTwoIndependentSessionsAndPartialProgress() {
        val course = merged(first.copy(signed = true), second).days.first().courses.single()
        assertTrue(course.attendanceAvailable)
        assertEquals(listOf("first", "second"), course.attendanceSessions.map { it.id })
        assertFalse(course.signed)
        assertEquals("已签 1/2", course.attendanceProgress)
        assertTrue(course.applyAttendance(setOf("second")).signed)
        assertEquals("✓ 已签 2/2", course.applyAttendance(setOf("second")).attendanceProgress)
    }
    @Test fun overlappingDifferentClassesAndReusedIdsCannotBeSilentlySelected() {
        assertFalse(merged(first, first.copy(id = "other", scheduleIds = listOf("other"))).days.first().courses.single().attendanceAvailable)
        assertFalse(merged(first.copy(courseNum = "different"), second.copy(courseName = "别的课")).days.first().courses.single().attendanceAvailable)
        assertFalse(merged(first, second.copy(id = "first", scheduleIds = listOf("first"))).days.first().courses.single().attendanceAvailable)
        assertFalse(merged(first.copy(classBeginTime = "08:00")).days.first().courses.single().attendanceAvailable)
    }
    @Test fun partialTeacherResultDoesNotCompleteSession() {
        val session = first.copy(scheduleIds = listOf("first", "teacher"))
        val partial = session.applyAttendance(setOf("first"))
        assertFalse(partial.signed)
        assertEquals(setOf("first"), partial.completedIds)
        assertTrue(partial.applyAttendance(setOf("teacher")).signed)
    }
    @Test fun controllerSubmitsOnlySelectedSessionAndPreservesOtherSessionOnRefresh() = runTest {
        var posted = emptyList<Course>()
        val linked = merged(first, second)
        val repo = object : CourseRepository {
            override suspend fun login(username: String, password: String, mode: NetworkMode) = UserSession("user", "演示")
            override suspend fun loadWeek(week: Int, semesterStart: LocalDate) = linked
            override suspend fun linkAttendance(schedule: WeekSchedule) = linked
            override suspend fun signCourses(courses: List<Course>): SignSummary {
                posted = courses
                return SignSummary(courses.flatMap { c -> c.realIds.map { SignItem(it, c.courseName, SignOutcome.SUCCESS) } })
            }
            override fun logout() {}
        }
        val store = object : SavedCredentialsStore {
            override fun read(): SavedCredentials? = null
            override fun save(credentials: SavedCredentials) {}
            override fun clear() {}
        }
        val controller = AssistantController(repo, store, object : AssistantPreferences {}, this, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.setUsername("u"); controller.setPassword("p"); controller.login(); advanceUntilIdle()
        var parent = controller.state.value.schedule!!.days.first().courses.single()
        controller.signCourse(parent) // A merged parent is never submitted as a batch.
        advanceUntilIdle(); assertTrue(posted.isEmpty())
        controller.signCourse(parent.attendanceSessions.first()); advanceUntilIdle()
        assertEquals(listOf("first"), posted.map { it.id })
        parent = controller.state.value.schedule!!.days.first().courses.single()
        assertEquals("已签 1/2", parent.attendanceProgress)
        assertFalse(parent.attendanceSessions.last().signed)
        controller.signCourse(parent.attendanceSessions.last()); advanceUntilIdle()
        assertEquals(listOf("second"), posted.map { it.id })
        assertTrue(controller.state.value.schedule!!.days.first().courses.single().signed)
        controller.close()
    }
}
