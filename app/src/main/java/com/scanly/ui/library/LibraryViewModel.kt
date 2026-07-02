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
import kotlinx.coroutines.flow.combine
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

    /** null = "All". */
    val selectedFolder = MutableStateFlow<String?>(null)

    val folders: StateFlow<List<String>> =
        repository.observeFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documents: StateFlow<List<DocumentSummary>> =
        combine(
            query.flatMapLatest { q ->
                if (q.isBlank()) repository.observeSummaries() else repository.searchSummaries(q)
            },
            selectedFolder,
        ) { docs, folder ->
            if (folder == null) docs else docs.filter { it.folder == folder }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onFolderSelect(folder: String?) { selectedFolder.value = folder }

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    /** Fires with the new document id when a gallery import finishes. */
    private val _importedDocId = MutableStateFlow<Long?>(null)
    val importedDocId = _importedDocId.asStateFlow()

    fun onQueryChange(q: String) { query.value = q }

    /** Library layout: grid (default) or Adobe-style list rows. */
    val isGrid = MutableStateFlow(true)
    fun toggleLayout() { isGrid.value = !isGrid.value }

    fun rename(id: Long, name: String) = viewModelScope.launch { repository.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { repository.deleteDocument(id) }

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
