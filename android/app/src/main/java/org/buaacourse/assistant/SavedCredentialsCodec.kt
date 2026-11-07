package org.buaacourse.assistant

import java.io.IOException
import org.json.JSONObject
import org.json.JSONTokener

/** The optional fields never grant automatic login to records from older APKs. */
internal object SavedCredentialsCodec {
    fun decode(bytes: ByteArray): SavedCredentials {
        val parser = JSONTokener(bytes.toString(Charsets.UTF_8))
        val json = parser.nextValue() as? JSONObject ?: invalid()
        val allowed = setOf("username", "password", "mode", "studentType", "autoLogin")
        if (parser.nextClean() != '\u0000' || json.keys().asSequence().any { it !in allowed }) invalid()
        val username = json.opt("username") as? String ?: invalid()
        val password = json.opt("password") as? String ?: invalid()
        val mode = when (json.opt("mode")) {
            "direct" -> NetworkMode.DIRECT
            "vpn" -> NetworkMode.VPN
            else -> invalid()
        }
        // Older versions saved a manual choice. It must not select a portal now:
        // the authenticated timetable source determines the student's identity.
        if (json.has("studentType") && json.opt("studentType") !in setOf("auto", "undergraduate", "graduate")) invalid()
        val autoLogin = if (!json.has("autoLogin")) false else json.opt("autoLogin") as? Boolean ?: invalid()
        return SavedCredentials(username, password, mode, StudentType.AUTO, autoLogin).also(::validate)
    }

    fun encode(value: SavedCredentials): ByteArray {
        validate(value)
        return JSONObject().put("username", value.username).put("password", value.password)
            .put("mode", if (value.mode == NetworkMode.VPN) "vpn" else "direct")
            .put("studentType", "auto")
            .put("autoLogin", value.autoLogin).toString().toByteArray(Charsets.UTF_8)
    }

    private fun validate(value: SavedCredentials) {
        if (value.username.isBlank() || value.username.length > 256 ||
            value.password.isEmpty() || value.password.length > 4096) invalid()
    }

    private fun invalid(): Nothing = throw IOException("Invalid saved record")
}
