package com.scanly.ui.capture

import com.google.common.truth.Truth.assertThat
import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import org.junit.Test

/**
 * Guards the specific regression that plagues the leading FOSS scanner: auto-capture
 * must NOT exit batch mode. After an auto-capture in batch mode, the machine must return
 * to SEARCHING so the next page can be scanned.
 */
class CaptureStateMachineTest {

    private fun quad(dx: Float = 0f) = DocumentQuad(
        QuadPoint(10f + dx, 10f), QuadPoint(90f + dx, 10f),
        QuadPoint(90f + dx, 90f), QuadPoint(10f + dx, 90f),
    )

    @Test
    fun autoCapture_fires_after_stable_hold() {
        val m = CaptureStateMachine(stableHoldMs = 800)
        assertThat(m.onDetection(quad(), now = 0, autoCapture = true)).isFalse()
        // Same quad still present past the hold window -> should fire.
        assertThat(m.onDetection(quad(), now = 900, autoCapture = true)).isTrue()
        assertThat(m.state).isEqualTo(CaptureState.CAPTURED)
    }

    @Test
    fun batchMode_returns_to_searching_after_capture() {
        val m = CaptureStateMachine(stableHoldMs = 800)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires
        m.afterCapture(batchMode = true)
        assertThat(m.state).isEqualTo(CaptureState.SEARCHING)

        // And it can detect + auto-capture the NEXT page without any reset.
        m.onDetection(quad(), now = 1000, autoCapture = true)
        assertThat(m.onDetection(quad(), now = 1900, autoCapture = true)).isTrue()
    }

    @Test
    fun nonBatch_ends_capture() {
        val m = CaptureStateMachine()
        m.afterCapture(batchMode = false)
        assertThat(m.state).isEqualTo(CaptureState.CAPTURED)
    }

    @Test
    fun movement_resets_stability() {
        val m = CaptureStateMachine(stableHoldMs = 800, movementTolerancePx = 24f)
        m.onDetection(quad(), now = 0, autoCapture = true)
        // Quad jumps > tolerance -> timer resets, no capture even past original window.
        assertThat(m.onDetection(quad(dx = 100f), now = 900, autoCapture = true)).isFalse()
        assertThat(m.state).isEqualTo(CaptureState.SEARCHING)
    }
}
