package com.scanly.foss

import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import com.scanly.platform.RecognizedText
import com.scanly.platform.RecognizedWord
import com.scanly.platform.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * FOSS OCR via Tesseract 5 (Tesseract4Android, Apache-2.0). Emits per-word bounding
 * boxes so [com.scanly.pdf.SearchablePdfBuilder] can lay down an invisible text layer.
 *
 * A fresh [TessBaseAPI] per call keeps pages isolated — a corrupt page can fail on its
 * own without poisoning a shared engine, which is the root of the multi-page OCR crashes
 * users report in other apps.
 *
 * Note: the Maven group is cz.adaptech.tesseract4android but the Java package is the
 * legacy com.googlecode.tesseract.android (kept for tess-two compatibility).
 */
class TesseractTextRecognizer @Inject constructor(
    private val trainedData: TrainedDataManager,
) : TextRecognizer {

    override suspend fun isLanguageAvailable(languageCode: String): Boolean {
        trainedData.ensureBundled("eng")
        return trainedData.isAvailable(languageCode)
    }

    override suspend fun recognize(page: Bitmap, languageCode: String): RecognizedText =
        withContext(Dispatchers.Default) {
            trainedData.ensureBundled("eng")
            val lang = if (trainedData.isAvailable(languageCode)) languageCode else "eng"
            val tess = TessBaseAPI()
            try {
                if (!tess.init(trainedData.dataPath.absolutePath, lang)) {
                    return@withContext RecognizedText.EMPTY
                }
                tess.setImage(page)
                val plain = tess.getUTF8Text() ?: ""
                val words = buildList {
                    val iterator = tess.resultIterator ?: return@buildList
                    iterator.begin()
                    val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                    do {
                        val text = iterator.getUTF8Text(level)
                        if (text.isNullOrBlank()) continue
                        val r = iterator.getBoundingRect(level) ?: continue
                        add(
                            RecognizedWord(
                                text,
                                RectF(r.left.toFloat(), r.top.toFloat(),
                                    r.right.toFloat(), r.bottom.toFloat()),
                            ),
                        )
                    } while (iterator.next(level))
                    iterator.delete()
                }
                RecognizedText(plain.trim(), words)
            } catch (t: Throwable) {
                // Isolated failure: report empty, never crash the batch.
                RecognizedText.EMPTY
            } finally {
                tess.recycle()
            }
        }
}
