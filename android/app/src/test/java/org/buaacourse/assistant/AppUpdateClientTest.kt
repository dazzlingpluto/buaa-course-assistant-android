package org.buaacourse.assistant

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private val source = githubManifestUrl("example-owner/course-assistant")
    private val assetName = "buaa-course-assistant-v2.3.1.apk"
    private val bytes = "test download payload".toByteArray()
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val manifest = """
        {"versionCode":24020,"versionName":"2.3.1","apkUrl":"$assetName",
         "sizeBytes":${bytes.size},"sha256":"$sha256","notes":"Test release"}
    """.trimIndent()

    @Test fun resolvesVersionedGitHubApkEvenWhenManifestResponseUsesCdn() = runBlocking {
        val cdn = "https://release-assets.githubusercontent.com/demo/signed?token=example"
        val release = client(manifest.toByteArray(), finalUrl = cdn).check(source)
        assertEquals("https://github.com/example-owner/course-assistant/releases/download/v2.3.1/$assetName",
            release.apkUrl.toString())
        assertEquals(24020L, release.versionCode)
        assertTrue(runCatching {
            parseAppRelease(manifest.replace(assetName, "https://example.org/file.apk"), source)
        }.exceptionOrNull() is InvalidUpdate)
    }

    @Test fun privateRepositoryOrMissingAssetIsReportedAsUnavailable() = runBlocking {
        assertTrue(runCatching { client(bytes, code = 404).check(source) }
            .exceptionOrNull() is GitHubUpdateUnavailable)
    }

    @Test fun onlyPublishesDownloadAfterHashAndPackageVerification() = runBlocking {
        val release = parseAppRelease(manifest, source)
        val directory = temporary.newFolder()
        val progress = mutableListOf<Int>()
        val result = client(bytes).download(release, directory, verify = { staging ->
            assertFalse(File(directory, "update.apk").exists())
            assertTrue(staging.readBytes().contentEquals(bytes))
        }, onProgress = progress::add)
        assertTrue(result.readBytes().contentEquals(bytes))
        assertEquals(listOf(100), progress)
        assertEquals(listOf("update.apk"), directory.list()!!.toList())
    }

    @Test fun badHashAndRejectedPackageLeaveNoPartialDownload() = runBlocking {
        val release = parseAppRelease(manifest, source)
        val directory = temporary.newFolder()
        val tampered = bytes.copyOf().apply { this[0] = 0 }
        assertTrue(runCatching {
            client(tampered).download(release, directory, { error("APK should not be inspected") }, {})
        }.exceptionOrNull() is InvalidUpdate)
        assertTrue(runCatching {
            client(bytes).download(release, directory, { throw InvalidUpdate() }, {})
        }.exceptionOrNull() is InvalidUpdate)
        assertTrue(directory.list()!!.isEmpty())
    }

    private fun client(payload: ByteArray, finalUrl: String? = null, code: Int = 200) =
        AppUpdateClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val responseRequest = finalUrl?.let { request.newBuilder().url(it.toHttpUrl()).build() } ?: request
        Response.Builder().request(responseRequest).protocol(Protocol.HTTP_1_1)
            .code(code).message("Test response").body(payload.toResponseBody()).build()
    }.build())
}
