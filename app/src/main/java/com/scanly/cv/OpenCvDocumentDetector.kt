package com.scanly.cv

import android.graphics.Bitmap
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Document boundary detection, 100% on-device and shared by both flavors.
 *
 * The pipeline runs at a bounded working size (≤[WORK_MAX_DIM] px), so a 480 px preview
 * frame and a 12 MP capture behave identically and cost the same:
 *
 *  1. CANDIDATES — five binarizations feed one contour search: median-auto Canny
 *     (adapts to dark/bright scenes), adaptive threshold in BOTH polarities (light page
 *     on dark desk AND dark page on light desk), and Otsu in both polarities (clean
 *     global-contrast scenes). Contours are approximated via their convex hull, which
 *     bridges small occlusions (a finger over an edge) that break the raw outline.
 *  2. EVIDENCE SCORING — every candidate is validated geometrically ([QuadGeometry])
 *     and then scored against the Canny edge map: a quad whose perimeter isn't backed
 *     by real image edges is a guess and is gated out, no matter how big or
 *     rectangular. This is what stops binarization phantom-blobs from beating the page.
 *  3. HOUGH FALLBACK — when no contour candidate scores well (broken outline: finger,
 *     shadow, low-contrast side), quads are built from the strongest straight lines in
 *     two roughly perpendicular direction clusters and scored the same way.
 *  4. REFINEMENT — the winner's sides are re-measured against the edge map
 *     (perpendicular probes → total-least-squares line per side → corner
 *     intersections), which removes the dilation bias of contour tracing and gives
 *     sub-pixel corners. Refinement is shift-bounded: it polishes, never relocates.
 *
 * Callers may pass a `prior` quad (e.g. the stabilized live-overlay quad at capture
 * time): candidates near it get a score boost, so the final crop matches what the user
 * was shown on screen. Stateless and safe to call from multiple threads.
 */
class OpenCvDocumentDetector @Inject constructor() : DocumentDetector {

    override fun detect(frame: Bitmap, prior: DocumentQuad?): DocumentQuad? {
        if (min(frame.width, frame.height) < 64) return null
        if (!OpenCvInitializer.ensure()) return null

        val rgba = Mat().also { Utils.bitmapToMat(frame, it) }

        // ---- Normalize to working scale ----
        val downscale = min(1f, WORK_MAX_DIM.toFloat() / max(rgba.cols(), rgba.rows()))
        val work: Mat
        if (downscale < 1f) {
            work = Mat()
            Imgproc.resize(rgba, work, Size(), downscale.toDouble(), downscale.toDouble(), Imgproc.INTER_AREA)
            rgba.release()
        } else {
            work = rgba
        }
        val w = work.cols()
        val h = work.rows()
        // Exact per-axis ratios (resize rounds), for mapping prior in / result out.
        val toWorkX = w.toFloat() / frame.width
        val toWorkY = h.toFloat() / frame.height

        val grey = Mat()
        Imgproc.cvtColor(work, grey, Imgproc.COLOR_RGBA2GRAY)
        work.release()
        Imgproc.GaussianBlur(grey, grey, Size(5.0, 5.0), 0.0)

        // ---- Edge evidence map: median-auto Canny, dilated by one step ----
        val median = medianIntensity(grey)
        val lower = (0.66 * median).coerceIn(10.0, 245.0)
        val upper = (1.33 * median).coerceIn(lower + 10.0, 255.0)
        val canny = Mat()
        Imgproc.Canny(grey, canny, lower, upper)
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val support = Mat()
        Imgproc.dilate(canny, support, kernel3)
        val supportBytes = ByteArray(w * h).also { support.get(0, 0, it) }

        val workPrior = prior?.let { QuadGeometry.scale(it, toWorkX, toWorkY) }
        val scorer = CandidateScorer(supportBytes, w, h, workPrior)

        // ---- Pass 1: contours of the (dilated) Canny map ----
        collectContourQuads(support, scorer)
        support.release()

        // ---- Passes 2–5: region binarizations, both polarities ----
        val kernel5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val thresh = Mat()
        for (mode in intArrayOf(Imgproc.THRESH_BINARY, Imgproc.THRESH_BINARY_INV)) {
            Imgproc.adaptiveThreshold(
                grey, thresh, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, mode, 21, 5.0,
            )
            Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel5)
            collectContourQuads(thresh, scorer)
        }
        for (mode in intArrayOf(Imgproc.THRESH_BINARY, Imgproc.THRESH_BINARY_INV)) {
            Imgproc.threshold(grey, thresh, 0.0, 255.0, mode or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel5)
            collectContourQuads(thresh, scorer)
        }
        thresh.release()
        kernel5.release()
        grey.release()

