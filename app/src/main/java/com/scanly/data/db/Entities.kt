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
