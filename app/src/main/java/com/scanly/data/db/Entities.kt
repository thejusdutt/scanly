package com.scanly.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.scanly.common.Filter

enum class OcrStatus { NONE, QUEUED, DONE, FAILED }

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val folder: String? = null,
    /** Comma-separated user tags (CamScanner-style labels), or null. */
    val tags: String? = null,
    /** Requires biometric/credential unlock to open (per-document lock). */
    val locked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "pages",
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val orderIndex: Int,
    /** Processed (warped + filtered) image, stored under app files. */
    val imagePath: String,
    /** Original capture, kept for re-cropping. */
    val originalPath: String?,
    /**
     * The crop applied to [originalPath], serialized via DocumentQuad.serialize().
     * Null when the original is already flat (book halves, whiteboard, imports) or for
     * rows predating this column. Filter changes re-warp with this quad, so switching
     * filters no longer discards the crop.
     */
    val cropQuad: String? = null,
    val filter: Filter = Filter.COLOR,
    val rotationDeg: Int = 0,
    val ocrStatus: OcrStatus = OcrStatus.NONE,
    /** Plain OCR text (also mirrored into the FTS table for search). */
    val ocrText: String? = null,
)

/** Full-text index over OCR'd text so the library can search by document content. */
@Fts4(contentEntity = PageEntity::class)
@Entity(tableName = "pages_fts")
data class PageFts(
    val ocrText: String?,
)
