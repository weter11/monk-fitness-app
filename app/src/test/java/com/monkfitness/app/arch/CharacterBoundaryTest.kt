package com.monkfitness.app.arch

import com.monkfitness.app.character.CharacterFeature
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

    private val characterRoot = File("src/main/java/com/monkfitness/app/character")

    /**
     * The other half of the dependency rule: the presenter consumes the frame the engine published.
     * A second solver here would share root authority with the engine, and editing a carrier after
     * the pipeline returned it would publish a reading nobody validated.
     */
    @Test
    fun theCharacterPresenterNeverDrivesAnEngineStageOrEditsAPose() {
        val forbidden = listOf(
            "ConstraintSolver" to "constraint solving owns the root; a second caller splits it",
            "IkStage" to "IK is an engine stage, not a presenter concern",
            "SkeletonPoseFinalizer" to "finalization publishes the frame; the presenter must not republish it",
            "bakeIkLimb" to "limb authoring belongs to the pose, not the presenter",
            ".setJoint(" to "the presenter builds a carrier through its constructor, never edits one",
            ".setJointRotation(" to "the presenter builds a carrier through its constructor, never edits one",
            ".copyFrom(" to "copying one pose over another is pose mutation"
        )
        val offenders = characterRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@flatMap emptyList()
                    }
                    val hit = forbidden.firstOrNull { (token, _) -> line.contains(token) }
                    if (hit == null) emptyList()
                    else listOf("${file.name}:${index + 1}: ${line.trim()} — ${hit.second}")
                }
            }
            .toList()

        assertTrue(
            "character/ must consume the engine's frame, not drive or edit it:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

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
        // Bound to the runtime's own path: the guard follows a cutover instead of duplicating it.
        val path = "src/main/assets/${CharacterFeature.ASSET_PATH}"
        val asset = File(path)
        assertTrue("the runtime must ship the asset it names: $path", asset.isFile)
        assertTrue("a compressed GLB cannot be memory-mapped by gltfio", asset.length() > 1_000_000)
    }
}
