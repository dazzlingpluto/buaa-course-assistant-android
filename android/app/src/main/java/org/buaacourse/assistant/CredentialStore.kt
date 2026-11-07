package org.buaacourse.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Never let data-class logging accidentally disclose a password. */
class SavedCredentials(
    val username: String,
    val password: String,
    val mode: NetworkMode,
    val studentType: StudentType = StudentType.AUTO,
    val autoLogin: Boolean = false,
) {
    override fun toString() = "SavedCredentials(redacted)"
}

interface SavedCredentialsStore {
    fun read(): SavedCredentials?
    fun save(credentials: SavedCredentials)
    fun clear()
}

/** Preserves the original alias, filename and AAD, including old three-field records. */
class CredentialStore(context: Context) : SavedCredentialsStore {
    private val directory = context.noBackupFilesDir
        ?: throw IllegalStateException("Private storage unavailable")
    private val base = File(directory, "saved-credentials.enc")
    private val file = AtomicFile(base)

    override fun read(): SavedCredentials? = synchronized(lock) {
        try {
            if (!base.exists() && !File("${base.path}.bak").exists()) {
                val unfinished = File("${base.path}.new")
                if (unfinished.exists()) {
                    deleteChecked(unfinished)
                    syncDirectory()
                }
                return@synchronized null
            }
            var plaintext: ByteArray? = null
            try {
                val record = file.openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > CredentialCipher.MAX_RECORD_BYTES) {
                            throw IOException("Saved record too large")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                plaintext = CredentialCipher.decrypt(key(false), record)
                SavedCredentialsCodec.decode(plaintext)
            } catch (_: Exception) {
                // Invalidated keys and corrupt data are removed before any future save.
                clearInternal()
                throw IOException("保存的账号密码已失效，请重新输入")
            } finally {
                plaintext?.fill(0)
            }
        } catch (_: Exception) {
            // Provider/parser messages and causes may contain confidential input.
            throw IOException("无法读取已保存的账号密码，请重新输入")
        }
    }

    override fun save(credentials: SavedCredentials) = synchronized(lock) {
        try {
            val plaintext = SavedCredentialsCodec.encode(credentials)
            val record = try { CredentialCipher.encrypt(key(true), plaintext) } finally { plaintext.fill(0) }
            var output: FileOutputStream? = null
            try {
                output = file.startWrite()
                output.write(record)
                output.fd.sync()
                file.finishWrite(output)
                output = null
                syncDirectory()
                if (!base.isFile || File("${base.path}.bak").exists() || File("${base.path}.new").exists()) {
                    throw IOException("Saved record commit failed")
                }
                base.inputStream().use { persisted ->
                    for (expected in record) {
                        if (persisted.read() != (expected.toInt() and 0xff)) throw IOException("Saved record mismatch")
                    }
                    if (persisted.read() != -1) throw IOException("Saved record mismatch")
                }
            } catch (error: Exception) {
                output?.let(file::failWrite)
                throw error
            }
        } catch (_: Exception) {
            throw IOException("未能保存账号密码，请重试")
        }
    }

    override fun clear() = synchronized(lock) {
        try { clearInternal() } catch (_: Exception) { throw IOException("未能清除已保存的账号密码，请重试") }
    }

    private fun clearInternal() {
        var keyError: Exception? = null
        try {
            val store = keyStore()
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
            if (store.containsAlias(KEY_ALIAS)) throw GeneralSecurityException("Saved key removal failed")
        } catch (error: Exception) { keyError = error }
        file.delete()
        deleteChecked(base)
        deleteChecked(File("${base.path}.bak"))
        deleteChecked(File("${base.path}.new"))
        syncDirectory()
        keyError?.let { throw it }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(create: Boolean): SecretKey {
        val existing = keyStore().getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        if (existing != null || !create) throw GeneralSecurityException("Saved key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun syncDirectory() {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun deleteChecked(target: File) {
        if (target.exists() && !target.delete()) throw IOException("Saved record removal failed")
        if (target.exists()) throw IOException("Saved record removal failed")
    }

    companion object {
        private const val KEY_ALIAS = "buaa.saved_credentials.v1"
        private val lock = Any()
    }
}
