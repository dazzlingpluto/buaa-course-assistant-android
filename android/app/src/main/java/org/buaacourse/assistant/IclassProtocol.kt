package org.buaacourse.assistant

import android.annotation.SuppressLint
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Pure protocol parsing, shared by the native client and offline regression tests. */
object IclassProtocol {
    private val vpnKey = "wrdvpnisthebest!".toByteArray(Charsets.UTF_8)
    private val loginNamePattern = Regex("loginName=([^&#\\\"'<>\\s]+)", RegexOption.IGNORE_CASE)
    private val signPaths = setOf("/app/common/get_timestamp.action", "/app/course/stu_scan_sign.action")

    private const val loginFormSelector = "form#loginForm, form#fm1, form:has(input[name=execution]), form:has(input[name=password])"

    data class Endpoints(
        val login: HttpUrl,
        val myCenter: HttpUrl,
        val userLogin: HttpUrl,
        val schedule: HttpUrl,
        val timestamp: HttpUrl,
        val sign: HttpUrl,
    )

    data class LoginForm(val action: HttpUrl, val fields: Map<String, String>)
    data class SignClassification(val outcome: SignOutcome, val message: String)

    fun endpoints(mode: NetworkMode): Endpoints {
        fun mapped(url: String): HttpUrl = if (mode == NetworkMode.VPN) toWebVpnUrl(url) else url.toHttpUrl()
        val login = if (mode == NetworkMode.VPN) {
            "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fd.buaa.edu.cn%2Flogin%3Fcas_login%3Dtrue"
        } else "https://sso.buaa.edu.cn/login"
        return Endpoints(
            mapped(login), mapped("https://iclass.buaa.edu.cn:8346/?type=jumpMyCenter"),
            mapped("https://iclass.buaa.edu.cn:8347/app/user/login.action"),
            mapped("https://iclass.buaa.edu.cn:8347/app/course/get_stu_course_sched.action"),
            mapped("http://iclass.buaa.edu.cn:8081/app/common/get_timestamp.action"),
            mapped("http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action"),
        )
    }

    fun toWebVpnUrl(raw: String): HttpUrl {
        val url = raw.toHttpUrl()
        if (url.host == "d.buaa.edu.cn") return url
        val defaultPort = (url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80)
        val protocol = if (defaultPort) url.scheme else "${url.scheme}-${url.port}"
        return HttpUrl.Builder().scheme("https").host("d.buaa.edu.cn")
            .encodedPath("/$protocol/${encryptHost(url.host)}${url.encodedPath}")
            .encodedQuery(url.encodedQuery).encodedFragment(url.encodedFragment).build()
    }

    // School WebVPN uses AES-CFB-style host obfuscation: ECB is used only as
    // the single-block primitive to produce each feedback keystream block.
    // This fixed public protocol key never encrypts user credentials or storage.
    @SuppressLint("GetInstance")
    private fun encryptHost(host: String): String {
        val plain = host.toByteArray(Charsets.UTF_8)
        val padded = ByteArray(((plain.size + 15) / 16) * 16) { '0'.code.toByte() }
        plain.copyInto(padded)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(vpnKey, "AES"))
        var feedback = vpnKey
        val encrypted = ByteArray(padded.size)
        for (offset in padded.indices step 16) {
            val stream = cipher.doFinal(feedback)
            val block = ByteArray(16) { i -> (padded[offset + i].toInt() xor stream[i].toInt()).toByte() }
            block.copyInto(encrypted, offset)
            feedback = block
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return hex(vpnKey) + hex(encrypted.copyOf(plain.size))
    }

