package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * B0 (RFC_BRANCH_B_IMPLEMENTATION) — `IntentBuilder` is the single §1.1 authoring surface.
 *
 * These tests pin the *positive* contract: every declaration on `IntentBuilder` populates exactly the
 * §1.1 carrier the legacy direct assignment used to populate, with identical values. They are the
 * regression guard for B0 — if a declaration ever wrote a different carrier or value, produced intent
 * would change and these would fail. They do NOT test consumer stages (those land in B1/B2/B3).
 *
 * The *negative* contract ("no `BasePose` helper assigns a §1.1 carrier field directly") is proven by
 * the refactor in `BasePose.kt`: `buildGaze`/`declarePosture`/`overrideExtremityOrientation`/the
 * `contacts.add` in `bakeIkLimb` all route through `IntentBuilder`, and a grep confirms zero direct
 * `jointsBuffer.<carrier>` writes remain. The hard carrier-visibility lockdown is deferred to B4.
 */
class IntentBuilderTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private fun fresh() = SkeletonPose()

    @Test
    fun spinePopulatesSpineIntent() {
        val p = fresh()
        IntentBuilder(p).spine(0.3f, -0.2f, Vector3(0f, 0f, 1f))
        assertEquals(0.3f, p.spineIntent.lumbarRad, 1e-6f)
        assertEquals(-0.2f, p.spineIntent.thoracicRad, 1e-6f)
        assertEquals(0f, p.spineIntent.axis.x, 1e-6f)
        assertEquals(0f, p.spineIntent.axis.y, 1e-6f)
        assertEquals(1f, p.spineIntent.axis.z, 1e-6f)
    }

    @Test
    fun jointPopulatesJointIntents() {
        val p = fresh()
        val rot = JointRotation(Vector3(1f, 0f, 0f), 0.5f)
        IntentBuilder(p).joint(Joint.HIP_F, rot)
        assertEquals(1, p.jointIntents.size)
        assertEquals(Joint.HIP_F, p.jointIntents[0].joint)
        assertEquals(rot, p.jointIntents[0].rotation)
    }

    @Test
    fun limbTargetPopulatesLimbTargets() {
        val p = fresh()
        val world = Vector3(1f, 2f, 3f)
        IntentBuilder(p).limbTarget(Joint.HAND_A, world)
        assertEquals(1, p.limbTargets.size)
        assertEquals(Joint.HAND_A, p.limbTargets[0].joint)
        assertEquals(1f, p.limbTargets[0].world.x, 1e-6f)
        assertEquals(2f, p.limbTargets[0].world.y, 1e-6f)
        assertEquals(3f, p.limbTargets[0].world.z, 1e-6f)
    }

    @Test
    fun postureAndPrecedencePopulateCarriers() {
        val p = fresh()
        IntentBuilder(p).posture(PostureIntent.Kind.SEATED_NEAR_FLOOR, 0.1f)
            .precedence(listOf(Joint.ANKLE_F, Joint.ANKLE_B))
        assertEquals(PostureIntent.Kind.SEATED_NEAR_FLOOR, p.postureIntent.kind)
        assertEquals(0.1f, p.postureIntent.tolerance, 1e-6f)
        assertEquals(listOf("ANKLE_F", "ANKLE_B"), p.contactPrecedence)
    }

    @Test
    fun contactAddsToContacts() {
        val p = fresh()
        val spec = ContactSpec(
            endJoint = Joint.HAND_A, rootJoint = Joint.CHEST, parentRotationJoint = Joint.SHOULDER_A,
            middleJoint = Joint.ELBOW_A, targetWorld = Vector3(0f, 1f, 0f), pole = Vector3(0f, 1f, 0f),
            length1 = 1f, length2 = 1f, constraint = def.armIKConstraint, straight = false, contact = null
        )
        IntentBuilder(p).contact(spec)
        assertEquals(1, p.contacts.size)
        assertEquals(Joint.HAND_A, p.contacts[0].endJoint)
    }

    @Test
    fun headTargetPopulatesHeadTarget() {
        val p = fresh()
        val world = Vector3(0f, 2f, 1f)
        IntentBuilder(p).headTarget(world)
        assertNotNull(p.headTarget)
        assertEquals(0f, p.headTarget!!.world.x, 1e-6f)
        assertEquals(2f, p.headTarget!!.world.y, 1e-6f)
        assertEquals(1f, p.headTarget!!.world.z, 1e-6f)
    }

    @Test
    fun overrideExtremityPopulatesExtremityOverrides() {
        val p = fresh()
        IntentBuilder(p).overrideExtremity(Extremity.FOOT_F)
        assertTrue(p.extremityOverrides.contains(Extremity.FOOT_F))
        assertFalse(p.isExtremityAutomatic(Extremity.FOOT_F))
    }

    @Test
    fun hintsPopulateMotionCameraEnvironment() {
        val p = fresh()
        IntentBuilder(p).motion("m").camera("c").environment("e")
        assertEquals("m", p.motion)
        assertEquals("c", p.camera)
        assertEquals("e", p.environment)
    }

    @Test
    fun builderIsFluentAndChainable() {
        val p = fresh()
        val out = IntentBuilder(p)
            .spine(0.1f, 0.2f)
            .joint(Joint.HIP_F, JointRotation(Vector3(0f, 0f, 1f), 0.3f))
            .posture(PostureIntent.Kind.STANDING)
            .build()
        assertSame(p, out)
        assertEquals(0.1f, p.spineIntent.lumbarRad, 1e-6f)
        assertEquals(1, p.jointIntents.size)
        assertEquals(PostureIntent.Kind.STANDING, p.postureIntent.kind)
    }
}
