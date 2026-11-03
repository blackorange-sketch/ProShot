package com.proshot.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Size
import android.view.Surface
import com.proshot.detail.Finisher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Owns the still-capture streams (JPEG and/or RAW) for one lens, fires a maximum-quality
 * capture and saves whatever comes back. All callbacks run on [handler]; saving runs on [io].
 */
class StillPipeline(
    private val ctx: Context,
    private val lens: Lens,
    wantJpeg: Boolean,
    rawRequested: Boolean,
    fullRes: Boolean,
    private val superSample: Boolean,
    private val handler: Handler,
    private val io: ExecutorService,
    private val onFinished: (message: String, ok: Boolean) -> Unit,
) : Shooter {
    private val maxRes = fullRes && lens.supportsMaxRes && Build.VERSION.SDK_INT >= 31

    override val jpegSize: Size? = when {
        !wantJpeg -> null
        maxRes -> lens.maxResJpegSize
        else -> lens.jpegSize
    }
    override val rawSize: Size? = when {
        !rawRequested || !lens.supportsRaw -> null
        maxRes -> lens.maxResRawSize
        else -> lens.rawSize
    }

    /** Decode, sharpen and re-encode the JPEG ourselves: from 50 MP downscaled 2x, or the standard frame. */
    private val processJpeg = (jpegSize?.let { it.width.toLong() * it.height <= 16_500_000L } == true && !maxRes) ||
        (superSample && maxRes && jpegSize != null)

    /** Capture mode, written into the file name so a photo can be traced back to the code path that made it. */
    private val modeTag: String = buildString {
        append(
            when {
                jpegSize == null -> "RAW"
                maxRes && superSample -> "SUPER"
                maxRes -> "FULL"
                processJpeg -> "DETAIL"
                else -> "STD"
            },
        )
        if (jpegSize != null && rawSize != null) append("_DNG")
    }

    private val depth = if (maxRes) 1 else 2
    private val jpegReader: ImageReader? = jpegSize?.let {
        ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, depth)
    }
    private val rawReader: ImageReader? = rawSize?.let {
        ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, depth)
    }

    private class Job(val base: String, val orientation: Int) {
        var jpeg: Image? = null
        var raw: Image? = null
        var result: TotalCaptureResult? = null
        var state = ManualState()
    }

    private var job: Job? = null

    @Volatile
    override var busy = false
        private set

    init {
        jpegReader?.setOnImageAvailableListener({ onImage(it, false) }, handler)
        rawReader?.setOnImageAvailableListener({ onImage(it, true) }, handler)
    }

    /** Output configurations to add to the camera session. */
    override fun describe(): String = listOfNotNull(
        jpegSize?.let { "JPEG ${it.width}x${it.height}" },
        rawSize?.let { "RAW ${it.width}x${it.height}" },
    ).joinToString(" + ")

    override fun outputConfigs(): List<OutputConfiguration> =
        listOfNotNull(jpegReader?.surface, rawReader?.surface).map { config(it) }

    private fun config(s: Surface): OutputConfiguration {
        val oc = OutputConfiguration(s)
        lens.physicalId?.let { oc.setPhysicalCameraId(it) }
        if (maxRes && Build.VERSION.SDK_INT >= 31) {
            oc.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        }
        return oc
    }

    private var afterCapture: (() -> Unit)? = null

    override fun capture(
        device: CameraDevice,
        session: CameraCaptureSession,
        state: ManualState,
        orientation: Int,
        onExposed: (() -> Unit)?,
    ) {
        if (busy) return
        afterCapture = onExposed
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        jpegReader?.let { b.addTarget(it.surface) }
        rawReader?.let { b.addTarget(it.surface) }
        QualityProfile.applyManual(b, lens.chars, state, true)
        QualityProfile.applyStill(b, lens.chars, LensProfiles.forLens(lens).stillNoiseReduction)
        if (maxRes && Build.VERSION.SDK_INT >= 31) {
            b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        }
        b.set(CaptureRequest.JPEG_ORIENTATION, orientation)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        job = Job("PS_${stamp}_${lens.equivMm}mm_${modeTag}_o$orientation", orientation).also { it.state = state }
        busy = true
        session.capture(b.build(), callback, handler)
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
        ) {
            afterCapture?.invoke()
            afterCapture = null
            job?.let { it.result = result; tryFinish(it) }
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            afterCapture?.invoke()
            afterCapture = null
            job?.let { discard(it) }
            job = null
            busy = false
            onFinished("Зйомка не вдалася (код ${failure.reason})", false)
        }
    }

    private fun onImage(reader: ImageReader, isRaw: Boolean) {
        val img = reader.acquireNextImage() ?: return
        val j = job
        if (j == null) {
            img.close()
            return
        }
        if (isRaw) j.raw = img else j.jpeg = img
        tryFinish(j)
    }

    private fun tryFinish(j: Job) {
        if (jpegReader != null && j.jpeg == null) return
        if (rawReader != null && j.raw == null) return
        val result = j.result ?: return
        job = null
        io.execute {
            try {
                val parts = mutableListOf<String>()
                val notes = mutableListOf<String>()
                j.jpeg?.let {
                    var processed = false
                    var diag = ""
                    if (processJpeg) {
                        val r = saveProcessed(it, j, result)
                        processed = r.first
                        diag = r.second
                    }
                    if (!processed) {
                        val buf = it.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val sw = if (maxRes) "ProShot FULL" else "ProShot"
                        val meta = ShotMeta.from(lens, result, j.state, sw, it.width, it.height)
                        val sv = PhotoSaver.saveJpegStamped(ctx, bytes, j.base, null, meta) // keep the HAL's own orientation
                        diag += (if (diag.isNotEmpty()) " >> " else "") + "камерний JPEG ${sv.note}"
                    }
                    notes += diag
                    parts += if (processed) (if (superSample) "JPEG SR" else "JPEG+") else "JPEG"
                }
                j.raw?.let { PhotoSaver.saveDng(ctx, lens.chars, result, it, j.orientation, j.base); parts += "DNG" }
                busy = false
                onFinished("Збережено ${parts.joinToString("+")}: ${j.base}" + (if (notes.isEmpty()) "" else " [${notes.joinToString("; ")}]"), true)
            } catch (t: Throwable) {
                busy = false
                onFinished("Помилка збереження: ${t.message}", false)
            } finally {
                discard(j)
            }
        }
    }

    /**
     * Decodes the camera JPEG (at half size when [superSample], which averages 2x2 pixels of the 50 MP
     * frame), runs the detail library's finishing pass and saves the result. Returns false (plus a diagnostic text) on any
     * failure so the caller can save the untouched camera JPEG instead.
     */
    private fun saveProcessed(img: Image, j: Job, result: TotalCaptureResult): Pair<Boolean, String> = try {
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = if (superSample) 2 else 1
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("decode failed")
        val bmpW = bmp.width
        val frame = YuvConvert.fromBitmap(bmp)
        bmp.recycle()
        val (post, optics) = LensProfiles.postFor(lens, frame.width, frame.height)
        val finished = Finisher.run(frame, post)
        // This HAL already rotates the JPEG pixels itself (tag = 1, portrait dimensions), others leave the
        // pixels as captured and write an orientation tag. Whatever the tag still asks for is baked in here.
        val halTag = ExifInterface(ByteArrayInputStream(bytes))
        val deg = if (halTag.getAttribute(ExifInterface.TAG_ORIENTATION) == null) {
            if (bmpW == img.width) j.orientation else 0
        } else {
            halTag.rotationDegrees
        }
        val upright = YuvConvert.rotate(finished, deg)
        val jpeg = YuvConvert.toJpeg(upright, 100)
        val sw = (if (superSample) "ProShot SUPER 50>12MP" else "ProShot DETAIL") + (if (optics) " + optics" else "")
        val meta = ShotMeta.from(lens, result, j.state, sw, upright.width, upright.height)
        val sv = PhotoSaver.saveJpegStamped(ctx, jpeg, j.base, 0, meta)
        (sv.uri != null) to "px ${upright.width}x${upright.height} rot$deg ${sv.note}"
    } catch (t: Throwable) {
        false to "обробка не вдалася: ${t::class.java.simpleName} ${t.message}"
    }

    private fun discard(j: Job) {
        runCatching { j.jpeg?.close() }
        runCatching { j.raw?.close() }
        j.jpeg = null
        j.raw = null
    }

    override fun close() {
        job?.let { discard(it) }
        job = null
        runCatching { jpegReader?.close() }
        runCatching { rawReader?.close() }
    }
}
