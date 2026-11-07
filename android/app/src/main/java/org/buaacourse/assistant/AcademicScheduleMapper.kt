package org.buaacourse.assistant

import java.text.Normalizer
import java.time.LocalTime

/** Official timetable entries own display; only a unique iClass match can supply attendance IDs. */
internal object AcademicScheduleMapper {
    fun merge(raw: WeekSchedule, lessons: List<AcademicLesson>, term: AcademicTerm, terms: List<AcademicTerm>): WeekSchedule {
        val original = raw.days.flatMap { day -> day.courses.map { day.date to it } }
        val originalIds = original.map { (_, course) ->
            (course.scheduleIds + course.id).map(String::trim).filter(String::isNotEmpty).distinct()
        }
        // A server ID must belong to one raw record across the entire week.
        // A matching row cannot authorize an ID also attached to a different,
        // unmatched class, date or time elsewhere in the response.
        val idOwners = originalIds.flatten().groupingBy { it }.eachCount()
        val unique = lessons.distinct()
        val candidates = unique.map { lesson ->
            original.indices.filter { index ->
                val (date, course) = original[index]
                lesson.date != null && time(lesson.startTime) != null && time(lesson.endTime) != null &&
                    lesson.startTime < lesson.endTime && date == lesson.date && course.courseNum.trim().isNotEmpty() &&
                    course.courseNum.trim() in listOf(lesson.courseCode.trim(), lesson.classCode.trim()) &&
                    name(course.courseName) == name(lesson.name) &&
                    time(course.classBeginTime)?.let { it >= lesson.startTime && it < lesson.endTime } == true &&
                    time(course.classEndTime)?.let { it <= lesson.endTime && it > time(course.classBeginTime).orEmpty() } == true &&
                    originalIds[index].isNotEmpty()
            }
        }
        val uses = candidates.flatten().groupingBy { it }.eachCount()
        val used = mutableSetOf<Int>()
        val verifiedIds = mutableSetOf<String>()
        val courses = unique.mapIndexed { index, lesson ->
            val possible = candidates[index].sortedBy { time(original[it].second.classBeginTime) }
            val unambiguous = possible.isNotEmpty() && possible.all { rawIndex ->
                uses[rawIndex] == 1 && originalIds[rawIndex].all { idOwners[it] == 1 }
            } && possible.zipWithNext().all { (a, b) ->
                time(original[a].second.classEndTime)!! <= time(original[b].second.classBeginTime)!!
            }
            val accepted = if (unambiguous) possible else emptyList()
            val sessions = accepted.map { rawIndex -> original[rawIndex].second.copy(
                id = original[rawIndex].second.id.trim(), scheduleIds = originalIds[rawIndex],
                attendanceAvailable = true, attendanceSessions = emptyList()) }
            val matched = sessions.singleOrNull()
            val ids = accepted.flatMap { originalIds[it] }.distinct()
            used += accepted
            verifiedIds += ids
            lesson.date to Course(
                id = matched?.id?.trim()?.takeIf(String::isNotEmpty) ?: ids.firstOrNull()
                    ?: "academic:" + listOf(lesson.classCode, lesson.courseCode, lesson.name, lesson.date.toString(),
                        lesson.startTime, lesson.endTime, lesson.location, lesson.beginSection.toString(), lesson.endSection.toString())
                        .joinToString("") { "${it.length}:$it" },
                courseName = lesson.name, courseNum = lesson.courseCode, classCode = lesson.classCode,
                classBeginTime = datedTime(lesson.date, lesson.startTime),
                classEndTime = datedTime(lesson.date, lesson.endTime),
                classroomName = lesson.location, teachers = lesson.teachers,
                scheduleIds = ids, signed = sessions.isNotEmpty() && sessions.all { it.signed }, attendanceAvailable = ids.isNotEmpty(),
                attendanceSessions = sessions, signedScheduleIds = sessions.flatMap { it.completedIds }.toSet(),
                beginSection = lesson.beginSection, endSection = lesson.endSection,
            )
        }
        val start = raw.weeks.firstOrNull { it.number == raw.week }?.start
            ?: raw.days.minOfOrNull { it.date }
            ?: raw.semesterStart.plusWeeks((raw.week - 1).toLong())
        val dates = (0L..6L).map { start.plusDays(it) }
        return raw.copy(
            days = dates.map { date ->
                DaySchedule(date, courses.filter { it.first == date }.map { it.second }.sortedBy { it.classBeginTime })
            },
            unscheduled = courses.filter { it.first !in dates }.map { it.second },
            academic = AcademicComparison(AcademicStatus.VERIFIED, terms, term.code, term.name,
                excluded = original.filterIndexed { index, _ -> index !in used }.map { it.second },
                verifiedScheduleIds = verifiedIds,
                uncertainCount = courses.count { !it.second.attendanceAvailable },
                message = "已加载正式课表，${courses.count { !it.second.attendanceAvailable }} 门课程暂未关联 iClass 签到。"),
        )
    }

