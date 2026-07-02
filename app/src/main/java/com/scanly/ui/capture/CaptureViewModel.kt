package com.scanly.ui.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.common.Filter
import com.scanly.data.repo.DocumentRepository
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CaptureUiState(
    val state: CaptureState = CaptureState.IDLE,
    val liveQuad: DocumentQuad? = null,
    val batchMode: Boolean = false,
    val autoCapture: Boolean = true,
    val pageCount: Int = 0,
    val documentId: Long? = null,
    val finishedDocumentId: Long? = null,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val detector: DocumentDetector,
    private val repository: DocumentRepository,
) : ViewModel() {

    private val machine = CaptureStateMachine()
    private val _ui = MutableStateFlow(CaptureUiState())
    val ui = _ui.asStateFlow()

    fun init(appendToDocumentId: Long?) {
        if (_ui.value.documentId == null) {
            _ui.update { it.copy(documentId = appendToDocumentId) }
        }
    }

    fun toggleBatch() = _ui.update { it.copy(batchMode = !it.batchMode) }
    fun toggleAuto() = _ui.update { it.copy(autoCapture = !it.autoCapture) }

    /** Live preview frame (RGBA) → detector. Returns whether to auto-capture now. */
    fun onPreviewFrame(frame: Bitmap, now: Long = System.currentTimeMillis()): Boolean {
        val quad = detector.detect(frame)
        val shouldCapture = machine.onDetection(quad, now, _ui.value.autoCapture)
        _ui.update { it.copy(liveQuad = quad, state = machine.state) }
        return shouldCapture
    }

    /** Full-resolution capture: warp + save as a page; create the doc on first page. */
    fun onCaptured(fullRes: Bitmap) {
        viewModelScope.launch {
            // Re-detect on the full-resolution image for an accurate crop, rather than
            // upscaling the coarse preview quad. Fall back to the full frame.
            val quad = withContext(Dispatchers.Default) {
                detector.detect(fullRes) ?: DocumentQuad.full(fullRes.width, fullRes.height)
            }
            val docId = _ui.value.documentId ?: withContext(Dispatchers.IO) {
                repository.createDocument(defaultName())
            }
            repository.addPage(docId, fullRes, quad, Filter.COLOR)
            fullRes.recycle()
            machine.afterCapture(_ui.value.batchMode)
            _ui.update {
                it.copy(
                    documentId = docId,
                    pageCount = it.pageCount + 1,
                    state = machine.state,
                )
            }
            if (!_ui.value.batchMode) finish()
        }
    }

    fun finish() {
        _ui.update { it.copy(finishedDocumentId = it.documentId) }
    }

    private fun defaultName(): String =
        com.scanly.common.RenamePattern.format(com.scanly.common.RenamePattern.DEFAULT, type = "Scan")
}
