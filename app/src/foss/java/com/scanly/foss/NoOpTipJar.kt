package com.scanly.foss

import android.app.Activity
import com.scanly.platform.TipJar
import com.scanly.platform.TipState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/** FOSS builds have no billing at all — fully free, no IAP. */
class NoOpTipJar @Inject constructor() : TipJar {
    override val state: Flow<TipState> = flowOf(TipState.Unavailable)
    override fun launchTip(activity: Activity) = Unit
}
