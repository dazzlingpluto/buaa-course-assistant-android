package org.buaacourse.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GsmisProtocolTest {
    private val start = LocalDate.of(2026, 9, 7)
    private val autumn = AcademicTerm("opaque-autumn", "2026-2027学年第一学期")
    private val spring = AcademicTerm("opaque-spring", "2026-2027学年第二学期")

    @Test fun termRowsRequireAnExplicitCodeAndDisplayName() {
        val terms = GsmisProtocol.parseTerms("""{"datas":{"kfdxnxqcx":{"rows":[
            {"XNXQDM":" term-a ","XNXQDM_DISPLAY":"2026-2027学年第一学期"},
            {"XNXQDM":"term-b","XNXQMC":"2026-2027学年第二学期"},
            {"XNXQDM":"term-a","XNXQDM_DISPLAY":"2026-2027学年第一学期"}
        ]}}}""")
        assertEquals(2, terms.size)
        assertEquals("term-a", terms[0].code)
        assertEquals("2026-2027学年第二学期", terms[1].name)
        assertFailure { GsmisProtocol.parseTerms("""{"datas":{"kfdxnxqcx":{"rows":[{"XNXQDM":"20261"}]}}}""") }
        assertFailure { GsmisProtocol.parseTerms("""{"datas":{"kfdxnxqcx":{"rows":[{"XNXQMC":"2026秋"}]}}}""") }
        assertFailure { GsmisProtocol.parseTerms("""{"datas":{"kfdxnxqcx":{"rows":[{"XNXQDM":"same","MC":"2026秋"},{"XNXQDM":"same","MC":"2027春"}]}}}""") }
    }

    @Test fun explicitTermSelectionNeverFallsBackToAnUnrequestedTerm() {
        val terms = listOf(autumn, spring, AcademicTerm("20261", "opaque label"))
        assertEquals(spring, GsmisProtocol.chooseTerm(terms, start, "opaque-spring"))
        assertEquals(terms[2], GsmisProtocol.chooseTerm(terms, start, "20261"))
        assertNull(GsmisProtocol.chooseTerm(terms, start, "missing"))
    }

    @Test fun suppliedTermResponseStatusAndRowCountMustBeConsistent() {
        fun response(code: String, total: String) = """{"code":$code,"datas":{"kfdxnxqcx":{
            "totalSize":$total,"rows":[{"XNXQDM":"term-a","XNXQDM_DISPLAY":"2026秋"}]
        }}}"""
        assertEquals("term-a", GsmisProtocol.parseTerms(response("0", "1")).single().code)
        assertEquals("term-a", GsmisProtocol.parseTerms(response("\"0\"", "\"1\"")).single().code)
        for (code in listOf("1", "null", "{}")) assertFailure { GsmisProtocol.parseTerms(response(code, "1")) }
        for (total in listOf("0", "2", "-1", "null", "{}")) assertFailure { GsmisProtocol.parseTerms(response("0", total)) }
    }

    @Test fun automaticTermSelectionUsesDisplayYearsAndSeasonInsteadOfOpaqueCodes() {
        val terms = listOf(spring, autumn)
        assertEquals(autumn, GsmisProtocol.chooseTerm(terms, start, null))
        assertEquals(spring, GsmisProtocol.chooseTerm(terms, LocalDate.of(2027, 2, 22), null))
        val calendarAutumn = AcademicTerm("a", "2026年秋季学期")
        val calendarSpring = AcademicTerm("b", "2027年春季学期")
        assertEquals(calendarAutumn, GsmisProtocol.chooseTerm(listOf(calendarAutumn, calendarSpring), start, null))
        assertEquals(calendarSpring, GsmisProtocol.chooseTerm(listOf(calendarAutumn, calendarSpring), LocalDate.of(2027, 2, 22), null))
        assertNull(GsmisProtocol.chooseTerm(listOf(AcademicTerm("20261", "20261")), start, null))
        assertNull(GsmisProtocol.chooseTerm(listOf(AcademicTerm("x", "2026-2027学年")), start, null))
        assertNull(GsmisProtocol.chooseTerm(listOf(AcademicTerm("x", "2026-2028学年第一学期")), start, null))
    }

    @Test fun ambiguousSemesterLabelsRequireAChoice() {
        assertNull(GsmisProtocol.chooseTerm(listOf(autumn, AcademicTerm("other", "2026年秋季学期")), start, null))
        assertNull(GsmisProtocol.chooseTerm(listOf(AcademicTerm("both", "2026-2027春秋学期")), start, null))
    }

    @Test fun graduateTasksJoinTimetableCodesWithoutDuplicatingRepeatedPlacements() {
        val snapshot = GsmisProtocol.parseSnapshot("""{"code":1,
            "rwList":[{"BJDM":" B1 ","KCMC":" 高等数学 ","KCDM":" C1 ","XNXQDM":"opaque-autumn"},{"BJDM":"B2","KCMC":"物理"}],
            "jgList":[{"BJDM":"B1","KCDM":"C1"},{"BJDM":"B1","KCDM":"C1"},{"BJDM":"B2","KCDM":"C2"}]
        }""", autumn)
        assertEquals(autumn, snapshot.term)
        assertEquals(listOf(AcademicCourse("C1", "B1", "高等数学"), AcademicCourse("C2", "B2", "物理")), snapshot.courses)
    }

    @Test fun graduateNamesCanComeFromPlacementsAndAgreeAcrossBothListsAfterNormalization() {
        val snapshot = GsmisProtocol.parseSnapshot("""{"code":1,
            "rwList":[{"BJDM":"B1","XNXQDM":"opaque-autumn","SCSKRQ":"2026-09-07"},
                {"BJDM":"B2","KCMC":"矩阵 理论（Ⅰ）","XNXQDM":"opaque-autumn"}],
            "jgList":[{"BJDM":"B1","KCDM":"C1","KCMC":"高等数学","XQ":1,"KSJCDM":1,"JSJCDM":2},
                {"BJDM":"B1","KCDM":"C1","KCMC":"高等 数学","XQ":3,"KSJCDM":1,"JSJCDM":2},
                {"BJDM":"B2","KCDM":"C2","KCMC":"矩阵理论(I)"}]
        }""", autumn)
        assertEquals(listOf(AcademicCourse("C1", "B1", "高等数学"), AcademicCourse("C2", "B2", "矩阵 理论（Ⅰ）")), snapshot.courses)
        val schedule = week(Course("active", "高等数学", courseNum = "C1"), Course("old", "已退课程", courseNum = "OLD"))
        val compared = GsmisProtocol.reconcile(schedule, snapshot, listOf(autumn))
        assertEquals(listOf("active"), compared.days.single().courses.map { it.id })
        assertEquals(listOf("old"), compared.academic!!.excluded.map { it.id })
    }

    @Test fun conflictingOrInvalidNameSourcesCannotAuthorizeFiltering() {
        val invalidBodies = listOf(
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":"数学"}],"jgList":[{"BJDM":"B1","KCDM":"C1","KCMC":"物理"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1"}],"jgList":[{"BJDM":"B1","KCDM":"C1","KCMC":"数学"},{"BJDM":"B1","KCDM":"C1","KCMC":"物理"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":"数学"}],"jgList":[{"BJDM":"B1","KCDM":"C1","KCMC":{}}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":{}}],"jgList":[{"BJDM":"B1","KCDM":"C1","KCMC":"数学"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":" "}],"jgList":[{"BJDM":"B1","KCDM":"C1","KCMC":null}]}""",
        )
        invalidBodies.forEach { body -> assertFailure { GsmisProtocol.parseSnapshot(body, autumn) } }
    }

    @Test fun incompleteOrInconsistentGraduateIdentitiesNeverBecomeAnEmptyEnrollmentList() {
        val invalidBodies = listOf(
            """{"code":1,"rwList":[],"jgList":[{"BJDM":"B1","KCDM":"C1"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":"数学"}],"jgList":[{"BJDM":"B2","KCDM":"C1"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":"数学"}],"jgList":[{"BJDM":"B1"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":"数学","KCDM":"wrong"}],"jgList":[{"BJDM":"B1","KCDM":"C1"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":"数学","XNXQDM":"other-term"}],"jgList":[{"BJDM":"B1","KCDM":"C1"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":"数学","XNXQDM":{}}],"jgList":[{"BJDM":"B1","KCDM":"C1"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1","KCMC":"数学"}],"jgList":[{"BJDM":"B1","KCDM":"C1"},{"BJDM":"B1","KCDM":"C2"}]}""",
            """{"code":1,"rwList":[{"BJDM":"B1"}],"jgList":[{"BJDM":"B1","KCDM":"C1"}]}""",
            """{"code":1,"rwList":[{"KCMC":"数学"}],"jgList":[{"BJDM":"B1","KCDM":"C1"}]}""",
        )
        invalidBodies.forEach { body -> assertFailure { GsmisProtocol.parseSnapshot(body, autumn) } }
    }

    @Test fun validSelectedTasksWithoutPlacementsAreNotTreatedAsMalformedSchedules() {
        val empty = GsmisProtocol.parseSnapshot("""{"code":1,
            "rwList":[{"BJDM":"not-arranged","XNXQDM":"opaque-autumn"}],"jgList":[]
        }""", autumn)
        assertTrue(empty.courses.isEmpty())
        val mixed = GsmisProtocol.parseSnapshot("""{"code":1,
            "rwList":[{"BJDM":"B1"},{"BJDM":"not-arranged"}],
            "jgList":[{"BJDM":"B1","KCDM":"C1","KCMC":"数学"}]
        }""", autumn)
        assertEquals(listOf(AcademicCourse("C1", "B1", "数学")), mixed.courses)
    }

    @Test fun htmlAccessRestrictionAndWrongJsonShapesAreFailuresWithoutResponseLeaks() {
        for (body in listOf(
            "<html>请在校园网环境下访问 ticket-secret</html>", "{}", "[]", "null",
            """{"code":0,"rwList":[],"jgList":[],"message":"ticket-secret"}""",
            """{"code":1,"rwList":[],"jgList":null}""",
            """{"code":1,"rwList":[],"jgList":[]} trailing-ticket-secret""",
        )) {
            val error = runCatching { GsmisProtocol.parseSnapshot(body, autumn) }.exceptionOrNull()
            assertTrue(error is IclassException)
            assertFalse(error!!.message.orEmpty().contains("ticket-secret"))
        }
        assertFailure { GsmisProtocol.parseTerms("<html>请在校园网环境下访问</html>") }
        assertFailure { GsmisProtocol.parseTerms("{}") }
    }

    @Test fun trulyEmptyOfficialTimetableKeepsAllIclassRowsForReview() {
        val snapshot = GsmisProtocol.parseSnapshot("""{"code":"1","rwList":[],"jgList":[]}""", autumn)
        val original = week(Course("a", "数学", courseNum = "C1"))
        val result = GsmisProtocol.reconcile(original, snapshot, listOf(autumn))
        assertEquals(original.days, result.days)
        assertEquals(AcademicStatus.UNMATCHED, result.academic!!.status)
        assertTrue(result.academic!!.excluded.isEmpty())
        assertTrue(result.academic!!.verifiedScheduleIds.isEmpty())
        assertEquals(1, result.academic!!.uncertainCount)
    }

    @Test fun exactCourseOrClassCodeAndNormalizedNameVerifyWhileUnlistedCourseIsExcluded() {
        val byCourseCode = Course("a", " 高等 数学（Ⅰ） ", courseNum = " C1 ", scheduleIds = listOf("a", "a-teacher"))
        val byClassCode = Course("b", "物理", courseNum = "B2")
        val withdrawn = Course("c", "已退课程", courseNum = "OLD")
        val original = week(byCourseCode, byClassCode, withdrawn)
        val snapshot = AcademicSnapshot(autumn, listOf(
            AcademicCourse("C1", "B1", "高等数学(I)"), AcademicCourse("C2", "B2", "物理"),
        ))
        val result = GsmisProtocol.reconcile(original, snapshot, listOf(autumn, spring))
        assertEquals(listOf(byCourseCode, byClassCode), result.days[0].courses)
        assertEquals(listOf(withdrawn), result.academic!!.excluded)
        assertEquals(setOf("a", "a-teacher", "b"), result.academic!!.verifiedScheduleIds)
        assertEquals(AcademicStatus.VERIFIED, result.academic!!.status)
        assertEquals(0, result.academic!!.uncertainCount)
        assertEquals(autumn.code, result.academic!!.termCode)
        assertEquals(listOf(autumn, spring), result.academic!!.terms)
        assertEquals(listOf(byCourseCode, byClassCode, withdrawn), original.days[0].courses)
        assertNull(original.academic)
    }

    @Test fun SameNameDifferentClassOrSameCodeDifferentNameStayUnverified() {
        val current = Course("good", "数学", courseNum = "B-NEW")
        val oldClass = Course("old", "数学", courseNum = "B-OLD")
        val renamed = Course("renamed", "其他名字", courseNum = "C1")
        val missingCode = Course("no-code", "没有编号")
        val missingName = Course("no-name", "", courseNum = "C3")
        val placeholderName = Course("placeholder", "未知课程", courseNum = "C4")
        val original = week(current, oldClass, renamed, missingCode, missingName, placeholderName)
        val result = GsmisProtocol.reconcile(original,
            AcademicSnapshot(autumn, listOf(AcademicCourse("C1", "B-NEW", "数学"))), listOf(autumn))
        assertEquals(original.days, result.days)
        assertEquals(5, result.academic!!.uncertainCount)
        assertEquals(setOf("good"), result.academic!!.verifiedScheduleIds)
        assertTrue(result.academic!!.excluded.isEmpty())
    }

    @Test fun completelyDifferentCodeDomainsDoNotCauseMassExclusion() {
        val original = week(Course("a", "数学", courseNum = "ICLASS-1"), Course("b", "已退课程", courseNum = "ICLASS-2"))
        val result = GsmisProtocol.reconcile(original,
            AcademicSnapshot(autumn, listOf(AcademicCourse("GSMIS-1", "B1", "数学"))), listOf(autumn))
        assertEquals(original.days, result.days)
        assertEquals(AcademicStatus.UNMATCHED, result.academic!!.status)
        assertTrue(result.academic!!.excluded.isEmpty())
        assertTrue(result.academic!!.verifiedScheduleIds.isEmpty())
    }

    @Test fun partialDayFailuresAndOriginalObjectsSurviveFiltering() {
        val valid = Course("a", "数学", courseNum = "C1")
        val schedule = WeekSchedule(1, start, listOf(
            DaySchedule(start, listOf(valid)), DaySchedule(start.plusDays(1), emptyList(), failed = true),
        ))
        val result = GsmisProtocol.reconcile(schedule,
            AcademicSnapshot(autumn, listOf(AcademicCourse("C1", "B1", "数学"))), listOf(autumn))
        assertEquals(schedule.days, result.days)
        assertTrue(result.days[1].failed)
        assertNull(schedule.academic)
    }

    @Test fun reusedScheduleIdCannotAuthorizeAnUnmatchedRow() {
        val original = week(Course("shared", "数学", courseNum = "C1"), Course("shared", "数学", courseNum = "old-class"))
        val result = GsmisProtocol.reconcile(original,
            AcademicSnapshot(autumn, listOf(AcademicCourse("C1", "B1", "数学"))), listOf(autumn))
        assertTrue(result.academic!!.verifiedScheduleIds.isEmpty())
        assertEquals(1, result.academic!!.uncertainCount)
    }

    private fun week(vararg courses: Course) = WeekSchedule(1, start, listOf(DaySchedule(start, courses.toList())))

    private fun assertFailure(action: () -> Any?) {
        assertTrue(runCatching(action).exceptionOrNull() is IclassException)
    }
}
