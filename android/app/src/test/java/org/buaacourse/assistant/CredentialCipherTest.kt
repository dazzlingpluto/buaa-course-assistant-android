package org.buaacourse.assistant

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class CredentialCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test fun decryptsIndependentLegacyV1FixtureWithPasswordWhitespace() {
        // Generated independently with AES-GCM, the original v1 AAD and fixed IV 00..0b.
        val fixture = Base64.getDecoder().decode("AQABAgMEBQYHCAkKCzwgo2igl6x64CS1sZONHQDs9KsWgBosD08Il+E/UyKSdXXdiI+xc+sH0xCf7KcKFMw0D+k/9Jn4SedEO2WaKHAXoSkmV/LZBicDTAaZ")
        assertEquals("{\"username\":\"demo\",\"password\":\" test password \",\"mode\":\"vpn\"}",
            CredentialCipher.decrypt(key, fixture).toString(Charsets.UTF_8))
    }

    @Test fun freshEncryptionUsesDifferentIvAndAuthenticatesBothRecords() {
        val plain = "用户名：演示；密码前后空格保留 ".toByteArray()
        val a = CredentialCipher.encrypt(key, plain)
        val b = CredentialCipher.encrypt(key, plain)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(plain, CredentialCipher.decrypt(key, a))
        assertArrayEquals(plain, CredentialCipher.decrypt(key, b))
    }

    @Test fun rejectsTamperedCiphertextAndUnknownVersion() {
        val record = CredentialCipher.encrypt(key, "demo".toByteArray())
        val changed = record.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { CredentialCipher.decrypt(key, changed) }
        val unknown = record.copyOf().also { it[0] = 2 }
        assertThrows(GeneralSecurityException::class.java) { CredentialCipher.decrypt(key, unknown) }
    }

    @Test fun rejectsWrongKeyAndInvalidRecordSizes() {
        val record = CredentialCipher.encrypt(key, "demo".toByteArray())
        val wrong = SecretKeySpec(ByteArray(32) { 0x7f }, "AES")
        assertThrows(GeneralSecurityException::class.java) { CredentialCipher.decrypt(wrong, record) }
        assertThrows(GeneralSecurityException::class.java) { CredentialCipher.decrypt(key, ByteArray(0)) }
        assertThrows(GeneralSecurityException::class.java) { CredentialCipher.decrypt(key, ByteArray(65537)) }
        assertThrows(GeneralSecurityException::class.java) { CredentialCipher.encrypt(key, ByteArray(0)) }
        assertThrows(GeneralSecurityException::class.java) { CredentialCipher.encrypt(key, ByteArray(65536)) }
    }
}
