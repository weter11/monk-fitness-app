package com.monkfitness.app.arch

import com.monkfitness.app.animation.BasePose
import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.IkStage
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.SkeletonNode
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.animation.WorldTarget
import com.monkfitness.app.validation.poses.MiddleSplitPose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12 WP-G (§12.7b/§12.7c) — **single-active-solver enforcement** for the R5 ownership invariant:
 *
 * > For one build cycle, exactly one Active Limb Solver may realize limb constraints for a given
 * > configuration. `IK_STAGE_ACTIVE=false` ⇒ the authoring bake is that solver and the engine
 * > stage must not realize; `IK_STAGE_ACTIVE=true` ⇒ the engine stage is that solver and the
 * > authoring bake must not realize.
 *
 * The invariant is enforced on **execution evidence**, never on the produced pose: the realization
 * sites register each realized limb on the carrier (`SkeletonPose.registerLimbRealization`), and
 * `SkeletonPipeline.runStages` rejects a frame whose cycle recorded a limb twice or a second solver
 * window. The counterfactual below is therefore deliberately constructed so that BOTH realizations
 * produce a byte-identical frame — the failure comes from the two observed executions, not from any
 * difference in output:
 *
 * ```text
 *   build cycle N                       (getter: identical declared inputs every time)
 *     realization #1  bakeIkLimb(... ANKLE_F ...)  -> knee/ankle locals
 *     realization #2  bakeIkLimb(... ANKLE_F ...)  -> identical locals (no-op on output)
 *   final pose == the single-realization pose (all 33 joints raw-bit identical, stamps identical)
 *   execution evidence: 2 realization events for ONE limb -> R5 violation
 * ```
 *
 * RED evidence for this file (WP-G RED gate, branch tip `23ff2ae`, run 2026-09-10T21:39:48): with
 * the evidence at build-cycle granularity the two counterfactual cases reported the SAME evidence
 * as a single realization (`limbSolverExecutions=1`, no violation raised) and the tests failed at
 * the final assertion — see the WP-G report. Nothing in this file is a workaround for that gap:
 * the assertions read the registered evidence directly.
 */
class SingleActiveSolverEnforcementTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalFlag = IK_STAGE_ACTIVE
    private val ctx = PoseContext(0f, Side.LEFT, def)

    @After
    fun restoreFlag() {
        IK_STAGE_ACTIVE = originalFlag
    }

    // ------------------------------------------------------------------ fixture

    /**
     * Test-only authoring probe: builds the standard skeleton and realizes ONE limb
     * (`ANKLE_F`: pelvis → hip → knee → ankle) `realizations` times, with identical declared
     * inputs, inside a single `build` cycle (`BasePose.build` opens exactly one cycle per build).
     */
    private class LimbRealizationProbe(private val realizations: Int) : BasePose() {

        /** Test-side count of registered realization-entry-point invocations made by this build. */
        var solverInvocations: Int = 0
            private set

        override fun onBuild(context: PoseContext): SkeletonPose {
            val definition = context.definition
            val nodes = SkeletonFactory.createStandardSkeleton()
            nodes.pelvis.localPosition.set(FIXTURE_ROOT.x, FIXTURE_ROOT.y, FIXTURE_ROOT.z)
            nodes.hipF.localPosition.set(0f, 0f, 0f) // hip world == root (no hip offset)
            nodes.kneeF.localPosition.set(definition.thighLength, 0f, 0f)
            nodes.ankleF.localPosition.set(definition.shinLength, 0f, 0f)
            nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }

            // REALIZATION LOOP — the registered realization entry point (BasePose.bakeIkLimb, the
            // Active Limb Solver while the engine stage is off). Identical declared inputs on every
            // iteration ⇒ identical writes ⇒ every realization after the first is a no-op on the
            // OUTPUT and a genuine second EXECUTION of the solver.
            repeat(realizations) {
                solverInvocations++
                bakeIkLimb(
                    nodes.hipF.worldPosition,
                    FIXTURE_TARGET,
                    definition.thighLength,
                    definition.shinLength,
                    FIXTURE_POLE,
                    definition.legIKConstraint,
                    nodes.pelvis.worldRotation,
                    nodes.kneeF,
                    nodes.ankleF,
                    SkeletonMath.IKResult()
                )
            }
            return SkeletonPose.fromHierarchy(nodes.roots, jointsBuffer)
        }
    }

    private fun jointBits(pose: SkeletonPose): List<Int> =
        Joint.entries.flatMap { j ->
            val v = pose.getJoint(j)
            listOf(v.x.toRawBits(), v.y.toRawBits(), v.z.toRawBits())
        }

    /** Raw-bit snapshot of every node's local position — the realization write surface. */
    private fun nodeLocalBits(pose: SkeletonPose): List<Int> {
        val out = mutableListOf<Int>()
        fun walk(node: SkeletonNode) {
            out.add(node.localPosition.x.toRawBits())
            out.add(node.localPosition.y.toRawBits())
            out.add(node.localPosition.z.toRawBits())
            for (child in node.children) walk(child)
        }
        for (root in pose.roots) walk(root)
        return out
    }

    private fun realizedJoints(pose: SkeletonPose): Set<Joint> =
        Joint.entries.filterTo(mutableSetOf()) { pose.limbRealizedLimbs and (1L shl it.index) != 0L }

    /** Total registered realization events for the frame's cycle: windows + duplicate executions. */
    private fun realizationEvents(pose: SkeletonPose): Int =
        pose.limbSolverExecutions + pose.limbDuplicateRealizations

    private fun assertR5Violation(violation: Throwable?, context: String) {
        assertTrue(
            "$context — expected the engine's single-active-solver enforcement to reject the " +
                "frame with an R5 violation. Observed: " +
                (violation?.let { "${it::class.simpleName}: ${it.message}" } ?: "no violation raised"),
            violation is IllegalStateException && violation.message.orEmpty().contains("R5 violation")
        )
    }

    /** Field-wise (raw-bit) comparison of two declarations — `Vector3` has no value equality. */
    private fun assertSameDeclaration(a: WorldTarget, b: WorldTarget) {
        assertEquals("joint", a.joint, b.joint)
        assertEquals(
            "world target",
            listOf(a.world.x.toRawBits(), a.world.y.toRawBits(), a.world.z.toRawBits()),
            listOf(b.world.x.toRawBits(), b.world.y.toRawBits(), b.world.z.toRawBits())
        )
        assertEquals(
            "authored pole",
            listOf(a.pole.x.toRawBits(), a.pole.y.toRawBits(), a.pole.z.toRawBits()),
            listOf(b.pole.x.toRawBits(), b.pole.y.toRawBits(), b.pole.z.toRawBits())
        )
        assertEquals("straight", a.straight, b.straight)
        assertEquals("declared length1", a.length1.toRawBits(), b.length1.toRawBits())
        assertEquals("declared length2", a.length2.toRawBits(), b.length2.toRawBits())
        assertTrue("declared constraint", a.constraint === b.constraint)
        assertTrue("declared lengths present (not the shorthand surface)", !a.length1.isNaN() && !a.length2.isNaN())
    }

    // ------------------------------------------------- 1. OFF: one authoring solver

    @Test
    fun inactiveConfigurationHasExactlyOneActiveSolver() {
        IK_STAGE_ACTIVE = false // deployed configuration: the authoring bake realizes

        val pose = LimbRealizationProbe(1).build(ctx)

        assertEquals("one authoring realization window", 1, pose.limbSolverExecutions)
        assertEquals("no limb realized twice", 0, pose.limbDuplicateRealizations)
        assertEquals(
            "the realized limb is registered as evidence",
            setOf(Joint.ANKLE_F), realizedJoints(pose)
        )
        assertEquals("exactly one registered realization event", 1, realizationEvents(pose))

        // The engine-side implementation contributes nothing while the configuration disables it:
        // no window, no node write, no evidence.
        val localsBefore = nodeLocalBits(pose)
        IkStage.apply(pose, def)
        assertEquals("zero engine-side realization windows", 1, pose.limbSolverExecutions)
        assertEquals("zero engine-side duplicate executions", 0, pose.limbDuplicateRealizations)
        assertEquals("the gated stage must write no limb geometry", localsBefore, nodeLocalBits(pose))

        // ... and the frame is accepted by the pipeline's enforcement.
        SkeletonPipeline(def).produceFrame(LimbRealizationProbe(1).build(ctx))
    }

    // ---------------------------------------------------- 2. ON: one engine solver

    @Test
    fun activeConfigurationHasExactlyOneActiveSolver() {
        IK_STAGE_ACTIVE = true // activated configuration: the engine stage realizes

        val pose = LimbRealizationProbe(1).build(ctx)

        // The gated authoring bake keeps its REGISTRATION effect and drops its realization.
        assertEquals("the gated bake realizes nothing", 0, pose.limbSolverExecutions)
        assertEquals("no authoring realization event", 0, pose.limbDuplicateRealizations)
        assertEquals("no limb realized by the bake", emptySet<Joint>(), realizedJoints(pose))
        assertEquals("the bake still registers its declared Limb Target", 1, pose.limbTargets.size)

        // The engine-side implementation realizes exactly once.
        IkStage.apply(pose, def)
        assertEquals("one engine-side realization window", 1, pose.limbSolverExecutions)
        assertEquals("no limb realized twice", 0, pose.limbDuplicateRealizations)
        assertEquals("the realized limb is registered as evidence", setOf(Joint.ANKLE_F), realizedJoints(pose))
        assertEquals("exactly one registered realization event", 1, realizationEvents(pose))

        // ... and the frame is accepted by the pipeline's enforcement.
        SkeletonPipeline(def).produceFrame(LimbRealizationProbe(1).build(ctx))
    }

    // ------------------------------------------- 3. counterfactual: two executions

    @Test
    fun doubleRealizationFailsEvenWithIdenticalPose() {
        // (a) Deployed configuration: the SAME limb realized twice by the same implementation
        // inside one build cycle. Identical declared inputs ⇒ identical output.
        IK_STAGE_ACTIVE = false
        val single = LimbRealizationProbe(1).build(ctx)
        val double = LimbRealizationProbe(2).build(ctx)
        assertEquals(
            "premise: the double-realization frame is raw-bit identical to the single-realization " +
                "frame (all 33 joints, all stamps) — the counterfactual is not output-distinguishable",
            jointBits(single), jointBits(double)
        )
        assertEquals(
            "premise: both frames carry the SAME window count (one implementation window)",
            1, double.limbSolverExecutions
        )
        assertEquals(
            "the second execution of ANKLE_F is execution evidence",
            1, double.limbDuplicateRealizations
        )
        assertEquals("two realization events for one limb", 2, realizationEvents(double))

        val authoringViolation = runCatching {
            SkeletonPipeline(def).produceFrame(LimbRealizationProbe(2).build(ctx))
        }.exceptionOrNull()
        assertR5Violation(authoringViolation, "flag-OFF: ANKLE_F realized twice in one build cycle")
        assertTrue(
            "the rejection must report the observed execution count (2 events) so the evidence, " +
                "not the pose, is what failed the frame: ${authoringViolation?.message}",
            authoringViolation?.message.orEmpty().contains("total realization events = 2")
        )

        // (b) Activated configuration: the duplicated declaration reaches the engine stage, whose
        // realization loop has no per-joint guard — two executions of ONE limb in ONE window, again
        // with identical output.
        IK_STAGE_ACTIVE = true
        val singleWindow = LimbRealizationProbe(1).build(ctx)
        IkStage.apply(singleWindow, def)
        SkeletonPose.fromHierarchy(singleWindow.roots, singleWindow)

        val staged = LimbRealizationProbe(2).build(ctx)
        val duplicates = staged.limbTargets.filter { it.joint == Joint.ANKLE_F }
        assertEquals("the stage receives the duplicated declaration", 2, duplicates.size)
        assertSameDeclaration(duplicates[0], duplicates[1])
        IkStage.apply(staged, def)
        SkeletonPose.fromHierarchy(staged.roots, staged)
        assertEquals(
            "premise: the duplicated realization writes the identical frame (single vs double " +
                "declaration of ANKLE_F through the same stage window)",
            jointBits(singleWindow), jointBits(staged)
        )
        assertEquals("one stage window", 1, staged.limbSolverExecutions)
        assertEquals("two executions of one limb", 1, staged.limbDuplicateRealizations)
        assertEquals("two realization events", 2, realizationEvents(staged))

        val stageViolation = runCatching {
            SkeletonPipeline(def).produceFrame(LimbRealizationProbe(2).build(ctx))
        }.exceptionOrNull()
        assertR5Violation(stageViolation, "flag-ON: ANKLE_F realized twice inside one stage window")
    }

    // ------------------------------------------------------ 4. per-build-cycle scope

    @Test
    fun executionEvidenceResetsPerBuildCycle() {
        IK_STAGE_ACTIVE = false
        val probe = LimbRealizationProbe(1)

        // Frame N: a legitimately realized frame is accepted, and the pipeline consumes the evidence.
        val first = probe.build(ctx)
        assertEquals(1, first.limbSolverExecutions)
        assertEquals(setOf(Joint.ANKLE_F), realizedJoints(first))
        SkeletonPipeline(def).produceFrame(first)

        // Frame N+1: rebuilding the SAME carrier starts a fresh count and a fresh realization set.
        val second = probe.build(ctx)
        assertEquals("a rebuilt carrier starts a fresh window count", 1, second.limbSolverExecutions)
        assertEquals("no stale duplicate evidence survives the frame", 0, second.limbDuplicateRealizations)
        assertEquals("the realized-limb set is per cycle", setOf(Joint.ANKLE_F), realizedJoints(second))
        SkeletonPipeline(def).produceFrame(second)

        // Two consecutive builds WITHOUT an intervening frame cannot sum into one ownership failure.
        val rebuilt = LimbRealizationProbe(1)
        rebuilt.build(ctx)
        val third = rebuilt.build(ctx)
        assertEquals(
            "a fresh authoring cycle RE-ARMS the counter instead of accumulating it",
            1, third.limbSolverExecutions
        )
        assertEquals(0, third.limbDuplicateRealizations)
        SkeletonPipeline(def).produceFrame(third)

        // Activated configuration: the same re-arm must hold for the stage-owned evidence — a
        // second frame of the same carrier (no rebuild) must not read the first frame's realized
        // limbs as a duplicate execution.
        IK_STAGE_ACTIVE = true
        val instrument = MiddleSplitPose()
        val built = instrument.build(ctx)
        SkeletonPipeline(def).produceFrame(built)
        val rebuiltInstrument = instrument.build(ctx)
        assertEquals("a rebuilt instrument opens with no realization evidence", 0, rebuiltInstrument.limbSolverExecutions)
        assertEquals(0, rebuiltInstrument.limbDuplicateRealizations)
        assertEquals("no realized-limb evidence leaks from the previous cycle", emptySet<Joint>(), realizedJoints(rebuiltInstrument))
        SkeletonPipeline(def).produceFrame(rebuiltInstrument) // accepted: fresh window, no duplicates
    }

    // ------------------------------------- 5. the validation instrument is not a solver

    @Test
    fun validationFixtureDoesNotBecomeSecondRuntimeSolver() {
        // A validation instrument (BaseValidationPose family) authors Limb Targets and realizes
        // them through the SAME single registered implementation as any other pose. It must never
        // add a second solver window or a duplicate realization of a limb.
        IK_STAGE_ACTIVE = true
        val activated = MiddleSplitPose().build(ctx)
        assertEquals("the validation bake realizes nothing under the active stage", 0, activated.limbSolverExecutions)
        assertEquals(0, activated.limbDuplicateRealizations)
        assertEquals("no limb realized by the validation bake", emptySet<Joint>(), realizedJoints(activated))
        val declared = activated.limbTargets.map { it.joint }.toSet()
        assertEquals("all four limbs declared", 4, declared.size)
        SkeletonPipeline(def).produceFrame(activated) // exactly one engine realization → accepted

        IK_STAGE_ACTIVE = false
        val deployed = MiddleSplitPose().build(ctx)
        assertEquals("the validation bake IS the one active solver while the stage is off", 1, deployed.limbSolverExecutions)
        assertEquals("and it realizes each declared limb exactly once", 0, deployed.limbDuplicateRealizations)
        assertEquals("every declared limb is registered as realized", declared, realizedJoints(deployed))
        SkeletonPipeline(def).produceFrame(deployed)

        // Mixing the instrument into a frame must not turn it into a second solver: the deployed
        // configuration counts exactly one authoring window even though the instrument carries
        // Contact Declarations (the Phase-2 settlement re-solve is not a limb-solver window).
        val withContacts = MiddleSplitPose().build(ctx)
        assertTrue("the instrument declares contacts", withContacts.contacts.isNotEmpty())
        assertEquals(1, withContacts.limbSolverExecutions)
        SkeletonPipeline(def).produceFrame(withContacts)
    }

    // ------------------------------------------------------------ premise & anchors

    @Test
    fun doubleRealizationIsObservationallyEquivalentToSingleRealization() {
        IK_STAGE_ACTIVE = false
        val singleProbe = LimbRealizationProbe(1)
        val single = singleProbe.build(ctx)
        val doubleProbe = LimbRealizationProbe(2)
        val double = doubleProbe.build(ctx)

        assertEquals("fixture: one realization", 1, singleProbe.solverInvocations)
        assertEquals("fixture: two realizations", 2, doubleProbe.solverInvocations)

        // OUTPUT equivalence — the premise of the counterfactual: the second realization changed
        // nothing observable. Raw bits, not a float tolerance.
        assertEquals(
            "the double-realization frame must be raw-bit identical to the single-realization " +
                "frame (identical declared inputs ⇒ identical writes)",
            jointBits(single), jointBits(double)
        )
        assertEquals(
            "realization stamps must read identically (clamp / dropped / verified)",
            listOf(
                single.maxIkClampAmount.toRawBits(),
                single.straightIntentDropped.hashCode(),
                single.boneLengthsVerified.hashCode()
            ),
            listOf(
                double.maxIkClampAmount.toRawBits(),
                double.straightIntentDropped.hashCode(),
                double.boneLengthsVerified.hashCode()
            )
        )
        // The only observable delta is REGISTRATION (intent), not geometry: the second bake appends
        // a second declaration of the very same limb.
        assertEquals("single realization registers one target", 1, single.limbTargets.size)
        assertEquals("double realization registers the same limb twice", 2, double.limbTargets.size)
        assertEquals(Joint.ANKLE_F, double.limbTargets[0].joint)
        assertEquals(Joint.ANKLE_F, double.limbTargets[1].joint)
        assertSameDeclaration(double.limbTargets[0], double.limbTargets[1])

        // Anti-vacuity: the limb was genuinely realized (it bends off the root→target axis and
        // keeps the proximal bone length), so the byte comparison is not two untouched trees.
        val axis = FIXTURE_TARGET.minus(FIXTURE_ROOT).normalize()
        val off = single.getJoint(Joint.KNEE_F).minus(FIXTURE_ROOT)
        val along = off.dot(axis)
        val perp = Vector3(off.x - axis.x * along, off.y - axis.y * along, off.z - axis.z * along)
        assertTrue("anti-vacuity: the realized limb must genuinely bend", perp.mag() > 1f)
        assertEquals(
            "anti-vacuity: proximal bone length preserved by the realization",
            def.thighLength, single.getJoint(Joint.KNEE_F).minus(FIXTURE_ROOT).mag(), 1e-3f
        )
    }

    @Test
    fun secondSolverWindowIsRejectedByTheSameEnforcement() {
        // Anti-vacuity anchor: the enforcement is NOT dead code — the shape it was originally built
        // for (two realizations in two DIFFERENT windows, here: an extra direct stage call plus the
        // pipeline's own window) is still rejected. This isolates the WP-G gap to GRANULARITY
        // (per-window vs per-execution), not to a missing check.
        IK_STAGE_ACTIVE = true
        val violation = runCatching {
            val carrier = LimbRealizationProbe(1).build(ctx)
            IkStage.apply(carrier, def) // window #1
            SkeletonPipeline(def).produceFrame(carrier) // window #2 → 2 windows
        }.exceptionOrNull()
        assertR5Violation(violation, "two solver windows in one frame")
    }

    @Test
    fun realizationEvidenceMaskIsOneBitPerJointIndex() {
        // The per-execution evidence is a `Long` bitmask keyed by `Joint.index`; a joint
        // enumeration that no longer fits (or reused indices) would silently alias two limbs.
        assertEquals("joint indices must be dense and unique", Joint.entries.size, Joint.entries.map { it.index }.distinct().size)
        assertTrue("the joint enumeration must fit one Long", Joint.entries.maxOf { it.index } < 64)
        assertFalse("no realized limb before any realization", LimbRealizationProbe(0).build(ctx).let { realizedJoints(it).isNotEmpty() })
    }

    // ------------------------------------------------------------------ fixture constants

    private companion object {
        // Bent, reachable leg target (d ≈ 128 < 0.9·(L1+L2) ≈ 189 on DEFAULT_ADULT) so the solve
        // genuinely places the knee off-axis instead of clamping the chain straight.
        val FIXTURE_ROOT = Vector3(0f, 220f, 0f)
        val FIXTURE_TARGET = Vector3(80f, 120f, 0f)
        val FIXTURE_POLE = Vector3(0f, 0f, 1f)
    }
}
