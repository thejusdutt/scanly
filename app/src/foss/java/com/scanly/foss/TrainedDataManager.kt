package com.scanly.foss

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Tesseract trained-data files under <files>/tessdata/<lang>.traineddata.
 *
 * English ("eng") is bundled as an asset and copied out on first use, so the FOSS build
 * does OCR with zero network. Additional languages are added only by explicit user action
 * (and in the foss build there is no INTERNET permission, so they must be imported via
 * the Storage Access Framework rather than downloaded).
 */
@Singleton
class TrainedDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Tesseract expects the PARENT of the `tessdata` directory as its data path. */
    val dataPath: File get() = context.filesDir

    private val tessDir: File get() = File(context.filesDir, "tessdata").apply { mkdirs() }

    fun isAvailable(lang: String): Boolean = File(tessDir, "$lang.traineddata").exists()

    /**
     * Copy a bundled asset language pack out to internal storage. Re-copies when the
     * bundled asset's size differs from the extracted file, so upgrading the app (e.g.
     * tessdata_fast → tessdata_best) refreshes stale models on existing installs.
     */
    fun ensureBundled(lang: String = "eng") {
        val assetName = "tessdata/$lang.traineddata"
        val target = File(tessDir, "$lang.traineddata")
        runCatching {
            val assetSize = context.assets.openFd(assetName).use { it.length }
            if (target.exists() && target.length() == assetSize) return
            context.assets.open(assetName).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onFailure {
            // Asset may be compressed (openFd fails) — fall back to copy-if-missing.
            if (!target.exists()) runCatching {
                context.assets.open(assetName).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    /** Import a user-picked .traineddata (SAF) — the offline way to add languages. */
    fun importLanguage(lang: String, bytes: ByteArray) {
        File(tessDir, "$lang.traineddata").writeBytes(bytes)
    }
}
