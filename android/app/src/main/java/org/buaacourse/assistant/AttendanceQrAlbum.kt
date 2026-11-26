package org.buaacourse.assistant

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

private const val albumName = "BCAA"
private const val pngType = "image/png"

internal suspend fun saveAttendanceQrToAlbum(context: Context, scheduleId: String, timestamp: Instant) {
    withContext(Dispatchers.IO) {
        val bitmap = renderAttendanceQrBitmap(encodeAttendanceQr(scheduleId, timestamp))
        val filename = "BCAA-QR-${UUID.randomUUID()}.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStorePhoto(context, bitmap, filename)
            else saveLegacyPhoto(context, bitmap, filename)
        } finally {
            bitmap.recycle()
        }
    }
}

internal fun renderAttendanceQrBitmap(qr: QrCode): Bitmap {
    val modules = qr.size + attendanceQrQuietZoneModules * 2
    val scale = 840 / modules
    val margin = attendanceQrQuietZoneModules * scale
    val side = modules * scale
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint = Paint().apply { color = Color.BLACK }
    for (y in 0 until qr.size) {
        for (x in 0 until qr.size) {
            if (qr.getModule(x, y)) {
                val left = (margin + x * scale).toFloat()
                val top = (margin + y * scale).toFloat()
                canvas.drawRect(left, top, left + scale, top + scale, paint)
            }
        }
    }
    return bitmap
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun saveMediaStorePhoto(context: Context, bitmap: Bitmap, filename: String) {
    val resolver = context.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, pngType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$albumName")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val image = resolver.insert(collection, values) ?: throw IOException("Could not create photo entry")
    try {
        val stream = resolver.openOutputStream(image) ?: throw IOException("Could not open photo entry")
        stream.use { bitmap.writePng(it) }
        val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        if (resolver.update(image, ready, null, null) != 1) throw IOException("Could not publish photo entry")
    } catch (error: Exception) {
        runCatching { resolver.delete(image, null, null) }
        throw error
    }
}

@Suppress("DEPRECATION")
private fun saveLegacyPhoto(context: Context, bitmap: Bitmap, filename: String) {
    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), albumName)
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Could not create the photo directory")
    }
    val image = File(directory, filename)
    if (!image.createNewFile()) throw IOException("Photo already exists")
    try {
        image.outputStream().use { bitmap.writePng(it) }
        MediaScannerConnection.scanFile(context, arrayOf(image.absolutePath), arrayOf(pngType), null)
    } catch (error: Exception) {
        image.delete()
        throw error
    }
}

private fun Bitmap.writePng(output: OutputStream) {
    if (!compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Could not encode the QR image")
}
