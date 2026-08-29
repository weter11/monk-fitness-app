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
 * Phase 3 (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md, §3.2/§4.3) — Settlement Result surfaced on
 * the carrier. The Phase-3 surface contract:
 *
 *  1. After [ConstraintSolver.solve] runs on a pose that the solver entered, the pose's
 *     `settlementResult` is non-null and its `settledRootWorld` equals the post-FK pelvis
 *     world position (R2 settled root, RFC §4.3).
 *  2. The pose fixture path that exits ConstraintSolver.kt:219 (no contacts AND CUSTOM
 *     posture intent) leaves `settlementResult` `null`.
 *  3. The Finalized Pose (Published Pose State) does not carry `settlementResult` —
 *     the published pose's `copyFrom(pose)` does not copy the field
 *     (PoseDefinition.kt:320-356, verified absence), so the Finalized Pose's slot
 *     remains null by construction. Clear-at-publish is a property of the copy
 *     boundary, NOT a write by the Finalizer (RFC R3/R7 prohibit the Finalizer from
 *     altering the Settlement Result).
 *  4. Sole-producer audit: `settlementResult =` appears in production source in EXACTLY
 *     ONE location — the ConstraintSolver populate site. No other production source
 *     may write to it. The Finalizer is prohibited (R3/R7).
 *  5. Contact-bearing regression: a real contact-bearing fixture exercises the contact
 *     path; the Settlement Result's `declaredContactJoints` lists the end-joints
 *     of the Contact Declarations the Solver iterates (renamed from `settledContactJoints`
 *     per audit B2; per-contact settlement tracking remains P7's scope).
 *
 * Fixture selection: in the current P0 harness the only fixtures that ENTER the solver
 * with ZERO contacts are ARMCIRCLES (declares STANDING in-build) and SQUAT_POSTURE
 * (SquatPose + IntentBuilder.posture STANDING). StandardPushUpPose and the default
 * SquatPose exit at the early-return (line 219). For contact-bearing coverage this
 * test class also uses MiddleSplitPose from the validation package, which declares
 * 2 contact-bearing bakeIkLimb calls (hips→ankles with ContactConstraint.ground(0f)).
 * This matches the P0 fixture notes in RuntimeArchitectureBaselineTest (line 132).
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

    // ---------------------------------------------------------------------------------
    // Test 1 — posture-driven path (ARMCIRCLES) drives solver entry; settlementResult
    // is populated and its settledRootWorld matches the post-FK pelvis world position.
    //
    // Counterfactual:
    //   - Pre-Phase-3 tree: `pose.settlementResult` is not a field; this test would not
    //     compile, so it cannot pass on a pre-Phase-3 tree.
    //   - Defective Phase-3 tree (e.g., settledRootWorld read before fromHierarchy):
    //     sr.settledRootWorld would be a stale or pre-FK value; the equality assertion
    //     against `pose.getJoint(PELVIS)` (which fromHierarchy populates LAST) would fail.
    // ---------------------------------------------------------------------------------
    @Test
    fun armCirclesPostureDrivenPoseProducesSettlementResult() {
        val built = ArmCirclesPose().build(context())
        ConstraintSolver.solve(built, definition)

        val sr = built.settlementResult
        assertNotNull("Posture-driven solve must populate settlementResult", sr)
        // The settled pelvis world position is in the published pose's joint array after
        // `fromHierarchy` ran (last line of ConstraintSolver.solve). The Settlement Result's
        // settledRootWorld must equal it byte-for-byte (R2 settled root, RFC §4.3).
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

    // ---------------------------------------------------------------------------------
    // Test 2 — Finalized Pose (Published Pose State) must NOT carry settlementResult.
    // The mechanism is the absence of `copyFrom` for the field, NOT a Finalizer write.
    //
    // Counterfactual:
    //   - Pre-Phase-3 tree: no `settlementResult` field on the carrier; this test
    //     would not compile.
    //   - Defective Phase-3 tree where `copyFrom` was changed to copy settlementResult:
    //     finalized.settlementResult would be non-null; the assertion would fail.
    //   - Defective Phase-3 tree where the Finalizer WRITES settlementResult (the
    //     previously-violating state, audit B1): the assertNull would still pass on
    //     outputPose because the Finalizer's write was on a slot that was already null,
    //     and a buggy write that DID set it would fail this test. So the test does NOT
    //     distinguish a B1-violating tree from a B1-resolved tree on its own — it
    //     requires the audit (test 4) to enforce the single-writer rule. This is the
    //     test's correct scope (it enforces the non-leakage contract; the sole-producer
    //     test enforces the no-second-writer rule).
    // ---------------------------------------------------------------------------------
    @Test
    fun finalizedPoseDoesNotCarrySettlementResult() {
        // Drive three frames through the re-used finalizer buffer. After every frame the
        // returned pose must have null settlementResult. The published pose's `copyFrom`
        // does not copy the field (verified at PoseDefinition.kt:320-356), so this
        // assertion holds by construction of the copy boundary.
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

    // ---------------------------------------------------------------------------------
    // Test 3 — contact-less CUSTOM pose exits ConstraintSolver at the early-return
    // (line 219) and so leaves settlementResult null. The inverse of test 1.
    //
    // Counterfactual:
    //   - Pre-Phase-3 tree: no field; test does not compile.
    //   - Defective Phase-3 tree where the Solver populates settlementResult on the
    //     early-return path: this assertion would fail (sr would be non-null). This
    //     catches a regression where the populate site is moved before the early-return.
    // ---------------------------------------------------------------------------------
    @Test
    fun contactlessCustomPoseLeavesSettlementResultNull() {
        // Build a StandardPushUpPose (CUSTOM default posture) — solver exits at line 219
        // (no contacts AND CUSTOM posture). PUSHUP is the canonical "CUSTOM + zero contacts"
        // fixture identified by the P0 baseline audit (line 132).
        val built = StandardPushUpPose().build(context())
        // Sanity: the test fixture is configured for the early-return path.
        assertTrue("Fixture has no contacts (early-return precondition)", built.contacts.isEmpty())
        ConstraintSolver.solve(built, definition)
        assertNull(
            "Contact-less CUSTOM pose must NOT populate settlementResult (solver exited at the early-return)",
            built.settlementResult
        )
    }

    // ---------------------------------------------------------------------------------
    // Test 4 — Sole-producer audit (production source). `settlementResult` must be
    // assigned in production source in EXACTLY ONE location: the ConstraintSolver
    // populate site. The Finalizer is prohibited (RFC R3/R7) and must have ZERO
    // writes. This test enforces the architectural rule, not implementation
    // convenience.
    //
    // Counterfactual:
    //   - Pre-Phase-3 tree: no `settlementResult =` writes anywhere; the assertion
    //     `totalWrites == 1` would fail (it would be 0). The test is a meaningful
    //     behavioral guard, not a compile-only distinction.
    //   - Defective Phase-3 tree where the Finalizer writes to settlementResult
    //     (the previously-violating state, audit B1): `finalizerWrites` would be
    //     1, and `totalWrites` would be 2. The assertions fail. The test
    //     CATCHES the B1 violation.
    // ---------------------------------------------------------------------------------
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
        // Architectural rule: ConstraintSolver is the sole producer. The Finalizer is
        // prohibited from writing (R3/R7). Asserting the rule, not implementation
        // convenience — the previously-violating state (B1) is the negative case
        // this test catches.
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

    // ---------------------------------------------------------------------------------
    // Test 5 — Contact-bearing regression. Real contact-bearing production fixture
    // (MiddleSplitPose from the validation package) drives the contact path. The
    // Settlement Result's `declaredContactJoints` must reflect the end-joints of the
    // Contact Declarations the Solver iterates — ANKLE_F, ANKLE_B for MiddleSplitPose
    // (two contact-bearing bakeIkLimb calls to hipF/hipB with ContactConstraint.ground(0f),
    // endNode = ankleF / ankleB).
    //
    // The test uses an EXISTING production fixture, no synthetic contact state. It is
    // not vacuous: the test would fail on a pre-Phase-3 tree (no field to read), and
    // it would fail on a Phase-3 tree where the field carries anything other than the
    // declared end-joints.
    //
    // Counterfactual:
    //   - Pre-Phase-3 tree: no `settlementResult` field; `built.settlementResult`
    //     would not compile, so this test cannot pass on a pre-Phase-3 tree.
    //   - Defective Phase-3 tree where declaredContactJoints is hardcoded empty:
    //     sr.declaredContactJoints.size would be 0; the assertion `size == 2` fails.
    //   - Defective Phase-3 tree where the field is renamed back to `settledContactJoints`:
    //     the data class field is `declaredContactJoints`; access to a non-existent
    //     `sr.settledContactJoints` would not compile, so the test would not even
    //     reach the assertion. (This is a compile-time guard against the B2
    //     regression.)
    //   - The test does NOT prove actual settlement — it proves the DECLARATION list
    //     reaches the Settlement Result, per the B2 scope discipline. Per-contact
    //     settlement tracking remains P7's scope.
    // ---------------------------------------------------------------------------------
    @Test
    fun middleSplitContactBearingPosePopulatesDeclaredContactJoints() {
        val built = MiddleSplitPose().build(context())
        // Sanity: this is a contact-bearing fixture (not the contact-less CUSTOM path).
        // The early-return at ConstraintSolver.kt:219 is `contacts.isEmpty() && !postureDriven`,
        // so a non-empty contact list bypasses the early-return and reaches the populate site.
        assertTrue(
            "Fixture must declare at least one contact (early-return precondition negation)",
            built.contacts.isNotEmpty()
        )
        ConstraintSolver.solve(built, definition)

        val sr = built.settlementResult
        assertNotNull("Contact-bearing solve must populate settlementResult", sr)
        // MiddleSplitPose declares 2 contact-bearing bakeIkLimb calls: hipF→ankleF, hipB→ankleB
        // (see MiddleSplitPose.kt:77-78, ContactConstraint.ground(0f)). The Solver iterates
        // these declarations; declaredContactJoints must list the corresponding end-joints.
        assertEquals(
            "MiddleSplitPose declares 2 contacts (hipF→ankleF, hipB→ankleB)",
            2, sr!!.declaredContactJoints.size
        )
        // The two end-joints must be exactly the ones the contact-bearing bake calls target.
        // Order follows the iteration order over `pose.contacts` (which follows
        // `bakeIkLimb` call order in MiddleSplitPose.buildStatic).
        val expectedJoints = setOf(Joint.ANKLE_F, Joint.ANKLE_B)
        assertEquals(
            "declaredContactJoints must contain exactly ANKLE_F and ANKLE_B (the two contact-bearing bakes)",
            expectedJoints,
            sr.declaredContactJoints.toSet()
        )
    }
}
