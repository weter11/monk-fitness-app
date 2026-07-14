package com.monkfitness.app.validation

/**
 * Holds the settings contract for the Engineering Validation subsystem.
 *
 * The subsystem is completely hidden until [DEFAULT_ENABLED] is overridden by the user
 * via the developer setting exposed in [com.monkfitness.app.ui.screens.SettingsScreen].
 */
object ValidationSettings {
    const val DEFAULT_ENABLED = false
    const val SETTING_KEY = "show_engineering_validation"
}
