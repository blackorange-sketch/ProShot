package com.proshot.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.proshot.detail.Scene
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), CameraEngine.Listener {

    private lateinit var engine: CameraEngine
    private lateinit var preview: AutoFitTextureView
    private lateinit var grid: GridView
    private lateinit var focusRing: FocusRingView
    private lateinit var faceBox: FaceBoxView
    private lateinit var chipAuto: TextView
    private lateinit var lensBar: LinearLayout
    private lateinit var liveText: TextView
    private lateinit var statusText: TextView
    private lateinit var lensInfo: TextView
    private lateinit var chipFormat: TextView
    private lateinit var chipRes: TextView
    private lateinit var chipWb: TextView
    private lateinit var chipGrid: TextView
    private lateinit var btnExpand: ImageView
    private lateinit var btnShutter: View
    private lateinit var btnFlip: ImageView
    private lateinit var thumb: ImageView
    private lateinit var flash: View
    private lateinit var proPanel: View
    private lateinit var rowIso: View
    private lateinit var rowShutter: View
    private lateinit var rowEv: View
    private lateinit var labelIso: TextView
    private lateinit var labelShutter: TextView
    private lateinit var labelEv: TextView
    private lateinit var labelFocus: TextView
    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var seekEv: SeekBar
    private lateinit var seekFocus: SeekBar
    private lateinit var modes: List<TextView>
    private lateinit var orientationListener: OrientationEventListener

    private val sound = MediaActionSound()
    private var lenses: List<Lens> = emptyList()
    private var current: Lens? = null
    private var lastBack: Lens? = null
    private var ms = ManualState()
    private var fmt = 0 // 0 = JPEG, 1 = RAW, 2 = JPEG+RAW, 3 = STACK (YUV), 4 = RAW-STACK

    private fun isStack() = fmt == 3 || fmt == 4

    /** The best stacking mode this lens offers. */
    private fun stackFmt(l: Lens) = if (RawStackPipeline.supported(l)) 4 else 3
    private var fullRes = false
    private var superSample = false
    private var gridOn = false
    private var panelOpen = false
    private var shooting = false
    private var surfaceReady = false
    private var opened = false
    private var lastUri: Uri? = null
    private var wbModes: List<Pair<Int, String>> = emptyList()

    // Zoom: totalZoom is relative to the main lens (1x = 35 mm equivalent).
    private var totalZoom = 1f
    private var mainEquiv = 24
    private var scaling = false
    private var lastPinchEnd = 0L
    private var selLensView: TextView? = null

    // Tap-to-focus extras.
    private var ringX = 0f
    private var ringY = 0f
    private var evArmed = false
    private var evDragging = false
    private var evStart = 0

    // Smart auto.
    private var smartOn = true
    private var autoStackActive = false
    private var autoHoldUntil = 0L
    private val smartBmp: Bitmap = Bitmap.createBitmap(SMART_W, SMART_H, Bitmap.Config.ARGB_8888)
    private val smartPx = IntArray(SMART_W * SMART_H)
    private val smartRunnable = object : Runnable {
        override fun run() {
            if (smartOn && opened && !shooting && !ms.manualExposure && preview.isAvailable) {
                val stats = runCatching {
                    preview.getBitmap(smartBmp)
                    smartBmp.getPixels(smartPx, 0, SMART_W, 0, 0, SMART_W, SMART_H)
                    Scene.stats(smartPx)
                }.getOrNull()
                engine.smartTick(stats)
            }
            preview.postDelayed(this, 700)
        }
    }

    private val hideStatus = Runnable { statusText.visibility = View.GONE }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startUp() else status("Потрібен дозвіл на камеру", false)
    }

    // ---------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        grid = findViewById(R.id.grid)
        focusRing = findViewById(R.id.focusRing)
        faceBox = findViewById(R.id.faceBox)
        chipAuto = findViewById(R.id.chipAuto)
        lensBar = findViewById(R.id.lensBar)
        liveText = findViewById(R.id.liveText)
        statusText = findViewById(R.id.statusText)
        lensInfo = findViewById(R.id.lensInfo)
        chipFormat = findViewById(R.id.chipFormat)
        chipRes = findViewById(R.id.chipRes)
        chipWb = findViewById(R.id.chipWb)
        chipGrid = findViewById(R.id.chipGrid)
        btnExpand = findViewById(R.id.btnExpand)
        btnShutter = findViewById(R.id.btnShutter)
        btnFlip = findViewById(R.id.btnFlip)
        thumb = findViewById(R.id.thumb)
        flash = findViewById(R.id.flash)
        proPanel = findViewById(R.id.proPanel)
        rowIso = findViewById(R.id.rowIso)
        rowShutter = findViewById(R.id.rowShutter)
        rowEv = findViewById(R.id.rowEv)
        labelIso = findViewById(R.id.labelIso)
        labelShutter = findViewById(R.id.labelShutter)
        labelEv = findViewById(R.id.labelEv)
        labelFocus = findViewById(R.id.labelFocus)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        seekEv = findViewById(R.id.seekEv)
        seekFocus = findViewById(R.id.seekFocus)
        modes = listOf(
            findViewById<TextView>(R.id.modePhoto), findViewById<TextView>(R.id.modeStack),
            findViewById<TextView>(R.id.modeRaw), findViewById<TextView>(R.id.modePro),
        )

        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setOval(0, 0, v.width, v.height)
        }
        thumb.clipToOutline = true
        sound.load(MediaActionSound.SHUTTER_CLICK)

        engine = CameraEngine(this, { preview.surfaceTexture }, this)

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                startUp()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceReady = false
                opened = false
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != OrientationEventListener.ORIENTATION_UNKNOWN) {
                    // Hysteresis: switch quadrant only once the phone is clearly (>60 deg) away from the
                    // current one, so a steep downward tilt or a wobble cannot flip the photo sideways.
                    val cur = engine.deviceOrientation
                    val d = Math.abs(o - cur).let { if (it > 180) 360 - it else it }
                    if (d > 60) engine.deviceOrientation = ((o + 45) / 90 * 90) % 360
                }
            }
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                evDragging = false
                val l = current
                val evOk = l != null && QualityProfile.evRange(l.chars).let { it.upper > it.lower }
                evArmed = evOk && !ms.manualExposure && focusRing.active &&
                    hypot(e.x - ringX, e.y - ringY) < dp(90)
                evStart = ms.evSteps
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapFocus(e.x, e.y, false)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (scaling || evDragging) return
                preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                tapFocus(e.x, e.y, true)
                status("AE/AF заблоковано — тапни, щоб розблокувати")
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val start = e1 ?: return false
                if (!evArmed || scaling) return false
                val l = current ?: return false
                if (!evDragging) {
                    val moveY = abs(e2.y - start.y)
                    if (moveY < dp(12) || moveY < abs(e2.x - start.x)) return false
                    evDragging = true
                }
                val range = QualityProfile.evRange(l.chars)
                val perEv = dp(70).toFloat()
                val evDelta = (start.y - e2.y) / perEv
                val steps = (evStart + evDelta / QualityProfile.evStepSize(l.chars)).roundToInt()
                    .coerceIn(range.lower, range.upper)
                setEv(steps, true)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (scaling || evDragging || SystemClock.uptimeMillis() - lastPinchEnd < 350) return false
                if (abs(vx) > abs(vy) && abs(vx) > 800f) {
                    switchMode(if (vx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })

        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (fullRes && !isStack()) {
                    status("Для зуму вимкни FULL (50 МП)", false)
                    return false
                }
                scaling = true
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val l = current ?: return false
                val (lo, hi) = zoomRange()
                totalZoom = (totalZoom * d.scaleFactor).coerceIn(lo, hi)
                applyDigital(l)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                scaling = false
                lastPinchEnd = SystemClock.uptimeMillis()
                settleLens()
            }
        })

        preview.setOnTouchListener { _, ev ->
            scaler.onTouchEvent(ev)
            gestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                evArmed = false
                evDragging = false
            }
            true
        }

        btnShutter.setOnClickListener { shutter() }
        btnFlip.setOnClickListener { flipCamera() }
        thumb.setOnClickListener { openGallery() }
        chipFormat.setOnClickListener { cycleFormat() }
        chipRes.setOnClickListener { toggleRes() }
        chipWb.setOnClickListener { cycleWb() }
        chipAuto.setOnClickListener {
            smartOn = !smartOn
            engine.setSmart(smartOn)
            if (!smartOn) revertAutoStack()
            refreshToggles()
            status(if (smartOn) "AUTO: експозиція і стек підлаштовуються під сцену" else "AUTO вимкнено")
        }
        chipGrid.setOnClickListener {
            gridOn = !gridOn
            grid.show = gridOn
            refreshToggles()
        }
        btnExpand.setOnClickListener {
            panelOpen = !panelOpen
            refreshToggles()
        }
        modes.forEachIndexed { i, tv -> tv.setOnClickListener { applyMode(i) } }
        bindSeekbars()
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (preview.isAvailable) {
            surfaceReady = true
            startUp()
        }
        refreshThumb()
        preview.removeCallbacks(smartRunnable)
        preview.postDelayed(smartRunnable, 1500)
    }

    override fun onPause() {
        preview.removeCallbacks(smartRunnable)
        orientationListener.disable()
        opened = false
        engine.pause()
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        engine.release()
        super.onDestroy()
    }

    private fun startUp() {
        if (!surfaceReady || opened) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
            return
        }
        if (lenses.isEmpty()) {
            lenses = runCatching { LensCatalog.discover(this) }.getOrDefault(emptyList())
            if (lenses.isEmpty()) {
                status("Камери не знайдено", false)
                return
            }
            current = lenses.firstOrNull { !it.front && it.equivMm in 22..44 } ?: lenses.first()
            mainEquiv = lenses.firstOrNull { !it.front && it.equivMm in 22..44 }?.equivMm
                ?: lenses.firstOrNull { !it.front && it.equivMm > 0 }?.equivMm ?: 24
        }
        opened = true
        applyLens(current ?: lenses.first(), totalZoom)
    }

    // ---------------------------------------------------------------- lenses

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ratioOf(l: Lens): Float = if (l.front || l.equivMm <= 0) 1f else l.equivMm.toFloat() / mainEquiv

    private fun digitalMax(l: Lens) = QualityProfile.maxDigitalZoom(l.chars)

    private fun zoomText(r: Float): String =
        if (r < 0.95f) ".${(r * 10).roundToInt()}" else String.format(Locale.US, "%.1f", r).removeSuffix(".0")

    /** Lowest and highest total zoom reachable with the lenses on the current side (digital zoom capped at 4x). */
    private fun zoomRange(): Pair<Float, Float> {
        val cur = current ?: return 1f to 1f
        val group = lenses.filter { it.front == cur.front }.ifEmpty { listOf(cur) }
        return group.minOf { ratioOf(it) } to group.maxOf { ratioOf(it) * min(digitalMax(it), 4f) }
    }

    private fun buildLensBar() {
        lensBar.removeAllViews()
        selLensView = null
        val cur = current ?: return
        for (l in lenses.filter { it.front == cur.front }) {
            val sel = l === cur
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                text = lensText(l, sel)
                textSize = if (sel) 12f else 11f
                setLineSpacing(0f, 0.85f)
                setTextColor(if (sel) Color.WHITE else 0xFFDDDDDD.toInt())
                setBackgroundResource(if (sel) R.drawable.lens_selected else R.drawable.lens_dot)
                setOnClickListener {
                    if (sel) {
                        totalZoom = ratioOf(l)
                        applyDigital(l)
                    } else {
                        applyLens(l, ratioOf(l))
                    }
                }
            }
            if (sel) selLensView = tv
            val size = dp(if (sel) 48 else 36)
            lensBar.addView(tv, LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            })
        }
    }

    /** Stock-style label: focal length in mm; while digitally zoomed the selected dot shows the zoom instead. */
    private fun lensText(l: Lens, sel: Boolean): String {
        val mm = if (l.equivMm > 0) l.equivMm.toString() else "?"
        if (!sel) return mm
        return if (abs(totalZoom - ratioOf(l)) > 0.05f) "${zoomText(totalZoom)}×" else "$mm\nmm"
    }

    /** Applies the part of [totalZoom] the current lens has to deliver digitally. */
    private fun applyDigital(l: Lens) {
        val z = (totalZoom / ratioOf(l)).coerceIn(1f, digitalMax(l))
        if (z != ms.zoom) {
            ms = ms.copy(zoom = z)
            engine.update(ms)
        }
        selLensView?.text = lensText(l, true)
    }

    /** After a pinch: pick the lens whose own field of view is closest below the requested zoom. */
    private fun settleLens() {
        val cur = current ?: return
        if (cur.front) return
        val back = lenses.filter { !it.front && it.equivMm > 0 }
        if (back.isEmpty()) return
        val best = back.filter { ratioOf(it) <= totalZoom * 1.06f }.maxByOrNull { ratioOf(it) }
            ?: back.minByOrNull { ratioOf(it) }
        if (best != null && best !== cur) applyLens(best, totalZoom)
    }

    private fun applyLens(l: Lens, total: Float = ratioOf(l)) {
        val changed = current !== l
        current = l
        if (!l.front) lastBack = l
        totalZoom = maxOf(total, ratioOf(l))
        if (autoStackActive) {
            autoStackActive = false
            if (isStack()) fmt = 0
        }
        faceBox.setBox(null)
        lensInfo.text = LensCatalog.describe(l)

        if (!l.supportsRaw && (fmt == 1 || fmt == 2)) fmt = 0
        if (fmt == 4 && !RawStackPipeline.supported(l)) fmt = 3
        if (!l.supportsMaxRes) {
            fullRes = false
            superSample = false
        }

        val avail = l.chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet() ?: emptySet()
        wbModes = listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "WB AUTO",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "WB ДЕНЬ",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "WB ХМАРНО",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "WB ТІНЬ",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "WB ЛАМПА",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "WB ЛЮМІН.",
        ).filter { it.first in avail }.ifEmpty { listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO to "WB AUTO") }

        ms = ManualState(
            manualExposure = ms.manualExposure,
            iso = QualityProfile.isoRange(l.chars).let { 100.coerceIn(it.lower, it.upper) },
            exposureNs = QualityProfile.exposureRange(l.chars).let { 8_000_000L.coerceIn(it.lower, it.upper) },
            awbMode = wbModes.first().first,
            zoom = (totalZoom / ratioOf(l)).coerceIn(1f, digitalMax(l)),
        )
        configureSeekbars(l)
        buildLensBar()
        refreshToggles()
        pushModes()
        engine.update(ms)
        engine.open(l, changed)
    }

    private fun flipCamera() {
        val cur = current ?: return
        val target = if (cur.front) {
            lastBack ?: lenses.firstOrNull { !it.front }
        } else {
            lenses.firstOrNull { it.front }
        }
        if (target == null) {
            status("Другої камери немає", false)
            return
        }
        applyLens(target)
    }

    // ---------------------------------------------------------------- modes and toggles

    private fun pushModes() {
        engine.stack = isStack()
        engine.rawStack = fmt == 4
        engine.wantJpeg = fmt != 1
        engine.wantRaw = fmt == 1 || fmt == 2
        engine.fullRes = fullRes && !isStack()
        engine.superSample = fullRes && superSample && !isStack()
    }

    private fun currentMode() = when {
        ms.manualExposure -> 3
        isStack() -> 1
        fmt == 1 || fmt == 2 -> 2
        else -> 0
    }

    private fun switchMode(step: Int) {
        applyMode((currentMode() + step).coerceIn(0, modes.size - 1))
    }

    private fun userChoseMode() {
        autoStackActive = false
        autoHoldUntil = SystemClock.uptimeMillis() + 60_000L
    }

    private fun applyMode(i: Int) {
        val l = current ?: return
        userChoseMode()
        val before = fmt
        when (i) {
            0 -> {
                ms = ms.copy(manualExposure = false)
                fmt = 0
            }
            1 -> {
                ms = ms.copy(manualExposure = false)
                fmt = stackFmt(l)
            }
            2 -> {
                if (!l.supportsRaw) {
                    status("RAW недоступний для цього об'єктива", false)
                    return
                }
                ms = ms.copy(manualExposure = false)
                fmt = 2
            }
            else -> {
                ms = ms.copy(manualExposure = true)
                if (l.supportsRaw && (fmt == 0 || isStack())) fmt = 2
            }
        }
        refreshToggles()
        pushModes()
        engine.update(ms)
        if (fmt != before) engine.reconfigure()
    }

    private fun cycleFormat() {
        val l = current ?: return
        userChoseMode()
        val order = if (l.supportsRaw) listOf(0, 1, 2, 4, 3) else listOf(0, 3)
        fmt = order[(order.indexOf(fmt) + 1) % order.size]
        refreshToggles()
        pushModes()
        engine.reconfigure()
    }

    private fun toggleRes() {
        val l = current ?: return
        if (!l.supportsMaxRes) {
            status("Full-res недоступний для цього об'єктива", false)
            return
        }
        if (isStack()) {
            status("СТЕК працює у стандартній роздільності — для FULL вимкни СТЕК", false)
            return
        }
        // STD -> FULL (50 MP) -> SUPER (50 MP downscaled to 12 MP) -> STD
        when {
            !fullRes -> fullRes = true
            !superSample -> superSample = true
            else -> {
                fullRes = false
                superSample = false
            }
        }
        if (fullRes && ms.zoom > 1f) {
            // Digital zoom is not available in the full-resolution sensor mode.
            totalZoom = ratioOf(l)
            ms = ms.copy(zoom = 1f)
            engine.update(ms)
            buildLensBar()
        }
        refreshToggles()
        pushModes()
        engine.reconfigure()
    }

    private fun cycleWb() {
        val i = wbModes.indexOfFirst { it.first == ms.awbMode }
        ms = ms.copy(awbMode = wbModes[(i + 1) % wbModes.size].first)
        refreshToggles()
        engine.update(ms)
    }

    private fun mp(w: Int, h: Int) = (w.toLong() * h / 1_000_000.0).roundToInt()

    private fun chip(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.chip_bg_on else R.drawable.chip_bg)
        v.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun refreshToggles() {
        val l = current

        val prof = l?.let { LensProfiles.forLens(it) }
        val stackN = prof?.stackFrames ?: 8
        val rawN = prof?.rawStackFrames ?: 12
        chipFormat.text = arrayOf("JPEG", "RAW", "JPEG+RAW", "STACK ×$stackN", "RAW-STACK ×$rawN")[fmt]
        chip(chipFormat, fmt != 0)

        val std = l?.jpegSize?.let { "${mp(it.width, it.height)}MP" } ?: ""
        val full = l?.maxResJpegSize?.let { "${mp(it.width, it.height)}MP" }
        val fullOn = fullRes && !isStack()
        chipRes.text = when {
            fullOn && full != null && superSample -> "SUPER $full→$std"
            fullOn && full != null -> "FULL $full"
            else -> "STD $std"
        }
        chip(chipRes, fullOn)

        chipWb.text = wbModes.firstOrNull { it.first == ms.awbMode }?.second ?: "WB"
        chip(chipWb, ms.awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO)
        chip(chipGrid, gridOn)
        chip(chipAuto, smartOn)

        val mode = currentMode()
        modes.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == mode) 0xFFFF3B30.toInt() else Color.WHITE)
            tv.paint.isFakeBoldText = i == mode
            tv.invalidate()
        }

        proPanel.visibility = if (ms.manualExposure || panelOpen) View.VISIBLE else View.GONE
        rowIso.visibility = if (ms.manualExposure) View.VISIBLE else View.GONE
        rowShutter.visibility = if (ms.manualExposure) View.VISIBLE else View.GONE
        rowEv.visibility = if (ms.manualExposure) View.GONE else View.VISIBLE
        btnExpand.rotation = if (panelOpen) 180f else 0f
    }

    // ---------------------------------------------------------------- shutter, focus, gallery

    private fun shutter() {
        if (shooting) return
        sound.play(MediaActionSound.SHUTTER_CLICK)
        btnShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(200).start()
        engine.capture()
    }

    private fun tapFocus(x: Float, y: Float, lock: Boolean) {
        if (preview.width == 0 || preview.height == 0) return
        engine.focusAt(x / preview.width, y / preview.height, lock)
        ringX = x
        ringY = y
        focusRing.show(preview.left + x, preview.top + y, lock)
    }

    /** Sets exposure compensation from the seekbar or from dragging after a tap. */
    private fun setEv(steps: Int, fromDrag: Boolean) {
        val l = current ?: return
        ms = ms.copy(evSteps = steps)
        val txt = String.format(Locale.US, "%+.1f", steps * QualityProfile.evStepSize(l.chars))
        labelEv.text = "EV $txt"
        seekEv.progress = steps - QualityProfile.evRange(l.chars).lower
        engine.update(ms)
        if (fromDrag) focusRing.keepAlive("EV $txt")
    }

    private fun refreshThumb() {
        Thread {
            var uri: Uri? = null
            var bmp: android.graphics.Bitmap? = null
            runCatching {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("DCIM/ProShot%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) {
                        uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0),
                        )
                    }
                }
                uri?.let { bmp = contentResolver.loadThumbnail(it, Size(160, 160), null) }
            }
            runOnUiThread {
                lastUri = uri
                bmp?.let { thumb.setImageBitmap(it) }
            }
        }.start()
    }

    private fun openGallery() {
        val u = lastUri ?: run {
            status("Знімків ще немає", false)
            return
        }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }

    // ---------------------------------------------------------------- seekbars

    private fun logMap(p: Int, lo: Double, hi: Double) = lo * (hi / lo).pow(p / 100.0)
    private fun logInv(v: Double, lo: Double, hi: Double) =
        (100 * ln(v / lo) / ln(hi / lo)).roundToInt().coerceIn(0, 100)

    private fun isoBounds(l: Lens) = QualityProfile.isoRange(l.chars).let { it.lower.toDouble() to it.upper.toDouble() }

    private fun shutterBounds(l: Lens) = QualityProfile.exposureRange(l.chars).let {
        it.lower.toDouble() to minOf(it.upper.toDouble(), 4e9).coerceAtLeast(it.lower.toDouble() * 2)
    }

    private fun configureSeekbars(l: Lens) {
        val (isoLo, isoHi) = isoBounds(l)
        seekIso.progress = logInv(ms.iso.toDouble(), isoLo, isoHi)
        labelIso.text = "ISO ${ms.iso}"

        val (shLo, shHi) = shutterBounds(l)
        seekShutter.progress = logInv(ms.exposureNs.toDouble(), shLo, shHi)
        labelShutter.text = QualityProfile.formatShutter(ms.exposureNs)

        val ev = QualityProfile.evRange(l.chars)
        seekEv.max = (ev.upper - ev.lower).coerceAtLeast(0)
        seekEv.progress = -ev.lower
        seekEv.isEnabled = ev.upper > ev.lower
        labelEv.text = "EV 0.0"

        seekFocus.progress = 0
        seekFocus.isEnabled = QualityProfile.minFocusDiopters(l.chars) > 0f
        labelFocus.text = "AF"
    }

    private fun bindSeekbars() {
        seekIso.setOnSeekBarChangeListener(onUser { p ->
            val l = current ?: return@onUser
            val (lo, hi) = isoBounds(l)
            val iso = logMap(p, lo, hi).roundToInt()
            ms = ms.copy(iso = iso)
            labelIso.text = "ISO $iso"
            engine.update(ms)
        })
        seekShutter.setOnSeekBarChangeListener(onUser { p ->
            val l = current ?: return@onUser
            val (lo, hi) = shutterBounds(l)
            val ns = logMap(p, lo, hi).toLong()
            ms = ms.copy(exposureNs = ns)
            labelShutter.text = QualityProfile.formatShutter(ns)
            engine.update(ms)
        })
        seekEv.setOnSeekBarChangeListener(onUser { p ->
            val l = current ?: return@onUser
            setEv(p + QualityProfile.evRange(l.chars).lower, false)
        })
        seekFocus.setOnSeekBarChangeListener(onUser { p ->
            val l = current ?: return@onUser
            val minFocus = QualityProfile.minFocusDiopters(l.chars)
            if (p == 0) {
                ms = ms.copy(focusDiopters = null)
                labelFocus.text = "AF"
            } else {
                val d = (p - 1) / 99f * minFocus
                ms = ms.copy(focusDiopters = d)
                labelFocus.text = if (d < 0.05f) "MF ∞" else String.format("MF %.2fm", 1f / d)
            }
            engine.update(ms)
        })
    }

    private fun onUser(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
            if (fromUser) block(p)
        }

        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    // ---------------------------------------------------------------- engine callbacks

    private fun status(text: String, ok: Boolean = true) {
        statusText.removeCallbacks(hideStatus)
        statusText.text = text
        statusText.setTextColor(if (ok) Color.WHITE else 0xFFFF8A80.toInt())
        statusText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        statusText.postDelayed(hideStatus, if (ok) 3000L else 5000L)
    }

    override fun onPreviewSize(width: Int, height: Int) {
        // The activity is locked to portrait, so the sensor's landscape frame is shown rotated.
        preview.setAspectRatio(height, width)
    }

    override fun onLive(text: String) {
        liveText.text = text
    }

    override fun onMessage(text: String, ok: Boolean) {
        status(text, ok)
        if (ok && text.startsWith("Збережено")) thumb.postDelayed({ refreshThumb() }, 400)
    }

    override fun onBusy(busy: Boolean) {
        shooting = busy
        btnShutter.alpha = if (busy) 0.45f else 1f
    }

    override fun onFace(box: RectF?) {
        if (box == null) {
            faceBox.setBox(null)
            return
        }
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        faceBox.setBox(
            RectF(
                preview.left + box.left * w, preview.top + box.top * h,
                preview.left + box.right * w, preview.top + box.bottom * h,
            ),
        )
    }

    private fun revertAutoStack() {
        if (!autoStackActive) return
        autoStackActive = false
        if (isStack()) {
            fmt = 0
            refreshToggles()
            pushModes()
            engine.reconfigure()
        }
    }

    override fun onSmartStack(wanted: Boolean) {
        if (!smartOn || shooting || ms.manualExposure || SystemClock.uptimeMillis() < autoHoldUntil) return
        val cur = current ?: return
        if (wanted && fmt == 0 && !fullRes) {
            fmt = stackFmt(cur)
            autoStackActive = true
            refreshToggles()
            pushModes()
            engine.reconfigure()
            status("Мало світла — авто СТЕК")
        } else if (!wanted && autoStackActive) {
            revertAutoStack()
            status("Достатньо світла — звичайний кадр")
        }
    }

    override fun onTooClose() {
        val cur = current ?: return
        if (cur.front) return
        val own = QualityProfile.minFocusDiopters(cur.chars)
        val closer = lenses
            .filter { !it.front && it !== cur && ratioOf(it) < ratioOf(cur) }
            .maxByOrNull { QualityProfile.minFocusDiopters(it.chars) } ?: return
        if (QualityProfile.minFocusDiopters(closer.chars) <= own * 1.2f) return
        status("Занадто близько — спробуй ${closer.equivMm} мм (фокусується ближче)")
    }

    override fun onModesChanged() {
        if (!engine.stack) autoStackActive = false
        fmt = if (engine.stack) (if (engine.rawStack) 4 else 3) else if (engine.wantRaw) (if (engine.wantJpeg) 2 else 1) else 0
        fullRes = engine.fullRes
        superSample = engine.superSample
        refreshToggles()
    }

    private companion object {
        const val SMART_W = 48
        const val SMART_H = 64
    }
}
