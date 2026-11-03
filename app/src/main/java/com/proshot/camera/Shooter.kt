package com.proshot.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.util.Size

/** One way of taking a still: single frame (JPEG/RAW) or a stacked burst. */
interface Shooter {
    val busy: Boolean
    val jpegSize: Size?
    val rawSize: Size?

    /** Output configurations to add to the camera session. */
    fun outputConfigs(): List<OutputConfiguration>

    /** [onExposed] runs once the sensor is done (before saving), e.g. to release an AF lock. */
    fun capture(
        device: CameraDevice,
        session: CameraCaptureSession,
        state: ManualState,
        orientation: Int,
        onExposed: (() -> Unit)?,
    )

    fun close()

    fun describe(): String

    /** Latest ISO seen in preview; used to tune stack merging strictness. */
    fun setIsoHint(iso: Int) {}
}
