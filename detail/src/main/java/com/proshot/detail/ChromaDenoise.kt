package com.proshot.detail

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp

/**
 * Edge-preserving colour-noise removal. A 5x5 bilateral filter on U and V whose weights come from
 * both the chroma difference and the difference of the co-located luma, so colour does not bleed
 * across edges. Small sensors (tele, front) have a lot of chroma noise and benefit the most.
 */
object ChromaDenoise {

    /** [strength] 0 = off, 1 = strong. Returns new U and V planes. */
    fun run(f: YuvFrame, strength: Float): Pair<ByteArray, ByteArray> {
        if (strength <= 0f) return f.u to f.v
        val w = f.width
        val h = f.height
        val cw = w / 2
        val ch = h / 2
        val sc = 3f + 22f * strength
        val sl = 12f + 30f * strength
        val lutC = FloatArray(256) { exp(-(it * it) / (2f * sc * sc)) }
        val lutL = FloatArray(256) { exp(-(it * it) / (2f * sl * sl)) }
        val outU = ByteArray(cw * ch)
        val outV = ByteArray(cw * ch)
        IntStream.range(0, ch).parallel().forEach { y ->
            for (x in 0 until cw) {
                val i = y * cw + x
                val cu = f.u[i].toInt() and 0xFF
                val cv = f.v[i].toInt() and 0xFF
                val cl = f.y[(2 * y) * w + 2 * x].toInt() and 0xFF
                var su = 0f
                var sv = 0f
                var sw = 0f
                for (j in -2..2) {
                    val yy = (y + j).coerceIn(0, ch - 1)
                    for (k in -2..2) {
                        val xx = (x + k).coerceIn(0, cw - 1)
                        val n = yy * cw + xx
                        val nu = f.u[n].toInt() and 0xFF
                        val nv = f.v[n].toInt() and 0xFF
                        val nl = f.y[(2 * yy) * w + 2 * xx].toInt() and 0xFF
                        val wt = lutC[(abs(nu - cu) + abs(nv - cv)).coerceAtMost(255)] * lutL[abs(nl - cl)]
                        su += nu * wt
                        sv += nv * wt
                        sw += wt
                    }
                }
                outU[i] = (su / sw + 0.5f).toInt().coerceIn(0, 255).toByte()
                outV[i] = (sv / sw + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return outU to outV
    }
}
