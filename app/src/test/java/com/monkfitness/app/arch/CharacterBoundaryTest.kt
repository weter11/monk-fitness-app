package com.monkfitness.app.arch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The character renderer is a consumer of the motion engine, never a dependency of it. Keeping the
 * boundary structural means Filament can be removed — or the presenter replaced — without touching
 * a pose, a solver or a validator.
 */
class CharacterBoundaryTest {

    private val engineRoots = listOf(
        File("src/main/java/com/monkfitness/app/animation"),
        File("src/main/java/com/monkfitness/app/poses")
    )

    @Test
    fun theMotionEngineDoesNotReferenceFilamentOrTheCharacterPresenter() {
        val forbidden = listOf("filament", "com.monkfitness.app.character")
        val offenders = engineRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    val hit = forbidden.firstOrNull { line.contains(it, ignoreCase = true) }
                    if (hit == null) emptyList() else listOf("${file.path}:${index + 1}: ${line.trim()}")
                }
            }

        assertTrue(
            "the motion engine must not depend on the renderer:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun theCharacterAssetsLiveInTheAppModuleTheEngineShippedWith() {
        val asset = File("src/main/assets/characters/humanoid_v1.glb")
        assertTrue("missing character asset: ${asset.absolutePath}", asset.isFile)
        assertTrue("a compressed GLB cannot be memory-mapped by gltfio", asset.length() > 1_000_000)
    }
}
