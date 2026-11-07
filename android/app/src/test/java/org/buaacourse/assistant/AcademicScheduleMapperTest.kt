package org.buaacourse.assistant

import java.time.LocalDate
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AcademicScheduleMapperTest {
    private val start = LocalDate.of(2026, 9, 7)
    private val term = AcademicTerm("2026-1", "2026-2027学年第一学期")
    private val lesson = AcademicLesson("C1", "B1", "数学", start, "08:00", "09:35", "J1", listOf("教师"))
    private val iclass = Course("real-id", "数学", "C1", "2026-09-07 08:00:00", "2026-09-07 09:35:00")
    private fun merge(lessons: List<AcademicLesson>, vararg courses: Course) = AcademicScheduleMapper.merge(
        WeekSchedule(1, start, listOf(DaySchedule(start, courses.toList()))), lessons, term, listOf(term))

    @Test fun officialCoursesWithoutIclassAreVisibleButNeverReceiveSyntheticAttendanceIds() {
        val result = merge(listOf(lesson))
        val course = result.days.first().courses.single()
        assertEquals("数学", course.courseName)
        assertFalse(course.attendanceAvailable)
        assertTrue(course.scheduleIds.isEmpty())
        assertTrue(result.academic!!.verifiedScheduleIds.isEmpty())
        assertEquals(7, result.days.size)
    }
    @Test fun exactUniqueMatchRetainsRealAttendanceStateAndOfficialMetadata() {
        val result = merge(listOf(lesson), iclass.copy(signed = true, scheduleIds = listOf("real-id", "teacher-id")), iclass.copy(id = "retired", courseNum = "OLD", scheduleIds = listOf("retired")))
        val course = result.days.first().courses.single()
        assertEquals("real-id", course.id)
        assertEquals(listOf("real-id", "teacher-id"), course.scheduleIds)
        assertTrue(course.attendanceAvailable)
        assertTrue(course.signed)
        assertEquals("J1", course.classroomName)
        assertEquals("retired", result.academic!!.excluded.single().id)
    }
    @Test fun codeNameOrTimeMismatchAndMultipleMatchesCannotAuthorizeSigning() {
        for (candidate in listOf(iclass.copy(courseNum = "wrong"), iclass.copy(courseName = "另一课程"), iclass.copy(classEndTime = "10:35"))) {
            assertFalse(merge(listOf(lesson), candidate).days.first().courses.single().attendanceAvailable)
        }
        assertFalse(merge(listOf(lesson), iclass, iclass.copy(id = "other-class")).days.first().courses.single().attendanceAvailable)
        assertTrue(merge(listOf(lesson, lesson.copy(classCode = "B2")), iclass).days.first().courses.none { it.attendanceAvailable })
    }
    @Test fun verifiedEmptyOfficialWeekDoesNotKeepStaleIclassCourses() {
        val result = merge(emptyList(), iclass)
        assertEquals(0, result.total)
        assertEquals(AcademicStatus.VERIFIED, result.academic!!.status)
        assertEquals(1, result.academic!!.excluded.size)
    }

    @Test fun reusedPrimaryOrTeacherIdsCannotAuthorizeAnOtherwiseUniqueMatch() {
        for (conflicting in listOf(
            iclass.copy(courseNum = "OLD", courseName = "已退课程"),
            iclass.copy(id = "other-id", courseNum = "OLD", scheduleIds = listOf(" real-id ")),
            iclass.copy(id = "other-id", courseNum = "OLD", scheduleIds = listOf("teacher-id")),
        )) {
            val result = merge(listOf(lesson), iclass.copy(scheduleIds = listOf("real-id", "teacher-id")), conflicting)
            val displayed = result.days.first().courses.single()
            assertFalse(displayed.attendanceAvailable)
            assertFalse(displayed.signed)
            assertTrue(displayed.scheduleIds.isEmpty())
            assertTrue(result.academic!!.verifiedScheduleIds.isEmpty())
        }
    }

    @Test fun reusedIdsAcrossDatesRemainAmbiguousEvenIfBothCoursesMatch() {
        val tomorrow = start.plusDays(1)
        val second = iclass.copy(classBeginTime = "$tomorrow 08:00:00", classEndTime = "$tomorrow 09:35:00")
        val raw = WeekSchedule(1, start, listOf(DaySchedule(start, listOf(iclass)), DaySchedule(tomorrow, listOf(second))))
        val result = AcademicScheduleMapper.merge(raw, listOf(lesson, lesson.copy(date = tomorrow)), term, listOf(term))
        val displayed = result.days.flatMap { it.courses }
        assertEquals(2, displayed.size)
        assertTrue(displayed.none { it.attendanceAvailable })
        assertTrue(displayed.all { it.scheduleIds.isEmpty() })
        assertTrue(result.academic!!.verifiedScheduleIds.isEmpty())
    }

    @Test fun repeatedIdsInsideOneRecordAreNormalizedWithoutInventingConflicts() {
        val result = merge(listOf(lesson), iclass.copy(id = " real-id ", scheduleIds = listOf("real-id", " real-id ", " ")))
        val course = result.days.first().courses.single()
        assertTrue(course.attendanceAvailable)
        assertEquals("real-id", course.id)
        assertEquals(listOf("real-id"), course.scheduleIds)
        assertEquals(setOf("real-id"), result.academic!!.verifiedScheduleIds)
    }

    @Test fun differentEndTimesCannotAddUnmatchedAttendanceIdsBeforeMapping() {
        val raw = IclassProtocol.parseCourses(JSONArray("""[
            {"id":"exact-id","courseName":"数学","courseNum":"C1","classBeginTime":"2026-09-07 08:00:00","classEndTime":"2026-09-07 09:35:00","classroomName":"J1"},
            {"id":"longer-id","courseName":"数学","courseNum":"C1","classBeginTime":"2026-09-07 08:00:00","classEndTime":"2026-09-07 10:35:00","classroomName":"J1"}
        ]"""))
        val result = merge(listOf(lesson), *raw.toTypedArray())
        val course = result.days.first().courses.single()
        assertTrue(course.attendanceAvailable)
        assertEquals(listOf("exact-id"), course.scheduleIds)
        assertEquals(setOf("exact-id"), result.academic!!.verifiedScheduleIds)
        assertEquals("longer-id", result.academic!!.excluded.single().id)
    }

    @Test fun unknownDateAndMissingActualTimesRemainVisibleWithoutAttendance() {
        val result = merge(listOf(
            lesson.copy(date = null, beginSection = 1, endSection = 2),
            lesson.copy(startTime = "", endTime = "", beginSection = 3, endSection = 4),
        ), iclass)
        assertEquals(2, result.total)
        assertEquals(1, result.unscheduled.size)
        assertFalse(result.unscheduled.single().attendanceAvailable)
        assertEquals(1, result.unscheduled.single().beginSection)
        assertFalse(result.unscheduled.single().classBeginTime.contains("null"))
        val timed = result.days.first().courses.single()
        assertEquals("", timed.classBeginTime)
        assertEquals("", timed.classEndTime)
        assertEquals(3, timed.beginSection)
        assertEquals(4, timed.endSection)
        assertFalse(timed.attendanceAvailable)
        assertTrue(result.academic!!.verifiedScheduleIds.isEmpty())
    }

    @Test fun linkingRetainsOfficialCalendarSectionsAndUnscheduledCourses() {
        val calendar = listOf(AcademicWeek(1, start, start.plusDays(6), true))
        val periods = listOf(AcademicPeriod(1, "08:00", "08:45"), AcademicPeriod(2, "08:50", "09:35"))
        val official = merge(listOf(lesson.copy(beginSection = 1, endSection = 2), lesson.copy(name = "待安排课程", date = null)))
            .copy(studentType = StudentType.UNDERGRADUATE, weeks = calendar, sections = periods)
        val raw = WeekSchedule(99, start.plusYears(1), listOf(DaySchedule(start, listOf(iclass, iclass.copy(id = "retired", courseNum = "OLD", scheduleIds = listOf("retired"))))))
        val linked = AcademicScheduleMapper.linkOfficial(official, raw)
        assertEquals(StudentType.UNDERGRADUATE, linked.studentType)
        assertEquals(calendar, linked.weeks)
        assertEquals(periods, linked.sections)
        assertEquals(1, linked.week)
        assertEquals(start, linked.semesterStart)
        assertEquals(2, linked.total)
        assertEquals("待安排课程", linked.unscheduled.single().courseName)
        assertFalse(linked.unscheduled.single().attendanceAvailable)
        assertTrue(linked.days.first().courses.single().attendanceAvailable)
        assertEquals(1, linked.days.first().courses.single().beginSection)
        assertEquals(listOf("real-id"), linked.days.first().courses.single().scheduleIds)
        assertEquals("retired", linked.academic!!.excluded.single().id)
    }

    @Test fun periodOnlyOfficialCourseDoesNotBorrowClockTimesFromIclass() {
        val official = merge(listOf(lesson.copy(startTime = "", endTime = "", beginSection = 1, endSection = 2)))
        val raw = WeekSchedule(1, start, listOf(DaySchedule(start, listOf(iclass))))
        val linked = AcademicScheduleMapper.linkOfficial(official, raw)
        val course = linked.days.first().courses.single()
        assertFalse(course.attendanceAvailable)
        assertTrue(course.scheduleIds.isEmpty())
        assertEquals("", course.classBeginTime)
        assertEquals(1, course.beginSection)
    }

    @Test fun explicitReturnedWeekDatesDetermineDisplayedDays() {
        val shiftedMonday = start.plusDays(7)
        val raw = WeekSchedule(1, start, emptyList(), weeks = listOf(AcademicWeek(1, shiftedMonday, shiftedMonday.plusDays(6))))
        val result = AcademicScheduleMapper.merge(raw, listOf(lesson.copy(date = shiftedMonday)), term, listOf(term))
        assertEquals(shiftedMonday, result.days.first().date)
        assertEquals(1, result.days.first().courses.size)
        assertTrue(result.unscheduled.isEmpty())
    }
}
