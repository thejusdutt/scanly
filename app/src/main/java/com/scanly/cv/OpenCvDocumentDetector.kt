package com.scanly.cv

import android.graphics.Bitmap
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

/**
 * Document boundary detection, 100% on-device and shared by both flavors.
 *
 * Two complementary binarization passes feed one contour search:
 *  1. auto-thresholded Canny (thresholds derived from the median intensity, so it
 *     adapts to dark/bright scenes instead of using fixed magic numbers), and
 *  2. adaptive threshold (catches low-contrast page edges Canny misses, e.g. white
 *     paper on a light desk).
 *
 * Candidate quads are scored by area x rectangularity (area relative to the rotated
 * bounding rect) so a clean page outline beats a big ragged blob.
 */
class OpenCvDocumentDetector @Inject constructor() : DocumentDetector {

    override fun detect(frame: Bitmap): DocumentQuad? {
        if (!OpenCvInitializer.ensure()) return null

        val src = Mat().also { Utils.bitmapToMat(frame, it) }
        val grey = Mat()
        Imgproc.cvtColor(src, grey, Imgproc.COLOR_RGBA2GRAY)
        src.release()
        Imgproc.GaussianBlur(grey, grey, Size(5.0, 5.0), 0.0)

        val frameArea = (frame.width * frame.height).toDouble()
        var best: List<QuadPoint>? = null
        var bestScore = 0.0

        fun consider(quad: List<QuadPoint>, score: Double) {
            if (score > bestScore) { bestScore = score; best = quad }
        }

        // --- Pass 1: median-based auto Canny ---
        val median = medianIntensity(grey)
        val lower = (0.66 * median).coerceIn(10.0, 245.0)
        val upper = (1.33 * median).coerceIn(lower + 10.0, 255.0)
        val edges = Mat()
        Imgproc.Canny(grey, edges, lower, upper)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
        collectQuads(edges, frameArea, ::consider)
        edges.release()

        // --- Pass 2: adaptive threshold (low-contrast edges) ---
        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            grey, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 21, 5.0,
        )
        Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_CLOSE, kernel)
        collectQuads(thresh, frameArea, ::consider)
        thresh.release()
        kernel.release()
        grey.release()

        return best?.let { orderCorners(it) }
    }

    /** Find convex 4-gons in a binary image and hand them to [consider] with a score. */
    private fun collectQuads(
        binary: Mat,
        frameArea: Double,
        consider: (List<QuadPoint>, Double) -> Unit,
    ) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binary, contours, hierarchy,
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        hierarchy.release()

        // Only the biggest few contours can plausibly be the page.
        val top = contours.sortedByDescending { Imgproc.contourArea(it) }.take(5)
        for (c in top) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            // Slightly rounded/damaged corners need a looser epsilon; try tight first.
            for (eps in doubleArrayOf(0.02, 0.035, 0.05)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                if (approx.total() == 4L) {
                    val poly = MatOfPoint(*approx.toArray())
                    val area = Imgproc.contourArea(approx)
                    val convex = Imgproc.isContourConvex(poly)
                    poly.release()
                    if (convex && area > frameArea * 0.10) {
                        // Rectangularity: how much of its rotated bounding box it fills.
                        val rect = Imgproc.minAreaRect(approx)
                        val rectArea = rect.size.width * rect.size.height
                        val rectangularity = if (rectArea > 0) area / rectArea else 0.0
                        consider(
                            approx.toArray().map { QuadPoint(it.x.toFloat(), it.y.toFloat()) },
                            area * rectangularity,
                        )
                        approx.release()
                        break
                    }
                }
                approx.release()
            }
            c2f.release()
        }
        contours.forEach { it.release() }
    }

    /** Median grey value, sampled from the histogram. */
    private fun medianIntensity(grey: Mat): Double {
        val hist = Mat()
        Imgproc.calcHist(
            listOf(grey), org.opencv.core.MatOfInt(0), Mat(), hist,
            org.opencv.core.MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f),
        )
        val total = grey.rows().toLong() * grey.cols()
        var seen = 0L
        for (i in 0 until 256) {
            seen += hist.get(i, 0)[0].toLong()
            if (seen >= total / 2) {
                hist.release()
                return i.toDouble()
            }
        }
        hist.release()
        return 128.0
    }

    /** Order arbitrary 4 points into TL, TR, BR, BL by sum/diff of coordinates. */
    private fun orderCorners(pts: List<QuadPoint>): DocumentQuad {
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.minBy { it.y - it.x }
        val bl = pts.maxBy { it.y - it.x }
        return DocumentQuad(tl, tr, br, bl)
    }
}
