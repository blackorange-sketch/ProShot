package com.proshot.camera

import android.content.Context
import android.graphics.ImageFormat
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
    private val handler: Handler,
    private val io: ExecutorService,
    private val onFinished: (message: String, ok: Boolean) -> Unit,
) {
    private val maxRes = fullRes && lens.supportsMaxRes && Build.VERSION.SDK_INT >= 31

    val jpegSize: Size? = when {
        !wantJpeg -> null
        maxRes -> lens.maxResJpegSize
        else -> lens.jpegSize
    }
    val rawSize: Size? = when {
        !rawRequested || !lens.supportsRaw -> null
        maxRes -> lens.maxResRawSize
        else -> lens.rawSize
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
    }

    private var job: Job? = null

    @Volatile
    var busy = false
        private set

    init {
        jpegReader?.setOnImageAvailableListener({ onImage(it, false) }, handler)
        rawReader?.setOnImageAvailableListener({ onImage(it, true) }, handler)
    }

    /** Output configurations to add to the camera session. */
    fun outputConfigs(): List<OutputConfiguration> =
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

    /** [onExposed] runs once the sensor frame is done (before saving), e.g. to release an AF lock. */
    fun capture(
        device: CameraDevice,
        session: CameraCaptureSession,
        state: ManualState,
        orientation: Int,
        onExposed: (() -> Unit)? = null,
    ) {
        if (busy) return
        afterCapture = onExposed
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        jpegReader?.let { b.addTarget(it.surface) }
        rawReader?.let { b.addTarget(it.surface) }
        QualityProfile.applyManual(b, lens.chars, state, true)
        QualityProfile.applyStill(b, lens.chars)
        if (maxRes && Build.VERSION.SDK_INT >= 31) {
            b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        }
        b.set(CaptureRequest.JPEG_ORIENTATION, orientation)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        job = Job("PS_${stamp}_${lens.equivMm}mm", orientation)
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
                j.jpeg?.let { PhotoSaver.saveJpeg(ctx, it, j.base); parts += "JPEG" }
                j.raw?.let { PhotoSaver.saveDng(ctx, lens.chars, result, it, j.orientation, j.base); parts += "DNG" }
                busy = false
                onFinished("Збережено ${parts.joinToString("+")}: ${j.base}", true)
            } catch (t: Throwable) {
                busy = false
                onFinished("Помилка збереження: ${t.message}", false)
            } finally {
                discard(j)
            }
        }
    }

    private fun discard(j: Job) {
        runCatching { j.jpeg?.close() }
        runCatching { j.raw?.close() }
        j.jpeg = null
        j.raw = null
    }

    fun close() {
        job?.let { discard(it) }
        job = null
        runCatching { jpegReader?.close() }
        runCatching { rawReader?.close() }
    }
}
