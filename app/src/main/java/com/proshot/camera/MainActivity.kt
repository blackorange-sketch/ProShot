package com.proshot.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), CameraEngine.Listener {

    private lateinit var engine: CameraEngine
    private lateinit var preview: AutoFitTextureView
    private lateinit var lensBar: LinearLayout
    private lateinit var liveText: TextView
    private lateinit var statusText: TextView
    private lateinit var lensInfo: TextView
    private lateinit var btnFormat: TextView
    private lateinit var btnRes: TextView
    private lateinit var btnMode: TextView
    private lateinit var btnWb: TextView
    private lateinit var btnShutter: View
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
    private lateinit var orientationListener: OrientationEventListener

    private var lenses: List<Lens> = emptyList()
    private val chips = mutableListOf<TextView>()
    private var current: Lens? = null
    private var ms = ManualState()
    private var fmt = 0 // 0 = JPEG, 1 = RAW, 2 = JPEG+RAW, 3 = STACK
    private var fullRes = false
    private var surfaceReady = false
    private var opened = false
    private var wbModes: List<Pair<Int, String>> = emptyList()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startUp() else status("Потрібен дозвіл на камеру", false)
    }

    // ---------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        lensBar = findViewById(R.id.lensBar)
        liveText = findViewById(R.id.liveText)
        statusText = findViewById(R.id.statusText)
        lensInfo = findViewById(R.id.lensInfo)
        btnFormat = findViewById(R.id.btnFormat)
        btnRes = findViewById(R.id.btnRes)
        btnMode = findViewById(R.id.btnMode)
        btnWb = findViewById(R.id.btnWb)
        btnShutter = findViewById(R.id.btnShutter)
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

        btnShutter.setOnClickListener { engine.capture() }
        btnFormat.setOnClickListener { cycleFormat() }
        btnRes.setOnClickListener { toggleRes() }
        btnMode.setOnClickListener { toggleMode() }
        btnWb.setOnClickListener { cycleWb() }
        bindSeekbars()
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (preview.isAvailable) {
            surfaceReady = true
            startUp()
        }
    }

    override fun onPause() {
        orientationListener.disable()
        opened = false
        engine.pause()
        super.onPause()
    }

    override fun onDestroy() {
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
            buildLensBar()
            if (lenses.isEmpty()) {
                status("Камери не знайдено", false)
                return
            }
            current = lenses.firstOrNull { !it.front && it.equivMm in 22..44 } ?: lenses.first()
        }
        opened = true
        applyLens(current ?: lenses.first())
    }

    // ---------------------------------------------------------------- lens + toggles

    private fun buildLensBar() {
        lensBar.removeAllViews()
        chips.clear()
        for (l in lenses) {
            val chip = TextView(this).apply {
                text = l.label
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { if (l !== current) applyLens(l) }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            lensBar.addView(chip, lp)
            chips += chip
        }
    }

    private fun applyLens(l: Lens) {
        current = l
        chips.forEachIndexed { i, c -> style(c, lenses[i] === l) }
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
        refreshToggles()
        pushModes()
        engine.update(ms)
        engine.open(l)
    }

    private fun pushModes() {
        engine.stack = fmt == 3
        engine.wantJpeg = fmt != 1
        engine.wantRaw = fmt == 1 || fmt == 2
        engine.fullRes = fullRes && fmt != 3
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
            status("STACK працює у стандартній роздільності — для FULL вимкни STACK", false)
            return
        }
        fullRes = !fullRes
        refreshToggles()
        pushModes()
        engine.reconfigure()
    }

    private fun toggleMode() {
        ms = ms.copy(manualExposure = !ms.manualExposure)
        refreshToggles()
        engine.update(ms)
    }

    private fun cycleWb() {
        val i = wbModes.indexOfFirst { it.first == ms.awbMode }
        ms = ms.copy(awbMode = wbModes[(i + 1) % wbModes.size].first)
        refreshToggles()
        engine.update(ms)
    }

    private fun mp(w: Int, h: Int) = (w.toLong() * h / 1_000_000.0).roundToInt()

    private fun refreshToggles() {
        val l = current
        btnFormat.text = arrayOf("JPEG", "RAW", "JPEG+RAW", "STACK ×8")[fmt]
        style(btnFormat, fmt != 0)

        val std = l?.jpegSize?.let { "${mp(it.width, it.height)}MP" } ?: ""
        val full = l?.maxResJpegSize?.let { "${mp(it.width, it.height)}MP" }
        val fullOn = fullRes && fmt != 3
        btnRes.text = if (fullOn && full != null) "FULL $full" else "STD $std"
        style(btnRes, fullOn)

        btnMode.text = if (ms.manualExposure) "MANUAL" else "AUTO"
        style(btnMode, ms.manualExposure)

        btnWb.text = wbModes.firstOrNull { it.first == ms.awbMode }?.second ?: "WB"
        style(btnWb, ms.awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO)

        rowIso.visibility = if (ms.manualExposure) View.VISIBLE else View.GONE
        rowShutter.visibility = if (ms.manualExposure) View.VISIBLE else View.GONE
        rowEv.visibility = if (ms.manualExposure) View.GONE else View.VISIBLE
    }

    private fun style(v: TextView, on: Boolean) {
        v.setBackgroundColor(if (on) 0xFFFFC107.toInt() else 0xFF333333.toInt())
        v.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        statusText.text = text
        statusText.setTextColor(if (ok) Color.WHITE else 0xFFFF8A80.toInt())
        statusText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onPreviewSize(width: Int, height: Int) {
        // The activity is locked to portrait, so the sensor's landscape frame is shown rotated.
        preview.setAspectRatio(height, width)
    }

    override fun onLive(text: String) {
        liveText.text = text
    }

    override fun onMessage(text: String, ok: Boolean) = status(text, ok)

    override fun onBusy(busy: Boolean) {
        btnShutter.isEnabled = !busy
        btnShutter.alpha = if (busy) 0.4f else 1f
    }

    override fun onModesChanged() {
        fmt = if (engine.stack) 3 else if (engine.wantRaw) (if (engine.wantJpeg) 2 else 1) else 0
        fullRes = engine.fullRes
        refreshToggles()
    }
}
