package com.scanly.data.prefs

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Security settings + secrets, stored in EncryptedSharedPreferences backed by an
 * Android-Keystore master key. Holds the app-lock toggle and the random SQLCipher
 * database passphrase — the passphrase never leaves the device and is never derived
 * from anything the user types.
 */
@Singleton
class SecurityPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "scanly_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK, false))
    val appLockEnabled = _appLockEnabled.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        _appLockEnabled.value = enabled
    }

    /**
     * The SQLCipher passphrase as bytes, generated once (256-bit random, stored
     * base64). Subsequent calls always return the identical bytes.
     */
    fun dbPassphrase(): ByteArray {
        val existing = prefs.getString(KEY_DB_PASS, null)
        if (existing != null) return existing.toByteArray(Charsets.US_ASCII)
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(fresh, Base64.NO_WRAP)
        prefs.edit().putString(KEY_DB_PASS, encoded).apply()
        return encoded.toByteArray(Charsets.US_ASCII)
    }

    private companion object {
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val KEY_DB_PASS = "db_passphrase"
    }
}
