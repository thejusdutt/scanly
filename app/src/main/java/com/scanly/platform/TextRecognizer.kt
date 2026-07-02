package com.scanly.platform

import android.graphics.Bitmap
import android.graphics.RectF

/** One recognized word with its bounding box in source-image pixel coordinates. */
data class RecognizedWord(val text: String, val box: RectF)

/** Full OCR result for a page: plain text plus per-word boxes for the PDF text layer. */
data class RecognizedText(
    val plainText: String,
    val words: List<RecognizedWord>,
) {
    val isEmpty: Boolean get() = plainText.isBlank()

    companion object {
        val EMPTY = RecognizedText("", emptyList())
    }
}

/**
 * On-device OCR. Implemented per flavor:
 *  - foss  -> Tesseract ([com.scanly.foss.TesseractTextRecognizer])
 *  - gplay -> ML Kit Text Recognition ([com.scanly.gplay.MlKitTextRecognizer])
 *
 * Word boxes feed [com.scanly.pdf.SearchablePdfBuilder] to produce a selectable,
 * searchable PDF text layer aligned to the scanned image.
 */
interface TextRecognizer {
    /** @param languageCode ISO 639-2/T (e.g. "eng"); ignored by engines that auto-detect. */
    suspend fun recognize(page: Bitmap, languageCode: String = "eng"): RecognizedText

    /** Whether the data needed for [languageCode] is available on device. */
    suspend fun isLanguageAvailable(languageCode: String): Boolean
}
