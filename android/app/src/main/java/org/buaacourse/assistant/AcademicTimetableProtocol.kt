package org.buaacourse.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object AcademicTimetableProtocol {
    fun parseUndergraduateTerms(body: String): List<AcademicTerm> =
        schoolData("本科生系统学期列表格式异常，请刷新重试") {
            val byCode = linkedMapOf<String, AcademicTerm>()
            for (row in successfulResponse(body).getJSONArray("datas").objects()) {
                val code = row.field("itemCode").required()
                val name = row.label("itemName").required()
                val selected = row.flag("selected")
                val existing = byCode[code]
                require(existing == null || existing.name == name)
                byCode[code] = AcademicTerm(code, name, selected || existing?.selected == true)
            }
            byCode.values.toList()
        }

    fun parseUndergraduateWeeks(body: String, term: AcademicTerm): List<AcademicWeek> =
        schoolData("本科生系统周次列表格式异常，请刷新重试") {
            val weeks = successfulResponse(body).getJSONArray("datas").objects().map { row ->
                val number = row.positiveInt("serialNumber") ?: error("Missing week number")
                require(row.field("term") == term.code)
                val monday = schoolDate(row.label("startDate").required())
                val sunday = schoolDate(row.label("endDate").required())
                require(monday.dayOfWeek == DayOfWeek.MONDAY && monday.plusDays(6) == sunday)
                AcademicWeek(number, monday, sunday, row.flag("curWeek"))
            }
            require(weeks.distinctBy(AcademicWeek::number).size == weeks.size)
            weeks.sortedBy(AcademicWeek::number)
        }

    fun parseUndergraduateWeek(
        body: String,
        term: AcademicTerm,
        week: Int,
        semesterStart: LocalDate,
    ): List<AcademicLesson> = schoolData("本科生课表数据不完整或时间格式异常，请刷新重试") {
        require(term.code.isNotBlank())
        requireWeek(week, semesterStart)
        val monday = semesterStart.plusWeeks(week.toLong() - 1)
        val rows = successfulResponse(body).getJSONObject("datas").getJSONArray("arrangedList")
        rows.objects().map { undergraduateLesson(it, monday) }.distinct().sortedWith(lessonOrder)
    }

    fun parseGraduateTerms(body: String): List<AcademicTerm> =
        schoolData("研究生系统学期列表格式异常，请刷新重试") { GraduateTimetableParser.parseTerms(body) }

    fun parseGraduateWeek(
        body: String,
        term: AcademicTerm,
        week: Int,
        semesterStart: LocalDate,
    ): List<AcademicLesson> = schoolData("研究生课表数据不完整或时间格式异常，请刷新重试") {
        requireWeek(week, semesterStart)
        val calendar = GraduateTimetableParser.parse(body, term, semesterStart)
        calendar.weeks.find { it.number == week }?.let(calendar::lessons).orEmpty().sortedWith(lessonOrder)
    }

    fun graduateWeeks(body: String, term: AcademicTerm, defaultStart: LocalDate): List<AcademicWeek> =
        schoolData("研究生课表校历无法确认，请核对所选学期后重试") {
            GraduateTimetableParser.parse(body, term, defaultStart).weeks
        }

    fun graduateSections(body: String): List<AcademicPeriod> =
        schoolData("研究生节次方案读取失败，请刷新重试") { GraduateTimetableParser.parsePeriods(body) }

    private fun undergraduateLesson(row: JSONObject, monday: LocalDate): AcademicLesson {
        val day = row.positiveInt("dayOfWeek")?.also { require(it <= 7) }
        val first = row.positiveInt("beginSection")
        val last = row.positiveInt("endSection")
        require(first == null || last == null || last >= first)
        val start = schoolTime(row.label("beginTime"))
        val end = schoolTime(row.label("endTime"))
        require(start == null || end == null || end > start)
        return AcademicLesson(
            courseCode = row.field("courseCode").required(),
            classCode = row.field("courseSerialNo").orEmpty(),
            name = row.label("courseName").required(),
            date = day?.let { monday.plusDays((it - 1).toLong()) },
            startTime = start?.format(hourMinute).orEmpty(),
            endTime = end?.format(hourMinute).orEmpty(),
            location = row.label("placeName").orEmpty(),
            teachers = listOfNotNull(row.label("weeksAndTeachers")),
            beginSection = first,
            endSection = last,
        )
    }

    private fun successfulResponse(body: String): JSONObject {
        val tokens = JSONTokener(body)
        val root = tokens.nextValue() as JSONObject
        require(tokens.nextClean() == '\u0000')
        require(root.field("code") == "0")
        return root
    }

    private fun JSONArray.objects() = List(length(), ::getJSONObject)

    private fun JSONObject.field(key: String): String? = when (val value = opt(key)) {
        null, JSONObject.NULL -> null
        is String, is Number -> value.toString().trim().takeIf(String::isNotEmpty)
        else -> error("Invalid scalar")
    }

    private fun JSONObject.label(key: String): String? = when (val value = opt(key)) {
        null, JSONObject.NULL -> null
        is String -> value.trim().takeIf(String::isNotEmpty)
        else -> error("Invalid text")
    }

    private fun String?.required(): String = requireNotNull(this)

    private fun JSONObject.positiveInt(key: String): Int? =
        field(key)?.let { raw -> raw.toInt().also { require(it > 0) } }

    private fun JSONObject.flag(key: String): Boolean = when (val value = opt(key)) {
        null, JSONObject.NULL, false, 0 -> false
        true, 1 -> true
        is String -> when (value.trim().lowercase(Locale.ROOT)) {
            "0", "false" -> false
            "1", "true" -> true
            else -> error("Invalid flag")
        }
        else -> error("Invalid flag")
    }

    private fun requireWeek(number: Int, anchor: LocalDate) {
        require(number > 0 && anchor.dayOfWeek == DayOfWeek.MONDAY)
    }

    private val datePattern = Regex("""([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})(?:[ T][0-9]{1,2}:[0-9]{2}(?::[0-9]{2}(?:\.[0-9]+)?)?)?""")
    private val timePattern = Regex("""([0-9]{1,2}):([0-9]{2})(?::00)?""")
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val lessonOrder = compareBy(
        AcademicLesson::date, AcademicLesson::startTime, AcademicLesson::endTime,
        AcademicLesson::courseCode, AcademicLesson::classCode,
    )

    private fun schoolDate(value: String): LocalDate {
        val parts = requireNotNull(datePattern.matchEntire(value)).groupValues
        return LocalDate.of(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
    }

    private fun schoolTime(value: String?): LocalTime? {
        if (value == null) return null
        val parts = requireNotNull(timePattern.matchEntire(value)).groupValues
        return LocalTime.of(parts[1].toInt(), parts[2].toInt())
    }

    private inline fun <T> schoolData(message: String, read: () -> T): T = try {
        read()
    } catch (_: Exception) {
        throw IclassException(message)
    }
}
