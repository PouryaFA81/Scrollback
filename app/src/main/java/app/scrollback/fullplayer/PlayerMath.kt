// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.fullplayer

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Aspect modes of the full player (MX/VLC style). */
enum class AspectMode(val label: String) {
    FIT("Fit"), FILL("Stretch"), CROP("Crop"), R16_9("16:9"), R4_3("4:3"), ORIGINAL("Original");

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

/** A width/height pair in pixels (Double so the maths stays exact until the end). */
data class Dim(val w: Double, val h: Double)

/** Pure maths for the full player: layout, gestures, resume and A-B repeat. Unit-tested. */
object PlayerMath {

    /**
     * Size of the visible video picture on screen for [mode]. [visW]/[visH] are the visible
     * (cropped) pixels libVLC reports, [sarNum]/[sarDen] the sample aspect ratio (0 = square).
     * Fit/16:9/4:3 letterbox inside the screen, Crop fills it and cuts the overflow, Fill
     * stretches, Original keeps the native pixel size.
     */
    fun displaySize(mode: AspectMode, visW: Int, visH: Int, sarNum: Int, sarDen: Int, screenW: Int, screenH: Int): Dim {
        val sw = screenW.toDouble(); val sh = screenH.toDouble()
        if (visW <= 0 || visH <= 0 || sw <= 0 || sh <= 0) return Dim(sw, sh)
        val sar = if (sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0
        val vw = visW * sar
        val vh = visH.toDouble()
        val screenAr = sw / sh
        fun fit(ar: Double) = if (screenAr < ar) Dim(sw, sw / ar) else Dim(sh * ar, sh)
        return when (mode) {
            AspectMode.FIT -> fit(vw / vh)
            AspectMode.R16_9 -> fit(16.0 / 9.0)
            AspectMode.R4_3 -> fit(4.0 / 3.0)
            AspectMode.FILL -> Dim(sw, sh)
            AspectMode.CROP -> { val ar = vw / vh; if (screenAr < ar) Dim(sh * ar, sh) else Dim(sw, sw / ar) }
            AspectMode.ORIGINAL -> Dim(vw, vh)
        }
    }

    /**
     * The surface must be bigger than the visible picture when the decoder adds padding
     * (e.g. 1920×1088 buffers for 1080p), so the visible part lands exactly on [display].
     */
    fun surfaceSize(display: Dim, w: Int, h: Int, visW: Int, visH: Int): Pair<Int, Int> {
        if (visW <= 0 || visH <= 0 || w <= 0 || h <= 0) return display.w.roundToInt() to display.h.roundToInt()
        return kotlin.math.ceil(display.w * w / visW).toInt() to kotlin.math.ceil(display.h * h / visH).toInt()
    }

    /** Which gesture a drag becomes once it passes the touch slop. */
    enum class Gesture { NONE, SEEK, BRIGHTNESS, VOLUME }

    fun classify(dx: Float, dy: Float, startX: Float, width: Float, slop: Float): Gesture = when {
        abs(dx) < slop && abs(dy) < slop -> Gesture.NONE
        abs(dx) > abs(dy) -> Gesture.SEEK
        startX < width / 2f -> Gesture.BRIGHTNESS
        else -> Gesture.VOLUME
    }

    /**
     * Brightness/volume level after a vertical drag: swiping up raises it, and 80 % of the
     * view's height covers the whole 0..1 range.
     */
    fun levelAfterDrag(start: Float, dy: Float, height: Float): Float {
        if (height <= 0f) return start.coerceIn(0f, 1f)
        return (start - dy / (height * 0.8f)).coerceIn(0f, 1f)
    }

    /** Maps a 0..1 level onto the stream's volume steps. */
    fun volumeStep(level: Float, max: Int): Int = (level * max).roundToInt().coerceIn(0, max)

    /**
     * Horizontal seek: a full-width swipe moves by up to 3 minutes (less for short clips: the
     * whole clip). Returns the clamped target time.
     */
    fun seekTarget(startMs: Long, dx: Float, width: Float, durationMs: Long): Long {
        if (width <= 0f) return startMs
        val span = if (durationMs > 0) minOf(durationMs, 180_000L) else 180_000L
        val target = startMs + (dx / width * span).roundToLong()
        return if (durationMs > 0) target.coerceIn(0, durationMs) else target.coerceAtLeast(0)
    }

    /** Double tap: left third back 10 s, right third forward 10 s, middle toggles play/pause (null). */
    fun doubleTapSeek(x: Float, width: Float, nowMs: Long, durationMs: Long, stepMs: Long = 10_000): Long? {
        if (width <= 0f) return null
        val dir = when {
            x < width / 3f -> -1
            x > width * 2f / 3f -> 1
            else -> return null
        }
        val t = nowMs + dir * stepMs
        return if (durationMs > 0) t.coerceIn(0, durationMs) else t.coerceAtLeast(0)
    }

    fun clampZoom(z: Float): Float = z.coerceIn(1f, 4f)

    /** "1:02:03" / "4:05"; with [signed] a leading +/−. */
    fun formatTime(ms: Long, signed: Boolean = false): String {
        val sign = if (signed) (if (ms < 0) "−" else "+") else (if (ms < 0) "−" else "")
        val s = abs(ms) / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%s%d:%02d:%02d".format(sign, h, m, sec) else "%s%d:%02d".format(sign, m, sec)
    }

    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

    fun clampSpeed(s: Float): Float = s.coerceIn(0.25f, 4f)

    // ---- Resume ----

    /** Resume only from a meaningful point: after 5 s and not in the last 10 s / 3 %. */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < 5_000) return false
        if (durationMs <= 0) return true
        return positionMs < durationMs - 10_000 && positionMs < durationMs * 0.97
    }

    /** What to store when leaving a file: 0 when it was (almost) finished, else the position. */
    fun positionToSave(positionMs: Long, durationMs: Long): Long =
        if (positionMs <= 0) 0 else if (durationMs > 0 && !shouldResume(positionMs, durationMs) && positionMs >= 5_000) 0 else positionMs
}

/** A-B repeat: first tap sets A, second sets B, third clears. */
class AbRepeat {
    var a: Long? = null; private set
    var b: Long? = null; private set
    val active: Boolean get() = a != null && b != null

    /** Advances the A → B → off cycle at [nowMs]. */
    fun tap(nowMs: Long) {
        when {
            a == null -> a = nowMs
            b == null -> {
                val first = a!!
                if (abs(nowMs - first) < 500) return // too short to loop; ignore
                if (nowMs < first) { b = first; a = nowMs } else b = nowMs
            }
            else -> { a = null; b = null }
        }
    }

    fun clear() { a = null; b = null }

    /** Where to jump at [nowMs], or null to keep playing. */
    fun check(nowMs: Long): Long? = if (active && nowMs >= b!!) a else null
}
