package com.proshot.detail

/** A burst merger: the first frame given at creation is the reference, later frames are added. */
interface FrameStacker {
    /** Number of frames merged so far, including the reference. */
    val frames: Int

    /** Aligns and accumulates [f]; returns the (dx, dy) shift that was found. */
    fun add(f: YuvFrame): IntArray

    fun result(): YuvFrame

    /** Releases native memory, if any. */
    fun close() {}
}
