package com.monkfitness.app.arch

import com.monkfitness.app.animation.ConstraintSolver
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.poses.ArmCirclesPose
import com.monkfitness.app.poses.StandardPushUpPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settlement Result surfaced on the carrier.
 *
 *  1. After [ConstraintSolver.solve] runs on a pose the solver entered, `settlementResult` is
 *     non-null and its `settledRootWorld` equals the post-FK pelvis world position.
 *  2. A pose that exits at the solver's early-return (no contacts AND CUSTOM posture intent)
 *     leaves `settlementResult` null.
 *  3. The Finalized Pose does not carry `settlementResult`: `copyFrom(pose)` does not copy the
 *     field, so the slot stays null by construction. Clear-at-publish is a property of the copy
 *     boundary, NOT a write by the Finalizer (the Finalizer is prohibited from altering the
 *     Settlement Result).
 *  4. Sole-producer audit: `settlementResult =` appears in production source in EXACTLY ONE
 *     location — the ConstraintSolver populate site. No other production source may write it.
 *  5. Contact-bearing regression: `declaredContactJoints` lists the end-joints of the Contact
 *     Declarations the Solver iterates (per-contact settlement tracking is out of scope).
 *
 * Fixture selection: the only fixtures that ENTER the solver with ZERO contacts are ARMCIRCLES
 * (declares STANDING in-build) and SQUAT_POSTURE (SquatPose + IntentBuilder.posture STANDING);
 * StandardPushUpPose and the default SquatPose exit at the early-return. MiddleSplitPose from
 * the validation package supplies contact-bearing coverage — 2 contact-bearing bakeIkLimb calls
 * (hips→ankles with ContactConstraint.ground(0f)).
 */
class SettlementResultSurfaceTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    private fun context(progress: Float = 0.5f) = PoseContext(
        progress = progress,
        side = Side.RIGHT,
        definition = definition,
        deltaTime = 1f / 60f,
        cycleDuration = 2500f
    )

    private fun pipeline() = SkeletonPipeline(definition)

    // A defective tree that read settledRootWorld before fromHierarchy would compare a
    // stale/pre-FK value against the pelvis that fromHierarchy populates LAST.
    @Test
    fun armCirclesPostureDrivenPoseProducesSettlementResult() {
        val built = ArmCirclesPose().build(context())
        ConstraintSolver.solve(built, definition)

        val sr = built.settlementResult
        assertNotNull("Posture-driven solve must populate settlementResult", sr)
        // The settled pelvis is in the published joint array after `fromHierarchy` ran (last
        // line of solve), so settledRootWorld must equal it byte-for-byte.
        val pelvisX = built.getJoint(Joint.PELVIS).x
        val pelvisY = built.getJoint(Joint.PELVIS).y
        val pelvisZ = built.getJoint(Joint.PELVIS).z
        assertEquals("Settled root X", pelvisX, sr!!.settledRootWorld.x, 1e-5f)
        assertEquals("Settled root Y", pelvisY, sr.settledRootWorld.y, 1e-5f)
        assertEquals("Settled root Z", pelvisZ, sr.settledRootWorld.z, 1e-5f)
        // ARMCIRCLES declares STANDING in-build but has no ContactSpec on any limb, so
        // declaredContactJoints is empty and conflictOutcomeJoint is null.
        assertEquals(
            "ARMCIRCLES has no Contact Declarations — declaredContactJoints is empty",
            0, sr.declaredContactJoints.size
        )
        assertNull(
            "ARMCIRCLES has no contacts — conflictOutcomeJoint is null",
            sr.conflictOutcomeJoint
        )
    }

    // The mechanism is the ABSENCE of a `copyFrom` for the field, NOT a Finalizer write.
    // Scope limit: a Finalizer write to an already-null slot would still pass here, so this test
    // enforces non-leakage only — the sole-producer test below enforces no-second-writer.
    @Test
    fun finalizedPoseDoesNotCarrySettlementResult() {
        // Three frames through the re-used finalizer buffer: the published pose's `copyFrom`
        // does not copy the field, so every frame's result is null by construction.
        val p = pipeline()
        repeat(3) { i ->
            val built = ArmCirclesPose().build(context())
            val finalized = p.produceFrame(built).pose
            assertNull(
                "Iteration $i: Finalized Pose must not carry Settlement Result (copyFrom does not copy the field; RFC §3.3)",
                finalized.settlementResult
            )
        }
    }

    // Inverse of the first test: catches a tree that populates on the early-return path, or a
    // populate site moved before the early-return.
    @Test
    fun contactlessCustomPoseLeavesSettlementResultNull() {
        // StandardPushUpPose is the canonical "CUSTOM + zero contacts" fixture: the solver
        // exits at the early-return.
        val built = StandardPushUpPose().build(context())
        // Sanity: the test fixture is configured for the early-return path.
        assertTrue("Fixture has no contacts (early-return precondition)", built.contacts.isEmpty())
        ConstraintSolver.solve(built, definition)
        assertNull(
            "Contact-less CUSTOM pose must NOT populate settlementResult (solver exited at the early-return)",
            built.settlementResult
        )
    }

    // Enforces the architectural rule, not implementation convenience: a tree with no writer at
    // all fails `totalWrites == 1`, and a Finalizer write makes it 2 — a behavioural guard, not
    // a compile-only distinction.
    @Test
    fun settlementResultHasExactlyOneSoleWriter() {
        val pkg = "com/monkfitness/app/animation"
        // Walk up from `user.dir` until we find the production source root (Gradle sets
        // user.dir to the project root or the module root).
        var moduleRoot: java.io.File? = null
        var probe = java.io.File(".").canonicalFile
        while (probe != null) {
            val tryPath = java.io.File(probe, "app/src/main/java/$pkg/ConstraintSolver.kt")
            if (tryPath.exists()) {
                moduleRoot = probe
                break
            }
            probe = probe.parentFile ?: break
        }
        checkNotNull(moduleRoot) {
            "Cannot locate production source root from user.dir=${java.io.File(".").canonicalFile}"
        }
        val productionSourceRoots = listOf(
            "ConstraintSolver.kt", "BasePose.kt", "IkStage.kt",
            "SkeletonPoseFinalizer.kt", "PoseDefinition.kt"
        ).map { java.io.File(moduleRoot, "app/src/main/java/$pkg/$it") }
        // The bare assignment pattern: `settlementResult = <something>` (excludes `==`).
        val writePattern = Regex("""settlementResult\s*=\s*[^=]""")
        var totalWrites = 0
        var solverWrites = 0
        var finalizerWrites = 0
        for (file in productionSourceRoots) {
            val text = file.readText()
            // Strip block comments so the regex doesn't pick up KDoc prose.
            val stripped = text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            val matches = writePattern.findAll(stripped).count()
            totalWrites += matches
            if (file.path.endsWith("ConstraintSolver.kt")) solverWrites = matches
            if (file.path.endsWith("SkeletonPoseFinalizer.kt")) finalizerWrites = matches
        }
        // ConstraintSolver is the sole producer; the Finalizer is prohibited from writing.
        assertEquals(
            "Exactly one production write to settlementResult (sole producer = ConstraintSolver, RFC §4.3 + R3)",
            1, totalWrites
        )
        assertEquals(
            "Sole producer must be ConstraintSolver (Phase 2 exit) — exactly one write",
            1, solverWrites
        )
        assertEquals(
            "SkeletonPoseFinalizer must have ZERO writes to settlementResult (RFC R3/R7 prohibit the Finalizer from altering the Settlement Result)",
            0, finalizerWrites
        )
    }

    // Real contact-bearing production fixture, no synthetic contact state: an existing
    // production fixture's `declaredContactJoints` must reflect the end-joints of the Contact
    // Declarations the Solver iterates — ANKLE_F, ANKLE_B for MiddleSplitPose (two
    // contact-bearing bakeIkLimb calls to hipF/hipB with ContactConstraint.ground(0f)).
    // Scope: this proves the DECLARATION list reaches the Settlement Result, not actual
    // settlement; per-contact settlement tracking is out of scope.
    @Test
    fun middleSplitContactBearingPosePopulatesDeclaredContactJoints() {
        val built = MiddleSplitPose().build(context())
        // Sanity: the early-return is `contacts.isEmpty() && !postureDriven`, so a non-empty
        // contact list bypasses it and reaches the populate site.
        assertTrue(
            "Fixture must declare at least one contact (early-return precondition negation)",
            built.contacts.isNotEmpty()
        )
        ConstraintSolver.solve(built, definition)

        val sr = built.settlementResult
        assertNotNull("Contact-bearing solve must populate settlementResult", sr)
        // MiddleSplitPose declares 2 contact-bearing bakeIkLimb calls (hipF→ankleF,
        // hipB→ankleB); declaredContactJoints must list their end-joints.
        assertEquals(
            "MiddleSplitPose declares 2 contacts (hipF→ankleF, hipB→ankleB)",
            2, sr!!.declaredContactJoints.size
        )
        // Exactly the joints the contact-bearing bakes target; order follows `pose.contacts`.
        val expectedJoints = setOf(Joint.ANKLE_F, Joint.ANKLE_B)
        assertEquals(
            "declaredContactJoints must contain exactly ANKLE_F and ANKLE_B (the two contact-bearing bakes)",
            expectedJoints,
            sr.declaredContactJoints.toSet()
        )
    }
}
