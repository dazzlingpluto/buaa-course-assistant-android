package org.buaacourse.assistant

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

internal object CasLoginProtocol {
    data class Captcha(val id: String?)

    private val assignment = Regex("""(?<![\w$.])config\s*\.\s*captcha\s*=(?!=)\s*""")

    fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val header = String(bytes, 0, minOf(12, bytes.size), Charsets.ISO_8859_1)
        return header.startsWith("\u0089PNG\r\n\u001a\n") || header.startsWith("\u00ff\u00d8") ||
            header.startsWith("GIF87a") || header.startsWith("GIF89a") ||
            (header.length == 12 && header.startsWith("RIFF") && header.endsWith("WEBP"))
    }

    fun captcha(html: String): Captcha? {
        val document = Jsoup.parse(html)
        val scripts = document.select("script").map { it.data() }
        // Some responses consist only of the JavaScript assignment.
        val sources = if (scripts.isEmpty() && !html.trimStart().startsWith("<")) listOf(html) else scripts
        for (source in sources) {
            for (match in assignment.findAll(source)) {
                val value = try {
                    JSONTokener(source.substring(match.range.last + 1)).nextValue()
                } catch (_: JSONException) {
                    continue
                }
                when (value) {
                    true -> return Captcha(null)
                    is JSONObject -> {
                        val enabled = value.opt("enabled")
                        if (enabled == false) continue
                        val id = sequenceOf(value.opt("id"), value.opt("captchaId"))
                            .filterIsInstance<String>().map(String::trim)
                            .firstOrNull { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                        if (id != null || enabled == true || value.opt("type") == "image") return Captcha(id)
                    }
                }
            }
        }
        return null
    }
}