    /** Add attendance to the already visible official timetable, never replace it with iClass. */
    fun linkOfficial(official: WeekSchedule, raw: WeekSchedule): WeekSchedule {
        val comparison = official.academic
        if (comparison?.status != AcademicStatus.VERIFIED || comparison.termCode.isNullOrBlank()) {
            return official.copy(
                days = official.days.map { day -> day.copy(courses = day.courses.map { it.copy(attendanceAvailable = false) }) },
                unscheduled = official.unscheduled.map { it.copy(attendanceAvailable = false) },
            )
        }
        fun lesson(course: Course, date: java.time.LocalDate?) = AcademicLesson(
            course.courseNum, course.classCode, course.courseName, date,
            time(course.classBeginTime).orEmpty(), time(course.classEndTime).orEmpty(),
            course.classroomName, course.teachers, course.beginSection, course.endSection,
        )
        val lessons = official.days.flatMap { day -> day.courses.map { lesson(it, day.date) } } +
            official.unscheduled.map { lesson(it, null) }
        val term = AcademicTerm(comparison.termCode, comparison.termName)
        val linked = merge(raw.copy(week = official.week, semesterStart = official.semesterStart,
            weeks = official.weeks), lessons, term, comparison.terms)
        // Absence is meaningful only after every date was successfully queried.
        // A record with a related identity anywhere in this week remains uncertain
        // if time, ID or teaching-class checks prevented its association.
        val observed = raw.days.flatMap { it.courses }
        val complete = raw.days.map { it.date }.toSet().containsAll(official.days.map { it.date }) && raw.days.none { it.failed }
        val days = linked.days.map { day -> day.copy(courses = day.courses.map { course ->
            val identityKnown = course.courseNum.isNotBlank() && name(course.courseName).isNotBlank()
            val hasRelated = observed.any { row ->
                row.courseNum.trim().isNotEmpty() && row.courseNum.trim() in listOf(course.courseNum.trim(), course.classCode.trim()) ||
                    name(row.courseName).isNotEmpty() && name(row.courseName) == name(course.courseName)
            }
            val unknownIdentityAtSameTime = raw.days.find { it.date == day.date }?.courses.orEmpty().any { row ->
                (row.courseNum.isBlank() || row.courseName.isBlank() || row.courseName == "未知课程") &&
                    (time(row.classBeginTime) == null || time(row.classEndTime) == null ||
                        time(row.classBeginTime)?.let { it < time(course.classEndTime).orEmpty() } == true &&
                        time(row.classEndTime)?.let { it > time(course.classBeginTime).orEmpty() } == true)
            }
            course.copy(attendanceNotRequired = complete && identityKnown && !course.attendanceAvailable &&
                !hasRelated && !unknownIdentityAtSameTime && time(course.classBeginTime) != null && time(course.classEndTime) != null)
        }) }
        val uncertain = days.sumOf { day -> day.courses.count { !it.attendanceAvailable && !it.attendanceNotRequired } } + official.unscheduled.size
        return official.copy(days = days, unscheduled = official.unscheduled.map {
            it.copy(attendanceAvailable = false, scheduleIds = emptyList())
        },
            academic = linked.academic?.copy(uncertainCount = uncertain), attendanceMessage = raw.attendanceMessage)
    }

    private fun datedTime(date: java.time.LocalDate?, value: String): String =
        if (value.isBlank()) "" else "${date?.let { "$it " }.orEmpty()}$value:00"

    private fun name(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).replace(Regex("[\\s\\p{Z}]+"), "")
    private fun time(value: String): String? {
        val match = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)").find(value) ?: return null
        return runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()).toString() }.getOrNull()
    }
}
