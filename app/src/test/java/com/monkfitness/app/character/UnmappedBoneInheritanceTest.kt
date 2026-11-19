package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The eleven visual-only bones have no engine joint, so they must ride the mapped parent that
 * carries them; holding their bind frame instead leaves fingers standing under a turning wrist.
 */
class UnmappedBoneInheritanceTest {

    private val rig = RetargetFixtures.assetRig()
    private val boneByJoint = RetargetFixtures.boneByJoint(rig)

    @Test
    fun rigidFrameCarriesAllElevenVisualOnlyBones() {
        val rotation = RetargetFixtures.axisAngle(Vector3(0f, 0f, 1f), 31f) *
            RetargetFixtures.axisAngle(Vector3(1f, 0f, 0f), 17f)
        val translation = Vector3(0.04f, -0.02f, 0.01f)
        val pivot = RetargetFixtures.bind.getValue(Joint.PELVIS)
        val pose = RetargetFixtures.rigidPose(rotation, translation, pivot)
        val matrices = RetargetFixtures.retargeter(rig).boneMatrices(pose, RetargetFixtures.unitsPerMetre)

        // A rigid motion has no per-bone correction at all, so every joint — the eleven leaves
        // included — must come out as the one transform that moved the whole body.
        val expected = RetargetFixtures.expectedRigidAsset(rig, rotation, translation)
        var worst = 0f
        for (index in 0 until matrices.size / 16) {
            worst = maxOf(
                worst,
                RetargetFixtures.worstMatrixDelta(
                    matrices.copyOfRange(index * 16, index * 16 + 16),
                    expected
                )
            )
        }
        assertTrue(
            "a visual-only bone did not follow the rigid motion (worst |value| $worst)",
            worst < 1e-4f
        )

        for (name in BoneMap.VISUAL_ONLY) {
            val child = RetargetFixtures.worldRotation(bone(matrices, name))
            val parent = RetargetFixtures.worldRotation(parentBone(matrices, name))
            val carried = angleRadians(parent.conjugate() * child)
            assertTrue("$name does not hold its parent's frame (off by $carried rad)", carried <= 1e-5f)

            // A bone that still held its bind frame would be exactly identity here, so this is the
            // case that separates riding the parent from freezing under it.
            val fromRest = angleRadians(child)
            assertTrue("$name is still at its bind rotation ($fromRest rad)", fromRest > MIN_CARRIED_RADIANS)
        }
    }

    @Test
    fun unmappedChildLocalRotationRemainsUnchangedUnderArticulatedPoses() {
        val retargeter = RetargetFixtures.retargeter(rig)
        for ((id, progress) in ARTICULATED) {
            val pose = livePose(id, progress)
            assertMoved(id, pose)
            val matrices = retargeter.boneMatrices(pose, RetargetFixtures.unitsPerMetre)

            for (name in BoneMap.VISUAL_ONLY) {
                val child = RetargetFixtures.worldRotation(bone(matrices, name))
                val parent = RetargetFixtures.worldRotation(parentBone(matrices, name))
                val local = angleRadians(parent.conjugate() * child)
                val inverse = angleRadians(child.conjugate() * parent)
                assertTrue("$name: parent→child is $local rad on $id", local <= 1e-4f)
                assertTrue("$name: child→parent is $inverse rad on $id", inverse <= 1e-4f)
            }
        }
    }

    @Test
    fun declaredTwistPolicyIsTwisNoneAndProducesRigidRide() {
        // The declaration is the behaviour: nothing attenuates the roll a parent hands down, so a
        // hand rolled 45° about its own axis must carry each finger by exactly that 45°.
        assertEquals(TwistPolicy.TWIST_NONE, BoneMap.twistPolicy)

        val roll = RetargetFixtures.axisAngle(Vector3(0f, 1f, 0f), 45f)
        val pivot = RetargetFixtures.bind.getValue(Joint.PELVIS)
        val pose = RetargetFixtures.rigidPose(roll, Vector3(0f, 0f, 0f), pivot)
        val matrices = RetargetFixtures.retargeter(rig).boneMatrices(pose, RetargetFixtures.unitsPerMetre)

        var fingers = 0
        for (name in BoneMap.VISUAL_ONLY) {
            val parentJoint = parentJointOf(name) ?: continue
            if (parentJoint != Joint.HAND_A && parentJoint != Joint.HAND_P) continue
            fingers++

            val hand = RetargetFixtures.worldRotation(
                RetargetFixtures.boneMatrix(matrices, rig, parentJoint, boneByJoint)
            )
            val child = RetargetFixtures.worldRotation(bone(matrices, name))
            val rolled = Math.toDegrees(angleRadians(hand).toDouble()).toFloat()
            assertEquals("the hand must carry the 45° roll, not a fraction of it", 45f, rolled, 1e-3f)
            val slipped = angleRadians(hand.conjugate() * child)
            assertTrue("$name is attenuated by $slipped rad instead of riding rigidly", slipped <= 1e-5f)
        }
        assertEquals("the ten finger bones ride a hand", 10, fingers)
    }

