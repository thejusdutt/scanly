package com.scanly.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.data.db.DocumentSummary
import com.scanly.data.repo.DocumentRepository
import com.scanly.domain.ImportImagesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val importImages: ImportImagesUseCase,
) : ViewModel() {

    val query = MutableStateFlow("")

    val documents: StateFlow<List<DocumentSummary>> =
        query.flatMapLatest { q ->
            if (q.isBlank()) repository.observeSummaries() else repository.searchSummaries(q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    /** Fires with the new document id when a gallery import finishes. */
    private val _importedDocId = MutableStateFlow<Long?>(null)
    val importedDocId = _importedDocId.asStateFlow()

    fun onQueryChange(q: String) { query.value = q }

    fun importFromGallery(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            _importedDocId.value = importImages(uris)
            _importing.value = false
        }
    }

    fun consumeImported() { _importedDocId.value = null }
}
