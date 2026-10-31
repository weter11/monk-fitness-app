package com.monkfitness.app.validation

/**
 * The settings contract for the subsystem: completely hidden until [DEFAULT_ENABLED] is overridden by the
 * user through the developer setting.
 */
object ValidationSettings {
    const val DEFAULT_ENABLED = false
    const val SETTING_KEY = "show_engineering_validation"
}
