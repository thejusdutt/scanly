package com.scanly.ui.capture

import com.google.common.truth.Truth.assertThat
import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import org.junit.Test

/**
 * Guards two regressions:
 *  1. auto-capture must NOT exit continuous scanning (the leading FOSS scanner's bug) —
 *     after an auto-capture the machine returns to SEARCHING for the next page;
 *  2. auto-capture must NOT machine-gun the SAME page — within the re-arm cooldown it
 *     only fires again once the document has left the frame.
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
    fun continuous_mode_returns_to_searching_after_capture() {
        val m = CaptureStateMachine(stableHoldMs = 800)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires
        m.afterCapture()
        assertThat(m.state).isEqualTo(CaptureState.SEARCHING)
    }

    @Test
    fun same_page_does_not_refire_during_cooldown() {
        val m = CaptureStateMachine(stableHoldMs = 800, rearmCooldownMs = 2500)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires
        m.afterCapture()
        // The SAME page is still lying there, holding perfectly still.
        m.onDetection(quad(), now = 1000, autoCapture = true)
        assertThat(m.onDetection(quad(), now = 1900, autoCapture = true)).isFalse()
    }

    @Test
    fun next_page_fires_after_document_leaves_frame() {
        val m = CaptureStateMachine(stableHoldMs = 800, rearmCooldownMs = 2500)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires
        m.afterCapture()
        // Page swap: document leaves the frame → machine re-arms immediately.
        m.onDetection(null, now = 1200, autoCapture = true)
        m.onDetection(quad(), now = 1400, autoCapture = true)
        assertThat(m.onDetection(quad(), now = 2300, autoCapture = true)).isTrue()
    }

    @Test
    fun refires_after_cooldown_even_if_page_never_left() {
        val m = CaptureStateMachine(stableHoldMs = 800, rearmCooldownMs = 2500)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires at 900
        m.afterCapture()
        m.onDetection(quad(), now = 1000, autoCapture = true)
        // Past cooldown (900 + 2500) with a stable hold behind it -> fires again.
        assertThat(m.onDetection(quad(), now = 3500, autoCapture = true)).isTrue()
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
