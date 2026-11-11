package org.buaacourse.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A memory-only authenticated session. Each sign-in owns its cookies and outstanding calls. */
class IclassRepository(
    private val logger: (String, String) -> Unit = { _, _ -> },
    private val baseClient: OkHttpClient = defaultClient(),
    private val schoolClock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
) : CourseRepository {
    private val lock = Any()
    private var active: NetworkSession? = null
    private class NetworkSession(val endpoints: IclassProtocol.Endpoints, val mode: NetworkMode, val username: String, baseClient: OkHttpClient) {
        val cookies = SessionCookies()
        val cancelled = AtomicBoolean(false)
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
        val client = baseClient.newBuilder().cookieJar(cookies)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        @Volatile var captchaPage: Page? = null
        @Volatile var user: UserSession? = null
        @Volatile var sessionId: String = ""
        @Volatile var iclassUserId: String = ""
        @Volatile var studentType: StudentType = StudentType.AUTO
        @Volatile var academicTermCode: String? = null
        @Volatile var academicTerms: List<AcademicTerm> = emptyList()

        fun close() {
            cancelled.set(true)
            calls.forEach { it.cancel() }
            cookies.clear()
            captchaPage = null
            user = null
            sessionId = ""
            iclassUserId = ""
            academicTermCode = null
            academicTerms = emptyList()
        }
    }

    internal class SessionCookies : CookieJar {
        private val storedCookies = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            storedCookies.removeAll { it.expiresAt <= now }
            for (cookie in cookies) {
                storedCookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > now) storedCookies.add(cookie)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            storedCookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return storedCookies.filter { it.matches(url) }
        }
        @Synchronized fun clear() = storedCookies.clear()
    }

    private data class Page(val url: HttpUrl, val code: Int, val location: String?, val body: String, val bytes: ByteArray = byteArrayOf())
    private data class AuthenticatedSession(val session: NetworkSession, val userId: String)
    private class HttpAccessDenied : IclassException("账号访问被拒绝，请重新登录或检查账号权限")

    override suspend fun login(username: String, password: String, mode: NetworkMode): UserSession = loginWithCaptcha(username, password, mode, "")

    override suspend fun loginWithCaptcha(username: String, password: String, mode: NetworkMode, captcha: String): UserSession {
        if (username.isBlank() || password.isEmpty()) throw IclassException("请输入账号和统一认证密码")
        val session = synchronized(lock) {
            active?.takeIf { it.user == null && it.captchaPage != null && it.mode == mode && it.username == username.trim() }
                ?: NetworkSession(IclassProtocol.endpoints(mode), mode, username.trim(), baseClient).also { active?.close(); active = it }
        }
        return try {
            log("正在连接统一身份认证服务")
            val entry = session.captchaPage ?: follow(session, request(session.endpoints.login))
            if (captcha.isBlank()) CasLoginProtocol.captcha(entry.body)?.let { challenge ->
                throw CaptchaRequiredException(loadCaptcha(session, entry, challenge))
            }
            val form = IclassProtocol.parseLoginForm(entry.body, entry.url, username, password, captcha.trim())
            log("已获取统一认证页面，正在提交认证")
            val formBody = FormBody.Builder().apply { form.fields.forEach { (key, value) -> add(key, value) } }.build()
            val result = follow(session, request(form.action).newBuilder().post(formBody)
                .header("Referer", entry.url.toString()).build(), containsPassword = true,
                directCasHandoff = mode == NetworkMode.DIRECT)
            CasLoginProtocol.captcha(result.body)?.let { challenge ->
                throw CaptchaRequiredException(
                    loadCaptcha(session, result, challenge),
                    "登录尚未完成，请重新输入验证码后重试",
                )
            }
            IclassProtocol.validateLoginResponse(result.body)

            log("认证提交完成，正在确认学校登录会话")
            val user = readSchoolSession(session)
            synchronized(lock) {
                ensureSession(session)
                session.user = user
                session.captchaPage = null
            }
            log("登录成功", "success")
            user
        } catch (error: Exception) {
            if (error is CaptchaRequiredException) { ensureSession(session); throw error }
            session.close()
            synchronized(lock) { if (active === session) active = null }
            if (error is CancellationException) throw error
            throw safeError(error)
        }
    }

    override suspend fun refreshLoginCaptcha(): LoginCaptcha {
        val session = synchronized(lock) { active?.takeIf { it.user == null && it.captchaPage != null } }
            ?: throw IclassException("验证码会话已失效，请重新登录")
        val page = follow(session, request(session.endpoints.login))
        val challenge = CasLoginProtocol.captcha(page.body) ?: throw IclassException("未取得验证码，请重新登录")
        return loadCaptcha(session, page, challenge)
    }

    private suspend fun loadCaptcha(
        session: NetworkSession,
        page: Page,
        requirement: CasLoginProtocol.Captcha,
    ): LoginCaptcha {
        val id = requirement.id ?: throw IclassException("未取得验证码，请重新登录")
        val endpoint = schoolUrl(session, "https://sso.buaa.edu.cn/captcha")
            .newBuilder().addQueryParameter("captchaId", id).build()
        val image = follow(session, request(endpoint).newBuilder().header("Cache-Control", "no-cache").build())
        if (image.bytes.size > 512 * 1024 || !CasLoginProtocol.isImage(image.bytes)) {
            throw IclassException("验证码图片读取失败，请重试")
        }
        synchronized(lock) {
            ensureSession(session)
            session.captchaPage = page
        }
        return LoginCaptcha(image.bytes)
    }

    private suspend fun readSchoolSession(session: NetworkSession): UserSession {
        val launchUrl = "https://uc.buaa.edu.cn/api/login".toHttpUrl().newBuilder()
            .addQueryParameter("target", "https://uc.buaa.edu.cn/#/user/login")
            .build()
        follow(session, request(schoolUrl(session, launchUrl.toString())))

        val statusUrl = schoolUrl(session, "https://uc.buaa.edu.cn/api/uc/status")
        val statusRequest = request(statusUrl).newBuilder()
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val response = follow(session, statusRequest)
        if (response.url.newBuilder().query(null).fragment(null).build() != statusUrl) {
            throw IclassException("统一认证会话尚未建立，请重新登录")
        }
        return AcademicAccessProtocol.authenticatedUser(response.body)
    }

    private suspend fun activateIclass(session: NetworkSession): UserSession {
        val loginName = resolveLoginName(session)
        val loginUrl = session.endpoints.userLogin.newBuilder().addQueryParameter("phone", loginName)
            .addQueryParameter("password", "").addQueryParameter("userLevel", "1")
            .addQueryParameter("verificationType", "2").addQueryParameter("verificationUrl", "").build()
        val data = json(follow(session, request(loginUrl)).body)
        if (IclassProtocol.status(data) != "0") throw IclassException("课程服务拒绝登录，请稍后重试或检查账号权限")
        val userData = data.optJSONObject("result") ?: data
        val userId = IclassProtocol.value(userData, "id")
        val sessionId = IclassProtocol.value(userData, "sessionId")
        if (userId.isBlank() || sessionId.isBlank()) throw IclassException("登录成功但用户信息不完整，请重新登录")
        val user = UserSession(userId, IclassProtocol.value(userData, "realName").ifBlank { IclassProtocol.value(userData, "name") })
        synchronized(lock) { ensureSession(session); session.sessionId = sessionId; session.iclassUserId = userId }
        return user
    }

    private suspend fun resolveLoginName(session: NetworkSession): String {
        var url = session.endpoints.myCenter
        repeat(8) {
            val page = execute(session, request(url))
            IclassProtocol.extractLoginName(page.url.toString())?.let { return it }
            if (page.location != null && page.code in REDIRECTS) {
                val next = IclassProtocol.academicRedirect(page.url, page.location, session.mode)
                requireTrusted(next)
                IclassProtocol.extractLoginName(next.toString())?.let { return it }
                url = next
            } else {
                requireSuccess(page.code)
                IclassProtocol.extractLoginName(page.body)?.let { return it }
                throw IclassException("课程认证未返回登录凭据，请重新登录或检查学校服务状态")
            }
        }
        throw IclassException("课程认证跳转次数过多，请稍后重试")
    }

    override suspend fun loadWeek(week: Int, semesterStart: LocalDate): WeekSchedule {
        if (week !in 0..MAX_WEEKS) throw IclassException("请选择学校返回的有效周次")
        val (session, _) = authenticated()
        log("正在读取学校正式课表")
        val preferred = session.studentType
        val candidates = when {
            preferred == StudentType.GRADUATE -> listOf(StudentType.GRADUATE)
            preferred == StudentType.AUTO && detectPortal(session) == AcademicAccessProtocol.Portal.GRADUATE ->
                listOf(StudentType.GRADUATE)
            else -> listOf(StudentType.UNDERGRADUATE, StudentType.GRADUATE)
        }
        val errors = mutableListOf<String>()
        for (type in candidates) {
            try {
                return officialWeek(session, type, week, semesterStart)
            } catch (error: IclassException) {
                ensureSession(session)
                val source = if (type == StudentType.UNDERGRADUATE) "本科教务" else "研究生教务"
                errors += "$source：${error.message}"
            }
        }
        throw IclassException("学校正式课表读取失败（${errors.joinToString("；")}）")
    }

    override suspend fun loadDirectAttendance(week: Int, semesterStart: LocalDate): WeekSchedule {
        if (week !in 0..MAX_WEEKS) throw IclassException("请选择有效周次")
        val (session, _) = authenticated()
        val today = LocalDate.now(schoolClock)
        val monday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        val currentStart = today.with(monday)
        val suppliedStart = semesterStart.with(monday)
        val currentOffset = ChronoUnit.WEEKS.between(suppliedStart, currentStart)
        // Keep the current week accessible when the configured academic year is out of range.
        val start = if (week == 0 && currentOffset !in 0L until MAX_WEEKS.toLong()) currentStart else suppliedStart
        val weeks = (1..MAX_WEEKS).map { number ->
            val first = start.plusWeeks(number.toLong() - 1)
            AcademicWeek(number, first, first.plusDays(6), current = first == currentStart)
        }
        val selected = if (week == 0) weeks.first { it.current } else weeks[week - 1]
        log("正在直接读取 iClass 签到课程")
        val days = withTimeoutOrNull(15_000) {
            if (session.sessionId.isBlank() || session.iclassUserId.isBlank()) activateIclass(session)
            loadIclassDays(session, selected.start)
        }
        ensureSession(session)
        if (days == null || days.all { it.failed }) throw IclassException("iClass 签到课程读取失败，请稍后刷新重试")
        return WeekSchedule(selected.number, start, days.map { day ->
            day.copy(courses = day.courses.map { course ->
                course.copy(attendanceAvailable = course.realIds.isNotEmpty()).applyAttendance(emptySet())
            })
        }, studentType = session.studentType, weeks = weeks)
    }

    private suspend fun detectPortal(session: NetworkSession): AcademicAccessProtocol.Portal {
        return try {
            // An ordinary navigation lets BYXT redirect to CAS before we send AJAX requests.
            val response = follow(session, request(schoolUrl(session, BYXT_PROBE)))
            AcademicAccessProtocol.portal(response.code, response.url, response.body)
        } catch (_: IclassException) {
            ensureSession(session)
            AcademicAccessProtocol.Portal.UNAVAILABLE
        }
    }

    override suspend fun linkAttendance(schedule: WeekSchedule): WeekSchedule {
        val (session, _) = authenticated()
        if (schedule.academic?.status != AcademicStatus.VERIFIED) return schedule
        return try {
            val raw = withTimeoutOrNull(15_000) {
                if (session.sessionId.isBlank() || session.iclassUserId.isBlank()) activateIclass(session)
                WeekSchedule(schedule.week, schedule.semesterStart, loadIclassDays(session, schedule.days.first().date))
            }
            ensureSession(session)
            if (raw == null || raw.days.all { it.failed }) {
                schedule.copy(attendanceMessage = "正式课表已加载；iClass 签到服务暂不可用，可稍后刷新重试。")
            } else AcademicScheduleMapper.linkOfficial(schedule, raw).copy(attendanceMessage =
                if (raw.days.any { it.failed }) "部分日期的签到信息暂未取得，正式课表不受影响。" else null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ensureSession(session)
            log("正式课表已保留，签到服务暂不可用", "warning")
            schedule.copy(attendanceMessage = "正式课表已加载；iClass 签到服务暂不可用，可稍后刷新重试。")
        }
    }

    private suspend fun loadIclassDays(session: NetworkSession, start: LocalDate): List<DaySchedule> = coroutineScope {
        val userId = session.iclassUserId
        val days = (0L..6L).map { offset ->
            async {
                val date = start.plusDays(offset)
                try {
                    if (session.sessionId.isBlank() || userId.isBlank()) throw IclassException("iClass 签到服务尚未连接")
                    val url = session.endpoints.schedule.newBuilder().addQueryParameter("id", userId)
                        .addQueryParameter("dateStr", date.format(DateTimeFormatter.BASIC_ISO_DATE)).build()
                    val data = json(follow(session, authenticatedRequest(session, url)).body)
                    val courses = when (IclassProtocol.status(data)) {
                        "2" -> emptyList()
                        "0" -> IclassProtocol.parseCourses(data.optJSONArray("result")
                            ?: throw IclassException("课程数据格式异常，请刷新重试"))
                        else -> throw IclassException("课程接口返回失败，请检查登录状态")
                    }
                    DaySchedule(date, courses)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    log("${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} 课程获取失败", "warning")
                    DaySchedule(date, emptyList(), failed = true)
                }
            }
        }.awaitAll()
        ensureSession(session)
        days
    }

    override fun selectAcademicTerm(code: String) {
        synchronized(lock) {
            val session = active ?: throw IclassException("请先登录")
            ensureSession(session)
            if (session.academicTerms.none { it.code == code }) {
                throw IclassException("请选择教务系统返回的有效学期")
            }
            session.academicTermCode = code
        }
    }

    private fun schoolUrl(session: NetworkSession, raw: String): HttpUrl = when (session.mode) {
        NetworkMode.DIRECT -> raw.toHttpUrl()
        NetworkMode.VPN -> IclassProtocol.toWebVpnUrl(raw)
    }

    private suspend fun academicQuery(
        session: NetworkSession,
        type: StudentType,
        url: String,
        fields: Map<String, String>? = null,
    ): String {
        val endpoint = schoolUrl(session, url)
        val entryPoint = if (type == StudentType.UNDERGRADUATE) BYXT_REFERER else GSMIS_HOME
        val query = request(endpoint).newBuilder()
            .header("Referer", schoolUrl(session, entryPoint).toString())
            .header("X-Requested-With", "XMLHttpRequest")
        if (type == StudentType.UNDERGRADUATE) {
            query.header("Accept", "application/json, text/javascript, */*; q=0.01")
        }
        if (fields != null) {
            val form = FormBody.Builder()
            for ((name, value) in fields) form.add(name, value)
            query.post(form.build())
        }
        val page = follow(session, query.build())
        if (IclassProtocol.isAuthenticationTarget(page.url)) throw IclassException("教务认证未完成")
        return page.body
    }

    private suspend fun officialWeek(session: NetworkSession, type: StudentType, requestedWeek: Int, defaultStart: LocalDate): WeekSchedule {
        val undergraduate = type == StudentType.UNDERGRADUATE
        val terms = if (undergraduate) {
            stage("本科学期列表") {
                val body = try {
                    academicQuery(session, type, BYXT_TERMS)
                } catch (denied: HttpAccessDenied) {
                    val home = follow(session, request(schoolUrl(session, BYXT_REFERER)))
                    when (AcademicAccessProtocol.portal(home.code, home.url, home.body)) {
                        AcademicAccessProtocol.Portal.SIGN_IN -> throw IclassException("本科教务会话未建立，请重新登录")
                        AcademicAccessProtocol.Portal.GRADUATE -> throw denied
                        else -> academicQuery(session, type, BYXT_TERMS)
                    }
                }
                AcademicTimetableProtocol.parseUndergraduateTerms(body)
            }
        } else {
            stage("研究生教务登录") {
                val home = follow(session, request(schoolUrl(session, GSMIS_HOME)))
                if (IclassProtocol.isAuthenticationTarget(home.url)) throw IclassException("教务认证未完成")
            }
            stage("研究生学期列表") { AcademicTimetableProtocol.parseGraduateTerms(academicQuery(session, type, GSMIS_TERMS, emptyMap())) }
        }
        if (terms.isEmpty()) throw IclassException("学校未返回可查询学期")
        val requestedCode = session.academicTermCode
        val term = when {
            requestedCode != null -> terms.find { it.code == requestedCode }
            undergraduate -> terms.firstOrNull { it.selected } ?: terms.first()
            else -> GraduateTimetableParser.currentTerm(terms, defaultStart)
        }
        if (term == null) return academicTermSelection(session, type, terms, defaultStart)
        var graduateBody: String? = null
        val calendar = if (undergraduate) {
            stage("本科周次列表") { AcademicTimetableProtocol.parseUndergraduateWeeks(
                academicQuery(session, type, "$BYXT_WEEKS?termCode=${java.net.URLEncoder.encode(term.code, "UTF-8")}"), term) }
        } else {
            graduateBody = stage("研究生课表请求") { academicQuery(session, type, GSMIS_SCHEDULE,
                linkedMapOf("ZC" to "", "XNXQDM" to term.code, "XH" to "", "XQDM" to "")) }
            stage("研究生校历") { AcademicTimetableProtocol.graduateWeeks(checkNotNull(graduateBody), term, defaultStart) }
        }
        val weeks = calendar.sortedBy { it.number }.ifEmpty {
            val currentTerm = if (undergraduate) GsmisProtocol.chooseTerm(listOf(term), defaultStart, null)
                else GraduateTimetableParser.currentTerm(terms, defaultStart)
            if (term != currentTerm) return academicTermSelection(session, type, terms, defaultStart, term)
            listOf(AcademicWeek(1, defaultStart, defaultStart.plusDays(6)))
        }
        val today = LocalDate.now(schoolClock)
        val selected = if (requestedWeek == 0) weeks.firstOrNull { today >= it.start && today <= it.end }
            ?: weeks.firstOrNull { it.current } ?: weeks.first()
            else weeks.find { it.number == requestedWeek } ?: throw IclassException("学校未返回第 $requestedWeek 周，请选择有效周次")
        val anchor = selected.start.minusWeeks((selected.number - 1).toLong())
        val lessons = if (undergraduate) stage("本科周课表") {
            AcademicTimetableProtocol.parseUndergraduateWeek(academicQuery(session, type, BYXT_SCHEDULE,
                linkedMapOf("termCode" to term.code, "type" to "week", "week" to selected.number.toString())), term, selected.number, anchor)
        } else stage("研究生周课表") {
            AcademicTimetableProtocol.parseGraduateWeek(checkNotNull(graduateBody), term, selected.number, anchor)
        }
        val sections = if (undergraduate) lessonSections(lessons)
            else AcademicTimetableProtocol.graduateSections(checkNotNull(graduateBody))
        val blank = WeekSchedule(selected.number, anchor,
            (0L..6L).map { DaySchedule(selected.start.plusDays(it), emptyList()) })
        val result = AcademicScheduleMapper.merge(blank, lessons, term, terms).copy(studentType = type, weeks = weeks, sections = sections,
            attendanceMessage = "正式课表已加载，正在关联签到信息…")
        synchronized(lock) { ensureSession(session); session.studentType = type; session.academicTerms = terms }
        log("${if (undergraduate) "本科生" else "研究生"}正式课表已加载，共 ${result.total} 门课程", "success")
        return result
    }

    private fun academicTermSelection(
        session: NetworkSession,
        type: StudentType,
        terms: List<AcademicTerm>,
        start: LocalDate,
        unavailable: AcademicTerm? = null,
    ): WeekSchedule {
        synchronized(lock) { ensureSession(session); session.studentType = type; session.academicTerms = terms }
        val comparison = if (unavailable == null) AcademicComparison(AcademicStatus.SELECT_TERM, terms)
            else AcademicComparison(AcademicStatus.UNAVAILABLE, terms, unavailable.code, unavailable.name,
                message = "该学期尚未返回可查询周次，可选择其他学期或稍后刷新。")
        return WeekSchedule(1, start, emptyList(), academic = comparison, studentType = type)
    }

    private fun lessonSections(lessons: List<AcademicLesson>): List<AcademicPeriod> {
        val count = maxOf(12, lessons.maxOfOrNull { it.endSection ?: 0 } ?: 0)
        return (1..count).map { number -> AcademicPeriod(number,
            lessons.filter { it.beginSection == number }.map { it.startTime }.filter { it.isNotBlank() }.distinct().singleOrNull().orEmpty(),
            lessons.filter { it.endSection == number }.map { it.endTime }.filter { it.isNotBlank() }.distinct().singleOrNull().orEmpty()) }
    }

    private suspend fun <T> stage(label: String, block: suspend () -> T): T = try { block() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IclassException("$label：${safeError(error).message}")
        }

    override suspend fun signCourses(courses: List<Course>): SignSummary {
        val (session, _) = authenticated()
        if (session.sessionId.isBlank() || session.iclassUserId.isBlank()) activateIclass(session)
        val userId = session.iclassUserId
        if (session.sessionId.isBlank() || userId.isBlank()) throw IclassException("iClass 签到服务暂不可用，请重新登录后重试")
        val results = mutableListOf<SignItem>()
        val seen = mutableSetOf<String>()
        for (course in courses) {
            coroutineContext.ensureActive()
            ensureSession(session)
            if (!course.attendanceAvailable || course.attendanceSessions.size > 1) {
                results += SignItem(course.id, course.courseName, SignOutcome.FAILED, "未找到对应的 iClass 排课，暂不可签到")
                continue
            }
            val ids = (course.scheduleIds + course.id).filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) {
                results += SignItem("", course.courseName, SignOutcome.FAILED, "课程缺少排课编号，请刷新课表")
                continue
            }
            for (id in ids) {
                coroutineContext.ensureActive()
                ensureSession(session)
                if (!seen.add(id)) continue
                if (id in course.completedIds) {
                    results += SignItem(id, course.courseName, SignOutcome.SKIPPED, "该课程已签到")
                    continue
                }
                try {
                    val timestamp = IclassProtocol.value(json(follow(session,
                        authenticatedRequest(session, session.endpoints.timestamp)).body), "timestamp").trim()
                    if (timestamp.isBlank()) throw IclassException("签到服务器未返回时间凭据，请重试")
                    val url = session.endpoints.sign.newBuilder().addQueryParameter("courseSchedId", id)
                        .addQueryParameter("timestamp", timestamp).build()
                    val body = FormBody.Builder().add("id", userId).build()
                    val page = follow(session, authenticatedRequest(session, url).newBuilder().post(body).build())
                    val classification = IclassProtocol.classifySignResponse(json(page.body))
                    results += SignItem(id, course.courseName, classification.outcome, classification.message)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    results += SignItem(id, course.courseName, SignOutcome.FAILED, safeError(error).message.orEmpty())
                }
                delay(150)
            }
        }
        ensureSession(session)
        return SignSummary(results).also {
            log("签到完成：${it.success} 成功，${it.skipped} 已签到，${it.failed} 失败", if (it.failed > 0) "warning" else "success")
        }
    }

    override fun logout() {
        synchronized(lock) { active?.close(); active = null }
    }

    private fun authenticated(): AuthenticatedSession = synchronized(lock) {
        val session = active ?: throw IclassException("请先登录")
        val user = session.user ?: throw IclassException("请先登录")
        if (session.cancelled.get()) throw IclassException("请先登录")
        // Snapshot the immutable user ID while holding the same lock as logout.
        // Closing a session later must cancel work, not null-dereference its user.
        AuthenticatedSession(session, user.userId)
    }

    private fun ensureSession(session: NetworkSession) {
        if (session.cancelled.get() || synchronized(lock) { active !== session }) {
            throw CancellationException("Session ended")
        }
    }

    private fun request(url: HttpUrl): Request = Request.Builder().url(url)
        // Keep the previous CAS page variant; mobile UAs may receive a different form.
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
        .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
        .header("Accept-Language", "zh-CN,zh;q=0.9").build()

    private fun authenticatedRequest(session: NetworkSession, url: HttpUrl): Request =
        request(url).newBuilder().header("sessionId", session.sessionId).build()

    private suspend fun follow(
        session: NetworkSession,
        initial: Request,
        containsPassword: Boolean = false,
        directCasHandoff: Boolean = false,
    ): Page {
        var pending = initial
        var carriesPassword = containsPassword
        repeat(12) {
            requireTrusted(pending.url)
            if (carriesPassword && !IclassProtocol.isAuthenticationTarget(pending.url)) {
                throw IclassException("认证跳转到不可信的提交地址，已停止登录")
            }
            val page = execute(session, pending)
            if (page.code !in REDIRECTS || page.location == null) {
                requireSuccess(page.code)
                return page
            }
            val resolved = page.url.resolve(page.location) ?: throw IclassException("服务器跳转地址无效")
            // A direct CAS POST can finish with a redirect to a general portal.
            // The UC status request confirms the session without visiting that portal.
            if (directCasHandoff && page.code in setOf(302, 303) &&
                IclassProtocol.isAuthenticationTarget(page.url) &&
                !IclassProtocol.isAuthenticationTarget(resolved)) {
                return page
            }
            val next = IclassProtocol.academicRedirect(page.url, page.location, session.mode)
            requireTrusted(next)
            val builder = pending.newBuilder().url(next)
            if (page.code == 303 || (page.code in setOf(301, 302) && pending.method == "POST")) {
                builder.get().removeHeader("Content-Type").removeHeader("Content-Length")
                carriesPassword = false
            }
            if (next.host != pending.url.host || next.port != pending.url.port || next.scheme != pending.url.scheme) {
                builder.removeHeader("sessionId").removeHeader("Referer")
            }
            pending = builder.build()
        }
        throw IclassException("服务器跳转次数过多，请重新登录")
    }

    private suspend fun execute(session: NetworkSession, request: Request): Page {
        ensureSession(session)
        requireTrusted(request.url)
        return suspendCancellableCoroutine { continuation ->
            val call = session.client.newCall(request)
            session.calls.add(call)
            continuation.invokeOnCancellation { call.cancel() }
            if (session.cancelled.get()) call.cancel()
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    session.calls.remove(call)
                    if (continuation.isActive) continuation.resumeWithException(
                        if (session.cancelled.get()) CancellationException("Session ended") else safeError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val page = response.use {
                            val body = it.body
                            if (body != null && (body.contentLength() > MAX_RESPONSE_BYTES || body.source().request(MAX_RESPONSE_BYTES + 1))) {
                                throw IclassException("服务器响应过大，请稍后重试")
                            }
                            val bytes = body?.bytes() ?: byteArrayOf()
                            Page(it.request.url, it.code, it.header("Location"),
                                String(bytes, body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8), bytes)
                        }
                        ensureSession(session)
                        if (continuation.isActive) continuation.resume(page)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is CancellationException) error else safeError(error))
                    } finally {
                        session.calls.remove(call)
                    }
                }
            })
        }
    }

    private fun json(body: String): JSONObject = try { JSONObject(body) }
        catch (_: JSONException) { throw IclassException("服务器返回了无法识别的数据，请重新登录或稍后重试") }

    private fun requireTrusted(url: HttpUrl) {
        if (!IclassProtocol.isTrustedUrl(url)) throw IclassProtocol.untrustedTarget(url)
    }

    private fun requireSuccess(code: Int) {
        if (code == 401 || code == 403) throw HttpAccessDenied()
        if (code !in 200..299) throw IclassException("学校服务请求失败（HTTP $code）")
    }

    private fun safeError(error: Exception): IclassException = when (error) {
        is IclassException -> error
        is SSLException -> IclassException("安全连接验证失败，请检查设备日期、网络或学校证书")
        is SocketTimeoutException -> IclassException("网络请求超时，请检查校园网络或切换校外网络")
        is UnknownHostException -> IclassException("无法连接学校服务，请检查网络模式及网络连接")
        else -> IclassException("网络请求失败，请检查连接后重试")
    }

    private fun log(message: String, level: String = "info") {
        try { logger(message, level) } catch (_: Exception) { /* UI callbacks cannot break requests. */ }
    }

    companion object {
        private const val BYXT_PROBE = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/currentUser.do"
        private const val BYXT_WEEKS = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/getTermWeeks.do"
        private const val BYXT_REFERER = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.html"
        private const val BYXT_TERMS = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/student/schoolCalendars.do"
        private const val BYXT_SCHEDULE = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do"
        private const val GSMIS_HOME = "https://gsmis.buaa.edu.cn/gsapp/sys/wdkbapp/*default/index.do"
        private const val GSMIS_TERMS = "https://gsmis.buaa.edu.cn/gsapp/sys/wdkbapp/modules/xskcb/kfdxnxqcx.do"
        private const val GSMIS_SCHEDULE = "https://gsmis.buaa.edu.cn/gsapp/sys/wdkbapp/bykb/loadXskbData.do"
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val MAX_WEEKS = 60
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }
}
