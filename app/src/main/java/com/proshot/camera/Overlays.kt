package com.proshot.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Rule-of-thirds grid drawn over the viewfinder. */
class GridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = resources.displayMetrics.density
    }

    var show = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        if (!show) return
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            c.drawLine(w * i / 3f, 0f, w * i / 3f, h, paint)
            c.drawLine(0f, h * i / 3f, w, h * i / 3f, paint)
        }
    }
}

/** Yellow ring that pops in where the user tapped to focus, then fades out. */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFD54F.toInt()
        strokeWidth = 2.5f * density
    }
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f

    init {
        alpha = 0f
    }

    fun show(x: Float, y: Float) {
        cx = x
        cy = y
        animate().cancel()
        alpha = 1f
        ValueAnimator.ofFloat(1.5f, 1f).apply {
            duration = 220
            addUpdateListener {
                scale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        animate().alpha(0f).setStartDelay(1300).setDuration(300).start()
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(cx, cy, 36f * density * scale, paint)
    }
}
