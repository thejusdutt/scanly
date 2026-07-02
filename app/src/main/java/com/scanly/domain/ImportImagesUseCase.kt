package com.scanly.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.scanly.common.RenamePattern
import com.scanly.data.repo.DocumentRepository
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Imports existing photos (Photo Picker URIs) as document pages: decode → detect the page
 * boundary → warp + filter via the normal pipeline. All pages land in one new document.
 * Runs entirely on-device; the Photo Picker needs no storage permission.
 */
class ImportImagesUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detector: DocumentDetector,
    private val repository: DocumentRepository,
) {
    /** @return the new document id, or null if nothing could be decoded. */
    suspend operator fun invoke(uris: List<Uri>): Long? = withContext(Dispatchers.Default) {
        var docId: Long? = null
        for (uri in uris) {
            val bmp = decode(uri) ?: continue
            val quad = detector.detect(bmp) ?: DocumentQuad.full(bmp.width, bmp.height)
            val id = docId ?: repository.createDocument(
                RenamePattern.format(RenamePattern.DEFAULT, type = "Import"),
            ).also { docId = it }
            repository.addPage(id, bmp, quad)
            bmp.recycle()
        }
        docId
    }

    /** Decode with subsampling so huge camera-roll photos don't blow the heap. */
    private fun decode(uri: Uri, maxDim: Int = 3000): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()
}
