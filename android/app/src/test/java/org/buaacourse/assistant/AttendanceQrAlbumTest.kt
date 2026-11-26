package org.buaacourse.assistant

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AttendanceQrAlbumTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun savesSelectedSessionWithNewServerTimeAndPublishesPng() {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val image = ContentUris.withAppendedId(collection, 41)
        val gallery = GalleryProvider(image)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, gallery)
        val output = ByteArrayOutputStream()
        shadowOf(compose.activity.applicationContext.contentResolver).registerOutputStream(image, output)
        val calls = AtomicInteger()
        val liveTime = Instant.parse("2026-11-26T00:05:00.123Z")
        val saveTime = liveTime.plusMillis(753)

        compose.setContent {
            AssistantTheme {
                AttendanceQrDialog("示例课", listOf("slot-one", "slot-two"), UiWords(AppLanguage.ZH),
                    loadTimestamp = { if (calls.incrementAndGet() == 1) liveTime else saveTime }, dismiss = {})
            }
        }
        compose.onNodeWithContentDescription("签到二维码").assertIsDisplayed()
        compose.onNodeWithText("切换排课（1/2）").performClick()
        assertEquals(1, calls.get())
        compose.onNodeWithTag("save_attendance_qr").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已保存到相册，请尽快扫码").fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(calls.get() >= 2)
        val inserted = gallery.inserted
        assertEquals("image/png", inserted.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/BCAA", inserted.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, inserted.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
        assertEquals(0, gallery.updated.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
        assertEquals(0, gallery.deletions)

        val bytes = output.toByteArray()
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val qr = encodeAttendanceQr("slot-two", saveTime.plusSeconds(2))
            val quietZone = attendanceQrQuietZoneModules
            val side = qr.size + quietZone * 2
            val scale = bitmap.width / side
            assertEquals(bitmap.width, bitmap.height)
            assertEquals(bitmap.width, side * scale)
            for (y in 0 until side) for (x in 0 until side) {
                val expected = if (qr.getModule(x - quietZone, y - quietZone)) Color.BLACK else Color.WHITE
                assertEquals(expected, bitmap.getPixel(x * scale + scale / 2, y * scale + scale / 2))
            }
        } finally { bitmap.recycle() }
    }

    private class GalleryProvider(private val image: Uri) : ContentProvider() {
        lateinit var inserted: ContentValues
        lateinit var updated: ContentValues
        var deletions = 0

        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                           sortOrder: String?): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(checkNotNull(values))
            return image
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            check(uri == image)
            updated = ContentValues(checkNotNull(values))
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = ++deletions
    }
}
