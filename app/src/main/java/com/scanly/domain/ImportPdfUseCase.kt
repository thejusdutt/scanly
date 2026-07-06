package com.scanly.domain

import android.content.Context
import android.net.Uri
import com.scanly.common.RenamePattern
import com.scanly.data.repo.DocumentRepository
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Imports an existing PDF as a Scanly document: each page is rendered on-device via
 * PdfBox and stored as a page image, so the whole toolset (filters, markup, OCR,
 * re-export) works on it. No network, no external converter.
 */
class ImportPdfUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DocumentRepository,
) {
    /** @return the new document id, or null when the PDF can't be read (or is encrypted). */
    suspend operator fun invoke(uri: Uri): Long? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { pdf ->
                    if (pdf.isEncrypted || pdf.numberOfPages == 0) return@use null
                    val docId = repository.createDocument(
                        RenamePattern.format(RenamePattern.DEFAULT, type = "PDFImport"),
                    )
                    val renderer = PDFRenderer(pdf)
                    val pageCount = min(pdf.numberOfPages, MAX_PAGES)
                    for (i in 0 until pageCount) {
                        val box = pdf.getPage(i).mediaBox
                        // 72 pt/inch native; aim for ~200 dpi, capped so huge pages
                        // can't blow the heap.
                        val maxDimPt = max(box.width, box.height)
                        val scale = min(200f / 72f, MAX_RENDER_DIM / maxDimPt)
                        val bmp = renderer.renderImage(i, scale)
                        repository.addRawPage(docId, bmp)
                        bmp.recycle()
                    }
                    docId
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_PAGES = 100
        const val MAX_RENDER_DIM = 2500f
    }
}
