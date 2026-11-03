package com.proshot.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

/** What the camera actually used for a shot; written into the JPEG's EXIF like the stock camera does. */
class ShotMeta(
    val lens: Lens,
    val iso: Int?,
    val exposureNs: Long?,
    val aperture: Float?,
    val focalMm: Float,
    val evComp: Float,
    val wbAuto: Boolean,
    val software: String,
    val width: Int? = null,
    val height: Int? = null,
) {
    companion object {
        fun from(
            lens: Lens,
            r: TotalCaptureResult?,
            state: ManualState,
            software: String,
            width: Int? = null,
            height: Int? = null,
        ): ShotMeta {
            val aperture = r?.get(CaptureResult.LENS_APERTURE)
                ?: lens.chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            val step = QualityProfile.evStepSize(lens.chars)
            return ShotMeta(
                lens = lens,
                iso = r?.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                aperture = aperture,
                focalMm = r?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: lens.focalMm,
                evComp = if (state.manualExposure) 0f else (state.evSteps + state.smartEvSteps) * step,
                wbAuto = state.awbMode == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO,
                software = software,
                width = width,
                height = height,
            )
        }
    }
}
