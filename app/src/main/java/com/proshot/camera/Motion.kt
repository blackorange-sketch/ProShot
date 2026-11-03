package com.proshot.camera

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Small, reusable view animations so the UI reacts to touch instead of just snapping between states. */
object Motion {

    /** Press feedback: the view shrinks a little while touched and springs back. Clicks still go through. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, depth: Float = 0.92f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(depth).scaleY(depth).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.5f)).start()
            }
            false
        }
    }

    /** Fade and slide in/out on a visibility change; does nothing when the view is already in the wanted state. */
    fun setVisible(v: View, show: Boolean, slidePx: Float = 24f) {
        if (show) {
            if (v.visibility == View.VISIBLE && v.tag != HIDING) return
            v.animate().cancel()
            v.tag = null
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.translationY = slidePx
                v.visibility = View.VISIBLE
            }
            v.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        } else {
            if (v.visibility != View.VISIBLE || v.tag == HIDING) return
            v.animate().cancel()
            v.tag = HIDING
            v.animate().alpha(0f).translationY(slidePx).setDuration(160).withEndAction {
                if (v.tag == HIDING) {
                    v.visibility = View.GONE
                    v.tag = null
                }
            }.start()
        }
    }

    /** Scale-in with a small overshoot, e.g. for a new thumbnail or the selected lens dot. */
    fun pop(v: View, from: Float = 0.6f) {
        v.animate().cancel()
        v.scaleX = from
        v.scaleY = from
        v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    /** Fades a view in after a delay; used for a staggered appearance of the lens dots. */
    fun appear(v: View, delayMs: Long) {
        v.alpha = 0f
        v.translationY = 10f
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Breathing alpha while something is being processed. Cancel the returned animator to stop. */
    fun pulse(v: View): ObjectAnimator = ObjectAnimator.ofFloat(v, View.ALPHA, 0.35f, 0.8f).apply {
        duration = 520
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        start()
    }

    private const val HIDING = "hiding"
}
