package com.proshot.detail

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Luma statistics of a small preview snapshot; all values are fractions 0..1. */
class SceneStats(val mean: Float, val highClip: Float, val lowClip: Float)

enum class Light { BRIGHT, NORMAL, DIM, DARK }

/** Scene analysis behind the smart auto mode: what the camera sees and what to do about it. */
object Scene {

    /** [argb] holds pixels in the android.graphics.Color layout (0xAARRGGBB). */
    fun stats(argb: IntArray): SceneStats {
        var sum = 0L
        var hi = 0
        var lo = 0
        for (p in argb) {
            val y = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            sum += y
            if (y >= 250) hi++
            if (y <= 10) lo++
        }
        val n = max(1, argb.size).toFloat()
        return SceneStats(sum / (255f * n), hi / n, lo / n)
    }

    /** Scene brightness in EV (ISO 100 equivalent) from the exposure the auto-exposure settled on. */
    fun ev(iso: Int, exposureNs: Long): Float {
        val t = exposureNs / 1e9
        return ((ln(1.0 / t) - ln(iso / 100.0)) / ln(2.0)).toFloat()
    }

    fun light(ev: Float): Light = when {
        ev >= 7f -> Light.BRIGHT
        ev >= 4.5f -> Light.NORMAL
        ev >= 2.5f -> Light.DIM
        else -> Light.DARK
    }

    /** Exposure bias in EV that protects blown highlights or lifts a crushed scene. */
    fun targetBias(s: SceneStats): Float = when {
        s.highClip > 0.10f && s.mean > 0.40f -> -1.0f
        s.highClip > 0.04f && s.mean > 0.40f -> -0.7f
        s.highClip > 0.02f && s.mean > 0.55f -> -0.3f
        s.lowClip > 0.45f && s.mean < 0.25f && s.highClip < 0.01f -> 0.7f
        s.lowClip > 0.30f && s.mean < 0.30f && s.highClip < 0.01f -> 0.3f
        else -> 0f
    }
}

/**
 * Turns a stream of analysis ticks into stable decisions: an exposure bias that moves in small
 * steps, and a stack on/off switch with dwell times so the camera does not flap between modes.
 */
class SmartController(private val now: () -> Long = { System.currentTimeMillis() }) {

    var biasEv = 0f
        private set
    var stackWanted = false
        private set
    var light = Light.NORMAL
        private set

    private var dimSince = -1L
    private var brightSince = -1L
    private var lastSwitch = Long.MIN_VALUE / 2

    // Hysteresis for the bias: once the bias moved away from 0 it is held until the scene changes.
    private var sceneEv = 0f
    private var holdAt = Long.MIN_VALUE / 2
    private var holdTrueEv = 0f
    private var relaxSince = -1L

    fun reset() {
        biasEv = 0f
        stackWanted = false
        dimSince = -1L
        brightSince = -1L
        lastSwitch = Long.MIN_VALUE / 2
        sceneEv = 0f
        holdAt = Long.MIN_VALUE / 2
        holdTrueEv = 0f
        relaxSince = -1L
    }

    /** One tick: [stats] may be null when no snapshot was available; iso/exposure come from capture results. */
    fun update(stats: SceneStats?, iso: Int, exposureNs: Long) {
        val t = now()
        if (iso > 0 && exposureNs > 0) {
            sceneEv = Scene.ev(iso, exposureNs)
            light = Scene.light(sceneEv + biasEv)
            val dim = light == Light.DIM || light == Light.DARK
            if (dim) {
                brightSince = -1L
                if (dimSince < 0) dimSince = t
            } else {
                dimSince = -1L
                if (brightSince < 0) brightSince = t
            }
            val canSwitch = t - lastSwitch >= DWELL_MS
            if (!stackWanted && dimSince >= 0 && t - dimSince >= ENTER_MS && canSwitch) {
                stackWanted = true
                lastSwitch = t
            } else if (stackWanted && brightSince >= 0 && t - brightSince >= LEAVE_MS && canSwitch) {
                stackWanted = false
                lastSwitch = t
            }
        }
        if (stats != null) stepBias(Scene.targetBias(stats), t)
    }

    private fun stepBias(target: Float, t: Long) {
        // Brightness of the real scene, independent of the compensation we applied ourselves.
        val trueEv = sceneEv + biasEv
        val away = (biasEv >= 0f && target > biasEv + 0.01f) || (biasEv <= 0f && target < biasEv - 0.01f)
        val relax = !away && abs(target - biasEv) > 0.01f
        when {
            away -> {
                biasEv = if (target > biasEv) min(target, biasEv + STEP) else max(target, biasEv - STEP)
                holdAt = t
                holdTrueEv = trueEv
                relaxSince = -1L
            }
            relax -> {
                // The corrected picture looks fine because of the correction: do not undo it
                // until the scene really changed or a long time has passed.
                val free = abs(trueEv - holdTrueEv) > 1f || t - holdAt > HOLD_MS
                if (!free) {
                    relaxSince = -1L
                } else {
                    if (relaxSince < 0) relaxSince = t
                    if (t - relaxSince >= RELAX_MS) {
                        biasEv = if (target > biasEv) min(target, biasEv + STEP) else max(target, biasEv - STEP)
                        relaxSince = t
                    }
                }
            }
            else -> relaxSince = -1L
        }
    }

    private companion object {
        const val STEP = 0.33f
        const val ENTER_MS = 1500L
        const val LEAVE_MS = 2500L
        const val DWELL_MS = 6000L
        const val RELAX_MS = 4000L
        const val HOLD_MS = 30000L
    }
}
