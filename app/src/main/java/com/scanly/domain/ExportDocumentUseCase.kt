package com.scanly.domain

import android.graphics.BitmapFactory
import com.scanly.data.files.DocumentStorage
import com.scanly.data.repo.DocumentRepository
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
        pdfBuilder.build(out, pages, wordMap, password)
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
