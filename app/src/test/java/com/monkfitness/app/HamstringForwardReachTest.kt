package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.HamstringStretchPose
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.*

/**
 * M13 — the Hamstring stretch's forward reach is realized by the ARM chain, so the pose's declared
 * hand target must lie inside that chain's own reachable band at every phase of the fold, and the
 * published frame must realize it exactly where it was declared (no solver relocation).
 *
 * Everything here is measured on the PRODUCED frame (`SkeletonPipeline.produceFrame(pose, ctx)`),
 * because a bare `build()` result carries the previous frame's world for engine-realized limbs.
 *
 * Measured through the whole fold (p = 0.00 … 1.00, step 0.05), `SkeletonDefinition.DEFAULT_ADULT`:
 *
 * | p | declared shoulder→hand | band | published − declared |
 * |---|---|---|---|
 * | 0.00 | `37.2108` | `[40.1344, 143.0800]` | `2.9237` (relocated onto the stop) |
 * | 0.05 | `41.0122` | idem | `0.0000` |
 * | 1.00 | `115.1790` | idem | `0.0000` |
 *
 * So the authored target leaves the chain's reachable band at the START of the fold, where it sits
 * inside the arm's minimum-flexion reach (`minReach(80, 66, 30°) = 40.1344`), and the solver answers
 * by pushing it out along its own ray: the published arm lands on exactly `30.00°` interior angle
 * with `ELBOW_A.y − SHOULDER_A.y = +64.583`, i.e. the elbows flung above the shoulder (BPS §6/§11
 * "Shoulders are relaxed and down, not shrugged"). The audit's stated direction for M13 — a target
 * *beyond* max arm reach ("~200 vs max 146") — does NOT reproduce: the largest declared
 * shoulder→hand distance over the fold is `115.1790`, `80.5%` of the `143.0800` cap.
 */
class HamstringForwardReachTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val armMin = SkeletonMath.minReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
    private val armMax = SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)

    /** The R2 projection's own guard band (`clampTargetToReach` margin = 0.02). */
    private val bandLo = armMin * 1.02f
    private val bandHi = armMax * 0.98f

    private val phases = (0..20).map { it / 20f }

    private class Chain(
        val hand: Joint, val shoulder: Joint, val elbow: Joint,
        val l1: Float, val l2: Float
    )

    private val chains = listOf(
        Chain(Joint.HAND_A, Joint.SHOULDER_A, Joint.ELBOW_A, def.upperArmLength, def.forearmLength),
        Chain(Joint.HAND_P, Joint.SHOULDER_P, Joint.ELBOW_P, def.upperArmLength, def.forearmLength)
    )

    /** By-value snapshot: `produceFrame(...).pose` is the Finalizer's reused buffer. */
    private class Sample(
        val p: Float,
        val chain: Chain,
        val shoulder: Vector3,
        val elbow: Vector3,
        val hand: Vector3,
        val declared: Vector3
    ) {
        fun declaredDistance() = distance(shoulder, declared)
        fun relocation() = distance(hand, declared)
        fun elbowInteriorDeg(): Float {
            val d = distance(shoulder, hand)
            val c = ((chain.l1 * chain.l1 + chain.l2 * chain.l2 - d * d) /
                (2f * chain.l1 * chain.l2)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(c.toDouble())).toFloat()
        }
    }

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 3500f
    )

    /**
     * Publishes the pose through the production pipeline (warmed, exactly as playback drives it) and
     * snapshots every value DURING the pass — storing `produceFrame(...).pose` references would alias
     * every sample to the last frame.
     */
    private fun publishedSamples(): List<Sample> {
        val pose = HamstringStretchPose()
        val pipeline = SkeletonPipeline(def)
        for (k in 0..10) pipeline.produceFrame(pose, ctx(0.3f))

        val out = ArrayList<Sample>(phases.size * chains.size)
        for (p in phases) {
            val frame = pipeline.produceFrame(pose, ctx(p)).pose
            for (c in chains) {
                val declared = frame.limbTargets.single { it.joint == c.hand }.world
                out += Sample(
                    p, c,
                    copy(frame.getJoint(c.shoulder)),
                    copy(frame.getJoint(c.elbow)),
                    copy(frame.getJoint(c.hand)),
                    copy(declared)
                )
            }
        }
        return out
    }

    private val samples by lazy { publishedSamples() }

    private fun f(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    private fun vec(v: Vector3) = String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z)

    /** Non-vacuity: the sweep really produced distinct, carrier-bearing frames. */
    @Test
    fun theSweepPublishesDistinctRealizedFrames() {
        assertEquals(phases.size * chains.size, samples.size)

        val side = samples.filter { it.chain.hand == Joint.HAND_A }
        val spread = side.maxOf { it.hand.x } - side.minOf { it.hand.x }
        assertTrue(
            "the fold must actually travel (HAND_A x spread = ${f(spread)})",
            spread > 100f
        )
        assertEquals(
            "every phase must publish its own frame (aliased samples would collapse)",
            phases.size, side.map { it.hand.x }.distinct().size
        )
    }

    /**
     * The core M13 invariant: the published hand realizes the declared target.
     *
     * FALSE before the fix at p = 0.00 (`HAND_A` relocated `2.9237` u, `HAND_P` the same); true
     * everywhere once the declared target is projected onto the chain's own band.
     */
    @Test
    fun forwardReachIsPublishedWhereThePoseDeclaredIt() {
        val violations = samples.filter { it.relocation() > 1e-3f }
        val worst = samples.maxByOrNull { it.relocation() }!!
        assertTrue(
            ("the IK solver relocated the authored hand target (${violations.size}/${samples.size} samples):\n" +
                violations.joinToString("\n") {
                    "p=${f(it.p)} ${it.chain.hand.name} published=${vec(it.hand)} declared=${vec(it.declared)} " +
                        "relocation=${f(it.relocation())}"
                } +
                "\nworst: p=${f(worst.p)} ${worst.chain.hand.name} relocation=${f(worst.relocation())}"),
            violations.isEmpty()
        )
    }

    /**
     * The declared target must stay inside the arm chain's reachable annulus across the whole fold —
     * reachable-by-construction (R2), not merely clamped downstream.
     */
    @Test
    fun declaredReachStaysInsideTheArmChainsReachableBand() {
        val bad = samples.filter {
            it.declaredDistance() < bandLo - 1e-3f || it.declaredDistance() > bandHi + 1e-3f
        }
        val lowest = samples.minByOrNull { it.declaredDistance() }!!
        assertTrue(
            ("declared reach leaves the band [${f(bandLo)}, ${f(bandHi)}]:\n" +
                bad.joinToString("\n") {
                    "p=${f(it.p)} ${it.chain.hand.name} d=${f(it.declaredDistance())}"
                } +
                "\nminimum measured = ${f(lowest.declaredDistance())} at p=${f(lowest.p)} " +
                lowest.chain.hand.name +
                " (the arm's own minimum-flexion reach is ${f(armMin)})"),
            bad.isEmpty()
        )
    }

    /**
     * The relocation's own signature: before the fix the realized arm sat on exactly the chain's
     * minimum-flexion stop (`30.000°`) with the elbows above the shoulders. The realized arm must be
     * off that stop — the pose's shape must come from its authoring, not from a clamp.
     */
    @Test
    fun realizedArmIsNeverPinnedOnTheFlexionStop() {
        val stop = def.armIKConstraint.minimumFlexionAngle
        val bad = samples.filter { it.elbowInteriorDeg() <= stop + 0.25f }
        val lowest = samples.minByOrNull { it.elbowInteriorDeg() }!!
        assertTrue(
            ("realized elbow interior angle pinned on the ${f(stop)}° stop (${bad.size}/${samples.size} " +
                "samples):\n" +
                bad.joinToString("\n") {
                    "p=${f(it.p)} ${it.chain.hand.name} interior=${f(it.elbowInteriorDeg())}° " +
                        "elbowAboveShoulder=${f(it.elbow.y - it.shoulder.y)}"
                }),
            bad.isEmpty()
        )
        assertTrue(
            ("minimum realized interior angle = ${f(lowest.elbowInteriorDeg())}° at p=${f(lowest.p)}"),
            lowest.elbowInteriorDeg() > stop
        )
    }

    /**
     * The fix must correct the reach band without steering the reach somewhere else: the hands still
     * start in front of the chest and still travel to the extended foot (BPS §6 — "Arms reach toward
     * the extended foot"; here 10 u short of the ankle and 20 u above the mat at the end of the fold).
     */
    @Test
    fun theReachStillAimsAtTheExtendedFoot() {
        val pose = HamstringStretchPose()
        val pipeline = SkeletonPipeline(def)
        for (k in 0..10) pipeline.produceFrame(pose, ctx(0.3f))

        val start = pipeline.produceFrame(pose, ctx(0f)).pose
        val handStart = copy(start.getJoint(Joint.HAND_A))
        val chestStart = copy(start.getJoint(Joint.CHEST))
        assertTrue(
            ("the reach must still start in front of and above the chest: hand=${vec(handStart)} " +
                "chest=${vec(chestStart)}"),
            handStart.x > chestStart.x && handStart.y > chestStart.y
        )

        val end = pipeline.produceFrame(pose, ctx(1f)).pose
        val handEnd = copy(end.getJoint(Joint.HAND_A))
        val knee = copy(end.getJoint(Joint.KNEE_F))
        val toe = copy(end.getJoint(Joint.TOE_F))
        val ankle = copy(end.getJoint(Joint.ANKLE_F))
        assertTrue(
            ("the reach must pass the extended knee: hand.x=${f(handEnd.x)} knee.x=${f(knee.x)}"),
            handEnd.x > knee.x
        )
        val toFoot = minOf(distance(handEnd, ankle), distance(handEnd, toe))
        assertTrue(
            "the reach must end at the extended foot: hand=${vec(handEnd)} ankle=${vec(ankle)} " +
                "toe=${vec(toe)} distance=${f(toFoot)}",
            toFoot < 40f
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Blast radius: every OTHER production pose class is untouched
    // ---------------------------------------------------------------------------------------------

    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val digest = corpusDigest()
        assertEquals(
            "geometry of the production poses outside the corrected hamstring stretch must be " +
                "byte-identical to the pre-M13 tree (the digest covers every joint of every sampled " +
                "frame of every other production pose class); a change here means the correction " +
                "leaked outside its scope. measured=$digest pinned=$UNAFFECTED_CORPUS_DIGEST",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    /** Every concrete production pose class in `poses/` except the corrected hamstring stretch. */
    private fun corpusDigest(): Long {
        var dir = java.io.File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        var moduleRoot: java.io.File? = null
        for (attempt in 0 until 8) {
            if (java.io.File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) {
                moduleRoot = dir; break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate the app module root")
        val names = java.io.File(root, "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot {
                it.startsWith("Base") || it == "PoseRegistry" || it == "HamstringStretchPose"
            }
            .sorted()
        assertTrue(
            "anti-vacuity: the digest corpus must contain the other poses (found ${names.size})",
            names.size >= 45
        )

        val samples2 = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        var hash = 1125899906842597L
        for (name in names) {
            val pipeline = SkeletonPipeline(def)
            val builder = MotionProbe.build(name)
            hash = hash * 31 + name.hashCode()
            for (p in samples2) {
                val frame = SkeletonPose().apply {
                    copyFrom(pipeline.produceFrame(builder, PoseContext(p, Side.LEFT, def)).pose)
                }
                for (joint in Joint.entries) {
                    val v = frame.getJoint(joint)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.x)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.y)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.z)
                }
            }
        }
        return hash
    }

    companion object {
        /**
         * Digest of the 50 unaffected production pose classes (every joint, every sampled frame,
         * `HamstringStretchPose` excluded because it is the M13 correction). Measured **equal on the
         * pre-fix tree (`0301563`) and on the corrected tree** — i.e. the M13 change is confined to
         * `HamstringStretchPose`. Attribution to M13 is direct, not inferred from this digest: the
         * whole-corpus dump (49 registry poses × 5 progress × every joint XYZ, `245` pose-frames)
         * differs in exactly `1` frame — `hamstring_stretch_hold` at `p=0.0`, 12 arm-chain joints,
         * max `0.8930` u at `FINGERTIPS_A` — with the other `244` frames byte-identical (the same
         * dump re-measured on both trees via a `git stash` round-trip, `md5sum -c` on the restored
         * pose file). This guard's own mutation check is the observed RED when the corrected pose is
         * folded back into the corpus.
         *
         * **Re-baselined by the M11/M12 limb-realization migration**
         * (`fix/m11-m12-limb-realization-migration`, off `a8d07cf`): `HamstringStretchPose` is still the
         * only excluded class, so this corpus contains `LatStretchPose` (M11 — the canonical authored
         * hierarchy replaces the hand-rolled tree, publishing `LUMBAR`/`CLAVICLE_*`/`SCAPULA_*` instead
         * of the world origin) and `CatCowPose` (M12 — the four-point support declaration and the
         * reachable-by-construction leg targets). Observed RED on the previous value before this
         * re-baseline. Attribution is direct, not inferred: the whole-corpus dump (`50` classes × `5`
         * samples × every joint, `8415` rows, `git stash` round-trip on the two pose files) differs in
         * exactly `95` xyz rows — `70` in `CatCowPose`, `25` in `LatStretchPose` — and the other `48`
         * classes are byte-identical. That pass's own blast-radius guard is
         * `M11M12LimbRealizationMigrationTest.UNAFFECTED_CORPUS_DIGEST`.
                  *
         * **Re-baselined by the M15 wall/forearm contact-plane correction**
         * (`fix/m15-wallslides-wall-geometry`, off `4203fff`): this corpus contains `WallSlidesPose`, the
         * pose M15 corrects — its arm chain is now authored in the wall prop's own contact plane, its
         * elbow is placed on that plane, and the wall prop itself spans the athlete instead of stopping
         * below the pelvis. Observed RED on the previous value `4572325181887128495` before the re-baseline (this live
         * run measured `2817082625550222209`). Attribution is direct, not inferred: the whole-corpus dump (`51`
         * classes × `9` samples × every joint XYZ, plus every `maxIkClampAmount` /
         * `boneLengthsVerified` / `supportedPoints` stamp, the environment props and the declared limb
         * targets — `16524` rows — over a `git stash` round-trip on the corrected pose file with
         * `md5sum -c` on restore) differs in exactly `126` rows, ALL of them inside `WallSlidesPose`:
         * the two arm chains' `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/`KNUCKLES_*`/`FINGERTIPS_*`
         * (`12` joints × `9` samples = `108`) plus the `9` `TARGETS` and `9` `ENV` rows — with the other
         * `50` classes byte-identical and the reachability stamp unchanged
         * (`maxIkClampAmount` `0.047028` on both trees). M15's own blast-radius guard is
         * `M15WallSlidesWallGeometryTest.UNAFFECTED_CORPUS_DIGEST`.
         * **Re-baselined by the B2 runner's-lunge back-knee correction**
         * (`fix/b2-wgs-back-knee-plane`, off `2fb6079`): this corpus contains
         * `DynamicWorldsGreatestStretchPose`, the pose B2 corrects. Its back leg's stance is now the
         * extension the pose's own KDoc declares — the ankle authored one full chain reach behind the
         * hip, at the definition's own floor-contact height, with the knee's bend side derived from the
         * hip→ankle chord — so the realized `KNEE_B` sits `+13.162892` ABOVE the mat instead of the
         * `−46.105583` BELOW it that T2 pinned.
         * Observed RED on the previous value `2817082625550222209` before the re-baseline (this live run measured
         * `5171161262690478550`). Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5`
         * samples × every joint XYZ = `8415` rows, taken in a pristine `origin/main` @ `2fb6079`
         * worktree and on this tree, then diffed) differs in exactly `20` rows, ALL of them inside
         * `DynamicWorldsGreatestStretchPose` (`KNEE_B`/`ANKLE_B`/`HEEL_B`/`TOE_B` × the `5` samples,
         * max `82.2520` u at `HEEL_B`), with the other `50` classes byte-identical and the pose's
         * reachability stamp unchanged (`maxIkClampAmount` `21.640945` / `13.396454` / `7.5872955` at
         * p = 0 / 0.25 / 0.5 on BOTH trees — that clamp is the pose's support arm, and B2 does not
         * touch the arms). B2's own blast-radius guard is `WorldsGreatestStretchBackKneePlaneTest`.
         *
        * **Re-baselined by the B1 diamond push-up elbow-plane correction**
        * (`fix/b1-diamond-pushup-elbow-plane`, off `2fb6079` — the T2 merge): this corpus means "every
        * production pose class except the ones this pass corrects", so it contains `DiamondPushUpPose`,
        * whose elbow pole is re-authored onto the trunk's own long axis. The inherited Z-dominant pole
        * shape is correct for a grip whose hands sit at or outside the shoulder line; the diamond grip is
        * `0.1` (the hands come to the fused base `4.6` from the midline against the shoulder joint's
        * `46`), so the pole's perpendicular residual collapsed onto the chord's downward basis vector and
        * realized the elbow `19.91` u BELOW the pose's own declared plane at the bottom of the rep
        * (measured `p = 0.5`; the pose is a pinned, attributed open item in the T2 invariant, whose entry
        * this correction removes in the same change). Observed RED on the previous value `2817082625550222209` before
        * the re-baseline (this live run measured `-5113577443173513437`). Attribution is direct, not
        * inferred
        * whole-corpus dump (`51` classes x `5` samples x every joint XYZ, `8415` rows, a `git stash`
        * round-trip on the corrected pose file with `md5sum -c` on restore) differs in exactly `60` rows,
        * ALL of them inside `DiamondPushUpPose` — `ELBOW_A`/`ELBOW_P` at all five samples
        * (`19.96 ... 46.79` u) plus the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` pair
        * (<= `8e-6` u float drift at four samples, and `5.29` / `10.57` / `19.38` u at `p = 0.5`, where
        * the engine's planted-hand flattening now fires because the elbow is above the hand) — with the
        * other `50` classes byte-identical. B1's own regression is `DiamondPushUpElbowClearanceTest`.
         *
         * **Re-measured by the B1 integration** (this branch merges `fix/b1-diamond-pushup-elbow-plane`
         * @ `29ee54b`, off `2fb6079`, onto the B2 merge `f8f8b24`): this corpus contains BOTH corrected
         * poses, so neither pass's committed value was valid on the merged tree. Observed RED on the
         * B2-merged value `5171161262690478550` before this re-baseline (this live run, with B1 integrated,
         * measured `-2759498806033257096`). The digest's move from the B2-merged value is B1's own delta
         * (`DiamondPushUpPose`'s `ELBOW_A`/`ELBOW_P` plus the derived hand chain — `60` rows of the
         * whole-corpus dump, per B1's own record); from the pre-B2 value it is the union of B2's `20`
         * rows and B1's `60`.
         *
         * **Re-baselined by the B3 side-plank support-leg correction** (`fix/b3-sideplank-knee-plane`,
         * rebased onto the B2 merge `f8f8b24`, with B1 integrated): this corpus contains
         * `IsometricSidePlankPose`, the pose B3 corrects — the support leg's residual knee bend is
         * authored out of the mat now (the bend plane's pole `(0, -1, 0)` → `(0, 1, 0)`), so the pose
         * publishes `KNEE_B` above its own declared plane instead of `25.8897` below it. Observed RED on
         * the B1-integrated value `-2759498806033257096` before this re-baseline (this live run measured `7812330171039804426`).
         * Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5` samples × every
         * joint XYZ, `8415` rows, over a `git stash` round-trip on the corrected pose file with
         * `md5sum -c` on restore) differs from the pre-B3 tree in exactly `5` xyz rows — all `5` samples
         * of `IsometricSidePlankPose`'s `KNEE_B` — while B1's `60` `DiamondPushUpPose` rows and B2's `20`
         * `DynamicWorldsGreatestStretchPose` rows are untouched by this pass. B3's own gate is
         * `IsometricSidePlankKneePlaneTest`.
         * **Re-baselined by the B4 supine elbow-plane correction**
         * (`fix/b4-glute-bridge-pelvic-tilt-elbow-plane`, off `ba3928b` — the B1+B2+B3 tree): this corpus
         * contains `GluteBridgePose` and `PelvicTiltPose`, the two supine poses B4 corrects. Their arms' bend
         * side is re-authored onto the poses' own lateral axis: the family's `(0, -1, ∓1)` pole spent its `-Y`
         * component on the horizontal shoulder→hand chord's DOWNWARD basis vector (measured
         * `phat_y = -0.6995 … -0.7079` against `h = 58.48 … 60.74`), realizing `ELBOW_A/P` `28.6 … 33.4` u
         * below each pose's own declared mat at EVERY phase. Both poses' elbows now lie in the floor plane
         * (`+7.1 … +12.8` u) and the engine's planted-hand flattening fires with them. Observed RED on the
         * previous value `7812330171039804426` before the re-baseline (this live run measured `-4360790078150551480`). Attribution is direct,
         * not inferred: the whole-corpus dump (`51` classes × `5` samples × every joint XYZ, `8415` rows, over a
         * `git stash` round-trip on the two corrected pose files with `md5sum -c` on restore) differs in
         * exactly `116` rows, ALL of them inside those two poses (`GluteBridgePose` `56`, `PelvicTiltPose` `60`
         * — the two `ELBOW_*` plus each arm's derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain, max
         * `43.0165` u at `GluteBridgePose` `ELBOW_A` `p = 1.0`), with the other `49` classes byte-identical and
         * every pose's stamps
         * unchanged (`maxIkClampAmount` reads identically on both trees — it folds the LEGS' own
         * min-reach clamp — with `boneLengthsVerified = true` and `straightIntentDropped = false`
         * everywhere). B4's own gate is `SupineArmElbowPlaneTest`.
         *
         * **Re-baselined by the B4 trunk correction** (`fix/b4-pelvic-tilt-trunk-ground-plane`, off the
         * B4-elbow merge `7012ccb`): this corpus contains `PelvicTiltPose`, whose rigid trunk is
         * re-authored out of the mat (the tilt's DIRECTION — the pose was carrying
         * `CHEST`/`SHOULDER_A/P`/`NECK_END`/`HEAD_POS` `120/138·sin(0.12)` below a pelvis whose resting
         * layer is `14`). Observed RED on the previous value `-4360790078150551480` before the
         * re-baseline (this live run measured `2038526775867347550`). Attribution is direct, not
         * inferred: the whole-corpus dump (`51` classes × `5` samples × every joint XYZ, `8415` rows,
         * over a `git stash` round-trip on the corrected pose file with `md5sum -c` on restore) differs
         * in exactly `56` rows, ALL of them inside `PelvicTiltPose` (its trunk chain plus the arm joints
         * that hang off the moved shoulder, max `33.0581` u at `HEAD_POS` `p = 1.0`; the pose's legs,
         * pelvis and the world-origin joints are unchanged, and the whole `p = 0.0` sample is
         * byte-identical because the authored tilt is zero at rest), with the other `50` classes
         * byte-identical. The trunk's own gate is `PelvicTiltTrunkPlaneTest`.
         *
         *
         * **Re-baselined by the first reach-band cleanup batch** (`fix/reach-band-batch1-airsquat-squat`,
         * off the C2 merge `d6f4f7f`): this corpus contains `AirSquatPose` and `SquatPose`, whose
         * authored limb targets are now projected onto their own chains' reachable annulus (R2/R4) — the
         * standing-phase ankle was authored as a locked-out leg (`210.288` against the engine's `0.98`
         * extension cap at `205.800`) and the counterbalance reach never left a `9.2 … 15.9` unit radius
         * against the arm chain's `minReach = 40.134`, so the solver relocated the realized
         * end-effector along the authored ray (`4.488 … 30.934` u) and the publishable geometry WAS the
         * projection. Observed RED on the previous value `2038526775867347550` before the re-baseline (this live run
         * measured `-8286303796776597892`). Attribution is direct, not inferred: a whole-corpus dump (`51` classes ×
         * `5` samples × every joint XYZ plus every stamp, the declared limb targets, the supported
         * points and the environment, `255` rows) diffed between this branch and a `git worktree` of
         * `origin/main` @ `d6f4f7f` differs in exactly `152` rows — `76` in each of the two poses, ALL
         * of them limb-chain joints (`KNEE_*` max `0.0495` u, `ANKLE_*`/`HEEL_*`/`TOE_*` `0.0205` u, the
         * derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `<= 0.0056` u, `ELBOW_*`
         * `<= 0.0006` u) — with the other `49` classes byte-identical, every `support` and `env` row
         * byte-identical, and the two poses' reachability stamp dropping from `30.93442 / 30.00000 /
         * 24.24812` to exactly `0.000000` (the relocation this batch removes). The batch's own gate is
         * `SquatReachBandAuthoringTest`; the three pose files stashed on the base tree re-run this guard
         * GREEN on the pre-baseline value (measured in the same session).
          *
          * **Re-baselined by the second reach-band cleanup batch** (`fix/reach-band-batch2-deepsquat-jump-cossack`,
          * off the first reach-band batch's merge `ef9400f`): this corpus is "every production pose class
          * except the classes this correction owns", so it includes the batch's two corrected poses
          * (`DeepSquatHoldPose`, `JumpSquatPose`). Observed RED on the pre-baseline value `-8286303796776597892`
          * before the re-baseline (this live run measured `-1036273880702829004` below). Attribution is direct, not
          * inferred
          * from this digest: the whole-corpus dump (`51` classes × `14` phases × every `Joint.entries` XYZ ×
          * both frame conditions — `47,124` rows, full float bits) measured on a worktree of `origin/main` @
          * `ef9400f` and on this branch differs in exactly `584` rows — `224` in `DeepSquatHoldPose`, `360` in
          * `JumpSquatPose` — ALL of them limb-chain joints (`KNEE_*` max `0.0484` u, `ANKLE_*`/`HEEL_*`/`TOE_*`
          * `0.0206` u, the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `0.0053` u, `ELBOW_*`
          * `0.0006` u), with the other `49` classes byte-identical — `CossackSquatPose` included, which this
          * batch deliberately does NOT change — and every pelvis/spine/girdle/head joint byte-identical, i.e.
          * the batch is target-side only (a root-side "fix" would have moved the holds' depths). The batch's
          * own gate is `ReachBandBatch2AuthoringTest`.
          *
          * **Re-baselined by the third reach-band cleanup batch**
          * (`fix/reach-band-batch3-halfkneel-hamstring-cobra`, off the #254 merge `cf8a14f`): this corpus is
          * "every production pose class except the classes this correction owns", so it contains
          * `HalfKneelingStretchPose` and `ProneCobraStretchPose` — the batch's poses. Their authored limb targets sat outside their own
          * chains' reachable annulus and are now projected onto it (R2/R4). Observed RED on the pre-baseline value `-1036273880702829004` before the re-baseline
          * (this live run measured `3463098060376872084`). Attribution is direct, not inferred: the whole-corpus A/B (`51`
          * classes × `15` phases × every `Joint.entries` XYZ × both frame conditions — `60,660` rows, full
          * float bits) measured on a worktree of `origin/main` @ `cf8a14f` and on this branch differs in
          * exactly `828` rows — `450` in `HalfKneelingStretchPose`, `180` in `HamstringStretchPose`, `198` in
          * `ProneCobraStretchPose`, ALL of them limb-chain joints (the arm chain's `ELBOW_*` max `0.0356` u,
          * the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `0.0206` u, the tucked leg's
          * `TOE_B`/`HEEL_B`/`ANKLE_B` `0.0059` u and `KNEE_B` `0.0002` u), with the other `48` production
          * classes byte-identical — `CouchStretchPose` included, which shares this site's authoring helper
          * and is deliberately NOT re-scoped — and every pelvis/spine/girdle/head joint of the three
          * byte-identical too, i.e. the batch is target-side only (a root-side "fix" would have moved their
          * stances). The batch's own gate is `ReachBandBatch3AuthoringTest`.
          *
          * **Re-baselined by the fourth (final) reach-band cleanup batch**
          * (`fix/reach-band-batch4-remaining-candidates`, off the #255 merge `ca011ad`): this corpus is "every
          * production pose class except the classes each correction owns", so it contains the batch's five poses
          * (`CouchStretchPose`, `QuadrupedThoracicRotationsPose`, `GluteBridgePose`, `PelvicTiltPose`,
          * `ThoracicExtensionPose`). Their authored limb targets sat outside their own chains' reachable annulus and
          * are now projected onto it (R2/R4). Observed RED on the pre-baseline value `3463098060376872084` before this re-baseline
          * (the live run measured `8362792623341605109`). Attribution is direct, not inferred: the whole-corpus A/B (`51` classes ×
          * `16` frames — a fresh pipeline's cold first frame plus `15` phases of an advancing pipeline — × every
          * `Joint.entries` XYZ AND every joint rotation, full float bits, `58,464` rows) measured on a worktree of
          * `origin/main` @ `ca011ad` and on this branch differs in exactly `852` rows — `240` in
          * `ThoracicExtensionPose`, `197` in `CouchStretchPose`, `176` in `PelvicTiltPose`, `146` in
          * `GluteBridgePose`, `93` in `QuadrupedThoracicRotationsPose` — i.e. `652` published joint rows, `128`
          * declared-target rows and `72` state rows whose ONLY moving field is the reachability stamp. Every differing
          * joint belongs to the pose's own corrected limb chain (arms: `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/
          * `KNUCKLES_*`/`FINGERTIPS_*`; legs: `KNEE_*`/`ANKLE_*`/`HEEL_*`/`TOE_*`), max published move `0.0324` u
          * (`QuadrupedThoracicRotationsPose`), and the other `46` production classes are byte-identical — every joint
          * ROTATION byte-identical everywhere, every pelvis/spine/girdle/head joint of the five unchanged, and every
          * support set, ground level, environment prop and state flag identical, i.e. the batch is target-side only.
          * The batch's own gate is `ReachBandBatch4AuthoringTest`.
          * **Re-baselined by the canonical-`SkeletonFactory` pose batch**
          * (`fix/canonical-skeletonfactory-pose-batch`, off the #256 merge `4a32d84`): the ten remaining
          * hand-rolled pose trees (`ArmCirclesPose`, `BurpeePose`, `FacePullPose`, `GluteBridgePose`,
          * `HipCarsPose`, `KettlebellSwingPose`, `MountainClimberPose`, `PelvicTiltPose`,
          * `ScapularRetractionPose`, `WallSlidesPose`) adopt `SkeletonFactory.createStandardSkeleton()` (the
          * M11 residue class — the last poses whose five canonical joints published at the world origin), and
          * all ten of them are inside this corpus. Observed RED on the previous value `<8362792623341605109>` before
          * this re-baseline (the live run measured `-5841693900045600427`). Attribution is direct, not inferred: the
          * whole-corpus A/B (`51` classes × `16` frames — a fresh pipeline's cold first frame plus `15` advancing
          * phases of a long-lived pipeline — × every `Joint.entries` position AND rotation as FULL FLOAT BITS,
          * plus the published state/stamps, the declared limb targets, the built authoring intents, the support
          * declarations and the environment metadata — `32,403` rows) measured on the pristine base tree
          * (`4a32d84`) and on this tree differs in exactly `800` rows — `80` in each of the ten poses, ALL of
          * them the five canonical joints (`LUMBAR`, `CLAVICLE_A/P`, `SCAPULA_A/P`) × `16` frames, each moving
          * from the world origin to its authored pass-through transform — with every other joint position and
          * rotation, every declared limb target, every stamp, every support set and every environment row
          * byte-identical. The batch's own gate is `CanonicalSkeletonFactoryPoseBatchTest`.
         *
          * **Re-baselined by the animation-coverage phase, batch 1** (`feat/animation-coverage-01`, based on
          * `7df32c0`): this corpus is "every production pose class except its own corrected pose", so the four pose
          * classes the batch ADDED (`HorseStancePose`, `WallSitPose`, `AnkleMobilityPose`, `CalfStretchPose`) entered
          * it. Attribution measured, not inferred: the per-pose digest probe (this guard's own hashing recipe, a fresh
          * pipeline per pose) run on the pristine `origin/main` worktree and on this branch reported **all 51
          * pre-existing pose classes byte-identical** — `0` differing digests, `4` added, `0` removed — on both the
          * `4a32d84` and the rebased `7df32c0` base, so the observed RED was corpus membership, not geometry drift.
          * Pre-rebaseline measurement: `-5841693900045600427`.
         *
         * **Re-baselined by the animation-coverage phase, batch 2** (`feat/animation-coverage-02`, based on the
          * #258 merge `6887738`): this corpus is "every production pose class except its own corrected pose", so the
          * four pose classes the batch ADDED (`RowsPose`, `DipsPose`, `BandPullApartPose`, `YTRaisesPose`) entered it
          * (the family base `BaseBarSupportPose` is filtered out of every corpus by the `Base*` rule). Attribution
          * measured, not inferred: the per-pose digest probe (this guard's own hashing recipe, a fresh pipeline per
          * pose) run on the pristine `origin/main` worktree (`6887738`) and on this branch reported **all `55`
          * pre-existing pose classes byte-identical** — `0` differing digests, `4` added, `0` removed — so the
          * observed RED was corpus membership, not geometry drift. Pre-rebaseline measurement: `-8409977029847140584`.
         */
        const val UNAFFECTED_CORPUS_DIGEST = -6285256207466094342L
    }
}

private fun distance(a: Vector3, b: Vector3) =
    sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2))

private fun copy(v: Vector3) = Vector3().set(v)
