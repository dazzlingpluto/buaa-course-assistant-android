package org.buaacourse.assistant

import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer
import java.time.LocalDate

/** Parses the graduate timetable and conservatively relates its identities to iClass. */
object GsmisProtocol {
    fun parseTerms(body: String): List<AcademicTerm> {
        val failure = "研究生系统学期列表格式异常，请刷新重试"
        val json = objectBody(body, failure)
        if (json.has("code") && scalar(json.opt("code")) != "0") throw IclassException(failure)
        val data = json.optJSONObject("datas")?.optJSONObject("kfdxnxqcx")
            ?: throw IclassException(failure)
        val rows = data.optJSONArray("rows") ?: throw IclassException(failure)
        if (data.has("totalSize") && scalar(data.opt("totalSize"))?.toIntOrNull() != rows.length()) {
            throw IclassException(failure)
        }
        val byCode = linkedMapOf<String, AcademicTerm>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: throw IclassException(failure)
            val code = identity(row, "XNXQDM", failure)
            val name = listOf("XNXQDM_DISPLAY", "XNXQMC", "DISPLAY", "MC")
                .firstNotNullOfOrNull { field -> (row.opt(field) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
                ?: throw IclassException(failure)
            val term = AcademicTerm(code, name)
            val previous = byCode.putIfAbsent(code, term)
            if (previous != null && previous.name != name) throw IclassException(failure)
        }
        return byCode.values.toList()
    }

    /** Never infer an opaque term code or silently substitute for an explicit selection. */
    fun chooseTerm(terms: List<AcademicTerm>, start: LocalDate, requestedCode: String?): AcademicTerm? {
        if (!requestedCode.isNullOrBlank()) {
            return terms.filter { it.code.trim() == requestedCode.trim() }.distinct().singleOrNull()
        }
        val targetTerm = if (start.monthValue >= 7) 1 else 2
        val targetYear = if (targetTerm == 1) start.year else start.year - 1
        return terms.filter { interpretation(it.name) == (targetYear to targetTerm) }.distinct().singleOrNull()
    }

    fun parseSnapshot(body: String, term: AcademicTerm): AcademicSnapshot {
        val failure = "研究生课表数据不完整，暂未过滤课程，请刷新重试"
        val json = objectBody(body, failure)
        if (scalar(json.opt("code")) != "1") throw IclassException("研究生课表获取失败，请检查登录状态后重试")
        val tasks = json.optJSONArray("rwList") ?: throw IclassException(failure)
        val placements = json.optJSONArray("jgList") ?: throw IclassException(failure)
        if (tasks.length() == 0 && placements.length() == 0) return AcademicSnapshot(term, emptyList())
        if (tasks.length() == 0 && placements.length() > 0) throw IclassException(failure)

        // Only arranged classes need a display identity. A legitimately selected
        // task without a placement must not invalidate the entire timetable.
        val codesByClass = linkedMapOf<String, String>()
        val namesByClass = linkedMapOf<String, String>()
        for (index in 0 until placements.length()) {
            val row = placements.optJSONObject(index) ?: throw IclassException(failure)
            val classCode = identity(row, "BJDM", failure)
            val courseCode = identity(row, "KCDM", failure)
            val previous = codesByClass.putIfAbsent(classCode, courseCode)
            if (previous != null && previous != courseCode) throw IclassException(failure)
            optionalName(row, failure)?.let { name ->
                val previousName = namesByClass.putIfAbsent(classCode, name)
                if (previousName != null && normalizedName(previousName) != normalizedName(name)) {
                    throw IclassException(failure)
                }
            }
        }
        val coursesByClass = linkedMapOf<String, AcademicCourse>()
        for (index in 0 until tasks.length()) {
            val row = tasks.optJSONObject(index) ?: throw IclassException(failure)
            if (row.has("XNXQDM") && row.opt("XNXQDM") !== JSONObject.NULL) {
                val returnedTerm = scalar(row.opt("XNXQDM")) ?: throw IclassException(failure)
                if (returnedTerm.isNotEmpty() && returnedTerm != term.code.trim()) throw IclassException(failure)
            }
            val classCode = identity(row, "BJDM", failure)
            if (classCode !in codesByClass) continue
            val taskName = optionalName(row, failure)
            val placementName = namesByClass[classCode]
            if (taskName != null && placementName != null && normalizedName(taskName) != normalizedName(placementName)) {
                throw IclassException(failure)
            }
            // GSMIS may attach KCMC to the placement rather than the task.
            // Resolve it through BJDM and reject conflicting names from either source.
            val name = taskName ?: placementName ?: throw IclassException(failure)
            val courseCode = codesByClass[classCode] ?: throw IclassException(failure)
            val taskCode = scalar(row.opt("KCDM"))
            if (taskCode != null && taskCode.isNotEmpty() && taskCode != courseCode) throw IclassException(failure)
            if (row.has("KCDM") && row.opt("KCDM") !== JSONObject.NULL && taskCode == null) throw IclassException(failure)
            val course = AcademicCourse(courseCode, classCode, name)
            val previous = coursesByClass.putIfAbsent(classCode, course)
            if (previous != null && (previous.code != courseCode || normalizedName(previous.name) != normalizedName(name))) {
                throw IclassException(failure)
            }
        }
        if (codesByClass.keys.any { it !in coursesByClass }) throw IclassException(failure)
        return AcademicSnapshot(term, coursesByClass.values.toList())
    }

    fun reconcile(schedule: WeekSchedule, snapshot: AcademicSnapshot, terms: List<AcademicTerm>): WeekSchedule {
        val original = schedule.days.flatMap { it.courses }
        val reference = snapshot.courses
        val exact: (Course) -> Boolean = { course ->
            val code = course.courseNum.trim()
            val name = normalizedName(course.courseName)
            code.isNotEmpty() && knownName(name) && reference.any {
                (code == it.code.trim() || code == it.classCode.trim()) && name == normalizedName(it.name)
            }
        }
        if (reference.isEmpty() || original.none(exact)) {
            return schedule.copy(academic = AcademicComparison(
                status = AcademicStatus.UNMATCHED,
                terms = terms,
                termCode = snapshot.term.code,
                termName = snapshot.term.name,
                uncertainCount = original.size,
                message = if (reference.isEmpty()) "研究生系统所选学期课表为空，已保留全部 iClass 排课，请核对学期"
                    else "未找到可确认的课程对应关系，已保留全部 iClass 排课，请核对学期及课程编号",
            ))
        }

        val officialCodes = reference.flatMap { listOf(it.code.trim(), it.classCode.trim()) }.filter(String::isNotEmpty).toSet()
        val officialNames = reference.map { normalizedName(it.name) }.toSet()
        val excluded = mutableListOf<Course>()
        val verifiedIds = linkedSetOf<String>()
        val unverifiedIds = linkedSetOf<String>()
        var uncertainCount = 0
        val days = schedule.days.map { day ->
            day.copy(courses = day.courses.filter { course ->
                val ids = (course.scheduleIds + course.id).map(String::trim).filter(String::isNotEmpty)
                if (exact(course)) {
                    verifiedIds += ids
                    true
                } else {
                    unverifiedIds += ids
                    val code = course.courseNum.trim()
                    val name = normalizedName(course.courseName)
                    // Unknown identity or a one-sided match may be a renamed course,
                    // a changed class, or another code system. Keep it for review.
                    if (code.isEmpty() || !knownName(name) || code in officialCodes || name in officialNames) {
                        uncertainCount++
                        true
                    } else {
                        excluded += course
                        false
                    }
                }
            })
        }
        // Inconsistent iClass rows must not make an unverified record signable
        // merely because it happens to reuse an ID from a confirmed row.
        verifiedIds.removeAll(unverifiedIds)
        return schedule.copy(days = days, academic = AcademicComparison(
            status = AcademicStatus.VERIFIED,
            terms = terms,
            termCode = snapshot.term.code,
            termName = snapshot.term.name,
            excluded = excluded,
            verifiedScheduleIds = verifiedIds,
            uncertainCount = uncertainCount,
            message = "已按研究生课表核对，排除 ${excluded.size} 条不在正式课表中的排课，${uncertainCount} 条待核对",
        ))
    }

    private fun interpretation(raw: String): Pair<Int, Int>? {
        val name = normalizedName(raw)
        val autumn = Regex("第?[一1]学期|上学期|秋").containsMatchIn(name)
        val spring = Regex("第?[二2]学期|下学期|春").containsMatchIn(name)
        if (autumn == spring) return null
        val term = if (autumn) 1 else 2
        val yearRange = Regex("(?<!\\d)(\\d{4})[-—–~～/至](\\d{4})(?!\\d)").find(name)
        if (yearRange != null) {
            val first = yearRange.groupValues[1].toInt()
            val second = yearRange.groupValues[2].toInt()
            return if (second == first + 1) first to term else null
        }
        // A lone year is only clear when explicitly marked as an academic year,
        // or with a calendar season. Names consisting of codes are not dates.
        val years = Regex("(?<!\\d)(\\d{4})(?!\\d)").findAll(name).toList()
        if (years.size != 1) return null
        val year = years.single().groupValues[1].toInt()
        return when {
            "学年" in name -> year to term
            "秋" in name -> year to 1
            "春" in name -> (year - 1) to 2
            else -> null
        }
    }

    private fun normalizedName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\s\\p{Z}]+"), "")

    private fun knownName(name: String) = name.isNotEmpty() && name != "未知课程"

    private fun scalar(value: Any?): String? = when (value) {
        is String -> value.trim()
        is Number -> value.toString().trim()
        else -> null
    }

    private fun identity(row: JSONObject, key: String, failure: String) = scalar(row.opt(key))
        ?.takeIf { it.isNotEmpty() } ?: throw IclassException(failure)

    private fun optionalName(row: JSONObject, failure: String): String? {
        val value = row.opt("KCMC")
        if (value == null || value === JSONObject.NULL) return null
        val name = value as? String ?: throw IclassException(failure)
        return name.trim().takeIf { it.isNotEmpty() }
    }

    private fun objectBody(body: String, failure: String): JSONObject {
        return try {
            val parser = JSONTokener(body)
            val result = parser.nextValue() as? JSONObject ?: throw IclassException(failure)
            if (parser.nextClean() != '\u0000') throw IclassException(failure)
            result
        } catch (_: Exception) {
            // Never expose untrusted HTML, session parameters or response bodies.
            throw IclassException(failure)
        }
    }
}
