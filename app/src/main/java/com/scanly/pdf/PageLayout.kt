package com.scanly.pdf

/**
 * PDF export page presets (points, 1/72"). AUTO keeps the page box exactly the size of
 * the scan image — the historical Scanly behavior.
 */
enum class PdfPageSize(val widthPt: Float, val heightPt: Float) {
    AUTO(0f, 0f),
    A4(595f, 842f),
    LETTER(612f, 792f),
    LEGAL(612f, 1008f),
}

/** Where a scan lands on the chosen page box. All values in PDF points. */
data class PagePlacement(
    val pageW: Float,
    val pageH: Float,
    /** Bottom-left corner of the drawn image (PDF origin is bottom-left). */
    val x: Float,
    val y: Float,
    val drawW: Float,
    val drawH: Float,
)

/**
 * Pure placement math for [SearchablePdfBuilder] — also rescales the invisible OCR
 * text layer, so it must stay exactly in sync with the drawn image. JVM-tested.
 */
object PageLayout {

    /**
     * Fit an imgW×imgH scan onto [size]: preset boxes rotate to landscape for landscape
     * images, and the image is scaled uniformly and centered. AUTO returns a full-bleed
     * page identical to the image.
     */
    fun place(imgW: Float, imgH: Float, size: PdfPageSize): PagePlacement {
        if (size == PdfPageSize.AUTO) {
            return PagePlacement(imgW, imgH, 0f, 0f, imgW, imgH)
        }
        val landscape = imgW > imgH
        val pageW = if (landscape) size.heightPt else size.widthPt
        val pageH = if (landscape) size.widthPt else size.heightPt
        val scale = minOf(pageW / imgW, pageH / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        return PagePlacement(
            pageW = pageW,
            pageH = pageH,
            x = (pageW - drawW) / 2f,
            y = (pageH - drawH) / 2f,
            drawW = drawW,
            drawH = drawH,
        )
    }
}
