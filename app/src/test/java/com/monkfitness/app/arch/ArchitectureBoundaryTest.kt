package com.monkfitness.app.arch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The motion core is pure Kotlin: `animation/` and `poses/` own the geometry, the poses and the
 * playback clock, so neither may reach for Compose, the Android framework or the UI package. The
 * dependency arrow points from `ui` into the core, and a reverse import is what silently turns a
 * testable core back into a view.
 */
class ArchitectureBoundaryTest {

    /** Imports the core may never carry, each with the reason it is barred. */
    private val forbidden = listOf(
        "androidx.compose." to "Compose",
        "android." to "the Android framework",
        "com.monkfitness.app.ui." to "the UI package",
        "com.monkfitness.app.render." to "the render side"
    )

    @Test
    fun theMotionCoreImportsNoComposeOrFrameworkCode() {
        val offenders = imports()
            .filter { (_, _, symbol) -> forbidden.any { (prefix, _) -> symbol.startsWith(prefix) } }
            .map { (path, line, symbol) ->
                val why = forbidden.first { (prefix, _) -> symbol.startsWith(prefix) }.second
                "$path:$line imports $symbol ($why)"
            }

        assertTrue(
            "animation/ and poses/ must stay free of the UI and the framework — these imports make " +
                "the motion core untestable on the JVM: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * Any other androidx artifact is not silently allowed either: a core that pulls in one androidx
     * library has started depending on the platform, so it has to be named and removed.
     */
    @Test
    fun theMotionCoreImportsNoOtherAndroidxCode() {
        val offenders = imports()
            .filter { (_, _, symbol) -> symbol.startsWith("androidx.") }
            .map { (path, line, symbol) -> "$path:$line imports $symbol" }

        assertTrue(
            "animation/ and poses/ must not depend on androidx at all — list and remove: $offenders",
            offenders.isEmpty()
        )
    }

    /** Every import line under the motion core, as (path, 1-based line, imported symbol). */
    private fun imports(): List<Triple<String, Int, String>> =
        coreSources().flatMap { (path, lines) ->
            lines.mapIndexedNotNull { index, text ->
                val trimmed = text.trim()
                if (!trimmed.startsWith("import ")) return@mapIndexedNotNull null
                Triple(path, index + 1, trimmed.removePrefix("import ").substringBefore(' ').trim())
            }
        }

    /** The motion core's sources, keyed by their path under `src/main/java`. */
    private fun coreSources(): Map<String, List<String>> {
        var dir = File(System.getProperty("user.dir") ?: ".")
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val base = File(moduleRoot ?: error("Could not locate the app module root from user.dir"), "src/main/java")

        return listOf("animation", "poses")
            .flatMap { packageDir -> File(base, "com/monkfitness/app/$packageDir").walkTopDown().toList() }
            .filter { it.isFile && it.name.endsWith(".kt") }
            .associate { it.relativeTo(base).path to it.readLines() }
    }
}
