package com.scanly.ui.capture

import com.scanly.platform.DocumentQuad
import kotlin.math.abs

enum class CaptureState { IDLE, SEARCHING, STABLE, CAPTURED }

/**
 * Drives auto-capture WITHOUT breaking continuous ("keep scanning") mode — the specific
 * bug users hit in the leading FOSS scanner. Auto-capture only moves STABLE→CAPTURED;
 * after a capture the machine returns to SEARCHING for the next page.
 *
 * Duplicate protection: after any capture (auto OR manual) the machine is disarmed for
 * the page currently in frame. It re-arms only when the scene actually changes —
 * the document leaves the frame, or a clearly different placement appears (a new sheet
 * slid under the camera moves every corner by more than [rearmJumpPx]). A page that
 * just sits there is never captured twice; time alone never re-arms.
 *
 * [rearmCooldownMs] is a quiet-period floor after each capture so a page that merely
 * lurched (bumped desk) can't refire instantly; an empty frame clears it, keeping fast
 * hand-swaps snappy.
 *
 * Pure logic (no Android deps) so it is unit-testable. See CaptureStateMachineTest.
 */
class CaptureStateMachine(
    // 1.2 s, not a snap: paired with the on-screen countdown ring ([holdProgress]) this
    // gives the user a visible moment to reframe or bail before auto-capture fires.
    private val stableHoldMs: Long = 1200,
    private val movementTolerancePx: Float = 24f,
    private val rearmCooldownMs: Long = 2500,
    /** Per-corner movement (vs. the captured page) that counts as a NEW placement. */
    private val rearmJumpPx: Float = 96f,
) {
    var state: CaptureState = CaptureState.IDLE
        private set

    private var lastQuad: DocumentQuad? = null
    private var stableSince: Long = 0
    private var cooldownUntil: Long = 0
    private var armed = true
    private var capturedQuad: DocumentQuad? = null

    /**
     * Feed a detection.
     * @return true when an auto-capture should fire NOW (caller is in auto mode).
     */
    fun onDetection(quad: DocumentQuad?, now: Long, autoCapture: Boolean): Boolean {
        if (quad == null) {
            // Document left the frame → next page may fire as soon as it's stable.
            armed = true
            capturedQuad = null
            cooldownUntil = 0
            state = CaptureState.SEARCHING
            lastQuad = null
            stableSince = 0
            return false
        }

        // Re-arm only on a genuinely different placement, never on time alone.
        if (!armed) {
            val captured = capturedQuad
            if (captured != null && quadsDiverge(captured, quad, rearmJumpPx)) {
                armed = true
                capturedQuad = null
            }
        }

        val moved = lastQuad?.let { quadsDiverge(it, quad, movementTolerancePx) } ?: true
        if (moved) {
            lastQuad = quad
            stableSince = now
            state = CaptureState.SEARCHING
            return false
        }

        // Quad is holding still.
        if (now - stableSince >= stableHoldMs) {
            state = CaptureState.STABLE
            if (autoCapture && armed && now >= cooldownUntil) {
                markCaptured(quad, now)
                return true
            }
        }
        return false
    }

    /**
     * Call after a capture completes; scanning continues for the next page. A manual
     * capture disarms auto-fire for the page still in frame — without this, auto mode
     * re-shoots the page the user just photographed [stableHoldMs] later.
     */
    fun afterCapture(now: Long = System.currentTimeMillis(), batchMode: Boolean = true) {
        if (armed) {
            armed = false
            capturedQuad = lastQuad
            cooldownUntil = now + rearmCooldownMs
        }
        lastQuad = null
        stableSince = 0
        state = if (batchMode) CaptureState.SEARCHING else CaptureState.CAPTURED
    }

    /**
     * Fraction (0..1) of the stable-hold window elapsed for a page that WILL auto-fire
     * once the window completes; 0 for disarmed or cooling-down pages. Drives the
     * on-screen countdown ring so auto-capture never feels like a surprise snapshot.
     */
    fun holdProgress(now: Long): Float {
        if (lastQuad == null || !armed || now < cooldownUntil) return 0f
        return ((now - stableSince).toFloat() / stableHoldMs).coerceIn(0f, 1f)
    }

    /**
     * Re-arm for the SAME placement — the user rejected the shot ("Retake" on the
     * confirm overlay), so the page still in frame must be capturable again without
     * having to leave the frame first.
     */
    fun rearm() {
        armed = true
        capturedQuad = null
        cooldownUntil = 0
        lastQuad = null
        stableSince = 0
        state = CaptureState.SEARCHING
    }

    private fun markCaptured(quad: DocumentQuad, now: Long) {
        state = CaptureState.CAPTURED
        lastQuad = null
        stableSince = 0
        armed = false
        capturedQuad = quad
        cooldownUntil = now + rearmCooldownMs
    }

    private fun quadsDiverge(a: DocumentQuad, b: DocumentQuad, tolerancePx: Float): Boolean =
        a.corners.zip(b.corners).any { (p, q) ->
            abs(p.x - q.x) > tolerancePx || abs(p.y - q.y) > tolerancePx
        }
}
