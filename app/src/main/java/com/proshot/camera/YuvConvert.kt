package com.proshot.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.proshot.detail.YuvFrame
import java.io.ByteArrayOutputStream

/** Conversions between camera Images, the stacking library's planar frames and JPEG bytes. */
object YuvConvert {

    /** Copies a YUV_420_888 image into tightly packed planes, so the Image can be closed at once. */
    fun fromImage(img: Image): YuvFrame {
        val w = img.width
        val h = img.height
        val y = ByteArray(w * h)
        val u = ByteArray(w / 2 * (h / 2))
        val v = ByteArray(w / 2 * (h / 2))
        copyPlane(img.planes[0], w, h, y)
        copyPlane(img.planes[1], w / 2, h / 2, u)
        copyPlane(img.planes[2], w / 2, h / 2, v)
        return YuvFrame(w, h, y, u, v)
    }

    private fun copyPlane(p: Image.Plane, w: Int, h: Int, out: ByteArray) {
        val buf = p.buffer
        val rowStride = p.rowStride
        val pixelStride = p.pixelStride
        var o = 0
        for (row in 0 until h) {
            val base = row * rowStride
            if (pixelStride == 1) {
                buf.position(base)
                buf.get(out, o, w)
            } else {
                for (col in 0 until w) out[o + col] = buf.get(base + col * pixelStride)
            }
            o += w
        }
    }

    fun toJpeg(f: YuvFrame, quality: Int): ByteArray {
        val w = f.width
        val h = f.height
        val nv = ByteArray(w * h * 3 / 2)
        System.arraycopy(f.y, 0, nv, 0, w * h)
        var o = w * h
        for (i in f.u.indices) {
            nv[o++] = f.v[i]
            nv[o++] = f.u[i]
        }
        val out = ByteArrayOutputStream(w * h / 3)
        YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }
}
