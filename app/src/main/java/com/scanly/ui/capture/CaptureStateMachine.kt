package com.scanly.ui.capture

import com.scanly.platform.DocumentQuad
import kotlin.math.abs

enum class CaptureState { IDLE, SEARCHING, STABLE, CAPTURED }

/**
 * Drives auto-capture WITHOUT breaking continuous ("keep scanning") mode — the specific
 * bug users hit in the leading FOSS scanner. Auto-capture only moves STABLE→CAPTURED;
 * after a capture the machine returns to SEARCHING for the next page.
 *
 * A re-arm cooldown after each auto-capture stops the machine from immediately
 * re-capturing the SAME page while the user swaps in the next sheet: within the
 * cooldown, auto-fire is suppressed unless the document leaves the frame first.
 *
 * Pure logic (no Android deps) so it is unit-testable. See CaptureStateMachineTest.
 */
class CaptureStateMachine(
    private val stableHoldMs: Long = 800,
    private val movementTolerancePx: Float = 24f,
    private val rearmCooldownMs: Long = 2500,
) {
    var state: CaptureState = CaptureState.IDLE
        private set

    private var lastQuad: DocumentQuad? = null
    private var stableSince: Long = 0
    private var cooldownUntil: Long = 0
    private var armed = true

    /**
     * Feed a detection.
     * @return true when an auto-capture should fire NOW (caller is in auto mode).
     */
    fun onDetection(quad: DocumentQuad?, now: Long, autoCapture: Boolean): Boolean {
        if (quad == null) {
            // Document left the frame → the next page can auto-fire immediately.
            armed = true
            state = CaptureState.SEARCHING
            lastQuad = null
            stableSince = 0
            return false
        }
        if (!armed && now >= cooldownUntil) armed = true

        val moved = lastQuad?.let { quadsDiverge(it, quad) } ?: true
        if (moved) {
            lastQuad = quad
            stableSince = now
            state = CaptureState.SEARCHING
            return false
        }

        // Quad is holding still.
        if (now - stableSince >= stableHoldMs) {
            state = CaptureState.STABLE
            if (autoCapture && armed) {
                markCaptured(now)
                return true
            }
        }
        return false
    }

    /** Call after a capture completes; scanning continues for the next page. */
    fun afterCapture(batchMode: Boolean = true) {
        lastQuad = null
        stableSince = 0
        state = if (batchMode) CaptureState.SEARCHING else CaptureState.CAPTURED
    }

    private fun markCaptured(now: Long) {
        state = CaptureState.CAPTURED
        lastQuad = null
        stableSince = 0
        armed = false
        cooldownUntil = now + rearmCooldownMs
    }

    private fun quadsDiverge(a: DocumentQuad, b: DocumentQuad): Boolean =
        a.corners.zip(b.corners).any { (p, q) ->
            abs(p.x - q.x) > movementTolerancePx || abs(p.y - q.y) > movementTolerancePx
        }
}
