package org.buaacourse.assistant

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AttendanceNotRequiredTest {
    private val date = LocalDate.of(2026, 9, 7)
    private val term = AcademicTerm("20261", "2026秋季")
    private val lesson = AcademicLesson("M1", "B1", "数学", date, "08:00", "09:35")
    private fun raw(courses: List<Course> = emptyList(), failed: Boolean = false) = WeekSchedule(1, date,
        (0L..6L).map { DaySchedule(date.plusDays(it), if (it == 0L) courses else emptyList(), failed && it == 6L) })
    private fun official() = AcademicScheduleMapper.merge(raw(), listOf(lesson), term, listOf(term))
    private fun course(schedule: WeekSchedule) = schedule.days.first().courses.single()

    @Test fun verifiedAbsenceIsNotAnActualSignInAndCannotSubmit() {
        assertFalse(course(official()).attendanceNotRequired) // Not queried yet.
        val result = AcademicScheduleMapper.linkOfficial(official(), raw())
        val course = course(result)
        assertTrue(course.attendanceNotRequired)
        assertFalse(course.attendanceAvailable)
        assertFalse(course.signed)
        assertTrue(course.scheduleIds.isEmpty())
        assertEquals(0, result.signed)
        assertEquals(1, result.notRequired)
        assertEquals(0, result.academic!!.uncertainCount)
    }
    @Test fun failedDayDoesNotProveAbsenceEvenForOtherDays() {
        val result = AcademicScheduleMapper.linkOfficial(official(), raw(failed = true))
        assertFalse(course(result).attendanceNotRequired)
        assertEquals(1, result.academic!!.uncertainCount)
    }
    @Test fun RelatedButUnmatchedRecordRemainsPending() {
        for (row in listOf(
            Course("real", "数学", "M1", "10:00", "11:35"),
            Course("real", "数学别名", "M1", "08:00", "09:35"),
            Course("real", "数学", "OTHER", "08:00", "09:35"),
            Course("real", "", "", "08:00", "09:35"),
            Course("real", "", "", "", ""),
        )) assertFalse(course(AcademicScheduleMapper.linkOfficial(official(), raw(listOf(row)))).attendanceNotRequired)
    }
    @Test fun unrelatedRecordsDoNotPreventNoAttendanceStatusAndNewScheduleRestoresSigning() {
        val unrelated = Course("other", "物理", "P1", "10:00", "11:35")
        assertTrue(course(AcademicScheduleMapper.linkOfficial(official(), raw(listOf(unrelated)))).attendanceNotRequired)
        val newRecord = Course("new", "数学", "M1", "08:00", "09:35")
        val linked = course(AcademicScheduleMapper.linkOfficial(official(), raw(listOf(newRecord))))
        assertTrue(linked.attendanceAvailable)
        assertFalse(linked.attendanceNotRequired)
    }
}