        // ---- Hough fallback for broken outlines ----
        if (scorer.bestScore < HOUGH_SKIP_SCORE) {
            collectHoughQuads(canny, w, h, scorer)
        }
        canny.release()
        kernel3.release()

        val best = scorer.best?.takeIf { scorer.bestScore >= MIN_SCORE } ?: return null
        val refined = refine(best, supportBytes, w, h)
        return QuadGeometry.scale(refined, 1f / toWorkX, 1f / toWorkY)
    }

    /** Find 4-gon candidates in a binary image via convex hulls of the biggest contours. */
    private fun collectContourQuads(binary: Mat, scorer: CandidateScorer) {
        val frameArea = (binary.cols() * binary.rows()).toDouble()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binary, contours, hierarchy,
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        hierarchy.release()

        val top = contours
            .map { it to Imgproc.contourArea(it) }
            .filter { (_, area) -> area >= frameArea * 0.02 }
            .sortedByDescending { (_, area) -> area }
            .take(TOP_CONTOURS)

        for ((contour, _) in top) {
            val hullIdx = MatOfInt()
            Imgproc.convexHull(contour, hullIdx)
            val pts = contour.toArray()
            val hull = hullIdx.toArray().map { pts[it] }
            hullIdx.release()
            if (hull.size < 4) continue

            val hull2f = MatOfPoint2f(*hull.toTypedArray())
            val peri = Imgproc.arcLength(hull2f, true)
            val approx = MatOfPoint2f()
            // Slightly rounded/damaged corners need a looser epsilon; walk tight → loose.
            for (eps in EPSILON_LADDER) {
                Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
                val n = approx.total()
                if (n == 4L) {
                    scorer.offer(approx.toArray().map { QuadPoint(it.x.toFloat(), it.y.toFloat()) })
                }
                if (n <= 4L) break
            }
            approx.release()
            hull2f.release()
        }
        contours.forEach { it.release() }
    }

    /**
     * Build quads from straight edges: cluster Hough segments into two roughly
     * perpendicular direction groups, then intersect every pair-of-pairs. Survives
     * outlines that contour tracing can't close.
     */
    private fun collectHoughQuads(canny: Mat, w: Int, h: Int, scorer: CandidateScorer) {
        val minDim = min(w, h)
        val linesMat = Mat()
        Imgproc.HoughLinesP(
            canny, linesMat, 1.0, Math.PI / 180.0, HOUGH_VOTES,
            minDim * 0.15, minDim * 0.02,
        )
        val segments = ArrayList<Segment>(linesMat.rows())
        for (r in 0 until linesMat.rows()) {
            val v = linesMat.get(r, 0) ?: continue
            val dx = (v[2] - v[0]).toFloat()
            val dy = (v[3] - v[1]).toFloat()
            val len = hypot(dx, dy)
            if (len < 1f) continue
            var angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
            if (angle < 0f) angle += 180f
            segments.add(
                Segment(
                    midX = ((v[0] + v[2]) / 2).toFloat(),
                    midY = ((v[1] + v[3]) / 2).toFloat(),
                    dirX = dx / len,
                    dirY = dy / len,
                    angleDeg = angle % 180f,
                    length = len,
                ),
            )
        }
        linesMat.release()
        if (segments.size < 4) return

        // Dominant orientation from a length-weighted angle histogram (4° bins, mod 180).
        val hist = FloatArray(45)
        for (s in segments) hist[(s.angleDeg / 4f).toInt().coerceIn(0, 44)] += s.length
        var peak = 0
        for (i in 1 until 45) if (hist[i] > hist[peak]) peak = i
        val theta0 = peak * 4f + 2f

        val clusterA = dedupe(segments.filter { angleDiff(it.angleDeg, theta0) <= 35f }, minDim)
        val clusterB = dedupe(segments.filter { angleDiff(it.angleDeg, theta0 + 90f) <= 35f }, minDim)
        if (clusterA.size < 2 || clusterB.size < 2) return

        val a = clusterA.map { it.toLine() }
        val b = clusterB.map { it.toLine() }
        for (i in a.indices) for (j in i + 1 until a.size) {
            for (k in b.indices) for (l in k + 1 until b.size) {
                val p1 = QuadGeometry.intersect(a[i], b[k]) ?: continue
                val p2 = QuadGeometry.intersect(a[i], b[l]) ?: continue
                val p3 = QuadGeometry.intersect(a[j], b[l]) ?: continue
                val p4 = QuadGeometry.intersect(a[j], b[k]) ?: continue
                scorer.offer(listOf(p1, p2, p3, p4))
            }
        }
    }

    /** Keep the longest segment of each near-collinear group, at most [MAX_LINES_PER_CLUSTER]. */
    private fun dedupe(cluster: List<Segment>, minDim: Int): List<Segment> {
        val kept = ArrayList<Segment>(MAX_LINES_PER_CLUSTER)
        val mergeDist = minDim * 0.025f
        for (s in cluster.sortedByDescending { it.length }) {
            val duplicate = kept.any { k ->
                angleDiff(k.angleDeg, s.angleDeg) < 6f &&
                    abs((s.midX - k.midX) * -k.dirY + (s.midY - k.midY) * k.dirX) < mergeDist
            }
            if (!duplicate) {
                kept.add(s)
                if (kept.size == MAX_LINES_PER_CLUSTER) break
            }
        }
        return kept
    }

    /** Circular difference between two segment angles, mod 180°. */
    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % 180f
        return min(d, 180f - d)
    }

    /**
     * Polish the winner: probe ±[REFINE_RANGE] px along each side's normal for the
     * nearest edge pixel, fit a line per side, rebuild corners from intersections.
     */
    private fun refine(quad: DocumentQuad, support: ByteArray, w: Int, h: Int): DocumentQuad {
        val stations = QuadGeometry.sideSamples(quad, REFINE_STATIONS, 0.10f)
        val corners = quad.corners
        val sidePoints = (0..3).map { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 1f) return@map emptyList<QuadPoint>()
            val nx = -(b.y - a.y) / len
            val ny = (b.x - a.x) / len
            stations[i].mapNotNull { st -> nearestEdgeHit(st, nx, ny, support, w, h) }
        }
        return QuadGeometry.refineCorners(
            quad, sidePoints,
            minPointsPerSide = REFINE_STATIONS / 3,
            maxShiftPx = REFINE_MAX_SHIFT,
        )
    }

    /** Closest support pixel to [st] along the normal (±[REFINE_RANGE] px), or null. */
    private fun nearestEdgeHit(
        st: QuadPoint,
        nx: Float,
        ny: Float,
        support: ByteArray,
        w: Int,
        h: Int,
    ): QuadPoint? {
        for (step in 0..REFINE_RANGE) {
            for (sign in if (step == 0) SIGN_ZERO else SIGN_BOTH) {
                val off = step * sign
                val x = (st.x + nx * off).roundToInt()
                val y = (st.y + ny * off).roundToInt()
                if (x in 0 until w && y in 0 until h && support[y * w + x] != ZERO_BYTE) {
                    return QuadPoint(st.x + nx * off, st.y + ny * off)
                }
            }
        }
        return null
    }

    /** Median grey value from a bulk histogram read (no per-bin JNI round-trips). */
    private fun medianIntensity(grey: Mat): Double {
        val hist = Mat()
        val channels = MatOfInt(0)
        val mask = Mat()
        val histSize = MatOfInt(256)
        val ranges = MatOfFloat(0f, 256f)
        Imgproc.calcHist(listOf(grey), channels, mask, hist, histSize, ranges)
        val bins = FloatArray(256).also { hist.get(0, 0, it) }
        hist.release(); channels.release(); mask.release(); histSize.release(); ranges.release()

        val half = grey.rows().toLong() * grey.cols() / 2
        var seen = 0L
        for (i in 0 until 256) {
            seen += bins[i].toLong()
            if (seen >= half) return i.toDouble()
        }
        return 128.0
    }

    private data class Segment(
        val midX: Float,
        val midY: Float,
        val dirX: Float,
        val dirY: Float,
        val angleDeg: Float,
        val length: Float,
    ) {
        fun toLine() = QuadGeometry.Line(midX, midY, dirX, dirY)
    }

    /**
     * Orders, validates and scores candidate quads against the edge-evidence map,
     * keeping the single best. All coordinates are working-scale.
     */
    private class CandidateScorer(
        private val support: ByteArray,
        private val w: Int,
        private val h: Int,
        private val prior: DocumentQuad?,
    ) {
        var best: DocumentQuad? = null
            private set
        var bestScore = 0f
            private set

        private val constraints = QuadGeometry.Constraints()
        private val priorTolerance = 0.08f * max(w, h)

        fun offer(points: List<QuadPoint>) {
            val quad = QuadGeometry.orderCorners(points)
            if (!QuadGeometry.validate(quad, w.toFloat(), h.toFloat(), constraints)) return
            val edgeSupport = edgeSupport(quad)
            if (edgeSupport < MIN_EDGE_SUPPORT) return
            val areaRatio = QuadGeometry.area(quad.corners) / (w * h)
            val proximity = prior?.let { QuadGeometry.priorProximity(quad, it, priorTolerance) } ?: 0f
            val score = QuadGeometry.score(areaRatio, QuadGeometry.angleQuality(quad), edgeSupport, proximity)
            if (score > bestScore) {
                bestScore = score
                best = quad
            }
        }

        /** Fraction of perimeter sample points backed by a Canny edge within ~2 px. */
        private fun edgeSupport(quad: DocumentQuad): Float {
            val sides = QuadGeometry.sideSamples(quad, SUPPORT_SAMPLES_PER_SIDE, 0.08f)
            var hits = 0
            var total = 0
            for (side in sides) {
                for (p in side) {
                    total++
                    if (hitNear(p.x.roundToInt(), p.y.roundToInt())) hits++
                }
            }
            return if (total == 0) 0f else hits.toFloat() / total
        }

        private fun hitNear(x: Int, y: Int): Boolean {
            // The support map is Canny dilated by one; a 3×3 probe adds another ±1 px.
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                val row = yy * w
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx in 0 until w && support[row + xx] != ZERO_BYTE) return true
                }
            }
            return false
        }
    }

    private companion object {
        /** Everything is detected at ≤ this size; parameters below assume it. */
        const val WORK_MAX_DIM = 640
        const val TOP_CONTOURS = 6
        val EPSILON_LADDER = doubleArrayOf(0.02, 0.035, 0.05, 0.07)

        const val SUPPORT_SAMPLES_PER_SIDE = 24
        /** Hard evidence gate: below this, a candidate is a guess, not a detection. */
        const val MIN_EDGE_SUPPORT = 0.45f
        /** Final acceptance floor for the combined score. */
        const val MIN_SCORE = 0.05f
        /** A contour result this strong makes the Hough pass unnecessary. */
        const val HOUGH_SKIP_SCORE = 0.28f

        const val HOUGH_VOTES = 40
        const val MAX_LINES_PER_CLUSTER = 6

        const val REFINE_STATIONS = 24
        const val REFINE_RANGE = 4
        const val REFINE_MAX_SHIFT = 8f

        const val ZERO_BYTE = 0.toByte()
        val SIGN_ZERO = intArrayOf(0)
        val SIGN_BOTH = intArrayOf(-1, 1)
    }
}
