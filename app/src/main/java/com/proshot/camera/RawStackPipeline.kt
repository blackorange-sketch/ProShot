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
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import com.proshot.detail.Finisher
import com.proshot.detail.RawMeta
import com.proshot.detail.RawRender
import com.proshot.detail.RawRenderParams
import com.proshot.detail.RawStacker
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Burst of RAW frames merged before demosaicing (the HDR+ idea): less noise and more real detail than
 * stacking already processed JPEG/YUV frames. The merged RAW is rendered with the colour data the
 * camera reported for the shot (white balance gains, colour matrix, lens shading) and then goes
 * through the same finishing pass as the other modes.
 */
class RawStackPipeline(
    private val ctx: Context,
    private val lens: Lens,
    private val handler: Handler,
    private val io: ExecutorService,
    private val onFinished: (message: String, ok: Boolean) -> Unit,
) : Shooter {

    private val profile = LensProfiles.forLens(lens)
    private val frameCount = profile.rawStackFrames
    private val size: Size = lens.rawSize ?: error("no RAW size")
    override val jpegSize: Size? = null
    override val rawSize: Size? = size

    private val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 3)

    @Volatile
    override var busy = false
        private set

    @Volatile private var orientation = 0
    @Volatile private var base = ""
    @Volatile private var shotState = ManualState()
    @Volatile private var firstResult: TotalCaptureResult? = null
    @Volatile private var lastResult: TotalCaptureResult? = null
    @Volatile private var expected = 0
    @Volatile private var resultLatch = CountDownLatch(1)

    // Touched only on the io thread.
    private var stacker: RawStacker? = null
    private var meta: RawMeta? = null
    private var processed = 0

    // Camera handler thread.
    private var done = 0
    private var launched = 0
    private var received = 0
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest? = null
    private var afterCapture: (() -> Unit)? = null
    private val inFlight = AtomicInteger()

    init {
        reader.setOnImageAvailableListener({ onImage(it) }, handler)
    }

    override fun outputConfigs(): List<OutputConfiguration> {
        val oc = OutputConfiguration(reader.surface)
        lens.physicalId?.let { oc.setPhysicalCameraId(it) }
        return listOf(oc)
    }

    override fun describe() = "RAW-STACK ${frameCount}× ${size.width}x${size.height}"

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
        base = "PS_${stamp}_${lens.equivMm}mm_RAWSTACK${frameCount}_o$orientation"
        this.orientation = orientation
        shotState = state
        firstResult = null
        lastResult = null
        afterCapture = onExposed
        done = 0
        launched = 0
        received = 0
        expected = frameCount
        inFlight.set(0)
        resultLatch = CountDownLatch(1)
        busy = true
        io.execute {
            stacker = null
            meta = null
            processed = 0
        }
        request = b.build()
        this.session = session
        launchNext()
    }

    /** Frames are requested in small batches so unprocessed RAW frames never pile up in memory. */
    private fun launchNext() {
        val s = session ?: return
        val req = request ?: return
        val n = minOf(BATCH, frameCount - launched)
        if (n <= 0) return
        launched += n
        s.captureBurst(List(n) { req }, callback, handler)
    }

    private fun maybeLaunch() {
        if (busy && launched < frameCount && received >= launched && inFlight.get() <= 1) launchNext()
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
        ) {
            if (firstResult == null) {
                firstResult = result
                resultLatch.countDown()
            }
            lastResult = result
            frameDone()
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            frameDone()
            received++
            io.execute {
                expected--
                maybeFinish()
            }
            maybeLaunch()
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
        received++
        if (inFlight.get() >= MAX_IN_FLIGHT) {
            // The merge cannot keep up: skip this frame rather than run out of memory.
            img.close()
            io.execute {
                expected--
                maybeFinish()
            }
            maybeLaunch()
            return
        }
        val frame = try {
            readRaw(img)
        } finally {
            img.close()
        }
        inFlight.incrementAndGet()
        io.execute { consume(frame) }
        maybeLaunch()
    }

    private fun readRaw(img: Image): ShortArray {
        val w = size.width
        val h = size.height
        val plane = img.planes[0]
        val buf = plane.buffer.order(ByteOrder.nativeOrder())
        val rowShorts = plane.rowStride / 2
        val sb = buf.asShortBuffer()
        val out = ShortArray(w * h)
        for (y in 0 until h) {
            sb.position(y * rowShorts)
            sb.get(out, y * w, w)
        }
        return out
    }

    private fun consume(frame: ShortArray) {
        try {
            var m = meta
            if (m == null) {
                // The noise model comes from the capture result of this burst.
                resultLatch.await(2, TimeUnit.SECONDS)
                m = buildMeta(firstResult)
                meta = m
            }
            val s = stacker ?: RawStacker(m).also { stacker = it }
            val proxy = RawStacker.proxyOf(frame, m.width, m.height, m.black.average().toFloat(), m.white)
            s.add(frame, proxy)
            processed++
        } catch (t: Throwable) {
            expected--
        } finally {
            inFlight.decrementAndGet()
        }
        handler.post { maybeLaunch() }
        maybeFinish()
    }

    private fun buildMeta(r: TotalCaptureResult?): RawMeta {
        val c = lens.chars
        val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val white = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        val bp = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = FloatArray(4) { i -> bp?.getOffsetForIndex(i and 1, i shr 1)?.toFloat() ?: 64f }
        var s = 0.0
        var o = 0.0
        val np = r?.get(CaptureResult.SENSOR_NOISE_PROFILE)
        if (np != null && np.isNotEmpty()) {
            for (p in np) {
                s += p.first
                o += p.second
            }
            s /= np.size
            o /= np.size
        } else {
            // No profile reported: a typical mid-ISO model.
            s = 4e-4
            o = 2e-5
        }
        return RawMeta(size.width, size.height, cfa.coerceIn(0, 3), black, white, s.toFloat(), o.toFloat())
    }

    private fun maybeFinish() {
        if (processed < expected) return
        val s = stacker
        val m = meta
        stacker = null
        processed = 0
        if (s == null || m == null) {
            busy = false
            onFinished("RAW-стек: жодного кадру не отримано", false)
            return
        }
        try {
            val r = lastResult
            val merged = s.result()
            val params = renderParams(m, r)
            val rendered = RawRender.render(merged, m, params)
            val (post, optics) = LensProfiles.postFor(lens, rendered.width, rendered.height)
            val finished = Finisher.run(rendered, post)
            val upright = YuvConvert.rotate(finished, orientation)
            val jpeg = YuvConvert.toJpeg(upright, 100)
            val shot = ShotMeta.from(
                lens, r, shotState,
                "ProShot RAW-STACK ${s.frames}x" + (if (optics) " + optics" else ""),
                upright.width, upright.height,
            )
            val sv = PhotoSaver.saveJpegStamped(ctx, jpeg, base, 0, shot)
            busy = false
            onFinished("Збережено RAW-STACK ${s.frames}×: $base [px ${upright.width}x${upright.height} ${sv.note}]", true)
        } catch (t: Throwable) {
            busy = false
            onFinished("Помилка RAW-стеку: ${t.message}", false)
        }
    }

    /** Colour pipeline inputs from the capture result, falling back to the DNG-style neutral point + forward matrix. */
    private fun renderParams(m: RawMeta, r: TotalCaptureResult?): RawRenderParams {
        val c = lens.chars
        var gains: FloatArray? = null
        var matrix: FloatArray? = null

        val g = r?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val t = r?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        if (g != null && t != null) {
            gains = floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue)
            matrix = toMatrix(t)
        }
        if (gains == null || matrix == null || gains.any { it <= 0f } || matrix.all { it == 0f }) {
            val n = r?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            val fm = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) ?: c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)
            if (n != null && n.size == 3 && fm != null) {
                val nr = n[0].toFloat()
                val ng = n[1].toFloat()
                val nb = n[2].toFloat()
                gains = floatArrayOf(ng / nr, 1f, 1f, ng / nb)
                // Scale so that the neutral colour maps to (1,1,1) as the forward matrix expects.
                val wbToXyz = toMatrix(fm)
                matrix = mul(D50_TO_SRGB, wbToXyz)
            } else {
                gains = floatArrayOf(2f, 1f, 1f, 1.6f)
                matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
        }

        var shade: Array<FloatArray>? = null
        var cols = 0
        var rows = 0
        val map = r?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        if (map != null && map.columnCount > 1 && map.rowCount > 1) {
            cols = map.columnCount
            rows = map.rowCount
            shade = Array(4) { ch -> FloatArray(cols * rows) { i -> map.getGainFactor(ch, i % cols, i / cols) } }
        }

        val arr = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        var cropX = 0
        var cropY = 0
        var outW = m.width
        var outH = m.height
        if (arr != null && arr.left >= 0 && arr.top >= 0 &&
            arr.left + arr.width() <= m.width && arr.top + arr.height() <= m.height
        ) {
            cropX = arr.left and 1.inv()
            cropY = arr.top and 1.inv()
            outW = arr.width()
            outH = arr.height()
        }
        return RawRenderParams(
            gains = gains,
            matrix = matrix,
            shade = shade,
            shadeCols = cols,
            shadeRows = rows,
            cropX = cropX,
            cropY = cropY,
            outW = outW,
            outH = outH,
            biasEv = ShotMeta.from(lens, r, shotState, "").evComp,
        )
    }

    private fun toMatrix(t: ColorSpaceTransform): FloatArray =
        FloatArray(9) { i -> t.getElement(i % 3, i / 3).toFloat() }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            o[i * 3 + j] = s
        }
        return o
    }

    override fun close() {
        busy = false
        runCatching { reader.close() }
    }

    companion object {
        private const val MAX_IN_FLIGHT = 6
        private const val BATCH = 4

        private val D50_TO_SRGB = floatArrayOf(
            3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f, 1.9161415f, 0.0334540f,
            0.0719453f, -0.2289914f, 1.4052427f,
        )

        /** True when the lens can deliver Bayer RAW that this pipeline understands. */
        fun supported(l: Lens): Boolean {
            val cfa = l.chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: return false
            return l.supportsRaw && l.rawSize != null && cfa in 0..3
        }
    }
}
