package com.proshot.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.proshot.detail.YuvFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text diagnostics log: one file per app run in Download (proshot_log_<time>.txt), rewritten after every
 * line. Sessions record which vendor keys the HAL accepted; every shot records timings, sizes and the capture
 * result, so the effect of a key (speed, noise, sharpness, exposure) can be compared between shots.
 */
object ShotLog {
    private val sb = StringBuilder()
    private var uri: Uri? = null

    @Synchronized
    fun add(ctx: Context, line: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        sb.append(ts).append(' ').append(line).append('\n')
        runCatching {
            val r = ctx.contentResolver
            if (uri == null) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "proshot_log_${System.currentTimeMillis() / 1000}.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            }
            uri?.let { u -> r.openOutputStream(u, "wt")?.use { it.write(sb.toString().toByteArray()) } }
        }
    }

    /** Mean luma and Laplacian variance (a sharpness-plus-noise measure) on a subsampled Y plane. */
    fun stats(f: YuvFrame): String {
        val w = f.width
        val h = f.height
        var n = 0L
        var sum = 0L
        var lap = 0.0
        var lap2 = 0.0
        var y = 4
        while (y < h - 4) {
            var x = 4
            while (x < w - 4) {
                val i = y * w + x
                val c = f.y[i].toInt() and 0xFF
                val l = 4 * c - (f.y[i - 1].toInt() and 0xFF) - (f.y[i + 1].toInt() and 0xFF) -
                    (f.y[i - w].toInt() and 0xFF) - (f.y[i + w].toInt() and 0xFF)
                sum += c
                lap += l
                lap2 += l.toDouble() * l
                n++
                x += 4
            }
            y += 4
        }
        if (n == 0L) return ""
        val mean = sum.toDouble() / n
        val v = lap2 / n - (lap / n) * (lap / n)
        return "luma=%.1f lap=%.0f".format(Locale.US, mean, v)
    }

    fun result(r: TotalCaptureResult): String {
        fun <T> g(k: CaptureResult.Key<T>): String = r.get(k)?.toString() ?: "-"
        val exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { "%.1fms".format(Locale.US, it / 1e6) } ?: "-"
        return "iso=${g(CaptureResult.SENSOR_SENSITIVITY)} exp=$exp nr=${g(CaptureResult.NOISE_REDUCTION_MODE)} " +
            "edge=${g(CaptureResult.EDGE_MODE)} tonemap=${g(CaptureResult.TONEMAP_MODE)} scene=${g(CaptureResult.CONTROL_SCENE_MODE)} " +
            "ae=${g(CaptureResult.CONTROL_AE_STATE)} depth=${g(CaptureResult.REQUEST_PIPELINE_DEPTH)} resultKeys=${r.keys.size}"
    }
}
