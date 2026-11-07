package org.buaacourse.assistant

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Byte-compatible with v1.0.1: version || 12-byte IV || GCM ciphertext/tag. */
internal object CredentialCipher {
    const val MAX_RECORD_BYTES = 65536
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val VERSION: Byte = 1
    private val context = "org.buaacourse.assistant:saved-credentials:v1".toByteArray(Charsets.UTF_8)

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        if (plaintext.isEmpty() || plaintext.size > MAX_RECORD_BYTES - 1 - IV_BYTES - TAG_BITS / 8) {
            throw GeneralSecurityException("Invalid credential record size")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(context)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("Invalid credential IV")
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: SecretKey, record: ByteArray): ByteArray {
        if (record.size <= 1 + IV_BYTES + TAG_BITS / 8 || record.size > MAX_RECORD_BYTES || record[0] != VERSION) {
            throw GeneralSecurityException("Invalid credential record")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, record.copyOfRange(1, 1 + IV_BYTES)))
        cipher.updateAAD(context)
        return cipher.doFinal(record, 1 + IV_BYTES, record.size - 1 - IV_BYTES)
    }
}
