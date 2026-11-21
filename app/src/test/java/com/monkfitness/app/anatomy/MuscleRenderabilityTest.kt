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

/**
 * `MuscleRenderabilityTest`: the mask is per instance, it is written in the same frame as the status
 * that decides it, and an invalid head hides the whole instance.
 *
 * The mask is a bit and not a hint: an invalid instance publishes no path at all, so no stale tube
 * can be drawn along the last route the solver accepted.
 */
class MuscleRenderabilityTest {

    /**
     * The shipped definition without its wrap surfaces: its obstacles refuse most heads at the bind
     * stance, and this suite is about the mask over a head the loop can reach.
     */
    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
        .copy(wrapSurfaces = emptyList())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val restPose = AnatomyPoseFixture.bindPose(skeleton)
    private val frame = AnatomyFrame.of(definition)

    private fun solverFor(definition: AnatomyDefinition = this.definition) =
        AnatomySolver(definition, skeleton, scale, restPose)

    /** A published pose in which the thorax frame cannot exist: the derived direction is non-finite. */
    private fun brokenThorax(): SkeletonPose {
        val joints = HashMap(X1Bind.bindJoints(skeleton))
        joints[Joint.NECK_END] = Vector3(Float.NaN, joints.getValue(Joint.NECK_END).y, 0f)
        return AnatomyPoseFixture.poseOf(joints)
    }

    private fun slotOf(muscleId: String, side: MuscleSide, head: Int = 0): Int =
        frame.heads.indexOf(MuscleHeadId(muscleId, side, head))

    @Test
    fun anInvalidInstanceIsMaskedInTheSameFrameAndRestoredWhenItClears() {
        val solver = solverFor()
        val slot = slotOf("rectus_abdominis", MuscleSide.A)
        val state = frame.muscleStates[slot]

        solver.solve(restPose, frame)
        assertTrue("the reference stance draws the muscle", state.renderable)
        assertEquals(PathStatus.VALID, state.pathStatus)
        val drawn = state.pointCount
        val path = FloatArray(drawn * 3) { frame.paths[slot * frame.pointStride + it] }
        assertTrue(drawn > 0)

        solver.solve(brokenThorax(), frame)
        assertFalse("an invalid head hides the instance in this same frame", state.renderable)
        assertEquals(PathStatus.INVALID, state.pathStatus)
        assertEquals("and publishes no path for it", 0, state.pointCount)

        solver.solve(restPose, frame)
        assertTrue("and the path comes back", state.renderable)
        assertEquals(PathStatus.VALID, state.pathStatus)
        assertEquals(drawn, state.pointCount)
        for (index in path.indices) {
            assertEquals(
                "the restored path is the same path, bit for bit",
                java.lang.Float.floatToIntBits(path[index]),
                java.lang.Float.floatToIntBits(frame.paths[slot * frame.pointStride + index])
            )
        }
    }

    @Test
    fun aMultiHeadInstanceIsAllOrNothing() {
        // Two heads, one of which needs the thorax frame: with that frame gone the second head is
        // refused while the first still solves, and the instance must hide anyway.
        val twoHeads = MuscleDefinition(
            id = "two_heads",
            name = "Two heads",
            layer = MuscleLayer.SUPERFICIAL,
            heads = listOf(
                MuscleHead(Attachment(Segment.PELVIS, 0f, 0f, 0f), Attachment(Segment.LUMBAR, 0f, 0.05f, 0f)),
                MuscleHead(Attachment(Segment.PELVIS, 0f, 0f, 0f), Attachment(Segment.THORAX, 0f, 0.05f, 0f))
            ),
            widthMetres = 0.05f,
            thicknessMetres = 0.02f,
            tendon = TendonSpec(0.1f, 0.1f),
            radiusKnots = listOf(0f to 0f, 1f to 0f)
        )
        val crafted = definition.copy(muscles = listOf(twoHeads), wrapSurfaces = emptyList())
        val on = AnatomyFrame.of(crafted)
        val solver = AnatomySolver(crafted, skeleton, scale, restPose)

        solver.solve(restPose, on)
        assertTrue("both heads solve at the reference stance", on.muscleStates.all { it.renderable })

        solver.solve(brokenThorax(), on)
        val first = on.muscleStates[0]
        val second = on.muscleStates[1]
        assertEquals("the first head still solves", PathStatus.VALID, first.pathStatus)
        assertEquals(PathStatus.INVALID, second.pathStatus)
        assertTrue("and it publishes its own path", first.pointCount > 0)
        assertFalse("but the instance is one shape, so it is hidden", first.renderable)
        assertFalse(second.renderable)
    }

    @Test
    fun everyInstanceIsTheConjunctionOfItsOwnHeads() {
        solverFor().solve(brokenThorax(), frame)
        for (slot in 0 until frame.headCount) {
            val instance = frame.instanceOf(slot)
            val allValid = frame.heads.indices
                .filter { frame.instanceOf(it) == instance }
                .all { frame.statuses[it] == PathStatus.VALID.ordinal }
            assertEquals(
                "instance $instance is drawable only when every head of it is",
                allValid,
                frame.renderable[instance]
            )
        }
    }

    @Test
    fun aHiddenMuscleMovesNoJoint() {
        // Renderability never reaches the skeleton: the pose is the engine's, the mask is the
        // anatomy's, and hiding a path must not freeze, hide or move a joint.
        val solver = solverFor()
        val pose = brokenThorax()
        val before = Joint.entries.associateWith { pose.getJoint(it).let { v -> Vector3(v.x, v.y, v.z) } }
        solver.solve(pose, frame)
        for (joint in Joint.entries) {
            val after = pose.getJoint(joint)
            assertEquals("$joint x", before.getValue(joint).x, after.x, 0f)
            assertEquals("$joint y", before.getValue(joint).y, after.y, 0f)
            assertEquals("$joint z", before.getValue(joint).z, after.z, 0f)
        }
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }

    @Test
    fun nothingIsRenderableBeforeASolve() {
        frame.clear()
        for (instance in 0 until frame.instanceCount) {
            assertFalse("a fresh carrier must not claim a drawable instance", frame.renderable[instance])
        }
    }
}
