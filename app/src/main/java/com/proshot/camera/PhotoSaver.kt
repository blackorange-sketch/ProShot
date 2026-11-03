package com.proshot.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.OutputStream

/** Writes captured frames into DCIM/ProShot through MediaStore (no storage permission needed). */
object PhotoSaver {

    private const val DIR = "DCIM/ProShot"

    fun saveJpeg(ctx: Context, image: Image, baseName: String): Uri? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return write(ctx, "$baseName.jpg", "image/jpeg") { it.write(bytes) }
    }

    /** Saves an already encoded JPEG and stamps the EXIF orientation afterwards. */
    fun saveJpegBytes(ctx: Context, bytes: ByteArray, baseName: String, jpegOrientation: Int): Uri? {
        val uri = write(ctx, "$baseName.jpg", "image/jpeg") { it.write(bytes) } ?: return null
        runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val ex = ExifInterface(pfd.fileDescriptor)
                ex.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(jpegOrientation).toString())
                ex.saveAttributes()
            }
        }
        return uri
    }

    fun saveDng(
        ctx: Context,
        chars: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        jpegOrientation: Int,
        baseName: String,
    ): Uri? {
        val creator = DngCreator(chars, result)
        creator.setOrientation(exifOrientation(jpegOrientation))
        return try {
            write(ctx, "$baseName.dng", "image/x-adobe-dng") { creator.writeImage(it, image) }
        } finally {
            creator.close()
        }
    }

    private fun exifOrientation(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun write(ctx: Context, name: String, mime: String, body: (OutputStream) -> Unit): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { body(it) } ?: error("no output stream")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
