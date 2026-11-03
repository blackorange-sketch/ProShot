package com.proshot.detail

import java.util.stream.IntStream

/**
 * Radial lens model in focal-length-normalised coordinates, in the pixels of the frame it is
 * applied to: a point at normalised radius r (ideal, undistorted) lands at r * (1 + k1 r^2 + k2 r^4
 * + k3 r^6) in the captured image. [fx], [fy] are focal lengths in pixels, ([cx], [cy]) the
 * principal point in pixels.
 */
class RadialLens(
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val k1: Float, val k2: Float, val k3: Float,
) {
    /** Scales a model calibrated for a [fromW]x[fromH] array to a [toW]x[toH] frame. */
    fun scaled(fromW: Int, fromH: Int, toW: Int, toH: Int): RadialLens {
        val sx = toW.toFloat() / fromW
        val sy = toH.toFloat() / fromH
        return RadialLens(fx * sx, fy * sy, cx * sx, cy * sy, k1, k2, k3)
    }
}

/** Geometric and shading fixes for lenses whose HAL leaves the raw optics uncorrected. */
object Optics {

    /** Resamples all planes so that straight lines in the scene are straight in the result. */
    fun undistort(f: YuvFrame, lens: RadialLens): YuvFrame {
        val w = f.width
        val h = f.height
        val y = ByteArray(w * h)
        remap(f.y, y, w, h, lens)
        val cw = w / 2
        val ch = h / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        remap(f.u, u, cw, ch, lens.scaled(w, h, cw, ch))
        remap(f.v, v, cw, ch, lens.scaled(w, h, cw, ch))
        return YuvFrame(w, h, y, u, v)
    }

    private fun remap(src: ByteArray, dst: ByteArray, w: Int, h: Int, l: RadialLens) {
        IntStream.range(0, h).parallel().forEach { py ->
            val yn = (py - l.cy) / l.fy
            for (px in 0 until w) {
                val xn = (px - l.cx) / l.fx
                val r2 = xn * xn + yn * yn
                val s = 1f + r2 * (l.k1 + r2 * (l.k2 + r2 * l.k3))
                val sx = xn * s * l.fx + l.cx
                val sy = yn * s * l.fy + l.cy
                dst[py * w + px] = sample(src, w, h, sx, sy)
            }
        }
    }

    private fun sample(src: ByteArray, w: Int, h: Int, x: Float, y: Float): Byte {
        val xc = x.coerceIn(0f, (w - 1).toFloat())
        val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt().coerceAtMost(w - 2)
        val y0 = yc.toInt().coerceAtMost(h - 2)
        val fx = xc - x0
        val fy = yc - y0
        val a = src[y0 * w + x0].toInt() and 0xFF
        val b = src[y0 * w + x0 + 1].toInt() and 0xFF
        val c = src[(y0 + 1) * w + x0].toInt() and 0xFF
        val d = src[(y0 + 1) * w + x0 + 1].toInt() and 0xFF
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return (top + (bot - top) * fy + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    /** Brightens the corners: gain = 1 + [strength] * r^2 with r = 0 at the centre and 1 in the corners. */
    fun devignette(y: ByteArray, w: Int, h: Int, strength: Float) {
        if (strength <= 0f) return
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val inv = 1f / (cx * cx + cy * cy)
        IntStream.range(0, h).parallel().forEach { row ->
            val dy = row - cy
            for (x in 0 until w) {
                val dx = x - cx
                val g = 1f + strength * (dx * dx + dy * dy) * inv
                val i = row * w + x
                y[i] = ((y[i].toInt() and 0xFF) * g + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
    }
}
