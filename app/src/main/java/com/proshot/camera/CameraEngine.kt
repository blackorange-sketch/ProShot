package com.proshot.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
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
import com.proshot.detail.Light
import com.proshot.detail.SceneStats
import com.proshot.detail.SmartController
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
        /** Main detected face as a box in 0..1 portrait preview coordinates, null when none. */
        fun onFace(box: RectF?) {}
        /** Smart auto's opinion on multi-frame stacking (sent every analysis tick). */
        fun onSmartStack(wanted: Boolean) {}
        /** The subject is closer than this lens can focus. */
        fun onTooClose() {}
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
    /** With [fullRes]: shoot 50 MP and downscale 2x for a cleaner, more detailed 12 MP image. */
    @Volatile var superSample = false
    /** Multi-frame stacking mode (works at the binned resolution, so it overrides full-res). */
    @Volatile var stack = false
    @Volatile var deviceOrientation = 0

    @Volatile private var afState: Int? = null
    private var frames = 0

    // Smart auto: all of this is touched on the camera thread only.
    private val smart = SmartController()
    @Volatile private var smartAuto = true
    private var lastIso = 0
    private var lastExp = 0L
    private var nearCount = 0
    private var hintShown = 0L
    private var meterSetAt = 0L
    private var focusToken = 0
    private var faceMissing = 0
    private var faceFrames = 0
    private var faceShown = false

    private val autoRelease = Runnable {
        if (!state.aeLock) releaseAfLock()
    }

    private fun afLabel(): String = when (afState) {
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "AF✓"
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "AF…"
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "AF✗"
        else -> "AF"
    }

    private fun msg(text: String, ok: Boolean = true) = main.post { listener.onMessage(text, ok) }

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("MissingPermission")
    fun open(target: Lens, newLens: Boolean = false) {
        handler.post {
            closeInternal()
            handler.removeCallbacks(autoRelease)
            state = state.copy(meter = null, faceMeter = null, aeLock = false, afAuto = false)
            frames = 0
            faceMissing = 0
            faceShown = false
            nearCount = 0
            if (newLens) {
                smart.reset()
                state = state.copy(smartEvSteps = 0)
            }
        }
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
        handler.post { lens?.let { open(it) } }
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
            StackPipeline(ctx, l, handler, io, finished)
        } else {
            StillPipeline(ctx, l, jpeg, raw, fullRes, superSample, handler, io, finished)
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
                            superSample = false
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
            handleFaces(result)
            if (++frames % 8 != 0) return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (iso != null) pipeline?.setIsoHint(iso)
            if (iso == null || exp == null) return
            lastIso = iso
            lastExp = exp
            expireMeter()
            checkTooClose(focus)
            val f = if (focus != null && focus > 0.01f) String.format("%.2fm", 1f / focus) else "∞"
            var text = "ISO $iso · ${QualityProfile.formatShutter(exp)} · $f · ${afLabel()}"
            if (smartAuto && !state.manualExposure) {
                text += " · " + lightLabel(smart.light)
                if (state.smartEvSteps != 0) {
                    val step = lens?.let { QualityProfile.evStepSize(it.chars) } ?: 0.33f
                    text += String.format(" %+.1fEV", state.smartEvSteps * step)
                }
            }
            main.post { listener.onLive(text) }
        }
    }

    private fun lightLabel(l: Light) = when (l) {
        Light.BRIGHT -> "ЯСНО"
        Light.NORMAL -> "НОРМА"
        Light.DIM -> "СУТІНКИ"
        Light.DARK -> "ТЕМНО"
    }

    /** A tap/face region is only kept for a while, then the camera meters the whole scene again. */
    private fun expireMeter() {
        if (state.meter != null && !state.aeLock && SystemClock.uptimeMillis() - meterSetAt > METER_TTL_MS) {
            state = state.copy(meter = null)
            startPreview()
        }
    }

    /** Autofocus pinned at its nearest distance and still unfocused for ~2 s: the subject is too close. */
    private fun checkTooClose(focus: Float?) {
        val l = lens ?: return
        val minF = QualityProfile.minFocusDiopters(l.chars)
        val af = afState
        val stuck = focus != null && minF > 0f && focus >= minF * 0.9f && state.focusDiopters == null &&
            (af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
        nearCount = if (stuck) nearCount + 1 else 0
        val now = SystemClock.uptimeMillis()
        if (nearCount >= 8 && now - hintShown > 20_000L) {
            hintShown = now
            main.post { listener.onTooClose() }
        }
    }

    // ---------------------------------------------------------------- coordinates

    /** The part of the active array the preview currently shows (the whole array without zoom). */
    private fun viewRect(l: Lens): android.graphics.Rect? =
        QualityProfile.cropRegion(l.chars, state.zoom)
            ?: l.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

    /** Portrait preview position (0..1) to a sensor position (0..1) inside [viewRect]. */
    private fun portraitToSensor(l: Lens, nx: Float, ny: Float): Pair<Float, Float> =
        when (l.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) {
            90 -> ny to (1f - nx)
            270 -> (1f - ny) to nx
            180 -> (1f - nx) to (1f - ny)
            else -> nx to ny
        }

    private fun sensorToPortrait(l: Lens, fx: Float, fy: Float): Pair<Float, Float> =
        when (l.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) {
            90 -> (1f - fy) to fx
            270 -> fy to (1f - fx)
            180 -> (1f - fx) to (1f - fy)
            else -> fx to fy
        }

    // ---------------------------------------------------------------- faces

    private fun handleFaces(result: TotalCaptureResult) {
        val l = lens ?: return
        if (!QualityProfile.faceDetectAvailable(l.chars)) return
        val faces = result.get(CaptureResult.STATISTICS_FACES)
        val best = faces?.filter { it.score >= 50 }?.maxByOrNull { it.bounds.width() * it.bounds.height() }
        if (best == null) {
            if (++faceMissing == 12) {
                faceMissing = 12
                if (state.faceMeter != null) {
                    state = state.copy(faceMeter = null)
                    startPreview()
                }
                if (faceShown) {
                    faceShown = false
                    main.post { listener.onFace(null) }
                }
            }
            return
        }
        faceMissing = 0
        val arr = l.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val vr = viewRect(l) ?: return
        val b = best.bounds
        val fx0 = (b.left - vr.left).toFloat() / vr.width()
        val fx1 = (b.right - vr.left).toFloat() / vr.width()
        val fy0 = (b.top - vr.top).toFloat() / vr.height()
        val fy1 = (b.bottom - vr.top).toFloat() / vr.height()
        if (fx1 < 0f || fy1 < 0f || fx0 > 1f || fy0 > 1f) return

        // Box for the overlay, throttled.
        if (++faceFrames % 3 == 0) {
            val (ax, ay) = sensorToPortrait(l, fx0, fy0)
            val (bx, by) = sensorToPortrait(l, fx1, fy1)
            val box = RectF(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
            faceShown = true
            main.post { listener.onFace(box) }
        }

        // The face drives AF/AE unless the user picked a point or manual focus is on.
        if (state.meter != null || state.aeLock || state.focusDiopters != null) return
        val cx = (b.centerX() - arr.left).coerceIn(0, arr.width())
        val cy = (b.centerY() - arr.top).coerceIn(0, arr.height())
        val rw = max((b.width() * 1.3f).toInt(), arr.width() / 12)
        val rh = max((b.height() * 1.3f).toInt(), arr.height() / 12)
        val x0 = (arr.left + cx - rw / 2).coerceIn(arr.left, max(arr.left, arr.right - rw))
        val y0 = (arr.top + cy - rh / 2).coerceIn(arr.top, max(arr.top, arr.bottom - rh))
        val old = state.faceMeter
        val moved = old == null || abs(old.x - x0) > arr.width() * 0.05f || abs(old.y - y0) > arr.height() * 0.05f
        if (moved) {
            state = state.copy(faceMeter = MeteringRectangle(x0, y0, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX))
            startPreview()
        }
    }

    // ---------------------------------------------------------------- smart auto

    fun setSmart(on: Boolean) {
        handler.post {
            smartAuto = on
            if (!on) {
                smart.reset()
                state = state.copy(smartEvSteps = 0)
                startPreview()
                main.post { listener.onSmartStack(false) }
            }
        }
    }

    /** One analysis tick from the UI: [stats] of a small preview snapshot, or null. */
    fun smartTick(stats: SceneStats?) {
        handler.post {
            if (!smartAuto || device == null) return@post
            val l = lens ?: return@post
            // Right after opening the preview is black; do not react to that.
            val usable = if (frames >= 30 && lastIso > 0) stats else null
            smart.update(usable, lastIso, lastExp)
            if (!state.manualExposure) {
                val step = QualityProfile.evStepSize(l.chars).coerceAtLeast(0.05f)
                val range = QualityProfile.evRange(l.chars)
                val steps = (smart.biasEv / step).roundToInt().coerceIn(range.lower, range.upper)
                if (steps != state.smartEvSteps) {
                    state = state.copy(smartEvSteps = steps)
                    startPreview()
                }
            }
            val wanted = smart.stackWanted
            main.post { listener.onSmartStack(wanted) }
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
        handler.post {
            // Fields owned by the engine survive UI updates.
            state = newState.copy(
                meter = state.meter,
                faceMeter = state.faceMeter,
                aeLock = state.aeLock,
                smartEvSteps = state.smartEvSteps,
                afAuto = state.afAuto,
            )
            startPreview()
        }
    }

    /**
     * Tap-to-focus. [nx], [ny] are 0..1 positions inside the upright (portrait) preview; they are
     * mapped back to sensor coordinates using the sensor orientation and the current zoom crop.
     * With [lock] the focus and exposure stay fixed until the next tap; otherwise continuous AF
     * takes over again after a few seconds.
     */
    fun focusAt(nx: Float, ny: Float, lock: Boolean = false) {
        handler.post {
            val d = device ?: return@post
            val s = session ?: return@post
            val l = lens ?: return@post
            val surf = previewSurface ?: return@post
            val vr = viewRect(l) ?: return@post
            val (fx, fy) = portraitToSensor(l, nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
            val rw = (vr.width() * 0.16f).toInt().coerceAtLeast(8)
            val rh = (vr.height() * 0.16f).toInt().coerceAtLeast(8)
            val x0 = ((vr.left + fx * vr.width()).toInt() - rw / 2).coerceIn(vr.left, max(vr.left, vr.right - rw))
            val y0 = ((vr.top + fy * vr.height()).toInt() - rh / 2).coerceIn(vr.top, max(vr.top, vr.bottom - rh))
            handler.removeCallbacks(autoRelease)
            state = state.copy(
                meter = MeteringRectangle(x0, y0, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX),
                faceMeter = null,
                aeLock = false,
                // Explicit single-shot AF follows the tapped region far more reliably than continuous AF.
                afAuto = state.focusDiopters == null,
            )
            meterSetAt = SystemClock.uptimeMillis()
            val token = ++focusToken
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
            if (lock) {
                // Give AE a moment to settle on the new area, then freeze it.
                handler.postDelayed({
                    if (token == focusToken && state.meter != null && !state.aeLock) {
                        state = state.copy(aeLock = true)
                        startPreview()
                    }
                }, 700)
            } else {
                handler.postDelayed(autoRelease, AF_HOLD_MS)
            }
        }
    }

    // ---------------------------------------------------------------- capture

    private fun orientation(): Int {
        val so = lens?.chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // Front sensors face the user, so the device rotation counts the other way.
        val sign = if (lens?.front == true) -1 else 1
        return (so + sign * deviceOrientation + 360) % 360
    }


    fun capture() {
        handler.post {
            val d = device
            val s = session
            val p = pipeline
            val l = lens
            if (d == null || s == null || p == null || l == null || p.busy) return@post
            main.post { listener.onBusy(true) }
            val needAf = state.focusDiopters == null && !state.aeLock && QualityProfile.minFocusDiopters(l.chars) > 0f
            if (needAf) focusThenShoot(d, s, p, l) else shoot(d, s, p, false)
        }
    }

    private fun focusThenShoot(d: CameraDevice, s: CameraCaptureSession, p: Shooter, l: Lens) {
        val surf = previewSurface ?: return
        try {
            state = state.copy(afAuto = true)
            startPreview()
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
        state = state.copy(afAuto = false)
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

    private companion object {
        const val AF_HOLD_MS = 3000L
        const val METER_TTL_MS = 10_000L
    }
}
