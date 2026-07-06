package com.scanly.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device QR decoding via ZXing (pure Java, Apache-2.0 — safe for the foss flavor).
 *
 * Called per preview frame from the single-threaded analysis executor; the pixel buffer
 * is reused across calls, so this class is NOT thread-safe by design.
 */
@Singleton
class QrDecoder @Inject constructor() {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    private var pixels = IntArray(0)

    /** Decoded QR payload in [frame], or null when none is visible. */
    fun decode(frame: Bitmap): String? {
        val w = frame.width
        val h = frame.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Throwable) {
            null
        } finally {
            reader.reset()
        }
    }
}
