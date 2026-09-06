package com.monkfitness.app.arch

import com.monkfitness.app.animation.Camera
import com.monkfitness.app.animation.ExerciseValidator
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.ProjectedSkeleton
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonEngine
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SkeletonProjector
import com.monkfitness.app.animation.SkeletonStyle
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.ValidatorConfig
import com.monkfitness.app.poses.ArmCirclesPose
import com.monkfitness.app.poses.StandardPushUpPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P9) — R9 (+R15 observer clause)
 * observer-isolation lock-in. TEST-ONLY: the plan states the current API shape already
 * prevents observer writes and names the equality test as P9's enforcement mechanism, so
 * this file adds the permanent regression net and NOTHING else — no production change, no
 * immutability wrapper, no runtime guard, no freeze mechanism, no new state category.
 *
 * RFC §5 R9: ExerciseValidator reads Published Pose State, Author Intent ranges, and the
 * Frame Context; it never writes the carrier, never derives geometry, and never drives
 * execution. RFC §5 R15(ii): additional read-only observers may consume Published Pose
 * State (via the Finalized Pose) at any time WITHOUT producing state and without new state
 * categories. RFC §3.3 lifetime: once the publish boundary completes (P8 structuralized
 * it), the Finalized Pose carries Published Pose State to its consumers — ExerciseValidator
 * and the Rendering layer today, N observers by the R15(ii) allowance.
 *
 * Published Pose State snapshot scope (authoritative, RFC §3.3 + §4.4):
 *  - the final world transforms of the 31 non-alias nodes (the `joints`/`rotations` carrier
 *    arrays minus WRIST_A/WRIST_P, which alias HAND_A/HAND_P per §2.1 and carry no
 *    independent state), captured from the carrier — the Finalized Pose is Published Pose
 *    State's delivery handle, and the Finalizer's extremity derivation writes published
 *    values directly into the carrier;
 *  - the COMPLETE Validation Stamp set of §4.4 as merged on the published carrier,
 *    including the multi-producer merged values (Clamp max-merge, Straight-Intent-Dropped
 *    OR-merge, Bone-Lengths AND-merge) and the Finalizer-owned per-hip Hip ROM channels.
 * NOT in scope (explicitly excluded): carrier implementation/debug bookkeeping that is not
 * Published Pose State content — `settlementResult` (P3 unpublished section), the P8
 * debug-only finalizer marker and `buildCycleToken`, the P4 `limbSolverExecutions`
 * counter, node-tree internals, and the Frame Context carriers.
 *
 * Comparison is exact bit-for-bit (`Float.toRawBits`), never epsilon: this is an isolation
 * test, so ANY observer write — down to a sign flip — must be detected. The snapshot is a
 * flat list of `NAME=value` lines and the equality failure prints the changed entries, so a
 * violation names the exact transform channel or stamp that an observer touched.
 *
 * Fixtures go through the REAL `SkeletonPipeline` (P8 publish tail live). ArmCirclesPose
 * enters the ConstraintSolver (STANDING posture intent) so the merged solver-family stamps
 * are genuinely non-default on its published carrier; StandardPushUpPose is the
 * contact-less CUSTOM family (solver skipped, Finalizer stamps only). A non-vacuity guard
 * asserts each snapshot carries real published content — if a fixture ever stops reaching
 * the solver/publish path, the guard fails instead of letting the equality assertions pass
 * vacuously (P2 anti-vacuity pattern).
 *
 * Second observer / R15(ii): the existing projection path stands in for the additional
 * read-only observer. The test intent is isolation, NOT observer output correctness:
 * observer #2 chains onto the same finalized carrier AFTER observer #1, and the post-chain
 * snapshot must still equal the pre-observation snapshot — adding another observer must not
 * mutate Published Pose State. No new production observer subsystem is invented for the
 * allowance.
 */
class ObserverIsolationTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    private fun ctx(progress: Float) = PoseContext(
        progress = progress, side = Side.RIGHT, definition = definition,
        deltaTime = 1f / 60f, cycleDuration = 2500f
    )

    /** Real pipeline, real publish tail (P8): build → Runtime Context Injection → solve → finalize → publish. */
    private fun finalizedCarrier(builder: PoseBuilder, progress: Float = 0.5f): SkeletonPose =
        SkeletonPipeline(definition).produceFrame(builder, ctx(progress)).pose

    // The two ALIAS identifiers (§2.1) — Published Pose State covers the 31 REAL nodes.
    private val aliasJoints = setOf(Joint.WRIST_A, Joint.WRIST_P)

    private val stampedHips = listOf(Joint.HIP_F, Joint.HIP_B)

    /**
     * Deep snapshot of the entire Published Pose State carried by [pose]: transforms of the
     * 31 non-alias nodes (world position + world axis-angle rotation, every float channel as
     * raw bits) plus every §4.4 Validation Stamp on the carrier, including the merged
     * solver-family values and both hips' Hip ROM stamp channels. Flat `NAME=value` lines so
     * any diff pinpoints which transform channel or stamp changed.
     */
    private fun snapshotPublishedPoseState(pose: SkeletonPose): List<String> {
        val entries = ArrayList<String>(31 * 7 + 15)

        // 1) §3.3 "the final world transforms of all 31 nodes after Flatten" — carrier values.
        for (joint in Joint.entries) {
            if (joint in aliasJoints) continue
            val p = pose.getJoint(joint)
            entries += "transform:${joint.name}.pos.x=${p.x.toRawBits()}"
            entries += "transform:${joint.name}.pos.y=${p.y.toRawBits()}"
            entries += "transform:${joint.name}.pos.z=${p.z.toRawBits()}"
            val r = pose.getJointRotation(joint)
            entries += "transform:${joint.name}.rot.axis.x=${r.axis.x.toRawBits()}"
            entries += "transform:${joint.name}.rot.axis.y=${r.axis.y.toRawBits()}"
            entries += "transform:${joint.name}.rot.axis.z=${r.axis.z.toRawBits()}"
            entries += "transform:${joint.name}.rot.angle=${r.angle.toRawBits()}"
        }

        // 2) §4.4 complete Validation Stamp set (merged values exactly as published).
        entries += "stamp:ClampStamp=${pose.maxIkClampAmount.toRawBits()}"
        entries += "stamp:StraightIntentDropped=${pose.straightIntentDropped}"
        entries += "stamp:BoneLengthsVerified=${pose.boneLengthsVerified}"
        entries += "stamp:RootTranslationDelta=${pose.rootTranslationDelta.toRawBits()}"
        entries += "stamp:RootRotationDelta=${pose.rootRotationDelta.toRawBits()}"
        entries += "stamp:BilateralSymmetryDelta=${pose.bilateralSymmetryDelta.toRawBits()}"
        entries += "stamp:BilateralOppositeBend=${pose.bilateralOppositeBend}"
        for (hip in stampedHips) {
            val s = pose.hipRomStamps[hip]
            if (s == null) {
                entries += "stamp:HipRom.${hip.name}=<absent>"
            } else {
                entries += "stamp:HipRom.${hip.name}.excursion=${s.excursionDegrees.toRawBits()}"
                entries += "stamp:HipRom.${hip.name}.sagittal=${s.sagittalDegrees.toRawBits()}"
                entries += "stamp:HipRom.${hip.name}.frontal=${s.frontalDegrees.toRawBits()}"
                entries += "stamp:HipRom.${hip.name}.axial=${s.axialDegrees.toRawBits()}"
            }
        }
        return entries
    }

    /**
     * Anti-vacuity guard (P2 pattern): the snapshot must hold genuinely PUBLISHED, non-default
     * content — 31 real nodes with no aliases, a populated Hip ROM stamp pair, a non-zero
     * published world position, and (when [expectMergedSolverStamps]) non-default merged
     * solver-family stamps proving the ConstraintSolver write window really executed for this
     * fixture. If the pipeline or fixture drifts away from the solver/publish path, THIS fails
     * before the equality assertions can become vacuously green.
     */
    private fun assertPublishedStateIsNonVacuous(snapshot: List<String>, expectMergedSolverStamps: Boolean) {
        assertEquals("Published Pose State = 31 non-alias nodes x 7 channels + 15 stamp lines", 31 * 7 + 15, snapshot.size)
        assertTrue("alias identifiers must never enter Published Pose State scope", snapshot.none { it.contains("WRIST_") })
        assertEquals("both hips must carry a Hip ROM stamp from the publish tail", 8, snapshot.count { it.startsWith("stamp:HipRom.") && !it.endsWith("<absent>") })
        val anyNonZeroPos = (0 until 31).any { i ->
            snapshot[i * 7 + 1].substringAfter('=').toInt() != 0f.toRawBits()
        }
        assertTrue("carrier must hold a non-zero published world position", anyNonZeroPos)
        assertTrue("BoneLengthsVerified AND-merge must have run (optimistic default flips only through a solve window)",
            snapshot.contains("stamp:BoneLengthsVerified=true"))
        if (expectMergedSolverStamps) {
            val clamp = line(snapshot, "stamp:ClampStamp").toInt()
            val rtd = line(snapshot, "stamp:RootTranslationDelta").toInt()
            assertTrue(
                "the fixture must enter the ConstraintSolver so the merged multi-producer stamps are " +
                    "non-default on the published carrier (clamp=${clamp.toFloat()} rtd=${rtd.toFloat()}) — " +
                    "otherwise the isolation assertions below are vacuous for the solver-family stamps",
                clamp != 0f.toRawBits() && rtd != 0f.toRawBits()
            )
        }
    }

    private fun line(snapshot: List<String>, key: String): String =
        snapshot.first { it.startsWith("$key=") }.substringAfter('=')

    /** Exact equality with a pinpointing diff: names the first changed entries. */
    private fun assertPublishedPoseStateUnchanged(label: String, before: List<String>, after: List<String>) {
        if (before == after) return
        val changed = before.filterIndexed { i, v -> after.getOrNull(i) != v }
        val message = StringBuilder(
            "R9/R15(ii) observer-isolation violation: $label mutated Published Pose State. "
        ).append("changed entries (${changed.size} of ${before.size}): ")
            .append(changed.take(6).joinToString(" | "))
        if (before.size != after.size) message.append(" (size ${before.size} -> ${after.size})")
        assertEquals(message.toString(), before, after)
    }

    private fun validatingObserver(pose: SkeletonPose, previous: SkeletonPose? = null, prePrevious: SkeletonPose? = null) {
        val report = ExerciseValidator(ValidatorConfig.ENGINEERING_VALIDATION).validate(
            pose = pose,
            definition = definition,
            environment = pose.environment,
            camera = Camera(),
            width = 1000f,
            height = 1000f,
            previousPose = previous,
            prePreviousPose = prePrevious,
            deltaTime = if (previous == null) 0f else 1f / 60f
        )
        // The observer actually ran over the finalized carrier (anti-vacuity for the call itself).
        assertNotNull("validator returned no report", report)
        assertEquals("all 18 rules reported", 18, report.results.size)
    }

    private fun projectingObserver(pose: SkeletonPose) {
        val buffer = ProjectedSkeleton()
        SkeletonProjector().project(
            pose = pose,
            camera = Camera(),
            engine = SkeletonEngine(definition, SkeletonStyle.DEFAULT),
            width = 1000f,
            height = 1000f,
            buffer = buffer,
            groundLevel = pose.environment.ground.level
        )
        // The observer ran (its OWN output buffer is populated from the pose). Output
        // correctness is NOT what this suite asserts — only that producing it left the
        // observed state untouched.
        assertTrue(
            "projection produced no output — the observer never executed",
            buffer.joints[Joint.HEAD_POS.index].x != 0f || buffer.joints[Joint.HEAD_POS.index].y != 0f
        )
    }

    /** Independent by-value clone of a published carrier (pipeline Frame History copyFrom idiom). */
    private fun detachedCopy(pose: SkeletonPose): SkeletonPose = SkeletonPose().apply { copyFrom(pose) }

    // ---------------------------------------------------------------------------------
    // 1 — R9: ExerciseValidator isolation. Solver-entered fixture (merged multi-producer
    //     stamps live on the carrier) AND the contact-less Finalizer-stamps-only family.
    // ---------------------------------------------------------------------------------

    @Test
    fun exerciseValidatorLeavesPublishedPoseStateBitIdentical() {
        val pose = finalizedCarrier(ArmCirclesPose())
        val before = snapshotPublishedPoseState(pose)
        assertPublishedStateIsNonVacuous(before, expectMergedSolverStamps = true)

        validatingObserver(pose)

        assertPublishedPoseStateUnchanged("after ExerciseValidator.validate", before, snapshotPublishedPoseState(pose))
    }

    @Test
    fun exerciseValidatorLeavesPublishedPoseStateBitIdenticalContactlessFamily() {
        // The CUSTOM / contact-less family: the solver is skipped, so the published stamp
        // set is the Finalizer-owned subset — the net must pin that family too.
        val pose = finalizedCarrier(StandardPushUpPose())
        val before = snapshotPublishedPoseState(pose)
        assertPublishedStateIsNonVacuous(before, expectMergedSolverStamps = false)

        validatingObserver(pose)

        assertPublishedPoseStateUnchanged("after validate (push-up family)", before, snapshotPublishedPoseState(pose))
    }

    @Test
    fun exerciseValidatorWithFrameHistoryLeavesPublishedPoseStateBitIdentical() {
        // The history-reading rule family (HAND_SLIDING, POSITION_DISCONTINUITY,
        // VELOCITY_DISCONTINUITY, ACCELERATION_SPIKE) must sit inside the net: genuine
        // prior frames feed validate() alongside the observed carrier. Prior frames are
        // detached by-value copies (sequential playback reuses one carrier per builder —
        // the same discipline the pipeline's own commitFrameHistory applies).
        val pipeline = SkeletonPipeline(definition)
        val builder = ArmCirclesPose()
        val prePrevious = detachedCopy(pipeline.produceFrame(builder, ctx(0.25f)).pose)
        val previous = detachedCopy(pipeline.produceFrame(builder, ctx(0.35f)).pose)
        val observed = pipeline.produceFrame(builder, ctx(0.45f)).pose

        val before = snapshotPublishedPoseState(observed)
        assertPublishedStateIsNonVacuous(before, expectMergedSolverStamps = true)

        validatingObserver(observed, previous = previous, prePrevious = prePrevious)

        assertPublishedPoseStateUnchanged("after validate() with Frame History", before, snapshotPublishedPoseState(observed))
    }

    // ---------------------------------------------------------------------------------
    // 2 — Rendering-layer observer: SkeletonProjector isolation (writes only its own
    //     ProjectedSkeleton buffers).
    // ---------------------------------------------------------------------------------

    @Test
    fun skeletonProjectorLeavesPublishedPoseStateBitIdentical() {
        val pose = finalizedCarrier(ArmCirclesPose())
        val before = snapshotPublishedPoseState(pose)
        assertPublishedStateIsNonVacuous(before, expectMergedSolverStamps = true)

        projectingObserver(pose)

        assertPublishedPoseStateUnchanged("after SkeletonProjector.project", before, snapshotPublishedPoseState(pose))
    }

    @Test
    fun skeletonProjectorLeavesPublishedPoseStateBitIdenticalContactlessFamily() {
        val pose = finalizedCarrier(StandardPushUpPose())
        val before = snapshotPublishedPoseState(pose)
        assertPublishedStateIsNonVacuous(before, expectMergedSolverStamps = false)

        projectingObserver(pose)

        assertPublishedPoseStateUnchanged("after project (push-up family)", before, snapshotPublishedPoseState(pose))
    }

    @Test
    fun repeatedProjectionLeavesPublishedPoseStateBitIdentical() {
        // The renderer pattern: successive render passes observe one long-lived carrier —
        // every pass must see identical Published Pose State.
        val pose = finalizedCarrier(ArmCirclesPose())
        val before = snapshotPublishedPoseState(pose)
        assertPublishedStateIsNonVacuous(before, expectMergedSolverStamps = true)
        repeat(3) { projectingObserver(pose) }
        assertPublishedPoseStateUnchanged("after 3x SkeletonProjector.project", before, snapshotPublishedPoseState(pose))
    }

    // ---------------------------------------------------------------------------------
    // 3 — R15(ii): the architectural allowance for an ADDITIONAL read-only observer.
    //     The existing projection path is observer #2 — chained AFTER observer #1 on the
    //     same finalized carrier. Adding another observer must not mutate Published Pose
    //     State; observation order must not matter (observers produce no state). Isolation
    //     only — observer output correctness is deliberately NOT asserted here.
    // ---------------------------------------------------------------------------------

    @Test
    fun secondReadOnlyObserverDoesNotMutatePublishedPoseState() {
        val pose = finalizedCarrier(ArmCirclesPose())
        val before = snapshotPublishedPoseState(pose)
        assertPublishedStateIsNonVacuous(before, expectMergedSolverStamps = true)

        // Observer #1 (R9 validator).
        validatingObserver(pose)
        // Pin state BEFORE observer #2 runs — otherwise #2's verdict could mask #1's write.
        assertPublishedPoseStateUnchanged("after observer #1 (validator)", before, snapshotPublishedPoseState(pose))

        // Observer #2 (R15(ii) additional observer via the existing rendering path).
        projectingObserver(pose)
        assertPublishedPoseStateUnchanged("after observer #2 (projector)", before, snapshotPublishedPoseState(pose))
    }

    @Test
    fun observerChainOrderDoesNotAffectPublishedPoseState() {
        // R15(ii) observers do not sequence-dependently produce state: the reversed chain
        // on an identically-produced carrier must preserve equality as well.
        val pose = finalizedCarrier(ArmCirclesPose())
        val before = snapshotPublishedPoseState(pose)
        assertPublishedStateIsNonVacuous(before, expectMergedSolverStamps = true)

        projectingObserver(pose)
        validatingObserver(pose)

        assertPublishedPoseStateUnchanged("after reversed observer chain", before, snapshotPublishedPoseState(pose))
    }
}
