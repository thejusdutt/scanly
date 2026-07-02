package com.scanly.ocr

import android.content.Context
import android.graphics.BitmapFactory
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.scanly.data.repo.DocumentRepository
import com.scanly.platform.TextRecognizer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Per-page OCR job. ONE work request per page (unique-named by page id) so:
 *  - it survives the app being backgrounded, and
 *  - a single corrupt/unreadable page fails on its own and is retryable, never aborting
 *    the rest of the batch (the multi-page OCR crash users report elsewhere).
 */
@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: DocumentRepository,
    private val recognizer: TextRecognizer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pageId = inputData.getLong(KEY_PAGE_ID, -1L)
        if (pageId <= 0) return Result.failure()
        val page = repository.getPage(pageId) ?: return Result.failure()

        val lang = inputData.getString(KEY_LANG) ?: "eng"
        val bitmap = BitmapFactory.decodeFile(page.imagePath)
            ?: run {
                repository.setOcrResult(pageId, null, ok = false)
                return Result.failure()
            }

        return try {
            val result = recognizer.recognize(bitmap, lang)
            repository.setOcrResult(pageId, result.plainText, ok = !result.isEmpty)
            Result.success()
        } catch (t: Throwable) {
            repository.setOcrResult(pageId, null, ok = false)
            // Retry transient failures a couple of times before giving up.
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val KEY_PAGE_ID = "pageId"
        const val KEY_LANG = "lang"

        fun enqueue(wm: WorkManager, pageId: Long, lang: String = "eng") {
            val request = OneTimeWorkRequestBuilder<OcrWorker>()
                .setInputData(workDataOf(
                    KEY_PAGE_ID to pageId,
                    KEY_LANG to lang,
                ))
                .addTag("ocr")
                .build()
            wm.enqueueUniqueWork(
                "ocr-page-$pageId",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
