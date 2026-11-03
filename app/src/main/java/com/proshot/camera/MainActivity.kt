package com.proshot.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), CameraEngine.Listener {

    private lateinit var engine: CameraEngine
    private lateinit var preview: AutoFitTextureView
    private lateinit var grid: GridView
    private lateinit var focusRing: FocusRingView
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
    private var fmt = 0 // 0 = JPEG, 1 = RAW, 2 = JPEG+RAW, 3 = STACK
    private var fullRes = false
    private var gridOn = false
    private var panelOpen = false
    private var shooting = false
    private var surfaceReady = false
    private var opened = false
    private var lastUri: Uri? = null
    private var wbModes: List<Pair<Int, String>> = emptyList()

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
                    engine.deviceOrientation = ((o + 45) / 90 * 90) % 360
                }
            }
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapFocus(e.x, e.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 800f) {
                    switchMode(if (vx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        preview.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        btnShutter.setOnClickListener { shutter() }
        btnFlip.setOnClickListener { flipCamera() }
        thumb.setOnClickListener { openGallery() }
        chipFormat.setOnClickListener { cycleFormat() }
        chipRes.setOnClickListener { toggleRes() }
        chipWb.setOnClickListener { cycleWb() }
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
    }

    override fun onPause() {
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
        }
        opened = true
        applyLens(current ?: lenses.first())
    }

    // ---------------------------------------------------------------- lenses

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLensBar() {
        lensBar.removeAllViews()
        val cur = current ?: return
        for (l in lenses.filter { it.front == cur.front }) {
            val sel = l === cur
            val num = if (l.equivMm > 0) l.equivMm.toString() else "?"
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                text = if (sel) "$num\nmm" else num
                textSize = if (sel) 12f else 10f
                setLineSpacing(0f, 0.85f)
                setTextColor(if (sel) Color.WHITE else 0xFFDDDDDD.toInt())
                setBackgroundResource(if (sel) R.drawable.lens_selected else R.drawable.lens_dot)
                setOnClickListener { if (!sel) applyLens(l) }
            }
            val size = dp(if (sel) 48 else 36)
            lensBar.addView(tv, LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            })
        }
    }

    private fun applyLens(l: Lens) {
        current = l
        if (!l.front) lastBack = l
        lensInfo.text = LensCatalog.describe(l)

        if (!l.supportsRaw && (fmt == 1 || fmt == 2)) fmt = 0
        if (!l.supportsMaxRes) fullRes = false

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
        )
        configureSeekbars(l)
        buildLensBar()
        refreshToggles()
        pushModes()
        engine.update(ms)
        engine.open(l)
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
        engine.stack = fmt == 3
        engine.wantJpeg = fmt != 1
        engine.wantRaw = fmt == 1 || fmt == 2
        engine.fullRes = fullRes && fmt != 3
    }

    private fun currentMode() = when {
        ms.manualExposure -> 3
        fmt == 3 -> 1
        fmt == 1 || fmt == 2 -> 2
        else -> 0
    }

    private fun switchMode(step: Int) {
        applyMode((currentMode() + step).coerceIn(0, modes.size - 1))
    }

    private fun applyMode(i: Int) {
        val l = current ?: return
        val before = fmt
        when (i) {
            0 -> {
                ms = ms.copy(manualExposure = false)
                fmt = 0
            }
            1 -> {
                ms = ms.copy(manualExposure = false)
                fmt = 3
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
                if (l.supportsRaw && (fmt == 0 || fmt == 3)) fmt = 2
            }
        }
        refreshToggles()
        pushModes()
        engine.update(ms)
        if (fmt != before) engine.reconfigure()
    }

    private fun cycleFormat() {
        val l = current ?: return
        val order = if (l.supportsRaw) listOf(0, 1, 2, 3) else listOf(0, 3)
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
        if (fmt == 3) {
            status("СТЕК працює у стандартній роздільності — для FULL вимкни СТЕК", false)
            return
        }
        fullRes = !fullRes
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

        val stackN = l?.let { LensProfiles.forLens(it).stackFrames } ?: 8
        chipFormat.text = arrayOf("JPEG", "RAW", "JPEG+RAW", "STACK ×$stackN")[fmt]
        chip(chipFormat, fmt != 0)

        val std = l?.jpegSize?.let { "${mp(it.width, it.height)}MP" } ?: ""
        val full = l?.maxResJpegSize?.let { "${mp(it.width, it.height)}MP" }
        val fullOn = fullRes && fmt != 3
        chipRes.text = if (fullOn && full != null) "FULL $full" else "STD $std"
        chip(chipRes, fullOn)

        chipWb.text = wbModes.firstOrNull { it.first == ms.awbMode }?.second ?: "WB"
        chip(chipWb, ms.awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO)
        chip(chipGrid, gridOn)

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

    private fun tapFocus(x: Float, y: Float) {
        if (preview.width == 0 || preview.height == 0) return
        engine.focusAt(x / preview.width, y / preview.height)
        focusRing.show(preview.left + x, preview.top + y)
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
            val steps = p + QualityProfile.evRange(l.chars).lower
            ms = ms.copy(evSteps = steps)
            labelEv.text = String.format("EV %+.1f", steps * QualityProfile.evStepSize(l.chars))
            engine.update(ms)
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

    override fun onModesChanged() {
        fmt = if (engine.stack) 3 else if (engine.wantRaw) (if (engine.wantJpeg) 2 else 1) else 0
        fullRes = engine.fullRes
        refreshToggles()
    }
}
