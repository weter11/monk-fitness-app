package com.monkfitness.app

/**
 * Spike-only stand-in for the application module's generated `BuildConfig`.
 *
 * The engine sources are compiled into this module verbatim (see `build.gradle.kts`); two of them
 * read `BuildConfig.DEBUG` for debug-only invariants. The spike never ships, so `DEBUG` is
 * constant true — the same value the engine sees in the app's debug build and in its unit tests.
 */
object BuildConfig {
    const val DEBUG: Boolean = true
}
