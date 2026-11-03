package com.proshot.camera

import android.content.Context
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest

/**
 * Qualcomm / ZTE session parameters that this phone's camera HAL lists (found with the camera dump).
 * What each one really does is not documented, so they are experiments the user switches on one by one
 * and judges by the photos. Every key is set to 1; the value type (byte or int) is probed at run time.
 */
object VendorKeys {
    private const val QC = "org.codeaurora.qcamera3.sessionParameters."
    private const val ZTE = "com.zte.camera.sessionParameters."

    class Key(val name: String, val label: String)

    val all = listOf(
        Key(QC + "enableMFNR", "MFNR: багатокадрове шумозаглушення"),
        Key(QC + "EnableMFHDR", "MFHDR: багатокадровий HDR"),
        Key(QC + "EnableSHDR", "SHDR: HDR зі зміною витримки на сенсорі"),
        Key(QC + "EnableQHDR", "QHDR"),
        Key(QC + "EnableAutoHDR", "Авто-HDR"),
        Key(QC + "EnableIdealRAW", "Ideal RAW"),
        Key(QC + "EnableXCFAOptimization", "XCFA: оптимізація квад-байєра"),
        Key(QC + "EnableInsensorZoom", "Зум на сенсорі"),
        Key(ZTE + "rawaiprocess", "ШІ-обробка RAW (ZTE)"),
    )

    private const val PREFS = "proshot"
    private const val PREF_KEY = "vendorKeys"

    fun enabled(ctx: Context): List<String> {
        val set = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(PREF_KEY, emptySet()).orEmpty()
        return all.map { it.name }.filter { it in set }
    }

    fun save(ctx: Context, names: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(PREF_KEY, names).apply()
    }

    /** Builds a session-parameters request holding every key that the HAL accepted, plus the names that worked. */
    fun shortName(name: String) = name.substringAfterLast('.').removePrefix("Enable").removePrefix("enable")

    /** Returns the request plus accepted key -> value type used ("byte" or "int"). */
    fun buildRequest(d: CameraDevice, names: List<String>): Pair<CaptureRequest?, Map<String, String>> {
        val ok = linkedMapOf<String, String>()
        val b = try {
            d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        } catch (t: Throwable) {
            return null to ok
        }
        for (n in names) {
            if (runCatching { b.set(CaptureRequest.Key(n, Byte::class.javaObjectType), 1.toByte()) }.isSuccess) {
                ok[n] = "byte"
            } else if (runCatching { b.set(CaptureRequest.Key(n, Int::class.javaObjectType), 1) }.isSuccess) {
                ok[n] = "int"
            }
        }
        return (if (ok.isEmpty()) null else runCatching { b.build() }.getOrNull()) to ok
    }
}
