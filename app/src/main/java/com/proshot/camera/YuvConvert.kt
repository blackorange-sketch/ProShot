package com.proshot.camera

import android.graphics.Bitmap
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

    /** Converts a decoded bitmap (BT.601 full range, like JPEG) to planar 4:2:0; dimensions are made even. */
    fun fromBitmap(bmp: Bitmap): YuvFrame {
        val w = bmp.width and 1.inv()
        val h = bmp.height and 1.inv()
        val y = ByteArray(w * h)
        val u = ByteArray(w / 2 * (h / 2))
        val v = ByteArray(w / 2 * (h / 2))
        val row0 = IntArray(w)
        val row1 = IntArray(w)
        for (r in 0 until h step 2) {
            bmp.getPixels(row0, 0, w, 0, r, w, 1)
            bmp.getPixels(row1, 0, w, 0, r + 1, w, 1)
            for (c in 0 until w step 2) {
                var su = 0
                var sv = 0
                for (k in 0..3) {
                    val p = if (k < 2) row0[c + (k and 1)] else row1[c + (k and 1)]
                    val rr = (p shr 16) and 0xFF
                    val gg = (p shr 8) and 0xFF
                    val bb = p and 0xFF
                    val yy = (77 * rr + 150 * gg + 29 * bb + 128) shr 8
                    val idx = (r + (k shr 1)) * w + c + (k and 1)
                    y[idx] = yy.coerceIn(0, 255).toByte()
                    su += ((-43 * rr - 85 * gg + 128 * bb + 128) shr 8) + 128
                    sv += ((128 * rr - 107 * gg - 21 * bb + 128) shr 8) + 128
                }
                val ci = (r / 2) * (w / 2) + c / 2
                u[ci] = ((su + 2) / 4).coerceIn(0, 255).toByte()
                v[ci] = ((sv + 2) / 4).coerceIn(0, 255).toByte()
            }
        }
        return YuvFrame(w, h, y, u, v)
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
