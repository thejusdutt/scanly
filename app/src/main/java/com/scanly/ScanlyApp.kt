package com.scanly

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Provides the Hilt-aware [HiltWorkerFactory] so OCR runs as injectable WorkManager jobs
 * (see [com.scanly.ocr.OcrWorker]). OpenCV is initialized lazily by the FOSS detector to
 * keep cold start fast.
 */
@HiltAndroidApp
class ScanlyApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
