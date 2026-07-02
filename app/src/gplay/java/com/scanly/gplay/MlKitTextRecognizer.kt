package com.scanly.gplay

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanly.platform.RecognizedText
import com.scanly.platform.RecognizedWord
import com.scanly.platform.TextRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * gplay OCR via ML Kit on-device Text Recognition v2 — faster than Tesseract for Latin
 * scripts and still 100% on-device. Falls back conceptually to Tesseract for scripts ML
 * Kit doesn't bundle (wired in [GplayModule] if desired).
 */
class MlKitTextRecognizer @Inject constructor() : TextRecognizer {

    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun isLanguageAvailable(languageCode: String): Boolean = true

    override suspend fun recognize(page: Bitmap, languageCode: String): RecognizedText =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(page, 0)
            client.process(image)
                .addOnSuccessListener { result ->
                    val words = buildList {
                        for (block in result.textBlocks)
                            for (line in block.lines)
                                for (element in line.elements) {
                                    val b = element.boundingBox ?: continue
                                    add(RecognizedWord(
                                        element.text,
                                        RectF(b.left.toFloat(), b.top.toFloat(),
                                            b.right.toFloat(), b.bottom.toFloat()),
                                    ))
                                }
                    }
                    cont.resume(RecognizedText(result.text.trim(), words))
                }
                .addOnFailureListener { cont.resume(RecognizedText.EMPTY) }
        }
}
