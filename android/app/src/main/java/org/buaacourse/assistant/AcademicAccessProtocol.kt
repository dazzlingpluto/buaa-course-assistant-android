package org.buaacourse.assistant

import okhttp3.HttpUrl
import org.json.JSONException
import org.json.JSONObject

internal object AcademicAccessProtocol {
    enum class Portal { UNDERGRADUATE, GRADUATE, SIGN_IN, UNAVAILABLE }

    private const val BYXT_HOST = "byxt.buaa.edu.cn"
    private val vpnByxtPrefix = IclassProtocol.toWebVpnUrl("https://$BYXT_HOST/").encodedPath

    fun portal(status: Int, url: HttpUrl, body: String): Portal {
        if (status == 401 || status == 403 || IclassProtocol.isAuthenticationTarget(url)) {
            return Portal.SIGN_IN
        }
        if (status !in 200..299 || !IclassProtocol.isTrustedUrl(url)) return Portal.UNAVAILABLE

        val path = when (url.host) {
            BYXT_HOST -> url.encodedPath
            "d.buaa.edu.cn" -> {
                if (!url.encodedPath.startsWith(vpnByxtPrefix)) return Portal.UNAVAILABLE
                "/" + url.encodedPath.removePrefix(vpnByxtPrefix)
            }
            else -> return Portal.UNAVAILABLE
        }
        if (path.startsWith("/jwapp/sys/byrhmhsy/")) return Portal.GRADUATE
        if (path == "/jwapp/sys/homeapp/api/home/currentUser.do" && body.asObject()?.hasSuccessCode() == true) {
            return Portal.UNDERGRADUATE
        }
        return Portal.UNAVAILABLE
    }

    fun authenticatedUser(body: String): UserSession {
        val envelope = body.asObject()
        val account = envelope?.takeIf { it.hasSuccessCode() }?.optJSONObject("data")
        val userId = account?.let { it.wireText("schoolid").ifEmpty { it.wireText("username") } }.orEmpty()
        if (userId.isEmpty()) throw IclassException("统一认证会话尚未建立，请重新登录")
        val name = account?.wireText("name").orEmpty()
        return UserSession(userId, name.ifEmpty { userId })
    }

    private fun String.asObject(): JSONObject? = try {
        JSONObject(this)
    } catch (_: JSONException) {
        null
    }

    private fun JSONObject.hasSuccessCode(): Boolean = wireText("code") == "0"

    private fun JSONObject.wireText(field: String): String = when (val value = opt(field)) {
        is Number -> value.toString()
        is String -> value.trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
        else -> ""
    }
}
