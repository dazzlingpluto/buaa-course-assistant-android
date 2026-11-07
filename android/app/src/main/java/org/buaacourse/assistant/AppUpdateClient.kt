package org.buaacourse.assistant

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.json.JSONObject

internal const val MAX_UPDATE_BYTES = 100L * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 64L * 1024

internal data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: HttpUrl,
    val sizeBytes: Long,
    val sha256: String,
    val notes: String,
)

internal class InvalidUpdate : IOException()
internal class GitHubUpdateUnavailable : IOException()

internal fun githubManifestUrl(repository: String): HttpUrl =
    "https://github.com/$repository/releases/latest/download/update.json".toHttpUrl()

internal fun parseAppRelease(json: String, manifestUrl: HttpUrl): AppRelease {
    val manifest = JSONObject(json)
    val code = manifest.get("versionCode").toString().toLongOrNull()
    val size = manifest.get("sizeBytes").toString().toLongOrNull()
    val name = manifest.getString("versionName").trim()
    val hash = manifest.getString("sha256").lowercase()
    val asset = manifest.getString("apkUrl")
    if (code == null || code !in 1..2_100_000_000L || size == null || size !in 1..MAX_UPDATE_BYTES ||
        !name.matches(Regex("[0-9]{1,10}\\.[0-9]{1,10}\\.[0-9]{1,10}")) ||
        !hash.matches(Regex("[0-9a-f]{64}")) || asset != "buaa-course-assistant-v$name.apk"
    ) throw InvalidUpdate()
    // Use the versioned asset on GitHub. The manifest response may come from a signed CDN URL.
    val apkUrl = manifestUrl.resolve("../../download/v$name/$asset") ?: throw InvalidUpdate()
    return AppRelease(code, name, apkUrl, size, hash, manifest.optString("notes").take(4000))
}

internal class AppUpdateClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followSslRedirects(false)
        .callTimeout(4, TimeUnit.MINUTES)
        .build(),
) {
    suspend fun check(url: HttpUrl): AppRelease = request(url, timeoutSeconds = 15) { response ->
        val json = response.peekBody(MAX_MANIFEST_BYTES + 1)
        if (json.contentLength() > MAX_MANIFEST_BYTES) throw InvalidUpdate()
        parseAppRelease(json.string(), url)
    }

    suspend fun download(
        release: AppRelease,
        directory: File,
        verify: (File) -> Unit,
        onProgress: (Int) -> Unit,
    ): File = request(release.apkUrl) { response ->
        val body = response.body ?: throw IOException("Missing APK response")
        val contentLength = body.contentLength()
        if (contentLength >= 0 && contentLength != release.sizeBytes) throw InvalidUpdate()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache")
        val staging = File.createTempFile("download-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > release.sizeBytes) throw InvalidUpdate()
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = (received * 100 / release.sizeBytes).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (received != release.sizeBytes || hash != release.sha256) throw InvalidUpdate()
            verify(staging)
            val ready = File(directory, "update.apk")
            // Replace the previous download only after the new package passes verification.
            FileSystem.SYSTEM.atomicMove(staging.toOkioPath(), ready.toOkioPath())
            ready
        } finally {
            staging.delete()
        }
    }

    private suspend fun <T> request(
        url: HttpUrl,
        timeoutSeconds: Long? = null,
        read: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        timeoutSeconds?.let { call.timeout().timeout(it, TimeUnit.SECONDS) }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        if (it.code == 404) throw GitHubUpdateUnavailable()
                        if (!it.isSuccessful || !it.request.url.isHttps) throw IOException("Update server response")
                        read(it)
                    }
                })
            }
        })
    }
}

@Suppress("DEPRECATION")
internal fun verifyUpdatePackage(context: Context, apk: File, release: AppRelease) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    val installed = pm.getPackageInfo(context.packageName, flags)
    val incoming = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw InvalidUpdate()
    fun PackageInfo.signers(): Set<Signature> = if (Build.VERSION.SDK_INT >= 28) {
        signingInfo?.apkContentsSigners.orEmpty().toSet()
    } else {
        signatures.orEmpty().toSet()
    }
    val installedSigners = installed.signers()
    if (incoming.packageName != context.packageName ||
        PackageInfoCompat.getLongVersionCode(incoming) != release.versionCode ||
        release.versionCode <= PackageInfoCompat.getLongVersionCode(installed) ||
        installedSigners.isEmpty() || incoming.signers() != installedSigners
    ) throw InvalidUpdate()
}
