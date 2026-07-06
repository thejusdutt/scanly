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
     * @param pageSize page box preset; AUTO = page box equals the scan image.
     * @param jpegQuality 0..1 compression of the embedded page images.
     */
    suspend fun build(
        output: File,
        pages: List<PageEntity>,
        pageWords: Map<Long, List<RecognizedWord>> = emptyMap(),
        password: String? = null,
        pageSize: PdfPageSize = PdfPageSize.AUTO,
        jpegQuality: Float = 0.9f,
    ): File = withContext(Dispatchers.Default) {
        val doc = PDDocument()
        try {
            for (page in pages.sortedBy { it.orderIndex }) {
                val bmp = BitmapFactory.decodeFile(page.imagePath) ?: continue
                addPage(doc, bmp, pageWords[page.id].orEmpty(), pageSize, jpegQuality)
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

    private fun addPage(
        doc: PDDocument,
        bmp: Bitmap,
        words: List<RecognizedWord>,
        pageSize: PdfPageSize,
        jpegQuality: Float,
    ) {
        val p = PageLayout.place(bmp.width.toFloat(), bmp.height.toFloat(), pageSize)
        val pdPage = PDPage(PDRectangle(p.pageW, p.pageH))
        doc.addPage(pdPage)

        val image = JPEGFactory.createFromImage(doc, bmp, jpegQuality)
        PDPageContentStream(doc, pdPage).use { cs ->
            cs.drawImage(image, p.x, p.y, p.drawW, p.drawH)

            if (words.isEmpty()) return@use
            // Invisible, selectable text layer aligned to each word box, in the SAME
            // transform as the drawn image (scaled + centered on the page box).
            val sx = p.drawW / bmp.width
            val sy = p.drawH / bmp.height
            val font = PDType1Font.HELVETICA
            for (w in words) {
                if (w.text.isBlank()) continue
                val boxH = (w.box.bottom - w.box.top).coerceAtLeast(1f) * sy
                val fontSize = boxH * 0.8f
                // PDF origin is bottom-left; image coords are top-left → flip Y.
                val x = p.x + w.box.left * sx
                val y = p.y + (bmp.height - w.box.bottom) * sy + boxH * 0.15f
                try {
                    cs.beginText()
                    cs.setRenderingMode(RenderingMode.NEITHER) // invisible
                    cs.setFont(font, fontSize)
                    cs.newLineAtOffset(x, y)
                    cs.showText(sanitize(w.text))
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
