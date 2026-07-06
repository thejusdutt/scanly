package com.scanly.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {

    @Test
    fun `auto is full-bleed at image size`() {
        val p = PageLayout.place(3000f, 4000f, PdfPageSize.AUTO)
        assertEquals(3000f, p.pageW, 0f)
        assertEquals(4000f, p.pageH, 0f)
        assertEquals(0f, p.x, 0f)
        assertEquals(0f, p.y, 0f)
        assertEquals(3000f, p.drawW, 0f)
        assertEquals(4000f, p.drawH, 0f)
    }

    @Test
    fun `portrait scan fits a4 portrait, centered, aspect preserved`() {
        val p = PageLayout.place(3000f, 4000f, PdfPageSize.A4)
        assertEquals(595f, p.pageW, 0f)
        assertEquals(842f, p.pageH, 0f)
        // Aspect preserved.
        assertEquals(3000f / 4000f, p.drawW / p.drawH, 0.0001f)
        // Fits inside and touches at least one pair of edges.
        assertTrue(p.drawW <= 595f + 0.01f && p.drawH <= 842f + 0.01f)
        assertTrue(p.drawW >= 595f - 0.01f || p.drawH >= 842f - 0.01f)
        // Centered.
        assertEquals((595f - p.drawW) / 2f, p.x, 0.001f)
        assertEquals((842f - p.drawH) / 2f, p.y, 0.001f)
    }

    @Test
    fun `landscape scan rotates the preset box`() {
        val p = PageLayout.place(4000f, 3000f, PdfPageSize.LETTER)
        assertEquals(792f, p.pageW, 0f) // letter turned landscape
        assertEquals(612f, p.pageH, 0f)
        assertEquals(4000f / 3000f, p.drawW / p.drawH, 0.0001f)
    }

    @Test
    fun `square scan on legal is width-bound and vertically centered`() {
        val p = PageLayout.place(2000f, 2000f, PdfPageSize.LEGAL)
        assertEquals(612f, p.pageW, 0f)
        assertEquals(1008f, p.pageH, 0f)
        assertEquals(612f, p.drawW, 0.01f)
        assertEquals(0f, p.x, 0.01f)
        assertTrue(p.y > 0f)
    }
}
