package com.scanly.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Light/dark preference. SYSTEM follows the OS setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Appearance settings, exposed as StateFlows so the theme switches live without an
 * activity restart. Plain SharedPreferences — nothing sensitive here (secrets live in
 * SecurityPrefs).
 */
@Singleton
class AppearancePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("scanly_appearance", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME, null)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM,
    )
    val themeMode = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, true))
    val dynamicColor = _dynamicColor.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _dynamicColor.value = enabled
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
    }
}
