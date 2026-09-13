package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.CatCowPose
import com.monkfitness.app.poses.LatStretchPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **M11 + M12 — the remaining limb-realization / carrier migration for `LatStretchPose` and
 * `CatCowPose`** (P11 whole-system audit, `docs/STABILIZATION_AUDIT.md` §3 rows M11/M12 and the
 * §4 "TODO — P1" item 2: *"H2 complement — migrate `LatStretchPose` (M11) and `CatCowPose` (M12)
 * onto `bakeIkLimb`/gaze helpers for full carrier coverage"*).
 *
 * ## What the two findings say on the current tree, and what was already migrated
 *
 * The literal `solveIK` bypass the rows name is GONE for both poses (P12 WP-D, `5727091`): both now
 * realize their four limbs through the registered package-level `bakeIkLimb`, both carry four
 * `limbTargets` with a complete declared realization context, and `CatCowPose` already declares its
 * gaze. The tests below therefore do not re-assert the migration that landed — they assert the
 * **residue the same rows name** ("for full carrier coverage"), each measured on the PUBLISHED
 * runtime path (`SkeletonPipeline.produceFrame(pose, ctx)` in the deployed
 * `IK_STAGE_ACTIVE = true` configuration, i.e. the frame the renderer and the validators read):
 *
 *  - **M11-a (legacy authored hierarchy).** `LatStretchPose` still builds its OWN hand-rolled node
 *    tree (the pre-factory shape) whose node set stops at `PELVIS → CHEST` and
 *    `CHEST → SHOULDER_*`. Five canonical joints are never authored, so they publish at the WORLD
 *    ORIGIN: measured `|LUMBAR − PELVIS| = 144.4507` (the pass-through is `0.0000` on every pose
 *    built from `SkeletonFactory.createStandardSkeleton()`), `|CLAVICLE_A| = |SCAPULA_A| = 0.0000`.
 *    That is the class the M3/M5 pass corrected for `ReverseSnowAngelPose` ("its hand-rolled tree
 *    has no lower-spine segment, so `Joint.LUMBAR` publishes at the world origin").
 *  - **M11-b (gaze — RECORDED, deliberately NOT migrated).** The audit's work item names the "gaze
 *    helpers" as M11's other half, and the pose indeed declares no `headTarget`. Measured, a
 *    world-space gaze target is NOT expressible for this body: `resolveHeadTarget` writes the world
 *    delta as the neck/head LOCAL offset, which this pose's `0.95` rad trunk pitch re-applies, so the
 *    resolved head lands `0.9147` rad off the authored axis (the B-7/B-8b constraint the M3/M5 pass
 *    recorded for the prone family; `MIGRATION_RULES` A8 prohibits the pose-side compensation). The
 *    pose's authored head already lives in the chain's own frame, and
 *    [latStretchAuthoredHeadStaysOnThePosesOwnTrunkAxis] pins that resolved behaviour — and is the
 *    trap that turns RED the moment someone declares the naive world target.
 *  - **M12-a (raw floor-frame limb targets outside their own chain's band).** `CatCowPose` authors
 *    its leg end-effector as an absolute world literal `(50, ankleHeight, ±hipWidth)`, which its own
 *    leg chain cannot fold to at ANY phase: `42.5 … 45.0` against
 *    `SkeletonMath.minReach(112, 98, 30°) = 56.0090`. The engine answers by relocating the realized
 *    foot (`maxIkClampAmount = 11.0090 → 16.0090`, realised `ANKLE_F` `13.5090` units off the
 *    declared point at `p = 0.5`) — the M8 second clause / M13 defect class ("unreachable authoring
 *    silently solver-clamped").
 *  - **M12-b (no support model on the canonical channel).** The quadruped declares NO
 *    `metadata.support.contacts`, so `SkeletonPose.supportedPoints` publishes EMPTY for a pose whose
 *    BPS §8 base is a four-point contact (`Cat-Cow (Reps).md`: "both hands … and both knees … remain
 *    in contact with the floor"). `EnvironmentPenetrationTest`'s pinned census names this pose and
 *    attributes it to M12.
 *
 * ## Counter-evidence / what these tests deliberately do NOT assert
 *
 *  * No tolerance is added to any pre-existing assertion, and nothing is `@Ignore`d or `assume`d.
 *  * The pose's AUTHORED choreography is not re-litigated here: the leg pole's lateral component
 *    (which splays the knee ≈`69` units outside the hip line) and the spine articulation authored
 *    as the pelvis tilt are both measured and reported as open items rather than fixed — M11/M12's
 *    wording (cleanup / migration) names neither.
 */
class M11M12LimbRealizationMigrationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val samples = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** The leg chain's declared realization context (`CatCowPose`'s two leg bakes). */
    private val legConstraint = def.legIKConstraint
    private val legMinReach = SkeletonMath.minReach(def.thighLength, def.shinLength, legConstraint)
    private val legMaxReach = SkeletonMath.maxReach(def.thighLength, def.shinLength, legConstraint)

    /** The engine's own reachability flag band (`M8M9M10SupportDeclarationTest`'s convention). */
    private val reachabilityFlagBand = 0.1f

    private fun ctx(p: Float) = PoseContext(p, Side.RIGHT, def, 0.0166f, 2500f)

    /** A frame captured BY VALUE — the pipeline publishes a reused buffer (the T-7 trap). */
    private fun snapshot(frame: SkeletonPose): SkeletonPose = SkeletonPose().apply { copyFrom(frame) }

    /**
     * The PUBLISHED frame of the production path at [p], in the deployed configuration.
     *
     * [warm] selects the second frame condition: a pipeline that has already produced two frames
     * (mid-playback) instead of a genuinely cold first frame on a fresh pipeline.
     */
    private fun published(builder: PoseBuilder, p: Float, warm: Boolean = false): SkeletonPose {
        val pipeline = SkeletonPipeline(def)
        if (warm) {
            pipeline.produceFrame(builder, ctx(0.3f))
            pipeline.produceFrame(builder, ctx(0.4f))
        }
        return snapshot(pipeline.produceFrame(builder, ctx(p)).pose)
    }

    private fun dist(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // =========================================================================================
    // M11-a — LatStretchPose publishes the canonical authored hierarchy
    // =========================================================================================

    /**
     * The pose's tree must cover the canonical joint set, so the two-segment spine and the shoulder
     * girdle publish real transforms instead of the world origin (the legacy-tree signature the
     * M3/M5 pass measured on `ReverseSnowAngelPose`).
     */
    @Test
    fun latStretchPublishesTheCanonicalAuthoredHierarchy() {
        val pose = LatStretchPose()
        var worstLumbar = 0f
        var worstStray = 0f
        var stray = ""

        for (p in samples) {
            val f = published(pose, p)
            val pelvis = f.getJoint(Joint.PELVIS)
            val chest = f.getJoint(Joint.CHEST)

            // (1) LUMBAR is the PELVIS pass-through (Issue E): the chest resolves to the same world
            // transform as the old single PELVIS->CHEST link, so a non-zero gap is the unpublished
            // segment the hand-rolled tree leaves behind.
            val lumbarGap = dist(f.getJoint(Joint.LUMBAR), pelvis)
            if (lumbarGap > worstLumbar) worstLumbar = lumbarGap

            // (2) CLAVICLE/SCAPULA lie ON the CHEST->SHOULDER segment (they are pass-throughs), and
            // (3) no canonical joint may publish at the world origin.
            for ((joint, side) in listOf(
                Joint.CLAVICLE_A to Joint.SHOULDER_A,
                Joint.SCAPULA_A to Joint.SHOULDER_A,
                Joint.CLAVICLE_P to Joint.SHOULDER_P,
                Joint.SCAPULA_P to Joint.SHOULDER_P
            )) {
                val v = f.getJoint(joint)
                val strayMag = v.mag()
                if (strayMag > worstStray) { worstStray = strayMag; stray = "$joint@p=$p" }
                val onSegment = dist(v, chest) + dist(v, f.getJoint(side)) - dist(chest, f.getJoint(side))
                assertTrue(
                    "LatStretchPose $joint@p=$p must sit on the CHEST->$side segment (a pass-through " +
                        "node), but it is $onSegment units off it — v=$v chest=$chest " +
                        "shoulder=${f.getJoint(side)}",
                    onSegment < 0.5f
                )
            }
        }
        assertEquals(
            "LatStretchPose must publish LUMBAR as the PELVIS pass-through (Issue E: PELVIS -> LUMBAR " +
                "-> CHEST with a coincident, identity-rotation lumbar) — the hand-rolled tree leaves " +
                "it at the world origin, |LUMBAR - PELVIS| = $worstLumbar",
            0f, worstLumbar, 0.001f
        )
        assertTrue(
            "anti-origin: the canonical girdle joints must carry authored transforms, not (0,0,0) " +
                "(worst |joint| = $worstStray at $stray)",
            worstStray > 1f
        )
    }

    // =========================================================================================
    // M11-b — LatStretchPose declares its gaze through the canonical carrier
    // =========================================================================================

    /**
     * M11-b — the pose's head is authored in the CHAIN'S OWN FRAME, which is the only representation
     * that can express this body orientation, and the published frame must realize exactly it.
     *
     * `resolveHeadTarget` (the sole head writer) derives the gaze DIRECTION from a world delta and
     * writes it verbatim as the neck/head LOCAL offset; the neck's parent rotation then re-applies
     * it, so a world-space `headTarget` on a trunk pitched `0.95` rad resolves the head
     * `0.9147` rad off the authored axis (measured — see the pose's own KDoc for the recorded
     * finding and the M3/M5 `SupermanPose` precedent). Consequently this pose declares NO gaze
     * target, and this guard pins the resolved behaviour that must survive instead: the published
     * neck/head chain lies on the pose's authored trunk axis, at the authored bone lengths.
     *
     * This guard is GREEN on the untouched base tree by construction (the authored head is already
     * correct); it is a trap for the naive "declare the gaze" migration, which turns it RED — which
     * is exactly how the `0.9147` rad figure in the finding was measured.
     */
    @Test
    fun latStretchAuthoredHeadStaysOnThePosesOwnTrunkAxis() {
        val pose = LatStretchPose()
        val leanAngle = 0.95f // the pose's authored trunk lean (about +Z), unchanged by this migration
        val authored = Vector3(sin(leanAngle), cos(leanAngle), 0f).normalize()
        var worstDir = 0f
        var worstBone = 0f

        for (p in samples) {
            val f = published(pose, p)
            val n = f.getJoint(Joint.NECK_END)
            val h = f.getJoint(Joint.HEAD_POS)
            val bone = dist(n, h)
            worstBone = maxOf(worstBone, abs(bone - 18f))
            val dir = Vector3(h.x - n.x, h.y - n.y, h.z - n.z).normalize()
            worstDir = maxOf(worstDir, dist(dir, authored))
        }
        assertTrue(
            "the published NECK_END->HEAD_POS bone must stay at the authored ~18 units " +
                "(worst error $worstBone)",
            worstBone < 0.18f
        )
        assertTrue(
            "LatStretchPose's published head must lie on the pose's own authored trunk axis " +
                "(worst direction error $worstDir). A world-space `headTarget` here would resolve " +
                "the head 0.9147 rad off it — `resolveHeadTarget` writes the world delta as a LOCAL " +
                "offset and the pitched trunk re-applies it (the recorded M11-b finding; the " +
                "M3/M5 SupermanPose precedent authors the head in the chain's own frame for exactly " +
                "this reason)",
            worstDir < 1e-3f
        )
    }

    // =========================================================================================
    // M12-a — CatCowPose's leg targets are reachable as authored
    // =========================================================================================

    /**
     * "Reachable-by-construction" (the R2 reach-target rule the M8 pass applied to the five standing
     * poses and M13 to the hamstring reach): a pose authors the DIRECTION and stance it wants, and
     * the declared target must lie inside its own chain's band, so the realized limb is exactly what
     * the pose declared and the reachability signal stays honest.
     */
    @Test
    fun catCowLegTargetsAreReachableAsAuthored() {
        val pose = CatCowPose()
        val witnesses = mutableListOf<String>()

        for (p in samples) {
            val built = pose.build(ctx(p))
            val f = published(pose, p)
            assertEquals(
                "anti-vacuity: CatCowPose declares its four limbs (found ${built.limbTargets.size})",
                4, built.limbTargets.size
            )
            for ((end, hip) in listOf(Joint.ANKLE_F to Joint.HIP_F, Joint.ANKLE_B to Joint.HIP_B)) {
                val target = built.limbTargets.first { it.joint == end }
                val d = dist(target.world, f.getJoint(hip))
                witnesses.add("$end@p=$p d=$d band=[$legMinReach,$legMaxReach] clamp=${f.maxIkClampAmount}")
                assertTrue(
                    "CatCowPose $end@p=$p is authored $d from its chain root, OUTSIDE the leg chain's " +
                        "reachable band [$legMinReach, $legMaxReach] — the solver relocates the " +
                        "realized foot instead of realizing the declared target",
                    d >= legMinReach - 0.5f && d <= legMaxReach + 0.5f
                )
                val realised = dist(f.getJoint(end), target.world)
                assertTrue(
                    "the published $end@p=$p must BE the declared target (relocation $realised)",
                    realised <= 0.5f
                )
            }
            assertTrue(
                "the engine's own reachability flag must not fire for CatCowPose at p=$p " +
                    "(maxIkClampAmount=${f.maxIkClampAmount}, band=$reachabilityFlagBand) — the " +
                    "declared leg targets are unreachable authoring",
                f.maxIkClampAmount <= reachabilityFlagBand
            )
        }
        assertTrue("witnesses: $witnesses", witnesses.size == samples.size * 2)
    }

    // =========================================================================================
    // M12-b — CatCowPose declares its four-point base on the canonical channel
    // =========================================================================================

    /**
     * BPS §8 (`Cat-Cow (Reps).md`): "Four-point base throughout: both hands (palm/carpal arch) and
     * both knees (patella/shin on a padded surface) remain in contact with the floor." The
     * declaration belongs on the ONE canonical channel (`metadata.support.contacts`) and must reach
     * the published carrier under both frame conditions — that is what the B-5/R8 resolution exists
     * for, and what the pre-fix empty carrier meant was inert.
     */
    @Test
    fun catCowDeclaresItsFourPointBaseOnTheCanonicalChannel() {
        val pose = CatCowPose()
        val declared = pose.metadata.support.contacts.map { it.point }.toSet()
        assertEquals(
            "CatCowPose's BPS §8 base is a four-point contact (both hands + both knees); the " +
                "declaration must name exactly that on `metadata.support.contacts`",
            setOf(SupportPoint.LEFT_HAND, SupportPoint.RIGHT_HAND, SupportPoint.LEFT_KNEE, SupportPoint.RIGHT_KNEE),
            declared
        )
        for (point in declared) {
            assertTrue(
                "anti-vacuity: every declared contact must resolve through the ONE canonical " +
                    "SupportPoint->Joint map (found none for $point)",
                SupportMath.jointsFor(point).isNotEmpty()
            )
        }
        assertEquals(
            "the declared pivot must be the quadruped's own support base (the KneePushUpPose " +
                "precedent: hands + knees, pivot KNEES)",
            PivotType.KNEES, pose.metadata.support.pivot
        )

        for (warm in listOf(false, true)) {
            for (p in samples) {
                val f = published(pose, p, warm)
                assertEquals(
                    "the declaration must REACH the published carrier (${if (warm) "mid-playback" else "cold"} " +
                        "frame, p=$p): published supportedPoints=${f.supportedPoints}",
                    declared, f.supportedPoints.toSet()
                )
            }
        }
    }

    // =========================================================================================
    // Blast radius — the pass is confined to the two classes it names
    // =========================================================================================

    /** The app module root, located by walking up from the test JVM's working directory. */
    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) return dir
            dir = dir.parentFile ?: break
        }
        error("Could not locate the app module root from ${System.getProperty("user.dir")}")
    }

    private fun corpusDigest(): Long {
        val names = File(moduleRoot(), "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || it in corrected }
            .sorted()
        assertTrue(
            "anti-vacuity: the digest corpus must contain the untouched poses (found ${names.size})",
            names.size >= 45
        )
        assertTrue(
            "anti-vacuity: the correction scope must be exactly the two classes this pass names",
            corrected.size == 2
        )
        var hash = 1125899906842597L
        for (name in names) {
            val pipeline = SkeletonPipeline(def)
            val builder = MotionProbe.build(name)
            hash = hash * 31 + name.hashCode()
            for (p in samples) {
                val frame = published(builder, p)
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

    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val digest = corpusDigest()
        assertEquals(
            "geometry of the production poses outside {LatStretchPose, CatCowPose} must be " +
                "byte-identical to the pre-fix tree (every joint of every sampled frame of every " +
                "other production pose class); a change here means the migration leaked outside its " +
                "scope. measured=$digest pinned=$UNAFFECTED_CORPUS_DIGEST",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    companion object {
        /** The exact classes this pass owns. */
        private val corrected = setOf("LatStretchPose", "CatCowPose")

        /**
         * Blast-radius guard: the production corpus MINUS the two corrected classes.
         *
         * Measured **equal** on the pre-fix tree (`origin/main` @ `a8d07cf`) and on the corrected
         * tree (both `-7010204834070121618`) — the pass is confined to its own two poses. Captured
         * with the same digest definition as the sibling scope guards
         * (`M8M9M10SupportDeclarationTest`, `HamstringForwardReachTest`): 49 pose classes × 5 progress
         * samples × every joint XYZ, full float bits.
         *
         * Attribution is direct, not inferred from this digest: the whole-corpus dump (50 classes ×
         * 5 samples × every joint, `8415` rows, `git stash` round-trip on the two pose files) differs
         * in exactly `95` xyz rows — `70` inside `CatCowPose` and `25` inside `LatStretchPose` — with
         * the other `48` classes byte-identical, `165` rows carrying the `maxIkClampAmount` change
         * (`11.0090 … 16.0090 → 0.0000`) and `165` the `supportedPoints` change
         * (`∅ → LEFT_HAND/RIGHT_HAND/LEFT_KNEE/RIGHT_KNEE`).
                  *
         * **Re-baselined by the M15 wall/forearm contact-plane correction**
         * (`fix/m15-wallslides-wall-geometry`, off `4203fff`): this corpus contains `WallSlidesPose`, the
         * pose M15 corrects — its arm chain is now authored in the wall prop's own contact plane, its
         * elbow is placed on that plane, and the wall prop itself spans the athlete instead of stopping
         * below the pelvis. Observed RED on the previous value `-7010204834070121618` before the re-baseline (this live
         * run measured `-8765447390407027904`). Attribution is direct, not inferred: the whole-corpus dump (`51`
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
         * Observed RED on the previous value `-8765447390407027904` before the re-baseline (this live run measured
         * `-6411368753266771563`). Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5`
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
        * this correction removes in the same change). Observed RED on the previous value `-8765447390407027904` before
        * the re-baseline (this live run measured `1750636614578788066`). Attribution is direct, not
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
         * B2-merged value `-6411368753266771563` before this re-baseline (this live run, with B1 integrated,
         * measured `4104715251719044407`). The digest's move from the B2-merged value is B1's own delta
         * (`DiamondPushUpPose`'s `ELBOW_A`/`ELBOW_P` plus the derived hand chain — `60` rows of the
         * whole-corpus dump, per B1's own record); from the pre-B2 value it is the union of B2's `20`
         * rows and B1's `60`.
         *
         * **Re-baselined by the B3 side-plank support-leg correction** (`fix/b3-sideplank-knee-plane`,
         * rebased onto the B2 merge `f8f8b24`, with B1 integrated): this corpus contains
         * `IsometricSidePlankPose`, the pose B3 corrects — the support leg's residual knee bend is
         * authored out of the mat now (the bend plane's pole `(0, -1, 0)` → `(0, 1, 0)`), so the pose
         * publishes `KNEE_B` above its own declared plane instead of `25.8897` below it. Observed RED on
         * the B1-integrated value `4104715251719044407` before this re-baseline (this live run measured `-4921300646213740599`).
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
         * previous value `-4921300646213740599` before the re-baseline (this live run measured `1352323178305455111`). Attribution is direct,
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
         * layer is `14`). Observed RED on the previous value `1352323178305455111` before the
         * re-baseline (this live run measured `7751640032323354141`). Attribution is direct, not
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
         * projection. Observed RED on the previous value `7751640032323354141` before the re-baseline (this live run
         * measured `-7426363716919112133`). Attribution is direct, not inferred: a whole-corpus dump (`51` classes ×
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
          * (`DeepSquatHoldPose`, `JumpSquatPose`). Observed RED on the pre-baseline value `-7426363716919112133`
          * before the re-baseline (this live run measured `7148088516904126963` below). Attribution is direct, not
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
          * `HalfKneelingStretchPose`, `HamstringStretchPose` and `ProneCobraStretchPose` — the batch's poses. Their authored limb targets sat outside their own
          * chains' reachable annulus and are now projected onto it (R2/R4). Observed RED on the pre-baseline value `7148088516904126963` before the re-baseline
          * (this live run measured `-403923776399062201`). Attribution is direct, not inferred: the whole-corpus A/B (`51`
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
          * are now projected onto it (R2/R4). Observed RED on the pre-baseline value `-403923776399062201` before this re-baseline
          * (the live run measured `4495770786565670824`). Attribution is direct, not inferred: the whole-corpus A/B (`51` classes ×
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
          * all ten of them are inside this corpus. Observed RED on the previous value `<4495770786565670824>` before
          * this re-baseline (the live run measured `-4989351702075985912`). Attribution is direct, not inferred: the
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
          * Pre-rebaseline measurement: `-4989351702075985912`.
         *
         * **Re-baselined by the animation-coverage phase, batch 2** (`feat/animation-coverage-02`, based on the
          * #258 merge `6887738`): this corpus is "every production pose class except its own corrected pose", so the
          * four pose classes the batch ADDED (`RowsPose`, `DipsPose`, `BandPullApartPose`, `YTRaisesPose`) entered it
          * (the family base `BaseBarSupportPose` is filtered out of every corpus by the `Base*` rule). Attribution
          * measured, not inferred: the per-pose digest probe (this guard's own hashing recipe, a fresh pipeline per
          * pose) run on the pristine `origin/main` worktree (`6887738`) and on this branch reported **all `55`
          * pre-existing pose classes byte-identical** — `0` differing digests, `4` added, `0` removed — so the
          * observed RED was corpus membership, not geometry drift. Pre-rebaseline measurement: `-905866779321124405`.
         */
        const val UNAFFECTED_CORPUS_DIGEST = 8464633966248789933L
    }
}
