package com.scanly.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BookSplitterTest {

    /** A bright spread profile with a dark gutter valley at [valleyAt]. */
    private fun profile(size: Int, valleyAt: Int, depth: Float): FloatArray =
        FloatArray(size) { i ->
            val d = abs(i - valleyAt)
            // ~20px-wide V-shaped shadow.
            (230f - (depth * (1f - d / 20f)).coerceAtLeast(0f))
        }

    @Test
    fun `finds a clear gutter shadow off-center`() {
        val means = profile(1000, valleyAt = 470, depth = 60f)
        val spine = BookSplitter.pickSpine(means, 350, 650)
        assertTrue("spine=$spine", abs(spine - 470) <= 5)
    }

    @Test
    fun `shallow valley falls back to the band center`() {
        // Depth 3 grey levels — noise, not a gutter.
        val means = profile(1000, valleyAt = 430, depth = 3f)
        assertEquals(500, BookSplitter.pickSpine(means, 350, 650))
    }

    @Test
    fun `flat profile picks the center`() {
        val means = FloatArray(800) { 240f }
        assertEquals(500, BookSplitter.pickSpine(means, 400, 600))
    }

    @Test
    fun `single dark text column is smoothed away`() {
        // One 2px-wide dark stroke (text rule) must not win over the wide gutter.
        val means = profile(1000, valleyAt = 520, depth = 50f)
        means[400] = 40f
        means[401] = 40f
        val spine = BookSplitter.pickSpine(means, 350, 650)
        assertTrue("spine=$spine", abs(spine - 520) <= 5)
    }

    @Test
    fun `degenerate band returns its center`() {
        assertEquals(5, BookSplitter.pickSpine(FloatArray(10) { 200f }, 5, 5))
    }
}
