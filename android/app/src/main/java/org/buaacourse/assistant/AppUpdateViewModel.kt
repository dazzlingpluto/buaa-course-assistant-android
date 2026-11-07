package org.buaacourse.assistant

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException

internal enum class UpdateStatus { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, UNAVAILABLE, INVALID, FAILED }

internal data class AppUpdateState(
    val visible: Boolean = false,
    val status: UpdateStatus = UpdateStatus.IDLE,
    val release: AppRelease? = null,
    val progress: Int = 0,
    val apk: File? = null,
) {
    val busy: Boolean get() = status == UpdateStatus.CHECKING || status == UpdateStatus.DOWNLOADING
}

internal class AppUpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val client = AppUpdateClient()
    private val repository = BuildConfig.UPDATE_REPOSITORY
    private val source = githubManifestUrl(repository)
    private val lastCheck = "last_github_check:$repository"
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()
    private val installed = app.packageManager.getPackageInfo(app.packageName, 0)
    private val versionCode = PackageInfoCompat.getLongVersionCode(installed)
    val versionName = installed.versionName.orEmpty()

    init {
        if (prefs.contains("source")) prefs.edit {
            remove("source")
            remove("last_check")
        }
    }

    fun open() { _state.update { it.copy(visible = true) } }

    fun close() { _state.update { it.copy(visible = false) } }

    fun checkOnResume() {
        if (_state.value.busy) return
        val elapsed = System.currentTimeMillis() - prefs.getLong(lastCheck, 0L)
        if (elapsed !in 0 until 24 * 60 * 60 * 1000L) check(manual = false)
    }

    fun checkManually() = check(manual = true)

    private fun check(manual: Boolean) {
        val current = _state.value
        if (current.busy) return
        prefs.edit { putLong(lastCheck, System.currentTimeMillis()) }
        _state.value = AppUpdateState(visible = current.visible, status = UpdateStatus.CHECKING)
        viewModelScope.launch {
            try {
                val release = client.check(source).takeIf { it.versionCode > versionCode }
                _state.update { state ->
                    state.copy(
                        status = if (release == null) UpdateStatus.CURRENT else UpdateStatus.AVAILABLE,
                        visible = state.visible || (!manual && release != null),
                        release = release,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = errorStatus(e)) }
            }
        }
    }

    fun download() {
        val current = _state.value
        val release = current.release ?: return
        if (current.busy || current.apk != null) return
        _state.value = current.copy(status = UpdateStatus.DOWNLOADING, progress = 0)
        viewModelScope.launch {
            try {
                val apk = client.download(
                    release, File(app.cacheDir, "updates"),
                    verify = { verifyUpdatePackage(app, it, release) },
                    onProgress = { percent -> _state.update { it.copy(progress = percent) } },
                )
                _state.update { it.copy(status = UpdateStatus.READY, apk = apk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = errorStatus(e)) }
            }
        }
    }

    fun installFailed() {
        _state.update {
            it.copy(status = UpdateStatus.FAILED, apk = it.apk?.takeIf(File::isFile))
        }
    }

    private fun errorStatus(error: Exception): UpdateStatus = when (error) {
        is GitHubUpdateUnavailable -> UpdateStatus.UNAVAILABLE
        is InvalidUpdate, is JSONException -> UpdateStatus.INVALID
        else -> UpdateStatus.FAILED
    }
}
