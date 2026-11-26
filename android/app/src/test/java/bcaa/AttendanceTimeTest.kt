package bcaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AttendanceTimeTest {
    @Test fun signingWindowEndsThirtyMinutesAfterTheSessionStarts() {
        val date = LocalDate.of(2026, 11, 26)
        val course = Course("session", "示例课", classBeginTime = "2026-11-26 08:50:00", classEndTime = "12:15")
        val window = checkNotNull(attendanceSignWindow(date, course))

        assertEquals(Instant.parse("2026-11-26T00:45:00Z"), window.start)
        assertEquals(Instant.parse("2026-11-26T01:20:00Z"), window.endInclusive)
        assertFalse(window.start.minusMillis(1) in window)
        assertTrue(window.start in window)
        assertTrue(window.endInclusive in window)
        assertFalse(window.endInclusive.plusMillis(1) in window)
        assertFalse(Instant.parse("2026-11-26T04:45:00Z") in window)
        assertEquals(window, attendanceSignWindow(date, course.copy(classEndTime = "09:00")))
    }

    @Test fun sessionDateIsRespectedWhenTheWindowCrossesMidnight() {
        val date = LocalDate.of(2026, 11, 26)
        val early = checkNotNull(attendanceSignWindow(date, Course("early", "凌晨课", classBeginTime = "00:02")))
        val late = checkNotNull(attendanceSignWindow(date, Course("late", "晚课", classBeginTime = "23:50")))

        assertEquals(Instant.parse("2026-11-25T15:57:00Z"), early.start)
        assertEquals(Instant.parse("2026-11-25T16:32:00Z"), early.endInclusive)
        assertEquals(Instant.parse("2026-11-26T16:20:00Z"), late.endInclusive)
        assertFalse(early.start.plusSeconds(24 * 60 * 60) in early)
    }

    @Test fun unconfirmedDateOrStartTimeDoesNotOpenSigning() {
        val date = LocalDate.of(2026, 11, 26)
        val course = Course("session", "示例课", classBeginTime = "08:00")
        assertNull(attendanceSignWindow(null, course))
        assertNull(attendanceSignWindow(date, course.copy(classBeginTime = "")))
        assertNull(attendanceSignWindow(date, course.copy(classBeginTime = "24:00")))
        assertNull(attendanceSignWindow(date, course.copy(classBeginTime = "第 1 节")))
    }
}
