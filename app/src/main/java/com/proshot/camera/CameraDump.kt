package com.proshot.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.MediaStore

/**
 * Writes everything Camera2 reports about every camera (including hidden ids) into Download/proshot_cameras.txt,
 * so missing features such as 50 MP or extra lenses can be diagnosed from a file instead of guesses.
 */
object CameraDump {

    fun write(ctx: Context): String {
        val name = "proshot_cameras_${System.currentTimeMillis() / 1000}.txt"
        val text = build(ctx)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        return name
    }

    private fun build(ctx: Context): String {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}\n")
        val listed = mgr.cameraIdList.toList()
        sb.append("cameraIdList: $listed\n\n")
        val ids = LinkedHashSet<String>(listed)
        for (n in 0..12) ids += n.toString()
        for (id in ids) {
            val c = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull()
            if (c == null) {
                sb.append("== id $id: not accessible\n\n")
                continue
            }
            dump(sb, "id $id${if (id in listed) "" else " (hidden)"}", c)
            for (pid in c.physicalCameraIds) {
                val pc = runCatching { mgr.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                dump(sb, "physical $pid (behind $id)", pc)
            }
        }
        return sb.toString()
    }

    private fun dump(sb: StringBuilder, title: String, c: CameraCharacteristics) {
        sb.append("== $title\n")
        sb.append("facing=${c.get(CameraCharacteristics.LENS_FACING)} sensorOrientation=${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}\n")
        sb.append("capabilities=${c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()}\n")
        sb.append("focal=${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()} sensorMm=${c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)}\n")
        sb.append("pixelArray=${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)} active=${c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}\n")
        if (Build.VERSION.SDK_INT >= 31) {
            sb.append("activeMaxRes=${c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)} pixelArrayMaxRes=${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)}\n")
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            for ((label, fmt) in listOf("JPEG" to ImageFormat.JPEG, "YUV" to ImageFormat.YUV_420_888, "RAW" to ImageFormat.RAW_SENSOR)) {
                sb.append("$label sizes=${map.getOutputSizes(fmt)?.joinToString { "${it.width}x${it.height}" }}\n")
                sb.append("$label highRes=${map.getHighResolutionOutputSizes(fmt)?.joinToString { "${it.width}x${it.height}" }}\n")
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val mm = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            if (mm != null) {
                for ((label, fmt) in listOf("JPEG" to ImageFormat.JPEG, "RAW" to ImageFormat.RAW_SENSOR)) {
                    sb.append("MAXRES $label=${mm.getOutputSizes(fmt)?.joinToString { "${it.width}x${it.height}" }}\n")
                }
            } else {
                sb.append("MAXRES map: none\n")
            }
        }
        val vendorReq = c.availableCaptureRequestKeys.map { it.name }.filter { !it.startsWith("android.") }
        val vendorRes = c.availableCaptureResultKeys.map { it.name }.filter { !it.startsWith("android.") }
        val vendorChars = c.keys.map { it.name }.filter { !it.startsWith("android.") }
        sb.append("vendor characteristics keys=$vendorChars\n")
        sb.append("vendor request keys=$vendorReq\n")
        sb.append("vendor result keys=$vendorRes\n\n")
    }
}
