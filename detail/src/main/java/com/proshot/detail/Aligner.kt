package com.proshot.detail

import java.util.stream.IntStream
import kotlin.math.abs

/**
 * Global-translation alignment on a luma pyramid (8x, 4x, 2x, 1x) with whole-pixel accuracy.
 * The reference pyramid is built once and reused for every following frame.
 */
class Aligner(ref: ByteArray, private val w: Int, private val h: Int) {

    private val factors = intArrayOf(8, 4, 2, 1)
    private val refLevels: List<ByteArray> = factors.map { downscale(ref, w, h, it) }

    /** Returns (dx, dy) such that cur[x + dx, y + dy] matches ref[x, y]. */
    fun estimate(cur: ByteArray): IntArray {
        var dx = 0
        var dy = 0
        for ((i, f) in factors.withIndex()) {
            val lw = w / f
            val lh = h / f
            val c = downscale(cur, w, h, f)
            val r = refLevels[i]
            val radius = if (i == 0) 6 else 2
            val cx = dx / f
            val cy = dy / f
            val limit = MAX_SHIFT / f
            val inset = limit + 2
            var best = Long.MAX_VALUE
            var bx = cx
            var by = cy
            for (sy in (cy - radius)..(cy + radius)) {
                if (sy < -limit || sy > limit) continue
                for (sx in (cx - radius)..(cx + radius)) {
                    if (sx < -limit || sx > limit) continue
                    val s = sad(r, c, lw, lh, sx, sy, inset)
                    if (s < best) {
                        best = s
                        bx = sx
                        by = sy
                    }
                }
            }
            dx = bx * f
            dy = by * f
        }
        return intArrayOf(dx, dy)
    }

    private fun sad(r: ByteArray, c: ByteArray, w: Int, h: Int, sx: Int, sy: Int, inset: Int): Long {
        val step = if (w > 1500) 3 else 1
        var sum = 0L
        var y = inset
        while (y < h - inset) {
            val ro = y * w
            val co = (y + sy) * w + sx
            var x = inset
            while (x < w - inset) {
                sum += abs((r[ro + x].toInt() and 0xFF) - (c[co + x].toInt() and 0xFF))
                x += step
            }
            y += step
        }
        return sum
    }

    companion object {
        /** Largest shift, in full-resolution pixels, that the aligner will report. */
        const val MAX_SHIFT = 64

        fun downscale(src: ByteArray, w: Int, h: Int, f: Int): ByteArray {
            if (f == 1) return src
            val lw = w / f
            val lh = h / f
            val out = ByteArray(lw * lh)
            IntStream.range(0, lh).parallel().forEach { y ->
                for (x in 0 until lw) {
                    var s = 0
                    for (j in 0 until f) {
                        val row = (y * f + j) * w + x * f
                        for (i in 0 until f) s += src[row + i].toInt() and 0xFF
                    }
                    out[y * lw + x] = (s / (f * f)).toByte()
                }
            }
            return out
        }
    }
}
