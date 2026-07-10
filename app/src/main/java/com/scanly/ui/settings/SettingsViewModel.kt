package com.scanly.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.scanly.data.prefs.AppearancePrefs
import com.scanly.data.prefs.CapturePrefs
import com.scanly.data.prefs.SecurityPrefs
import com.scanly.data.prefs.ThemeMode
import com.scanly.platform.TipJar
import com.scanly.platform.TipState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tipJar: TipJar,
    private val securityPrefs: SecurityPrefs,
    private val appearancePrefs: AppearancePrefs,
    private val capturePrefs: CapturePrefs,
) : ViewModel() {
    val tipState: Flow<TipState> = tipJar.state
    fun tip(activity: Activity) = tipJar.launchTip(activity)

    val appLockEnabled: StateFlow<Boolean> = securityPrefs.appLockEnabled
    fun setAppLock(enabled: Boolean) = securityPrefs.setAppLockEnabled(enabled)

    val themeMode: StateFlow<ThemeMode> = appearancePrefs.themeMode
    fun setThemeMode(mode: ThemeMode) = appearancePrefs.setThemeMode(mode)

    val dynamicColor: StateFlow<Boolean> = appearancePrefs.dynamicColor
    fun setDynamicColor(enabled: Boolean) = appearancePrefs.setDynamicColor(enabled)

    val reviewEachScan: StateFlow<Boolean> = capturePrefs.reviewEachScan
    fun setReviewEachScan(enabled: Boolean) = capturePrefs.setReviewEachScan(enabled)

    val autoCapture: StateFlow<Boolean> = capturePrefs.autoCapture
    fun setAutoCapture(enabled: Boolean) = capturePrefs.setAutoCapture(enabled)
}
