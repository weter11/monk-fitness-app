package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the architecture fence mechanically, not by convention: no compiled source in
 * `domain/adaptive` may import the data layer (`com.monkfitness.app.data.*`, Room, or Android), and
 * every `domain.adaptive` type the adapter hands the mapper is defined in this package.
 *
 * This runs on the JVM against the sources themselves, so it fails the build the moment a future
 * edit reintroduces a `data.model.Exercise` import into the mapper or anywhere else in this package
 * — the exact regression the PR #268 review caught.
 */
class AdaptiveDomainPurityTest {

    private val packageDir = File(
        "src/main/java/com/monkfitness/app/domain/adaptive"
    ).let { dir ->
        // Unit-test JVM cwd is app/, but fall back to the repo root layout for safety.
        if (dir.isDirectory) dir else File("app/$dir")
    }

    @Test
    fun noAdaptiveDomainSourceImportsTheDataLayer() {
        assertTrue(
            "expected the domain/adaptive package to exist at ${packageDir.absolutePath}",
            packageDir.isDirectory
        )

        val sources = packageDir.listFiles { file -> file.isFile && file.extension == "kt" }
            ?.sortedBy { it.name }
            ?: emptyList()
        assertTrue("expected at least one .kt file in ${packageDir.absolutePath}", sources.isNotEmpty())

        val forbidden = sources.flatMap { source ->
            source.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) return@mapNotNull null
                // Only a data-layer or platform import into this package breaks the boundary.
                if (trimmed.startsWith("import com.monkfitness.app.data.")) {
                    "${source.name}: $trimmed"
                } else if (
                    trimmed.startsWith("import androidx.room.") ||
                    trimmed.startsWith("import android.")
                ) {
                    "${source.name}: $trimmed"
                } else null
            }
        }

        assertTrue(
            "domain/adaptive must not depend on the data layer or Android/Room. Found: $forbidden",
            forbidden.isEmpty()
        )
    }

    @Test
    fun thePlannedExerciseTypeLivesInThisPackage() {
        // A pure domain type the mapper consumes: resolving it here means it is defined in
        // domain.adaptive, not re-exported from the data layer.
        val resolved = Class.forName("com.monkfitness.app.domain.adaptive.PlannedExercise")
        assertEquals(
            "PlannedExercise must live in the adaptive domain package",
            javaClass.`package`?.name,
            resolved.`package`?.name
        )
        // Pure: no data-layer supertype leaks through it.
        val name = resolved.superclass.name
        assertTrue("PlannedExercise must not extend a data-layer type: $name", name == "java.lang.Object")
    }
}
