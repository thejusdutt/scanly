package com.scanly.foss

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
 * Hilt bindings for the FOSS flavor. This module exists ONLY in src/foss, so the
 * proprietary ML Kit / Billing code is never compiled or shipped here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FossModule {

    @Binds
    @Singleton
    abstract fun detector(impl: OpenCvDocumentDetector): DocumentDetector

    @Binds
    @Singleton
    abstract fun recognizer(impl: TesseractTextRecognizer): TextRecognizer

    @Binds
    @Singleton
    abstract fun tipJar(impl: NoOpTipJar): TipJar
}
