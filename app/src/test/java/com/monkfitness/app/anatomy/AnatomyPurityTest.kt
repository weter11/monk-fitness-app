package com.monkfitness.app.anatomy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Anatomy is an engine-space observer: it reads published joints and writes its own buffers, so it
 * must not reach for the renderer, the rig or a platform type to do it.
 *
 * The dependency is structural rather than documented because a single `android.` import is enough to
 * make every anatomy test need Robolectric — which is how the package would quietly stop being a
 * pure-Kotlin JVM module.
 */
class AnatomyPurityTest {

    private val forbidden = listOf(
        "android.",
        "androidx.",
        "com.google.android.filament",
        "com.monkfitness.app.character",
        "com.monkfitness.app.ui",
        "com.monkfitness.app.render"
    )

    @Test
    fun theAnatomyPackageImportsNoPlatformRendererOrRigCode() {
        val root = File("src/main/java/com/monkfitness/app/anatomy")
        assertTrue("the anatomy sources must exist at ${root.path}", root.isDirectory)

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    if (!line.trimStart().startsWith("import ")) return@flatMap emptyList()
                    val hit = forbidden.firstOrNull { line.contains(it) }
                    if (hit == null) emptyList() else listOf("${file.name}:${index + 1}: ${line.trim()}")
                }
            }
            .toList()

        assertTrue(
            "anatomy must stay engine-space only:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun theAnatomySourcesReadPosesAndNeverTheFilesystem() {
        // The loader takes a String, so the only file access in the package would be a caller's; an
        // asset read here would bind the JVM tests to an Android Context.
        val sources = File("src/main/java/com/monkfitness/app/anatomy").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val offenders = sources.filter { file ->
            val text = file.readText()
            text.contains("java.io.File(") || text.contains("context.assets")
        }
        assertTrue("no anatomy file may read a file or an asset: ${offenders.map { it.name }}", offenders.isEmpty())
    }
}
