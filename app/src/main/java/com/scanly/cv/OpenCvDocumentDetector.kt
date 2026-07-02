package com.scanly.cv

import android.graphics.Bitmap
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

/**
 * Edge detection via the classic OpenCV contour pipeline. 100% on-device, no proprietary
 * deps, so it's shared by both flavors and powers the live preview overlay. (ML Kit's
 * document scanner is a separate full-screen flow, not a per-frame detector, so even the
 * gplay build uses this for the real-time boundary.)
 *
 * Returns the largest convex 4-point contour by area, mapped to source pixels.
 */
class OpenCvDocumentDetector @Inject constructor() : DocumentDetector {

    override fun detect(frame: Bitmap): DocumentQuad? {
        if (!OpenCvInitializer.ensure()) return null

        val src = Mat().also { Utils.bitmapToMat(frame, it) }
        val grey = Mat()
        Imgproc.cvtColor(src, grey, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(grey, grey, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(grey, edges, 75.0, 200.0)
        // Close gaps so page borders form a single contour.
        val kernel = Mat()
        Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
        kernel.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        hierarchy.release()

        val frameArea = (frame.width * frame.height).toDouble()
        var best: List<QuadPoint>? = null
        var bestArea = 0.0

        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            if (approx.total() == 4L) {
                val area = Imgproc.contourArea(approx)
                // Ignore tiny noise (<15% of frame) and keep the largest.
                if (area > bestArea && area > frameArea * 0.15) {
                    bestArea = area
                    best = approx.toArray().map { QuadPoint(it.x.toFloat(), it.y.toFloat()) }
                }
            }
            c2f.release(); approx.release(); c.release()
        }

        src.release(); grey.release(); edges.release()
        return best?.let { orderCorners(it) }
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
