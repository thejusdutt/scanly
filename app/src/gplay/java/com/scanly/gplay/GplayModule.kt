package com.scanly.gplay

import com.scanly.cv.OpenCvDocumentDetector
import com.scanly.platform.DocumentDetector
import com.scanly.platform.TextRecognizer
import com.scanly.platform.TipJar
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the gplay flavor: shared OpenCV live-overlay detector, ML Kit OCR,
 * and the optional Play-Billing tip jar. This module exists ONLY in src/gplay.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GplayModule {

    @Binds
    @Singleton
    abstract fun detector(impl: OpenCvDocumentDetector): DocumentDetector

    @Binds
    @Singleton
    abstract fun recognizer(impl: MlKitTextRecognizer): TextRecognizer

    @Binds
    @Singleton
    abstract fun tipJar(impl: BillingTipJar): TipJar
}
