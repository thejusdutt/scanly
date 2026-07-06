package com.scanly.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A document with its ordered pages. */
data class DocumentWithPages(
    @androidx.room.Embedded val document: DocumentEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "documentId")
    val pages: List<PageEntity>,
)

/** Lightweight row for the library grid: name, count, first-page thumbnail, OCR flag. */
data class DocumentSummary(
    val id: Long,
    val name: String,
    val folder: String?,
    val tags: String?,
    val locked: Boolean,
    val updatedAt: Long,
    val pageCount: Int,
    val thumbnailPath: String?,
    val hasText: Boolean,
)

@Dao
interface ScanlyDao {

    // --- documents ---
    @Insert
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Query("UPDATE documents SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun renameDocument(id: Long, name: String, now: Long)

    @Query("UPDATE documents SET updatedAt = :now WHERE id = :id")
    suspend fun touchDocument(id: Long, now: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT d.id AS id, d.name AS name, d.folder AS folder, d.tags AS tags,
            d.locked AS locked, d.updatedAt AS updatedAt,
            (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
            (SELECT p.imagePath FROM pages p WHERE p.documentId = d.id
                ORDER BY p.orderIndex LIMIT 1) AS thumbnailPath,
            (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id
                AND p.ocrStatus = 'DONE') > 0 AS hasText
        FROM documents d
        ORDER BY d.updatedAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<DocumentSummary>>

    @Query("UPDATE documents SET tags = :tags WHERE id = :id")
    suspend fun setTags(id: Long, tags: String?)

    @Query("UPDATE documents SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("SELECT tags FROM documents WHERE tags IS NOT NULL AND tags != ''")
    fun observeTagCsvs(): Flow<List<String>>

    @Query("SELECT DISTINCT folder FROM documents WHERE folder IS NOT NULL AND folder != '' ORDER BY folder")
    fun observeFolders(): Flow<List<String>>

    @Query("UPDATE documents SET folder = :folder WHERE id = :id")
    suspend fun setFolder(id: Long, folder: String?)

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocumentWithPages(id: Long): Flow<DocumentWithPages?>

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocumentWithPages(id: Long): DocumentWithPages?

    // --- pages ---
    @Insert
    suspend fun insertPage(page: PageEntity): Long

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: Long)

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getPage(id: Long): PageEntity?

    @Query("SELECT COALESCE(MAX(orderIndex), -1) + 1 FROM pages WHERE documentId = :docId")
    suspend fun nextOrderIndex(docId: Long): Int

    @Query("UPDATE pages SET ocrStatus = :status WHERE id = :pageId")
    suspend fun setOcrStatus(pageId: Long, status: OcrStatus)

    @Query("UPDATE pages SET ocrStatus = :status, ocrText = :text WHERE id = :pageId")
    suspend fun setOcrResult(pageId: Long, status: OcrStatus, text: String?)

    // --- search (FTS over OCR text) ---
    @Transaction
    @Query(
        """
        SELECT DISTINCT d.* FROM documents d
        JOIN pages p ON p.documentId = d.id
        JOIN pages_fts fts ON fts.rowid = p.id
        WHERE pages_fts MATCH :query
        ORDER BY d.updatedAt DESC
        """,
    )
    fun searchDocuments(query: String): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT DISTINCT d.id AS id, d.name AS name, d.folder AS folder, d.tags AS tags,
            d.locked AS locked, d.updatedAt AS updatedAt,
            (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
            (SELECT p.imagePath FROM pages p WHERE p.documentId = d.id
                ORDER BY p.orderIndex LIMIT 1) AS thumbnailPath,
            1 AS hasText
        FROM documents d
        JOIN pages p ON p.documentId = d.id
        JOIN pages_fts fts ON fts.rowid = p.id
        WHERE pages_fts MATCH :query
        ORDER BY d.updatedAt DESC
        """,
    )
    fun searchSummaries(query: String): Flow<List<DocumentSummary>>
}
