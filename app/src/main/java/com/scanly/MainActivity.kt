package com.scanly

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.scanly.data.prefs.SecurityPrefs
import com.scanly.ui.ScanlyApp as ScanlyUi
import com.scanly.ui.security.BiometricUnlock
import com.scanly.ui.security.LockGate
import com.scanly.ui.theme.ScanlyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FragmentActivity (not ComponentActivity) because BiometricPrompt requires it for the
 * app-lock and per-document unlock flows.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var securityPrefs: SecurityPrefs

    private val locked = mutableStateOf(false)
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked.value = securityPrefs.appLockEnabled.value
        setContent {
            ScanlyTheme {
                val isLocked by locked
                if (isLocked) {
                    LockGate(title = "Scanly is locked", onUnlockRequest = ::unlock)
                } else {
                    ScanlyUi()
                }
            }
        }
        if (locked.value) unlock()
    }

    override fun onStart() {
        super.onStart()
        // Re-lock after the app has been in the background for a while — not on every
        // switch, so quick share-sheet round-trips don't nag for credentials.
        if (securityPrefs.appLockEnabled.value && backgroundedAt > 0 &&
            SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS
        ) {
            locked.value = true
            unlock()
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    private fun unlock() {
        BiometricUnlock.prompt(this, title = "Unlock Scanly") {
            locked.value = false
        }
    }

    private companion object {
        const val RELOCK_AFTER_MS = 30_000L
    }
}
