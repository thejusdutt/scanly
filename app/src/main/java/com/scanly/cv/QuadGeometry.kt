package com.scanly.cv

import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure quad math for document detection: corner ordering, validation, candidate scoring
 * and the line-fit corner refinement. Deliberately free of Android and OpenCV types so
 * every routine is unit-testable on the plain JVM (see QuadGeometryTest).
 */
object QuadGeometry {

    /** An infinite 2D line through ([px], [py]) with unit direction ([dx], [dy]). */
    data class Line(val px: Float, val py: Float, val dx: Float, val dy: Float)

    /** Acceptance limits for [validate]. Pixel values assume a ~640 px working image. */
    data class Constraints(
        /** Quad area as a fraction of the frame area. */
        val minAreaRatio: Float = 0.04f,
        val maxAreaRatio: Float = 1.02f,
        /** Perspective skew allowance; 90° is a straight-on rectangle. */
        val minInteriorAngleDeg: Float = 45f,
        val maxInteriorAngleDeg: Float = 135f,
        /** Shortest allowed side, as a fraction of min(frameW, frameH). */
        val minSideFrac: Float = 0.08f,
        /** Corners this close to the frame edge count as "on the border". */
        val borderMarginPx: Float = 6f,
        /** Line-built corners may poke this far outside the frame (page edge off-screen). */
        val maxOutsideFrac: Float = 0.10f,
    )

    /**
     * Order arbitrary 4 points into TL, TR, BR, BL: sort into a ring around the centroid,
     * then start the ring at the corner nearest the top-left. Unlike the classic
     * sum/difference heuristic this never assigns one point to two roles for strongly
     * rotated quads (a 45° "diamond" breaks sum/diff and yields a degenerate warp).
     */
    fun orderCorners(pts: List<QuadPoint>): DocumentQuad {
        require(pts.size == 4) { "need exactly 4 points, got ${pts.size}" }
        var cx = 0f; var cy = 0f
        for (p in pts) { cx += p.x; cy += p.y }
        cx /= 4f; cy /= 4f
        // Ascending atan2 in image coords (y down) walks TL → TR → BR → BL.
        val ring = pts.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = (0..3).minBy { ring[it].x + ring[it].y }
        return DocumentQuad(
            ring[start], ring[(start + 1) % 4], ring[(start + 2) % 4], ring[(start + 3) % 4],
        )
    }

