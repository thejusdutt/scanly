package com.scanly.ui.capture

import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import kotlin.math.abs

/**
 * Temporal filter between the per-frame detector and everything that consumes the live
 * quad (overlay + auto-capture). Raw detections jitter frame-to-frame — the two
 * binarization passes can trade wins, and a single frame can miss entirely — which the
 * user sees as a flickering outline. Three rules kill that:
 *
 *  1. SMOOTH: small corner movement is blended into the shown quad (EMA) instead of
 *     snapping, so the outline glides with hand shake.
 *  2. CONFIRM: a quad far from the shown one (or the first quad after a gap) must
 *     persist for [confirmFrames] consecutive frames before it is displayed, so a
 *     one-frame spurious contour never flashes on screen.
 *  3. HOLD: when detection drops out, the last quad is kept for [holdMs] before the
 *     outline disappears, so intermittent misses don't blink it off.
 *
 * Pure logic (no Android/OpenCV deps) — unit-tested in QuadStabilizerTest.
 */
class QuadStabilizer(
    /** EMA weight of the newest detection when it's near the shown quad. */
    private val smoothing: Float = 0.45f,
    /** Max per-corner distance still treated as "the same quad, moved a little". */
    private val jumpTolerancePx: Float = 56f,
    /** Consecutive consistent frames a new/jumped quad needs before being shown. */
    private val confirmFrames: Int = 2,
    /** How long the last quad survives detection dropouts. */
    private val holdMs: Long = 500,
) {
    private var shown: DocumentQuad? = null
    private var lastSeenAt: Long = 0
    private var candidate: DocumentQuad? = null
    private var candidateCount: Int = 0

    /** Feed the raw detector output for one frame; returns the quad to display/act on. */
    fun update(raw: DocumentQuad?, now: Long): DocumentQuad? {
        if (raw == null) {
            candidate = null
            candidateCount = 0
            val held = shown
            if (held != null && now - lastSeenAt <= holdMs) return held
            shown = null
            return null
        }

        lastSeenAt = now
        val current = shown
        if (current != null && near(current, raw)) {
            // Same quad, small motion → glide toward it.
            val blended = lerp(current, raw, smoothing)
            shown = blended
            candidate = null
            candidateCount = 0
            return blended
        }

        // New quad, or a big jump: require it to repeat before trusting it.
        val cand = candidate
        if (cand != null && near(cand, raw)) {
            candidateCount++
            candidate = lerp(cand, raw, smoothing)
        } else {
            candidate = raw
            candidateCount = 1
        }
        if (candidateCount >= confirmFrames) {
            shown = candidate
            candidate = null
            candidateCount = 0
        }
        return shown
    }

    fun reset() {
        shown = null
        candidate = null
        candidateCount = 0
        lastSeenAt = 0
    }

    private fun near(a: DocumentQuad, b: DocumentQuad): Boolean =
        a.corners.zip(b.corners).all { (p, q) ->
            abs(p.x - q.x) <= jumpTolerancePx && abs(p.y - q.y) <= jumpTolerancePx
        }

    private fun lerp(from: DocumentQuad, to: DocumentQuad, t: Float): DocumentQuad {
        fun p(a: QuadPoint, b: QuadPoint) =
            QuadPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        return DocumentQuad(
            p(from.topLeft, to.topLeft),
            p(from.topRight, to.topRight),
            p(from.bottomRight, to.bottomRight),
            p(from.bottomLeft, to.bottomLeft),
        )
    }
}
