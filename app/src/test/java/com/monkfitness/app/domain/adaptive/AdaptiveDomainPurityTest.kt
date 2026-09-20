package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the architecture fence mechanically, not by convention: no compiled source anywhere in
 * `domain/adaptive` — the package itself **and every nested package of it** — may import the data layer
 * (`com.monkfitness.app.data.*`, Room, or Android), the UI/ViewModel layers, or any `androidx`/`android`
 * platform type at all, and every `domain.adaptive` type the adapter hands the mapper is defined in this
 * package.
 *
 * This runs on the JVM against the sources themselves, so it fails the build the moment a future
 * edit reintroduces a `data.model.Exercise` import into the mapper or anywhere else in this package
 * — the exact regression the PR #268 review caught — or lets the configuration validator reach for
 * Android, Room, DataStore or a ViewModel instead of the metadata its caller supplies.
 *
 * §30 step 11 revised the scan: it used to read only the package's own top-level files, and the target
 * adaptive engine is the first thing to live in a nested package (`domain/adaptive/engine`). Reading the
 * whole subtree is strictly stronger than reading one level of it — a nested package is fenced exactly
 * like a top-level one, and a *future* nested package cannot sit outside the fence by accident.
 */
class AdaptiveDomainPurityTest {

    private val packageDir = File(
        "src/main/java/com/monkfitness/app/domain/adaptive"
    ).let { dir ->
        // Unit-test JVM cwd is app/, but fall back to the repo root layout for safety.
        if (dir.isDirectory) dir else File("app/$dir")
    }

    /**
     * The import prefixes that may never appear in this package. `domain.usecase` is deliberately
     * absent: it is a domain-layer package over the app's own model, and the one import this package
     * legitimately takes from it is the canonical program-calendar constant.
     */
    private val forbiddenImportPrefixes = listOf(
        "import com.monkfitness.app.data.",
        "import com.monkfitness.app.ui.",
        "import com.monkfitness.app.viewmodel.",
        "import com.monkfitness.app.animation.",
        "import com.monkfitness.app.poses.",
        "import androidx.",
        "import android.",
        "import kotlinx."
    )

    @Test
    fun noAdaptiveDomainSourceImportsTheDataLayerPlatformOrUi() {
        assertTrue(
            "expected the domain/adaptive package to exist at ${packageDir.absolutePath}",
            packageDir.isDirectory
        )

        val sources = packageDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
        assertTrue("expected at least one .kt file in ${packageDir.absolutePath}", sources.isNotEmpty())

        val forbidden = sources.flatMap { source ->
            source.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) return@mapNotNull null
                // Only a platform/UI/data-layer import into this package breaks the boundary.
                if (forbiddenImportPrefixes.any { trimmed.startsWith(it) }) {
                    "${source.name}: $trimmed"
                } else null
            }
        }

        assertTrue(
            "domain/adaptive must not depend on the data layer, Android/Room/DataStore or the UI. " +
                "Found: $forbidden",
            forbidden.isEmpty()
        )
    }

    @Test
    fun thePurePresentationValueTheTargetRuntimeConsumesLivesInTheDomain() {
        // §30 step 15 replaced this probe's subject. `PlannedExercise` was the Stage-1 mapper's pure
        // type and is retired with it; the target runtime's equivalent — the presentation composed from
        // a revision plus its standing adjustments — is `domain.workout.EffectiveExercise`, and the
        // claim is the same one: it is a domain type, not something re-exported from the data layer.
        val resolved = Class.forName("com.monkfitness.app.domain.workout.EffectiveExercise")
        assertEquals(
            "the presented element must live in the domain, not in the data layer",
            "com.monkfitness.app.domain.workout",
            resolved.`package`?.name
        )
        val name = resolved.superclass.name
        assertTrue(
            "it must not extend a data-layer type: $name",
            name == "java.lang.Object"
        )
    }
}
