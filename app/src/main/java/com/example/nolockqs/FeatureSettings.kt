package com.example.nolockqs

/**
 * The on/off switches on the module screen. The app saves them in the Xposed framework's remote
 * preferences, and the hooks in System UI and System Framework read the same group. A switch that
 * was never set, or can't be read, counts as on, so a protection is never off by accident.
 */
object FeatureSettings {
    const val GROUP = "features"
    const val BLOCK_QUICK_SETTINGS = "block_quick_settings"
    const val BLOCK_POWER_MENU = "block_power_menu"
    const val DEFAULT_ON = true
}
