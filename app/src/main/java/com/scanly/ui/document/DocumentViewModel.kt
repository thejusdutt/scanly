package com.scanly.ui.document

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.common.ContactParser
import com.scanly.data.db.DocumentWithPages
import com.scanly.data.files.DocumentStorage
import com.scanly.data.repo.DocumentRepository
import com.scanly.domain.ExportDocumentUseCase
import com.scanly.domain.RunOcrUseCase
import com.scanly.pdf.PdfPageSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class ExportAction { SHARE, SAVE_SAF, SHARE_IMAGES, PRINT, SHARE_VCF, SHARE_LONG_IMAGE, SHARE_TXT }

/** Freshly exported files awaiting a screen-side action (share intent or SAF "save as"). */
data class ExportResult(val files: List<File>, val action: ExportAction)

@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val runOcrUseCase: RunOcrUseCase,
    private val exportDocument: ExportDocumentUseCase,
    private val storage: DocumentStorage,
    private val unlockRegistry: com.scanly.ui.security.DocumentUnlockRegistry,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val documentId: Long = savedState.get<Long>("documentId") ?: -1L

    /** True once THIS session has passed the per-document unlock prompt. */
    private val _sessionUnlocked = MutableStateFlow(unlockRegistry.isUnlocked(documentId))
    val sessionUnlocked = _sessionUnlocked.asStateFlow()

    fun markUnlocked() {
        unlockRegistry.markUnlocked(documentId)
        _sessionUnlocked.value = true
    }

    /** Toggle the per-document lock (biometric/credential gate on open). */
    fun toggleLock() = viewModelScope.launch {
        val isLocked = document.value?.document?.locked ?: false
        repository.setLocked(documentId, !isLocked)
        if (!isLocked) {
            // Just locked it: the current session keeps access.
            markUnlocked()
            _message.value = "Locked — unlocking will require your screen lock"
        } else {
            _message.value = "Lock removed"
        }
    }

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

    /** Contact parsed from this document's OCR text, or null when nothing usable. */
    fun parsedContact(): ContactParser.Contact? =
        collectText()?.let { ContactParser.parse(it) }?.takeIf { !it.isEmpty }

    /** Write the parsed contact as a .vcf and hand it to the share sheet. */
    fun shareVCard() = viewModelScope.launch {
        val contact = parsedContact()
        if (contact == null) {
            _message.value = "No contact details found — run OCR first"
            return@launch
        }
        val file = withContext(Dispatchers.IO) {
            storage.exportFile("contact_$documentId.vcf")
                .apply { writeText(ContactParser.toVCard(contact)) }
        }
        _exported.value = ExportResult(listOf(file), ExportAction.SHARE_VCF)
    }

    fun requestShare(
        searchable: Boolean,
        password: String?,
        pageSize: PdfPageSize = PdfPageSize.AUTO,
        jpegQuality: Float = 0.9f,
    ) = buildThen(searchable, password, ExportAction.SHARE, pageSize, jpegQuality)

    fun requestSaveToDevice(
        searchable: Boolean,
        password: String?,
        pageSize: PdfPageSize = PdfPageSize.AUTO,
        jpegQuality: Float = 0.9f,
    ) = buildThen(searchable, password, ExportAction.SAVE_SAF, pageSize, jpegQuality)

    fun requestPrint() = buildThen(searchable = true, password = null, action = ExportAction.PRINT)

    /** Bake a user watermark onto every page (CamScanner parity — without their forced one). */
    fun applyWatermark(spec: com.scanly.cv.ImageProcessing.WatermarkSpec) =
        viewModelScope.launch {
            _busy.value = true
            repository.watermarkDocument(documentId, spec)
            _busy.value = false
            _message.value = "Watermark applied to all pages"
        }

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

    /** CamScanner-style "long image": all pages stitched into one tall JPEG. */
    fun requestLongImage() = viewModelScope.launch {
        _busy.value = true
        val file = runCatching { exportDocument.asLongImage(documentId) }.getOrNull()
        _busy.value = false
        if (file == null) _message.value = "Nothing to export yet"
        else _exported.value = ExportResult(listOf(file), ExportAction.SHARE_LONG_IMAGE)
    }

    /** Recognized text as a .txt file. */
    fun requestText() = viewModelScope.launch {
        _busy.value = true
        val file = exportDocument.asText(documentId)
        _busy.value = false
        if (file == null) _message.value = "No recognized text yet — run OCR first"
        else _exported.value = ExportResult(listOf(file), ExportAction.SHARE_TXT)
    }

    /** Move the selected pages into a fresh document ("split"). */
    fun extractPages(pageIds: List<Long>) = viewModelScope.launch {
        val newId = repository.extractPages(
            pageIds,
            com.scanly.common.RenamePattern.format(
                com.scanly.common.RenamePattern.DEFAULT, type = "Extract",
            ),
        )
        _message.value = if (newId == null) "Nothing selected"
        else "Moved ${pageIds.size} page(s) to a new document"
    }

    fun deletePages(pageIds: List<Long>) = viewModelScope.launch {
        pageIds.forEach { repository.deletePage(it) }
    }

    fun setTags(tags: List<String>) = viewModelScope.launch {
        repository.setTags(documentId, tags)
        _message.value = if (tags.isEmpty()) "Tags cleared" else "Tags saved"
    }

    private fun buildThen(
        searchable: Boolean,
        password: String?,
        action: ExportAction,
        pageSize: PdfPageSize = PdfPageSize.AUTO,
        jpegQuality: Float = 0.9f,
    ) = viewModelScope.launch {
        _busy.value = true
        val file = runCatching {
            exportDocument(
                documentId, searchable,
                password = password?.takeIf { it.isNotBlank() },
                pageSize = pageSize,
                jpegQuality = jpegQuality,
            )
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
