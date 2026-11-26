package bcaa

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val campusZone = ZoneId.of("Asia/Shanghai")
internal const val attendanceQrQuietZoneModules = 4
private const val qrRefreshMillis = 2_000L
private const val savedQrOffsetMillis = 2_000L

internal fun attendanceQrWindow(date: LocalDate, course: Course): OpenEndRange<Instant>? {
    val start = TimetableLayout.time(course.classBeginTime)?.let(LocalTime::parse) ?: return null
    val end = TimetableLayout.time(course.classEndTime)?.let(LocalTime::parse) ?: return null
    if (end <= start) return null
    val opens = date.atTime(start).atZone(campusZone).minusMinutes(10).toInstant()
    val closes = date.atTime(end).atZone(campusZone).toInstant()
    return opens..<closes
}

internal fun encodeAttendanceQr(scheduleId: String, time: Instant): QrCode = QrCode.encodeText(
    IclassProtocol.attendanceQrUrl(scheduleId, time.toEpochMilli()), QrCode.Ecc.LOW,
)

@Composable
internal fun AttendanceQrButton(
    course: Course,
    date: LocalDate?,
    enabled: Boolean,
    words: UiWords,
    loadTimestamp: suspend () -> Instant,
) {
    if (date == null || !course.attendanceAvailable || course.attendanceNotRequired) return
    val scheduleIds = course.realIds
    if (scheduleIds.isEmpty()) return
    val window = remember(date, course.classBeginTime, course.classEndTime) { attendanceQrWindow(date, course) } ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = Instant.now()
                delay(qrRefreshMillis)
            }
        }
    }
    val available = now in window
    var open by remember(date, scheduleIds, available) { mutableStateOf(false) }

    OutlinedButton(
        onClick = { open = Instant.now() in window },
        enabled = enabled && available,
        modifier = Modifier.fillMaxWidth().testTag("attendance_qr_${course.id}"),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(if (available) words.text("签到二维码", "Attendance QR code")
            else words.text("签到二维码 · 课前 10 分钟至下课", "Attendance QR · 10 min before class until it ends"))
    }
    if (open) AttendanceQrDialog(course.courseName, scheduleIds, words, loadTimestamp) { open = false }
}

@Composable
internal fun AttendanceQrDialog(
    courseName: String,
    scheduleIds: List<String>,
    words: UiWords,
    loadTimestamp: suspend () -> Instant,
    dismiss: () -> Unit,
) {
    var index by remember(scheduleIds) { mutableIntStateOf(0) }
    val scheduleId = scheduleIds[index]
    val fetch by rememberUpdatedState(loadTimestamp)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var liveTimestamp by remember { mutableStateOf<Instant?>(null) }
    var refreshFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refreshFailed = false
            try {
                while (true) {
                    liveTimestamp = fetch()
                    delay(qrRefreshMillis)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                refreshFailed = true
            } finally {
                liveTimestamp = null
            }
        }
    }
    val qr = remember(scheduleId, liveTimestamp) {
        try {
            liveTimestamp?.let { encodeAttendanceQr(scheduleId, it) }
        } catch (_: DataTooLongException) {
            null
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var pendingPermissionId by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember(scheduleId) { mutableStateOf<String?>(null) }
    fun save(id: String) {
        if (saving) return
        saving = true
        saveMessage = null
        scope.launch {
            try {
                val timestamp = fetch().plusMillis(savedQrOffsetMillis)
                saveAttendanceQrToAlbum(context.applicationContext, id, timestamp)
                saveMessage = words.text("已保存到相册，请尽快扫码", "Saved to photos. Scan promptly.")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                saveMessage = words.text("保存失败，请稍后重试", "Couldn't save the QR code. Try again.")
            } finally {
                saving = false
            }
        }
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = pendingPermissionId
        pendingPermissionId = null
        if (id != null) {
            if (granted) save(id)
            else saveMessage = words.text("请允许存储权限后保存", "Allow storage access to save to photos.")
        }
    }

    val description = words.text("签到二维码", "Attendance QR code")
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(courseName) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (qr != null) {
                    Canvas(Modifier.size(220.dp).semantics { contentDescription = description }) {
                        val width = qr.size + attendanceQrQuietZoneModules * 2
                        val moduleSize = (minOf(size.width, size.height) / width).toInt().toFloat()
                        val left = ((size.width - qr.size * moduleSize) / 2).toInt().toFloat()
                        val top = ((size.height - qr.size * moduleSize) / 2).toInt().toFloat()
                        drawRect(Color.White)
                        for (y in 0 until qr.size) {
                            for (x in 0 until qr.size) {
                                if (qr.getModule(x, y)) drawRect(Color.Black, Offset(left + x * moduleSize, top + y * moduleSize),
                                    Size(moduleSize, moduleSize))
                            }
                        }
                    }
                    Text(words.text("使用智慧教室扫码", "Scan with Smart Classroom"))
                } else if (refreshFailed) {
                    Text(words.text("无法获取 iClass 服务器时间", "Couldn't get iClass server time."),
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text(words.text("重试", "Retry")) }
                } else if (liveTimestamp == null) {
                    Text(words.text("正在获取服务器时间…", "Getting server time…"))
                } else {
                    Text(words.text("该排课无法生成二维码", "Could not generate a QR code for this session"))
                }
                if (scheduleIds.size > 1) {
                    TextButton(onClick = { index = (index + 1) % scheduleIds.size },
                        enabled = !saving && pendingPermissionId == null) {
                        Text(words.text("切换排课（${index + 1}/${scheduleIds.size}）", "Switch session (${index + 1}/${scheduleIds.size})"))
                    }
                }
                saveMessage?.let { Text(it) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingPermissionId = scheduleId
                        requestPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else save(scheduleId)
                },
                enabled = qr != null && !saving && pendingPermissionId == null,
                modifier = Modifier.testTag("save_attendance_qr"),
            ) {
                Text(if (saving) words.text("正在保存…", "Saving…") else words.text("保存到相册", "Save to photos"))
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(words.text("关闭", "Close")) } },
    )
}
