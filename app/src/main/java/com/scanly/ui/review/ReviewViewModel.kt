package com.scanly.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.common.Filter
import com.scanly.cv.ImageProcessing
import com.scanly.data.db.DocumentWithPages
import com.scanly.data.repo.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    fun movePage(pageId: Long, up: Boolean) =
        viewModelScope.launch { repository.movePage(pageId, up) }

    /** Requested pager index (thumbnail-strip taps); consumed by the screen. */
    private val _scrollTo = MutableStateFlow<Int?>(null)
    val scrollTo = _scrollTo.asStateFlow()

    fun requestScroll(index: Int) { _scrollTo.value = index }
    fun consumeScroll() { _scrollTo.value = null }
}
