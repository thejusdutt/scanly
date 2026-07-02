package com.scanly.domain

import androidx.work.WorkManager
import com.scanly.data.repo.DocumentRepository
import com.scanly.ocr.OcrWorker
import javax.inject.Inject

/**
 * Queues OCR for every page of a document. Marks each page QUEUED immediately (so the UI
 * shows per-page status), then enqueues one isolated [OcrWorker] per page.
 */
class RunOcrUseCase @Inject constructor(
    private val repository: DocumentRepository,
    private val workManager: WorkManager,
) {
    suspend operator fun invoke(documentId: Long, languageCode: String = "eng") {
        val dwp = repository.getDocumentWithPages(documentId) ?: return
        for (page in dwp.pages) {
            repository.markOcrQueued(page.id)
            OcrWorker.enqueue(workManager, page.id, languageCode)
        }
    }
}
