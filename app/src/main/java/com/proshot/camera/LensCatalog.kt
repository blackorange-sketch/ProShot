package com.proshot.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One physical lens (or a standalone camera) the app can shoot with. */
data class Lens(
    /** Id passed to CameraManager.openCamera(). */
    val openId: String,
    /** Non-null when streams must be routed to a physical sensor behind a logical camera. */
    val physicalId: String?,
    val label: String,
    val focalMm: Float,
    val equivMm: Int,
    val chars: CameraCharacteristics,
    val supportsRaw: Boolean,
    val rawSize: Size?,
    val jpegSize: Size?,
    /** Largest JPEG size in ultra-high-resolution sensor mode, null when the lens has none. */
    val maxResJpegSize: Size?,
    /** Largest RAW size in ultra-high-resolution sensor mode, null when unavailable. */
    val maxResRawSize: Size?,
) {
    val supportsMaxRes: Boolean get() = maxResJpegSize != null
}

object LensCatalog {

    fun discover(ctx: Context): List<Lens> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = mgr.cameraIdList.toSet()
        val found = LinkedHashMap<String, Lens>()

        for (id in mgr.cameraIdList) {
            val c = mgr.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue

            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val physicalIds = c.physicalCameraIds
            val isLogical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

            if (isLogical && physicalIds.isNotEmpty()) {
                for (pid in physicalIds) {
                    val pc = runCatching { mgr.getCameraCharacteristics(pid) }.getOrNull() ?: c
                    val direct = pid in ids
                    found.putIfAbsent(pid, build(if (direct) pid else id, if (direct) null else pid, pc))
                }
            } else {
                found.putIfAbsent(id, build(id, null, c))
            }
        }
        return found.values.sortedBy { it.equivMm }
    }

    private fun build(openId: String, physicalId: String?, c: CameraCharacteristics): Lens {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (sensor != null && focal > 0f) {
            (focal * 43.267f / hypot(sensor.width, sensor.height)).roundToInt()
        } else 0

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }

        var maxRes: Size? = null
        var maxResRaw: Size? = null
        if (Build.VERSION.SDK_INT >= 31) {
            val mm = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            maxRes = mm?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
            maxResRaw = mm?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        }

        val role = when {
            equiv == 0 -> "Camera"
            equiv < 22 -> "Ultra-wide"
            equiv < 45 -> "Main"
            else -> "Tele"
        }
        val label = if (equiv > 0) "$role ${equiv}mm" else "$role $openId"

        return Lens(
            openId = openId,
            physicalId = physicalId,
            label = label,
            focalMm = focal,
            equivMm = equiv,
            chars = c,
            supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) && rawSize != null,
            rawSize = rawSize,
            jpegSize = jpegSize,
            maxResJpegSize = maxRes,
            maxResRawSize = maxResRaw,
        )
    }

    fun describe(l: Lens): String = buildString {
        append(l.label).append(" [id ").append(l.physicalId ?: l.openId).append("]\n")
        l.jpegSize?.let { append("JPEG ${it.width}x${it.height}") }
        l.maxResJpegSize?.let { append(" | FULL ${it.width}x${it.height}") }
        append(if (l.supportsRaw) " | RAW ${l.rawSize!!.width}x${l.rawSize.height}" else " | no RAW")
        l.maxResRawSize?.let { append(" | FULL RAW ${it.width}x${it.height}") }
    }
}
