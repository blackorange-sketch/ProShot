package com.proshot.detail

/** JNI entry points of libproshot_detail.so. Use [NativeStacker] and [StackerFactory] instead. */
internal object NativeBridge {

    val available: Boolean by lazy {
        try {
            System.loadLibrary("proshot_detail")
            true
        } catch (t: Throwable) {
            false
        }
    }

    external fun nativeCreate(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, sigma: Float): Long
    external fun nativeAdd(handle: Long, y: ByteArray, u: ByteArray, v: ByteArray, shiftOut: IntArray)
    external fun nativeResult(handle: Long, y: ByteArray, u: ByteArray, v: ByteArray)
    external fun nativeFree(handle: Long)
}

/** C++ implementation of the stacker: multi-threaded, accumulators live outside the Java heap. */
class NativeStacker(ref: YuvFrame, sigma: Float) : FrameStacker {

    private var handle = NativeBridge.nativeCreate(ref.y, ref.u, ref.v, ref.width, ref.height, sigma)
    private val w = ref.width
    private val h = ref.height

    override var frames = 1
        private set

    override fun add(f: YuvFrame): IntArray {
        check(handle != 0L) { "stacker closed" }
        require(f.width == w && f.height == h) { "frame size mismatch" }
        val shift = IntArray(2)
        NativeBridge.nativeAdd(handle, f.y, f.u, f.v, shift)
        frames++
        return shift
    }

    override fun result(): YuvFrame {
        check(handle != 0L) { "stacker closed" }
        val y = ByteArray(w * h)
        val u = ByteArray(w / 2 * (h / 2))
        val v = ByteArray(w / 2 * (h / 2))
        NativeBridge.nativeResult(handle, y, u, v)
        return YuvFrame(w, h, y, u, v)
    }

    override fun close() {
        if (handle != 0L) {
            NativeBridge.nativeFree(handle)
            handle = 0L
        }
    }
}

/** Picks the native stacker when the library loads and works, otherwise the Kotlin one. */
object StackerFactory {

    /** True when frames are being merged by the C++ implementation. */
    val nativeAvailable: Boolean get() = NativeBridge.available

    fun create(ref: YuvFrame, sigma: Float): FrameStacker {
        if (NativeBridge.available) {
            try {
                return NativeStacker(ref, sigma)
            } catch (t: Throwable) {
                // fall through to the Kotlin implementation
            }
        }
        return Stacker(ref, sigma)
    }
}
