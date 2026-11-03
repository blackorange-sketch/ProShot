package com.proshot.camera

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/** TextureView that keeps the camera preview's aspect ratio instead of stretching it. */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : TextureView(context, attrs, defStyle) {

    private var ratioW = 0
    private var ratioH = 0

    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0)
        ratioW = width
        ratioH = height
        requestLayout()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(w, h)
        } else if (w * ratioH < h * ratioW) {
            setMeasuredDimension(w, w * ratioH / ratioW)
        } else {
            setMeasuredDimension(h * ratioW / ratioH, h)
        }
    }
}
