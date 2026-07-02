package com.scanly.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.scanly.data.db.PageEntity
import com.scanly.platform.RecognizedWord
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a multi-page PDF from scanned pages, optionally with an INVISIBLE OCR text layer
 * positioned over each word so the PDF looks like the scan but selects/searches like text.
 *
 * No watermark is ever added — that is the whole point.
 */
@Singleton
class SearchablePdfBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init { PDFBoxResourceLoader.init(context) }

    /**
     * @param pageWords optional per-page OCR words (image-pixel coords). When present and
     *   non-empty, an invisible text layer is laid down for that page.
     * @param password optional user password; when set, the PDF is encrypted (AES-128).
     */
    suspend fun build(
        output: File,
        pages: List<PageEntity>,
        pageWords: Map<Long, List<RecognizedWord>> = emptyMap(),
        password: String? = null,
    ): File = withContext(Dispatchers.Default) {
        val doc = PDDocument()
        try {
            for (page in pages.sortedBy { it.orderIndex }) {
                val bmp = BitmapFactory.decodeFile(page.imagePath) ?: continue
                addPage(doc, bmp, pageWords[page.id].orEmpty())
                bmp.recycle()
            }
            if (!password.isNullOrBlank()) {
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            output.parentFile?.mkdirs()
            doc.save(output)
        } finally {
            doc.close()
        }
        output
    }

    private fun addPage(doc: PDDocument, bmp: Bitmap, words: List<RecognizedWord>) {
        val pageW = bmp.width.toFloat()
        val pageH = bmp.height.toFloat()
        val pdPage = PDPage(PDRectangle(pageW, pageH))
        doc.addPage(pdPage)

        val image = JPEGFactory.createFromImage(doc, bmp, 0.9f)
        PDPageContentStream(doc, pdPage).use { cs ->
            // Full-bleed scan image.
            cs.drawImage(image, 0f, 0f, pageW, pageH)

            if (words.isEmpty()) return@use
            // Invisible, selectable text layer aligned to each word box.
            val font = PDType1Font.HELVETICA
            for (w in words) {
                if (w.text.isBlank()) continue
                val text = w.text
                val boxH = (w.box.bottom - w.box.top).coerceAtLeast(1f)
                val fontSize = boxH * 0.8f
                // PDF origin is bottom-left; image coords are top-left → flip Y.
                val x = w.box.left
                val y = pageH - w.box.bottom + boxH * 0.15f
                try {
                    cs.beginText()
                    cs.setRenderingMode(RenderingMode.NEITHER) // invisible
                    cs.setFont(font, fontSize)
                    cs.newLineAtOffset(x, y)
                    cs.showText(sanitize(text))
                    cs.endText()
                } catch (_: Throwable) {
                    // Glyph not encodable in Helvetica/WinAnsi — skip this word's text
                    // layer; the image still shows it. Never fail the whole PDF.
                    runCatching { cs.endText() }
                }
            }
        }
    }

    /** Drop characters Helvetica's WinAnsi encoding can't represent. */
    private fun sanitize(s: String): String =
        s.filter { it.code in 32..255 }.ifBlank { " " }
}
