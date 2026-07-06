package com.scanly.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Splits a warped two-page book spread into left/right pages at the spine.
 *
 * The spine shows up as a dark valley in the column-intensity profile (gutter shadow).
 * [pickSpine] finds it inside the central band; when the valley is too shallow to trust
 * (flat, evenly lit book), the geometric center is used instead — for a properly framed
 * spread that is at most a few percent off. Splitting trims a hair on each side of the
 * cut so the gutter shadow itself doesn't land on either page.
 */
object BookSplitter {

    /** Split a landscape spread; null when [spread] isn't spread-shaped (single page). */
    fun split(spread: Bitmap): Pair<Bitmap, Bitmap>? {
        val w = spread.width
        val h = spread.height
        if (w < h * 1.15f) return null // portrait-ish → a single page, don't split

        val spine = if (OpenCvInitializer.ensure()) {
            pickSpine(columnMeans(spread), (w * 0.35f).toInt(), (w * 0.65f).toInt())
        } else {
            w / 2
        }

        val trim = max(2, (w * 0.004f).toInt())
        val leftWidth = (spine - trim).coerceIn(1, w - 1)
        val rightStart = (spine + trim).coerceIn(0, w - 2)
        val left = Bitmap.createBitmap(spread, 0, 0, leftWidth, h)
        val right = Bitmap.createBitmap(spread, rightStart, 0, w - rightStart, h)
        return left to right
    }

    /** Mean grey intensity per column. */
    private fun columnMeans(bitmap: Bitmap): FloatArray {
        val src = Mat().also { Utils.bitmapToMat(bitmap, it) }
        val grey = Mat()
        Imgproc.cvtColor(src, grey, Imgproc.COLOR_RGBA2GRAY)
        src.release()
        val row = Mat()
        Core.reduce(grey, row, 0, Core.REDUCE_AVG, CvType.CV_32F)
        grey.release()
        val means = FloatArray(row.cols()).also { row.get(0, 0, it) }
        row.release()
        return means
    }

    /**
     * Index of the darkest (smoothed) column in [from, to), or the band center when the
     * valley is shallower than [minValleyDepth] grey levels below the band average.
     * Pure logic — unit-tested in BookSplitterTest.
     */
    fun pickSpine(means: FloatArray, from: Int, to: Int, minValleyDepth: Float = 6f): Int {
        val lo = from.coerceIn(0, means.size)
        val hi = to.coerceIn(lo, means.size)
        if (hi - lo < 3) return (lo + hi) / 2

        // Box-smooth so single dark text columns don't masquerade as the gutter.
        val window = 9
        val half = window / 2
        var bandSum = 0f
        var minVal = Float.MAX_VALUE
        var minIdx = (lo + hi) / 2
        for (i in lo until hi) {
            var sum = 0f
            var n = 0
            for (j in (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(means.size - 1)) {
                sum += means[j]; n++
            }
            val smoothed = sum / n
            bandSum += smoothed
            if (smoothed < minVal) {
                minVal = smoothed
                minIdx = i
            }
        }
        val bandAvg = bandSum / (hi - lo)
        return if (bandAvg - minVal >= minValleyDepth) minIdx else (lo + hi) / 2
    }
}
