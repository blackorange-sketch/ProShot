package com.proshot.detail

/** Everything the finishing pass can do; the defaults are conservative. */
data class PostParams(
    val sharpenAmount: Float = 0.6f,
    val sharpenRadius: Float = 1.2f,
    val sharpenThreshold: Int = 3,
    val cornerBoost: Float = 0f,
    val chromaDenoise: Float = 0.4f,
    /** Wide, gentle local-contrast pass that makes fine texture and small print "pop" (0 = off). */
    val clarity: Float = 0f,
    val clarityRadius: Float = 4f,
    val vignette: Float = 0f,
    /** Set only when the HAL does not already correct distortion for this lens. */
    val lens: RadialLens? = null,
)

/** Runs optics fixes, colour denoise and sharpening on a stacked frame, in that order. */
object Finisher {

    fun run(frame: YuvFrame, p: PostParams): YuvFrame {
        var f = frame
        p.lens?.let { f = Optics.undistort(f, it) }
        if (p.vignette > 0f) Optics.devignette(f.y, f.width, f.height, p.vignette)
        if (p.chromaDenoise > 0f) {
            val (u, v) = ChromaDenoise.run(f, p.chromaDenoise)
            f = YuvFrame(f.width, f.height, f.y, u, v)
        }
        Sharpen.unsharp(f.y, f.width, f.height, p.sharpenAmount, p.sharpenRadius, p.sharpenThreshold, p.cornerBoost)
        if (p.clarity > 0f) Sharpen.unsharp(f.y, f.width, f.height, p.clarity, p.clarityRadius, 6, 0f)
        return f
    }
}
