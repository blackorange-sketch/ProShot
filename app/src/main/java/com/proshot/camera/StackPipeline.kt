package com.proshot.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import com.proshot.detail.Finisher
import com.proshot.detail.FrameStacker
import com.proshot.detail.Noise
import com.proshot.detail.StackerFactory
import com.proshot.detail.YuvFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Shoots a burst of [frameCount] identical-exposure YUV frames, aligns and merges them with the
 * detail library and saves one JPEG. Frames are copied out of the camera buffers immediately and
 * merged on [io] so the camera thread never stalls.
 */
class StackPipeline(
    private val ctx: Context,
    private val lens: Lens,
    private val handler: Handler,
    private val io: ExecutorService,
    private val onFinished: (message: String, ok: Boolean) -> Unit,
) : Shooter {

    private val profile = LensProfiles.forLens(lens)
    private val frameCount = profile.stackFrames
    private val size: Size = pickSize(lens)
    override val jpegSize: Size? = size
    override val rawSize: Size? = null

    private val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)

    @Volatile
    override var busy = false
        private set

    @Volatile private var isoHint = 100
    @Volatile private var expected = 0
    @Volatile private var orientation = 0
    @Volatile private var base = ""
    @Volatile private var lastResult: TotalCaptureResult? = null
    @Volatile private var shotState = ManualState()

    // Touched only on the io thread.
    private var stacker: FrameStacker? = null
    private var processed = 0

    // Touched only on the camera handler thread.
    private var done = 0
    private var afterCapture: (() -> Unit)? = null

    init {
        reader.setOnImageAvailableListener({ onImage(it) }, handler)
    }

    override fun setIsoHint(iso: Int) {
        isoHint = iso
    }

    override fun outputConfigs(): List<OutputConfiguration> {
        val oc = OutputConfiguration(reader.surface)
        lens.physicalId?.let { oc.setPhysicalCameraId(it) }
        return listOf(oc)
    }

    override fun describe() = "STACK ${frameCount}× ${size.width}x${size.height}"

    override fun capture(
        device: CameraDevice,
        session: CameraCaptureSession,
        state: ManualState,
        orientation: Int,
        onExposed: (() -> Unit)?,
    ) {
        if (busy) return
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(reader.surface)
        QualityProfile.applyManual(b, lens.chars, state, true)
        QualityProfile.applyStill(b, lens.chars, profile.stillNoiseReduction)
        QualityProfile.applyStackFrame(b, lens.chars)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        base = "PS_${stamp}_${lens.equivMm}mm_STACK${frameCount}_o$orientation"
        this.orientation = orientation
        shotState = state
        lastResult = null
        afterCapture = onExposed
        done = 0
        expected = frameCount
        busy = true
        io.execute {
            stacker = null
            processed = 0
        }
        val req = b.build()
        session.captureBurst(List(frameCount) { req }, callback, handler)
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { isoHint = it }
            lastResult = result
            frameDone()
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            frameDone()
            io.execute {
                expected--
                maybeFinish()
            }
        }
    }

    private fun frameDone() {
        if (++done >= frameCount) {
            afterCapture?.invoke()
            afterCapture = null
        }
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        if (!busy) {
            img.close()
            return
        }
        val frame = try {
            YuvConvert.fromImage(img)
        } finally {
            img.close()
        }
        io.execute { consume(frame) }
    }

    private fun consume(frame: YuvFrame) {
        try {
            val s = stacker
            if (s == null) stacker = StackerFactory.create(frame, Noise.sigmaForIso(isoHint)) else s.add(frame)
            processed++
        } catch (t: Throwable) {
            expected--
        }
        maybeFinish()
    }

    private fun maybeFinish() {
        if (processed < expected) return
        val s = stacker
        stacker = null
        processed = 0
        if (s == null) {
            busy = false
            onFinished("Стекінг: жодного кадру не отримано", false)
            return
        }
        try {
            val merged = s.result()
            val (post, optics) = LensProfiles.postFor(lens, merged.width, merged.height)
            val finished = Finisher.run(merged, post)
            val upright = YuvConvert.rotate(finished, orientation)
            val jpeg = YuvConvert.toJpeg(upright, 100)
            val engineName = if (StackerFactory.nativeAvailable) "C++" else "Kotlin"
            val meta = ShotMeta.from(
                lens, lastResult, shotState,
                "ProShot STACK ${s.frames}x ($engineName${if (optics) " + optics" else ""})",
                upright.width, upright.height,
            )
            val sv = PhotoSaver.saveJpegStamped(ctx, jpeg, base, 0, meta)
            busy = false
            onFinished("Збережено STACK ${s.frames}× ($engineName${if (optics) " + оптика" else ""}): $base [px ${upright.width}x${upright.height} ${sv.note}]", true)
        } catch (t: Throwable) {
            busy = false
            onFinished("Помилка стекінгу: ${t.message}", false)
        } finally {
            s.close()
        }
    }

    override fun close() {
        busy = false
        runCatching { reader.close() }
    }

    private companion object {
        /** Largest YUV size up to ~13.5 MP: the binned frame, which keeps a burst in memory. */
        fun pickSize(l: Lens): Size {
            val map = l.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            return sizes.filter { it.width.toLong() * it.height <= 13_500_000L }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: l.jpegSize
                ?: Size(4000, 3000)
        }
    }
}