    @Test
    fun antipodalSegmentTieBreakIsDeterministicAndFinite() {
        val before = RetargetFixtures.bind.getValue(Joint.ELBOW_A)
        val after = RetargetFixtures.bind.getValue(Joint.HAND_A)
        val span = sqrt(
            (after.x - before.x) * (after.x - before.x) +
                (after.y - before.y) * (after.y - before.y) +
                (after.z - before.z) * (after.z - before.z)
        )
        // The forearm binds pointing straight down; publishing its child straight up is the one
        // direction pair with no minimal rotation, so the tie-break decides the frame.
        val bindDirection = Vector3(
            (after.x - before.x) / span,
            (after.y - before.y) / span,
            (after.z - before.z) / span
        )
        assertEquals("the forearm no longer binds along −Y", 1f, -bindDirection.y, 1e-3f)
        val flipped = Vector3(
            before.x - bindDirection.x * span,
            before.y - bindDirection.y * span,
            before.z - bindDirection.z * span
        )
        val pose = RetargetFixtures.pose(mapOf(Joint.HAND_A to flipped))

        val retargeter = RetargetFixtures.retargeter(rig)
        val first = retargeter.boneMatrices(pose, RetargetFixtures.unitsPerMetre)
        for (value in first) assertTrue("the antipodal frame produced a non-finite value", value.isFinite())

        val forearm = RetargetFixtures.boneMatrix(first, rig, Joint.ELBOW_A, boneByJoint)
        val placed = RetargetFixtures.rotate(
            RetargetFixtures.worldRotation(forearm),
            Vector3(bindDirection.x, bindDirection.y, bindDirection.z)
        )
        val miss = RetargetFixtures.angleBetween(placed, Vector3(-bindDirection.x, -bindDirection.y, -bindDirection.z))
        assertTrue("the placed direction must land on the published one, missed by $miss°", miss <= 1e-4f)

        // The frame after this one is the same input, and the walk reuses its world buffers: a
        // tie-break that depended on left-over state would drift here.
        val second = retargeter.boneMatrices(pose, RetargetFixtures.unitsPerMetre)
        assertArrayEquals("two frames of one pose must be bit-identical", first, second, 0f)
    }

    /** Rotation magnitude in radians, stable at both zero and a half turn. */
    private fun angleRadians(q: Quat): Float =
        2f * atan2(sqrt(q.x * q.x + q.y * q.y + q.z * q.z), abs(q.w))

    private fun bone(matrices: FloatArray, name: String): FloatArray {
        val node = requireNotNull(rig.nodeByNormalizedName(name)) { "the rig has no node $name" }
        val skinIndex = rig.jointNodes.indexOf(node)
        check(skinIndex >= 0) { "$name is not a skin joint" }
        return matrices.copyOfRange(skinIndex * 16, skinIndex * 16 + 16)
    }

    private fun parentBone(matrices: FloatArray, name: String): FloatArray {
        val node = requireNotNull(rig.nodeByNormalizedName(name)) { "the rig has no node $name" }
        val parent = rig.nodes[node].parent
        check(parent >= 0) { "$name has no parent to inherit from" }
        val skinIndex = rig.jointNodes.indexOf(parent)
        check(skinIndex >= 0) { "the parent of $name is not a skin joint" }
        return matrices.copyOfRange(skinIndex * 16, skinIndex * 16 + 16)
    }

    private fun parentJointOf(name: String): Joint? {
        val node = requireNotNull(rig.nodeByNormalizedName(name)) { "the rig has no node $name" }
        val parent = rig.nodes[node].parent
        return boneByJoint.entries.firstOrNull { it.value == parent }?.key
    }

    /** A frame the pipeline actually publishes, warmed the way the renderer reaches it. */
    private fun livePose(builderId: String, progress: Float): SkeletonPose {
        val builder = requireNotNull(PoseRegistry.getPoseConfig(builderId)) { "no builder $builderId" }.builder
        val pipeline = SkeletonPipeline(RetargetFixtures.definition)
        repeat(11) {
            pipeline.produceFrame(builder, PoseContext(0.3f, Side.RIGHT, RetargetFixtures.definition))
        }
        return pipeline.produceFrame(
            builder,
            PoseContext(progress, Side.RIGHT, RetargetFixtures.definition)
        ).pose
    }

    private fun assertMoved(id: String, pose: SkeletonPose) {
        var moved = 0f
        for (joint in Joint.entries) {
            val p = pose.getJoint(joint)
            val b = RetargetFixtures.bind.getValue(joint)
            val dx = p.x - b.x
            val dy = p.y - b.y
            val dz = p.z - b.z
            moved = maxOf(moved, sqrt(dx * dx + dy * dy + dz * dz))
        }
        assertTrue("$id at this progress is the bind frame, so the case proves nothing", moved > 1f)
    }

    private companion object {
        /** 5°, so a frame that carries a bone visibly cannot pass by rounding. */
        val MIN_CARRIED_RADIANS = Math.toRadians(5.0).toFloat()

        val ARTICULATED = listOf("squat_standard" to 0.42f, "birddog_hold" to 0.5f)
    }
}
