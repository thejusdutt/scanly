package com.scanly.cv

import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class QuadGeometryTest {

    private fun p(x: Float, y: Float) = QuadPoint(x, y)

    private fun assertPointNear(expected: QuadPoint, actual: QuadPoint, tol: Float = 0.5f) {
        assertTrue(
            "expected ($expected) got ($actual)",
            hypot(actual.x - expected.x, actual.y - expected.y) <= tol,
        )
    }

    // ---- ordering ----

    @Test
    fun `orders shuffled axis-aligned corners`() {
        val quad = QuadGeometry.orderCorners(
            listOf(p(380f, 500f), p(100f, 100f), p(100f, 500f), p(380f, 100f)),
        )
        assertEquals(p(100f, 100f), quad.topLeft)
        assertEquals(p(380f, 100f), quad.topRight)
        assertEquals(p(380f, 500f), quad.bottomRight)
        assertEquals(p(100f, 500f), quad.bottomLeft)
    }

    @Test
    fun `orders a 45-degree diamond into four distinct corners`() {
        // The classic sum/diff heuristic assigns one point to two roles here,
        // producing a degenerate quad and a garbage warp.
        val pts = listOf(p(100f, 0f), p(200f, 100f), p(100f, 200f), p(0f, 100f))
        val quad = QuadGeometry.orderCorners(pts.shuffled(kotlin.random.Random(42)))
        val corners = quad.corners
        assertEquals(4, corners.toSet().size)
        assertTrue(corners.containsAll(pts))
        // Ring order must be preserved (consecutive corners are adjacent, never diagonal).
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            assertEquals(100f * kotlin.math.sqrt(2f), hypot(b.x - a.x, b.y - a.y), 0.01f)
        }
    }

    @Test
    fun `orders a mildly rotated page`() {
        // ~15° rotated rectangle: TL must still be the corner nearest the top-left.
        val quad = QuadGeometry.orderCorners(
            listOf(p(120f, 60f), p(410f, 138f), p(330f, 438f), p(40f, 360f)),
        )
        assertEquals(p(120f, 60f), quad.topLeft)
        assertEquals(p(410f, 138f), quad.topRight)
        assertEquals(p(330f, 438f), quad.bottomRight)
        assertEquals(p(40f, 360f), quad.bottomLeft)
    }

    // ---- metrics ----

    @Test
    fun `shoelace area of a square`() {
        val corners = listOf(p(0f, 0f), p(10f, 0f), p(10f, 10f), p(0f, 10f))
        assertEquals(100f, QuadGeometry.area(corners), 0.001f)
    }

    @Test
    fun `interior angles of a rectangle are all 90`() {
        val corners = listOf(p(0f, 0f), p(20f, 0f), p(20f, 10f), p(0f, 10f))
        QuadGeometry.interiorAnglesDeg(corners).forEach { assertEquals(90f, it, 0.01f) }
    }

    @Test
    fun `convexity check`() {
        assertTrue(QuadGeometry.isConvex(listOf(p(0f, 0f), p(10f, 1f), p(9f, 10f), p(1f, 9f))))
        // Chevron: one reflex corner.
        assertFalse(QuadGeometry.isConvex(listOf(p(0f, 0f), p(10f, 0f), p(5f, 5f), p(10f, 10f))))
    }

    @Test
    fun `angle quality is 1 for a rectangle and lower for a skewed quad`() {
        val rect = QuadGeometry.orderCorners(listOf(p(0f, 0f), p(20f, 0f), p(20f, 10f), p(0f, 10f)))
        val skewed = QuadGeometry.orderCorners(listOf(p(0f, 0f), p(20f, 6f), p(20f, 10f), p(0f, 16f)))
        assertEquals(1f, QuadGeometry.angleQuality(rect), 0.01f)
        assertTrue(QuadGeometry.angleQuality(skewed) < QuadGeometry.angleQuality(rect))
    }

    // ---- lines ----

    @Test
    fun `intersects perpendicular lines`() {
        val horizontal = QuadGeometry.Line(0f, 5f, 1f, 0f)
        val vertical = QuadGeometry.Line(3f, 0f, 0f, 1f)
        assertPointNear(p(3f, 5f), QuadGeometry.intersect(horizontal, vertical)!!, 0.001f)
    }

    @Test
    fun `parallel lines do not intersect`() {
        val a = QuadGeometry.Line(0f, 0f, 1f, 0f)
        val b = QuadGeometry.Line(0f, 5f, 1f, 0f)
        assertNull(QuadGeometry.intersect(a, b))
    }

    @Test
    fun `fits a line through noisy horizontal points`() {
        val pts = (0..20).map { p(it * 5f, 40f + (if (it % 2 == 0) 0.4f else -0.4f)) }
        val line = QuadGeometry.fitLine(pts)
        assertNotNull(line)
        // Direction is horizontal (up to sign), and the line passes near y = 40.
        assertTrue(abs(line!!.dy) < 0.02f)
        assertEquals(40f, line.py, 0.5f)
    }

    @Test
    fun `line fit needs two distinct points`() {
        assertNull(QuadGeometry.fitLine(emptyList()))
        assertNull(QuadGeometry.fitLine(listOf(p(1f, 1f))))
        assertNull(QuadGeometry.fitLine(listOf(p(1f, 1f), p(1f, 1f), p(1f, 1f))))
    }

    // ---- refinement ----

    private val coarseSquare = DocumentQuad(p(10f, 10f), p(110f, 10f), p(110f, 110f), p(10f, 110f))

    /** Edge points exactly on the true rectangle x∈[12,108], y∈[12,108]. */
    private fun trueSidePoints(): List<List<QuadPoint>> {
        val ts = (0..9).map { 20f + it * 8f }
        return listOf(
            ts.map { p(it, 12f) },   // top
            ts.map { p(108f, it) },  // right
            ts.map { p(it, 108f) },  // bottom
            ts.map { p(12f, it) },   // left
        )
    }

    @Test
    fun `refines corners onto side-line intersections`() {
        val refined = QuadGeometry.refineCorners(
            coarseSquare, trueSidePoints(), minPointsPerSide = 5, maxShiftPx = 8f,
        )
        assertPointNear(p(12f, 12f), refined.topLeft)
        assertPointNear(p(108f, 12f), refined.topRight)
        assertPointNear(p(108f, 108f), refined.bottomRight)
        assertPointNear(p(12f, 108f), refined.bottomLeft)
    }

    @Test
    fun `refinement falls back per-corner when a side has too few points`() {
        val sides = trueSidePoints().toMutableList()
        sides[0] = emptyList() // no evidence for the top edge
        val refined = QuadGeometry.refineCorners(coarseSquare, sides, 5, 8f)
        // Corners touching the top side keep their coarse positions…
        assertEquals(coarseSquare.topLeft, refined.topLeft)
        assertEquals(coarseSquare.topRight, refined.topRight)
        // …while the bottom corners still refine.
        assertPointNear(p(108f, 108f), refined.bottomRight)
        assertPointNear(p(12f, 108f), refined.bottomLeft)
    }

    @Test
    fun `refinement never relocates beyond the shift budget`() {
        // Evidence lines 30 px inside the coarse quad: a shift that large means the
        // coarse quad was wrong, not imprecise — refinement must not follow it.
        val ts = (0..9).map { 30f + it * 6f }
        val farSides = listOf(
            ts.map { p(it, 40f) },
            ts.map { p(80f, it) },
            ts.map { p(it, 80f) },
            ts.map { p(40f, it) },
        )
        val refined = QuadGeometry.refineCorners(coarseSquare, farSides, 5, 8f)
        assertEquals(coarseSquare.corners, refined.corners)
    }

    // ---- validation ----

    private val frameW = 480f
    private val frameH = 640f

    private fun quadOf(tl: QuadPoint, tr: QuadPoint, br: QuadPoint, bl: QuadPoint) =
        DocumentQuad(tl, tr, br, bl)

    @Test
    fun `accepts a centered page quad`() {
        val q = quadOf(p(90f, 120f), p(390f, 130f), p(380f, 520f), p(80f, 510f))
        assertTrue(QuadGeometry.validate(q, frameW, frameH))
    }

    @Test
    fun `rejects the frame-border quad`() {
        val q = quadOf(p(1f, 1f), p(479f, 1f), p(479f, 639f), p(1f, 639f))
        assertFalse(QuadGeometry.validate(q, frameW, frameH))
    }

    @Test
    fun `rejects a tiny quad`() {
        val q = quadOf(p(200f, 200f), p(260f, 200f), p(260f, 260f), p(200f, 260f))
        assertFalse(QuadGeometry.validate(q, frameW, frameH))
    }

    @Test
    fun `rejects extreme skew`() {
        // Sheared parallelogram with ~41° corners — outside the [45°, 135°] window.
        val q = quadOf(p(60f, 60f), p(420f, 60f), p(500f, 130f), p(140f, 130f))
        assertFalse(QuadGeometry.validate(q, frameW, frameH))
    }

    @Test
    fun `rejects a quad with a collapsed side`() {
        val q = quadOf(p(100f, 100f), p(120f, 100f), p(380f, 500f), p(90f, 480f))
        assertFalse(QuadGeometry.validate(q, frameW, frameH))
    }

    @Test
    fun `rejects corners far outside the frame`() {
        val q = quadOf(p(-200f, 100f), p(380f, 100f), p(380f, 500f), p(-200f, 500f))
        assertFalse(QuadGeometry.validate(q, frameW, frameH))
    }

    @Test
    fun `accepts corners slightly outside the frame`() {
        // A page whose left edge is just off-screen is a normal capture.
        val q = quadOf(p(-20f, 100f), p(380f, 100f), p(380f, 500f), p(-20f, 500f))
        assertTrue(QuadGeometry.validate(q, frameW, frameH))
    }

    // ---- scoring ----

    @Test
    fun `edge evidence beats raw size`() {
        // A big phantom blob with weak edge backing must lose to a smaller,
        // well-supported page quad.
        val phantom = QuadGeometry.score(areaRatio = 0.85f, angleQuality = 0.9f, edgeSupport = 0.35f)
        val realPage = QuadGeometry.score(areaRatio = 0.35f, angleQuality = 0.85f, edgeSupport = 0.9f)
        assertTrue(realPage > phantom)
    }

    @Test
    fun `prior proximity boosts an otherwise equal candidate`() {
        val base = QuadGeometry.score(0.4f, 0.9f, 0.8f, priorProximity = 0f)
        val boosted = QuadGeometry.score(0.4f, 0.9f, 0.8f, priorProximity = 1f)
        assertEquals(base * 1.5f, boosted, 0.0001f)
    }

    @Test
    fun `prior proximity is 1 on the prior and 0 beyond tolerance`() {
        val q = quadOf(p(100f, 100f), p(380f, 100f), p(380f, 500f), p(100f, 500f))
        val far = quadOf(p(300f, 300f), p(580f, 300f), p(580f, 700f), p(300f, 700f))
        assertEquals(1f, QuadGeometry.priorProximity(q, q, 50f), 0.0001f)
        assertEquals(0f, QuadGeometry.priorProximity(q, far, 50f), 0.0001f)
    }

    // ---- sampling & scaling ----

    @Test
    fun `side samples stay inside the inset segment`() {
        val q = quadOf(p(0f, 0f), p(100f, 0f), p(100f, 100f), p(0f, 100f))
        val sides = QuadGeometry.sideSamples(q, n = 10, endInsetFrac = 0.1f)
        assertEquals(4, sides.size)
        sides.forEach { assertEquals(10, it.size) }
        // Top side runs x 0→100 at y=0; all samples within the inset window.
        sides[0].forEach {
            assertTrue(it.x > 10f && it.x < 90f)
            assertEquals(0f, it.y, 0.001f)
        }
    }

    @Test
    fun `scale multiplies coordinates`() {
        val q = quadOf(p(10f, 20f), p(30f, 20f), p(30f, 40f), p(10f, 40f))
        val s = QuadGeometry.scale(q, 2f, 0.5f)
        assertEquals(p(20f, 10f), s.topLeft)
        assertEquals(p(60f, 10f), s.topRight)
        assertEquals(p(60f, 20f), s.bottomRight)
        assertEquals(p(20f, 20f), s.bottomLeft)
    }
}
