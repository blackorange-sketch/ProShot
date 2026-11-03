package com.proshot.detail

import java.util.stream.IntStream
import kotlin.math.roundToInt

/**
 * Merges a burst into the first frame. Each extra frame is aligned, then averaged in per pixel
 * with a weight that falls to zero where it disagrees with the reference (moving subjects,
 * misalignment), so ghosts are rejected while static areas lose noise by roughly sqrt(N).
 */
class Stacker(private val ref: YuvFrame, sigma: Float) {

    private val w = ref.width
    private val h = ref.height
    private val cw = w / 2
    private val ch = h / 2
    private val k2 = (4f * sigma).let { it * it }
    private val aligner = Aligner(ref.y, w, h)

    private val sumY = FloatArray(w * h)
    private val wgtY = FloatArray(w * h)
    private val sumU = FloatArray(cw * ch)
    private val sumV = FloatArray(cw * ch)
    private val wgtC = FloatArray(cw * ch)

    var frames = 1
        private set

    init {
        for (i in sumY.indices) {
            sumY[i] = (ref.y[i].toInt() and 0xFF).toFloat()
            wgtY[i] = 1f
        }
        for (i in sumU.indices) {
            sumU[i] = (ref.u[i].toInt() and 0xFF).toFloat()
            sumV[i] = (ref.v[i].toInt() and 0xFF).toFloat()
            wgtC[i] = 1f
        }
    }

    /** Aligns and accumulates [f]; returns the (dx, dy) shift that was found. */
    fun add(f: YuvFrame): IntArray {
        require(f.width == w && f.height == h) { "frame size mismatch" }
        val s = aligner.estimate(f.y)
        val dx = s[0]
        val dy = s[1]

        IntStream.range(0, h).parallel().forEach { y ->
            val sy = y + dy
            if (sy in 0 until h) {
                val ro = y * w
                val co = sy * w
                for (x in 0 until w) {
                    val sx = x + dx
                    if (sx < 0 || sx >= w) continue
                    val rv = ref.y[ro + x].toInt() and 0xFF
                    val cv = f.y[co + sx].toInt() and 0xFF
                    val d = (rv - cv).toFloat()
                    val wt = 1f - d * d / k2
                    if (wt > 0f) {
                        sumY[ro + x] += cv * wt
                        wgtY[ro + x] += wt
                    }
                }
            }
        }

        val cdx = dx shr 1
        val cdy = dy shr 1
        IntStream.range(0, ch).parallel().forEach { y ->
            val sy = y + cdy
            if (sy in 0 until ch) {
                for (x in 0 until cw) {
                    val sx = x + cdx
                    if (sx < 0 || sx >= cw) continue
                    // Chroma takes its weight from the co-located luma pixel.
                    val ly = 2 * y
                    val lx = 2 * x
                    val lsy = ly + dy
                    val lsx = lx + dx
                    if (lsy < 0 || lsy >= h || lsx < 0 || lsx >= w) continue
                    val d = ((ref.y[ly * w + lx].toInt() and 0xFF) -
                        (f.y[lsy * w + lsx].toInt() and 0xFF)).toFloat()
                    val wt = 1f - d * d / k2
                    if (wt > 0f) {
                        val i = y * cw + x
                        val j = sy * cw + sx
                        sumU[i] += (f.u[j].toInt() and 0xFF) * wt
                        sumV[i] += (f.v[j].toInt() and 0xFF) * wt
                        wgtC[i] += wt
                    }
                }
            }
        }
        frames++
        return s
    }

    fun result(): YuvFrame {
        val y = ByteArray(w * h)
        for (i in y.indices) y[i] = (sumY[i] / wgtY[i]).roundToInt().coerceIn(0, 255).toByte()
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        for (i in u.indices) {
            u[i] = (sumU[i] / wgtC[i]).roundToInt().coerceIn(0, 255).toByte()
            v[i] = (sumV[i] / wgtC[i]).roundToInt().coerceIn(0, 255).toByte()
        }
        return YuvFrame(w, h, y, u, v)
    }
}
