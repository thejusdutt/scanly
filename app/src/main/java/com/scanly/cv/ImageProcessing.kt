package com.scanly.cv

import android.graphics.Bitmap
import com.scanly.common.Filter
import com.scanly.platform.DocumentQuad
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/**
 * Perspective correction + filters, shared by both flavors. Pure OpenCV (Apache-2.0),
 * so it works identically in the FOSS and Play builds.
 */
object ImageProcessing {

    /** Warp the quad region of [src] to a flat, deskewed rectangle. */
    fun warp(src: Bitmap, quad: DocumentQuad): Bitmap {
        OpenCvInitializer.ensure()
        val srcMat = Mat().also { Utils.bitmapToMat(src, it) }

        val tl = quad.topLeft; val tr = quad.topRight
        val br = quad.bottomRight; val bl = quad.bottomLeft

        val widthTop = hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble())
        val widthBottom = hypot((br.x - bl.x).toDouble(), (br.y - bl.y).toDouble())
        val heightLeft = hypot((bl.x - tl.x).toDouble(), (bl.y - tl.y).toDouble())
        val heightRight = hypot((br.x - tr.x).toDouble(), (br.y - tr.y).toDouble())
        val outW = maxOf(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val outH = maxOf(heightLeft, heightRight).toInt().coerceAtLeast(1)

        val srcPts = MatOfPoint2f(
            Point(tl.x.toDouble(), tl.y.toDouble()),
            Point(tr.x.toDouble(), tr.y.toDouble()),
            Point(br.x.toDouble(), br.y.toDouble()),
            Point(bl.x.toDouble(), bl.y.toDouble()),
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0),
        )

        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val dst = Mat()
        Imgproc.warpPerspective(srcMat, dst, transform, Size(outW.toDouble(), outH.toDouble()))

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, out)
        srcMat.release(); dst.release(); transform.release()
        return out
    }

    /** Apply a [Filter]. GREYSCALE produces real 8-bit grey (the headline gap fix). */
    fun applyFilter(src: Bitmap, filter: Filter): Bitmap {
        OpenCvInitializer.ensure()
        val mat = Mat().also { Utils.bitmapToMat(src, it) }
        val result = when (filter) {
            Filter.COLOR -> enhanceColor(mat)
            Filter.GREYSCALE -> greyscale(mat)
            Filter.BW -> blackAndWhite(mat)
            Filter.MAGIC -> magic(mat)
        }
        val out = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, out)
        mat.release(); if (result !== mat) result.release()
        return out
    }

    private fun enhanceColor(mat: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        // Mild contrast/brightness lift.
        rgb.convertTo(rgb, -1, 1.15, 10.0)
        return rgb
    }

    private fun greyscale(mat: Mat): Mat {
        val grey = Mat()
        Imgproc.cvtColor(mat, grey, Imgproc.COLOR_RGBA2GRAY)
        // Normalize lighting so scans of text are consistently legible.
        Imgproc.equalizeHist(grey, grey)
        return grey
    }

    private fun blackAndWhite(mat: Mat): Mat {
        val grey = Mat()
        Imgproc.cvtColor(mat, grey, Imgproc.COLOR_RGBA2GRAY)
        val bw = Mat()
        Imgproc.adaptiveThreshold(
            grey, bw, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 15, 10.0,
        )
        grey.release()
        return bw
    }

    /** "Magic color": divide by a blurred illumination estimate to flatten shadows. */
    private fun magic(mat: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        val blur = Mat()
        Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), 25.0)
        val out = Mat(rgb.size(), CvType.CV_8UC3)
        org.opencv.core.Core.divide(rgb, blur, out, 255.0)
        rgb.release(); blur.release()
        return out
    }
}
