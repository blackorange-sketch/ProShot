package com.proshot.detail

/** Planar 4:2:0 frame with tightly packed rows. Width and height must be even. */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)

object Noise {
    /** Rough 8-bit luma noise sigma for a given ISO; drives how strict frame merging is. */
    fun sigmaForIso(iso: Int): Float = (2f + iso / 300f).coerceIn(2f, 10f)
}