    fun extractLoginName(value: String): String? {
        val encoded = loginNamePattern.find(value)?.groupValues?.get(1) ?: return null
        return try {
            // URLDecoder is a form decoder; protect literal base64 '+' first.
            URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8").takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) { null }
    }

    fun isTrustedUrl(url: HttpUrl): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.scheme == "http") return url.host == "iclass.buaa.edu.cn" && url.port == 8081 && url.encodedPath in signPaths
        return url.scheme == "https" && when (url.host) {
            "sso.buaa.edu.cn", "d.buaa.edu.cn", "uc.buaa.edu.cn", "gsmis.buaa.edu.cn", "byxt.buaa.edu.cn" -> url.port == 443
            "iclass.buaa.edu.cn" -> url.port in setOf(443, 8346, 8347)
            else -> false
        }
    }

    fun academicRedirect(from: HttpUrl, location: String, mode: NetworkMode): HttpUrl {
        val redirect = from.resolve(location) ?: throw IclassException("服务器跳转地址无效")
        val target = if (redirect.scheme == "http" && redirect.host == "sso.buaa.edu.cn" && redirect.port == 80) {
            redirect.newBuilder().scheme("https").port(443).build()
        } else {
            redirect
        }
        if (!isTrustedUrl(target)) throw untrustedTarget(target)
        return if (mode == NetworkMode.VPN) toWebVpnUrl(target.toString()) else target
    }

    internal fun untrustedTarget(url: HttpUrl): IclassException = IclassException(
        "服务器返回了不可信的地址，已停止请求（主机：${url.host}；协议：${url.scheme.uppercase()}；端口：${url.port}）",
    )

    fun isAuthenticationTarget(url: HttpUrl): Boolean {
        if (!isTrustedUrl(url) || url.scheme != "https") return false
        return when (url.host) {
            "sso.buaa.edu.cn" -> true
            "d.buaa.edu.cn" -> url.encodedPath.startsWith("/https/${encryptHost("sso.buaa.edu.cn")}/")
            else -> false
        }
    }

    fun parseLoginForm(
        html: String,
        pageUrl: HttpUrl,
        username: String,
        password: String,
        captcha: String = "",
    ): LoginForm {
        val challenge = CasLoginProtocol.captcha(html)
        if (challenge != null && captcha.isBlank()) throw IclassException("请输入验证码后继续登录")

        val form = Jsoup.parse(html).selectFirst(loginFormSelector)
            ?: throw IclassException("未取得统一认证表单，请重新登录")
        val action = form.attr("action")
        val resolved = pageUrl.resolve(action.ifBlank { pageUrl.toString() })
            ?: throw IclassException("统一认证表单提交地址无效")
        val target = when {
            pageUrl.host == "d.buaa.edu.cn" && resolved.host == "sso.buaa.edu.cn" &&
                isAuthenticationTarget(resolved) -> toWebVpnUrl(resolved.toString())
            pageUrl.host == "d.buaa.edu.cn" && resolved.host == pageUrl.host &&
                action.startsWith("/login") && resolved.encodedPath == "/login" ->
                toWebVpnUrl(resolved.newBuilder().host("sso.buaa.edu.cn").build().toString())
            else -> resolved
        }
        if (!isAuthenticationTarget(pageUrl) || target.host != pageUrl.host || !isAuthenticationTarget(target)) {
            throw IclassException("统一认证表单提交地址不可信，已停止登录")
        }

        val fields = linkedMapOf<String, String>()
        for (input in form.select("input[name]:not([disabled])")) {
            val name = input.attr("name")
            val type = input.attr("type").lowercase()
            if (name.isBlank() || type in setOf("button", "submit", "reset", "image", "file")) continue
            val checkable = type == "checkbox" || type == "radio"
            if (checkable && !input.hasAttr("checked")) continue
            fields[name] = if (checkable && !input.hasAttr("value")) "on" else input.attr("value")
        }
        if (fields["execution"].isNullOrBlank()) throw IclassException("统一认证会话参数缺失，请重新登录")
        fields["username"] = username.trim()
        fields["password"] = password
        fields.putIfAbsent("_eventId", "submit")
        if (challenge != null) {
            fields["captcha"] = captcha.trim()
            fields["captchaResponse"] = captcha.trim()
        }
        return LoginForm(target, fields)
    }

    fun validateLoginResponse(html: String) {
        if (CasLoginProtocol.captcha(html) != null) throw IclassException("统一认证需要验证码，请重新登录")
        if (Jsoup.parse(html).selectFirst(loginFormSelector) != null) {
            throw IclassException("统一认证未通过，请检查账号和密码后重试")
        }
    }


    fun value(data: JSONObject, key: String): String = data.opt(key)?.takeUnless { it === JSONObject.NULL }?.toString().orEmpty()
    fun status(data: JSONObject): String = if (data.has("STATUS")) value(data, "STATUS") else value(data, "status")
    fun message(data: JSONObject): String = listOf("ERRMSG", "ERRORMSG", "MSG", "message", "msg")
        .firstNotNullOfOrNull { key -> value(data, key).takeIf { it.isNotBlank() } }.orEmpty()

    fun classifySignResponse(data: JSONObject): SignClassification {
        val message = message(data)
        return when {
            status(data) == "0" -> SignClassification(SignOutcome.SUCCESS, "签到成功")
            "已签到" in message -> SignClassification(SignOutcome.SKIPPED, "该课程已签到")
            "时间" in message || "未开始" in message || "结束" in message ->
                SignClassification(SignOutcome.FAILED, "当前不在可签到时段，请核对课程时间")
            "登录" in message || "失效" in message || "过期" in message ->
                SignClassification(SignOutcome.FAILED, "登录状态可能已失效，请重新登录")
            else -> SignClassification(SignOutcome.FAILED, "服务器拒绝签到，请核对出勤情况及官方记录")
        }
    }

    fun parseCourses(array: JSONArray): List<Course> {
        val merged = linkedMapOf<List<String>, Course>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: throw IclassException("课程数据格式异常，请刷新重试")
            val id = value(row, "id")
            val course = Course(
                id = id,
                courseName = value(row, "courseName").ifBlank { value(row, "course_name").ifBlank { "未知课程" } },
                courseNum = value(row, "courseNum"),
                classBeginTime = value(row, "classBeginTime"),
                classEndTime = value(row, "classEndTime"),
                classroomName = value(row, "classroomName"),
                teachBuildName = value(row, "teachBuildName"),
                storeyName = value(row, "storeyName"),
                teachers = listOf(value(row, "teacherName").ifBlank { "未知" }),
                scheduleIds = listOf(id).filter { it.isNotBlank() },
                signed = value(row, "signStatus") == "1",
                signedScheduleIds = if (value(row, "signStatus") == "1") setOf(id) else emptySet(),
            )
            // Both time boundaries must agree before attendance IDs can merge.
            // Otherwise an exact official match could inherit another session's ID.
            val name = java.text.Normalizer.normalize(course.courseName, java.text.Normalizer.Form.NFKC)
                .replace(Regex("[\\s\\p{Z}]+"), "")
            val key = listOf(course.courseNum.trim(), name, course.classBeginTime, course.classEndTime, course.classroomName)
            val previous = merged[key]
            merged[key] = if (previous == null) course else previous.copy(
                teachers = (previous.teachers + course.teachers).distinct(),
                scheduleIds = (previous.scheduleIds + course.scheduleIds).distinct(),
                signed = previous.signed && course.signed,
                signedScheduleIds = previous.completedIds + course.completedIds,
            )
        }
        return merged.values.toList()
    }
}

/** Contains only a user-safe message; never wraps raw URLs, credentials or server HTML. */
open class IclassException(message: String) : Exception(message)
