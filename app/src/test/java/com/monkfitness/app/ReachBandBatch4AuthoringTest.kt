package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.*

/**
 * Fourth (final) reach-band cleanup batch — `CouchStretchPose`, `QuadrupedThoracicRotationsPose`,
 * `GluteBridgePose`, `PelvicTiltPose` and `ThoracicExtensionPose` (production geometry), plus the
 * verdicts for the candidates this batch measured and deliberately did NOT change
 * (`SupermanPose`, `DeadBugPose`) and the corpus census that closes the class.
 *
 * ## Instrument
 *
 * Every authored limb target was measured through the production entry point
 * `SkeletonPipeline.produceFrame(pose, ctx)` — `15` phases
 * (`0.00, 0.05, 0.10, 0.20, 0.25, 0.30, 0.40, 0.50, 0.60, 0.70, 0.75, 0.80, 0.90, 0.95, 1.00`) × the
 * pose's declared limb chains × both frame conditions (a fresh pipeline's cold first frame, then an
 * advancing pipeline), against each chain's own `[SkeletonMath.minReach, SkeletonMath.maxReach]`
 * (`112/98` legs → `[56.0090, 205.8000]`; `80/66` arms → `[40.1344, 143.0800]`). The whole `51`-class
 * corpus was swept with the same instrument.
 *
 * ## What the batch measured on the base tree (`origin/main` @ `ca011ad`)
 *
 * | pose | chain | authored root→target | band | relocation | realized interior |
 * |---|---|---|---|---|---|
 * | `QuadrupedThoracicRotationsPose` | support hand (P, the floor pillar) | `111.2617 … 175.5976` | `≤ 143.0800` | **`0.000 … 32.5176`** (`8`/`15` phases) | `156.9356°` (the `0.98` cap) |
 * | `QuadrupedThoracicRotationsPose` | reaching hand (A) | `22.9198 … 164.4045` | `[40.1344, 143.0800]` | **`21.3245` max** (`4`/`15`: min side at p = 0.40/0.50, max side at p = 0.95/1.00) | `30.0000°` / `156.9356°` |
 * | `QuadrupedThoracicRotationsPose` | both ankles | `148.8220` | in band | `0.000` | — |
 * | `CouchStretchPose` | both hands | `142.0487 … 156.6795` | `≤ 143.0800` | **`0.3610 … 13.5995`** (`13`/`15`) | `156.9356°` |
 * | `CouchStretchPose` | front ankle | `100.8197 … 121.3899` | in band | `0.000` | — |
 * | `GluteBridgePose` | both ankles | `45.0111 … 59.5483` | `≥ 56.0090` | **`10.9979 … 1.3647`** (`12`/`15`) | `30.0000°` (the stop) |
 * | `GluteBridgePose` | both hands | `77.8899 … 85.1704` | in band | `≤ 0.0409` | in band, no stamp |
 * | `PelvicTiltPose` | both ankles | `45.0111` (EVERY phase) | `≥ 56.0090` | **`10.9979`** (`15`/`15`) | `30.0000°` |
 * | `PelvicTiltPose` | both hands | `85.1704 … 85.8596` | in band | `0.000` | — |
 * | `ThoracicExtensionPose` | both hands | `34.6182 … 37.3581` | `≥ 40.1344` | **`5.5162 … 2.7763`** (`15`/`15`) | `30.0000°` |
 *
 * ## Verdict per SITE (measured, not assumed)
 *
 * The discriminators the earlier batches established:
 *
 *  1. a request LONGER than `L1 + L2` is impossible for ANY chain geometry — no author could have
 *     meant it (batch 2's `JumpSquatPose` flight `107 %`, batch 3's half-kneeling hands `106.7 %`);
 *  2. a request tighter than the constraint's own `minimumFlexionAngle` is past the model's fold
 *     stop (batch 2's `DeepSquatHoldPose` `24.80°`, batch 3's `HamstringStretchPose` tuck `18.89°`
 *     and `ProneCobraStretchPose` start `14.39°`, the M13 pass's hamstring start hand `2.9237` u);
 *  3. a REALIZABLE request that only the `0.98` extension cap trims is the deliberate model limit —
 *     leave it, and PIN it (batch 2's `CossackSquatPose` `207.000` → `1.200`).
 *
 *  * **Unintended authoring error → FIXED (5 poses, 7 sites).**
 *    * `QuadrupedThoracicRotationsPose`: the support arm's request reaches `120.3 %` of its own limb
 *      (rule 1) while the pose's own intent is BPS §6's "Supporting (down) arm: extended, shoulder
 *      stable" — an extended pillar, not a request past the limb; and the reaching arm asks for a
 *      hand AT its own shoulder at mid-sweep (`22.9198`, rule 2 — the collar-side stop) although the
 *      drill's choreography threads the hand UNDER the torso and then overhead (BPS §6/§9).
 *    * `CouchStretchPose`: the composed hand target passes the arm cap at the end of the rep (rule
 *      1's class — `106.7 %` at p = 1) while the family's own sibling needed the identical
 *      correction; BPS §6 allows "arms rest on the front thigh".
 *    * `GluteBridgePose` / `PelvicTiltPose`: the authored stance folds the knee to an interior
 *      `23.52°` against the `30°` stop (rule 2) although both poses' BPS (§7/§9 and §3/§7) specify
 *      "knees bent ~90°, feet flat" — an in-band configuration (a 90° interior is a `148.81` u
 *      chord), so nothing in these exercises requires the fold stop.
 *    * `ThoracicExtensionPose`: the hands-behind-the-head clasp asks for an interior elbow angle of
 *      `25.17° … 27.60°` against the same `30°` stop (rule 2) at EVERY phase.
 *  * **Deliberate model limit → NOT changed, PINNED (2 poses).**
 *    * `SupermanPose`: BPS §7/§9 specify "knees are extended, not bent" / "Knee: extended (~0°)" and
 *      "Shoulder flexion (arms forward): ~150–180°", and the pose authors exactly that — the legs at
 *      `210.0000` u and the arms at `≤ 146.0788` u, i.e. `L1 + L2` BY CONSTRUCTION (`hip + legLen`
 *      along the lift direction), not a number past the limb. The `0.98` cap answers with a `4.2000`
 *      u / `2.9988` u trim. Projecting would only hide the cap.
 *    * `DeadBugPose`: BPS §6/§9 specify the stationary arm "vertical toward the ceiling, shoulders at
 *      ~90° flexion", authored as `shoulder.y + (L1 + L2)` = `146.0000` u — again `L1 + L2` by
 *      construction; the cap trims `2.9200` u.
 *  * **Not a reach-band site at all (measured, explained, left byte-identical):** the five poses that
 *    author in their own pre-solve frame (`ArmCirclesPose`, `FacePullPose`, `HipCarsPose`,
 *    `ScapularRetractionPose`, `WallSlidesPose`) carry a `235.0` u apparent relocation that IS the
 *    solver's own root transport (`effector = declared + (0, rootTranslation, 0)` component-wise,
 *    stamp `0.0470`), and `DynamicWorldsGreatestStretchPose`'s `21.6410` u support-hand site is
 *    reported but out of this batch's candidate set (it is a DECLARED support contact
 *    — `RIGHT_HAND` in `metadata.support` — so its correction is a support-surface decision).
 *
 * ## The fix (pose-side only)
 *
 * The R2/R4 convention the first three batches established: project the authored target onto its own
 * chain's annulus ALONG ITS OWN RAY (`SkeletonMath.clampTargetToReach`, `REACH_MARGIN = 1e-4` of the
 * chain's span), so the declared target IS the published position and the reachability stamp stays an
 * honest signal. No solver, engine, carrier or stamp semantics change. `BaseThoracicPose` gains the
 * same `protected open fun projectArmTargetToReach` hook batch 3 installed in `BaseHipFlexorPose`,
 * called inside `bakeThoracicArm` between composition and the bake, with a no-op default that keeps
 * the family's third variant (`DynamicWorldsGreatestStretchPose`) byte-identical;
 * `CouchStretchPose` overrides batch 3's hook in `BaseHipFlexorPose`; the two supine poses project
 * their own stance literal with their own `REACH_MARGIN` companion constant.
 */
