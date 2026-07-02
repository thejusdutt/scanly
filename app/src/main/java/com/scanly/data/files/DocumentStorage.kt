package com.scanly.data.files

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device storage for scanned page images and generated exports.
 *
 * Everything lives under the app's private files dir (documents/, exports/). Nothing is
 * world-readable; sharing happens via FileProvider + the system share sheet, so the user
 * explicitly chooses where anything goes. This is the "clear, single storage location"
 * the research flagged as a pain point.
 */
@Singleton
class DocumentStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
    val exportsDir: File = File(context.filesDir, "exports").apply { mkdirs() }

    private fun docDir(documentId: Long) = File(docsDir, documentId.toString()).apply { mkdirs() }

    /** Persist a processed page bitmap as JPEG; returns the absolute path. */
    fun savePageImage(documentId: Long, bitmap: Bitmap, quality: Int = 90): String {
        val file = File(docDir(documentId), "page_${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file.absolutePath
    }

    /** Persist the original capture (for later re-cropping). */
    fun saveOriginal(documentId: Long, bitmap: Bitmap): String {
        val file = File(docDir(documentId), "orig_${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file.absolutePath
    }

    fun exportFile(name: String): File = File(exportsDir, name)

    /** Saved signatures (transparent PNGs), newest first. */
    val signaturesDir: File get() = File(context.filesDir, "signatures").apply { mkdirs() }

    fun latestSignature(): File? =
        signaturesDir.listFiles { f -> f.extension == "png" }?.maxByOrNull { it.lastModified() }

    fun deletePage(path: String) { runCatching { File(path).delete() } }

    fun deleteDocument(documentId: Long) { runCatching { docDir(documentId).deleteRecursively() } }
}
