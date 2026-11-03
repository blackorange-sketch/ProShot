package com.proshot.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs

class CameraEngine(
    private val ctx: Context,
    private val textureProvider: () -> SurfaceTexture?,
    private val listener: Listener,
) {
    interface Listener {
        fun onPreviewSize(width: Int, height: Int)
        fun onLive(text: String)
        fun onMessage(text: String, ok: Boolean)
        fun onBusy(busy: Boolean)
        /** The engine had to change a mode (e.g. dropped RAW) so the UI should refresh its toggles. */
        fun onModesChanged()
    }

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("proshot-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var pipeline: Shooter? = null
    private var previewSurface: Surface? = null
    private var lens: Lens? = null

    @Volatile var state = ManualState()
        private set
    @Volatile var wantJpeg = true
    @Volatile var wantRaw = false
    @Volatile var fullRes = false
    /** Multi-frame stacking mode (works at the binned resolution, so it overrides full-res). */
    @Volatile var stack = false
    @Volatile var deviceOrientation = 0

    @Volatile private var afState: Int? = null
    private var frames = 0

    private fun msg(text: String, ok: Boolean = true) = main.post { listener.onMessage(text, ok) }

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("MissingPermission")
    fun open(target: Lens) {
        state = state.copy(meter = null)
        handler.post { closeInternal() }
        handler.postDelayed({
            lens = target
            try {
                mgr.openCamera(target.openId, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        createSession(d, target)
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        d.close()
                        device = null
                        msg("Камеру відключено", false)
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        d.close()
                        device = null
                        msg("Помилка камери (код $error)", false)
                    }
                }, handler)
            } catch (t: Throwable) {
                msg("Не вдалося відкрити камеру: ${t.message}", false)
            }
        }, 200)
    }

    /** Re-creates the session for the current lens after a mode toggle. */
    fun reconfigure() {
        lens?.let { open(it) }
    }

    fun pause() {
        handler.post { closeInternal() }
    }

    fun release() {
        handler.post {
            closeInternal()
            thread.quitSafely()
        }
        io.shutdown()
    }

    private fun closeInternal() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        pipeline?.close()
        pipeline = null
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    // ---------------------------------------------------------------- session

    private fun createSession(d: CameraDevice, l: Lens) {
        val st = textureProvider()
        if (st == null) {
            msg("Поверхня прев'ю ще не готова", false)
            return
        }

        val raw = !stack && wantRaw && l.supportsRaw && (!fullRes || l.maxResRawSize != null)
        val jpeg = wantJpeg || !raw
        if (wantRaw && !raw) msg("RAW у цьому режимі недоступний — лише JPEG", false)

        val stillSize = if (fullRes && !stack && l.maxResJpegSize != null) l.maxResJpegSize else l.jpegSize
        val previewSize = choosePreview(l, stillSize)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(st)
        previewSurface = surface
        main.post { listener.onPreviewSize(previewSize.width, previewSize.height) }

        val finished: (String, Boolean) -> Unit = { text, ok ->
            main.post {
                listener.onBusy(false)
                listener.onMessage(text, ok)
            }
        }
        val still: Shooter = if (stack) {
            StackPipeline(ctx, l, STACK_FRAMES, handler, io, finished)
        } else {
            StillPipeline(ctx, l, jpeg, raw, fullRes, handler, io, finished)
        }

        val previewCfg = OutputConfiguration(surface)
        l.physicalId?.let { previewCfg.setPhysicalCameraId(it) }

        val sc = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(previewCfg) + still.outputConfigs(),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    pipeline = still
                    startPreview()
                    msg("${l.label}: ${still.describe()}")
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    still.close()
                    when {
                        stack -> {
                            stack = false
                            msg("Стекінг не підтримується цим об'єктивом — звичайна зйомка", false)
                            main.post { listener.onModesChanged() }
                            reconfigure()
                        }
                        fullRes -> {
                            fullRes = false
                            msg("Full-res не підтримується цією комбінацією — стандартна роздільність", false)
                            main.post { listener.onModesChanged() }
                            reconfigure()
                        }
                        raw -> {
                            wantRaw = false
                            msg("RAW-потік не підтримується — лише JPEG", false)
                            main.post { listener.onModesChanged() }
                            reconfigure()
                        }
                        else -> msg("Не вдалося налаштувати сесію камери", false)
                    }
                }
            },
        )
        try {
            d.createCaptureSession(sc)
        } catch (t: Throwable) {
            still.close()
            msg("Помилка сесії: ${t.message}", false)
        }
    }

    private fun choosePreview(l: Lens, still: Size?): Size {
        val map = l.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val ratio = still?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        val matching = sizes.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.width <= 2560 }
        val pool = matching.ifEmpty { sizes.filter { it.width <= 1920 } }
        return pool.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 960)
    }

    // ---------------------------------------------------------------- preview

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (++frames % 8 != 0) return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (iso != null) pipeline?.setIsoHint(iso)
            if (iso == null || exp == null) return
            val f = if (focus != null && focus > 0.01f) String.format("%.2fm", 1f / focus) else "∞"
            main.post { listener.onLive("ISO $iso · ${QualityProfile.formatShutter(exp)} · $f") }
        }
    }

    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val l = lens ?: return
        val surf = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            QualityProfile.applyManual(b, l.chars, state, false)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (t: Throwable) {
            msg("Прев'ю: ${t.message}", false)
        }
    }

    fun update(newState: ManualState) {
        state = newState.copy(meter = state.meter)
        handler.post { startPreview() }
    }

    /**
     * Tap-to-focus. [nx], [ny] are 0..1 positions inside the upright (portrait) preview; they are
     * mapped back to sensor coordinates using the sensor orientation.
     */
    fun focusAt(nx: Float, ny: Float) {
        handler.post {
            val d = device ?: return@post
            val s = session ?: return@post
            val l = lens ?: return@post
            val surf = previewSurface ?: return@post
            val arr = l.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
            val so = l.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val (fx, fy) = when (so) {
                90 -> ny to (1f - nx)
                270 -> (1f - ny) to nx
                180 -> (1f - nx) to (1f - ny)
                else -> nx to ny
            }
            val rw = (arr.width() * 0.16f).toInt().coerceAtLeast(8)
            val rh = (arr.height() * 0.16f).toInt().coerceAtLeast(8)
            val x0 = ((arr.left + fx * arr.width()).toInt() - rw / 2).coerceIn(arr.left, arr.right - rw)
            val y0 = ((arr.top + fy * arr.height()).toInt() - rh / 2).coerceIn(arr.top, arr.bottom - rh)
            state = state.copy(meter = MeteringRectangle(x0, y0, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX))
            if (state.focusDiopters == null && QualityProfile.minFocusDiopters(l.chars) > 0f) {
                try {
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(surf)
                    QualityProfile.applyManual(b, l.chars, state, false)
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    s.capture(b.build(), null, handler)
                } catch (_: Throwable) {
                }
            }
            startPreview()
        }
    }

    // ---------------------------------------------------------------- capture

    private fun orientation(): Int {
        val so = lens?.chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // Front sensors face the user, so the device rotation counts the other way.
        val sign = if (lens?.front == true) -1 else 1
        return (so + sign * deviceOrientation + 360) % 360
    }

    private companion object {
        const val STACK_FRAMES = 8
    }

    fun capture() {
        handler.post {
            val d = device
            val s = session
            val p = pipeline
            val l = lens
            if (d == null || s == null || p == null || l == null || p.busy) return@post
            main.post { listener.onBusy(true) }
            val needAf = state.focusDiopters == null && QualityProfile.minFocusDiopters(l.chars) > 0f
            if (needAf) focusThenShoot(d, s, p, l) else shoot(d, s, p, false)
        }
    }

    private fun focusThenShoot(d: CameraDevice, s: CameraCaptureSession, p: Shooter, l: Lens) {
        val surf = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            QualityProfile.applyManual(b, l.chars, state, false)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            afState = null
            s.capture(b.build(), null, handler)
        } catch (t: Throwable) {
            shoot(d, s, p, false)
            return
        }
        val deadline = SystemClock.uptimeMillis() + 1500
        val poll = object : Runnable {
            override fun run() {
                val st = afState
                val locked = st == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    st == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                if (locked || SystemClock.uptimeMillis() > deadline) shoot(d, s, p, true) else handler.postDelayed(this, 40)
            }
        }
        handler.postDelayed(poll, 60)
    }

    private fun shoot(d: CameraDevice, s: CameraCaptureSession, p: Shooter, releaseAf: Boolean) {
        try {
            p.capture(d, s, state, orientation()) { if (releaseAf) releaseAfLock() }
        } catch (t: Throwable) {
            main.post { listener.onBusy(false) }
            msg("Зйомка: ${t.message}", false)
        }
    }

    private fun releaseAfLock() {
        val d = device ?: return
        val s = session ?: return
        val l = lens ?: return
        val surf = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            QualityProfile.applyManual(b, l.chars, state, false)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, handler)
        } catch (_: Throwable) {
        }
        startPreview()
    }
}
