package com.scanly.ui.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanly.common.Filter
import com.scanly.common.RenamePattern
import com.scanly.cv.BookSplitter
import com.scanly.cv.ImageProcessing
import com.scanly.cv.QuadGeometry
import com.scanly.data.repo.DocumentRepository
import com.scanly.domain.RunOcrUseCase
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import com.scanly.qr.QrDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

/**
 * Capture modes, Adobe/CamScanner-parity:
 *  - DOCUMENT: boundary detect + warp, continuous batch scanning.
 *  - BOOK: capture an open spread, auto-split at the spine into two pages.
 *  - ID_CARD: two-shot flow, both sides composed onto one page.
 *  - WHITEBOARD: full-frame capture (no crop) + whiteboard filter.
 *  - BUSINESS_CARD: boundary detect, single shot, OCR auto-queued so "Add to
 *    contacts" works right after saving.
 *  - QR: live decode only; nothing is stored.
 */
enum class CaptureMode {
    DOCUMENT, BOOK, ID_CARD, WHITEBOARD, BUSINESS_CARD, QR;

    /** Modes where a stable quad may fire the shutter automatically. */
    val autoCapturable: Boolean
        get() = this == DOCUMENT || this == BOOK || this == BUSINESS_CARD

    /** Modes that run the document detector and show the live overlay. */
    val usesBoundaryDetection: Boolean
        get() = this != WHITEBOARD && this != QR
}

