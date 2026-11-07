package org.buaacourse.assistant

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class GraduateCalendar internal constructor(
    val weeks: List<AcademicWeek>,
    val periods: List<AcademicPeriod>,
    private val lessonsForWeek: (AcademicWeek) -> List<AcademicLesson>,
) {
    fun lessons(week: AcademicWeek): List<AcademicLesson> =
        weeks.getOrNull(week.number - 1)?.let(lessonsForWeek).orEmpty()
}

object GraduateTimetableParser {
    private const val INVALID_TERMS = "研究生系统学期列表格式异常，请刷新重试"
    private const val INVALID_TIMETABLE = "研究生课表数据不完整，请刷新重试"
    private val space = Regex("[\\s\\p{Z}]+")
    private val teacherSeparator = Regex("[,，、;；\\n\\r]+")
    private val clockDigits = Regex("[0-9]{1,4}")
    private val termCode = Regex("[0-9]{4}[123]")
    private val schoolZone = ZoneId.of("Asia/Shanghai")

    fun parseTerms(body: String): List<AcademicTerm> {
        val root = readObject(body, INVALID_TERMS)
        val data = root.optJSONObject("datas")?.optJSONObject("kfdxnxqcx")
            ?: throw IclassException(INVALID_TERMS)
        val rows = data.optJSONArray("rows") ?: throw IclassException(INVALID_TERMS)
        if (scalar(root.opt("code")) != "0" ||
            scalar(data.opt("totalSize"))?.toIntOrNull() != rows.length()
        ) throw IclassException(INVALID_TERMS)

        val namesByCode = linkedMapOf<String, String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: throw IclassException(INVALID_TERMS)
            val code = scalar(row.opt("XNXQDM")) ?: throw IclassException(INVALID_TERMS)
            if (!termCode.matches(code)) throw IclassException(INVALID_TERMS)
            val name = listOf("XNXQDM_DISPLAY", "XNXQMC", "DISPLAY", "MC").firstNotNullOfOrNull { field ->
                (row.opt(field) as? String)?.trim()?.takeIf(String::isNotEmpty)
            } ?: throw IclassException(INVALID_TERMS)
            val previous = namesByCode.putIfAbsent(code, name)
            if (previous != null && previous != name) throw IclassException(INVALID_TERMS)
        }
        return namesByCode.entries.sortedByDescending { it.key }.mapIndexed { index, (code, name) ->
            AcademicTerm(code, name, selected = index == 0)
        }
    }

    fun parse(body: String, term: AcademicTerm, defaultStart: LocalDate): GraduateCalendar {
        if (defaultStart.dayOfWeek != DayOfWeek.MONDAY) {
            throw IclassException("学期起始日期应为星期一")
        }
        val response = timetable(body)
        val tasks = response.objects("rwList").groupBy { row ->
            validateTerm(row, term)
            row.requiredCode("BJDM")
        }
        val arrangements = response.objects("jgList").groupBy { row ->
            validateTerm(row, term)
            row.requiredCode("BJDM")
        }
        val schemes = readSchemes(response)
        val periods = displayPeriods(schemes)
        val placements = mutableListOf<Placement>()
        val calendarStarts = mutableSetOf<LocalDate>()

        for ((classCode, rows) in arrangements) {
            val classTasks = tasks[classCode] ?: invalid()
            val courseCode = rows.map { it.requiredCode("KCDM") }.distinct().singleOrNull() ?: invalid()
            if (classTasks.any { it.optionalCode("KCDM")?.let { code -> code != courseCode } == true }) invalid()
            val names = (classTasks + rows).mapNotNull { it.optionalText("KCMC") }
            val name = names.firstOrNull() ?: invalid()
            if (names.any { normalizeName(it) != normalizeName(name) }) invalid()

            val classPlacements = rows.map { readPlacement(it, courseCode, classCode, name, schemes) }
            val firstDates = classTasks.mapNotNull { row ->
                row.optionalText("SCSKRQ")?.let { value ->
                    if (value.length != 10) invalid()
                    try {
                        LocalDate.parse(value)
                    } catch (_: DateTimeException) {
                        invalid()
                    }
                }
            }.distinct()
            if (firstDates.size > 1) invalid()
            val firstOffset = classPlacements.mapNotNull { placement ->
                placement.pattern.weekMask.indexOf('1').takeIf { it >= 0 }?.let { firstWeek ->
                    firstWeek * 7L + placement.pattern.weekday - 1
                }
            }.minOrNull()
            if (firstOffset != null && firstDates.isNotEmpty()) {
                val monday = firstDates.single().minusDays(firstOffset)
                if (monday.dayOfWeek != DayOfWeek.MONDAY) invalid()
                calendarStarts += monday
            }
            placements += classPlacements
        }

        if (placements.isEmpty()) return GraduateCalendar(emptyList(), periods) { emptyList() }
        val start = when {
            calendarStarts.size == 1 -> calendarStarts.single()
            calendarStarts.isEmpty() && term.code == "20261" && defaultStart == LocalDate.of(2026, 9, 7) ->
                defaultStart
            else -> throw IclassException("无法根据研究生课表确认学期起始日期")
        }
        val today = LocalDate.now(schoolZone)
        val weeks = try {
            (1..placements.maxOf { it.pattern.weekMask.length }).map { number ->
                val monday = start.plusWeeks(number.toLong() - 1)
                val sunday = monday.plusDays(6)
                AcademicWeek(number, monday, sunday, current = today in monday..sunday)
            }
        } catch (_: DateTimeException) {
            invalid()
        }
        val mergedPlacements = mergePlacements(placements)
        return GraduateCalendar(weeks, periods) { week ->
            mergedPlacements.filter { it.pattern.weekMask.getOrNull(week.number - 1) == '1' }.map { placement ->
                placement.lesson.copy(date = week.start.plusDays(placement.pattern.weekday.toLong() - 1))
            }.sortedWith(compareBy(AcademicLesson::date, AcademicLesson::startTime, AcademicLesson::name))
        }
    }

    fun parsePeriods(body: String): List<AcademicPeriod> = displayPeriods(readSchemes(timetable(body)))

    private data class PlacementPattern(
        val classCode: String,
        val weekday: Int,
        val weekMask: String,
        val scheme: String,
        val location: String,
        val teachers: Set<String>,
    )

    private data class Placement(
        val pattern: PlacementPattern,
        val lesson: AcademicLesson,
        val firstPeriod: Int,
        val lastPeriod: Int,
    )

    private fun readPlacement(
        row: JSONObject,
        courseCode: String,
        classCode: String,
        name: String,
        schemes: Map<String, Map<Int, AcademicPeriod>>,
    ): Placement {
        val weekday = row.positiveNumber("XQ")
        if (weekday > 7) invalid()
        val mask = row.optionalText("ZCBH") ?: invalid()
        if (mask.any { it != '0' && it != '1' }) invalid()

        val first = row.positiveNumber("KSJCDM")
        val last = row.positiveNumber("JSJCDM")
        val scheme = row.requiredCode("JCFADM")
        val periods = schemes[scheme] ?: invalid()
        val coveredPeriods = periods.keys.count { it in first..last }
        if (last < first || coveredPeriods.toLong() != last.toLong() - first + 1) invalid()
        val location = row.optionalText("JASMC").orEmpty()
        val teachers = row.optionalText("JGJSXM").orEmpty().split(teacherSeparator)
            .map(String::trim).filter(String::isNotEmpty).distinct()
        val lesson = AcademicLesson(
            courseCode, classCode, name, null, periods.getValue(first).start, periods.getValue(last).end,
            location, teachers, first, last,
        )
        return Placement(PlacementPattern(classCode, weekday, mask, scheme, location, teachers.toSet()), lesson, first, last)
    }

    private fun mergePlacements(placements: List<Placement>): List<Placement> =
        placements.groupBy { it.pattern }.values.flatMap { matching ->
            val merged = mutableListOf<Placement>()
            for (placement in matching.sortedBy { it.firstPeriod }) {
                val previous = merged.lastOrNull()
                if (previous == null || placement.firstPeriod.toLong() > previous.lastPeriod.toLong() + 1) {
                    merged += placement
                } else if (placement.lastPeriod > previous.lastPeriod) {
                    merged[merged.lastIndex] = previous.copy(
                        lastPeriod = placement.lastPeriod,
                        lesson = previous.lesson.copy(endTime = placement.lesson.endTime, endSection = placement.lastPeriod),
                    )
                }
            }
            merged
        }

    private fun readSchemes(response: JSONObject): Map<String, Map<Int, AcademicPeriod>> {
        val schemes = linkedMapOf<String, MutableMap<Int, AcademicPeriod>>()
        val schemeRows = response.opt("jcfaList")
        if (schemeRows == null || schemeRows === JSONObject.NULL) return schemes
        for (scheme in response.objects("jcfaList")) {
            val groupCode = scheme.optionalCode("JCFADM")
            for (row in scheme.objects("skjcList")) {
                val rowCode = row.optionalCode("JCFADM")
                if (rowCode != null && groupCode != null && rowCode != groupCode) invalid()
                val code = rowCode ?: groupCode ?: invalid()
                val number = row.positiveNumber("DM")
                val start = readClock(row.requiredCode("KSSJ"))
                val end = readClock(row.requiredCode("JSSJ"))
                if (start >= end) invalid()
                val period = AcademicPeriod(number, start.toString(), end.toString())
                val previous = schemes.getOrPut(code) { linkedMapOf() }.putIfAbsent(number, period)
                if (previous != null && previous != period) invalid()
            }
        }
        for (periods in schemes.values) {
            val sorted = periods.values.sortedBy { it.number }
            if (sorted.zipWithNext().any { (earlier, later) -> earlier.end > later.start }) invalid()
        }
        return schemes
    }

    private fun displayPeriods(schemes: Map<String, Map<Int, AcademicPeriod>>): List<AcademicPeriod> =
        schemes.values.flatMap { it.values }.groupBy { it.number }.toSortedMap().map { (number, periods) ->
            periods.distinct().singleOrNull() ?: AcademicPeriod(number)
        }

    private fun readClock(value: String): LocalTime {
        if (!clockDigits.matches(value)) invalid()
        val digits = value.toInt()
        val hour = digits / 100
        val minute = digits % 100
        if (hour >= 24 || minute >= 60) invalid()
        return LocalTime.of(hour, minute)
    }

    private fun validateTerm(row: JSONObject, term: AcademicTerm) {
        val reported = row.optionalCode("XNXQDM")
        if (reported != null && reported != term.code.trim()) invalid()
    }

    private fun timetable(body: String): JSONObject {
        val response = readObject(body, INVALID_TIMETABLE)
        if (scalar(response.opt("code")) != "1") throw IclassException("研究生课表获取失败，请检查登录状态后重试")
        return response
    }

    private fun JSONObject.objects(field: String): List<JSONObject> {
        val rows = opt(field) as? JSONArray ?: invalid()
        return List(rows.length()) { index -> rows.optJSONObject(index) ?: invalid() }
    }

    private fun JSONObject.positiveNumber(field: String): Int =
        requiredCode(field).toIntOrNull()?.takeIf { it > 0 } ?: invalid()

    private fun JSONObject.requiredCode(field: String): String = optionalCode(field) ?: invalid()

    private fun JSONObject.optionalCode(field: String): String? {
        val raw = opt(field)
        if (raw == null || raw === JSONObject.NULL) return null
        if (raw !is Number && raw !is String) invalid()
        return scalar(raw)
    }

    private fun JSONObject.optionalText(field: String): String? {
        val raw = opt(field)
        if (raw == null || raw === JSONObject.NULL) return null
        return (raw as? String ?: invalid()).trim().takeIf(String::isNotEmpty)
    }

    private fun scalar(raw: Any?): String? = when (raw) {
        is String -> raw.trim().takeIf(String::isNotEmpty)
        is Number -> raw.toString()
        else -> null
    }

    private fun normalizeName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC).replace(space, "")

    private fun readObject(body: String, failure: String): JSONObject {
        try {
            val reader = JSONTokener(body)
            val json = reader.nextValue() as? JSONObject ?: throw IclassException(failure)
            if (reader.nextClean() != '\u0000') throw IclassException(failure)
            return json
        } catch (_: JSONException) {
            throw IclassException(failure)
        }
    }

    private fun invalid(): Nothing = throw IclassException(INVALID_TIMETABLE)
}
