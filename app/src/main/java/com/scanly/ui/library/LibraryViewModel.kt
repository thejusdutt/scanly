package com.scanly.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.data.db.DocumentSummary
import com.scanly.data.repo.DocumentRepository
import com.scanly.domain.ImportImagesUseCase
import com.scanly.domain.ImportPdfUseCase
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

enum class LibrarySort { RECENT, NAME, OLDEST }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val importImages: ImportImagesUseCase,
    private val importPdf: ImportPdfUseCase,
) : ViewModel() {

    val query = MutableStateFlow("")

    /** null = "All". */
    val selectedFolder = MutableStateFlow<String?>(null)
    val selectedTag = MutableStateFlow<String?>(null)
    val sort = MutableStateFlow(LibrarySort.RECENT)

    val folders: StateFlow<List<String>> =
        repository.observeFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTags: StateFlow<List<String>> =
        repository.observeAllTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documents: StateFlow<List<DocumentSummary>> =
        combine(
            query.flatMapLatest { q ->
                if (q.isBlank()) repository.observeSummaries() else repository.searchSummaries(q)
            },
            selectedFolder,
            selectedTag,
            sort,
        ) { docs, folder, tag, sortMode ->
            docs
                .filter { folder == null || it.folder == folder }
                .filter { doc ->
                    tag == null || doc.tags.orEmpty()
                        .split(',').map(String::trim).contains(tag)
                }
                .let { list ->
                    when (sortMode) {
                        LibrarySort.RECENT -> list.sortedByDescending { it.updatedAt }
                        LibrarySort.OLDEST -> list.sortedBy { it.updatedAt }
                        LibrarySort.NAME -> list.sortedBy { it.name.lowercase() }
                    }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onFolderSelect(folder: String?) { selectedFolder.value = folder }
    fun onTagSelect(tag: String?) { selectedTag.value = tag }
    fun setSort(mode: LibrarySort) { sort.value = mode }

    // ---- multi-select ----

    val selection = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleSelect(id: Long) {
        selection.value = if (id in selection.value) selection.value - id else selection.value + id
    }

    fun clearSelection() { selection.value = emptySet() }

    fun deleteSelected() = viewModelScope.launch {
        selection.value.forEach { repository.deleteDocument(it) }
        clearSelection()
    }

    /** Merge the selected documents, in current list order, into the first one. */
    fun mergeSelected() = viewModelScope.launch {
        val ordered = documents.value.filter { it.id in selection.value }.map { it.id }
        repository.mergeDocuments(ordered)
        clearSelection()
    }

    fun moveSelectedToFolder(folder: String?) = viewModelScope.launch {
        selection.value.forEach { repository.setFolder(it, folder) }
        clearSelection()
    }

    // ---- imports ----

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    /** Fires with the new document id when an import finishes. */
    private val _importedDocId = MutableStateFlow<Long?>(null)
    val importedDocId = _importedDocId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

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

    /** Render an existing PDF into pages on-device (PdfBox — no network). */
    fun importPdfFile(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            _importing.value = true
            val docId = importPdf(uri)
            _importing.value = false
            if (docId == null) {
                _message.value = "Couldn't read that PDF (is it password-protected?)"
            } else {
                _importedDocId.value = docId
            }
        }
    }

    fun consumeImported() { _importedDocId.value = null }
    fun consumeMessage() { _message.value = null }
}