data class CaptureUiState(
    val state: CaptureState = CaptureState.IDLE,
    val mode: CaptureMode = CaptureMode.DOCUMENT,
    val liveQuad: DocumentQuad? = null,
    /** Analyzer frame dimensions, needed to map quad coords onto the preview view. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** True when no document has been detected for a few seconds (Adobe-style hint). */
    val noDocumentHint: Boolean = false,
    val autoCapture: Boolean = true,
    /** In ID mode: true once the front side is captured and we're waiting for the back. */
    val idFrontCaptured: Boolean = false,
    val pageCount: Int = 0,
    /** Thumbnail of the most recently captured page (Adobe-style capture stack). */
    val lastPageThumb: String? = null,
    val documentId: Long? = null,
    val finishedDocumentId: Long? = null,
    /** When set, the next shot REPLACES this page and the screen closes. */
    val retakePageId: Long? = null,
    /** Decoded QR payload awaiting user action (QR mode). */
    val qrResult: String? = null,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val detector: DocumentDetector,
    private val repository: DocumentRepository,
    private val qrDecoder: QrDecoder,
    private val runOcr: RunOcrUseCase,
) : ViewModel() {

    private val machine = CaptureStateMachine()
    private val stabilizer = QuadStabilizer()
    private val _ui = MutableStateFlow(CaptureUiState())
    val ui = _ui.asStateFlow()

    private var idFront: Bitmap? = null

    fun init(appendToDocumentId: Long?, retakePageId: Long? = null) {
        _ui.update {
            it.copy(
                documentId = it.documentId ?: appendToDocumentId,
                retakePageId = it.retakePageId ?: retakePageId,
            )
        }
    }

    fun toggleAuto() = _ui.update { it.copy(autoCapture = !it.autoCapture) }

    fun setMode(mode: CaptureMode) {
        if (_ui.value.mode == mode) return
        idFront?.recycle(); idFront = null
        stabilizer.reset()
        _ui.update {
            it.copy(
                mode = mode,
                idFrontCaptured = false,
                liveQuad = null,
                qrResult = null,
                state = CaptureState.SEARCHING,
            )
        }
    }

    fun dismissQr() = _ui.update { it.copy(qrResult = null) }

    private var lastQuadSeenAt = 0L

    /** Live preview frame (RGBA) → detector. Returns whether to auto-capture now. */
    fun onPreviewFrame(frame: Bitmap, now: Long = System.currentTimeMillis()): Boolean {
        val mode = _ui.value.mode

        if (mode == CaptureMode.QR) {
            // Decode-only: pause while a result sheet is showing.
            if (_ui.value.qrResult == null) {
                qrDecoder.decode(frame)?.let { text ->
                    _ui.update { it.copy(qrResult = text) }
                }
            }
            return false
        }
        if (mode == CaptureMode.WHITEBOARD) {
            // Full-frame capture: no boundary, no overlay, manual shutter only.
            if (_ui.value.liveQuad != null || _ui.value.frameWidth != frame.width) {
                _ui.update {
                    it.copy(liveQuad = null, frameWidth = frame.width, frameHeight = frame.height)
                }
            }
            return false
        }

        // Stabilize the raw detection so the overlay doesn't flicker and auto-capture
        // isn't reset by single-frame jitter.
        val quad = stabilizer.update(detector.detect(frame), now)
        if (quad != null) lastQuadSeenAt = now
        if (lastQuadSeenAt == 0L) lastQuadSeenAt = now
        val auto = _ui.value.autoCapture && mode.autoCapturable
        val shouldCapture = machine.onDetection(quad, now, auto)
        _ui.update {
            it.copy(
                liveQuad = quad,
                frameWidth = frame.width,
                frameHeight = frame.height,
                noDocumentHint = quad == null && now - lastQuadSeenAt > 3000,
                state = machine.state,
            )
        }
        return shouldCapture
    }

    /** Full-resolution capture, routed per mode. */
    fun onCaptured(fullRes: Bitmap) {
        viewModelScope.launch {
            when (_ui.value.mode) {
                CaptureMode.ID_CARD -> onIdCardShot(fullRes)
                CaptureMode.BOOK -> onBookShot(fullRes)
                CaptureMode.WHITEBOARD -> onWhiteboardShot(fullRes)
                CaptureMode.BUSINESS_CARD -> onBusinessCardShot(fullRes)
                CaptureMode.QR -> fullRes.recycle()
                CaptureMode.DOCUMENT -> onDocumentShot(fullRes)
            }
        }
    }

    private suspend fun onDocumentShot(fullRes: Bitmap) {
        // Re-detect on the full-resolution image for an accurate crop, biased toward
        // the stabilized quad the user was just shown so overlay and crop agree.
        // If full-res detection fails outright, the shown quad (scaled up) is still
        // a far better crop than the whole frame.
        val prior = capturePrior(fullRes.width, fullRes.height)
        val quad = withContext(Dispatchers.Default) {
            detector.detect(fullRes, prior)
                ?: prior
                ?: DocumentQuad.full(fullRes.width, fullRes.height)
        }

        val retakeId = _ui.value.retakePageId
        if (retakeId != null) {
            val docId = repository.replacePage(retakeId, fullRes, quad)
            fullRes.recycle()
            machine.afterCapture()
            _ui.update { it.copy(finishedDocumentId = docId ?: it.documentId) }
            return
        }

        val docId = ensureDocument("Scan")
        val pageId = repository.addPage(docId, fullRes, quad, Filter.COLOR)
        fullRes.recycle()
        afterPagesAdded(docId, added = 1, thumbOf = pageId)
    }

    /** Book spread: warp the whole spread, then split it at the spine into two pages. */
    private suspend fun onBookShot(fullRes: Bitmap) {
        val prior = capturePrior(fullRes.width, fullRes.height)
        val halves: List<Bitmap> = withContext(Dispatchers.Default) {
            val quad = detector.detect(fullRes, prior)
                ?: prior
                ?: DocumentQuad.full(fullRes.width, fullRes.height)
            val spread = ImageProcessing.warp(fullRes, quad).also { fullRes.recycle() }
            val split = BookSplitter.split(spread)
            if (split == null) {
                listOf(spread) // not spread-shaped — keep as a single page
            } else {
                spread.recycle()
                listOf(split.first, split.second)
            }
        }
        val docId = ensureDocument("Book")
        var lastId = 0L
        for (half in halves) {
            lastId = repository.addProcessedPage(docId, half, Filter.COLOR)
            half.recycle()
        }
        afterPagesAdded(docId, added = halves.size, thumbOf = lastId)
    }

    /** Whiteboard: the WHOLE frame is the content — no crop, dedicated filter. */
    private suspend fun onWhiteboardShot(fullRes: Bitmap) {
        val docId = ensureDocument("Whiteboard")
        val pageId = withContext(Dispatchers.Default) {
            repository.addProcessedPage(docId, fullRes, Filter.WHITEBOARD)
        }
        fullRes.recycle()
        afterPagesAdded(docId, added = 1, thumbOf = pageId)
    }

    /** Business card: single shot, OCR queued immediately for contact extraction. */
    private suspend fun onBusinessCardShot(fullRes: Bitmap) {
        val prior = capturePrior(fullRes.width, fullRes.height)
        val quad = withContext(Dispatchers.Default) {
            detector.detect(fullRes, prior)
                ?: prior
                ?: DocumentQuad.full(fullRes.width, fullRes.height)
        }
        val docId = ensureDocument("BusinessCard")
        repository.addPage(docId, fullRes, quad, Filter.COLOR)
        fullRes.recycle()
        runOcr(docId)
        machine.afterCapture()
        // Cards are one-shot: go straight to review, where "Add to contacts" lives.
        _ui.update {
            it.copy(documentId = docId, pageCount = it.pageCount + 1, finishedDocumentId = docId)
        }
    }

    /** ID-card flow: first shot = front, second = back, composed onto one page. */
    private suspend fun onIdCardShot(fullRes: Bitmap) {
        val prior = capturePrior(fullRes.width, fullRes.height)
        val card = withContext(Dispatchers.Default) {
            val quad = detector.detect(fullRes, prior)
                ?: prior
                ?: DocumentQuad.full(fullRes.width, fullRes.height)
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
        val docId = ensureDocument("IDCard")
        repository.addPage(docId, composed, DocumentQuad.full(composed.width, composed.height), Filter.COLOR)
        composed.recycle()
        _ui.update {
            it.copy(documentId = docId, pageCount = it.pageCount + 1, idFrontCaptured = false)
        }
        finish()
    }

    private suspend fun ensureDocument(type: String): Long =
        _ui.value.documentId ?: withContext(Dispatchers.IO) {
            repository.createDocument(defaultName(type))
        }

    /** Shared post-capture bookkeeping for continuous ("keep scanning") modes. */
    private suspend fun afterPagesAdded(docId: Long, added: Int, thumbOf: Long) {
        val thumb = repository.getPage(thumbOf)?.imagePath
        machine.afterCapture()
        _ui.update {
            it.copy(
                documentId = docId,
                pageCount = it.pageCount + added,
                lastPageThumb = thumb ?: it.lastPageThumb,
                state = machine.state,
            )
        }
    }

    /**
     * The stabilized live-overlay quad, mapped from analyzer-frame to capture-image
     * coordinates. Both streams are 4:3 and display-oriented, so the mapping is a
     * uniform scale; if their aspects somehow disagree the quad can't be mapped and
     * must not bias full-resolution detection.
     */
    private fun capturePrior(fullW: Int, fullH: Int): DocumentQuad? {
        val ui = _ui.value
        val quad = ui.liveQuad ?: return null
        if (ui.frameWidth <= 0 || ui.frameHeight <= 0) return null
        val sx = fullW.toFloat() / ui.frameWidth
        val sy = fullH.toFloat() / ui.frameHeight
        if (abs(sx / sy - 1f) > 0.05f) return null
        return QuadGeometry.scale(quad, sx, sy)
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
