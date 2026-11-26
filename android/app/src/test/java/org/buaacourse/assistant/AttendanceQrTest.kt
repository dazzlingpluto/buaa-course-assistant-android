package org.buaacourse.assistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AttendanceQrTest {
    @Test fun scanUrlUsesOnlyTheEncodedScheduleIdAndCurrentEpochMilliseconds() {
        val id = "class +01&A/2"
        val timestamp = Instant.parse("2026-11-26T00:05:00.123Z")
        val url = IclassProtocol.attendanceQrUrl(id, timestamp.toEpochMilli()).toHttpUrl()

        assertEquals("http", url.scheme)
        assertEquals("iclass.buaa.edu.cn", url.host)
        assertEquals(8081, url.port)
        assertEquals("/eschool/app/course/stu_scan_sign.action", url.encodedPath)
        assertEquals(setOf("courseSchedId", "timestamp"), url.queryParameterNames)
        assertEquals(id, url.queryParameter("courseSchedId"))
        assertEquals(timestamp.toEpochMilli().toString(), url.queryParameter("timestamp"))
    }

    @Test fun qrWindowUsesTheSpecificSessionDateAndBeijingTime() {
        val date = LocalDate.of(2026, 11, 26)
        val course = Course("session-two", "示例课", classBeginTime = "2026-11-26 08:50:00", classEndTime = "09:35")
        val window = checkNotNull(attendanceQrWindow(date, course))

        assertEquals(Instant.parse("2026-11-26T00:40:00Z"), window.start)
        assertEquals(Instant.parse("2026-11-26T01:35:00Z"), window.endExclusive)
        assertFalse(window.start.minusMillis(1) in window)
        assertTrue(window.start in window)
        assertTrue(window.endExclusive.minusMillis(1) in window)
        assertFalse(window.endExclusive in window)
        assertNull(attendanceQrWindow(date, course.copy(classEndTime = "")))
        assertNull(attendanceQrWindow(date, course.copy(classEndTime = "08:30")))
    }
}
