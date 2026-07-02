package com.scanly.ui.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.common.Filter
import com.scanly.common.RenamePattern
import com.scanly.data.repo.DocumentRepository
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import com.scanly.cv.ImageProcessing
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
    val idCardMode: Boolean = false,
    /** In ID mode: true once the front side is captured and we're waiting for the back. */
    val idFrontCaptured: Boolean = false,
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

    private var idFront: Bitmap? = null

    fun init(appendToDocumentId: Long?) {
        if (_ui.value.documentId == null) {
            _ui.update { it.copy(documentId = appendToDocumentId) }
        }
    }

    fun toggleBatch() = _ui.update { it.copy(batchMode = !it.batchMode) }
    fun toggleAuto() = _ui.update { it.copy(autoCapture = !it.autoCapture) }

    fun toggleIdCard() = _ui.update {
        idFront?.recycle(); idFront = null
        // ID mode is a two-shot manual flow: auto-capture off while active.
        it.copy(
            idCardMode = !it.idCardMode,
            idFrontCaptured = false,
            autoCapture = if (!it.idCardMode) false else it.autoCapture,
        )
    }

    /** Live preview frame (RGBA) → detector. Returns whether to auto-capture now. */
    fun onPreviewFrame(frame: Bitmap, now: Long = System.currentTimeMillis()): Boolean {
        val quad = detector.detect(frame)
        val auto = _ui.value.autoCapture && !_ui.value.idCardMode
        val shouldCapture = machine.onDetection(quad, now, auto)
        _ui.update { it.copy(liveQuad = quad, state = machine.state) }
        return shouldCapture
    }

    /** Full-resolution capture: warp + save as a page; create the doc on first page. */
    fun onCaptured(fullRes: Bitmap) {
        viewModelScope.launch {
            if (_ui.value.idCardMode) {
                onIdCardShot(fullRes)
                return@launch
            }
            // Re-detect on the full-resolution image for an accurate crop, rather than
            // upscaling the coarse preview quad. Fall back to the full frame.
            val quad = withContext(Dispatchers.Default) {
                detector.detect(fullRes) ?: DocumentQuad.full(fullRes.width, fullRes.height)
            }
            val docId = _ui.value.documentId ?: withContext(Dispatchers.IO) {
                repository.createDocument(defaultName("Scan"))
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

    /** ID-card flow: first shot = front, second = back, composed onto one page. */
    private suspend fun onIdCardShot(fullRes: Bitmap) {
        val card = withContext(Dispatchers.Default) {
            val quad = detector.detect(fullRes) ?: DocumentQuad.full(fullRes.width, fullRes.height)
            ImageProcessing.warp(fullRes, quad).also { fullRes.recycle() }
        }
        val front = idFront
        if (front == null) {
            idFront = card
            _ui.update { it.copy(idFrontCaptured = true) }
            return
        }
        idFront = null
        val composed = withContext(Dispatchers.Default) { composeIdPage(front, card) }
        front.recycle(); card.recycle()
        val docId = _ui.value.documentId ?: withContext(Dispatchers.IO) {
            repository.createDocument(defaultName("IDCard"))
        }
        repository.addPage(docId, composed, DocumentQuad.full(composed.width, composed.height), Filter.COLOR)
        composed.recycle()
        _ui.update {
            it.copy(documentId = docId, pageCount = it.pageCount + 1, idFrontCaptured = false)
        }
        finish()
    }

    fun finish() {
        _ui.update { it.copy(finishedDocumentId = it.documentId) }
    }

    override fun onCleared() {
        idFront?.recycle(); idFront = null
    }

    private fun defaultName(type: String): String =
        RenamePattern.format(RenamePattern.DEFAULT, type = type)

    companion object {
        /**
         * Both card sides stacked on one white A4-proportioned page, like the
         * "ID mode" competitors gate behind a subscription.
         */
        fun composeIdPage(front: Bitmap, back: Bitmap): Bitmap {
            val cardW = maxOf(front.width, back.width)
            val pageW = (cardW * 1.25f).toInt()
            val pageH = (pageW * 1.414f).toInt() // A4 ratio
            val page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            fun draw(b: Bitmap, centerY: Float) {
                val scale = minOf(cardW.toFloat() / b.width, pageH * 0.4f / b.height)
                val w = b.width * scale
                val h = b.height * scale
                val left = (pageW - w) / 2f
                canvas.drawBitmap(
                    b, null,
                    android.graphics.RectF(left, centerY - h / 2, left + w, centerY + h / 2),
                    paint,
                )
            }
            draw(front, pageH * 0.28f)
            draw(back, pageH * 0.72f)
            return page
        }
    }
}
