package com.proshot.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import com.proshot.detail.PostParams
import com.proshot.detail.RadialLens
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tuning for one physical camera of the Z50S Pro. The numbers are starting points chosen from the
 * sensor class (large clean 1/1.49" main, small noisy tele and front, soft-cornered ultra-wide);
 * adjust them after real test shots.
 */
data class LensProfile(
    val name: String,
    /** Burst length in STACK mode: noisier sensors get more frames. */
    val stackFrames: Int,
    /** Burst length in RAW-STACK mode (RAW frames are cheap to merge but heavy to hold, so they run in small batches). */
    val rawStackFrames: Int,
    /** In-camera denoise strength for single-frame stills (NOISE_REDUCTION_MODE_*). */
    val stillNoiseReduction: Int,
    /** Finishing pass applied after stacking. */
    val post: PostParams,
    /** Vignetting gain to apply only when the HAL does not do shading correction itself. */
    val vignetteIfUncorrected: Float = 0f,
    /** Extra exposure compensation (EV) for single-frame and stacked stills, to match the stock camera's brightness. */
    val stillEvBias: Float = 0f,
)

object LensProfiles {

    private val MAIN = LensProfile(
        name = "Main 35mm · IMX800",
        stackFrames = 6,
        rawStackFrames = 12,
        stillNoiseReduction = CameraMetadata.NOISE_REDUCTION_MODE_FAST,
        post = PostParams(sharpenAmount = 0.9f, sharpenRadius = 1.0f, sharpenThreshold = 3, cornerBoost = 0.2f, chromaDenoise = 0.3f, clarity = 0.25f),
    )

    private val ULTRAWIDE = LensProfile(
        name = "Ultra-wide 13mm · OV50D",
        stackFrames = 8,
        rawStackFrames = 12,
        stillNoiseReduction = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        post = PostParams(sharpenAmount = 0.75f, sharpenRadius = 1.0f, sharpenThreshold = 5, cornerBoost = 0.9f, chromaDenoise = 0.45f, clarity = 0.15f),
        vignetteIfUncorrected = 0.25f,
    )

    private val TELE = LensProfile(
        name = "Tele 80mm · Hi-847",
        stackFrames = 10,
        rawStackFrames = 14,
        stillNoiseReduction = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        post = PostParams(sharpenAmount = 0.9f, sharpenRadius = 1.2f, sharpenThreshold = 4, cornerBoost = 0.1f, chromaDenoise = 0.6f, clarity = 0.2f),
        stillEvBias = 0.4f,
    )

    private val FRONT = LensProfile(
        name = "Front · OV16A",
        stackFrames = 8,
        rawStackFrames = 8,
        stillNoiseReduction = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        post = PostParams(sharpenAmount = 0.55f, sharpenRadius = 1.3f, sharpenThreshold = 4, cornerBoost = 0.3f, chromaDenoise = 0.5f),
    )

    fun forLens(l: Lens): LensProfile = when {
        l.front -> FRONT
        l.equivMm in 65..120 -> TELE
        l.equivMm in 8..17 -> ULTRAWIDE
        else -> MAIN
    }

    /**
     * Post-processing parameters for [lens], with the optics fixes switched on only where the HAL
     * leaves them undone: vignetting when there is no shading correction, distortion when there is
     * no distortion correction but the lens reports a calibration.
     */
    fun postFor(lens: Lens, frameW: Int, frameH: Int): Pair<PostParams, Boolean> {
        val p = forLens(lens)
        val c = lens.chars
        var optics = false

        var vignette = 0f
        val shading = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
        val halShading = shading != null && (shading.contains(CameraMetadata.SHADING_MODE_FAST) ||
            shading.contains(CameraMetadata.SHADING_MODE_HIGH_QUALITY))
        if (!halShading && p.vignetteIfUncorrected > 0f) {
            vignette = p.vignetteIfUncorrected
            optics = true
        }

        val model = distortionModel(c, frameW, frameH)
        if (model != null) optics = true
        return p.post.copy(vignette = vignette, lens = model) to optics
    }

    /** Radial model from the lens calibration, or null when the HAL already corrects or nothing is reported. */
    private fun distortionModel(c: CameraCharacteristics, w: Int, h: Int): RadialLens? {
        if (Build.VERSION.SDK_INT < 28) return null
        val modes = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
        if (modes != null && (modes.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_FAST) ||
                modes.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY))) return null

        val d = c.get(CameraCharacteristics.LENS_DISTORTION) ?: return null
        val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: return null
        val arr = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        if (d.size != 5 || k.size < 4 || d[0] == 0f) return null

        val sx = w.toFloat() / arr.width()
        val sy = h.toFloat() / arr.height()
        val lens = RadialLens(
            fx = k[0] * sx, fy = k[1] * sy,
            cx = (k[2] - arr.left) * sx, cy = (k[3] - arr.top) * sy,
            k1 = d[1] / d[0], k2 = d[2] / d[0], k3 = 0f,
        )

        // Sanity guard: a plausible correction moves the corner by a few percent of the width at most.
        val rc = hypot(w / 2f / lens.fx, h / 2f / lens.fy)
        val r2 = rc * rc
        val shift = abs(r2 * (lens.k1 + r2 * lens.k2)) * hypot(w / 2f, h / 2f)
        if (shift < 0.002f * w || shift > 0.06f * w) return null
        return lens
    }
}
