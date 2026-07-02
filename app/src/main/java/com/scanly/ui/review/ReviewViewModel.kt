package com.scanly.ui.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.common.Filter
import com.scanly.data.db.DocumentWithPages
import com.scanly.data.repo.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
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

    fun deletePage(pageId: Long) =
        viewModelScope.launch { repository.deletePage(pageId) }

    fun rotatePage(pageId: Long) =
        viewModelScope.launch { repository.rotatePage(pageId) }

    fun movePage(pageId: Long, up: Boolean) =
        viewModelScope.launch { repository.movePage(pageId, up) }
}
