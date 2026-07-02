package com.scanly.ui.document

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.data.db.DocumentWithPages
import com.scanly.data.repo.DocumentRepository
import com.scanly.domain.ExportDocumentUseCase
import com.scanly.domain.RunOcrUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class ExportAction { SHARE, SAVE_SAF, SHARE_IMAGES, PRINT }

/** Freshly exported files awaiting a screen-side action (share intent or SAF "save as"). */
data class ExportResult(val files: List<File>, val action: ExportAction)

@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val runOcrUseCase: RunOcrUseCase,
    private val exportDocument: ExportDocumentUseCase,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val documentId: Long = savedState.get<Long>("documentId") ?: -1L

    val document: StateFlow<DocumentWithPages?> =
        repository.observeDocument(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _exported = MutableStateFlow<ExportResult?>(null)
    val exported = _exported.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun runOcr() = viewModelScope.launch {
        runOcrUseCase(documentId)
        _message.value = "OCR queued for all pages"
    }

    fun rename(name: String) = viewModelScope.launch { repository.rename(documentId, name) }

    /** All recognized text of the document in page order, or null if none yet. */
    fun collectText(): String? {
        val pages = document.value?.pages?.sortedBy { it.orderIndex } ?: return null
        val text = pages.mapNotNull { it.ocrText?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        return text.ifBlank { null }
    }

    fun requestShare(searchable: Boolean, password: String?) =
        buildThen(searchable, password, ExportAction.SHARE)

    fun requestSaveToDevice(searchable: Boolean, password: String?) =
        buildThen(searchable, password, ExportAction.SAVE_SAF)

    fun requestPrint() = buildThen(searchable = true, password = null, action = ExportAction.PRINT)

    val folders: StateFlow<List<String>> =
        repository.observeFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFolder(folder: String?) = viewModelScope.launch {
        repository.setFolder(documentId, folder)
        _message.value = if (folder.isNullOrBlank()) "Removed from folder"
        else "Moved to \"$folder\""
    }

    fun requestShareImages() = viewModelScope.launch {
        _busy.value = true
        val files = exportDocument.asImages(documentId)
        _busy.value = false
        if (files.isEmpty()) _message.value = "Nothing to export yet"
        else _exported.value = ExportResult(files, ExportAction.SHARE_IMAGES)
    }

    private fun buildThen(searchable: Boolean, password: String?, action: ExportAction) =
        viewModelScope.launch {
            _busy.value = true
            val file = runCatching {
                exportDocument(documentId, searchable, password = password?.takeIf { it.isNotBlank() })
            }.getOrNull()
            _busy.value = false
            if (file == null) _message.value = "Nothing to export yet"
            else _exported.value = ExportResult(listOf(file), action)
        }

    fun consumeExport() { _exported.value = null }
    fun consumeMessage() { _message.value = null }
    fun postMessage(text: String) { _message.value = text }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        repository.deleteDocument(documentId)
        onDeleted()
    }
}
