package com.scanly.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera/scanning preferences, persisted so the capture screen opens the way the user
 * left it. Plain SharedPreferences — nothing sensitive here.
 */
@Singleton
class CapturePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("scanly_capture", Context.MODE_PRIVATE)

    /**
     * Adobe-style confirm step: pause after every shot so the user can KEEP or RETAKE
     * it before anything is saved. On by default — surprise auto-captures were the #1
     * complaint about the old flow.
     */
    private val _reviewEachScan = MutableStateFlow(prefs.getBoolean(KEY_REVIEW, true))
    val reviewEachScan = _reviewEachScan.asStateFlow()

    private val _autoCapture = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val autoCapture = _autoCapture.asStateFlow()

    /** Raw ImageCapture.FLASH_MODE_* int (AUTO=0, ON=1, OFF=2); defaults to OFF. */
    private val _flashMode = MutableStateFlow(prefs.getInt(KEY_FLASH, 2))
    val flashMode = _flashMode.asStateFlow()

    private val _showGrid = MutableStateFlow(prefs.getBoolean(KEY_GRID, false))
    val showGrid = _showGrid.asStateFlow()

    fun setReviewEachScan(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REVIEW, enabled).apply()
        _reviewEachScan.value = enabled
    }

    fun setAutoCapture(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
        _autoCapture.value = enabled
    }

    fun setFlashMode(mode: Int) {
        prefs.edit().putInt(KEY_FLASH, mode).apply()
        _flashMode.value = mode
    }

    fun setShowGrid(shown: Boolean) {
        prefs.edit().putBoolean(KEY_GRID, shown).apply()
        _showGrid.value = shown
    }

    private companion object {
        const val KEY_REVIEW = "review_each_scan"
        const val KEY_AUTO = "auto_capture"
        const val KEY_FLASH = "flash_mode"
        const val KEY_GRID = "show_grid"
    }
}
