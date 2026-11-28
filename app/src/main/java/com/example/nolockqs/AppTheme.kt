package com.example.nolockqs

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit

/**
 * The app's theme: the same as the device (the default, following its dark theme as it changes),
 * always light, or always dark. Android itself keeps the choice for the app, so the launch screen
 * already shows it; NoLockQS also keeps its own copy to show the selection and to set it again if
 * the two ever differ.
 */
object AppTheme {

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val FILE = "app_settings"
    private const val KEY_THEME = "theme"

    fun get(context: Context): String =
        when (val theme = prefs(context).getString(KEY_THEME, SYSTEM)) {
            LIGHT, DARK -> theme
            else -> SYSTEM
        }

    /** Saves [theme] and applies it at once: open screens redraw in it. */
    fun set(context: Context, theme: String) {
        prefs(context).edit { putString(KEY_THEME, theme) }
        apply(context, theme)
    }

    /** Hands the saved theme (or [theme]) to Android, which redraws only if it changed. */
    fun apply(context: Context, theme: String = get(context)) {
        val mode = when (theme) {
            LIGHT -> UiModeManager.MODE_NIGHT_NO
            DARK -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        runCatching { context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
