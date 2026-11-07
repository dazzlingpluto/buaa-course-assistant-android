package org.buaacourse.assistant

import java.time.LocalDate
import java.time.LocalTime

/** Presentation geometry only; the actual attendance identity always remains the iClass ID. */
internal object TimetableLayout {
    data class Period(val start: String, val end: String, val number: Int = 0)
    data class Span(val first: Int, val last: Int)
    data class Cluster(val span: Span, val courses: List<Course>)
    val periods = listOf(
        Period("08:00", "08:45"), Period("08:50", "09:35"), Period("09:50", "10:35"),
        Period("10:40", "11:25"), Period("11:30", "12:15"), Period("14:00", "14:45"),
        Period("14:50", "15:35"), Period("15:50", "16:35"), Period("16:40", "17:25"),
        Period("17:30", "18:15"), Period("19:00", "19:45"), Period("19:50", "20:35"),
        Period("20:40", "21:25"), Period("21:30", "22:15"),
    ).mapIndexed { index, period -> period.copy(number = index + 1) }

    /** Only server-provided times or unambiguous lesson boundaries label the live grid. */
    fun periods(schedule: WeekSchedule?): List<Period> {
        val courses = schedule?.days.orEmpty().flatMap { it.courses } + schedule?.unscheduled.orEmpty()
        val supplied = schedule?.sections.orEmpty().filter { it.number > 0 }.groupBy { it.number }
        val last = (supplied.keys + courses.flatMap { listOfNotNull(it.beginSection, it.endSection) }).maxOrNull()
            ?.coerceAtLeast(1) ?: 14
        fun unique(values: List<String>): String = values.mapNotNull(::time).distinct().singleOrNull().orEmpty()
        return (1..last).map { number ->
            val starts = supplied[number].orEmpty().map { it.start }
            val ends = supplied[number].orEmpty().map { it.end }
            Period(
                unique(starts).ifBlank { unique(courses.filter { it.beginSection == number }.map { it.classBeginTime }) },
                unique(ends).ifBlank { unique(courses.filter { it.endSection == number }.map { it.classEndTime }) }, number,
            )
        }
    }

    fun time(raw: String): String? {
        val match = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)").find(raw) ?: return null
        return runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()).toString() }.getOrNull()
    }

    fun span(course: Course, sections: List<Period> = periods): Span? {
        if (course.beginSection != null && course.endSection != null) {
            val first = sections.indexOfFirst { it.number == course.beginSection }
            val last = sections.indexOfFirst { it.number == course.endSection }
            if (first >= 0 && last >= first) return Span(first, last)
            return null
        }
        val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
        val beginDate = datePattern.find(course.classBeginTime)?.value
        val endDate = datePattern.find(course.classEndTime)?.value
        if (beginDate != null && endDate != null && beginDate != endDate) return null
        val first = sections.indexOfFirst { it.start.isNotBlank() && it.start == time(course.classBeginTime) }
        val last = sections.indexOfFirst { it.end.isNotBlank() && it.end == time(course.classEndTime) }
        return if (first >= 0 && last >= first) Span(first, last) else null
    }

    /** Connected overlaps form one selectable cluster, so a later card cannot cover another. */
    fun clusters(courses: List<Course>, sections: List<Period> = periods): List<Cluster> {
        val ordered = courses.mapNotNull { course -> span(course, sections)?.let { it to course } }
            .sortedWith(compareBy({ it.first.first }, { it.first.last }, { it.second.id }))
        val result = mutableListOf<Cluster>()
        for ((span, course) in ordered) {
            val previous = result.lastOrNull()
            if (previous != null && span.first <= previous.span.last) {
                result[result.lastIndex] = Cluster(Span(previous.span.first, maxOf(previous.span.last, span.last)), previous.courses + course)
            } else result += Cluster(span, listOf(course))
        }
        return result
    }

    fun days(state: AssistantUiState): List<DaySchedule> {
        val actual = state.schedule?.days.orEmpty().distinctBy { it.date }.sortedBy { it.date }
        if (actual.size == 7) return actual
        val start = state.schedule?.weeks?.find { it.number == state.week }?.start
            ?: (state.schedule?.semesterStart ?: state.semesterStart).plusWeeks((state.week - 1).toLong())
        return (0L..6L).map { offset ->
            val date = start.plusDays(offset)
            state.schedule?.days?.find { it.date == date } ?: DaySchedule(date, emptyList(), failed = true)
        }
    }

    fun key(date: LocalDate?, course: Course): String = listOf(date?.toString().orEmpty(), course.id, course.classBeginTime,
        course.courseNum, course.courseName, course.classEndTime, course.classroomName, course.teachBuildName, course.storeyName)
        .joinToString("") { "${it.length}:$it" }
}
