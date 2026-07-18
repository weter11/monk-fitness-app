package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * B0 (RFC_BRANCH_B_IMPLEMENTATION §2) — IntentBuilder substrate.
 *
 * B0 is purely additive: it introduces [SkeletonPose.IntentBuilder] as the **sole mutator** of the
 * §1.1 intent carriers and locks their public setters to `private set`. No engine stage consumes the
 * dead carriers yet, and no pose is changed, so this test only asserts the substrate is correct:
 *  1. the builder populates every §1.1 carrier it declares;
 *  2. the builder is the only path that may write those carriers (the compile guard — a direct
 *     `pose.spineIntent = …` / `pose.postureIntent = …` / `pose.headTarget = …` assignment outside
 *     the builder is now a compile error, enforced by `private set` on [SkeletonPose]);
 *  3. clearing via [SkeletonPose.IntentBuilder.reset] restores the defaults.
 *
 * This does NOT assert byte-identity (there is no behavior change in B0) and does NOT flip any
 * `Section11CarriersTest` dead→live assertion (that is B1/B2's job).
 */
class IntentBuilderSubstrateTest {

    @Test
    fun builderPopulatesSpineIntent() {
        val pose = SkeletonPose()
        SkeletonPose.IntentBuilder(pose).spine(0.3f, -0.2f, Vector3(0f, 0f, 1f))
        assertEquals(0.3f, pose.spineIntent.lumbarRad, 1e-6f)
        assertEquals(-0.2f, pose.spineIntent.thoracicRad, 1e-6f)
    }

    @Test
    fun builderPopulatesJointIntents() {
        val pose = SkeletonPose()
        val rot = JointRotation(Vector3(1f, 0f, 0f), 0.5f)
        SkeletonPose.IntentBuilder(pose).joint(Joint.HIP_F, rot)
        assertEquals(1, pose.jointIntents.size)
        assertEquals(Joint.HIP_F, pose.jointIntents[0].joint)
    }

    @Test
    fun builderPopulatesLimbTargets() {
        val pose = SkeletonPose()
        val world = Vector3(10f, 20f, 30f)
        SkeletonPose.IntentBuilder(pose).limbTarget(Joint.HAND_A, world)
        assertEquals(1, pose.limbTargets.size)
        assertEquals(Joint.HAND_A, pose.limbTargets[0].joint)
    }

    @Test
    fun builderPopulatesHeadTarget() {
        val pose = SkeletonPose()
        val world = Vector3(0f, 100f, 0f)
        SkeletonPose.IntentBuilder(pose).headTarget(world)
        assertNotNull(pose.headTarget)
        assertEquals(100f, pose.headTarget!!.world.y, 1e-6f)
    }

    @Test
    fun builderPopulatesPostureAndPrecedence() {
        val pose = SkeletonPose()
        SkeletonPose.IntentBuilder(pose)
            .posture(PostureIntent.Kind.STANDING, 0.1f, listOf(Joint.HAND_A, Joint.HAND_P))
        assertEquals(PostureIntent.Kind.STANDING, pose.postureIntent.kind)
        assertEquals(0.1f, pose.postureIntent.tolerance, 1e-6f)
        assertEquals(listOf("HAND_A", "HAND_P"), pose.contactPrecedence)
    }

    @Test
    fun builderPopulatesContactsAndExtremityOverride() {
        val pose = SkeletonPose()
        val spec = ContactSpec(
            endJoint = Joint.HAND_A, rootJoint = Joint.SHOULDER_A,
            parentRotationJoint = Joint.CHEST, middleJoint = Joint.ELBOW_A,
            targetWorld = Vector3(), pole = Vector3(), length1 = 1f, length2 = 1f,
            constraint = IKConstraint.LegConstraint, straight = false, contact = ContactConstraint.ground(0f)
        )
        SkeletonPose.IntentBuilder(pose)
            .contact(spec)
            .overrideExtremity(Extremity.HAND_A)
        assertEquals(1, pose.contacts.size)
        assertTrue(pose.extremityOverrides.contains(Extremity.HAND_A))
    }

    @Test
    fun resetRestoresDefaults() {
        val pose = SkeletonPose()
        val b = SkeletonPose.IntentBuilder(pose)
        b.spine(0.4f, 0.4f)
            .joint(Joint.HIP_F, JointRotation())
            .limbTarget(Joint.HAND_A, Vector3(1f, 2f, 3f))
            .posture(PostureIntent.Kind.HANGING_UNDER_BAR)
            .overrideExtremity(Extremity.FOOT_F)
        b.reset()
        assertEquals(0f, pose.spineIntent.lumbarRad, 1e-6f)
        assertEquals(0f, pose.spineIntent.thoracicRad, 1e-6f)
        assertEquals(0, pose.jointIntents.size)
        assertEquals(0, pose.limbTargets.size)
        assertEquals(PostureIntent.Kind.CUSTOM, pose.postureIntent.kind)
        assertEquals(0, pose.extremityOverrides.size)
    }
}
