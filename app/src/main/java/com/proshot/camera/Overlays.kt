package com.proshot.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

/** Yellow ring that pops in where the user tapped to focus; shows EV while dragging and a lock label. */
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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
        textSize = 13f * density
        isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 0f, 0xFF000000.toInt())
    }
    private var cx = 0f
    private var cy = 0f
    private var scale = 1f
    private var locked = false
    private var evLabel: String? = null

    init {
        alpha = 0f
    }

    val active: Boolean get() = alpha > 0.05f

    fun show(x: Float, y: Float, lock: Boolean = false) {
        cx = x
        cy = y
        locked = lock
        evLabel = null
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
        scheduleFade()
    }

    /** Keeps the ring on screen while the user drags for exposure compensation. */
    fun keepAlive(ev: String?) {
        evLabel = ev
        animate().cancel()
        alpha = 1f
        invalidate()
        scheduleFade()
    }

    private fun scheduleFade() {
        if (!locked) animate().alpha(0f).setStartDelay(1500).setDuration(300).start()
    }

    override fun onDraw(c: Canvas) {
        val r = 36f * density * scale
        c.drawCircle(cx, cy, r, paint)
        evLabel?.let { c.drawText(it, cx + r + 8f * density, cy + 5f * density, text) }
        if (locked) {
            val label = "AE/AF LOCK"
            c.drawText(label, cx - text.measureText(label) / 2f, cy + r + 18f * density, text)
        }
    }
}

/** Thin white box around the main face the camera is focusing on. */
class FaceBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFFFFF.toInt()
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private var box: RectF? = null

    fun setBox(r: RectF?) {
        box = r
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        box?.let { c.drawRoundRect(it, 8f, 8f, paint) }
    }
}
