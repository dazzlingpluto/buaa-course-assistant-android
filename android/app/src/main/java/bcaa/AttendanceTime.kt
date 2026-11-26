package bcaa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal val LocalAttendanceClock = staticCompositionLocalOf { Clock.systemUTC() }

internal fun attendanceSignWindow(date: LocalDate?, course: Course): ClosedRange<Instant>? {
    if (date == null) return null
    val time = TimetableLayout.time(course.classBeginTime)?.let(LocalTime::parse) ?: return null
    val start = date.atTime(time).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
    return start.minusSeconds(5 * 60)..start.plusSeconds(30 * 60)
}

@Composable
internal fun rememberAttendanceTime(refreshMillis: Long = 1_000L): State<Instant> {
    val clock = LocalAttendanceClock.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(clock.instant(), clock, lifecycle, refreshMillis) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = clock.instant()
                delay(refreshMillis)
            }
        }
    }
}
