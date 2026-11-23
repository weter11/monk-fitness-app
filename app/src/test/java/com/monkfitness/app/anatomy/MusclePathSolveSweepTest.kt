package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A whole rep has to solve.
 *
 * The solver refuses a head rather than publish a folded or intersecting path, so every refusal is a
 * hole in the tube layer — and, while the layer is drawn, a change in how many heads it draws. These
 * are the sweeps §7.7 records as "0 refused across the rep": twenty samples of the shipped exercise,
 * through the production pipeline, over every head of the definition.
 */
class MusclePathSolveSweepTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val solver = AnatomySolver(definition, skeleton, scale, AnatomyPoseFixture.bindPose(skeleton))
    private val frame = AnatomyFrame.of(definition)

    @Test
    fun theSquatRepSolvesEveryHeadOnEverySample() = sweep("squat_standard")

    @Test
    fun thePushUpRepSolvesEveryHeadOnEverySample() = sweep("pushup_standard")

    @Test
    fun thePlankRepSolvesEveryHeadOnEverySample() = sweep("plank_standard")

    /** Solves [samples] stations of one builder and fails on the first head any of them cannot solve. */
    private fun sweep(animationId: String, samples: Int = 20) {
        val refusals = ArrayList<String>()
        for (sample in 0 until samples) {
            val progress = sample.toFloat() / (samples - 1)
            solver.solve(AnatomyPoseFixture.livePose(animationId, progress), frame)
            for (slot in 0 until frame.headCount) {
                if (frame.statuses[slot] == PathStatus.VALID.ordinal) continue
                val id = frame.heads[slot]
                refusals += "$animationId progress=$progress ${id.muscleId} ${id.side} head${id.headIndex}" +
                    " refused: ${PathReason.entries[frame.reasonCodes[slot]]}" +
                    " (${SearchOutcome.entries[frame.searchOutcomes[slot]]}," +
                    " segment ${frame.failingSegments[slot]}, obstacle ${frame.failingObstacles[slot]})"
            }
        }
        assertEquals("every head must solve on every sample:\n${refusals.joinToString("\n")}", 0, refusals.size)
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
