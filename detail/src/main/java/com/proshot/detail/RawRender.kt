package com.proshot.detail

import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Everything needed to turn a merged RAW frame into a display-referred picture. */
class RawRenderParams(
    /** White balance gain per channel in RGGB order (R, green-on-red-row, green-on-blue-row, B). */
    val gains: FloatArray,
    /** Row-major 3x3: white-balanced camera RGB to linear sRGB. */
    val matrix: FloatArray,
    /** Lens shading gain map, 4 channels (RGGB order) of rows x cols values, or null. */
    val shade: Array<FloatArray>? = null,
    val shadeCols: Int = 0,
    val shadeRows: Int = 0,
    /** Output window inside the RAW frame; must start on even coordinates. */
    val cropX: Int = 0,
    val cropY: Int = 0,
    val outW: Int,
    val outH: Int,
    /** Exposure compensation (user plus smart auto) the capture was metered with, in EV. */
    val biasEv: Float = 0f,
    /** Fixed brightness gain; null lets the renderer aim the median at mid grey. */
    val gainOverride: Float? = null,
)

object RawRender {

    private const val LUT_MAX = 4f
    private const val LUT_SIZE = 16384
    private val lut: FloatArray = FloatArray(LUT_SIZE + 1) { i ->
        val x = i.toFloat() / LUT_SIZE * LUT_MAX
        // Soft shoulder into white, sRGB curve, then a mild S-curve for contrast.
        val hl = if (x <= 0.75f) x else 0.75f + 0.25f * kotlin.math.tanh((x - 0.75f) / 0.25f)
        val v = if (hl <= 0.0031308f) 12.92f * hl else 1.055f * hl.pow(1f / 2.4f) - 0.055f
        (v - 0.10f * sin(2.0 * PI * v).toFloat() / (2f * PI.toFloat())).coerceIn(0f, 1f)
    }

