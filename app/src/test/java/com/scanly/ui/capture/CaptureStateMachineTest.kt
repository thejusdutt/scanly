package com.scanly.ui.capture

import com.google.common.truth.Truth.assertThat
import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import org.junit.Test

/**
 * Guards two regressions:
 *  1. auto-capture must NOT exit continuous scanning (the leading FOSS scanner's bug) —
 *     after an auto-capture the machine returns to SEARCHING for the next page;
 *  2. auto-capture must NOT machine-gun the SAME page — it only fires again once the
 *     scene actually changes (document leaves the frame, or a clearly different
 *     placement appears). Time alone never re-arms: a page left lying under the camera
 *     used to be re-captured every cooldown interval, flooding the document with
 *     duplicates.
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
    fun same_unmoved_page_never_refires_no_matter_how_long() {
        val m = CaptureStateMachine(stableHoldMs = 800, rearmCooldownMs = 2500)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires at 900
        m.afterCapture(now = 900)
        m.onDetection(quad(), now = 1000, autoCapture = true)
        // Way past any cooldown: the page just lies there — must NOT re-capture.
        assertThat(m.onDetection(quad(), now = 3500, autoCapture = true)).isFalse()
        assertThat(m.onDetection(quad(), now = 60_000, autoCapture = true)).isFalse()
    }

    @Test
    fun replacement_page_slid_in_without_emptying_the_frame_fires() {
        val m = CaptureStateMachine(stableHoldMs = 800, rearmCooldownMs = 2500, rearmJumpPx = 96f)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires at 900
        m.afterCapture(now = 900)
        m.onDetection(quad(), now = 1000, autoCapture = true)
        // A new sheet slides under the camera: every corner jumps far -> re-arms.
        m.onDetection(quad(dx = 150f), now = 3600, autoCapture = true)
        assertThat(m.onDetection(quad(dx = 150f), now = 4500, autoCapture = true)).isTrue()
    }

    @Test
    fun manual_capture_disarms_auto_for_the_page_still_in_frame() {
        val m = CaptureStateMachine(stableHoldMs = 800, rearmCooldownMs = 2500)
        m.onDetection(quad(), now = 0, autoCapture = true)
        // User taps the shutter before the stable hold elapses.
        m.afterCapture(now = 300)
        m.onDetection(quad(), now = 400, autoCapture = true)
        // Without disarming, auto mode re-shoots the same page one hold later.
        assertThat(m.onDetection(quad(), now = 1300, autoCapture = true)).isFalse()
        assertThat(m.onDetection(quad(), now = 9000, autoCapture = true)).isFalse()
        // Swap to a genuinely new page -> normal auto-capture resumes.
        m.onDetection(null, now = 9100, autoCapture = true)
        m.onDetection(quad(), now = 9200, autoCapture = true)
        assertThat(m.onDetection(quad(), now = 10_100, autoCapture = true)).isTrue()
    }

    @Test
    fun holdProgress_ramps_while_steady_and_resets_after_fire() {
        val m = CaptureStateMachine(stableHoldMs = 800)
        m.onDetection(quad(), now = 0, autoCapture = true)
        assertThat(m.holdProgress(now = 400)).isWithin(0.01f).of(0.5f)
        assertThat(m.holdProgress(now = 800)).isEqualTo(1f)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires
        // Fired: the countdown ring must vanish, not sit at 100 %.
        assertThat(m.holdProgress(now = 950)).isEqualTo(0f)
    }

    @Test
    fun holdProgress_is_zero_for_a_page_that_will_not_fire() {
        val m = CaptureStateMachine(stableHoldMs = 800, rearmCooldownMs = 2500)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires
        m.afterCapture(now = 900)
        // Same page still in frame — disarmed, so no countdown may show.
        m.onDetection(quad(), now = 1000, autoCapture = true)
        assertThat(m.holdProgress(now = 1400)).isEqualTo(0f)
        assertThat(m.holdProgress(now = 60_000)).isEqualTo(0f)
    }

    @Test
    fun rearm_lets_the_same_placement_fire_again_after_a_retake() {
        val m = CaptureStateMachine(stableHoldMs = 800, rearmCooldownMs = 2500)
        m.onDetection(quad(), now = 0, autoCapture = true)
        m.onDetection(quad(), now = 900, autoCapture = true) // fires
        m.afterCapture(now = 900)
        // User rejects the shot on the confirm overlay: the page hasn't moved, but it
        // must be capturable again without leaving the frame first.
        m.rearm()
        m.onDetection(quad(), now = 1000, autoCapture = true)
        assertThat(m.onDetection(quad(), now = 1900, autoCapture = true)).isTrue()
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
