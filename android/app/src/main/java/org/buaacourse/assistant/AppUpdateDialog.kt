package org.buaacourse.assistant

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun AppUpdateDialog(updates: AppUpdateViewModel, words: UiWords) {
    val state by updates.state.collectAsStateWithLifecycle()
    if (!state.visible) return

    val context = LocalContext.current
    var needsPermission by rememberSaveable { mutableStateOf(false) }
    val release = state.release
    val apk = state.apk
    val action = when {
        apk != null -> words.text("安装更新", "Install update")
        release != null -> words.text("下载更新", "Download update")
        else -> words.text("检查更新", "Check for updates")
    }
    AlertDialog(
        onDismissRequest = updates::close,
        title = { Text(words.text("应用更新", "App updates")) },
        text = {
            Column(
                Modifier.heightIn(max = 370.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(words.text("当前版本 ${updates.versionName}", "Installed version ${updates.versionName}"))
                Text(words.text("更新来源 · GitHub Releases", "Update source · GitHub Releases"))
                release?.let {
                    Text(words.text("发现新版本 ${it.versionName}", "New version ${it.versionName}"))
                    if (it.notes.isNotBlank()) Text(it.notes)
                }
                if (state.status == UpdateStatus.DOWNLOADING) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                val message = when (state.status) {
                    UpdateStatus.IDLE -> ""
                    UpdateStatus.CHECKING -> words.text("正在检查更新…", "Checking for updates…")
                    UpdateStatus.CURRENT -> words.text("当前已是最新版。", "You have the latest version.")
                    UpdateStatus.AVAILABLE -> ""
                    UpdateStatus.DOWNLOADING -> words.text("正在下载 ${state.progress}%", "Downloading ${state.progress}%")
                    UpdateStatus.READY -> words.text("下载和验证完成，请确认安装。", "Verified and ready to install.")
                    UpdateStatus.UNAVAILABLE -> words.text("GitHub 上暂未找到公开的更新文件。", "No public update file was found on GitHub.")
                    UpdateStatus.INVALID -> words.text("更新清单或安装包验证失败，请联系发布者。", "The manifest or APK could not be verified.")
                    UpdateStatus.FAILED -> words.text("更新操作失败，请检查网络或系统设置后重试。", "Update failed. Check your network or system settings and try again.")
                }
                if (message.isNotBlank()) Text(message)
                if (needsPermission && apk != null) {
                    Text(words.text("允许此应用安装未知应用后，返回这里再点「安装更新」。",
                        "Allow installs from this app, then return and tap Install update again."))
                }
                if (!state.busy && release != null) {
                    TextButton(onClick = { needsPermission = false; updates.checkManually() }) {
                        Text(words.text("重新检查", "Check again"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy,
                onClick = {
                    when {
                        apk != null -> try {
                            if (!apk.isFile) {
                                updates.installFailed()
                            } else if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                                needsPermission = true
                                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                            } else {
                                needsPermission = false
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }
                        } catch (_: RuntimeException) {
                            updates.installFailed()
                        }
                        release != null -> updates.download()
                        else -> updates.checkManually()
                    }
                },
            ) { Text(action) }
        },
        dismissButton = { TextButton(onClick = updates::close) { Text(words.text("稍后", "Later")) } },
    )
}
