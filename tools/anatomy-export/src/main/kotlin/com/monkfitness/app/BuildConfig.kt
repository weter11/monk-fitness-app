package com.monkfitness.app

/**
 * The exporter compiles the pure-Kotlin packages out of the app module, where `BuildConfig` is
 * generated. This stands in for it: nothing the exporter compiles reads a build flag except the
 * validation-stamp trace, which is off in an export.
 */
object BuildConfig {
    const val DEBUG = false
    const val FILAMENT_CHARACTER_ENABLED = false
}
