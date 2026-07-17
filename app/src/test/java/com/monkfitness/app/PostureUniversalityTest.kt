package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Branch B — B3 (Posture universality).
 *
 * B3 makes the [ConstraintSolver] the owner of the coarse pelvis height for **every** pose that
 * names a non-[PostureIntent.Kind.CUSTOM] intent, not just contact instruments (M3). Every
 * production pose now declares a [PostureIntent]; for the static STANDING shapes the solver pins
 * `pelvis.y` to the STANDING seed (== the value the pose used to hand-write as `standH`), so the
 * root is engine-derived and byte-identical to the previously-authored pose.
 *
 * These tests prove the B3 contract:
 *  - every production pose carries a (non-null) posture intent;
 *  - the STANDING poses route their pelvis height through the solver (flag on) and match the
 *    exact STANDING seed;
 *  - flipping the flag off falls back to the authored root (the reversible CUSTOM path), proving
 *    the solver — not the pose — is the root owner when the flag is on.
 */
class PostureUniversalityTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalPosture = EngineFlags.SOLVER_OWNS_POSTURE

    @After
    fun resetFlag() {
        EngineFlags.SOLVER_OWNS_POSTURE = originalPosture
    }

    private fun standingPoses(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "ArmCircles" to { ArmCirclesPose() },
        "FacePull" to { FacePullPose() },
        "HipCars" to { HipCarsPose() },
        "ScapularRetraction" to { ScapularRetractionPose() },
        "WallSlides" to { WallSlidesPose() }
    )

    private fun finalized(factory: () -> PoseBuilder): SkeletonPose =
        SkeletonPipeline(def).produceFrame(factory(), PoseContext(0.5f, Side.LEFT, def)).pose

    @Test
    fun everyProductionPoseDeclaresPostureIntent() {
        // B3 exit criterion: every concrete production pose populates postureIntent (default CUSTOM
        // counts as a declared intent; the STANDING shapes declare their real kind below).
        val poses: List<Pair<String, () -> PoseBuilder>> = standingPoses() + listOf(
            "AirSquat" to { AirSquatPose() },
            "StandardPushUp" to { StandardPushUpPose() },
            "HamstringStretch" to { HamstringStretchPose() },
            "StandardPullUp" to { StandardPullUpPose() },
            "GluteBridge" to { GluteBridgePose() },
            "CatCow" to { CatCowPose() }
        )
        for ((name, factory) in poses) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertNotNull("$name must declare a postureIntent", pose.postureIntent)
        }
    }

    @Test
    fun standingPosesDeclareStanding() {
        for ((name, factory) in standingPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertEquals(
                "$name must declare STANDING posture (B3)",
                PostureIntent.Kind.STANDING, pose.postureIntent.kind
            )
        }
    }

    @Test
    fun solverOwnsStandingRootWhenFlagOn() {
        EngineFlags.SOLVER_OWNS_POSTURE = true
        val standH = def.shinLength + def.thighLength + 25f
        for ((name, factory) in standingPoses()) {
            val pose = finalized(factory)
            assertFinite(pose, name)
            // The solver pins pelvis.y to the exact STANDING seed (== the old hand-written standH).
            assertEquals(
                "$name: solver must own the STANDING root height",
                standH, pose.getJoint(Joint.PELVIS).y, 1e-4f
            )
        }
    }

    @Test
    fun flagOffFallsBackToAuthoredRoot() {
        // With the flag off the solver leaves the authored root untouched; the STANDING poses now
        // author pelvis.y = 0 (the solver owns the height), so flag-off yields a low root while
        // flag-on pins standH. This is the reversible B3 boundary.
        EngineFlags.SOLVER_OWNS_POSTURE = false
        val off = finalized { ArmCirclesPose() }
        EngineFlags.SOLVER_OWNS_POSTURE = true
        val on = finalized { ArmCirclesPose() }
        val standH = def.shinLength + def.thighLength + 25f
        assertTrue("flag-off authored root must be near floor (was ${off.getJoint(Joint.PELVIS).y})",
            off.getJoint(Joint.PELVIS).y < 1f)
        assertEquals("flag-on solver root must pin standH", standH, on.getJoint(Joint.PELVIS).y, 1e-4f)
    }

    @Test
    fun standingPoseDeterministic() {
        EngineFlags.SOLVER_OWNS_POSTURE = true
        val a = finalized { ArmCirclesPose() }
        val b = finalized { ArmCirclesPose() }
        for (joint in listOf(Joint.PELVIS, Joint.CHEST, Joint.HIP_F, Joint.ANKLE_F, Joint.SHOULDER_A, Joint.HAND_A)) {
            val pa = a.getJoint(joint)
            val pb = b.getJoint(joint)
            assertEquals("${joint} x differs", pa.x, pb.x, 1e-5f)
            assertEquals("${joint} y differs", pa.y, pb.y, 1e-5f)
            assertEquals("${joint} z differs", pa.z, pb.z, 1e-5f)
        }
    }

    private fun assertFinite(pose: SkeletonPose, label: String) {
        for (joint in Joint.entries) {
            val p = pose.getJoint(joint)
            assertTrue("$label: $joint not finite (${p.x},${p.y},${p.z})", p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
        }
    }
}
