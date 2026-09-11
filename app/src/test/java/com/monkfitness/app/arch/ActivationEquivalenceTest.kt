package com.monkfitness.app.arch

import com.monkfitness.app.animation.BasePose
import com.monkfitness.app.animation.ConstraintSolver
import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.SkeletonNode
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportPoint
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.poses.AirSquatPose
import com.monkfitness.app.poses.AlternatingForwardLungesPose
import com.monkfitness.app.poses.ArmCirclesPose
import com.monkfitness.app.poses.BirdDogPose
import com.monkfitness.app.poses.BurpeePose
import com.monkfitness.app.poses.CatCowPose
import com.monkfitness.app.poses.CossackSquatPose
import com.monkfitness.app.poses.CouchStretchPose
import com.monkfitness.app.poses.DeadBugPose
import com.monkfitness.app.poses.DynamicWorldsGreatestStretchPose
import com.monkfitness.app.poses.FacePullPose
import com.monkfitness.app.poses.GluteBridgePose
import com.monkfitness.app.poses.HalfKneelingStretchPose
import com.monkfitness.app.poses.HamstringStretchPose
import com.monkfitness.app.poses.HipCarsPose
import com.monkfitness.app.poses.JumpSquatPose
import com.monkfitness.app.poses.KettlebellSwingPose
import com.monkfitness.app.poses.LatStretchPose
import com.monkfitness.app.poses.LegRaisePose
import com.monkfitness.app.poses.MountainClimberPose
import com.monkfitness.app.poses.PelvicTiltPose
import com.monkfitness.app.poses.PikePushUpPose
import com.monkfitness.app.poses.ProneCobraStretchPose
import com.monkfitness.app.poses.QuadrupedThoracicRotationsPose
import com.monkfitness.app.poses.ReverseSnowAngelPose
import com.monkfitness.app.poses.ScapularRetractionPose
import com.monkfitness.app.poses.SquatPose
import com.monkfitness.app.poses.StandardPullUpPose
import com.monkfitness.app.poses.StandardPushUpPose
import com.monkfitness.app.poses.StaticForearmPlankPose
import com.monkfitness.app.poses.SupermanPose
import com.monkfitness.app.poses.ThoracicExtensionPose
import com.monkfitness.app.poses.WallSlidesPose
import com.monkfitness.app.validation.poses.DeadHangPose
import com.monkfitness.app.validation.poses.DeepOverheadSquatPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import com.monkfitness.app.validation.poses.PikeSitPose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P12 WP-H — **§12.9 cross-configuration equivalence harness** (R5 Activation / Limb-Solver
 * Ownership Transition).
 *
 * ---------------------------------------------------------------------------------------------
 * WHAT THIS HARNESS PROVES
 * ---------------------------------------------------------------------------------------------
 *
 * > The engine-owned Active Limb Solver path preserves the relevant observable semantics of the
 * > pre-activation authoring-owned path.
 *
 * Both configurations are executed through the **complete canonical pipeline** on the **same
 * declared intent**, with an **independent fresh build per configuration** (never the OFF output
 * fed into the ON run), and the comparison reaches the **finalized/published observable
 * boundary** — not `IkStage.apply` and not an intermediate pre-finalizer pose:
 *
 * ```text
 *   Configuration OFF   build()  ->  SkeletonPipeline.produceFrame  ->  published pose
 *   Configuration ON    build()  ->  SkeletonPipeline.produceFrame  ->  published pose
 *                       ^ same declared intent, fresh instances, one pipeline per observation
 * ```
 *
 * ---------------------------------------------------------------------------------------------
 * THE EQUIVALENCE RELATION (explicit — no blanket "approximately equal everything")
 * ---------------------------------------------------------------------------------------------
 *
 * | # | Dimension (section)                                     | Rule                 | Why |
 * |---|---------------------------------------------------------|----------------------|-----|
 * | 1 | `INTENT.limbTargets` — declared Limb Targets (joint, world, pole, straight, declared lengths, declared constraint, declared contact) | **RAW-BIT exact** | Intent is ownership-neutral: §12.5 acceptance requires registration effects to run in BOTH configurations (`BasePose.bakeIkLimb` / `BaseValidationPose.bakeIkLimb` register above the realization gate), and §12.4/B-3 requires the declared solve inputs to be the authoring bake's own values. A difference here is either a registration regression or a lossy decode. |
 * | 2 | `INTENT.contacts` — declared ContactSpecs (end/root/parentRotation/middle joints, target, pole, lengths, straight, constraint, contact) | **RAW-BIT exact** | Contact Declarations are Phase-2 settlement intent, not realization (WP-D gate-placement rule: `contacts.add` sits above the realization gate). The Phase-2 contact re-solve is R3 settlement ownership, which consumes exactly these declarations (RFC §5 R3). |
 * | 3 | `INTENT.contactPrecedence`, `INTENT.posture` | **exact** | Raw §1.1 declarations; the Solver's conflict-resolution and posture entry read them unchanged. |
 * | 4 | `ROOT.authored` — pelvis local transform as authored (captured before the frame) | **RAW-BIT exact** | Author Intent is frozen at build return (RFC §5 R1). |
 * | 5 | `ROOT.published` — published pelvis local + world transform | **RAW-BIT exact** | The published root is the R2 root-authority output; §12.7d requires the engine stage to move only `middle`/`end` limb locals, and the Pipeline's own Phase-1 check asserts the pelvis is bit-identical across the limb stage. |
 * | 6 | `PUBLISHED.transforms` — all 33 joints, world position + world rotation | **RAW-BIT exact** | RFC §6 Phase 4: publication completes with these transforms. Both implementations execute the same frozen responsibility set with the same declared inputs (R5: "two implementations of the same frozen responsibility set"), so the published frame is expected bit-exact — this is the strongest form of the claim, and where ownership legitimately moved raw bits the case is adjudicated in [ADJUDICATIONS] instead of loosened globally. |
 * | 7 | `PUBLISHED.nodes` — node tree structure + every node local transform | **RAW-BIT exact** | Detects a structural difference (a missing/extra node) as well as a differently-realized limb local, independent of FK. |
 * | 8 | `STAMPS` — the complete §4.4 set: Clamp Stamp, Straight-Intent-Dropped, Bone-Lengths-Verified, Root Translation Delta, Root Rotation Delta, Hip ROM stamp ×2 (4 angles each), Bilateral Symmetry Delta, Bilateral Opposite Bend | **RAW-BIT exact** | §4.4 fixes each stamp's producer and merge rule. The Active Limb Solver is the FIRST writer of the three solver-family stamps in both configurations; the ConstraintSolver (max/OR/AND, merge-once) and the Finalizer (sole producers) are configuration-independent. A stamp that changes only because realization is *recorded* differently is an adjudicated entry, never a tolerance. |
 * | 9 | `SETTLEMENT` — Settlement Result presence, settled root world transform, declared-contact joint list, conflict outcome, and the PUBLISHED world position of every settled contact end-effector | **RAW-BIT exact** | RFC §5 R3 (Settled-Contact Guarantee) + §4.3: the Settlement Result's membership is fixed and the settled contacts must survive Phase 3 finalization. This is the contact/settlement coverage. |
 * | 10 | `PUBLISH` — the published instance is a distinct carrier instance / frame published | **exact** | R11 transfer chain: the returned Finalized Pose is the buffer the Finalizer publishes from, never the input carrier. |
 * | 11 | `KINEMATIC` — the published kinematic-state marker (`isTransformsUpdated`) | **exact** | Added at WP-I: this was the ONE published field the observation did not compare, and it DID diverge — the engine stage consumes the build-window marker (§12.7a), so the finalizer's FK-refresh branch ran on every activated frame and wrote the truthful marker only onto its INPUT carrier (`copyFrom` had already captured the stale value into the published one). WP-I corrected the finalizer's bookkeeping (one write, no geometry change) and added this dimension so the equivalence claim covers the complete published state; a future divergence of the marker across configurations now fails here instead of hiding. |
 *
 * **Execution evidence is deliberately NOT an equivalence dimension.** The realization counters
 * (`limbSolverExecutions`, `limbRealizedLimbs`, `limbDuplicateRealizations`) are *per-configuration
 * instrumentation* — the bake's authoring window in one configuration, the stage window in the
 * other — so they are asserted as per-configuration PREMISES instead (exactly one window, never a
 * duplicate realization, in both configurations; §12.7a/§12.7b). Comparing them as if they were
 * observable output would be comparing the ownership mechanism with itself.
 *
 * There is exactly ONE tolerance-bearing place in this file and it is not a corpus comparison: the
 * §9 ConstraintSolver boundary test's contact-target distance is *not* compared at all — nothing in
 * the corpus is compared with a tolerance. [ADJUDICATIONS] is the only relaxation mechanism and it
 * starts EMPTY: any non-identical dimension is a failure until it is adjudicated here with a named
 * invariant, a named tolerance and a named reason (and an entry must also explain why it cannot
 * hide a real ownership regression — see the class KDoc of each adjudication).
 *
 * ---------------------------------------------------------------------------------------------
 * TDD SEQUENCE (this file is the RED artefact)
 * ---------------------------------------------------------------------------------------------
 *
 * RED was executed first, against unmodified production source, with every assertion strict. Every
 * resulting mismatch was classified (defect of this harness / genuine P12 ownership defect /
 * adjudicated legitimate difference); the classification and its evidence are recorded in the WP-H
 * report. GREEN then fixed only genuine P12 ownership/equivalence defects — no assertion was
 * loosened to make a comparison pass.
 *
 * ---------------------------------------------------------------------------------------------
 * LEAKAGE DISCIPLINE (§7 of the WP-H brief)
 * ---------------------------------------------------------------------------------------------
 *
 *  - a fresh `SkeletonPipeline` per observation (no Frame History reuse between observations);
 *  - a fresh pose instance per build (`factory()` is invoked inside the run lambda) — the OFF
 *    output is never the ON input;
 *  - realization evidence is read BEFORE the frame and settlement AFTER it, because
 *    `SkeletonPipeline.runStages` resets the evidence at frame end (by design);
 *  - `observationIsIndependentOfConfigurationOrder` runs every corpus case in BOTH directions
 *    (`OFF→ON` and `ON→OFF`) and requires the per-configuration observations to be identical;
 *  - the flag is restored in `@After` and in every test's `finally`.
 */
class ActivationEquivalenceTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalStage = IK_STAGE_ACTIVE

    // P12 WP-I (§12.8 disposition): this harness is CONFIGURATION-AGNOSTIC by construction — every
    // test sets the flag explicitly for each observation and restores it in `finally` / `@After` —
    // so its former WP-H premise ("the deployed default must still be flag-OFF") is gone. That
    // state-2 pin now lives in exactly ONE place: the §12.10 activation gate
    // (`ActivationGateTest.productionConfigurationIsStateThree`), which owns the deployed-state
    // claim. The corpus remains the §12.9 proof FOR THE ACTIVATED CONFIGURATION as well: with the
    // production default `true` it still compares flag-OFF (the legacy authoring configuration, a
    // valid R5-selectable configuration) against flag-ON and requires raw-bit identity of the
    // complete published observation.

    @After
    fun restoreStage() {
        IK_STAGE_ACTIVE = originalStage
    }

    private fun ctx(progress: Float) = PoseContext(progress, Side.LEFT, def)

    // =====================================================================================
    // 1. The explicit equivalence relation
    // =====================================================================================

    /**
     * The only relaxation mechanism in this file. EMPTY by construction: with no entry, every
     * dimension is compared under its declared rule (raw-bit exact / exact) and any difference
     * fails the harness. An entry is admissible only when it names (a) the invariant that
     * survives, (b) the tolerance, (c) the architectural justification and (d) why it cannot
     * hide a real ownership regression.
     *
     * WP-H RED/GREEN outcome: **no entry was required** — every corpus case in every progress is
     * raw-bit identical across the ownership configurations, so no case had to be relaxed. The
     * mechanism exists so that a future legitimate representation change is *adjudicated* here
     * rather than silently tolerated by a broad tolerance. It is asserted empty by
     * [equivalenceRelationHasNoUnusedRelaxation], so adding a relaxation without a matching
     * comment block that documents the four points above is itself a test failure.
     */
    private val adjudications: List<Adjudication> = emptyList()

    private data class Adjudication(
        val caseKey: String,
        val dimension: String,
        val rule: String,
        val invariant: String,
        val justification: String
    )

    // =====================================================================================
    // 2. Corpus (§12.9 representative cases; reachable branches, no fuzzing)
    // =====================================================================================

    private class Evidence(
        val windows: Int,
        val duplicates: Int,
        val realized: String
    ) {
        companion object {
            fun of(pose: SkeletonPose) = Evidence(
                windows = pose.limbSolverExecutions,
                duplicates = pose.limbDuplicateRealizations,
                realized = Joint.entries
                    .filter { pose.limbRealizedLimbs and (1L shl it.index) != 0L }
                    .joinToString(",") { it.name }
            )
        }
    }

    /** One produced frame: the built carrier, its pre-frame evidence, its authored root, the published pose. */
    private class BuiltFrame(
        val built: SkeletonPose,
        val evidenceBeforeFrame: Evidence,
        val authoredRoot: List<String>,
        val published: SkeletonPose
    )

    private class CorpusEntry(
        val name: String,
        val exercises: String,
        val progresses: List<Float>,
        val frames: Int = 1,
        /**
         * Whether the build template returns the CARRIER the authoring bake registered its
         * realization evidence on. Every production build returns `jointsBuffer` itself except
         * `BaseThoracicPose.finalizeThoracicPose`, which returns an independent snapshot
         * (`out.copyFrom(jointsBuffer)`) so that two samples of one builder cannot alias — and the
         * evidence fields are deliberately absent from `copyFrom` (P3 suppression: Published Pose
         * State never inherits instrumentation). For those three templates the flag-OFF authoring
         * window is therefore not observable on the returned carrier; FINDING F-1 in the class KDoc
         * records that limitation, and [snapshotReturningTemplatesCarryNoAuthoringEvidence] pins it.
         */
        val authoringEvidenceObservable: Boolean = true,
        val run: (SkeletonPipeline, PoseContext) -> BuiltFrame
    ) {
        fun key(progress: Float) = "$name@$progress"
    }

    private fun standard(
        name: String,
        exercises: String,
        progresses: List<Float> = THREE,
        frames: Int = 1,
        authoringEvidenceObservable: Boolean = true,
        factory: () -> PoseBuilder
    ) = CorpusEntry(name, exercises, progresses, frames, authoringEvidenceObservable) { pipeline, context ->
        runBuilder(pipeline, context, factory())
    }

    /**
     * The canonical observation path: `build()` (fresh instance) → runtime-context injection with
     * the pose's own metadata (exactly what the pipeline's builder overload does, see
     * [explicitInjectionPathMatchesTheBuilderEntryPoint]) → the complete stage chain → published
     * pose. The built carrier is retained so the settlement evidence and the pre-frame realization
     * evidence can be observed (the pipeline resets the evidence at frame end by design).
     */
    private fun runBuilder(pipeline: SkeletonPipeline, context: PoseContext, builder: PoseBuilder): BuiltFrame {
        val built = builder.build(context)
        val evidence = Evidence.of(built)
        val authoredRoot = rootLines(built)
        val supportPoints = HashSet<SupportPoint>()
        for (contact in builder.metadata.support.contacts) supportPoints.add(contact.point)
        val published = pipeline.produceFrame(built, builder.metadata.environment, supportPoints).pose
        return BuiltFrame(built, evidence, authoredRoot, published)
    }

    private fun corpus(): List<CorpusEntry> = listOf(
        // ---- §12.9 baseline fixtures (the plan's "3 baseline fixtures × reps") -----------------
        standard(
            "BASELINE.StandardPushUp",
            "contact pose (metadata support) entering Phase-2 settlement + support flattening",
            FIVE
        ) { StandardPushUpPose() },
        CorpusEntry(
            "BASELINE.SquatPosture",
            "posture-driven root authority (UNI-6 seed/settle; solver entered without contacts)",
            FIVE
        ) { pipeline, context ->
            val metadata = SquatPose().metadata
            val pose = SquatPose().build(context)
            SkeletonPose.IntentBuilder(pose).posture(PostureIntent.Kind.STANDING)
            val evidence = Evidence.of(pose)
            val authoredRoot = rootLines(pose)
            val supportPoints = HashSet<SupportPoint>()
            for (contact in metadata.support.contacts) supportPoints.add(contact.point)
            val published = pipeline.produceFrame(pose, metadata.environment, supportPoints).pose
            BuiltFrame(pose, evidence, authoredRoot, published)
        },
        standard(
            "BASELINE.ArmCircles",
            "contact-less posture-declared pose: solver runs via the posture branch, root displaced",
            FIVE
        ) { ArmCirclesPose() },

        // ---- A. reachable / BENT limb intent (arm) -------------------------------------------
        standard("BENT-ARM.FacePull", "bent arm intent realized through the registered bake") { FacePullPose() },
        standard("BENT-ARM.WallSlides", "bent arm intent (WP-C capture-only family)") { WallSlidesPose() },
        standard("BENT-ARM.HipCars", "bent arm intent (WP-C capture-only family)") { HipCarsPose() },
        standard("BENT-ARM.KettlebellSwing", "bent arm intent (WP-C reset family)") { KettlebellSwingPose() },
        standard("BENT-ARM.ScapularRetraction", "bent arm intent (WP-C reset family)") { ScapularRetractionPose() },
        standard("BENT-ARM.Burpee", "bent arm intent (WP-C capture-only family)") { BurpeePose() },

        // ---- A. reachable / BENT limb intent (leg) -------------------------------------------
        standard("BENT-LEG.AirSquat", "bent leg intent (squat family)") { AirSquatPose() },
        standard("BENT-LEG.CossackSquat", "bent leg intent (lunge family, asymmetric)") { CossackSquatPose() },
        standard("BENT-LEG.ForwardLunge", "bent leg intent (alternating lunge)") { AlternatingForwardLungesPose() },
        standard("BENT-LEG.BirdDog", "bent leg + bent arm intent (quadruped family)") { BirdDogPose() },

        // ---- B. STRAIGHT-limb intent (the validation instruments are the only straight authors)
        standard(
            "STRAIGHT-ARM.DeadHang",
            "straight-limb intent + straight-intent drop reading (settled-contact instrument)",
            FIVE
        ) { DeadHangPose() },
        standard(
            "STRAIGHT-LEG.MiddleSplit",
            "straight-leg probe whose target sits inside L1 -> bent fallback + straight-intent drop",
            FIVE
        ) { MiddleSplitPose() },
        standard(
            "STRAIGHT-LEG.PikeSit",
            "straight-leg intent with full-extension constraint + planted contact",
            FIVE
        ) { PikeSitPose() },
        standard(
            "STRAIGHT-ARM.DeepOverheadSquat",
            "straight/overhead arm intent with planted contact",
            FIVE
        ) { DeepOverheadSquatPose() },

        // ---- C. CONTACT / SETTLEMENT, including multi-frame (Frame History) sequences ---------
        standard(
            "CONTACT.Sequence.StandardPushUp",
            "3-frame sequence on ONE pipeline: settlement + support flatten + Frame History",
            THREE,
            frames = 3
        ) { StandardPushUpPose() },
        standard(
            "CONTACT.Sequence.MiddleSplit",
            "3-frame sequence on ONE pipeline: settled contacts survive finalization (R3) per frame",
            THREE,
            frames = 3
        ) { MiddleSplitPose() },

        // ---- planning-solve-derived intent (§12.4b, B-1 family) -------------------------------
        standard("PLAN.CouchStretch", "target composed from the sanctioned §12.4b planning solve") { CouchStretchPose() },
        standard("PLAN.HalfKneelingStretch", "target composed from the sanctioned §12.4b planning solve") { HalfKneelingStretchPose() },

        // ---- WP-D representation migrations (adjudicated differential; see class KDoc §10) -----
        standard("WPD.LatStretch", "hierarchy-built pose (authored-hierarchy conversion, parent frame)") { LatStretchPose() },
        standard("WPD.DeadBug", "world-position-built pose -> authored hierarchy (WP-D conversion)") { DeadBugPose() },
        standard("WPD.MountainClimber", "hierarchy-built pose (never a direct-solveIK consumer)") { MountainClimberPose() },
        standard("WPD.LegRaise", "world-position-built pose -> authored hierarchy (WP-D conversion)") { LegRaisePose() },
        standard("WPD.ReverseSnowAngel", "hierarchy-built sagittal-sweep pose (WP-D conversion)") { ReverseSnowAngelPose() },
        standard("WPD.GluteBridge", "hierarchy-built pose (pelvis tilt declared)") { GluteBridgePose() },
        standard("WPD.PelvicTilt", "world-position-built pose -> authored hierarchy (package bake)") { PelvicTiltPose() },
        standard("WPD.Superman", "world-position-built pose -> authored hierarchy (WP-D conversion)") { SupermanPose() },
        standard("WPD.CatCow", "world-position-built pose + authored spine direction (WP-D conversion)") { CatCowPose() },
        standard("WPD.QuadrupedThoracicRotations", "BaseThoracicPose arm bake via the package bake", authoringEvidenceObservable = false) { QuadrupedThoracicRotationsPose() },
        standard("WPD.ThoracicExtension", "BaseThoracicPose arm bake via the package bake", authoringEvidenceObservable = false) { ThoracicExtensionPose() },
        standard("WPD.DynamicWorldsGreatestStretch", "BaseThoracicPose arm bake via the package bake", authoringEvidenceObservable = false) { DynamicWorldsGreatestStretchPose() },
        standard("WPD.ProneCobraStretch", "prone pose with bent arm intent") { ProneCobraStretchPose() },

        // ---- existing R-rule / production corpus (IkStageTest's production set) ---------------
        standard("BASE.StandardPullUp", "hanging arm intent (vertical pull family)") { StandardPullUpPose() },
        standard("BASE.StaticForearmPlank", "plank posture + forearm support") { StaticForearmPlankPose() },
        standard("BASE.HamstringStretch", "hip-hinge pose with bent leg intent") { HamstringStretchPose() },
        standard("BASE.JumpSquat", "squat family with articulated motion") { JumpSquatPose() },
        standard("BASE.PikePushUp", "push-up family with overhead intent") { PikePushUpPose() }
    )

    // =====================================================================================
    // 3. Observation model
    // =====================================================================================

    /**
     * One raw scalar per line (`key=value`, floats as `toRawBits()` integers) so that
     * (a) comparison is exact and (b) the deliberate-regression proofs can perturb a single
     * declared value deterministically instead of comparing tolerances.
     */
    private class Observation(
        val caseKey: String,
        val stageActive: Boolean,
        val frameIndex: Int,
        val exercises: String,
        val evidence: Evidence,
        val sections: Map<String, List<String>>
    ) {
        fun perturb(dimension: String, transform: (List<String>) -> List<String>): Observation =
            Observation(
                caseKey, stageActive, frameIndex, exercises, evidence,
                sections.toMutableMap().apply { this[dimension] = transform(getValue(dimension)) }
            )

        /** Perturbs the first line whose key matches [key], adding [bits] to its raw int value. */
        fun perturbRawBits(dimension: String, key: String, bits: Int): Observation = perturb(dimension) { lines ->
            lines.map { line ->
                if (line.substringBefore('=') != key) line else {
                    val value = line.substringAfter('=')
                    val n = value.toIntOrNull()
                    if (n == null) line else "$key=${n + bits}"
                }
            }
        }
    }

    private fun observe(entry: CorpusEntry, progress: Float, stageActive: Boolean): List<Observation> {
        IK_STAGE_ACTIVE = stageActive
        val pipeline = SkeletonPipeline(def)
        val context = ctx(progress)
        val out = mutableListOf<Observation>()
        repeat(entry.frames) { frame ->
            val produced = entry.run(pipeline, context)
            out += Observation(
                caseKey = entry.key(progress),
                stageActive = stageActive,
                frameIndex = frame,
                exercises = entry.exercises,
                evidence = produced.evidenceBeforeFrame,
                sections = sections(produced)
            )
        }
        return out
    }

    private fun sections(f: BuiltFrame): Map<String, List<String>> {
        val sections = LinkedHashMap<String, List<String>>()

        // 1. INTENT — declared Limb Targets (ownership-neutral, §12.5 acceptance).
        sections["INTENT.limbTargets"] = f.built.limbTargets.mapIndexed { i, t ->
            listOf(
                "target.$i.joint=${t.joint.name}",
                "target.$i.world=${vecBits(t.world)}",
                "target.$i.pole=${vecBits(t.pole)}",
                "target.$i.straight=${t.straight}",
                "target.$i.length1=${t.length1.toRawBits()}",
                "target.$i.length2=${t.length2.toRawBits()}",
                "target.$i.constraint=${constraintKey(t.constraint)}",
                "target.$i.contact=${contactKey(t.contact)}"
            )
        }.flatten()

        // 2. INTENT — declared ContactSpecs (Phase-2 settlement intent, R3).
        sections["INTENT.contacts"] = f.built.contacts.mapIndexed { i, c ->
            listOf(
                "contact.$i.end=${c.endJoint.name}",
                "contact.$i.root=${c.rootJoint.name}",
                "contact.$i.parentRotation=${c.parentRotationJoint.name}",
                "contact.$i.middle=${c.middleJoint.name}",
                "contact.$i.targetWorld=${vecBits(c.targetWorld)}",
                "contact.$i.pole=${vecBits(c.pole)}",
                "contact.$i.length1=${c.length1.toRawBits()}",
                "contact.$i.length2=${c.length2.toRawBits()}",
                "contact.$i.straight=${c.straight}",
                "contact.$i.constraint=${constraintKey(c.constraint)}",
                "contact.$i.contact=${contactKey(c.contact)}"
            )
        }.flatten()

        // 3. INTENT — precedence + posture.
        sections["INTENT.control"] = listOf(
            "precedence=${f.built.contactPrecedence.joinToString(",")}",
            "posture.kind=${f.built.postureIntent.kind}",
            "posture.tolerance=${f.built.postureIntent.tolerance.toRawBits()}"
        )

        // 4. ROOT — authored and published.
        sections["ROOT.authored"] = f.authoredRoot
        sections["ROOT.published"] = rootLines(f.published)

        // 5/6/7. PUBLISHED — all 33 joints + the node tree.
        sections["PUBLISHED.transforms"] = Joint.entries.flatMap { joint ->
            val p = f.published.getJoint(joint)
            val r = f.published.getJointRotation(joint)
            listOf(
                "${joint.name}.pos=(${p.x.toRawBits()},${p.y.toRawBits()},${p.z.toRawBits()})",
                "${joint.name}.rot=(${r.axis.x.toRawBits()},${r.axis.y.toRawBits()},${r.axis.z.toRawBits()})@${r.angle.toRawBits()}",
                "${joint.name}.world.x=${p.x.toRawBits()}",
                "${joint.name}.world.y=${p.y.toRawBits()}",
                "${joint.name}.world.z=${p.z.toRawBits()}"
            )
        }
        sections["PUBLISHED.nodes"] = f.published.roots.flatMap { nodeLines(it) }

        // 8. STAMPS — the complete §4.4 set.
        sections["STAMPS"] = stampLines(f.published)

        // 8b. KINEMATIC — the published carrier's kinematic-state marker. Added at WP-I: this was
        // the ONE published field the OFF/ON observation did not compare, and it DID diverge —
        // the engine stage consumes the build-window marker (`isTransformsUpdated`) and the
        // finalizer's refresh branch wrote the truth only onto its INPUT carrier, so a
        // flag-ON frame published `false` where a flag-OFF frame published `true`. WP-I corrected
        // the finalizer (bookkeeping only, no geometry) and extended this observation so the
        // equivalence claim covers the complete published state instead of the observed subset.
        sections["KINEMATIC"] = listOf(
            "published.isTransformsUpdated=${f.published.isTransformsUpdated}"
        )

        // 9. SETTLEMENT — settlement state + settled contacts at the published boundary.
        sections["SETTLEMENT"] = settlementLines(f)

        // 10. PUBLISH — transfer-chain markers.
        sections["PUBLISH"] = listOf(
            "publishedIsDistinctCarrier=${f.published !== f.built}",
            "publishedRoots=${f.published.roots.size}",
            "authoredLimbs=${f.built.limbTargets.size}",
            "authoredContacts=${f.built.contacts.size}"
        )
        return sections
    }

    private fun nodeLines(node: SkeletonNode, path: String = ""): List<String> {
        val here = if (path.isEmpty()) node.joint.name else "$path/${node.joint.name}"
        return listOf(
            "node.$here.local=(${node.localPosition.x.toRawBits()}," +
                "${node.localPosition.y.toRawBits()},${node.localPosition.z.toRawBits()})",
            "node.$here.localRot=" + rotBits(node.localRotation)
        ) + node.children.flatMap { nodeLines(it, here) }
    }

    private fun stampLines(pose: SkeletonPose): List<String> = buildList {
        add("maxIkClampAmount=${pose.maxIkClampAmount.toRawBits()}")
        add("straightIntentDropped=${if (pose.straightIntentDropped) 1 else 0}")
        add("boneLengthsVerified=${if (pose.boneLengthsVerified) 1 else 0}")
        add("rootTranslationDelta=${pose.rootTranslationDelta.toRawBits()}")
        add("rootRotationDelta=${pose.rootRotationDelta.toRawBits()}")
        add("bilateralSymmetryDelta=${pose.bilateralSymmetryDelta.toRawBits()}")
        add("bilateralOppositeBend=${if (pose.bilateralOppositeBend) 1 else 0}")
        for (hip in listOf(Joint.HIP_F, Joint.HIP_B)) {
            val stamp = pose.hipRomStamps[hip]
            if (stamp == null) {
                add("hipRom.${hip.name}=absent")
            } else {
                add("hipRom.${hip.name}.excursion=${stamp.excursionDegrees.toRawBits()}")
                add("hipRom.${hip.name}.sagittal=${stamp.sagittalDegrees.toRawBits()}")
                add("hipRom.${hip.name}.frontal=${stamp.frontalDegrees.toRawBits()}")
                add("hipRom.${hip.name}.axial=${stamp.axialDegrees.toRawBits()}")
            }
        }
    }

    private fun settlementLines(f: BuiltFrame): List<String> {
        val lines = mutableListOf<String>()
        val settlement = f.built.settlementResult
        lines += "settlementResult.produced=${if (settlement != null) 1 else 0}"
        if (settlement != null) {
            lines += "settledRootWorld.x=${settlement.settledRootWorld.x.toRawBits()}"
            lines += "settledRootWorld.y=${settlement.settledRootWorld.y.toRawBits()}"
            lines += "settledRootWorld.z=${settlement.settledRootWorld.z.toRawBits()}"
            lines += "declaredContactJoints=${settlement.declaredContactJoints.joinToString(",") { it.name }}"
            lines += "conflictOutcomeJoint=${settlement.conflictOutcomeJoint?.name ?: "null"}"
            for (joint in settlement.declaredContactJoints) {
                val published = f.published.getJoint(joint)
                lines += "publishedSettledContact.${joint.name}=(${published.x.toRawBits()}," +
                    "${published.y.toRawBits()},${published.z.toRawBits()})"
            }
        }
        return lines
    }

    private fun rootLines(pose: SkeletonPose): List<String> {
        val pelvis = findPelvis(pose) ?: return listOf("pelvis=absent")
        val local = pelvis.localPosition
        val world = pelvis.worldPosition
        return listOf(
            "pelvis.local.x=${local.x.toRawBits()}",
            "pelvis.local.y=${local.y.toRawBits()}",
            "pelvis.local.z=${local.z.toRawBits()}",
            "pelvis.localRot=${rotBits(pelvis.localRotation)}",
            "pelvis.world.x=${world.x.toRawBits()}",
            "pelvis.world.y=${world.y.toRawBits()}",
            "pelvis.world.z=${world.z.toRawBits()}"
        )
    }

    private fun findPelvis(pose: SkeletonPose): SkeletonNode? {
        fun search(node: SkeletonNode): SkeletonNode? {
            if (node.joint == Joint.PELVIS) return node
            for (child in node.children) search(child)?.let { return it }
            return null
        }
        for (root in pose.roots) search(root)?.let { return it }
        return null
    }

    private fun vecBits(v: Vector3) = "(${v.x.toRawBits()},${v.y.toRawBits()},${v.z.toRawBits()})"

    private fun rotBits(r: JointRotation) = "(${vecBits(r.axis)})@${r.angle.toRawBits()}"

    private fun constraintKey(c: IKConstraint?): String = if (c == null) "null" else
        "min=${c.minimumFlexionAngle.toRawBits()},ext=${c.maximumExtensionRatio.toRawBits()}," +
            "full=${c.allowFullExtension},limits=(${c.angularLimits.minFlexionDegrees.toRawBits()}," +
            "${c.angularLimits.maxFlexionDegrees.toRawBits()}," +
            "${c.angularLimits.maxRootDeviationDegrees.toRawBits()})"

    private fun contactKey(c: ContactConstraint?): String = if (c == null) "null" else
        "normal=${vecBits(c.normal)},point=${vecBits(c.point)}"

    // =====================================================================================
    // 4. Comparison
    // =====================================================================================

    private data class Delta(val caseKey: String, val frameIndex: Int, val dimension: String, val detail: String) {
        override fun toString() = "$caseKey frame $frameIndex §$dimension :: $detail"
    }

    private fun compare(off: Observation, on: Observation): List<Delta> {
        val deltas = mutableListOf<Delta>()
        for (dimension in off.sections.keys) {
            val a = off.sections.getValue(dimension)
            val b = on.sections.getValue(dimension)
            if (a == b) continue
            val adjudicated = adjudications.any { it.dimension == dimension && (it.caseKey == off.caseKey || it.caseKey == "*") }
            if (adjudicated) continue
            deltas += Delta(off.caseKey, off.frameIndex, dimension, firstDifference(a, b))
        }
        return deltas
    }

    private fun firstDifference(a: List<String>, b: List<String>): String {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: "<absent>"
            val y = b.getOrNull(i) ?: "<absent>"
            if (x != y) return "OFF[$x] vs ON[$y]"
        }
        return "byte-identical lists with different sizes (${a.size} vs ${b.size})"
    }

    // =====================================================================================
    // 5. The harness itself
    // =====================================================================================

    @Test
    fun corpusIsEquivalentAcrossOwnershipConfigurations() {
        val deltas = mutableListOf<Delta>()
        val premiseFailures = mutableListOf<String>()
        val report = StringBuilder()
        var frames = 0
        var cases = 0
        try {
            for (entry in corpus()) {
                for (progress in entry.progresses) {
                    cases++
                    val offObservation = runCatching { observe(entry, progress, false) }
                    if (offObservation.isFailure) {
                        val error = offObservation.exceptionOrNull()!!
                        premiseFailures += "${entry.key(progress)}: the flag-OFF configuration threw " +
                            "${error::class.simpleName}: ${error.message}"
                        continue
                    }
                    val off = offObservation.getOrThrow()
                    val onObservation = runCatching { observe(entry, progress, true) }
                    if (onObservation.isFailure) {
                        val error = onObservation.exceptionOrNull()!!
                        premiseFailures += "${entry.key(progress)}: the flag-ON configuration threw " +
                            "${error::class.simpleName}: ${error.message}"
                        continue
                    }
                    val on = onObservation.getOrThrow()
                    if (off.size != entry.frames) {
                        premiseFailures += "${entry.key(progress)}: expected ${entry.frames} frames, got ${off.size}"
                        continue
                    }
                    for (i in off.indices) {
                        premiseFailures += assertEvidencePremises(off[i], on[i], entry)
                        val caseDeltas = compare(off[i], on[i])
                        frames++
                        if (caseDeltas.isEmpty()) {
                            report.appendLine("PASS ${entry.key(progress)} frame $i [${entry.exercises}]")
                        } else {
                            deltas += caseDeltas
                            for (delta in caseDeltas) report.appendLine("FAIL $delta [${entry.exercises}]")
                        }
                    }
                }
            }
        } finally {
            IK_STAGE_ACTIVE = originalStage
        }
        report.appendLine("TOTAL cases=$cases frames=$frames deltas=${deltas.size} premiseFailures=${premiseFailures.size}")
        for (failure in premiseFailures) report.appendLine("PREMISE-FAIL $failure")
        writeReport(report.toString())
        assertTrue(
            "${premiseFailures.size} per-configuration ownership premise(s) failed — a green " +
                "comparison must not be produced by two equally-broken configurations:\n" +
                premiseFailures.take(10).joinToString("\n"),
            premiseFailures.isEmpty()
        )
        assertTrue(
            "§12.9 cross-configuration equivalence failed for ${deltas.size} dimension instance(s) " +
                "(first 10):\n" + deltas.take(10).joinToString("\n") +
                "\nFull per-case report: ${reportPath()}",
            deltas.isEmpty()
        )
        assertTrue("anti-vacuity: the corpus must exercise frames", frames > 50)
    }

    /**
     * Per-configuration PREMISES (never equivalence dimensions): the §12.7a/§12.7b ownership
     * contract must hold in each configuration, so a green comparison cannot be produced by two
     * equally-broken configurations. Returns the failure messages instead of throwing, so one run
     * collects the whole corpus.
     */
    private fun assertEvidencePremises(off: Observation, on: Observation, entry: CorpusEntry): List<String> {
        val failures = mutableListOf<String>()
        val declared = off.sections.getValue("PUBLISH")
            .first { it.startsWith("authoredLimbs=") }.substringAfter('=').toInt()
        fun expect(condition: Boolean, message: String) {
            if (!condition) failures += message
        }

        // OFF: the authoring bake is the Active Limb Solver — one window per build cycle, no
        // duplicate realization. (Snapshot-returning build templates are the documented exception;
        // see [CorpusEntry.authoringEvidenceObservable] and FINDING F-1.)
        if (declared > 0 && entry.authoringEvidenceObservable) {
            expect(
                off.evidence.windows == 1,
                "${entry.name} (flag-OFF): the authoring implementation must open exactly one " +
                    "realization window for a pose that declares limbs — observed ${off.evidence.windows}"
            )
            expect(
                off.evidence.realized.isNotEmpty(),
                "${entry.name} (flag-OFF): the authoring implementation must have realized limbs"
            )
        }
        if (declared == 0) {
            expect(
                off.evidence.windows == 0,
                "${entry.name} (flag-OFF): a pose with no declared limb opens no realization window — " +
                    "observed ${off.evidence.windows}"
            )
        }
        expect(off.evidence.duplicates == 0, "${entry.name} (flag-OFF): no limb may be realized twice")

        // ON: the bake is gated (§12.7a) — the build window realizes nothing; the stage owns it.
        expect(
            on.evidence.windows == 0,
            "${entry.name} (flag-ON): the gated authoring bake must not realize anything (§12.7a) — " +
                "pre-frame evidence must be empty, observed ${on.evidence.windows}"
        )
        expect(on.evidence.duplicates == 0, "${entry.name} (flag-ON): no authoring realization event")
        expect(
            on.evidence.realized.isEmpty(),
            "${entry.name} (flag-ON): no authored limb may be recorded as realized — " +
                "observed '${on.evidence.realized}'"
        )
        return failures
    }

    @Test
    fun observationIsIndependentOfConfigurationOrder() {
        val offenders = mutableListOf<String>()
        try {
            for (entry in corpus()) {
                for (progress in entry.progresses) {
                    val offThenOn = observe(entry, progress, false) to observe(entry, progress, true)
                    val onThenOff = observe(entry, progress, true) to observe(entry, progress, false)
                    for (i in offThenOn.first.indices) {
                        if (offThenOn.first[i].sections != onThenOff.second[i].sections) {
                            offenders += "${entry.key(progress)} frame $i: the flag-OFF observation " +
                                "depends on whether flag-ON ran before it"
                        }
                        if (offThenOn.second[i].sections != onThenOff.first[i].sections) {
                            offenders += "${entry.key(progress)} frame $i: the flag-ON observation " +
                                "depends on whether flag-OFF ran before it"
                        }
                    }
                }
            }
        } finally {
            IK_STAGE_ACTIVE = originalStage
        }
        assertTrue(
            "cross-configuration state leakage: running the corpus in both directions must produce " +
                "identical per-configuration observations:\n" + offenders.take(10).joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun explicitInjectionPathMatchesTheBuilderEntryPoint() {
        // The corpus observes through the built-carrier overload (it needs the carrier for the
        // settlement + pre-frame evidence). This premise proves that path is the SAME frame the
        // builder overload produces — i.e. the harness did not invent a second pipeline entry.
        try {
            for (stage in listOf(false, true)) {
                IK_STAGE_ACTIVE = stage
                for (factory in listOf<() -> PoseBuilder>(
                    { StandardPushUpPose() }, { MiddleSplitPose() }, { ReverseSnowAngelPose() }, { DeadHangPose() }
                )) {
                    val builder = factory()
                    val built = builder.build(ctx(0.5f))
                    val supportPoints = HashSet<SupportPoint>()
                    for (contact in builder.metadata.support.contacts) supportPoints.add(contact.point)
                    val explicit = SkeletonPipeline(def)
                        .produceFrame(built, builder.metadata.environment, supportPoints).pose
                    val entryPoint = SkeletonPipeline(def).produceFrame(factory(), ctx(0.5f)).pose
                    for (joint in Joint.entries) {
                        val a = explicit.getJoint(joint)
                        val b = entryPoint.getJoint(joint)
                        assertEquals(
                            "flag=$stage $joint: the harness's explicit-injection path must reproduce " +
                                "the builder overload bit-exactly (same injection, same stages)",
                            listOf(a.x.toRawBits(), a.y.toRawBits(), a.z.toRawBits()),
                            listOf(b.x.toRawBits(), b.y.toRawBits(), b.z.toRawBits())
                        )
                    }
                }
            }
        } finally {
            IK_STAGE_ACTIVE = originalStage
        }
    }

    // =====================================================================================
    // 6. Deliberate-regression proofs (§11 — the harness must be able to fail)
    // =====================================================================================

    /**
     * Test-only pose with the B-1 authoring-dependency shape WP-B eliminated from production:
     * the second Limb Target is composed from the FIRST limb's realized node inside `build()`.
     * Under flag-OFF that node holds the solved offset; under flag-ON the bake is gated (§12.7a)
     * and the node still holds the authored value — so the two configurations declare *different
     * intent* and must produce different published frames. This is a genuine production-path
     * divergence, not a mutated snapshot: it proves the comparator sees real cross-configuration
     * behaviour, which is exactly the class of defect §12.9 exists to detect.
     */
    private class AuthoringDependentProbe : BasePose() {
        override fun onBuild(context: PoseContext): SkeletonPose {
            val definition = context.definition
            val nodes = SkeletonFactory.createStandardSkeleton()
            nodes.pelvis.localPosition.set(0f, 220f, 0f)
            nodes.hipF.localPosition.set(0f, 0f, 0f)
            nodes.hipB.localPosition.set(0f, 0f, 0f)
            nodes.kneeF.localPosition.set(0f, 0f, 0f)
            nodes.kneeB.localPosition.set(0f, 0f, 0f)
            nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }

            bakeIkLimb(
                nodes.hipF.worldPosition, PROBE_TARGET, definition.thighLength, definition.shinLength,
                PROBE_POLE, definition.legIKConstraint, nodes.pelvis.worldRotation,
                nodes.kneeF, nodes.ankleF, SkeletonMath.IKResult()
            )
            // The B-1 dependency: compose the second limb's target from the FIRST limb's node.
            val realizedKnee = nodes.kneeF.localPosition
            val composed = Vector3(realizedKnee.x + 60f, 220f + realizedKnee.y - 40f, realizedKnee.z)
            bakeIkLimb(
                nodes.hipB.worldPosition, composed, definition.thighLength, definition.shinLength,
                PROBE_POLE, definition.legIKConstraint, nodes.pelvis.worldRotation,
                nodes.kneeB, nodes.ankleB, SkeletonMath.IKResult()
            )
            return SkeletonPose.fromHierarchy(nodes.roots, jointsBuffer)
        }
    }

    @Test
    fun deliberateIntentDivergenceIsDetected() {
        val entry = CorpusEntry(
            "REGRESSION-A.authoringDependentProbe",
            "test-only B-1 authoring dependency (intent composed from a realized node)",
            listOf(0.5f)
        ) { pipeline, context -> runBuilder(pipeline, context, AuthoringDependentProbe()) }

        val off = observe(entry, 0.5f, false).single()
        val on = observe(entry, 0.5f, true).single()
        val dimensions = compare(off, on).map { it.dimension }.toSet()

        // Anti-vacuity: the probe must genuinely declare two limbs, and the divergence must be real.
        assertEquals("probe declares two limb targets", 2, off.sections.getValue("PUBLISH").last { it.startsWith("authoredLimbs=") }.substringAfter('=').toInt())
        assertTrue(
            "the harness must detect the declared-intent divergence of an authoring-dependent pose " +
                "(reported dimensions: $dimensions)",
            "INTENT.limbTargets" in dimensions
        )
        assertTrue(
            "the divergence must also surface at the published boundary (reported dimensions: $dimensions)",
            "PUBLISHED.transforms" in dimensions
        )
        // And it must be a REAL divergence, not an artifact of the evidence/premise sections.
        assertTrue(
            "the OFF and ON runs of this probe must genuinely differ in geometry",
            off.sections.getValue("PUBLISHED.transforms") != on.sections.getValue("PUBLISHED.transforms")
        )
    }

    @Test
    fun deliberateStampPerturbationIsDetected() {
        val entry = standard("REGRESSION-B.MiddleSplit", "§4.4 stamp coverage", FIVE) { MiddleSplitPose() }
        val off = observe(entry, 0.5f, false).single()
        val on = observe(entry, 0.5f, true).single()
        assertTrue(
            "premise: the unmodified case must be equivalent before the perturbation",
            compare(off, on).isEmpty()
        )

        // (a) a solver-family stamp perturbed beyond any plausible numeric tolerance;
        val clampTampered = on.perturbRawBits("STAMPS", "maxIkClampAmount", PERTURBATION_BITS)
        val clampDeltas = compare(off, clampTampered)
        assertEquals(
            "exactly the perturbed dimension must be reported for a Clamp-Stamp change",
            setOf("STAMPS"), clampDeltas.map { it.dimension }.toSet()
        )
        assertTrue(
            "the reported delta must name the perturbed stamp",
            clampDeltas.single().detail.contains("maxIkClampAmount")
        )

        // (b) the straight-intent flag (the §12.9 diagnostic stamp the plan calls out explicitly);
        val droppedTampered = on.perturb("STAMPS") { lines ->
            lines.map { if (it.startsWith("straightIntentDropped=")) "straightIntentDropped=${1 - it.substringAfter('=').toInt()}" else it }
        }
        val droppedDeltas = compare(off, droppedTampered)
        assertEquals(
            "exactly the perturbed dimension must be reported for a Straight-Intent-Dropped change",
            setOf("STAMPS"), droppedDeltas.map { it.dimension }.toSet()
        )
        assertTrue(
            "the reported delta must name the perturbed stamp",
            droppedDeltas.single().detail.contains("straightIntentDropped")
        )

        // Negative control: a zero-delta perturbation must NOT be reported.
        assertTrue(
            "control: a zero-raw-bit perturbation must remain equivalent",
            compare(off, on.perturbRawBits("STAMPS", "maxIkClampAmount", 0)).isEmpty()
        )
    }

    @Test
    fun deliberateRootAuthorityPerturbationIsDetected() {
        val entry = standard("REGRESSION-C.ArmCircles", "root authority (UNI-6 root displacement)", FIVE) { ArmCirclesPose() }
        val off = observe(entry, 0.5f, false).single()
        val on = observe(entry, 0.5f, true).single()
        assertTrue("premise: the unmodified case must be equivalent", compare(off, on).isEmpty())
        assertTrue(
            "premise: this case must have displaced the root (anti-vacuity for root authority)",
            off.sections.getValue("STAMPS").first { it.startsWith("rootTranslationDelta=") }
                .substringAfter('=').toInt() != 0f.toRawBits()
        )

        // (a) an authored/published root value moved by one raw-bit delta (the RAW-BIT rule's tolerance is 0);
        val rootDeltas = compare(off, on.perturbRawBits("ROOT.published", "pelvis.local.y", PERTURBATION_BITS))
        assertEquals(
            "exactly the root dimension must be reported for a pelvis perturbation",
            setOf("ROOT.published"), rootDeltas.map { it.dimension }.toSet()
        )
        assertTrue(
            "the reported delta must name the perturbed root scalar",
            rootDeltas.single().detail.contains("pelvis.local.y")
        )

        // (b) the settled root (the R2/R3 root-authority output inside the Settlement Result);
        val settledDeltas = compare(off, on.perturbRawBits("SETTLEMENT", "settledRootWorld.y", PERTURBATION_BITS))
        assertEquals(
            "exactly the settlement dimension must be reported for a settled-root perturbation",
            setOf("SETTLEMENT"), settledDeltas.map { it.dimension }.toSet()
        )
        assertTrue(
            "the reported delta must name the perturbed settled-root scalar",
            settledDeltas.single().detail.contains("settledRootWorld.y")
        )

        // (c) the published world transform of a joint (geometry authority);
        val transformDeltas = compare(off, on.perturbRawBits("PUBLISHED.transforms", "PELVIS.world.y", PERTURBATION_BITS))
        assertEquals(
            "exactly the published-transform dimension must be reported for a geometry perturbation",
            setOf("PUBLISHED.transforms"), transformDeltas.map { it.dimension }.toSet()
        )
    }

    /**
     * FINDING F-1 (raised at WP-H) — **adjudicated at WP-I: the snapshot boundary is INTENTIONAL
     * and is now codified.** Authoring realization evidence is carrier-local, and one build template
     * returns a snapshot instead of its carrier.
     *
     * `BaseThoracicPose.finalizeThoracicPose` returns `out.copyFrom(jointsBuffer)` (not the carrier)
     * so that two samples of one builder cannot alias. The realization evidence fields are
     * deliberately absent from `copyFrom` (P3 suppression: Published Pose State never inherits
     * instrumentation — pinned by `RuntimeSolverOwnershipAuditTest`), so the returned carrier of the
     * three `BaseThoracicPose` templates carries ZERO authoring evidence in the flag-OFF
     * configuration even though the authoring bake did realize their limbs. Consequence: in
     * flag-OFF the pipeline's `count == 0 && !IK_STAGE_ACTIVE` disjunct is what admits those
     * frames, i.e. §12.7b's "exactly one window, proven by the counter" does not *prove* anything
     * for that family in the legacy diagnostic configuration.
     *
     * **WP-I disposition (option A — codified, not silently fixed).** The boundary is the
     * architectural consequence of two deliberate decisions, and correcting it would *create* the
     * thing §15 forbids (a second evidence-mutation path):
     *  - the instrumentation must NOT cross `copyFrom` (P3 suppression pattern; the published pose
     *    is produced through `copyFrom`, so propagating the counter would make enforcement state
     *    Published Pose State);
     *  - the evidence is by construction carrier-local to the implementation that realized ON that
     *    carrier: the thoracic bake realizes on `jointsBuffer`, so that is the carrier that must
     *    carry (and does carry) its evidence — transporting it onto the returned snapshot would be
     *    evidence transported between carriers, i.e. a second evidence path.
     *
     * It cannot invalidate state-3 ownership, and that is proved rather than asserted:
     *  - with the engine stage ACTIVE the bake is gated (§12.7a), so no authoring realization exists
     *    to hide — the stage registers its window and its per-limb mask on the very carrier the
     *    pipeline inspects (`check(count == 1)`, the flag-OFF disjunct being unreachable), which is
     *    why the acceptance below is evidence-backed;
     *  - the flag-OFF admission cannot mask a second solver in the deployed state, and the family's
     *    activated path is additionally subjected to the §14 double-realization rejection
     *    (`ActivationGateTest.f1SnapshotFamilyCannotHideASecondRealization`);
     *  - the *semantic* result of the family's realization is preserved across the boundary by
     *    design — clamp / dropped / verified stamps ride `copyFrom` and the published frame is
     *    raw-bit identical across configurations (this corpus).
     *
     * Corollary for any new family: **evidence is carrier-local** — a build template that returns a
     * copy hides its own realization evidence, so it must be covered by the activated-configuration
     * enforcement (engine-side evidence on the inspected carrier), never by the flag-OFF disjunct.
     */
    @Test
    fun snapshotReturningTemplatesCarryNoAuthoringEvidence() {
        val exporters = listOf<() -> PoseBuilder>(
            { ThoracicExtensionPose() },
            { QuadrupedThoracicRotationsPose() },
            { DynamicWorldsGreatestStretchPose() }
        )
        try {
            IK_STAGE_ACTIVE = false
            for (factory in exporters) {
                val built = factory().build(ctx(0.5f))
                assertTrue(
                    "F-1 premise: the template must declare limbs through a registered bake " +
                        "(anti-vacuity)",
                    built.limbTargets.isNotEmpty()
                )
                assertEquals(
                    "F-1: the snapshot-returning template carries no authoring realization evidence " +
                        "on the returned carrier (the bake registered on `jointsBuffer`)",
                    0, built.limbSolverExecutions
                )
                assertEquals(0, built.limbDuplicateRealizations)
                // The frame is still accepted: the pipeline's flag-OFF disjunct admits a frame with
                // no registered realization (this is the coverage limitation F-1 records).
                val violation = runCatching {
                    SkeletonPipeline(def).produceFrame(factory(), ctx(0.5f))
                }.exceptionOrNull()
                assertTrue(
                    "F-1: the frame must still be accepted in the deployed configuration — observed " +
                        (violation?.let { "${it::class.simpleName}: ${it.message}" } ?: "no violation"),
                    violation == null
                )
            }

            // The ACTIVATED configuration is fully covered: the pipeline's own enforcement admits a
            // frame only when exactly one engine realization window was registered on the carrier
            // it inspects (`check(count == 1 …)` in runStages), so ACCEPTANCE of these frames at
            // flag-ON is the evidence that state 3 covers this template. No direct stage call is
            // needed — and none is made (the harness observes only through the pipeline boundary,
            // pinned by the WP-H static audit).
            IK_STAGE_ACTIVE = true
            for (factory in exporters) {
                val built = factory().build(ctx(0.5f))
                assertEquals("the gated bake realizes nothing", 0, built.limbSolverExecutions)
                val violation = runCatching {
                    SkeletonPipeline(def).produceFrame(factory(), ctx(0.5f))
                }.exceptionOrNull()
                assertTrue(
                    "F-1: the activated configuration must register exactly one engine realization " +
                        "window on the carrier the pipeline inspects (the frame is otherwise rejected) — " +
                        "observed " + (violation?.let { "${it::class.simpleName}: ${it.message}" } ?: "acceptance"),
                    violation == null
                )
            }
        } finally {
            IK_STAGE_ACTIVE = originalStage
        }
    }

    @Test
    fun equivalenceRelationHasNoUnusedRelaxation() {
        // The relation must stay explicit: an adjudication entry that no corpus case needs would
        // silently permit a future divergence. Empty is the WP-H outcome (see the KDoc above);
        // this test makes re-introducing a blanket relaxation a deliberate, reviewable act.
        assertTrue(
            "adjudications must name case + dimension + rule + invariant + justification, and must " +
                "be empty while every corpus case is raw-bit equivalent",
            adjudications.isEmpty()
        )
    }

    /**
     * §12.8 disposition pin — the WP-H form of this test pinned the state-2-only probes as *still
     * present and unconditional* (nothing deleted, `@Ignore`d, `assume`d or flag-conditionalized
     * just to make the harness green) while WP-I still owned their retirement. WP-I has now retired
     * them (same commit as the flip), so the pin asserts the DISPOSITION instead:
     *  - the frozen plan keeps naming the retirement classification (§12.8) — it is not amended;
     *  - the three state-2-only probes are GONE from `IkStageTest` (retired, not hidden: no
     *    `@Ignore`, no `assume`, nothing left asserting that state 2 is the default);
     *  - each retired probe's coverage has a replacement that describes state 3:
     *    the byte-identity parity pair → the §12.9 cross-configuration corpus (this class),
     *    `flagDefaultsFalse` → the §12.7 configuration-surface audit + the §12.10 activation gate.
     */
    @Test
    fun retiredAndStateTwoOnlyProbesAreExplicitlyClassified() {
        val plan = File(repositoryRoot(), "docs/IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md")
        assertTrue("the frozen plan must be readable at ${plan.path}", plan.isFile)
        val planText = plan.readText()
        assertTrue(
            "§12.8 must keep naming the tests that become invalid at activation",
            planText.contains("### 12.8 Tests that become INVALID at activation")
        )
        val retiredProbes = listOf(
            "productionPosesByteIdenticalStageOnVsOff",
            "contactPosesByteIdenticalStageOnVsOff",
            "flagDefaultsFalse"
        )
        for (retired in retiredProbes) {
            assertTrue(
                "§12.8 must explicitly classify `$retired` as a state-2 guarantee replaced at WP-I " +
                    "(a retired probe has to be named, not silently dropped)",
                planText.contains(retired)
            )
        }

        val ikStageTest = File(moduleRoot(), "src/test/java/com/monkfitness/app/IkStageTest.kt")
        assertTrue("the IkStage suite must still exist (its carrier test is state-agnostic)", ikStageTest.isFile)
        val suiteText = ikStageTest.readText()
        for (retired in retiredProbes) {
            assertFalse(
                "`$retired` must be RETIRED at activation, not kept as a state-2 guarantee " +
                    "(the suite now describes state 3)",
                suiteText.contains("fun $retired(")
            )
        }
        assertFalse(
            "a retired probe may not be hidden instead of removed (no @Ignore / assume)",
            suiteText.contains("@Ignore") || suiteText.contains("assumeTrue") || suiteText.contains("assumeFalse")
        )
        assertTrue(
            "the state-agnostic carrier coverage of the retired suite must survive",
            suiteText.contains("fun limbTargetsCarrierIsLiveAfterB1(")
        )

        // The replacements must be real and reachable in the current suite, not asserted by name only.
        val replacements = listOf(
            "ActivationGateTest.kt" to "criterionFive_section129EquivalenceCorpusIsGreen",
            "ActivationGateTest.kt" to "productionConfigurationIsStateThree",
            "ActivationEquivalenceTest.kt" to "corpusIsEquivalentAcrossOwnershipConfigurations",
            "RuntimeSolverOwnershipAuditTest.kt" to "ikStageFlagIsDeclarationOnlyAndReadOnlyAtRealizationDecisionSites"
        )
        for ((file, method) in replacements) {
            val source = File(moduleRoot(), "src/test/java/com/monkfitness/app/arch/$file")
            assertTrue("the replacement suite $file must exist", source.isFile)
            assertTrue(
                "`$file` must carry the replacement coverage `$method`",
                source.readText().contains("fun $method(")
            )
        }
    }

    // =====================================================================================
    // 7. §9 — the ConstraintSolver settlement boundary is not an R5 realization
    // =====================================================================================
    @Test
    fun constraintSolverSettlementIsNotASecondLimbRealizationPath() {
        try {
            for (stage in listOf(false, true)) {
                IK_STAGE_ACTIVE = stage
                val built = MiddleSplitPose().build(ctx(0.5f))
                val declaredLimbs = built.limbTargets.size
                val declaredContacts = built.contacts.map { it.endJoint }
                val windowsBefore = built.limbSolverExecutions
                val maskBefore = built.limbRealizedLimbs
                val duplicatesBefore = built.limbDuplicateRealizations

                assertTrue("premise: the instrument declares limbs (anti-vacuity)", declaredLimbs > 0)
                assertTrue("premise: the instrument declares contacts (anti-vacuity)", declaredContacts.isNotEmpty())
                if (stage) {
                    assertEquals(
                        "flag-ON premise: the Phase-2 settlement pass must run with ZERO R5 " +
                            "realization evidence (the gated bake realized nothing) — settlement does " +
                            "not require R5 execution evidence",
                        0, windowsBefore
                    )
                }

                // The settlement pass ALONE, driven by its declared ContactSpecs.
                ConstraintSolver.solve(built, def, null)

                assertEquals(
                    "the settlement re-solve must not consume the Limb Targets as a hidden second " +
                        "limb realization path",
                    declaredLimbs, built.limbTargets.size
                )
                assertEquals(
                    "the settlement re-solve must not add Contact Declarations",
                    declaredContacts, built.contacts.map { it.endJoint }
                )
                assertEquals(
                    "the settlement re-solve must register no realization window",
                    windowsBefore, built.limbSolverExecutions
                )
                assertEquals(
                    "the settlement re-solve must register no realized limb (§12.7b evidence stays " +
                        "owned by the registered realization sites)",
                    maskBefore, built.limbRealizedLimbs
                )
                assertEquals(
                    "the settlement re-solve must register no duplicate realization",
                    duplicatesBefore, built.limbDuplicateRealizations
                )

                val settlement = built.settlementResult
                assertTrue(
                    "the settlement pass must produce the Settlement Result (R3) from its ContactSpec inputs",
                    settlement != null
                )
                assertEquals(
                    "the Settlement Result's contact membership must follow the declared ContactSpecs",
                    declaredContacts, settlement!!.declaredContactJoints
                )

                // Settled-contact invariant at the published boundary: the settled root is the
                // published root (R2/R3 — the Solver is the last subsystem to move the root).
                val publishedPelvis = findPelvis(built)!!
                assertEquals(
                    "settlement $stage: the settled root world transform must be the published root " +
                        "(R2 root authority: nothing after the Solver moves it)",
                    listOf(
                        settlement.settledRootWorld.x.toRawBits(),
                        settlement.settledRootWorld.y.toRawBits(),
                        settlement.settledRootWorld.z.toRawBits()
                    ),
                    listOf(
                        publishedPelvis.worldPosition.x.toRawBits(),
                        publishedPelvis.worldPosition.y.toRawBits(),
                        publishedPelvis.worldPosition.z.toRawBits()
                    )
                )
            }
        } finally {
            IK_STAGE_ACTIVE = originalStage
        }
    }

    // =====================================================================================
    // 8. §14 — validation re-certification probe matrix
    // =====================================================================================

    @Test
    fun validationProbesAreReCertifiedInBothOwnershipConfigurations() {
        // The four validation instruments are the ONLY production authors of `straight = true`
        // (plan §12.3 B-4) and therefore the only probes whose readings can differ by which
        // implementation realized the limb. Each probe must EXECUTE, OBSERVE and PASS in BOTH
        // configurations, and the probe reading must come from the ACTIVE implementation.
        data class Probe(val name: String, val factory: () -> PoseBuilder)
        val probes = listOf(
            Probe("MiddleSplit", { MiddleSplitPose() }),
            Probe("DeadHang", { DeadHangPose() }),
            Probe("PikeSit", { PikeSitPose() }),
            Probe("DeepOverheadSquat", { DeepOverheadSquatPose() })
        )
        val report = StringBuilder()
        var droppingProbes = 0
        try {
            for (probe in probes) {
                val observations = LinkedHashMap<Boolean, Observation>()
                for (stage in listOf(false, true)) {
                    val entry = standard("PROBE.${probe.name}", "validation probe re-certification", FIVE, factory = probe.factory)
                    val observation = observe(entry, 0.5f, stage).single()
                    observations[stage] = observation
                    val dropped = observation.sections.getValue("STAMPS")
                        .first { it.startsWith("straightIntentDropped=") }.substringAfter('=') == "1"
                    if (dropped) droppingProbes++
                    report.appendLine(
                        "PROBE ${probe.name} stage=$stage executes=yes observes(dropsStraightIntent)=$dropped " +
                            "contactJoints=" + observation.sections.getValue("SETTLEMENT")
                            .firstOrNull { it.startsWith("declaredContactJoints=") }.orEmpty().substringAfter('=')
                    )
                }
                val off = observations.getValue(false)
                val on = observations.getValue(true)
                // (a) the probe OBSERVES: both configurations carry the same published reading;
                // (b) the probe is not a second solver: the activated build window is empty
                //     (this is the §12.7a premise asserted in the corpus, restated here per probe);
                assertEquals(
                    "${probe.name}: the probe reading must be produced in both configurations",
                    off.sections.getValue("STAMPS"), on.sections.getValue("STAMPS")
                )
                assertEquals(
                    "${probe.name}: the activated build window must carry no realization — the " +
                        "reading comes from the active implementation, not from a second solver",
                    0, on.evidence.windows
                )
                if (!probe.factory().metadata.support.contacts.isEmpty()) {
                    assertTrue(
                        "${probe.name}: the probe's declared support must still register contacts",
                        on.sections.getValue("INTENT.contacts").isNotEmpty()
                    )
                }
            }
        } finally {
            IK_STAGE_ACTIVE = originalStage
        }
        // Anti-vacuity: the probe set must actually exercise the straight-intent diagnostic in at
        // least one configuration, otherwise the re-certification would be green for free.
        assertTrue(
            "the probe set must exercise the straight-intent drop reading (the §12.9 diagnostic)",
            droppingProbes > 0
        )
        writeReport(report.toString(), suffix = "-probes")
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    private fun reportPath(suffix: String = "") =
        File(System.getProperty("java.io.tmpdir"), "p12-wph-equivalence-report$suffix.txt").absolutePath

    /** The app module directory (contains `src/`), located by walking up from the JVM working dir. */
    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) return dir
            dir = dir.parentFile ?: break
        }
        error("Could not locate the app module root from ${System.getProperty("user.dir")}")
    }

    /** The repository root — the module directory's parent (owns `docs/`). */
    private fun repositoryRoot(): File = moduleRoot().parentFile
        ?: error("The module directory has no parent: ${moduleRoot().path}")

    private fun writeReport(text: String, suffix: String = "") {
        runCatching { File(reportPath(suffix)).writeText(text) }
    }

    private companion object {
        /** Full progress sweep — baseline + contact + validation-probe cases. */
        val FIVE = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        /** Reduced sweep — the representation-migration and capture-only families. */
        val THREE = listOf(0f, 0.5f, 1f)

        /** Raw-bit perturbation used by the deliberate-regression proofs (≈16 units at a y≈220 scale). */
        const val PERTURBATION_BITS = 1 shl 20

        /** Test-only probe geometry (bent, reachable leg target). */
        val PROBE_TARGET = Vector3(80f, 120f, 0f)
        val PROBE_POLE = Vector3(0f, 0f, 1f)
    }
}
