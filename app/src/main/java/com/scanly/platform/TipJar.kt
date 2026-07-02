package com.scanly.platform

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Optional, one-time "tip". NEVER a subscription and NEVER gates a feature. */
sealed interface TipState {
    data object Unavailable : TipState        // foss build, or billing not ready
    data class Available(val price: String) : TipState
    data object Tipped : TipState
}

/**
 * Implemented per flavor:
 *  - foss  -> [com.scanly.foss.NoOpTipJar]   (no billing, returns Unavailable)
 *  - gplay -> [com.scanly.gplay.BillingTipJar] (Play Billing one-time product)
 */
interface TipJar {
    val state: Flow<TipState>
    fun launchTip(activity: Activity)

    companion object {
        val NONE = object : TipJar {
            override val state: Flow<TipState> = flowOf(TipState.Unavailable)
            override fun launchTip(activity: Activity) = Unit
        }
    }
}
