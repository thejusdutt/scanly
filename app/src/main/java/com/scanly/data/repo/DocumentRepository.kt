package com.scanly.data.repo

import android.graphics.Bitmap
import com.scanly.common.Filter
import com.scanly.cv.ImageProcessing
import com.scanly.data.db.DocumentEntity
import com.scanly.data.db.DocumentSummary
import com.scanly.data.db.DocumentWithPages
import com.scanly.data.db.OcrStatus
import com.scanly.data.db.PageEntity
import com.scanly.data.db.ScanlyDao
import com.scanly.data.files.DocumentStorage
import com.scanly.platform.DocumentQuad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for documents and pages. Orchestrates DB + file storage and the
 * warp/filter pipeline so ViewModels stay thin.
 */
@Singleton
class DocumentRepository @Inject constructor(
    private val dao: ScanlyDao,
    private val storage: DocumentStorage,
) {
    fun observeDocuments(): Flow<List<DocumentEntity>> = dao.observeDocuments()
    fun observeDocument(id: Long): Flow<DocumentWithPages?> = dao.observeDocumentWithPages(id)
    fun search(query: String): Flow<List<DocumentEntity>> =
        dao.searchDocuments(sanitizeFts(query))

    fun observeSummaries(): Flow<List<DocumentSummary>> = dao.observeSummaries()
    fun searchSummaries(query: String): Flow<List<DocumentSummary>> =
        dao.searchSummaries(sanitizeFts(query))

    suspend fun createDocument(name: String): Long =
        dao.insertDocument(DocumentEntity(name = name))

    /**
     * Add a captured page: warp to the detected quad, apply [filter], persist both the
     * processed and original images, and insert the page row.
     */
    suspend fun addPage(
        documentId: Long,
        original: Bitmap,
        quad: DocumentQuad,
        filter: Filter = Filter.COLOR,
    ): Long = withContext(Dispatchers.Default) {
        val warped = ImageProcessing.warp(original, quad)
        val processed = ImageProcessing.applyFilter(warped, filter)
        if (processed !== warped) warped.recycle()
        val imagePath = storage.savePageImage(documentId, processed)
        processed.recycle()
        val originalPath = storage.saveOriginal(documentId, original)
        val order = dao.nextOrderIndex(documentId)
        dao.insertPage(
            PageEntity(
                documentId = documentId,
                orderIndex = order,
                imagePath = imagePath,
                originalPath = originalPath,
                filter = filter,
            ),
        ).also { touch(documentId) }
    }

    /** Re-apply a filter to an existing page from its original capture. */
    suspend fun changeFilter(pageId: Long, filter: Filter) = withContext(Dispatchers.Default) {
        val page = dao.getPage(pageId) ?: return@withContext
        val originalPath = page.originalPath ?: return@withContext
        val original = android.graphics.BitmapFactory.decodeFile(originalPath) ?: return@withContext
        val processed = ImageProcessing.applyFilter(original, filter)
        original.recycle()
        storage.deletePage(page.imagePath)
        val newPath = storage.savePageImage(page.documentId, processed)
        processed.recycle()
        dao.updatePage(page.copy(imagePath = newPath, filter = filter, ocrStatus = OcrStatus.NONE))
        touch(page.documentId)
    }

    /** Re-warp a page from its original capture with a user-adjusted quad (manual crop). */
    suspend fun recrop(pageId: Long, quad: DocumentQuad) = withContext(Dispatchers.Default) {
        val page = dao.getPage(pageId) ?: return@withContext
        val originalPath = page.originalPath ?: return@withContext
        val original = android.graphics.BitmapFactory.decodeFile(originalPath) ?: return@withContext
        val warped = ImageProcessing.warp(original, quad)
        original.recycle()
        val processed = ImageProcessing.applyFilter(warped, page.filter)
        if (processed !== warped) warped.recycle()
        storage.deletePage(page.imagePath)
        val newPath = storage.savePageImage(page.documentId, processed)
        processed.recycle()
        dao.updatePage(
            page.copy(imagePath = newPath, ocrStatus = OcrStatus.NONE, ocrText = null),
        )
        touch(page.documentId)
    }

    /** Rotate the processed page image 90° clockwise. */
    suspend fun rotatePage(pageId: Long) = withContext(Dispatchers.Default) {
        val page = dao.getPage(pageId) ?: return@withContext
        val bmp = android.graphics.BitmapFactory.decodeFile(page.imagePath) ?: return@withContext
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated !== bmp) bmp.recycle()
        storage.deletePage(page.imagePath)
        val newPath = storage.savePageImage(page.documentId, rotated)
        rotated.recycle()
        dao.updatePage(page.copy(imagePath = newPath, rotationDeg = (page.rotationDeg + 90) % 360))
        touch(page.documentId)
    }

    /** Swap a page with its neighbor above/below in the reading order. */
    suspend fun movePage(pageId: Long, up: Boolean) {
        val page = dao.getPage(pageId) ?: return
        val pages = dao.getDocumentWithPages(page.documentId)?.pages
            ?.sortedBy { it.orderIndex } ?: return
        val idx = pages.indexOfFirst { it.id == pageId }
        val swapIdx = if (up) idx - 1 else idx + 1
        if (idx < 0 || swapIdx !in pages.indices) return
        val other = pages[swapIdx]
        dao.updatePage(pages[idx].copy(orderIndex = other.orderIndex))
        dao.updatePage(other.copy(orderIndex = pages[idx].orderIndex))
        touch(page.documentId)
    }

    /**
     * Composite a signature PNG onto a page image. Position/size are fractions of the
     * page (center x/y, signature width), so the caller's preview maps 1:1.
     */
    suspend fun stampSignature(
        pageId: Long,
        signaturePath: String,
        centerXFrac: Float,
        centerYFrac: Float,
        widthFrac: Float,
    ) = withContext(Dispatchers.Default) {
        val page = dao.getPage(pageId) ?: return@withContext
        val base = android.graphics.BitmapFactory.decodeFile(page.imagePath) ?: return@withContext
        val sig = android.graphics.BitmapFactory.decodeFile(signaturePath)
            ?: run { base.recycle(); return@withContext }
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        base.recycle()
        val targetW = out.width * widthFrac
        val targetH = targetW * sig.height / sig.width
        val left = centerXFrac * out.width - targetW / 2
        val top = centerYFrac * out.height - targetH / 2
        android.graphics.Canvas(out).drawBitmap(
            sig, null,
            android.graphics.RectF(left, top, left + targetW, top + targetH),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        sig.recycle()
        storage.deletePage(page.imagePath)
        val newPath = storage.savePageImage(page.documentId, out)
        out.recycle()
        dao.updatePage(page.copy(imagePath = newPath))
        touch(page.documentId)
    }

    suspend fun deletePage(pageId: Long) {
        val page = dao.getPage(pageId) ?: return
        storage.deletePage(page.imagePath)
        page.originalPath?.let(storage::deletePage)
        dao.deletePage(pageId)
        touch(page.documentId)
    }

    suspend fun deleteDocument(id: Long) {
        storage.deleteDocument(id)
        dao.deleteDocument(id)
    }

    suspend fun rename(id: Long, name: String) =
        dao.renameDocument(id, name, System.currentTimeMillis())

    suspend fun getDocumentWithPages(id: Long): DocumentWithPages? = dao.getDocumentWithPages(id)

    suspend fun getPage(pageId: Long): PageEntity? = dao.getPage(pageId)

    suspend fun markOcrQueued(pageId: Long) = dao.setOcrStatus(pageId, OcrStatus.QUEUED)
    suspend fun setOcrResult(pageId: Long, text: String?, ok: Boolean) =
        dao.setOcrResult(pageId, if (ok) OcrStatus.DONE else OcrStatus.FAILED, text)

    private suspend fun touch(documentId: Long) =
        dao.touchDocument(documentId, System.currentTimeMillis())

    private fun sanitizeFts(raw: String): String = com.scanly.common.FtsQuery.sanitize(raw)
}
