package com.proshot.detail

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Luma-only unsharp mask with a noise threshold and optional extra gain towards the corners. */
object Sharpen {

    /**
     * Sharpens [y] in place. [radius] is the blur size in pixels, [threshold] (0..255) leaves
     * noise-level differences alone, [cornerBoost] adds gain at the frame corners where wide
     * lenses are softer (0 = uniform, 1 = double gain in the corners).
     */
    fun unsharp(y: ByteArray, w: Int, h: Int, amount: Float, radius: Float, threshold: Int, cornerBoost: Float) {
        if (amount <= 0f) return
        val r = max(1, ceil(radius).toInt())
        val blur = boxBlur(boxBlur(y, w, h, r), w, h, r)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val inv = 1f / (cx * cx + cy * cy)
        val thr = threshold.coerceAtLeast(0).toFloat()
        IntStream.range(0, h).parallel().forEach { row ->
            val dy = row - cy
            val o = row * w
            for (x in 0 until w) {
                val dx = x - cx
                val gain = amount * (1f + cornerBoost * (dx * dx + dy * dy) * inv)
                val v = (y[o + x].toInt() and 0xFF)
                var d = (v - (blur[o + x].toInt() and 0xFF)).toFloat()
                if (thr > 0f && abs(d) < thr) d *= abs(d) / thr
                val out = v + gain * d
                y[o + x] = (if (out < 0f) 0 else if (out > 255f) 255 else (out + 0.5f).toInt()).toByte()
            }
        }
    }

    /** Separable box blur with edge clamping; two passes of this give a triangle (near-Gaussian) kernel. */
    internal fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val n = 2 * r + 1
        val tmp = ByteArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)].toInt() and 0xFF
            for (x in 0 until w) {
                tmp[row + x] = ((sum + n / 2) / n).toByte()
                sum += (src[row + (x + r + 1).coerceAtMost(w - 1)].toInt() and 0xFF) -
                    (src[row + (x - r).coerceAtLeast(0)].toInt() and 0xFF)
            }
        }
        val out = ByteArray(w * h)
        val strip = 64
        val strips = (w + strip - 1) / strip
        IntStream.range(0, strips).parallel().forEach { s ->
            val x0 = s * strip
            val sw = minOf(strip, w - x0)
            val sum = IntArray(sw)
            for (i in -r..r) {
                val o = i.coerceIn(0, h - 1) * w + x0
                for (k in 0 until sw) sum[k] += tmp[o + k].toInt() and 0xFF
            }
            for (y in 0 until h) {
                val o = y * w + x0
                val add = (y + r + 1).coerceAtMost(h - 1) * w + x0
                val sub = (y - r).coerceAtLeast(0) * w + x0
                for (k in 0 until sw) {
                    out[o + k] = ((sum[k] + n / 2) / n).toByte()
                    sum[k] += (tmp[add + k].toInt() and 0xFF) - (tmp[sub + k].toInt() and 0xFF)
                }
            }
        }
        return out
    }
}
