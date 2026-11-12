package com.monkfitness.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The dynamic camera is gone: its caption string and its toggle state/branch must not remain in the
 * source or in any of the seven locale resource files. The per-frame fit (`frameZoom`) remains as a
 * general engine capability for bare off-screen frames — that is reported, not asserted away here.
 */
class DynamicCameraRemovalTest {

    private val locales = listOf("values", "values-bg", "values-es", "values-it", "values-pt", "values-ru", "values-uk")

    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/res/values/strings.xml").isFile) return dir
            dir = dir.parentFile ?: break
        }
        error("Could not locate the app module root from ${System.getProperty("user.dir")}")
    }

    @Test
    fun noDynamicCameraStringRemainsInAnyOfTheSevenLocales() {
        val root = moduleRoot()
        for (locale in locales) {
            val file = File(root, "src/main/res/$locale/strings.xml")
            assertTrue("missing locale resource: $locale", file.isFile)
            val text = file.readText()
            assertFalse("$locale still declares R.string.camera_dynamic", text.contains("camera_dynamic"))
            assertFalse("$locale still names 'Dynamic camera'", text.contains("C2 — the exercise hero's camera mode toggle"))
        }
        assertEquals("the removal test must cover all 7 locales", 7, locales.size)
    }

    @Test
    fun noDynamicCameraToggleOrSettingRemainsInTheHeroSources() {
        val root = File(moduleRoot(), "src/main/java/com/monkfitness/app")
        val banned = listOf(
            "dynamicCamera",
            "R.string.camera_dynamic",
            "camera_dynamic",
            "dynamic per-frame fit is the opt-in mode",
            "dynamic \"camera push-in\" mode"
        )
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                for (term in banned) {
                    if (text.contains(term)) offenders.add("${file.name}: $term")
                }
            }
        assertTrue(
            "dynamic-camera state, setting or caption remains:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
