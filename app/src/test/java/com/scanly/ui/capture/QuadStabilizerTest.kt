package com.scanly.ui.capture

import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QuadStabilizerTest {

    private fun quad(offset: Float) = DocumentQuad(
        QuadPoint(100f + offset, 100f + offset),
        QuadPoint(380f + offset, 100f + offset),
        QuadPoint(380f + offset, 500f + offset),
        QuadPoint(100f + offset, 500f + offset),
    )

    @Test
    fun `first detection needs confirmation before showing`() {
        val s = QuadStabilizer(confirmFrames = 2)
        assertNull(s.update(quad(0f), now = 0))
        assertNotNull(s.update(quad(2f), now = 100))
    }

    @Test
    fun `one-frame spurious quad never shows`() {
        val s = QuadStabilizer(confirmFrames = 2)
        s.update(quad(0f), 0)
        s.update(quad(1f), 100) // confirmed, shown
        // A wildly different quad for a single frame must not replace it.
        val far = quad(300f)
        val shown = s.update(far, 200)
        assertNotNull(shown)
        assertTrue(abs(shown!!.topLeft.x - 100f) < 60f)
    }

    @Test
    fun `jumped quad takes over after persisting`() {
        val s = QuadStabilizer(confirmFrames = 2)
        s.update(quad(0f), 0)
        s.update(quad(1f), 100)
        s.update(quad(300f), 200)
        val shown = s.update(quad(301f), 300)
        assertNotNull(shown)
        assertTrue(shown!!.topLeft.x > 300f)
    }

    @Test
    fun `small movement is smoothed not snapped`() {
        val s = QuadStabilizer(confirmFrames = 1, smoothing = 0.5f)
        s.update(quad(0f), 0)
        val shown = s.update(quad(40f), 100)!!
        // Halfway between 100 and 140, not snapped to 140.
        assertEquals(120f, shown.topLeft.x, 1f)
    }

    @Test
    fun `dropout inside hold window keeps the quad`() {
        val s = QuadStabilizer(confirmFrames = 1, holdMs = 500)
        s.update(quad(0f), 0)
        assertNotNull(s.update(null, 300))
    }

    @Test
    fun `dropout past hold window clears the quad`() {
        val s = QuadStabilizer(confirmFrames = 1, holdMs = 500)
        s.update(quad(0f), 0)
        assertNull(s.update(null, 900))
        // And the next detection starts a fresh confirmation cycle.
        val s2 = QuadStabilizer(confirmFrames = 2, holdMs = 500)
        s2.update(quad(0f), 0)
        s2.update(null, 900)
        assertNull(s2.update(quad(0f), 1000))
    }

    @Test
    fun `reset clears state`() {
        val s = QuadStabilizer(confirmFrames = 1)
        s.update(quad(0f), 0)
        s.reset()
        assertNull(s.update(null, 100))
    }
}
