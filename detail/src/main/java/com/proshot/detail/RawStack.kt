package com.proshot.detail

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Static description of a RAW_SENSOR frame; no Android types so it can be tested on a plain JVM. */
class RawMeta(
    val width: Int,
    val height: Int,
    /** Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
    val cfa: Int,
    /** Black level for each CFA position, index (y and 1) * 2 + (x and 1). */
    val black: FloatArray,
    val white: Float,
    /** Noise model in normalised units: variance = noiseS * signal + noiseO. */
    val noiseS: Float,
    val noiseO: Float,
) {
    val range: Float get() = white - black.average().toFloat()

    /** Channel (0 = R, 1 = green on red rows, 2 = green on blue rows, 3 = B) at each CFA position. */
    fun channelAt(x: Int, y: Int): Int = CHANNELS[cfa.coerceIn(0, 3)][((y and 1) shl 1) or (x and 1)]

    companion object {
        val CHANNELS = arrayOf(
            intArrayOf(0, 1, 2, 3),
            intArrayOf(1, 0, 3, 2),
            intArrayOf(2, 3, 0, 1),
            intArrayOf(3, 2, 1, 0),
        )
    }
}

/**
 * Merges a burst of RAW frames before demosaicing. Frames are aligned with a global translation
 * measured on a half-resolution luma proxy (so the shift is always an even number of raw pixels and
 * the Bayer pattern stays in phase). Each tile of the candidate frame is compared to the reference
 * using the sensor noise model, and tiles that moved are rejected softly.
 */
class RawStacker(private val m: RawMeta) {
    private val w = m.width
    private val h = m.height
    private val tile = 32
    private val tilesX = (w + tile - 1) / tile
    private val tilesY = (h + tile - 1) / tile

    private var ref: ShortArray? = null
    private var aligner: Aligner? = null
    private var sum: FloatArray? = null
    private val wsum = FloatArray(tilesX * tilesY)

    var frames = 0
        private set

    /** Fraction of tiles of the last merged frame that were accepted. */
    var lastAccepted = 1f
        private set

    /** [proxy] is the frame reduced 2x2 to 8 bit ([proxyOf]); returns the raw-pixel shift used. */
    fun add(frame: ShortArray, proxy: ByteArray): IntArray {
        val r = ref
        if (r == null) {
            ref = frame
            aligner = Aligner(proxy, w / 2, h / 2)
            val s = FloatArray(w * h)
            IntStream.range(0, h).parallel().forEach { y ->
                for (x in 0 until w) {
                    s[y * w + x] = (frame[y * w + x].toInt() and 0xFFFF) - m.black[((y and 1) shl 1) or (x and 1)]
                }
            }
            sum = s
            wsum.fill(1f)
            frames = 1
            return intArrayOf(0, 0)
        }
        val est = aligner!!.estimate(proxy)
        val sx = est[0] * 2
        val sy = est[1] * 2
        merge(r, frame, sx, sy)
        frames++
        return intArrayOf(sx, sy)
    }

    private fun merge(r: ShortArray, f: ShortArray, sx: Int, sy: Int) {
        val s = sum!!
        val range = m.range
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        IntStream.range(0, tilesY).parallel().forEach { ty ->
            val y0 = ty * tile
            val y1 = min(h, y0 + tile)
            for (tx in 0 until tilesX) {
                val x0 = tx * tile
                val x1 = min(w, x0 + tile)
                var mad = 0.0
                var mean = 0.0
                var n = 0
                for (y in y0 until y1) {
                    val cy = y + sy
                    if (cy < 0 || cy >= h) continue
                    for (x in x0 until x1) {
                        val cx = x + sx
                        if (cx < 0 || cx >= w) continue
                        val b = m.black[((y and 1) shl 1) or (x and 1)]
                        val rv = (r[y * w + x].toInt() and 0xFFFF) - b
                        val cv = (f[cy * w + cx].toInt() and 0xFFFF) - b
                        mad += abs(cv - rv)
                        mean += rv
                        n++
                    }
                }
                val area = (x1 - x0) * (y1 - y0)
                var wgt = 0f
                if (n * 2 >= area) {
                    mad /= n
                    val sig = sqrt(maxOf(0.0, (m.noiseS * (mean / n / range).coerceIn(0.0, 1.0) + m.noiseO).toDouble())) * range
                    val expected = 0.798 * 1.4142 * sig + 0.5
                    val ratio = mad / expected
                    wgt = ((3.2 - ratio) / 1.7).coerceIn(0.0, 1.0).toFloat()
                }
                if (wgt <= 0f) continue
                accepted.incrementAndGet()
                for (y in y0 until y1) {
                    val cy = y + sy
                    for (x in x0 until x1) {
                        val cx = x + sx
                        val b = m.black[((y and 1) shl 1) or (x and 1)]
                        val v = if (cy < 0 || cy >= h || cx < 0 || cx >= w) {
                            (r[y * w + x].toInt() and 0xFFFF) - b
                        } else {
                            (f[cy * w + cx].toInt() and 0xFFFF) - b
                        }
                        s[y * w + x] += wgt * v
                    }
                }
                wsum[ty * tilesX + tx] += wgt
            }
        }
        lastAccepted = accepted.get().toFloat() / (tilesX * tilesY)
    }

    /** Black-subtracted merged frame in raw units; consumes the accumulator. */
    fun result(): FloatArray {
        val s = sum ?: error("no frames")
        IntStream.range(0, h).parallel().forEach { y ->
            val ty = y / tile
            for (x in 0 until w) {
                s[y * w + x] /= wsum[ty * tilesX + x / tile]
            }
        }
        sum = null
        ref = null
        return s
    }

    companion object {
        /** 2x2 average of a RAW frame, black removed and square-rooted into 8 bit, for alignment. */
        fun proxyOf(frame: ShortArray, w: Int, h: Int, black: Float, white: Float): ByteArray {
            val pw = w / 2
            val ph = h / 2
            val out = ByteArray(pw * ph)
            val inv = 1f / (white - black)
            IntStream.range(0, ph).parallel().forEach { y ->
                for (x in 0 until pw) {
                    val i = (2 * y) * w + 2 * x
                    val v = ((frame[i].toInt() and 0xFFFF) + (frame[i + 1].toInt() and 0xFFFF) +
                        (frame[i + w].toInt() and 0xFFFF) + (frame[i + w + 1].toInt() and 0xFFFF)) * 0.25f - black
                    val n = (v * inv).coerceIn(0f, 1f)
                    out[y * pw + x] = (sqrt(n) * 255f + 0.5f).toInt().toByte()
                }
            }
            return out
        }
    }
}
