package org.buaacourse.assistant

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AttendanceTotalsTest {
    private val date = LocalDate.of(2026, 9, 7)
    private fun schedule(vararg courses: Course) = WeekSchedule(1, date, listOf(DaySchedule(date, courses.toList())))

    @Test fun splitSessionsAndNotRequiredCoursesUseActualIclassDenominator() {
        val slots = (1..3).map { Course("slot-$it", "数学", signed = it == 1) }
        val parent = Course("academic:math", "数学", attendanceSessions = slots)
        val noAttendance = Course("academic:none", "无签到课程", attendanceAvailable = false, attendanceNotRequired = true, scheduleIds = emptyList())
        val unlinked = Course("academic:unknown", "待确认", attendanceAvailable = false, scheduleIds = emptyList())
        val result = schedule(parent, noAttendance, unlinked)
        assertEquals(3, result.attendanceTotal)
        assertEquals(1, result.attendanceSigned)
        assertEquals(3, result.total) // Number of timetable courses remains separate.
    }
    @Test fun severalTeachersInOneSessionRequireOnlyOneButtonPress() {
        val course = Course("a", "数学", scheduleIds = listOf("a", "b", "c"), signedScheduleIds = setOf("a"))
        assertEquals(1, schedule(course).attendanceTotal)
        assertEquals(0, schedule(course).attendanceSigned)
        assertEquals(0, schedule(course.applyAttendance(setOf("b"))).attendanceSigned)
        assertEquals(1, schedule(course.applyAttendance(setOf("b", "c"))).attendanceSigned)
    }
    @Test fun splitPeriodsWithSeveralTeachersAreTwoClicksNotTeacherCount() {
        val rows = org.json.JSONArray("""[
            {"id":"first-a","courseName":"数学","courseNum":"M1","classBeginTime":"08:00","classEndTime":"09:35","signStatus":"1"},
            {"id":"first-b","courseName":"数学","courseNum":"M1","classBeginTime":"08:00","classEndTime":"09:35","signStatus":"1"},
            {"id":"second-a","courseName":"数学","courseNum":"M1","classBeginTime":"09:50","classEndTime":"11:25","signStatus":"0"},
            {"id":"second-b","courseName":"数学","courseNum":"M1","classBeginTime":"09:50","classEndTime":"11:25","signStatus":"0"}
        ]""")
        val sessions = IclassProtocol.parseCourses(rows)
        val result = schedule(Course("academic:merged", "数学", attendanceSessions = sessions))
        assertEquals(2, sessions.size)
        assertEquals(2, result.attendanceTotal)
        assertEquals(1, result.attendanceSigned)
    }
    @Test fun duplicateIdsDoNotInflateTotalsAndEmptyTimetableHasZeroCounts() {
        val record = Course("a", "数学", scheduleIds = listOf("a", "a"), signed = true)
        assertEquals(1, schedule(record, record).attendanceTotal)
        assertEquals(1, schedule(record, record).attendanceSigned)
        assertEquals(0, schedule().attendanceTotal)
        assertEquals(0, schedule().attendanceSigned)
    }
}
