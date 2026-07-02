package com.scanly.cv

import android.util.Log
import org.opencv.android.OpenCVLoader
import java.util.concurrent.atomic.AtomicBoolean

/** Loads the bundled OpenCV native libs exactly once, lazily, off the cold-start path. */
object OpenCvInitializer {
    private val initialized = AtomicBoolean(false)

    fun ensure(): Boolean {
        if (initialized.get()) return true
        val ok = OpenCVLoader.initLocal()
        if (ok) initialized.set(true) else Log.e("Scanly", "OpenCV failed to load")
        return ok
    }
}
