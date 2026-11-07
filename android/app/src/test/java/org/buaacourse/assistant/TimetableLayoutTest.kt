package org.buaacourse.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TimetableLayoutTest {
    private fun course(id: String, begin: String, end: String) = Course(id, id, classBeginTime = begin, classEndTime = end)

    @Test fun standardBoundariesMapToPeriodsIncludingEveningAndLunchGap() {
        assertEquals(TimetableLayout.Span(0, 1), TimetableLayout.span(course("a", "2026-09-07 08:00:00", "2026-09-07 09:35:00")))
        assertEquals(TimetableLayout.Span(4, 5), TimetableLayout.span(course("a", "11:30", "14:45")))
        assertEquals(TimetableLayout.Span(12, 13), TimetableLayout.span(course("a", "20:40", "22:15")))
    }

    @Test fun invalidNonStandardReversedAndOvernightTimesNeverReceiveInventedPeriods() {
        listOf(course("a", "25:00", "26:00"), course("a", "09:10", "10:20"), course("a", "14:00", "09:35"),
            course("a", "", ""), course("a", "2026-09-07 08:00", "2026-09-08 09:35")).forEach { assertNull(TimetableLayout.span(it)) }
    }

    @Test fun connectedOverlapsKeepEveryCourseAndAdjacentPeriodsStaySeparate() {
        val result = TimetableLayout.clusters(listOf(course("a", "08:00", "09:35"), course("b", "08:50", "10:35"),
            course("c", "09:50", "11:25"), course("d", "11:30", "12:15")))
        assertEquals(2, result.size)
        assertEquals(setOf("a", "b", "c"), result.first().courses.map { it.id }.toSet())
        assertEquals(TimetableLayout.Span(0, 3), result.first().span)
        assertEquals("d", result.last().courses.single().id)
    }

    @Test fun weekDatesFollowActualStartAndMissingDaysAreNotReportedAsEmpty() {
        val start = LocalDate.of(2026, 9, 8)
        val state = AssistantUiState(semesterStart = start, week = 2, schedule = WeekSchedule(2, start,
            listOf(DaySchedule(start.plusWeeks(1), emptyList()))))
        val days = TimetableLayout.days(state)
        assertEquals(7, days.size)
        assertEquals(start.plusWeeks(1), days.first().date)
        assertFalse(days.first().failed)
        assertTrue(days.last().failed)
    }

    @Test fun serverDatesOverrideSemesterArithmeticAndCalendarWeeksHandlePartialDays() {
        val fallback = LocalDate.of(2026, 9, 7)
        val actual = LocalDate.of(2026, 9, 14)
        val allDays = (0L..6L).map { DaySchedule(actual.plusDays(it), emptyList()) }
        val state = AssistantUiState(week = 1, schedule = WeekSchedule(1, fallback, allDays))
        assertEquals(allDays, TimetableLayout.days(state))
        val calendar = AcademicWeek(1, actual, actual.plusDays(6))
        assertEquals(actual, TimetableLayout.days(state.copy(schedule = state.schedule!!.copy(days = allDays.take(1), weeks = listOf(calendar)))).first().date)
    }

    @Test fun sectionNumbersPlaceClassesWithoutInventingClockTimes() {
        val date = LocalDate.of(2026, 9, 7)
        val course = course("new", "", "").copy(beginSection = 15, endSection = 16)
        val schedule = WeekSchedule(1, date, listOf(DaySchedule(date, listOf(course))))
        val periods = TimetableLayout.periods(schedule)
        assertEquals(16, periods.size)
        assertTrue(periods.all { it.start.isEmpty() && it.end.isEmpty() })
        assertEquals(TimetableLayout.Span(14, 15), TimetableLayout.span(course, periods))
    }

    @Test fun actualSectionTimesAndLessonBoundariesNeverFillUnconfirmedGaps() {
        val date = LocalDate.of(2026, 9, 7)
        val course = course("late", "09:15", "11:05").copy(beginSection = 2, endSection = 4)
        val schedule = WeekSchedule(1, date, listOf(DaySchedule(date, listOf(course))),
            sections = listOf(AcademicPeriod(1, "07:40", "08:25")))
        val periods = TimetableLayout.periods(schedule)
        assertEquals("07:40", periods[0].start)
        assertEquals("09:15", periods[1].start)
        assertEquals("", periods[1].end)
        assertEquals("", periods[2].start)
        assertEquals("11:05", periods[3].end)
        assertEquals(TimetableLayout.Span(1, 3), TimetableLayout.span(course, periods))
        assertNull(TimetableLayout.span(course("no-section", "08:00", "09:35"), periods))
    }

    @Test fun unscheduledKeysDoNotPretendToHaveADate() {
        val course = course("floating", "", "")
        assertNotEquals(TimetableLayout.key(null, course), TimetableLayout.key(LocalDate.of(2026, 9, 7), course))
    }
}
