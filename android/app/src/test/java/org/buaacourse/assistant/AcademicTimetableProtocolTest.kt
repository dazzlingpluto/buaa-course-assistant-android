package org.buaacourse.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AcademicTimetableProtocolTest {
    private val start = LocalDate.of(2026, 9, 7)
    private val term = AcademicTerm("20261", "2026-2027学年第一学期")
    private val undergraduate = """{"code":"0","datas":{"code":"sample-student","name":"课程表","arrangedList":[
        {"courseCode":"sample-course","courseName":"示例课程","courseSerialNo":"sample-class",
         "beginTime":"08:00","endTime":"09:35","beginSection":1,"endSection":2,
         "placeName":"星桥楼 6-107","weeksAndTeachers":"1-16周 示例教师","dayOfWeek":2}
    ]}}"""

    @Test fun undergraduateTermsAcceptSchoolCalendarNamesWithoutGuessingCodes() {
        val result = AcademicTimetableProtocol.parseUndergraduateTerms("""{"code":0,"datas":[
            {"itemCode":"autumn-code","itemName":"2026-2027学年第一学期","selected":true},
            {"itemCode":"other-code","itemName":"2026-2027学年(秋)"},
            {"itemCode":"autumn-code","itemName":"2026-2027学年第一学期"}
        ]}""")
        assertEquals(listOf("autumn-code", "other-code"), result.map { it.code })
        assertEquals("2026-2027学年(秋)", result.last().name)
        assertTrue(result.first().selected)
        assertTrue(AcademicTimetableProtocol.parseUndergraduateTerms("""{"code":"0","datas":[]}""").isEmpty())
        for (body in listOf(
            """{"code":"1","datas":[]}""", """{"code":"0"}""",
            """{"code":"0","datas":[{"itemCode":"x"}]}""",
            """{"code":"0","datas":[{"itemCode":"x","itemName":"秋"},{"itemCode":"x","itemName":"春"}]}""",
        )) assertFailure { AcademicTimetableProtocol.parseUndergraduateTerms(body) }
    }

    @Test fun undergraduateWeekUsesActualTimesAndMondayAnchor() {
        val lesson = undergraduateWeek(undergraduate, week = 2).single()
        assertEquals("sample-course", lesson.courseCode)
        assertEquals("sample-class", lesson.classCode)
        assertEquals(LocalDate.of(2026, 9, 15), lesson.date)
        assertEquals("08:00", lesson.startTime)
        assertEquals("09:35", lesson.endTime)
        assertEquals("星桥楼 6-107", lesson.location)
        assertEquals(listOf("1-16周 示例教师"), lesson.teachers)
    }

    @Test fun undergraduateOptionalMetadataDoesNotInventIdentityOrTime() {
        val body = JSONObject(undergraduate)
        val row = body.getJSONObject("datas").getJSONArray("arrangedList").getJSONObject(0)
        listOf("courseSerialNo", "beginSection", "endSection", "placeName", "weeksAndTeachers").forEach { row.remove(it) }
        row.put("beginTime", "8:00:00")
        val lesson = undergraduateWeek(body.toString()).single()
        assertEquals("", lesson.classCode)
        assertEquals("", lesson.location)
        assertTrue(lesson.teachers.isEmpty())
        assertEquals("08:00", lesson.startTime)
        row.remove("beginTime")
        assertEquals("", undergraduateWeek(body.toString()).single().startTime)
    }

    @Test fun undergraduateNullableFieldsKeepCoursesWithoutInventingMondayOrTime() {
        val body = JSONObject(undergraduate)
        val rows = body.getJSONObject("datas").getJSONArray("arrangedList")
        val unknown = JSONObject(rows.getJSONObject(0).toString()).put("courseCode", "not-arranged")
        listOf("beginTime", "endTime", "dayOfWeek", "beginSection", "endSection").forEach { unknown.put(it, JSONObject.NULL) }
        rows.put(unknown)
        val result = undergraduateWeek(body.toString())
        assertEquals(2, result.size)
        val course = result.single { it.courseCode == "not-arranged" }
        assertNull(course.date)
        assertEquals("", course.startTime)
        assertEquals("", course.endTime)
        assertNull(course.beginSection)
        assertNull(course.endSection)
        val arranged = result.single { it.courseCode == "sample-course" }
        assertEquals(1, arranged.beginSection)
        assertEquals(2, arranged.endSection)
    }

    @Test fun undergraduateWeeksUseActualReturnedDatesAndCurrentSelection() {
        val body = """{"code":0,"datas":[
            {"serialNumber":20,"term":"20261","startDate":"2027-1-18 00:00:00","endDate":"2027-1-24T00:00:00","curWeek":true,"name":"第20周"},
            {"serialNumber":1,"term":"20261","startDate":"2026-09-07","endDate":"2026-09-13","curWeek":false}
        ]}"""
        val result = AcademicTimetableProtocol.parseUndergraduateWeeks(body, term)
        assertEquals(listOf(1, 20), result.map { it.number })
        assertEquals(LocalDate.of(2027, 1, 18), result.last().start)
        assertTrue(result.last().current)
        assertFailure { AcademicTimetableProtocol.parseUndergraduateWeeks(body.replace("20261", "other"), term) }
        assertFailure { AcademicTimetableProtocol.parseUndergraduateWeeks(body.replace("2027-1-24", "2027-1-25"), term) }
        assertFailure { AcademicTimetableProtocol.parseUndergraduateWeeks(body.replace("\"serialNumber\":20", "\"serialNumber\":1"), term) }
        assertTrue(AcademicTimetableProtocol.parseUndergraduateWeeks("""{"code":"0","datas":[]}""", term).isEmpty())
    }

    @Test fun graduateTermOrderDoesNotClaimTheMostRecentTermIsCurrent() {
        val body = """{"code":"0","datas":{"kfdxnxqcx":{"totalSize":2,"rows":[
            {"XNXQDM":"20252","XNXQDM_DISPLAY":"旧学期"},
            {"XNXQDM":"20261","XNXQDM_DISPLAY":"当前学期"}
        ]}}}"""
        val result = AcademicTimetableProtocol.parseGraduateTerms(body)
        assertEquals(listOf("20261", "20252"), result.map { it.code })
        assertTrue(result.none { it.selected })
        assertFailure { AcademicTimetableProtocol.parseGraduateTerms(body.replace("\"totalSize\":2,", "")) }
    }

    @Test fun undergraduateEmptyResultIsDifferentFromFailureOrIncompleteData() {
        assertTrue(undergraduateWeek("""{"code":"0","datas":{"arrangedList":[]}}""").isEmpty())
        for (body in listOf(
            """{"code":"1","datas":{"arrangedList":[]}}""",
            """{"code":"0","datas":{}}""",
            """{"code":"0","datas":{"arrangedList":null}}""",
            """{"datas":{"arrangedList":[]}}""",
        )) assertFailure { undergraduateWeek(body) }
    }

    @Test fun undergraduateInvalidFieldsRejectWholeResponse() {
        for ((field, invalid) in listOf(
            "beginTime" to "24:00", "beginTime" to "08:60", "endTime" to "07:00",
            "dayOfWeek" to "8", "dayOfWeek" to "0", "beginSection" to "-1",
            "courseCode" to "", "courseName" to "", "endSection" to "0",
            "weeksAndTeachers" to JSONObject(),
        )) {
            val body = JSONObject(undergraduate)
            body.getJSONObject("datas").getJSONArray("arrangedList").getJSONObject(0).put(field, invalid)
            assertFailure { undergraduateWeek(body.toString()) }
        }
    }

    @Test fun duplicateRowsAreDeduplicatedButDistinctClassesAreNot() {
        val body = JSONObject(undergraduate)
        val rows = body.getJSONObject("datas").getJSONArray("arrangedList")
        rows.put(JSONObject(rows.getJSONObject(0).toString()))
        assertEquals(1, undergraduateWeek(body.toString()).size)
        rows.put(JSONObject(rows.getJSONObject(0).toString()).put("courseSerialNo", "second-class"))
        assertEquals(2, undergraduateWeek(body.toString()).size)
    }

    @Test fun graduateBitmapAndSchemeTimesDetermineActualLessons() {
        assertTrue(graduateWeek(graduate(), week = 1).isEmpty())
        assertTrue(graduateWeek(graduate(), week = 3).isEmpty())
        assertTrue(graduateWeek(graduate(), week = 5).isEmpty())
        val lesson = graduateWeek(graduate(), week = 2).single()
        assertEquals(LocalDate.of(2026, 9, 14), lesson.date)
        assertEquals("08:00", lesson.startTime)
        assertEquals("09:35", lesson.endTime)
        assertEquals(1, lesson.beginSection)
        assertEquals(2, lesson.endSection)
        assertEquals("示例课程", lesson.name)
        assertEquals(setOf("示例教师甲", "示例教师乙"), lesson.teachers.toSet())
        assertEquals(LocalDate.of(2026, 9, 28), graduateWeek(graduate(), week = 4).single().date)
    }

    @Test fun graduateAdjacentSectionsMergeAndGapsRemainSeparate() {
        val body = graduate()
        val rows = body.getJSONArray("jgList")
        rows.put(JSONObject(rows.getJSONObject(0).toString()).put("KSJCDM", 3).put("JSJCDM", 4))
        val merged = graduateWeek(body).single()
        assertEquals("08:00", merged.startTime)
        assertEquals("11:25", merged.endTime)
        assertEquals(4, merged.endSection)
        rows.getJSONObject(1).put("KSJCDM", 4).put("JSJCDM", 5)
        assertEquals(2, graduateWeek(body).size)
    }

    @Test fun graduateClassesLocationsTeachersAndWeekMasksDoNotMerge() {
        for ((key, value) in listOf("JASMC" to "松石楼 2-208", "JGJSXM" to "另一教师", "ZCBH" to "1101")) {
            val body = graduate()
            val rows = body.getJSONArray("jgList")
            rows.put(JSONObject(rows.getJSONObject(0).toString()).put("KSJCDM", 3).put("JSJCDM", 4).put(key, value))
            assertEquals(2, graduateWeek(body).size)
        }
        val body = graduate()
        body.getJSONArray("rwList").put(JSONObject(body.getJSONArray("rwList").getJSONObject(0).toString()).put("BJDM", "second-class"))
        body.getJSONArray("jgList").put(JSONObject(body.getJSONArray("jgList").getJSONObject(0).toString())
            .put("BJDM", "second-class").put("KSJCDM", 3).put("JSJCDM", 4))
        assertEquals(2, graduateWeek(body).size)
    }

    @Test fun graduateMissingOrContradictoryPeriodTimesAreNotReplacedWithDefaults() {
        fun slots(body: JSONObject) = body.getJSONArray("jcfaList").getJSONObject(0).getJSONArray("skjcList")
        val missing = graduate()
        slots(missing).remove(1)
        assertFailure { graduateWeek(missing) }
        val invalid = graduate()
        slots(invalid).getJSONObject(1).put("JSSJ", 960)
        assertFailure { graduateWeek(invalid) }
        val conflict = graduate()
        slots(conflict).put(JSONObject(slots(conflict).getJSONObject(0).toString()).put("KSSJ", 801))
        assertFailure { graduateWeek(conflict) }
        val reversed = graduate()
        slots(reversed).getJSONObject(1).put("KSSJ", 830)
        assertFailure { graduateWeek(reversed) }
        val wrongScheme = graduate()
        wrongScheme.getJSONArray("jgList").getJSONObject(0).put("JCFADM", "02")
        assertFailure { graduateWeek(wrongScheme) }
    }

    @Test fun graduateUsesReturnedSchemeForLaterEveningPeriods() {
        val body = graduate()
        body.getJSONArray("jcfaList").put(JSONObject("""{"skjcList":[
            {"JCFADM":"evening","DM":"14","KSSJ":"2130","JSSJ":"2215"}
        ]}"""))
        body.getJSONArray("jgList").getJSONObject(0)
            .put("JCFADM", "evening").put("KSJCDM", 14).put("JSJCDM", 14).put("XQ", 7)
        body.getJSONArray("rwList").getJSONObject(0).put("SCSKRQ", "2026-09-20")
        val lesson = graduateWeek(body).single()
        assertEquals("21:30", lesson.startTime)
        assertEquals("22:15", lesson.endTime)
        assertEquals(LocalDate.of(2026, 9, 20), lesson.date)
    }

    @Test fun graduateInfersMissingSchemeOnlyWhenOneSchemeIsReturned() {
        val body = graduate()
        val row = body.getJSONArray("jgList").getJSONObject(0)
        row.put("JCFADM", JSONObject.NULL)
        val lesson = graduateWeek(body).single()
        assertEquals("08:00", lesson.startTime)
        assertEquals("09:35", lesson.endTime)

        row.remove("JCFADM")
        assertEquals(lesson, graduateWeek(body).single())

        val schemes = body.getJSONArray("jcfaList")
        schemes.put(JSONObject(schemes.getJSONObject(0).toString().replace("\"01\"", "\"02\"")))
        assertFailure { graduateWeek(body) }
    }

    @Test fun graduateChecksIdentitiesAndFieldsEvenForUnselectedWeeks() {
        for ((key, invalid) in listOf("ZCBH" to "01x1", "XQ" to "9", "KSJCDM" to "0", "XNXQDM" to "20251")) {
            val body = graduate()
            body.getJSONArray("jgList").getJSONObject(0).put(key, invalid)
            assertFailure { graduateWeek(body, week = 1) }
        }
        val unmatched = graduate()
        unmatched.getJSONArray("jgList").getJSONObject(0).put("BJDM", "unknown-class")
        assertFailure { graduateWeek(unmatched) }
        val otherTerm = graduate()
        otherTerm.getJSONArray("rwList").getJSONObject(0).put("XNXQDM", "20251")
        assertFailure { graduateWeek(otherTerm) }
    }

    @Test fun graduateEmptyAndMalformedResultsAreDifferent() {
        assertTrue(graduateWeek(JSONObject("""{"code":1,"rwList":[],"jgList":[]}""")).isEmpty())
        for (body in listOf(
            """{"code":0,"rwList":[],"jgList":[]}""",
            """{"code":1,"rwList":[]}""",
            """{"code":1,"rwList":[],"jgList":[{}]}""",
        )) assertFailure { graduateWeek(JSONObject(body)) }
    }

    @Test fun graduateInconsistentFirstClassDateCannotBeHiddenByTheDefaultAnchor() {
        val body = graduate()
        body.getJSONArray("rwList").getJSONObject(0).put("SCSKRQ", "2026-09-15")
        assertFailure { graduateWeek(body) }
        assertFailure { AcademicTimetableProtocol.graduateWeeks(body.toString(), term, start) }
    }

    @Test fun graduateQueryableWeeksAndFullSectionTimelineComeFromTheResponse() {
        val body = graduate()
        body.getJSONArray("jgList").getJSONObject(0).put("ZCBH", "01" + "0".repeat(27) + "1")
        val weeks = AcademicTimetableProtocol.graduateWeeks(body.toString(), term, start)
        assertEquals(30, weeks.size)
        assertEquals(start, weeks.first().start)
        assertEquals(start.plusWeeks(29).plusDays(6), weeks.last().end)
        val periods = AcademicTimetableProtocol.graduateSections(body.toString())
        assertEquals(listOf(1, 2, 3, 4, 5), periods.map { it.number })
        assertEquals("11:30", periods.last().start)
        assertEquals("12:15", periods.last().end)
        assertEquals(1, graduateWeek(body, 30).size)
    }

    @Test fun graduateDefaultAnchorOnlyFillsMissingEvidenceFor2026Autumn() {
        val body = graduate()
        body.getJSONArray("rwList").getJSONObject(0).remove("SCSKRQ")
        assertEquals(start, AcademicTimetableProtocol.graduateWeeks(body.toString(), term, start).first().start)
        assertFailure { AcademicTimetableProtocol.graduateWeeks(body.toString(), term, start.plusWeeks(1)) }
        val other = AcademicTerm("20252", "2025-2026学年第二学期")
        body.getJSONArray("rwList").getJSONObject(0).put("XNXQDM", other.code)
        assertFailure { AcademicTimetableProtocol.graduateWeeks(body.toString(), other, start) }
        body.getJSONArray("rwList").getJSONObject(0).put("SCSKRQ", "2026-03-09")
        assertEquals(LocalDate.of(2026, 3, 2), AcademicTimetableProtocol.graduateWeeks(body.toString(), other, start).first().start)
    }

    @Test fun graduateUnarrangedTasksDoNotDiscardTheArrangedClasses() {
        val body = graduate()
        body.getJSONArray("rwList").put(JSONObject("""{"BJDM":"not-arranged","XNXQDM":"20261"}"""))
        assertEquals(1, graduateWeek(body).size)
        assertEquals(4, AcademicTimetableProtocol.graduateWeeks(body.toString(), term, start).size)
        body.put("jgList", org.json.JSONArray())
        assertTrue(graduateWeek(body).isEmpty())
        assertTrue(AcademicTimetableProtocol.graduateWeeks(body.toString(), term, start).isEmpty())
    }

    @Test fun graduateTaskNameCanFillInTheMissingScheduleName() {
        val body = graduate()
        val row = body.getJSONArray("jgList").getJSONObject(0)
        body.getJSONArray("rwList").getJSONObject(0).put("KCMC", row.getString("KCMC"))
        row.remove("KCMC")
        assertEquals("示例课程", graduateWeek(body).single().name)
    }

    @Test fun malformedTransportAndInvalidWeekFailWithoutExposingResponse() {
        for (body in listOf("<html>private-session=secret</html>", "[]", "$undergraduate trailing-private-data")) {
            val error = assertFailure { undergraduateWeek(body) }
            assertFalse(error.message.orEmpty().contains("secret"))
            assertFalse(error.message.orEmpty().contains("private"))
        }
        assertFailure { undergraduateWeek(undergraduate, week = 0) }
        assertFailure { graduateWeek(graduate(), week = -1) }
        assertFailure { AcademicTimetableProtocol.parseGraduateWeek(graduate().toString(), term, 2, start.plusDays(1)) }
    }

    private fun graduate(): JSONObject = JSONObject(
        checkNotNull(javaClass.getResourceAsStream("/gsmis-timetable-sample.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    )

    private fun undergraduateWeek(body: String, week: Int = 2) =
        AcademicTimetableProtocol.parseUndergraduateWeek(body, term, week, start)

    private fun graduateWeek(body: JSONObject, week: Int = 2) =
        AcademicTimetableProtocol.parseGraduateWeek(body.toString(), term, week, start)

    private fun assertFailure(block: () -> Any?): IclassException {
        try {
            block()
        } catch (error: IclassException) {
            return error
        }
        throw AssertionError("Expected a safe IclassException")
    }
}