    /** Shoelace area of an ordered (non-self-intersecting) polygon. */
    fun area(corners: List<QuadPoint>): Float {
        var s = 0f
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** Interior angle at each corner of an ordered quad, in degrees. */
    fun interiorAnglesDeg(corners: List<QuadPoint>): FloatArray = FloatArray(4) { i ->
        val prev = corners[(i + 3) % 4]
        val v = corners[i]
        val next = corners[(i + 1) % 4]
        val ax = prev.x - v.x; val ay = prev.y - v.y
        val bx = next.x - v.x; val by = next.y - v.y
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        if (la < 1e-3f || lb < 1e-3f) return@FloatArray 0f
        val cosA = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
        Math.toDegrees(acos(cosA).toDouble()).toFloat()
    }

    /** True when the ordered quad is strictly convex (no straight or reflex corners). */
    fun isConvex(corners: List<QuadPoint>): Boolean {
        var sign = 0
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Geometric plausibility gate for a candidate page quad in a [frameW]×[frameH] image. */
    fun validate(
        quad: DocumentQuad,
        frameW: Float,
        frameH: Float,
        c: Constraints = Constraints(),
    ): Boolean {
        val corners = quad.corners
        val slack = c.maxOutsideFrac * max(frameW, frameH)
        if (corners.any {
                it.x < -slack || it.x > frameW + slack || it.y < -slack || it.y > frameH + slack
            }
        ) return false
        if (!isConvex(corners)) return false

        val ratio = area(corners) / (frameW * frameH)
        if (ratio < c.minAreaRatio || ratio > c.maxAreaRatio) return false

        val angles = interiorAnglesDeg(corners)
        if (angles.any { it < c.minInteriorAngleDeg || it > c.maxInteriorAngleDeg }) return false

        val minSide = c.minSideFrac * min(frameW, frameH)
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            if (hypot(b.x - a.x, b.y - a.y) < minSide) return false
        }

        // Every corner pinned to the border = the frame itself (binarization artifact).
        val m = c.borderMarginPx
        val allOnBorder = corners.all {
            (it.x < m || it.x > frameW - m) && (it.y < m || it.y > frameH - m)
        }
        return !allOnBorder
    }

    /** 1.0 for a perfect rectangle, decaying to 0 as corners drift from 90°. */
    fun angleQuality(quad: DocumentQuad): Float {
        var acc = 1.0
        for (a in interiorAnglesDeg(quad.corners)) {
            acc *= max(0f, 1f - abs(a - 90f) / 55f).toDouble()
        }
        return acc.pow(0.25).toFloat()
    }

    /**
     * Rank a candidate. Edge support dominates (squared): a quad whose perimeter isn't
     * backed by actual image edges is a guess, no matter how big or rectangular. Area
     * breaks ties toward the larger of equally-supported quads (page beats a text block
     * inside it), and [angleQuality] punishes skew. [priorProximity] ∈ [0,1] nudges the
     * choice toward the quad the user was just shown (see [priorProximity]).
     */
    fun score(
        areaRatio: Float,
        angleQuality: Float,
        edgeSupport: Float,
        priorProximity: Float = 0f,
    ): Float =
        edgeSupport * edgeSupport *
            sqrt(areaRatio.coerceIn(0f, 1f)) *
            angleQuality *
            (1f + 0.5f * priorProximity)

    /** 1 when [q] sits exactly on [prior], falling linearly to 0 at mean corner distance [tolerancePx]. */
    fun priorProximity(q: DocumentQuad, prior: DocumentQuad, tolerancePx: Float): Float {
        if (tolerancePx <= 0f) return 0f
        var sum = 0f
        for (i in 0..3) {
            val a = q.corners[i]
            val b = prior.corners[i]
            sum += hypot(b.x - a.x, b.y - a.y)
        }
        return (1f - sum / 4f / tolerancePx).coerceIn(0f, 1f)
    }

    /**
     * [n] evenly spaced points along each side, skipping [endInsetFrac] of the length at
     * both ends — corners are often rounded or damaged and must not count against the
     * sides' edge evidence. Sides are ordered TL→TR, TR→BR, BR→BL, BL→TL.
     */
    fun sideSamples(quad: DocumentQuad, n: Int, endInsetFrac: Float): List<List<QuadPoint>> {
        val c = quad.corners
        return (0..3).map { i ->
            val a = c[i]
            val b = c[(i + 1) % 4]
            (0 until n).map { k ->
                val t = endInsetFrac + (1f - 2f * endInsetFrac) * (k + 0.5f) / n
                QuadPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
    }

    /** Total-least-squares line through [points] (principal axis), or null if degenerate. */
    fun fitLine(points: List<QuadPoint>): Line? {
        if (points.size < 2) return null
        var mx = 0f; var my = 0f
        for (p in points) { mx += p.x; my += p.y }
        mx /= points.size; my /= points.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in points) {
            val dx = (p.x - mx).toDouble()
            val dy = (p.y - my).toDouble()
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        if (sxx + syy < 1e-6) return null // all points coincide
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        return Line(mx, my, cos(theta).toFloat(), sin(theta).toFloat())
    }

    /** Intersection of two infinite lines, or null when near-parallel. */
    fun intersect(a: Line, b: Line): QuadPoint? {
        val denom = a.dx * b.dy - a.dy * b.dx
        if (abs(denom) < 1e-4f) return null // directions are unit vectors
        val t = ((b.px - a.px) * b.dy - (b.py - a.py) * b.dx) / denom
        return QuadPoint(a.px + t * a.dx, a.py + t * a.dy)
    }

    /**
     * Replace each corner with the intersection of total-least-squares lines fitted to
     * edge points sampled along the two adjacent sides. A corner falls back to its
     * coarse position when either side has fewer than [minPointsPerSide] points, the
     * lines are near-parallel, or the refined corner moves further than [maxShiftPx] —
     * refinement is only allowed to polish, never to relocate.
     *
     * [sidePoints] follows [sideSamples] order: sidePoints[i] belongs to the side from
     * corner i to corner i+1, so corner i is the meet of sides (i+3)%4 and i.
     */
    fun refineCorners(
        coarse: DocumentQuad,
        sidePoints: List<List<QuadPoint>>,
        minPointsPerSide: Int,
        maxShiftPx: Float,
    ): DocumentQuad {
        val lines = (0..3).map { i ->
            sidePoints[i].takeIf { it.size >= minPointsPerSide }?.let(::fitLine)
        }
        val corners = coarse.corners
        val refined = (0..3).map { i ->
            val la = lines[(i + 3) % 4]
            val lb = lines[i]
            val p = if (la != null && lb != null) intersect(la, lb) else null
            if (p != null && hypot(p.x - corners[i].x, p.y - corners[i].y) <= maxShiftPx) {
                p
            } else {
                corners[i]
            }
        }
        return DocumentQuad(refined[0], refined[1], refined[2], refined[3])
    }

    /** Scale all corners by ([sx], [sy]) — e.g. working-resolution ↔ full-resolution. */
    fun scale(q: DocumentQuad, sx: Float, sy: Float): DocumentQuad = DocumentQuad(
        QuadPoint(q.topLeft.x * sx, q.topLeft.y * sy),
        QuadPoint(q.topRight.x * sx, q.topRight.y * sy),
        QuadPoint(q.bottomRight.x * sx, q.bottomRight.y * sy),
        QuadPoint(q.bottomLeft.x * sx, q.bottomLeft.y * sy),
    )
}
