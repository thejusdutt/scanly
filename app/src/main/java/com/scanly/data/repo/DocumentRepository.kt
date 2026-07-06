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
import kotlinx.coroutines.flow.map
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
                cropQuad = quad.serialize(),
                filter = filter,
            ),
        ).also { touch(documentId) }
    }

    /**
     * Add a page whose image is ALREADY flat (book half, whiteboard full-frame): no warp,
     * just the filter. The unfiltered image is kept as the "original" so filter changes
     * and manual re-crops still work.
     */
    suspend fun addProcessedPage(
        documentId: Long,
        image: Bitmap,
        filter: Filter = Filter.COLOR,
    ): Long = withContext(Dispatchers.Default) {
        val processed = ImageProcessing.applyFilter(image, filter)
        val imagePath = storage.savePageImage(documentId, processed)
        if (processed !== image) processed.recycle()
        val originalPath = storage.saveOriginal(documentId, image)
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

    /**
     * Re-shoot an existing page in place (Adobe-style "retake"): new original + warp,
     * same document position and filter. Returns the owning document id.
     */
    suspend fun replacePage(
        pageId: Long,
        original: Bitmap,
        quad: DocumentQuad,
    ): Long? = withContext(Dispatchers.Default) {
        val page = dao.getPage(pageId) ?: return@withContext null
        val warped = ImageProcessing.warp(original, quad)
        val processed = ImageProcessing.applyFilter(warped, page.filter)
        if (processed !== warped) warped.recycle()
        storage.deletePage(page.imagePath)
        page.originalPath?.let(storage::deletePage)
        val imagePath = storage.savePageImage(page.documentId, processed)
        processed.recycle()
        val originalPath = storage.saveOriginal(page.documentId, original)
        dao.updatePage(
            page.copy(
                imagePath = imagePath,
                originalPath = originalPath,
                cropQuad = quad.serialize(),
                rotationDeg = 0,
                ocrStatus = OcrStatus.NONE,
                ocrText = null,
            ),
        )
        touch(page.documentId)
        page.documentId
    }

    /**
     * Re-apply a filter to an existing page from its original capture, re-warping with
     * the page's stored crop first. (Without the warp, every filter change silently
     * replaced a neatly cropped page with the full uncropped capture.) Pages whose
     * original is already flat — book halves, whiteboard, imports, legacy rows — have
     * no stored crop and use the original as-is.
     */
    suspend fun changeFilter(pageId: Long, filter: Filter) = withContext(Dispatchers.Default) {
        val page = dao.getPage(pageId) ?: return@withContext
        val originalPath = page.originalPath ?: return@withContext
        val original = android.graphics.BitmapFactory.decodeFile(originalPath) ?: return@withContext
        val quad = page.cropQuad?.let { DocumentQuad.deserialize(it) }
        val base = if (quad != null) {
            ImageProcessing.warp(original, quad).also { original.recycle() }
        } else {
            original
        }
        val processed = ImageProcessing.applyFilter(base, filter)
        if (processed !== base) base.recycle()
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
            page.copy(
                imagePath = newPath,
                cropQuad = quad.serialize(),
                ocrStatus = OcrStatus.NONE,
                ocrText = null,
            ),
        )
        touch(page.documentId)
    }

    /**
     * Replace a page's processed image with [transform] applied to it (adjust, inpaint,
     * markup, straighten, watermark). The original capture is untouched, so filter
     * changes and re-crops still reset these edits — same semantics as filters. OCR is
     * invalidated because the pixels changed.
     */
    suspend fun updateProcessedImage(
        pageId: Long,
        transform: (Bitmap) -> Bitmap?,
    ) = withContext(Dispatchers.Default) {
        val page = dao.getPage(pageId) ?: return@withContext
        val src = android.graphics.BitmapFactory.decodeFile(page.imagePath) ?: return@withContext
        val out = transform(src)
        if (out == null) {
            src.recycle()
            return@withContext
        }
        if (out !== src) src.recycle()
        storage.deletePage(page.imagePath)
        val newPath = storage.savePageImage(page.documentId, out)
        out.recycle()
        dao.updatePage(page.copy(imagePath = newPath, ocrStatus = OcrStatus.NONE, ocrText = null))
        touch(page.documentId)
    }

    /** Bake a user watermark onto every page of the document. */
    suspend fun watermarkDocument(documentId: Long, spec: ImageProcessing.WatermarkSpec) {
        val pages = dao.getDocumentWithPages(documentId)?.pages ?: return
        for (page in pages) {
            updateProcessedImage(page.id) { ImageProcessing.watermark(it, spec) }
        }
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

    /** Save an image as a page verbatim (PDF import): no warp, no filter enhancement. */
    suspend fun addRawPage(documentId: Long, image: Bitmap): Long = withContext(Dispatchers.IO) {
        val imagePath = storage.savePageImage(documentId, image)
        val originalPath = storage.saveOriginal(documentId, image)
        val order = dao.nextOrderIndex(documentId)
        dao.insertPage(
            PageEntity(
                documentId = documentId,
                orderIndex = order,
                imagePath = imagePath,
                originalPath = originalPath,
                filter = Filter.COLOR,
            ),
        ).also { touch(documentId) }
    }

    /** Move pages (rows + image files) to the end of [targetDocId]. */
    suspend fun movePages(pageIds: List<Long>, targetDocId: Long) = withContext(Dispatchers.IO) {
        var order = dao.nextOrderIndex(targetDocId)
        val touched = mutableSetOf<Long>()
        for (pageId in pageIds) {
            val page = dao.getPage(pageId) ?: continue
            if (page.documentId == targetDocId) continue
            val newImage = storage.movePageFile(page.imagePath, targetDocId)
            val newOriginal = page.originalPath?.let { storage.movePageFile(it, targetDocId) }
            dao.updatePage(
                page.copy(
                    documentId = targetDocId,
                    orderIndex = order++,
                    imagePath = newImage,
                    originalPath = newOriginal,
                ),
            )
            touched.add(page.documentId)
        }
        touched.forEach { touch(it) }
        touch(targetDocId)
    }

    /** Merge whole documents into the FIRST id; emptied source documents are deleted. */
    suspend fun mergeDocuments(ids: List<Long>): Long? {
        val target = ids.firstOrNull() ?: return null
        for (source in ids.drop(1)) {
            val pages = dao.getDocumentWithPages(source)?.pages
                ?.sortedBy { it.orderIndex } ?: continue
            movePages(pages.map { it.id }, target)
            deleteDocument(source)
        }
        return target
    }

    /** Extract pages into a new document ("split"); returns the new document id. */
    suspend fun extractPages(pageIds: List<Long>, name: String): Long? {
        if (pageIds.isEmpty()) return null
        val docId = createDocument(name)
        movePages(pageIds, docId)
        return docId
    }

    suspend fun setTags(id: Long, tags: List<String>) =
        dao.setTags(id, tags.joinToString(",").ifBlank { null })

    /** Distinct user tags across the library, for the filter chips. */
    fun observeAllTags(): Flow<List<String>> = dao.observeTagCsvs().map { rows ->
        rows.flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
    }

    suspend fun setLocked(id: Long, locked: Boolean) = dao.setLocked(id, locked)

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

    fun observeFolders(): Flow<List<String>> = dao.observeFolders()

    suspend fun setFolder(id: Long, folder: String?) =
        dao.setFolder(id, folder?.trim()?.ifBlank { null })

    suspend fun getDocumentWithPages(id: Long): DocumentWithPages? = dao.getDocumentWithPages(id)

    suspend fun getPage(pageId: Long): PageEntity? = dao.getPage(pageId)

    suspend fun markOcrQueued(pageId: Long) = dao.setOcrStatus(pageId, OcrStatus.QUEUED)
    suspend fun setOcrResult(pageId: Long, text: String?, status: OcrStatus) =
        dao.setOcrResult(pageId, status, text)

    private suspend fun touch(documentId: Long) =
        dao.touchDocument(documentId, System.currentTimeMillis())

    private fun sanitizeFts(raw: String): String = com.scanly.common.FtsQuery.sanitize(raw)
}
