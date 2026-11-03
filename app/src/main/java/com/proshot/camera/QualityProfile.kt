package com.proshot.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Range

/** User-adjustable shooting state. */
data class ManualState(
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 8_000_000L,
    /** Diopters; null means autofocus. 0 = infinity. */
    val focusDiopters: Float? = null,
    val evSteps: Int = 0,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    /** Tap-to-focus/meter area in sensor coordinates; null means the camera decides. */
    val meter: MeteringRectangle? = null,
    /** Digital zoom relative to the lens' own field of view (1 = none). */
    val zoom: Float = 1f,
    /** AE lock requested by long-press (AF is held by the engine). */
    val aeLock: Boolean = false,
    /** Exposure bias in EV steps chosen by the smart auto mode, added to [evSteps]. */
    val smartEvSteps: Int = 0,
    /** Metering area around the main detected face, used when the user has not tapped. */
    val faceMeter: MeteringRectangle? = null,
    /** Single-shot AF (explicit trigger) instead of continuous AF: used after a tap and before a shot. */
    val afAuto: Boolean = false,
)

/**
 * Picks the highest-quality processing mode each lens actually advertises and writes it into
 * capture requests. Every key is checked against the lens characteristics first, so a mode a
 * HAL does not support is silently skipped instead of crashing the session.
 */
object QualityProfile {

    private fun IntArray?.has(v: Int) = this != null && contains(v)

    private fun <T> CameraCharacteristics.list(key: CameraCharacteristics.Key<T>): T? = get(key)

    /** Settings for the still capture: everything pushed to maximum quality. */
    fun applyStill(
        b: CaptureRequest.Builder,
        c: CameraCharacteristics,
        nrMode: Int = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
    ) {
        b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
        b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
        b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, android.util.Size(0, 0))

        best(c.list(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            nrMode, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            ?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }

        best(c.list(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            CameraMetadata.EDGE_MODE_HIGH_QUALITY, CameraMetadata.EDGE_MODE_FAST)
            ?.let { b.set(CaptureRequest.EDGE_MODE, it) }

        best(c.list(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST)
            ?.let { b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }

        best(c.list(CameraCharacteristics.SHADING_AVAILABLE_MODES),
            CameraMetadata.SHADING_MODE_HIGH_QUALITY, CameraMetadata.SHADING_MODE_FAST)
            ?.let { b.set(CaptureRequest.SHADING_MODE, it) }

        best(c.list(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
            CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY, CameraMetadata.HOT_PIXEL_MODE_FAST)
            ?.let { b.set(CaptureRequest.HOT_PIXEL_MODE, it) }

        best(c.list(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
            CameraMetadata.TONEMAP_MODE_HIGH_QUALITY, CameraMetadata.TONEMAP_MODE_FAST)
            ?.let { b.set(CaptureRequest.TONEMAP_MODE, it) }

        if (c.list(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES).has(
                CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)) {
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        }

        if (c.list(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION).has(
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

        // Needed so the DNG writer receives the lens shading map and hot pixel map.
        if (c.list(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES).has(
                CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }
        if (c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES)?.contains(true) == true) {
            b.set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, true)
        }

        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }
    }

    /**
     * Extra settings for burst frames that will be merged: minimal in-camera denoise (the stack
     * removes noise without smearing detail) and exposure/white balance locked across the burst.
     */
    fun applyStackFrame(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        best(c.list(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            ?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
        if (c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    /** First preferred mode that the lens supports, or null if none is supported. */
    private fun best(available: IntArray?, vararg preferred: Int): Int? =
        preferred.firstOrNull { available.has(it) }

    /** Exposure, white balance and focus, shared by preview and still requests. */
    fun applyManual(b: CaptureRequest.Builder, c: CameraCharacteristics, s: ManualState, forStill: Boolean) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        (s.meter ?: s.faceMeter)?.let { m ->
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(m))
            }
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(m))
            }
        }

        if (s.manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoRange(c).clamp(s.iso))
            val exp = exposureRange(c).clamp(s.exposureNs)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, 33_333_333L))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val ev = evRange(c)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (s.evSteps + s.smartEvSteps).coerceIn(ev.lower, ev.upper))
            if (s.aeLock && c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            }
        }

        cropRegion(c, s.zoom)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }

        if (!forStill && faceDetectAvailable(c)) {
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        }

        if (c.list(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES).has(s.awbMode)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, s.awbMode)
        }

        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (s.focusDiopters != null && minFocus > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters.coerceIn(0f, minFocus))
        } else if (minFocus > 0f) {
            // Continuous-picture keeps a locked AF result through the still capture.
            val mode = when {
                s.afAuto && afModes.has(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                afModes.has(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.has(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                else -> CameraMetadata.CONTROL_AF_MODE_OFF
            }
            b.set(CaptureRequest.CONTROL_AF_MODE, mode)
        }
    }

    /** Centered crop of the active array for digital zoom, or null when no zoom is needed. */
    fun cropRegion(c: CameraCharacteristics, zoom: Float): Rect? {
        if (zoom <= 1.001f) return null
        val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val z = zoom.coerceIn(1f, maxDigitalZoom(c))
        if (z <= 1.001f) return null
        val w = (a.width() / z).toInt() and 1.inv()
        val h = (a.height() / z).toInt() and 1.inv()
        val x = a.left + (a.width() - w) / 2
        val y = a.top + (a.height() - h) / 2
        return Rect(x, y, x + w, y + h)
    }

    fun maxDigitalZoom(c: CameraCharacteristics): Float =
        (c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceIn(1f, 6f)

    fun faceDetectAvailable(c: CameraCharacteristics): Boolean =
        c.list(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
            .has(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) &&
            (c.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0) > 0

    fun isoRange(c: CameraCharacteristics): Range<Int> =
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)

    fun exposureRange(c: CameraCharacteristics): Range<Long> =
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)

    fun minFocusDiopters(c: CameraCharacteristics): Float =
        c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    fun evRange(c: CameraCharacteristics): Range<Int> =
        c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)

    fun evStepSize(c: CameraCharacteristics): Float =
        c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f

    private fun Range<Int>.clamp(v: Int) = v.coerceIn(lower, upper)
    private fun Range<Long>.clamp(v: Long) = v.coerceIn(lower, upper)

    fun formatShutter(ns: Long): String {
        val sec = ns / 1_000_000_000.0
        return if (sec >= 0.5) String.format("%.1fs", sec) else "1/${Math.round(1.0 / sec)}"
    }
}
