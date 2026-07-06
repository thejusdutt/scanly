package com.scanly.domain

import android.graphics.BitmapFactory
import com.scanly.data.files.DocumentStorage
import com.scanly.data.repo.DocumentRepository
import com.scanly.pdf.PdfPageSize
import com.scanly.pdf.SearchablePdfBuilder
import com.scanly.platform.RecognizedWord
import com.scanly.platform.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Exports a document to a watermark-free PDF. When [searchable] is true, OCR word boxes
 * are computed on-device and laid down as an invisible text layer.
 */
class ExportDocumentUseCase @Inject constructor(
    private val repository: DocumentRepository,
    private val storage: DocumentStorage,
    private val pdfBuilder: SearchablePdfBuilder,
    private val recognizer: TextRecognizer,
) {
    suspend operator fun invoke(
        documentId: Long,
        searchable: Boolean,
        languageCode: String = "eng",
        password: String? = null,
        pageSize: PdfPageSize = PdfPageSize.AUTO,
        jpegQuality: Float = 0.9f,
    ): File? = withContext(Dispatchers.Default) {
        val dwp = repository.getDocumentWithPages(documentId) ?: return@withContext null
        val pages = dwp.pages.sortedBy { it.orderIndex }
        if (pages.isEmpty()) return@withContext null

        val wordMap: Map<Long, List<RecognizedWord>> =
            if (!searchable) emptyMap()
            else pages.associate { page ->
                val bmp = BitmapFactory.decodeFile(page.imagePath)
                val words = if (bmp == null) emptyList()
                else recognizer.recognize(bmp, languageCode).words.also { bmp.recycle() }
                page.id to words
            }

        val safeName = dwp.document.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = storage.exportFile("$safeName.pdf")
        pdfBuilder.build(out, pages, wordMap, password, pageSize, jpegQuality)
    }

    /**
     * All pages stitched vertically into one long JPEG (CamScanner-style "long image",
     * handy for chat apps). Bounded working size so huge documents can't blow the heap.
     */
    suspend fun asLongImage(documentId: Long): File? = withContext(Dispatchers.Default) {
        val dwp = repository.getDocumentWithPages(documentId) ?: return@withContext null
        val pages = dwp.pages.sortedBy { it.orderIndex }
        if (pages.isEmpty()) return@withContext null

        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val dims = pages.mapNotNull { page ->
            android.graphics.BitmapFactory.decodeFile(page.imagePath, opts)
            if (opts.outWidth <= 0) null else page.imagePath to (opts.outWidth to opts.outHeight)
        }
        if (dims.isEmpty()) return@withContext null

        var width = 1000
        val gap = 12
        var totalH = dims.sumOf { (_, wh) ->
            (wh.second.toLong() * width / wh.first) + gap
        } - gap
        if (totalH > MAX_LONG_IMAGE_HEIGHT) {
            width = (width * MAX_LONG_IMAGE_HEIGHT / totalH).toInt().coerceAtLeast(240)
            totalH = dims.sumOf { (_, wh) -> (wh.second.toLong() * width / wh.first) + gap } - gap
        }

        // RGB_565: no alpha needed, halves the peak memory of the stitched canvas.
        val out = android.graphics.Bitmap.createBitmap(
            width, totalH.toInt(), android.graphics.Bitmap.Config.RGB_565,
        )
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        var y = 0
        for ((path, wh) in dims) {
            val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: continue
            val h = wh.second * width / wh.first
            canvas.drawBitmap(
                bmp, null,
                android.graphics.Rect(0, y, width, y + h), paint,
            )
            bmp.recycle()
            y += h + gap
        }

        val safeName = dwp.document.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = storage.exportFile("${safeName}_long.jpg")
        java.io.FileOutputStream(file).use {
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
        }
        out.recycle()
        file
    }

    /** Recognized text of all pages as a shareable .txt, or null when there is none. */
    suspend fun asText(documentId: Long): File? = withContext(Dispatchers.IO) {
        val dwp = repository.getDocumentWithPages(documentId) ?: return@withContext null
        val text = dwp.pages.sortedBy { it.orderIndex }
            .mapNotNull { it.ocrText?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        if (text.isBlank()) return@withContext null
        val safeName = dwp.document.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        storage.exportFile("$safeName.txt").apply { writeText(text) }
    }

    private companion object {
        const val MAX_LONG_IMAGE_HEIGHT = 16000L
    }

    /** Export each page as a JPEG (for share-as-images). Returns the ordered files. */
    suspend fun asImages(documentId: Long): List<File> = withContext(Dispatchers.IO) {
        val dwp = repository.getDocumentWithPages(documentId) ?: return@withContext emptyList()
        val safeName = dwp.document.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        dwp.pages.sortedBy { it.orderIndex }.mapIndexedNotNull { i, page ->
            val src = File(page.imagePath)
            if (!src.exists()) return@mapIndexedNotNull null
            val out = storage.exportFile("${safeName}_p${(i + 1).toString().padStart(2, '0')}.jpg")
            src.copyTo(out, overwrite = true)
            out
        }
    }
}
