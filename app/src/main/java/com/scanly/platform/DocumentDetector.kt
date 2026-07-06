package com.scanly.platform

import android.graphics.Bitmap

/** A plain 2D point in image-pixel space. Deliberately NOT android.graphics.PointF so the
 *  quad model (and the capture state machine that uses it) stays free of Android types and
 *  is testable on the plain JVM. */
data class QuadPoint(val x: Float, val y: Float)

/**
 * A detected document boundary, as four corners in source-image pixel coordinates,
 * ordered top-left, top-right, bottom-right, bottom-left.
 */
data class DocumentQuad(
    val topLeft: QuadPoint,
    val topRight: QuadPoint,
    val bottomRight: QuadPoint,
    val bottomLeft: QuadPoint,
) {
    val corners: List<QuadPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Compact "x,y,x,y,x,y,x,y" form for persistence (see PageEntity.cropQuad). */
    fun serialize(): String = corners.joinToString(",") { "${it.x},${it.y}" }

    companion object {
        /** Full-frame fallback when nothing is detected. */
        fun full(width: Int, height: Int) = DocumentQuad(
            QuadPoint(0f, 0f),
            QuadPoint(width.toFloat(), 0f),
            QuadPoint(width.toFloat(), height.toFloat()),
            QuadPoint(0f, height.toFloat()),
        )

        /** Inverse of [serialize]; null on malformed input. */
        fun deserialize(s: String): DocumentQuad? = runCatching {
            val v = s.split(',').map { it.toFloat() }
            require(v.size == 8) { "expected 8 floats" }
            DocumentQuad(
                QuadPoint(v[0], v[1]),
                QuadPoint(v[2], v[3]),
                QuadPoint(v[4], v[5]),
                QuadPoint(v[6], v[7]),
            )
        }.getOrNull()
    }
}

/**
 * Finds the page boundary in a frame. Implemented per flavor:
 *  - foss  -> OpenCV contour detection ([com.scanly.cv.OpenCvDocumentDetector])
 *  - gplay -> shared OpenCV detector for the live overlay too (ML Kit's scanner is a
 *    separate full-screen flow, not a per-frame detector).
 *
 * Both run 100% on-device. Used for the live preview overlay and on capture.
 */
interface DocumentDetector {
    /**
     * Detect the page boundary in [frame] — a live preview frame or a full-resolution
     * capture (implementations normalize the working size internally).
     *
     * @param prior a quad the caller already trusts, in [frame] pixel coordinates —
     *   e.g. the stabilized live-overlay quad at capture time. Candidates near it get
     *   a score boost so the final crop matches what the user was shown.
     */
    fun detect(frame: Bitmap, prior: DocumentQuad? = null): DocumentQuad?
}
