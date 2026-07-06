package com.scanly.ui.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.common.Filter
import com.scanly.cv.ImageProcessing
import com.scanly.cv.QuadGeometry
import com.scanly.data.db.DocumentWithPages
import com.scanly.data.db.PageEntity
import com.scanly.data.repo.DocumentRepository
import com.scanly.platform.DocumentQuad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: DocumentRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val documentId: Long = savedState.get<Long>("documentId") ?: -1L

    val document: StateFlow<DocumentWithPages?> =
        repository.observeDocument(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setFilter(pageId: Long, filter: Filter) =
        viewModelScope.launch { repository.changeFilter(pageId, filter) }

    /** Bake brightness/contrast into one page (preview shows the identical formula). */
    fun adjustPage(pageId: Long, brightness: Float, contrast: Float) =
        viewModelScope.launch {
            repository.updateProcessedImage(pageId) {
                ImageProcessing.adjust(it, brightness, contrast)
            }
        }

    /** Same adjustment applied to every page (Adobe-style bulk edit). */
    fun adjustAllPages(brightness: Float, contrast: Float) =
        viewModelScope.launch {
            val pages = document.value?.pages.orEmpty()
            for (page in pages) {
                repository.updateProcessedImage(page.id) {
                    ImageProcessing.adjust(it, brightness, contrast)
                }
            }
        }

    /** Small-angle deskew bake. */
    fun straightenPage(pageId: Long, degrees: Float) =
        viewModelScope.launch {
            repository.updateProcessedImage(pageId) { ImageProcessing.rotateFine(it, degrees) }
        }

    fun deletePage(pageId: Long) =
        viewModelScope.launch { repository.deletePage(pageId) }

    fun rotatePage(pageId: Long) =
        viewModelScope.launch { repository.rotatePage(pageId) }

    /** Serialized so rapid drag-reorder swaps can't interleave and scramble the order. */
    private val moveMutex = Mutex()

    fun movePage(pageId: Long, up: Boolean) =
        viewModelScope.launch { moveMutex.withLock { repository.movePage(pageId, up) } }

    /** Requested pager index (thumbnail-strip taps); consumed by the screen. */
    private val _scrollTo = MutableStateFlow<Int?>(null)
    val scrollTo = _scrollTo.asStateFlow()

    fun requestScroll(index: Int) { _scrollTo.value = index }
    fun consumeScroll() { _scrollTo.value = null }

    // ---- filter previews ----

    /** One page's worth of per-filter thumbnails; regenerated when the page changes. */
    private var previewCache: Pair<String, Map<Filter, Bitmap>>? = null

    /**
     * Small per-filter previews of [page], built the same way changeFilter builds the
     * real image: downscaled original, warped with the stored crop, then each filter.
     */
    suspend fun filterPreviews(page: PageEntity): Map<Filter, Bitmap>? {
        // Keyed on what previews are built FROM: id + original + crop. Edits that only
        // touch the processed image (markup, adjust) don't invalidate them.
        val key = "${page.id}:${page.originalPath}:${page.cropQuad}"
        previewCache?.let { (k, v) -> if (k == key) return v }
        val built = buildPreviews(page) ?: return null
        previewCache = key to built
        return built
    }

    private suspend fun buildPreviews(page: PageEntity): Map<Filter, Bitmap>? =
        withContext(Dispatchers.Default) {
            val src = page.originalPath ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= PREVIEW_WIDTH) sample *= 2
            val original = BitmapFactory.decodeFile(
                src,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@withContext null
            // The stored crop is in full-resolution pixels; scale it to the decode size.
            val quad = page.cropQuad?.let { DocumentQuad.deserialize(it) }?.let {
                QuadGeometry.scale(
                    it,
                    original.width.toFloat() / bounds.outWidth,
                    original.height.toFloat() / bounds.outHeight,
                )
            }
            val base = if (quad != null) {
                ImageProcessing.warp(original, quad).also { original.recycle() }
            } else {
                original
            }
            val previews = Filter.entries.associateWith { ImageProcessing.applyFilter(base, it) }
            base.recycle()
            previews
        }

    private companion object {
        const val PREVIEW_WIDTH = 220
    }
}