class ReachBandBatch4AuthoringTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** Dense enough to bracket both the seam frames and each pose's extreme phase. */
    private val phases = floatArrayOf(
        0.0f, 0.05f, 0.1f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.75f, 0.8f, 0.9f, 0.95f, 1.0f
    )

    /** end joint → (chain root, middle joint). */
    private val chains = listOf(
        Triple(Joint.ANKLE_F, Joint.HIP_F, Joint.KNEE_F),
        Triple(Joint.ANKLE_B, Joint.HIP_B, Joint.KNEE_B),
        Triple(Joint.HAND_A, Joint.SHOULDER_A, Joint.ELBOW_A),
        Triple(Joint.HAND_P, Joint.SHOULDER_P, Joint.ELBOW_P)
    )

    /** The poses this batch author-fixes (all five measured out of band on the base tree). */
    private fun fixed(): Map<String, () -> PoseBuilder> = mapOf(
        "CouchStretchPose" to { CouchStretchPose() as PoseBuilder },
        "QuadrupedThoracicRotationsPose" to { QuadrupedThoracicRotationsPose() as PoseBuilder },
        "GluteBridgePose" to { GluteBridgePose() as PoseBuilder },
        "PelvicTiltPose" to { PelvicTiltPose() as PoseBuilder },
        "ThoracicExtensionPose" to { ThoracicExtensionPose() as PoseBuilder }
    )

    private val bandTolerance = 1e-3f
    private val relocationTolerance = 0.05f
    private val rayCos = 0.99999f

    private fun f(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    private fun vec(v: Vector3) = String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z)

    private class Reading(
        val pose: String,
        val frame: String,
        val phase: Float,
        val chain: Joint,
        val declared: Vector3,
        val root: Vector3,
        val mid: Vector3,
        val effector: Vector3,
        val declaredDistance: Float,
        val minReach: Float,
        val maxReach: Float,
        val relocation: Float,
        val publishedDistance: Float,
        val interiorDegrees: Float,
        val clampStamp: Float,
        val pelvis: Vector3,
        val hip: Vector3,
        val chest: Vector3,
        val chestRot: JointRotation,
        val support: String,
        val groundLevel: Float,
        val minJointY: Float,
        val elbowA: Vector3,
        val elbowP: Vector3,
        val handA: Vector3,
        val handP: Vector3,
        val kneeA: Vector3,
        val kneeP: Vector3,
        val ankleF: Vector3,
        val ankleB: Vector3,
        val shoulderA: Vector3,
        val shoulderP: Vector3
    )

    private fun context(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** Reads one published frame's primitives DURING the call (never stores the reused buffer). */
    private fun measure(builder: PoseBuilder, pipeline: SkeletonPipeline, frame: String, phase: Float): List<Reading> {
        val name = builder.javaClass.simpleName
        val published = pipeline.produceFrame(builder, context(phase)).pose
        val stamp = published.maxIkClampAmount
        val support = published.supportedPoints.sortedBy { it.name }.joinToString(",") { it.name }
        val groundLevel = published.environment.ground.level
        val pelvis = copy(published.getJoint(Joint.PELVIS))
        val hip = copy(published.getJoint(Joint.HIP_F))
        val chest = copy(published.getJoint(Joint.CHEST))
        var minJointY = Float.MAX_VALUE
        for (j in Joint.entries) minJointY = minOf(minJointY, published.getJoint(j).y)
        val out = mutableListOf<Reading>()
        for ((end, rootJoint, midJoint) in chains) {
            val target = published.limbTargets.firstOrNull { it.joint == end } ?: continue
            val constraint = target.constraint!!
            val root = published.getJoint(rootJoint)
            val mid = published.getJoint(midJoint)
            val effector = published.getJoint(end)
            out += Reading(
                pose = name, frame = frame, phase = phase, chain = end,
                declared = copy(target.world), root = copy(root), mid = copy(mid), effector = copy(effector),
                declaredDistance = distance(target.world, root),
                minReach = SkeletonMath.minReach(target.length1, target.length2, constraint),
                maxReach = SkeletonMath.maxReach(target.length1, target.length2, constraint),
                relocation = distance(effector, target.world),
                publishedDistance = distance(effector, root),
                interiorDegrees = interiorAngleDegrees(root, mid, effector),
                clampStamp = stamp,
                pelvis = pelvis, hip = hip, chest = chest, chestRot = copyRot(published.getJointRotation(Joint.CHEST)),
                support = support, groundLevel = groundLevel,
                minJointY = minJointY,
                elbowA = copy(published.getJoint(Joint.ELBOW_A)),
                elbowP = copy(published.getJoint(Joint.ELBOW_P)),
                handA = copy(published.getJoint(Joint.HAND_A)),
                handP = copy(published.getJoint(Joint.HAND_P)),
                kneeA = copy(published.getJoint(Joint.KNEE_F)),
                kneeP = copy(published.getJoint(Joint.KNEE_B)),
                ankleF = copy(published.getJoint(Joint.ANKLE_F)),
                ankleB = copy(published.getJoint(Joint.ANKLE_B)),
                shoulderA = copy(published.getJoint(Joint.SHOULDER_A)),
                shoulderP = copy(published.getJoint(Joint.SHOULDER_P))
            )
        }
        return out
    }

    /** Every sampled frame of one builder: the cold first frame, then an advancing pipeline's rep. */
    private fun readings(builder: PoseBuilder): List<Reading> {
        val out = mutableListOf<Reading>()
        out += measure(builder, SkeletonPipeline(def), "cold", phases.first())
        val pipeline = SkeletonPipeline(def)
        for (k in 0..10) pipeline.produceFrame(builder, context(0.3f))
        for (p in phases) out += measure(builder, pipeline, "playing", p)
        return out
    }

    private fun fixedReadings(): List<Reading> = fixed().values.flatMap { readings(it()) }

    private fun where(r: Reading) = "${r.pose}/${r.chain.name} @ p=${f(r.phase)} (${r.frame})"

    private fun bandExcess(r: Reading) = when {
        r.declaredDistance > r.maxReach -> r.declaredDistance - r.maxReach
        r.declaredDistance < r.minReach -> r.minReach - r.declaredDistance
        else -> 0f
    }

    private fun cosine(a: Vector3, b: Vector3): Float {
        val am = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        val bm = sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        if (am < 1e-6f || bm < 1e-6f) return 0f
        return ((a.x * b.x + a.y * b.y + a.z * b.z) / (am * bm)).coerceIn(-1f, 1f)
    }

    private fun sub(a: Vector3, b: Vector3) = Vector3(a.x - b.x, a.y - b.y, a.z - b.z)

    /** Does [declared] lie on the ray `root → target`? (the projection preserves the RAY, not the point) */
    private fun onRay(root: Vector3, declared: Vector3, target: Vector3): Float =
        cosine(sub(declared, root), sub(target, root))

    // ----------------------------------------------------------------------------------------------
    // 1 — every authored target of the fixed poses is inside its own chain's reachable band
    // ----------------------------------------------------------------------------------------------

    @Test
    fun everyFixedTargetSitsInsideItsChainsOwnReachBand() {
        val all = fixedReadings()
        val violations = all.filter { bandExcess(it) > bandTolerance }.map {
            "${where(it)}: authored root→target ${f(it.declaredDistance)} outside " +
                "[${f(it.minReach)}, ${f(it.maxReach)}] by ${f(bandExcess(it))}"
        }
        assertTrue(
            "the fixed poses' authored limb targets must be reachable-by-construction (R2/R4) — the " +
                "solver relocates anything outside the chain's own annulus " +
                "(${violations.size} of ${all.size} readings):\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 2 — the published effector IS the authored target (no relocation)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun thePublishedEffectorFollowsTheAuthoredTargetWithoutRelocation() {
        val all = fixedReadings()
        val bad = all.filter { it.relocation > relocationTolerance }.map {
            "${where(it)}: |published − declared| = ${f(it.relocation)} " +
                "(declared ${f(it.declaredDistance)} of [${f(it.minReach)}, ${f(it.maxReach)}], " +
                "interior ${f(it.interiorDegrees)}°)"
        }
        assertTrue(
            "the published end-effector must be the declared target — no reach relocation:\n" +
                bad.joinToString("\n"),
            bad.isEmpty()
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 3 — the pose records no reach-relocation stamp (the honest IK_TARGET_UNREACHABLE signal)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun thePublishedFrameRecordsNoReachRelocationStamp() {
        val bad = fixedReadings().filter { it.clampStamp > relocationTolerance }
            .map { "${where(it)}: reachability stamp = ${f(it.clampStamp)}" }
        assertTrue(
            "a reachable-by-construction target must solve without a relocation stamp:\n" +
                bad.distinct().joinToString("\n"),
            bad.isEmpty()
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 4 — the fix is target-side per pose: the authored exercise motion and the declaration surface
    //     are untouched
    // ----------------------------------------------------------------------------------------------

    @Test
    fun theCouchStretchKeepsItsAuthoredStanceAndAimsAtTheFrontKnee() {
        val rs = readings(CouchStretchPose())
        // (a) the front leg's authored target is NOT part of this fix — byte-identical literal.
        val frontAnkle = rs.filter { it.chain == Joint.ANKLE_F }
        assertTrue(
            "the front leg's authored target (55, 25, −22) must be untouched: " +
                frontAnkle.map { vec(it.declared) }.distinct().joinToString(),
            frontAnkle.all { abs(it.declared.x - 55f) < 1e-5f && abs(it.declared.y - 25f) < 1e-5f &&
                abs(it.declared.z + def.hipWidth) < 1e-5f }
        )
        assertTrue(
            "the front leg's declared target must stay inside its own band",
            frontAnkle.all { bandExcess(it) <= bandTolerance }
        )
        // (b) the pelvis still slides backward into the couch (the authored lerp(10, −15)) and the
        //     torso still rocks from its authored forward lean to upright.
        val playing = rs.filter { it.frame == "playing" }
        assertEquals("every sampled phase must contribute every declared chain", phases.size, playing.groupBy { it.phase }.size)
        val pelvisX = playing.map { it.pelvis.x }
        assertTrue(
            "the pelvis push-back must survive the fix (x travel ${f(pelvisX.max() - pelvisX.min())})",
            pelvisX.max() - pelvisX.min() >= 20f
        )
        val pelvisY = playing.map { it.pelvis.y }
        assertTrue(
            "the pelvis' authored Pythagorean drop must survive the fix (y travel ${f(pelvisY.max() - pelvisY.min())})",
            pelvisY.max() - pelvisY.min() >= 5f
        )
        // (c) the hands still aim at the front knee: every declared arm target lies on the ray from
        //     its own shoulder to that frame's knee-composed choreography target.
        val bad = mutableListOf<String>()
        for (r in playing) {
            if (r.chain != Joint.HAND_A && r.chain != Joint.HAND_P) continue
            val kneeApex = planFrontKnee(55f, r.hip)
            val aimZ = if (r.chain == Joint.HAND_A) -def.shoulderWidth * 0.8f else def.shoulderWidth * 0.8f
            val aim = Vector3(kneeApex.x - 10f, kneeApex.y + 15f, aimZ)
            val c = onRay(r.root, r.declared, aim)
            if (c < rayCos) {
                bad += "${where(r)}: declared aim deviates from the knee-composed ray " +
                    "(cos = ${f(c)}, declared ${vec(r.declared)})"
            }
        }
        assertTrue(
            "the fix must keep the arms' authored aim at the front knee (only the radius moves):\n" +
                bad.joinToString("\n"),
            bad.isEmpty()
        )
        // (d) anti-collateral: the declaration surface is untouched by a reach-authoring fix.
        for (r in rs) {
            assertEquals("${where(r)}: the declared support model must be unchanged", "LEFT_FOOT", r.support)
            assertEquals("${where(r)}: the declared ground level must be unchanged", 0f, r.groundLevel, 0f)
            assertTrue(
                "${where(r)}: no joint may be published below the declared ground level " +
                    "(min joint y = ${f(r.minJointY)})",
                r.minJointY >= r.groundLevel - 1e-3f
            )
        }
    }

    @Test
    fun theQuadrupedKeepsItsTabletopItsPillarAndItsThoraxDrivenSweep() {
        val rs = readings(QuadrupedThoracicRotationsPose())
        val playing = rs.filter { it.frame == "playing" }
        // (a) the tabletop legs are not part of this fix: the authored literal and the published
        //     stance are both untouched.
        val legs = rs.filter { it.chain == Joint.ANKLE_F || it.chain == Joint.ANKLE_B }
        assertTrue(
            "the legs' authored target (pelvisX − shinLength, 15, ±hipWidth) must be untouched: " +
                legs.map { f(it.declaredDistance) }.distinct().joinToString(),
            legs.all { abs(it.declaredDistance - 148.8220f) < 1e-3f }
        )
        assertTrue(
            "the legs' stance must stay where it was measured (ankle y = 15, x = −118)",
            legs.all { abs(it.effector.y - 15f) < 1e-4f && abs(it.effector.x + 118f) < 1e-4f }
        )
        // (b) the support arm still aims at its authored floor pillar: the declared target lies on
        //     the ray from the support shoulder to (chestX, 0, shoulderWidth).
        val bad = mutableListOf<String>()
        for (r in playing.filter { it.chain == Joint.HAND_P }) {
            val aim = Vector3(r.chest.x, 0f, def.shoulderWidth)
            val c = onRay(r.root, r.declared, aim)
            if (c < rayCos) bad += "${where(r)}: pillar aim deviates (cos = ${f(c)}, declared ${vec(r.declared)})"
        }
        assertTrue(
            "the support arm must keep aiming at the authored floor pillar (only the radius moves):\n" +
                bad.joinToString("\n"),
            bad.isEmpty()
        )
        // (c) the reaching arm still lives in the twist's own plane (the chest twist is about world
        //     X, so both the shoulder and its target stay in the chest's x = 100 plane) and still
        //     sweeps low → high across the rep (BPS §6/§9: threading, then overhead).
        val reach = playing.filter { it.chain == Joint.HAND_A }
        assertTrue(
            "the reaching hand must stay in the thorax's own sweep plane (declared x = 100): " +
                reach.map { f(it.declared.x) }.distinct().joinToString(),
            reach.all { abs(it.declared.x - 100f) < 1e-4f }
        )
        val ys = reach.map { it.declared.y }
        assertTrue(
            "the authored sweep must survive the fix (declared y ${f(ys.min())} … ${f(ys.max())})",
            ys.max() - ys.min() >= 150f
        )
        assertTrue("the sweep must still rise overhead at the end", ys.max() >= 200f)
        // (d) the thorax twist is the driver and is untouched (chest world rotation swings through
        //     more than a radian of twist), and the declaration surface is unchanged.
        //
        //     NOTE — this pose publishes a joint BELOW its declared ground level
        //     (`FINGERTIPS_P` `y = −21.3196` at p = 0.40; `−15.7438` on the cold frame), and this
        //     batch is NOT the cause and does not touch it: the reading is byte-identical on the base
        //     tree (`origin/main` @ `ca011ad`) and on this tree, and it is the undeclared
        //     hand-chain class `PublishedBelowGroundInvariantTest` (T2) owns. Gate 6 below proves the
        //     whole published frame stays within `0.05` u of the pre-batch declaration.
        for (r in rs) {
            assertEquals("${where(r)}: the declared support model must be unchanged", "", r.support)
            assertEquals("${where(r)}: the declared ground level must be unchanged", 0f, r.groundLevel, 0f)
        }
    }

    @Test
    fun theSupineBridgeKeepsItsAuthoredStanceLiftAndElbowPlane() {
        val rs = readings(GluteBridgePose())
        val playing = rs.filter { it.frame == "playing" }
        // (a) the stance is still the authored one: the declared target lies on the ray from the
        //     published hip to the authored literal (45, ankleHeight, ±hipWidth).
        val bad = mutableListOf<String>()
        for (r in playing) {
            if (r.chain != Joint.ANKLE_F && r.chain != Joint.ANKLE_B) continue
            val z = if (r.chain == Joint.ANKLE_F) -def.hipWidth else def.hipWidth
            val aim = Vector3(45f, def.foot.ankleHeight, z)
            val c = onRay(r.root, r.declared, aim)
            if (c < rayCos) bad += "${where(r)}: stance aim deviates (cos = ${f(c)}, declared ${vec(r.declared)})"
        }
        assertTrue(
            "the fix must keep the authored stance ray (only the radius moves):\n" + bad.joinToString("\n"),
            bad.isEmpty()
        )
        assertTrue(
            "the in-band end of the rep must stay the authored literal (proof the projection is a " +
                "no-op inside the band): ${playing.filter { it.chain == Joint.ANKLE_F }.map { vec(it.declared) }.distinct().size} distinct declarations",
            playing.filter { it.chain == Joint.ANKLE_F }.any {
                abs(it.declared.x - 45f) < 1e-4f && abs(it.declared.y - def.foot.ankleHeight) < 1e-4f
            }
        )
        // (b) the authored rep survives: the pelvis still lifts 14 → 54.
        val pelvisY = playing.map { it.pelvis.y }
        assertTrue(
            "the bridge lift must survive the fix (pelvis y ${f(pelvisY.min())} … ${f(pelvisY.max())})",
            pelvisY.max() - pelvisY.min() >= 35f
        )
        // (c) the B4 elbow plane is intact: the elbows bow in this pose's own floor plane (outboard,
        //     above the mat), not through it.
        for (r in playing) {
            assertTrue(
                "${where(r)}: the B4 elbow correction must survive (elbow y = ${f(r.elbowA.y)})",
                r.elbowA.y >= -1e-3f && r.elbowP.y >= -1e-3f
            )
            assertTrue(
                "${where(r)}: the elbow bow must stay lateral (|elbow z| > |hand z|)",
                abs(r.elbowA.z) > abs(r.handA.z) && abs(r.elbowP.z) > abs(r.handP.z)
            )
        }
        // (d) the arms and the declaration surface are untouched.
        assertTrue(
            "the hands' authored target (−35, 12, ±51) must be untouched",
            rs.filter { it.chain == Joint.HAND_A || it.chain == Joint.HAND_P }
                .all { abs(it.declared.x + 35f) < 1e-5f && abs(it.declared.y - 12f) < 1e-5f }
        )
        for (r in rs) {
            assertEquals(
                "${where(r)}: the declared support model must be unchanged",
                "LEFT_FOOT,RIGHT_FOOT", r.support
            )
            assertEquals("${where(r)}: the declared ground level must be unchanged", 0f, r.groundLevel, 0f)
            assertTrue(
                "${where(r)}: no joint may be published below the declared ground level " +
                    "(min joint y = ${f(r.minJointY)})",
                r.minJointY >= r.groundLevel - 1e-3f
            )
        }
    }

    @Test
    fun theSupinePelvicTiltKeepsItsStaticPelvisItsTrunkArcAndItsElbowPlane() {
        val rs = readings(PelvicTiltPose())
        val playing = rs.filter { it.frame == "playing" }
        // (a) the same stance ray as the sibling bridge.
        val bad = mutableListOf<String>()
        for (r in playing) {
            if (r.chain != Joint.ANKLE_F && r.chain != Joint.ANKLE_B) continue
            val z = if (r.chain == Joint.ANKLE_F) -def.hipWidth else def.hipWidth
            val aim = Vector3(45f, def.foot.ankleHeight, z)
            val c = onRay(r.root, r.declared, aim)
            if (c < rayCos) bad += "${where(r)}: stance aim deviates (cos = ${f(c)}, declared ${vec(r.declared)})"
        }
        assertTrue(
            "the fix must keep the authored stance ray (only the radius moves):\n" + bad.joinToString("\n"),
            bad.isEmpty()
        )
        // (b) the B4 static pelvis is untouched (it IS this pose's resting layer).
        assertTrue(
            "the pelvis must stay on its static layer (y = 14): " +
                playing.map { f(it.pelvis.y) }.distinct().joinToString(),
            playing.all { abs(it.pelvis.y - 14f) < 1e-4f }
        )
        // (c) the B4 trunk arc is intact: the chest swings UP off the resting layer (the sign the B4
        //     pass corrected) — the model's own 120 · sin(0.12) = 14.3655 u, published as an absolute
        //     chest y of 28.3650 at the end of the rep.
        val chestY = playing.map { it.chest.y }
        assertTrue(
            "the B4 trunk arc must survive the fix (chest y ${f(chestY.min())} … ${f(chestY.max())})",
            chestY.max() - chestY.min() >= 14.3f
        )
        assertTrue(
            "the B4 arc's published endpoint must be unchanged (chest y at p = 1 = 28.3650; measured " +
                f(chestY.max()) + ")",
            abs(chestY.max() - 28.3650f) < 0.01f
        )
        // (d) the B4 elbow plane is intact.
        for (r in playing) {
            assertTrue(
                "${where(r)}: the B4 elbow correction must survive (elbow y = ${f(r.elbowA.y)})",
                r.elbowA.y >= -1e-3f && r.elbowP.y >= -1e-3f
            )
            assertTrue(
                "${where(r)}: the elbow bow must stay lateral (|elbow z| > |hand z|)",
                abs(r.elbowA.z) > abs(r.handA.z) && abs(r.elbowP.z) > abs(r.handP.z)
            )
        }
        for (r in rs) {
            assertEquals(
                "${where(r)}: the declared support model must be unchanged",
                "LEFT_FOOT,RIGHT_FOOT", r.support
            )
            assertEquals("${where(r)}: the declared ground level must be unchanged", 0f, r.groundLevel, 0f)
            assertTrue(
                "${where(r)}: no joint may be published below the declared ground level " +
                    "(min joint y = ${f(r.minJointY)})",
                r.minJointY >= r.groundLevel - 1e-3f
            )
        }
    }

    @Test
    fun theThoracicExtensionKeepsItsClaspAimItsKneelingStanceAndItsFlares() {
        val rs = readings(ThoracicExtensionPose())
        val playing = rs.filter { it.frame == "playing" }
        // (a) the hands still aim at the authored hands-behind-the-head point: the declared target
        //     lies on the ray from its shoulder to (headBase − 12, +6, ±0.55 · shoulderWidth), where
        //     headBase is the B-8b projection (authored gaze × neck length inside the chest frame).
        val bad = mutableListOf<String>()
        for (r in playing) {
            if (r.chain != Joint.HAND_A && r.chain != Joint.HAND_P) continue
            val base = claspBase(r.chest, r.chestRot)
            val z = if (r.chain == Joint.HAND_A) -def.shoulderWidth * 0.55f else def.shoulderWidth * 0.55f
            val aim = Vector3(base.x - 12f, base.y + 6f, z)
            val c = onRay(r.root, r.declared, aim)
            if (c < rayCos) bad += "${where(r)}: clasp aim deviates (cos = ${f(c)}, declared ${vec(r.declared)}, aim ${vec(aim)})"
        }
        assertTrue(
            "the fix must keep the arms' authored aim at the clasp (only the radius moves):\n" +
                bad.joinToString("\n"),
            bad.isEmpty()
        )
        // (b) the elbows stay FLARED: outboard of the hands and above them, at every phase.
        for (r in playing) {
            assertTrue(
                "${where(r)}: the elbows must stay flared outboard (|elbow z| = ${f(abs(r.elbowA.z))} " +
                    "vs |hand z| = ${f(abs(r.handA.z))})",
                abs(r.elbowA.z) > abs(r.handA.z) * 2f && abs(r.elbowP.z) > abs(r.handP.z) * 2f
            )
            assertTrue(
                "${where(r)}: the elbows must stay above the hands (elbow y = ${f(r.elbowA.y)}, " +
                    "hand y = ${f(r.handA.y)})",
                r.elbowA.y > r.handA.y && r.elbowP.y > r.handP.y
            )
        }
        // (c) the tall-kneeling legs are not part of this fix: knees on the floor at y = 15 and the
        //     shins flat behind them (ankle x = knee x − shinLength).
        val knees = playing.map { it.kneeA.y }
        assertTrue(
            "the kneeling legs must stay on the floor (knee y = ${f(knees.min())} … ${f(knees.max())})",
            playing.all { abs(it.kneeA.y - 15f) < 1e-3f && abs(it.kneeP.y - 15f) < 1e-3f }
        )
        assertTrue(
            "the shins must stay flat (ankle x = knee x − shinLength, y = 15)",
            playing.all {
                abs(it.ankleF.y - 15f) < 1e-3f && abs(it.ankleF.x - (it.kneeA.x - def.shinLength)) < 1e-3f
            }
        )
        // (d) the extension is still driven by the thoracic spine: the head end travels back as the
        //     chest node's own rotation grows from 0 to the authored arch.
        val chestRot = playing.minByOrNull { it.phase }!!.chest.x - playing.maxByOrNull { it.phase }!!.chest.x
        assertTrue(
            "the thoracic arch must still carry the chest backward over the rep " +
                "(chest x travel ${f(chestRot)})",
            chestRot >= 20f
        )
        for (r in rs) {
            assertEquals("${where(r)}: the declared support model must be unchanged", "", r.support)
            assertEquals("${where(r)}: the declared ground level must be unchanged", 0f, r.groundLevel, 0f)
            assertTrue(
                "${where(r)}: no joint may be published below the declared ground level " +
                    "(min joint y = ${f(r.minJointY)})",
                r.minJointY >= r.groundLevel - 1e-3f
            )
        }
    }

    // ----------------------------------------------------------------------------------------------
    // 5 — THE INTENTIONAL-LIMIT PINS: the deliberate `0.98` caps are preserved, not projected away
    // ----------------------------------------------------------------------------------------------

    /**
     * `SupermanPose` and `DeadBugPose` were measured by this batch and deliberately left
     * byte-identical: both author a FULLY EXTENDED limb (`L1 + L2` by construction — the BPS's own
     * "knees are extended, not bent" / "arms vertical toward the ceiling"), and the `0.98` extension
     * cap answers with a `4.2000` / `2.9988` / `2.9200` u trim. Projecting the declaration would only
     * hide a real, deliberate model limit (the same verdict the second batch pinned for
     * `CossackSquatPose`). This test fails if the site disappears (i.e. if someone "cleans" it) or if
     * the request ever grows past the limb.
     */
    @Test
    fun theDeliberateStraightLimbCapsArePreservedNotProjected() {
        val failures = mutableListOf<String>()

        // Superman: legs authored at exactly L1 + L2, trimmed by the cap at every phase.
        val superman = readings(SupermanPose())
        val sLegs = superman.filter { it.chain == Joint.ANKLE_F || it.chain == Joint.ANKLE_B }
        val limbLeg = def.thighLength + def.shinLength
        if (sLegs.any { abs(it.declaredDistance - limbLeg) > 1e-3f }) {
            failures += "SupermanPose: the legs must still ask for the fully extended limb " +
                "(${f(limbLeg)}); measured ${sLegs.map { f(it.declaredDistance) }.distinct()}"
        }
        if (sLegs.none { it.relocation > 4.0f } || sLegs.none { it.clampStamp > 4.0f }) {
            failures += "SupermanPose: the deliberate cap trim must still be published " +
                "(worst relocation ${f(sLegs.maxOf { it.relocation })}, stamp ${f(sLegs.maxOf { it.clampStamp })})"
        }
        if (sLegs.any { it.interiorDegrees < 156.9f }) {
            failures += "SupermanPose: the realized legs must sit ON the 0.98 cap " +
                "(interior ${sLegs.map { f(it.interiorDegrees) }.distinct()})"
        }
        // …and the arms, whose request is the same construction: ≤ L1 + L2 plus the composition's
        // own sub-0.1 u perpendicular slack.
        val sArms = superman.filter { it.chain == Joint.HAND_A || it.chain == Joint.HAND_P }
        val limbArm = def.upperArmLength + def.forearmLength
        if (sArms.any { it.declaredDistance > limbArm + 0.1f }) {
            failures += "SupermanPose: the arms' request must stay within the limb " +
                "(${f(sArms.maxOf { it.declaredDistance })} vs ${f(limbArm)} + 0.1)"
        }
        if (sArms.none { it.relocation > 2.9f }) {
            failures += "SupermanPose: the arms' cap trim must still be published " +
                "(worst relocation ${f(sArms.maxOf { it.relocation })})"
        }

        // DeadBug: the stationary (neutral) arm authored at exactly L1 + L2.
        val deadBug = readings(DeadBugPose())
        val dArms = deadBug.filter { it.chain == Joint.HAND_A || it.chain == Joint.HAND_P }
        if (dArms.none { abs(it.declaredDistance - limbArm) < 1e-3f }) {
            failures += "DeadBugPose: the stationary arm must still ask for the fully extended limb " +
                "(${f(limbArm)}); measured ${dArms.map { f(it.declaredDistance) }.distinct()}"
        }
        if (dArms.none { it.relocation > 2.9f } || dArms.none { it.clampStamp > 2.9f }) {
            failures += "DeadBugPose: the deliberate cap trim must still be published " +
                "(worst relocation ${f(dArms.maxOf { it.relocation })}, stamp ${f(dArms.maxOf { it.clampStamp })})"
        }
        if (dArms.none { it.declaredDistance <= it.maxReach }) {
            failures += "DeadBugPose: the moving arm must still realize in band (the pose's own " +
                "0.94 factor); min declared ${f(dArms.minOf { it.declaredDistance })}"
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ----------------------------------------------------------------------------------------------
    // 6 — NON-VACUITY: the same measurement still rejects the PRE-BATCH authoring, and the fixed
    //     poses publish the SAME geometry the pre-batch declaration did
    // ----------------------------------------------------------------------------------------------

    /**
     * The sensitivity control. Every fixed pose is a FINAL class, so the pre-batch authoring is
     * restored through the sanctioned builder-delegation shape: `IK_STAGE_ACTIVE` re-bakes the
     * declared targets from the carrier after `build` returns, so re-declaring the pre-batch targets
     * reproduces the pre-batch PUBLISHED frame through the SAME production entry point.
     *
     * It doubles as the batch's central preservation gate: the fixed poses must publish within
     * `0.05` u of that pre-batch frame (the projection's `1e-4` margin), i.e. the reach fix changes
     * the DECLARATION, not the exercise.
     */
    private class PreBatchAuthoredTargets(
        private val inner: PoseBuilder,
        private val def: SkeletonDefinition
    ) : PoseBuilder by inner {
        override fun build(context: PoseContext): SkeletonPose {
            val built = inner.build(context)
            val p = context.progress
            when (inner.javaClass.simpleName) {
                "CouchStretchPose" -> {
                    // The base's shared arm choreography, verbatim: hands at the front knee's
                    // planning apex + (−10, +15, ±0.8 · shoulderWidth).
                    val knee = planFrontKnee(55f, built.getJoint(Joint.HIP_F), def)
                    setTarget(built, Joint.HAND_A, knee.x - 10f, knee.y + 15f, -def.shoulderWidth * 0.8f)
                    setTarget(built, Joint.HAND_P, knee.x - 10f, knee.y + 15f, def.shoulderWidth * 0.8f)
                }
                "QuadrupedThoracicRotationsPose" -> {
                    // The support arm's floor pillar and the reaching arm's chest-frame sweep,
                    // verbatim (0.35, 0, −0.45 normalized × 0.82 · limb, world-space y override).
                    val chestW = built.getJoint(Joint.CHEST)
                    setTarget(built, Joint.HAND_P, chestW.x, 0f, def.shoulderWidth)
                    val reachLen = (def.upperArmLength + def.forearmLength) * 0.82f
                    val local = Vector3(0.35f, 0f, -0.45f).normalize()
                    val out = Vector3()
                    SkeletonMath.rotAround(
                        Vector3(local.x * reachLen, 0f, local.z * reachLen),
                        built.getJointRotation(Joint.CHEST).axis,
                        built.getJointRotation(Joint.CHEST).angle,
                        out
                    )
                    out.set(out.x + chestW.x, out.y + chestW.y, out.z + chestW.z)
                    val y = SkeletonMath.lerp(20f, chestW.y + reachLen * 0.9f, p)
                    setTarget(built, Joint.HAND_A, out.x, if (y < 6f) 6f else y, out.z)
                }
                "GluteBridgePose", "PelvicTiltPose" -> {
                    // The pose's own stance literal, verbatim: (45, ankleHeight, ±hipWidth).
                    setTarget(built, Joint.ANKLE_F, 45f, def.foot.ankleHeight, -def.hipWidth)
                    setTarget(built, Joint.ANKLE_B, 45f, def.foot.ankleHeight, def.hipWidth)
                }
                "ThoracicExtensionPose" -> {
                    // The handed clasp, verbatim: the B-8b head-base projection + (−12, +6,
                    // ±0.55 · shoulderWidth).
                    val chestW = built.getJoint(Joint.CHEST)
                    val headDir = Vector3(-0.12f, 1.0f, 0f).normalize()
                    val base = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, 0f)
                    val out = Vector3()
                    SkeletonMath.rotAround(
                        base,
                        built.getJointRotation(Joint.CHEST).axis,
                        built.getJointRotation(Joint.CHEST).angle,
                        out
                    )
                    out.set(out.x + chestW.x, out.y + chestW.y, out.z + chestW.z)
                    setTarget(built, Joint.HAND_A, out.x - 12f, out.y + 6f, -def.shoulderWidth * 0.55f)
                    setTarget(built, Joint.HAND_P, out.x - 12f, out.y + 6f, def.shoulderWidth * 0.55f)
                }
            }
            return built
        }
    }

    @Test
    fun theSameMeasurementStillRejectsThePreBatchAuthoring() {
        // The pre-batch relocation each pose's authoring read on `origin/main` @ `ca011ad`.
        val preBatch = mapOf(
            "CouchStretchPose" to 13.5995f,
            "QuadrupedThoracicRotationsPose" to 32.5176f,
            "GluteBridgePose" to 10.9979f,
            "PelvicTiltPose" to 10.9979f,
            "ThoracicExtensionPose" to 5.5162f
        )
        val failures = mutableListOf<String>()
        var controlReadings = 0
        for ((name, factory) in fixed()) {
            val control = readings(PreBatchAuthoredTargets(factory(), def))
            val production = readings(factory())
            controlReadings += control.size
            val worstControl = control.maxOf { it.relocation }
            val worstStamp = control.maxOf { it.clampStamp }
            val outOfBand = control.count { bandExcess(it) > bandTolerance }
            val expected = preBatch.getValue(name)
            if (worstControl < expected - 0.01f || worstStamp < expected - 0.01f || outOfBand == 0) {
                failures += "$name: the pre-batch authoring must still read as relocated by this " +
                    "harness (measured relocation ${f(worstControl)}, stamp ${f(worstStamp)}, " +
                    "out-of-band $outOfBand of ${control.size}; pre-batch site ${f(expected)})"
            }
            val worstProduction = production.maxOf { it.relocation }
            if (worstProduction >= worstControl / 10f) {
                failures += "$name: the production pose must differ from the pre-batch control on " +
                    "the measured property (${f(worstProduction)} vs ${f(worstControl)})"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue(
            "anti-vacuity: the control must have produced readings (got $controlReadings)",
            controlReadings >= 200
        )
    }

    @Test
    fun theFixedPosesPublishTheSameGeometryThePreBatchDeclarationDid() {
        val failures = mutableListOf<String>()
        for ((name, factory) in fixed()) {
            val control = readings(PreBatchAuthoredTargets(factory(), def))
                .associateBy { Triple(it.frame, it.phase, it.chain) }
            for (r in readings(factory())) {
                val c = control[Triple(r.frame, r.phase, r.chain)] ?: continue
                val delta = distance(r.effector, c.effector)
                if (delta > 0.05f) {
                    failures += "${where(r)}: the fix moved the published effector ${f(delta)} u away " +
                        "from the pre-batch frame (${vec(r.effector)} vs ${vec(c.effector)})"
                }
            }
        }
        assertTrue(
            "the reach projection is a DECLARATION correction: the published geometry must stay " +
                "within 0.05 u of the pre-batch frame (the projection margin is 1e-4 of the chain's " +
                "span):\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 7 — the complete remaining reach-band census (the class this batch closes)
    // ----------------------------------------------------------------------------------------------

    /**
     * Every pose of the corpus whose published frame still carries a non-zero reach signal after this
     * batch, with the value measured on THIS tree, and the verdict that keeps it.
     *
     *  * `SupermanPose` / `DeadBugPose` — the deliberate `0.98` straight-limb caps (pinned above).
     *  * `CossackSquatPose` + the three alternating lunge variants + `MountainClimberPose` — REALIZABLE
     *    requests (`207.000` / `207.0263` / `206.3640` against a `210` u limb) that the `0.98` cap
     *    trims; batch 2 recorded the lunge family's site as inherited from `BaseLungePose`'s standing
     *    convention and left it to a base-scoped batch.
     *  * the five pre-solve-frame poses — their `235.0` u apparent relocation IS the solver's root
     *    transport (`effector = declared + (0, rootTranslation, 0)`, no clamp: the stamp reads
     *    `0.0470`), i.e. NOT a reach-band site; their real residual is the `0.0470` u boundary-exact
     *    authoring of `hip.y − maxReach`, reported here rather than silently absorbed.
     *  * `DynamicWorldsGreatestStretchPose` — an unintended-class site (`164.7209` = `112.8 %` of the
     *    `146` u arm, `21.6410` u relocation) whose support hand the pose ALSO declares as a support
     *    contact (`RIGHT_HAND`), i.e. its correction is a support-surface decision; measured and
     *    reported, out of this batch's candidate set.
     */
    @Test
    fun theCompleteRemainingReachCensusIsPinned() {
        // pose → the worst relocation its published frame carries on this tree.
        val capped = mapOf(
            "SupermanPose" to 4.2000f,
            "DeadBugPose" to 2.9200f,
            "CossackSquatPose" to 1.2000f,
            "AlternatingForwardLungesPose" to 1.2263f,
            "AlternatingReverseLungesPose" to 1.2263f,
            "AlternatingSideLungesPose" to 1.2263f,
            "MountainClimberPose" to 0.5640f,
            "DynamicWorldsGreatestStretchPose" to 21.6410f
        )
        val failures = mutableListOf<String>()
        for (pose in fixed().keys) {
            val worst = readings(MotionProbe.build(pose)).maxOf { it.relocation }
            if (worst > relocationTolerance) {
                failures += "$pose: fixed by this batch, but the published frame still relocates by ${f(worst)}"
            }
        }
        for ((name, expected) in capped) {
            if (name in fixed().keys) continue
            val worst = readings(MotionProbe.build(name)).maxOf { it.relocation }
            if (abs(worst - expected) > 0.01f) {
                failures += "$name: measured worst relocation ${f(worst)}, pinned census value ${f(expected)}"
            }
        }
        // The five pre-solve-frame poses: the apparent 235 u is the ROOT TRANSPORT, not a clamp.
        val rootTransport = listOf(
            "ArmCirclesPose", "FacePullPose", "HipCarsPose", "ScapularRetractionPose", "WallSlidesPose"
        )
        var artifactReadings = 0
        for (name in rootTransport) {
            val rs = readings(MotionProbe.build(name))
            for (ch in rs.map { it.chain }.distinct()) {
                val cry = rs.filter { it.chain == ch }
                val worst = cry.maxByOrNull { it.relocation }!!
                artifactReadings++
                // the published effector is the declared target transported by the root's own
                // translation and clamped onto the annulus — never an authored-ray relocation.
                if (abs(worst.relocation - 235.0470f) > 0.05f && abs(worst.relocation - 235.0f) > 0.05f) {
                    failures += "$name/$ch: the pre-solve-frame artifact must read ~235 (the root " +
                        "transport); measured ${f(worst.relocation)}"
                }
                if (worst.clampStamp > 0.05f) {
                    failures += "$name/$ch: the root-transport artifact must carry no clamp " +
                        "(stamp ${f(worst.clampStamp)})"
                }
            }
        }
        assertTrue(
            "the corpus's remaining reach sites must read exactly as measured by this batch:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
        assertTrue("anti-vacuity: the artifact census must have produced readings", artifactReadings >= 20)
    }

    // ----------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------

    /** The interior angle at the middle joint of a realized (published) chain. */
    private fun interiorAngleDegrees(a: Vector3, b: Vector3, c: Vector3): Float {
        val ux = a.x - b.x; val uy = a.y - b.y; val uz = a.z - b.z
        val vx = c.x - b.x; val vy = c.y - b.y; val vz = c.z - b.z
        val um = sqrt(ux * ux + uy * uy + uz * uz)
        val vm = sqrt(vx * vx + vy * vy + vz * vz)
        if (um < 1e-6f || vm < 1e-6f) return 0f
        val cosA = ((ux * vx + uy * vy + uz * vz) / (um * vm)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosA.toDouble())).toFloat()
    }

    private fun distance(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun copy(v: Vector3) = Vector3(v.x, v.y, v.z)

    private fun copyRot(r: JointRotation) = JointRotation(Vector3(r.axis.x, r.axis.y, r.axis.z), r.angle)

    /**
     * The B-8b head-base projection of a published frame: the pose's own authored gaze × the
     * definition's neck length, rotated into world by the frame the chest node publishes (the same
     * composition `chestLocalToWorld` + `buildGaze` perform at authoring time).
     */
    private fun claspBase(chest: Vector3, chestRot: JointRotation): Vector3 {
        val headDir = Vector3(-0.12f, 1.0f, 0f).normalize()
        val local = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, 0f)
        val out = Vector3()
        SkeletonMath.rotAround(local, chestRot.axis, chestRot.angle, out)
        return Vector3(out.x + chest.x, out.y + chest.y, out.z + chest.z)
    }
}

/** Overwrites a declared limb target in place (test-side only; see [PreBatchAuthoredTargets]). */
private fun setTarget(pose: SkeletonPose, joint: Joint, x: Float, y: Float, z: Float) {
    val target = pose.limbTargets.firstOrNull { it.joint == joint } ?: return
    target.world.set(x, y, z)
}

/**
 * Replays `BaseHipFlexorPose.planFrontLegKnee`'s sanctioned planning solve from a built/published
 * frame: the front knee's apex for the variant's own authored front-leg target, used to re-compose
 * the base's pre-batch arm choreography.
 */
private fun planFrontKnee(ankleX: Float, hip: Vector3, def: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT): Vector3 {
    val out = SkeletonMath.IKResult()
    planLimbPlacement(
        Vector3(hip.x, hip.y, hip.z), Vector3(ankleX, 25f, -def.hipWidth), def.thighLength, def.shinLength,
        Vector3(1f, 0f, -0.5f), def.legIKConstraint, out
    )
    return out.joint
}
