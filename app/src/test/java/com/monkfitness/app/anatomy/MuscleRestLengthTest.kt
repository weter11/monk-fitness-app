package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * `MuscleRestLengthTest`: the reference length is the solved length at the reference stance, not an
 * authored number, so the rest state and the runtime cannot disagree — and a head that cannot produce
 * one is refused instead of divided by.
 */
class MuscleRestLengthTest {

    private val shipped: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())

    /**
     * The shipped definition without its wrap surfaces. The obstacles refuse most heads at the bind
     * stance (see `theShippedDefinitionRefusesMostHeadsAtTheReferenceStanceAndSaysWhy`), and this
     * suite is about the reference length itself, not about that review.
     */
    private val definition: AnatomyDefinition = shipped.copy(wrapSurfaces = emptyList())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val restPose = AnatomyPoseFixture.bindPose(skeleton)
    private val frames = SegmentFrames(skeleton)
    private val frameSet = SegmentFrameSet()
    private val frame = AnatomyFrame.of(definition)

    init {
        frames.derive(restPose, frameSet)
    }

    private fun solverFor(definition: AnatomyDefinition = this.definition) =
        AnatomySolver(definition, skeleton, scale, restPose)

    private fun state(slot: Int) = frame.muscleStates[slot]

    @Test
    fun everyHeadHasAPositiveFiniteLengthAtTheReferenceStance() {
        solverFor().solve(restPose, frame)
        for (slot in 0 until frame.headCount) {
            val value = state(slot).restLength
            assertTrue("${state(slot).id} head ${state(slot).headIndex} has no reference length: $value", value.isFinite())
            assertTrue("and it must be strictly positive: $value", value > 0f)
        }
    }

    @Test
    fun theReferenceLengthIsTheSameLoopRunAtTheReferenceStance() {
        // Recomputed through the pass loop alone, so the check cannot pass by reading its own output.
        val scratch = AnatomyFrame.of(definition)
        val loop = MusclePathSolver(definition, MusclePathBuilder(definition, scale.toFloat()), AnatomyValidator(definition, scale), scale)
        solverFor().solve(restPose, frame)

        var slot = 0
        for (muscle in definition.muscles) {
            for (side in MuscleSide.entries) {
                for (headIndex in muscle.heads.indices) {
                    val expected = if (loop.solve(muscle, side, restPose, frameSet, scratch, slot) > 0) {
                        scratch.modelledPathLengths[slot]
                    } else {
                        Float.NaN
                    }
                    if (expected.isFinite()) {
                        assertEquals(
                            "${muscle.id} head $headIndex",
                            expected.toDouble(),
                            state(slot).restLength.toDouble(),
                            maxOf(expected.toDouble(), 1.0) * 1e-6
                        )
                    } else {
                        assertFalse("$muscle must not claim a reference length", state(slot).restLength.isFinite())
                    }
                    slot++
                }
            }
        }
    }

    @Test
    fun theReferenceLengthDoesNotFollowTheRuntimePose() {
        val solver = solverFor()
        solver.solve(restPose, frame)
        val rest = FloatArray(frame.headCount) { frame.restLengths[it] }

        solver.solve(lengthened(), frame)
        for (slot in 0 until frame.headCount) {
            assertEquals(
                "the reference stance fixes this number, not the pose being solved",
                rest[slot],
                frame.restLengths[slot],
                0f
            )
        }
    }

    @Test
    fun theRelativeChangeIsTheModelledLengthOverTheReferenceMinusOne() {
        val solver = solverFor()
        solver.solve(lengthened(), frame)
        for (slot in 0 until frame.headCount) {
            val state = state(slot)
            if (state.pathStatus != PathStatus.VALID) continue
            val expected = (state.modelledPathLength / state.restLength - 1f).toDouble()
            assertEquals("${state.id} head ${state.headIndex}", expected, state.relativePathLengthChange.toDouble(), 1e-6)
        }
    }

    @Test
    fun theTendonPortionIsTheAuthoredFractionOfTheSolvedLength() {
        solverFor().solve(restPose, frame)
        for (slot in 0 until frame.headCount) {
            val state = state(slot)
            val muscle = definition.muscle(state.id)!!
            val fractions = muscle.tendon.originFraction + muscle.tendon.insertionFraction
            assertEquals(
                "${state.id} head ${state.headIndex}",
                (fractions * state.modelledPathLength).toDouble(),
                state.visualTendonPortionLength.toDouble(),
                1e-6
            )
        }
    }

    @Test
    fun aHeadWithNoReferencePathIsRefusedAndNeverDivides() {
        // Origin and insertion at the same point: the reference stance produces no length at all.
        val nullLength = MuscleDefinition(
            id = "null_length",
            name = "Null length",
            layer = MuscleLayer.SUPERFICIAL,
            heads = listOf(MuscleHead(Attachment(Segment.PELVIS, 0f, 0f, 0f), Attachment(Segment.PELVIS, 0f, 0f, 0f))),
            widthMetres = 0.05f,
            thicknessMetres = 0.02f,
            tendon = TendonSpec(0.1f, 0.1f),
            radiusKnots = listOf(0f to 0f, 1f to 0f)
        )
        val degenerate = definition.copy(muscles = listOf(nullLength), wrapSurfaces = emptyList())
        val on = AnatomyFrame.of(degenerate)
        AnatomySolver(degenerate, skeleton, scale, restPose).solve(restPose, on)

        val state = on.muscleStates[0]
        assertEquals(PathStatus.INVALID, state.pathStatus)
        assertEquals(PathReason.REST_LENGTH_INVALID, state.reason)
        assertEquals(SearchOutcome.REJECTED_BEFORE_SEARCH, state.searchOutcome)
        assertEquals("no path is published for a head that cannot be compared", 0, state.pointCount)
        assertEquals("and no meaningless ratio is", 0f, state.relativePathLengthChange, 0f)
        assertFalse("so the instance is not drawn", state.renderable)
    }

    @Test
    fun theShippedDefinitionPublishesAReferenceLengthForEveryHead() {
        // The placeholder geometry is tuned against §7.4.2's bind-stance rows: every authored
        // attachment now clears its own obstacle union, so the reference solve reaches every head and
        // the network has a denominator on the first frame instead of on none of them.
        val on = AnatomyFrame.of(shipped)
        AnatomySolver(shipped, skeleton, scale, restPose).solve(restPose, on)
        for (slot in 0 until on.headCount) {
            val state = on.muscleStates[slot]
            assertEquals(
                "the reference stance must solve ${state.id} ${state.side} head${state.headIndex}",
                PathStatus.VALID,
                state.pathStatus
            )
            assertTrue(
                "a published rest length is finite and positive, was ${state.restLength}",
                state.restLength.isFinite() && state.restLength > 0f
            )
            assertEquals(
                "at the reference stance the modelled length is the reference length",
                state.restLength,
                state.modelledPathLength,
                1e-4f
            )
            assertTrue("and the instance is drawable", state.renderable)
        }
    }

    /** The reference stance with the thorax lifted, so a runtime pose differs from it. */
    private fun lengthened(): SkeletonPose {
        val joints = HashMap(X1Bind.bindJoints(skeleton))
        val chest = joints.getValue(Joint.CHEST)
        joints[Joint.CHEST] = Vector3(chest.x, chest.y + abs(skeleton.torsoLength) * 0.1f, chest.z)
        return AnatomyPoseFixture.poseOf(joints)
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
