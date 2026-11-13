package com.monkfitness.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hero's dynamic camera mode is gone: no caption in any of the seven locales, and no toggle state,
 * branch or per-frame re-fit of the hero left in the sources. The per-frame fit itself stays as the
 * engine capability that bare off-screen frames use — that is a different rule, not the removed mode.
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
    fun noCaptionRemainsInAnyOfTheSevenLocales() {
        val root = moduleRoot()
        for (locale in locales) {
            val file = File(root, "src/main/res/$locale/strings.xml")
            assertTrue("missing locale resource: $locale", file.isFile)
            assertFalse(
                "$locale still declares R.string.camera_dynamic",
                file.readText().contains("camera_dynamic")
            )
        }
        assertEquals("the removal must be checked in all 7 locales", 7, locales.size)
    }

    @Test
    fun noToggleStateOrBranchRemainsInTheSources() {
        val root = File(moduleRoot(), "src/main/java/com/monkfitness/app")
        val banned = listOf(
            "dynamicCamera",
            "camera_dynamic",
            "opt-in dynamic",
            "opt-in mode",
            "camera push-in"
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
            "a dynamic-camera toggle, its caption or its mode description remains:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
