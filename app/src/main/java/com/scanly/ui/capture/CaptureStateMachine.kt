package com.scanly.ui.capture

import com.scanly.platform.DocumentQuad
import kotlin.math.abs

enum class CaptureState { IDLE, SEARCHING, STABLE, CAPTURED }

/**
 * Drives auto-capture WITHOUT breaking batch mode — the specific bug users hit in the
 * leading FOSS scanner. Auto-capture only moves STABLE→CAPTURED; after a capture in batch
 * mode the machine returns to SEARCHING for the next page and never tears down batch.
 *
 * Pure logic (no Android deps) so it is unit-testable. See CaptureStateMachineTest.
 */
class CaptureStateMachine(
    private val stableHoldMs: Long = 800,
    private val movementTolerancePx: Float = 24f,
) {
    var state: CaptureState = CaptureState.IDLE
        private set

    private var lastQuad: DocumentQuad? = null
    private var stableSince: Long = 0

    /**
     * Feed a detection.
     * @return true when an auto-capture should fire NOW (caller is in auto mode).
     */
    fun onDetection(quad: DocumentQuad?, now: Long, autoCapture: Boolean): Boolean {
        if (quad == null) {
            state = CaptureState.SEARCHING
            lastQuad = null
            stableSince = 0
            return false
        }

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
            if (autoCapture) {
                markCaptured()
                return true
            }
        }
        return false
    }

    /** Call after a capture completes. In batch mode we resume scanning the next page. */
    fun afterCapture(batchMode: Boolean) {
        lastQuad = null
        stableSince = 0
        state = if (batchMode) CaptureState.SEARCHING else CaptureState.CAPTURED
    }

    private fun markCaptured() {
        state = CaptureState.CAPTURED
        lastQuad = null
        stableSince = 0
    }

    private fun quadsDiverge(a: DocumentQuad, b: DocumentQuad): Boolean =
        a.corners.zip(b.corners).any { (p, q) ->
            abs(p.x - q.x) > movementTolerancePx || abs(p.y - q.y) > movementTolerancePx
        }
}
