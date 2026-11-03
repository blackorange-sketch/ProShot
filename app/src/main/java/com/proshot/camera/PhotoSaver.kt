package com.proshot.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    fun saveJpegBytes(
        ctx: Context, bytes: ByteArray, baseName: String, jpegOrientation: Int, meta: ShotMeta? = null,
    ): Uri? {
        val uri = write(ctx, "$baseName.jpg", "image/jpeg") { it.write(bytes) } ?: return null
        stampExif(ctx, uri, meta, jpegOrientation)
        return uri
    }

    /** Writes orientation and shooting data (device, lens, ISO, shutter, aperture, focal length) into the JPEG. */
    fun stampExif(ctx: Context, uri: Uri, meta: ShotMeta?, jpegOrientation: Int? = null) {
        runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val ex = ExifInterface(pfd.fileDescriptor)
                if (jpegOrientation != null) {
                    ex.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(jpegOrientation).toString())
                }
                if (meta != null) {
                    val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                    ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                    ex.setAttribute(ExifInterface.TAG_MODEL, if (Build.MODEL == "NX713J") "nubia Z50S Pro" else Build.MODEL)
                    ex.setAttribute(ExifInterface.TAG_SOFTWARE, meta.software)
                    ex.setAttribute(ExifInterface.TAG_DATETIME, now)
                    ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                    ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, now)
                    meta.exposureNs?.let {
                        ex.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString())
                    }
                    meta.iso?.let { ex.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
                    meta.aperture?.let { ex.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
                    if (meta.focalMm > 0f) ex.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, meta.focalMm.toString())
                    if (meta.lens.equivMm > 0) {
                        ex.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, meta.lens.equivMm.toString())
                    }
                    ex.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, meta.evComp.toString())
                    ex.setAttribute(ExifInterface.TAG_FLASH, "0")
                    ex.setAttribute(ExifInterface.TAG_WHITE_BALANCE, if (meta.wbAuto) "0" else "1")
                    ex.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, "2")
                    ex.setAttribute(ExifInterface.TAG_LENS_MAKE, Build.MANUFACTURER)
                    ex.setAttribute(
                        ExifInterface.TAG_LENS_MODEL,
                        listOf(meta.lens.label, meta.lens.sensor).filter { it.isNotEmpty() }.joinToString(" "),
                    )
                    meta.width?.let { ex.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, it.toString()) }
                    meta.height?.let { ex.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, it.toString()) }
                }
                ex.saveAttributes()
            }
        }
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
