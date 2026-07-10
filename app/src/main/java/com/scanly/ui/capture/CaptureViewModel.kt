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
import com.scanly.data.prefs.CapturePrefs
import com.scanly.data.repo.DocumentRepository
import com.scanly.domain.RunOcrUseCase
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import com.scanly.qr.QrDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** 0..1 countdown toward auto-capture while a page is held steady (ring UI). */
    val holdProgress: Float = 0f,
    /** Downscaled look at a shot awaiting the user's KEEP/RETAKE verdict. */
    val pendingPreview: Bitmap? = null,
    /** True while a shot is being detected/saved — blocks the shutter and auto-fire. */
    val processing: Boolean = false,
    /** Bumped whenever a shot fails to process; the screen surfaces it transiently. */
    val errorCount: Int = 0,
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
    private val capturePrefs: CapturePrefs,
) : ViewModel() {

    private val machine = CaptureStateMachine()
    private val stabilizer = QuadStabilizer()
    private val _ui = MutableStateFlow(CaptureUiState(autoCapture = capturePrefs.autoCapture.value))
    val ui = _ui.asStateFlow()

    val flashMode: StateFlow<Int> = capturePrefs.flashMode
    val showGrid: StateFlow<Boolean> = capturePrefs.showGrid

    private var idFront: Bitmap? = null

    /** Full-resolution shot + its crop, held un-saved until the user keeps it. */
    private class PendingShot(val fullRes: Bitmap, val quad: DocumentQuad, val mode: CaptureMode)
    private var pending: PendingShot? = null

    /** Set iff THIS session created the document — an X→Discard then deletes it whole. */
    private var createdDocId: Long? = null

    /** Pages added this session; when appending, Discard removes only these. */
    private val sessionPageIds = mutableListOf<Long>()

    fun init(appendToDocumentId: Long?, retakePageId: Long? = null) {
        _ui.update {
            it.copy(
                documentId = it.documentId ?: appendToDocumentId,
                retakePageId = it.retakePageId ?: retakePageId,
            )
        }
    }

    fun toggleAuto() {
        val next = !_ui.value.autoCapture
        capturePrefs.setAutoCapture(next)
        _ui.update { it.copy(autoCapture = next) }
    }

    fun setFlashMode(mode: Int) = capturePrefs.setFlashMode(mode)
    fun toggleGrid() = capturePrefs.setShowGrid(!capturePrefs.showGrid.value)

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
        val current = _ui.value
        // A shot is mid-processing or awaiting the user's verdict — freeze detection so
        // auto-capture can't fire a second shot underneath the confirm overlay.
        if (current.processing || current.pendingPreview != null) return false
        val mode = current.mode

        if (mode == CaptureMode.QR) {
            // Decode-only: pause while a result sheet is showing.
            if (current.qrResult == null) {
                qrDecoder.decode(frame)?.let { text ->
                    _ui.update { it.copy(qrResult = text) }
                }
            }
            return false
        }
        if (mode == CaptureMode.WHITEBOARD) {
            // Full-frame capture: no boundary, no overlay, manual shutter only.
            if (current.liveQuad != null || current.frameWidth != frame.width) {
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
        val auto = current.autoCapture && mode.autoCapturable
        val shouldCapture = machine.onDetection(quad, now, auto)
        _ui.update {
            it.copy(
                liveQuad = quad,
                frameWidth = frame.width,
                frameHeight = frame.height,
                noDocumentHint = quad == null && now - lastQuadSeenAt > 3000,
                state = machine.state,
                holdProgress = if (auto) machine.holdProgress(now) else 0f,
            )
        }
        return shouldCapture
    }

    /**
     * Full-resolution capture. The crop quad is detected ONCE here — whatever preview
     * the user confirms is exactly what gets saved (re-detecting at save time could
     * crop differently if the document moved while the confirm overlay was up).
     */
    fun onCaptured(fullRes: Bitmap) {
        val mode = _ui.value.mode
        if (mode == CaptureMode.QR || _ui.value.processing || pending != null) {
            fullRes.recycle()
            return
        }
        _ui.update { it.copy(processing = true) }
        viewModelScope.launch {
            try {
                val quad = withContext(Dispatchers.Default) {
                    if (mode.usesBoundaryDetection) {
                        // Bias toward the stabilized quad the user was just shown so
                        // overlay and crop agree; if full-res detection fails outright,
                        // the shown quad (scaled up) is still a far better crop than
                        // the whole frame.
                        val prior = capturePrior(fullRes.width, fullRes.height)
                        detector.detect(fullRes, prior)
                            ?: prior
                            ?: DocumentQuad.full(fullRes.width, fullRes.height)
                    } else {
                        DocumentQuad.full(fullRes.width, fullRes.height)
                    }
                }
                if (capturePrefs.reviewEachScan.value) {
                    val preview = withContext(Dispatchers.Default) {
                        buildPreview(fullRes, quad, mode)
                    }
                    pending = PendingShot(fullRes, quad, mode)
                    _ui.update { it.copy(pendingPreview = preview, processing = false) }
                } else {
                    save(fullRes, quad, mode)
                }
            } catch (t: Throwable) {
                fullRes.recycle()
                _ui.update { it.copy(processing = false, errorCount = it.errorCount + 1) }
            }
        }
    }

    /** User confirmed the pending shot — commit it through the per-mode save path. */
    fun keepPendingShot() {
        val p = pending ?: return
        pending = null
        _ui.update { it.copy(pendingPreview = null, processing = true) }
        viewModelScope.launch {
            try {
                save(p.fullRes, p.quad, p.mode)
            } catch (t: Throwable) {
                p.fullRes.recycle()
                _ui.update { it.copy(processing = false, errorCount = it.errorCount + 1) }
            }
        }
    }

    /** User rejected the pending shot — discard it and re-arm for the same placement. */
    fun retakePendingShot() {
        val p = pending ?: return
        pending = null
        p.fullRes.recycle()
        machine.rearm()
        // The preview bitmap may still be mid-draw in the closing overlay; drop the
        // reference and let GC reclaim it rather than recycling under the renderer.
        _ui.update { it.copy(pendingPreview = null, state = machine.state) }
    }

    private suspend fun save(fullRes: Bitmap, quad: DocumentQuad, mode: CaptureMode) {
        try {
            when (mode) {
                CaptureMode.ID_CARD -> onIdCardShot(fullRes, quad)
                CaptureMode.BOOK -> onBookShot(fullRes, quad)
                CaptureMode.WHITEBOARD -> onWhiteboardShot(fullRes)
                CaptureMode.BUSINESS_CARD -> onBusinessCardShot(fullRes, quad)
                CaptureMode.QR -> fullRes.recycle()
                CaptureMode.DOCUMENT -> onDocumentShot(fullRes, quad)
            }
        } finally {
            _ui.update { it.copy(processing = false) }
        }
    }

    /**
     * What the saved page will look like (warped for boundary modes), downscaled for
     * the confirm overlay. Always a NEW bitmap — never an alias of [fullRes] — so the
     * pending shot and its preview can be released independently.
     */
    private fun buildPreview(fullRes: Bitmap, quad: DocumentQuad, mode: CaptureMode): Bitmap {
        val flat = if (mode == CaptureMode.WHITEBOARD) fullRes else ImageProcessing.warp(fullRes, quad)
        val maxSide = maxOf(flat.width, flat.height)
        val scale = PREVIEW_MAX_SIDE.toFloat() / maxSide
        val preview = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                flat,
                (flat.width * scale).toInt().coerceAtLeast(1),
                (flat.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            flat.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (flat !== fullRes && flat !== preview) flat.recycle()
        return preview
    }

    private suspend fun onDocumentShot(fullRes: Bitmap, quad: DocumentQuad) {
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
        sessionPageIds += pageId
        afterPagesAdded(docId, added = 1, thumbOf = pageId)
    }

    /** Book spread: warp the whole spread, then split it at the spine into two pages. */
    private suspend fun onBookShot(fullRes: Bitmap, quad: DocumentQuad) {
        val halves: List<Bitmap> = withContext(Dispatchers.Default) {
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
            sessionPageIds += lastId
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
        sessionPageIds += pageId
        afterPagesAdded(docId, added = 1, thumbOf = pageId)
    }

    /** Business card: single shot, OCR queued immediately for contact extraction. */
    private suspend fun onBusinessCardShot(fullRes: Bitmap, quad: DocumentQuad) {
        val docId = ensureDocument("BusinessCard")
        val pageId = repository.addPage(docId, fullRes, quad, Filter.COLOR)
        fullRes.recycle()
        sessionPageIds += pageId
        runOcr(docId)
        machine.afterCapture()
        // Cards are one-shot: go straight to review, where "Add to contacts" lives.
        _ui.update {
            it.copy(documentId = docId, pageCount = it.pageCount + 1, finishedDocumentId = docId)
        }
    }

    /** ID-card flow: first shot = front, second = back, composed onto one page. */
    private suspend fun onIdCardShot(fullRes: Bitmap, quad: DocumentQuad) {
        val card = withContext(Dispatchers.Default) {
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
        val pageId = repository.addPage(
            docId, composed, DocumentQuad.full(composed.width, composed.height), Filter.COLOR,
        )
        composed.recycle()
        sessionPageIds += pageId
        _ui.update {
            it.copy(documentId = docId, pageCount = it.pageCount + 1, idFrontCaptured = false)
        }
        finish()
    }

    private suspend fun ensureDocument(type: String): Long =
        _ui.value.documentId ?: withContext(Dispatchers.IO) {
            repository.createDocument(defaultName(type)).also { createdDocId = it }
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

    /**
     * Delete everything THIS session produced (X → Discard): the whole document if we
     * created it, otherwise just the pages appended to an existing one.
     */
    fun discardSession(onDone: () -> Unit) {
        pending?.fullRes?.recycle(); pending = null
        viewModelScope.launch {
            val created = createdDocId
            if (created != null) {
                repository.deleteDocument(created)
            } else {
                sessionPageIds.forEach { repository.deletePage(it) }
            }
            onDone()
        }
    }

    override fun onCleared() {
        idFront?.recycle(); idFront = null
        pending?.fullRes?.recycle(); pending = null
    }

    private fun defaultName(type: String): String =
        RenamePattern.format(RenamePattern.DEFAULT, type = type)

    companion object {
        /** Long-side cap for the confirm-overlay preview; full-res stays untouched. */
        private const val PREVIEW_MAX_SIDE = 1600

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
