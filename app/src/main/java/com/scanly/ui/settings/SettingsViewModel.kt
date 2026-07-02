package com.scanly.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.scanly.platform.TipJar
import com.scanly.platform.TipState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tipJar: TipJar,
) : ViewModel() {
    val tipState: Flow<TipState> = tipJar.state
    fun tip(activity: Activity) = tipJar.launchTip(activity)
}
