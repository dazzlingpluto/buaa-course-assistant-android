package org.buaacourse.assistant

import java.io.IOException
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class SavedCredentialsCodecTest {
    @Test fun originalEncryptedThreeFieldFixtureRestoresWithoutAutomaticConsent() {
        val fixture = Base64.getDecoder().decode("AQABAgMEBQYHCAkKCzwgo2igl6x64CS1sZONHQDs9KsWgBosD08Il+E/UyKSdXXdiI+xc+sH0xCf7KcKFMw0D+k/9Jn4SedEO2WaKHAXoSkmV/LZBicDTAaZ")
        val bytes = CredentialCipher.decrypt(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), fixture)
        val saved = SavedCredentialsCodec.decode(bytes)
        assertEquals("demo", saved.username)
        assertEquals(" test password ", saved.password)
        assertEquals(NetworkMode.VPN, saved.mode)
        assertEquals(StudentType.AUTO, saved.studentType)
        assertFalse(saved.autoLogin)
    }

    @Test fun encryptedRecordsKeepConsentButAlwaysRestoreAutomaticPortalDetection() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        for (type in StudentType.entries) for (autoLogin in listOf(false, true)) {
            val original = SavedCredentials("demo", " password with spaces ", NetworkMode.DIRECT, type, autoLogin)
            val encrypted = CredentialCipher.encrypt(key, SavedCredentialsCodec.encode(original))
            val restored = SavedCredentialsCodec.decode(CredentialCipher.decrypt(key, encrypted))
            assertEquals(original.username, restored.username)
            assertEquals(original.password, restored.password)
            assertEquals(original.mode, restored.mode)
            assertEquals(StudentType.AUTO, restored.studentType)
            assertEquals(autoLogin, restored.autoLogin)
            assertEquals("SavedCredentials(redacted)", restored.toString())
        }
    }

    @Test fun missingAutomaticLoginFieldNeverEnablesIt() {
        val saved = SavedCredentialsCodec.decode("""{"username":"demo","password":"pw","mode":"direct","studentType":"undergraduate"}""".toByteArray())
        assertEquals(StudentType.AUTO, saved.studentType)
        assertFalse(saved.autoLogin)
    }

    @Test fun malformedConsentOrUnknownStudentTypeIsRejectedWithoutEchoingSecrets() {
        val invalid = listOf(
            """{"username":"private","password":"secret","mode":"direct","autoLogin":"true"}""",
            """{"username":"private","password":"secret","mode":"direct","autoLogin":null}""",
            """{"username":"private","password":"secret","mode":"direct","studentType":"other"}""",
            """{"username":"private","password":"secret","mode":"direct","extra":true}""",
            """{"username":"private","password":"secret","mode":"direct"} true""",
            """{"username":"private","password":"","mode":"direct"}""",
        )
        invalid.forEach { input ->
            val failure = assertThrows(IOException::class.java) { SavedCredentialsCodec.decode(input.toByteArray()) }
            assertFalse(failure.message.orEmpty().contains("secret"))
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }
}
