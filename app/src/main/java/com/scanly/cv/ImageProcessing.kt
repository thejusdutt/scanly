package com.scanly.cv

import android.graphics.Bitmap
import com.scanly.common.Filter
import com.scanly.platform.DocumentQuad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
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
        // Cubic keeps small text crisp on the one-shot full-res warp; replicate fills
        // the sliver a corner slightly outside the frame would otherwise paint black.
        Imgproc.warpPerspective(
            srcMat, dst, transform, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar.all(0.0),
        )

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
            Filter.WHITEBOARD -> whiteboard(mat)
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

    /**
     * Prepare a page image for OCR: greyscale, upscale small captures toward ~300 DPI
     * text size, flatten illumination (division normalization), and boost local
     * contrast with CLAHE. Returns the processed bitmap and the scale factor applied,
     * so recognizers can map word boxes back to source-image coordinates.
     */
    fun prepareForOcr(src: Bitmap, minWidth: Int = 1800): Pair<Bitmap, Float> {
        OpenCvInitializer.ensure()
        val mat = Mat().also { Utils.bitmapToMat(src, it) }
        val grey = Mat()
        Imgproc.cvtColor(mat, grey, Imgproc.COLOR_RGBA2GRAY)
        mat.release()

        // Flatten uneven lighting: divide by a heavily blurred illumination estimate.
        val blur = Mat()
        Imgproc.GaussianBlur(grey, blur, Size(0.0, 0.0), 21.0)
        val flat = Mat()
        org.opencv.core.Core.divide(grey, blur, flat, 255.0)
        grey.release(); blur.release()

        // Local contrast (CLAHE is gentler than global equalizeHist for OCR).
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(flat, flat)

        // Upscale small images: LSTM Tesseract wants ~30px+ character height.
        val scale = if (flat.cols() < minWidth) minWidth.toFloat() / flat.cols() else 1f
        if (scale > 1f) {
            Imgproc.resize(
                flat, flat,
                Size(flat.cols() * scale.toDouble(), flat.rows() * scale.toDouble()),
                0.0, 0.0, Imgproc.INTER_CUBIC,
            )
        }

        val out = Bitmap.createBitmap(flat.cols(), flat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(flat, out)
        flat.release()
        return out to scale
    }

    /**
     * Whiteboard: flatten glare/shadow (division normalization), push the near-white
     * board to pure white, and boost saturation so marker strokes stay vivid. Applied
     * to the FULL frame — whiteboard captures skip boundary cropping by design.
     */
    private fun whiteboard(mat: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        val blur = Mat()
        Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), 41.0)
        val flat = Mat(rgb.size(), CvType.CV_8UC3)
        org.opencv.core.Core.divide(rgb, blur, flat, 255.0)
        rgb.release(); blur.release()

        val hsv = Mat()
        Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_RGB2HSV)
        flat.release()
        val channels = ArrayList<Mat>(3)
        org.opencv.core.Core.split(hsv, channels)
        channels[1].convertTo(channels[1], -1, 1.4, 0.0)   // saturation → vivid markers
        channels[2].convertTo(channels[2], -1, 1.12, -8.0) // value → board to white
        org.opencv.core.Core.merge(channels, hsv)
        channels.forEach { it.release() }

        val out = Mat()
        Imgproc.cvtColor(hsv, out, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        return out
    }

    /**
     * Brightness/contrast bake. [contrast] multiplies around mid-grey, [brightness] adds:
     * out = contrast * in + (brightness + 128 * (1 - contrast)). The Compose live preview
     * uses the SAME formula in a ColorMatrix, so what the user sees is what gets saved.
     */
    fun adjust(src: Bitmap, brightness: Float, contrast: Float): Bitmap {
        OpenCvInitializer.ensure()
        val mat = Mat().also { Utils.bitmapToMat(src, it) }
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        mat.release()
        val offset = brightness + 128f * (1f - contrast)
        rgb.convertTo(rgb, -1, contrast.toDouble(), offset.toDouble())
        val out = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgb, out)
        rgb.release()
        return out
    }

    /** Small-angle deskew: rotate about the center onto a white (paper) canvas. */
    fun rotateFine(src: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(rotated, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        if (rotated !== src) rotated.recycle()
        return out
    }

    /**
     * Cleanup eraser: remove the masked region (fingers, stains, handwriting) by
     * inpainting from the surroundings. [mask] must match [src] in size; any pixel with
     * alpha or luminance > 0 is treated as "erase this".
     */
    fun inpaint(src: Bitmap, mask: Bitmap): Bitmap {
        OpenCvInitializer.ensure()
        val srcMat = Mat().also { Utils.bitmapToMat(src, it) }
        val rgb = Mat()
        Imgproc.cvtColor(srcMat, rgb, Imgproc.COLOR_RGBA2RGB)
        srcMat.release()

        val maskRgba = Mat().also { Utils.bitmapToMat(mask, it) }
        val maskGrey = Mat()
        Imgproc.cvtColor(maskRgba, maskGrey, Imgproc.COLOR_RGBA2GRAY)
        maskRgba.release()
        Imgproc.threshold(maskGrey, maskGrey, 10.0, 255.0, Imgproc.THRESH_BINARY)

        val out = Mat()
        org.opencv.photo.Photo.inpaint(rgb, maskGrey, out, 6.0, org.opencv.photo.Photo.INPAINT_TELEA)
        rgb.release(); maskGrey.release()

        val bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        out.release()
        return bmp
    }

    /** User watermark, baked onto the page (single diagonal or tiled). */
    data class WatermarkSpec(
        val text: String,
        /** Text size as a fraction of page width. */
        val sizeFrac: Float = 0.10f,
        /** 0..1 */
        val opacity: Float = 0.25f,
        val tiled: Boolean = true,
        val color: Int = android.graphics.Color.DKGRAY,
    )

    fun watermark(src: Bitmap, spec: WatermarkSpec): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.color
            alpha = (spec.opacity.coerceIn(0.05f, 1f) * 255).toInt()
            textSize = out.width * spec.sizeFrac.coerceIn(0.04f, 0.4f)
            isFakeBoldText = true
        }
        val textWidth = paint.measureText(spec.text).coerceAtLeast(1f)
        canvas.save()
        canvas.rotate(-30f, out.width / 2f, out.height / 2f)
        if (spec.tiled) {
            val stepX = textWidth * 1.6f
            val stepY = paint.textSize * 5f
            var row = 0
            var y = -out.height * 0.5f
            while (y < out.height * 1.5f) {
                var x = -out.width * 0.5f + (row % 2) * stepX / 2f
                while (x < out.width * 1.5f) {
                    canvas.drawText(spec.text, x, y, paint)
                    x += stepX
                }
                y += stepY
                row++
            }
        } else {
            canvas.drawText(
                spec.text,
                out.width / 2f - textWidth / 2f,
                out.height / 2f + paint.textSize / 3f,
                paint,
            )
        }
        canvas.restore()
        return out
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