    /** [merged] holds black-subtracted raw values (0 .. [RawMeta.range]) as returned by [RawStacker.result]. */
    fun render(merged: FloatArray, m: RawMeta, p: RawRenderParams): YuvFrame {
        val w = m.width
        val h = m.height
        linearize(merged, m, p)

        val gain = p.gainOverride ?: autoGain(merged, m, p)
        val outW = p.outW and 1.inv()
        val outH = p.outH and 1.inv()
        val y = ByteArray(outW * outH)
        val u = ByteArray(outW / 2 * (outH / 2))
        val v = ByteArray(outW / 2 * (outH / 2))
        val mat = p.matrix
        val chan = IntArray(4) { m.channelAt(it and 1, it shr 1) }
        // Class of each channel: 0 = R, 1 = G, 2 = B.
        val cls = intArrayOf(0, 1, 1, 2)

        IntStream.range(0, outH / 2).parallel().forEach { by ->
            val rgb = FloatArray(3)
            for (bx in 0 until outW / 2) {
                var su = 0f
                var sv = 0f
                for (k in 0..3) {
                    val ox = bx * 2 + (k and 1)
                    val oy = by * 2 + (k shr 1)
                    val rx = p.cropX + ox
                    val ry = p.cropY + oy
                    demosaic(merged, w, h, rx, ry, cls[chan[((ry and 1) shl 1) or (rx and 1)]],
                        cls[chan[((ry and 1) shl 1) or ((rx + 1) and 1)]], rgb)
                    val r0 = rgb[0]
                    val g0 = rgb[1]
                    val b0 = rgb[2]
                    val r = tone((mat[0] * r0 + mat[1] * g0 + mat[2] * b0) * gain)
                    val g = tone((mat[3] * r0 + mat[4] * g0 + mat[5] * b0) * gain)
                    val b = tone((mat[6] * r0 + mat[7] * g0 + mat[8] * b0) * gain)
                    val yy = 0.299f * r + 0.587f * g + 0.114f * b
                    y[oy * outW + ox] = (yy * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                    su += -0.168736f * r - 0.331264f * g + 0.5f * b
                    sv += 0.5f * r - 0.418688f * g - 0.081312f * b
                }
                val ci = by * (outW / 2) + bx
                u[ci] = (su * 0.25f * 255f + 128f + 0.5f).toInt().coerceIn(0, 255).toByte()
                v[ci] = (sv * 0.25f * 255f + 128f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return YuvFrame(outW, outH, y, u, v)
    }

    private fun tone(x: Float): Float {
        if (x <= 0f) return 0f
        val f = x / LUT_MAX * LUT_SIZE
        if (f >= LUT_SIZE) return lut[LUT_SIZE]
        val i = f.toInt()
        val t = f - i
        return lut[i] * (1f - t) + lut[i + 1] * t
    }

    /** Normalises to 0..1, applies lens shading and white balance gains, clips. In place. */
    private fun linearize(a: FloatArray, m: RawMeta, p: RawRenderParams) {
        val w = m.width
        val h = m.height
        val inv = 1f / m.range
        val shade = p.shade
        IntStream.range(0, h).parallel().forEach { y ->
            val fy = if (shade != null && h > 1) y.toFloat() / (h - 1) * (p.shadeRows - 1) else 0f
            val y0 = fy.toInt().coerceAtMost(maxOf(0, p.shadeRows - 2))
            val ty = fy - y0
            for (x in 0 until w) {
                val ch = m.channelAt(x, y)
                var g = p.gains[ch]
                if (shade != null) {
                    val fx = x.toFloat() / (w - 1) * (p.shadeCols - 1)
                    val x0 = fx.toInt().coerceAtMost(maxOf(0, p.shadeCols - 2))
                    val tx = fx - x0
                    val s = shade[ch]
                    val c = p.shadeCols
                    val x1 = minOf(x0 + 1, c - 1)
                    val y1 = minOf(y0 + 1, p.shadeRows - 1)
                    val top = s[y0 * c + x0] * (1 - tx) + s[y0 * c + x1] * tx
                    val bot = s[y1 * c + x0] * (1 - tx) + s[y1 * c + x1] * tx
                    g *= top * (1 - ty) + bot * ty
                }
                val i = y * w + x
                a[i] = (a[i] * inv).coerceIn(0f, 1f).let { (it * g).coerceIn(0f, 1f) }
            }
        }
    }

    /** Brightness gain that puts the median of the green samples where the metered exposure intends it. */
    private fun autoGain(a: FloatArray, m: RawMeta, p: RawRenderParams): Float {
        val w = m.width
        val h = m.height
        val hist = IntArray(1024)
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (m.channelAt(x, y).let { it == 1 || it == 2 }) {
                    hist[(a[y * w + x] * 1023f).toInt().coerceIn(0, 1023)]++
                    n++
                }
                x += 7
            }
            y += 5
        }
        var acc = 0
        var median = 0
        for (i in 0 until 1024) {
            acc += hist[i]
            if (acc * 2 >= n) {
                median = i
                break
            }
        }
        val med = (median + 0.5f) / 1023f
        val target = 0.18f * 2f.pow(p.biasEv)
        return (target / med).coerceIn(0.9f, 4f)
    }

    /**
     * Malvar-He-Cutler 5x5 demosaic for one pixel. [cls] is the colour sample at (x, y) (0 R, 1 G, 2 B)
     * and [rightCls] the class of its right-hand neighbour, which tells which kind of green it is.
     */
    private fun demosaic(a: FloatArray, w: Int, h: Int, x: Int, y: Int, cls: Int, rightCls: Int, out: FloatArray) {
        val interior = x >= 2 && y >= 2 && x < w - 2 && y < h - 2
        fun p(dx: Int, dy: Int): Float {
            val xx = if (interior) x + dx else (x + dx).coerceIn(0, w - 1)
            val yy = if (interior) y + dy else (y + dy).coerceIn(0, h - 1)
            return a[yy * w + xx]
        }
        val c = p(0, 0)
        val n1 = p(0, -1); val s1 = p(0, 1); val w1 = p(-1, 0); val e1 = p(1, 0)
        val n2 = p(0, -2); val s2 = p(0, 2); val w2 = p(-2, 0); val e2 = p(2, 0)
        val diag = p(-1, -1) + p(1, -1) + p(-1, 1) + p(1, 1)
        when (cls) {
            1 -> {
                // Green sample: horizontal neighbours are red on a red row, blue on a blue row.
                val h5 = (5f * c + 4f * (w1 + e1) - (w2 + e2) + 0.5f * (n2 + s2) - diag) * 0.125f
                val v5 = (5f * c + 4f * (n1 + s1) - (n2 + s2) + 0.5f * (w2 + e2) - diag) * 0.125f
                out[1] = c
                if (rightCls == 0) { out[0] = h5; out[2] = v5 } else { out[2] = h5; out[0] = v5 }
            }
            else -> {
                val g = (4f * c + 2f * (n1 + s1 + w1 + e1) - (n2 + s2 + w2 + e2)) * 0.125f
                val other = (6f * c + 2f * diag - 1.5f * (n2 + s2 + w2 + e2)) * 0.125f
                out[1] = g
                if (cls == 0) { out[0] = c; out[2] = other } else { out[2] = c; out[0] = other }
            }
        }
        out[0] = out[0].coerceIn(0f, 1f)
        out[1] = out[1].coerceIn(0f, 1f)
        out[2] = out[2].coerceIn(0f, 1f)
    }
}
