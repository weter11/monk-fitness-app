package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.*

/**
 * Third reach-band cleanup batch — `HalfKneelingStretchPose`, `HamstringStretchPose` and
 * `ProneCobraStretchPose` (production geometry), plus the `CouchStretchPose` family-scope control
 * (test-only; its authoring is unchanged) and a corpus-wide blast-radius census.
 *
 * ## What this batch measured (base `origin/main` @ `cf8a14f`, the #254 merge)
 *
 * Every authored limb target of every pose was measured through the production entry point
 * `SkeletonPipeline.produceFrame(pose, ctx)` — `15` phases
 * (`0.00, 0.05, 0.10, 0.20, 0.25, 0.30, 0.40, 0.50, 0.60, 0.70, 0.75, 0.80, 0.90, 0.95, 1.00`) × the
 * pose's declared limb chains × both frame conditions (a fresh pipeline's cold first frame, then an
 * advancing pipeline) — against each chain's own `[SkeletonMath.minReach, SkeletonMath.maxReach]`.
 *
 * | pose | chain | authored root→target | band | relocation |
 * |---|---|---|---|---|
 * | `HalfKneelingStretchPose` | both hands | `155.160` (p=0) … `166.868` (p=1) | `≤ 143.080` | **`12.080 … 23.788`** |
 * | `HalfKneelingStretchPose` | front ankle | `100.835 … 119.436` | in band | `0.000` |
 * | `HamstringStretchPose` | tucked (back) ankle | `36.688` (every frame) | `≥ 56.009` | **`19.321`** |
 * | `HamstringStretchPose` | extended (front) ankle | `205.000` | in band | `0.000` |
 * | `HamstringStretchPose` | both hands | `40.937 … 115.179` (the M13 projection) | in band | `0.000` |
 * | `ProneCobraStretchPose` | both hands | `23.000` (p=0) … `154.027` (p=1) | `[40.134, 143.080]` | **`17.134` (p=0) … `10.947` (p=1)** |
 * | `ProneCobraStretchPose` | both ankles | `195.000` | in band | `0.000` |
 * | `CouchStretchPose` (sibling, NOT fixed) | both hands | `142.049 … 156.679` | `≤ 143.080` | **`13.600`** |
 *
 * ## Classification — measured, per SITE
 *
 * The discriminator (unchanged since the first two batches) is whether an IDEAL (`L1 + L2`) chain
 * could honour the request at all, and whether the request contradicts the pose's own stated intent:
 *
 *  * **Unintended authoring error → fixed.**
 *    * `HalfKneelingStretchPose`'s hands ask for `155.160 … 166.868` from a `80 + 66 = 146` u limb —
 *      LONGER than the limb itself, i.e. impossible for ANY chain geometry, while the pose's own
 *      intent (`BaseHipFlexorPose.solveArmsOnKnee`: "Both arms rest on the front knee") is a
 *      reachable one. The solver published them exactly on the `0.98` cap with the elbow reading
 *      `156.94°` interior (a straight arm, not a hand resting on a knee).
 *    * `HamstringStretchPose`'s tucked leg asks for a `36.688` chord — an interior knee angle of
 *      `18.89°` against the `IKConstraint`'s own `30°` stop (`161°` of knee flexion, past the BPS's
 *      "knee flexed, foot tucked in" model). Same class as the second batch's `DeepSquatHoldPose`
 *      site (`24.80°` against the same stop, fixed there).
 *    * `ProneCobraStretchPose`'s hand sweep leaves the band at BOTH ends: its start asks for an
 *      interior elbow angle of `14.39°` (past the `30°` stop) and its end asks `154.027` from the
 *      same `146` u limb (`105.5 %` of it).
 *  * **Deliberate model limit / sibling site → left unchanged, pinned.** `CouchStretchPose` (the other
 *    `BaseHipFlexorPose` variant) composes its hands through the SAME shared helper and carries the
 *    same class of site (`142.049 … 156.679`, `13.600` u relocation). It is NOT in this batch, so the
 *    family's shared choreography is not re-scoped: the projection is opted into in
 *    `HalfKneelingStretchPose` alone, and this file pins the sibling's site so a base-scoped change
 *    has to come as its own decision. The corpus's other measured out-of-band sites
 *    (`GluteBridgePose` / `PelvicTiltPose` legs `10.998`, `SupermanPose` `4.200` / `2.999`,
 *    `ThoracicExtensionPose` `5.516`, the lunge-family `1.226`, `CossackSquatPose` `1.200`) are
 *    likewise unchanged and pinned.
 *
 * ## The fix (pose-side only)
 *
 * The assembly `BaseSquatPose`/`BaseLungePose` precedent — project the authored target onto its own
 * chain's annulus along its own ray (`SkeletonMath.clampTargetToReach`, `REACH_MARGIN = 1e-4` of the
 * chain's span). No solver, engine, carrier or stamp semantics change; the projection's own margin
 * keeps the published geometry (the solver's relocation WAS that boundary projection) and takes the
 * reachability stamp to exactly `0`. The poses keep their authored roots, depths, stance and
 * choreography — only the declaration moves.
 */
class ReachBandBatch3AuthoringTest {

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

    /** The poses this batch author-fixes (all three measured out of band on the base tree). */
    private fun fixed(): Map<String, () -> PoseBuilder> = mapOf(
        "HalfKneelingStretchPose" to { HalfKneelingStretchPose() as PoseBuilder },
        "HamstringStretchPose" to { HamstringStretchPose() as PoseBuilder },
        "ProneCobraStretchPose" to { ProneCobraStretchPose() as PoseBuilder }
    )

    private val bandTolerance = 1e-3f
    private val relocationTolerance = 0.05f

    private fun f(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    private fun vec(v: Vector3) = String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z)

    private class Reading(
        val pose: String,
        val frame: String,
        val phase: Float,
        val chain: Joint,
        val declared: Vector3,
        val root: Vector3,
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
        val support: String,
        val groundLevel: Float,
        val minJointY: Float
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
        val groundLevel = builder.metadata.environment.ground.level
        val pelvis = copy(published.getJoint(Joint.PELVIS))
        val hip = copy(published.getJoint(Joint.HIP_F))
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
                declared = copy(target.world), root = copy(root), effector = copy(effector),
                declaredDistance = distance(target.world, root),
                minReach = SkeletonMath.minReach(target.length1, target.length2, constraint),
                maxReach = SkeletonMath.maxReach(target.length1, target.length2, constraint),
                relocation = distance(effector, target.world),
                publishedDistance = distance(effector, root),
                interiorDegrees = interiorAngleDegrees(root, mid, effector),
                clampStamp = stamp,
                pelvis = pelvis, hip = hip, support = support, groundLevel = groundLevel,
                minJointY = minJointY
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
    fun theHalfKneelingStretchKeepsItsAuthoredStanceAndAimsAtTheSameKnee() {
        val rs = readings(HalfKneelingStretchPose())
        // (a) the front leg's authored target is NOT part of this fix — byte-identical literal.
        val frontAnkle = rs.filter { it.chain == Joint.ANKLE_F }.map { it.declared }
        assertTrue(
            "the front leg's authored target (65, 25, −22) must be untouched: " +
                frontAnkle.distinct().joinToString { vec(it) },
            frontAnkle.all { abs(it.x - 65f) < 1e-5f && abs(it.y - 25f) < 1e-5f && abs(it.z + def.hipWidth) < 1e-5f }
        )
        assertTrue(
            "the front leg's declared target must stay inside its own band",
            rs.filter { it.chain == Joint.ANKLE_F }.all { bandExcess(it) <= bandTolerance }
        )
        // (b) the pelvis still lunges forward and drops (the stretch's authored motion).
        val playing = rs.filter { it.frame == "playing" }
        val byPhase = playing.groupBy { it.phase }
        assertEquals("every sampled phase must contribute every declared chain", phases.size, byPhase.size)
        val pelvisX = playing.map { it.pelvis.x }
        val pelvisY = playing.map { it.pelvis.y }
        assertTrue(
            "the pelvis lunge must survive the fix (x spread ${f(pelvisX.max() - pelvisX.min())})",
            pelvisX.max() - pelvisX.min() >= 20f
        )
        assertTrue(
            "the pelvis' authored Pythagorean drop must survive the fix " +
                "(y spread ${f(pelvisY.max() - pelvisY.min())})",
            pelvisY.max() - pelvisY.min() >= 5f
        )
        // (c) the hands still aim at the front knee: every declared arm target points along the ray
        //     from its own shoulder to that frame's knee-composed choreography target.
        val bad = mutableListOf<String>()
        for (r in playing) {
            if (r.chain != Joint.HAND_A && r.chain != Joint.HAND_P) continue
            val knee = playing.first { it.phase == r.phase && it.chain == Joint.ANKLE_F }
            val kneeApex = planFrontKneeFromHip(knee.hip, def)
            val aimZ = if (r.chain == Joint.HAND_A) -def.shoulderWidth * 0.8f else def.shoulderWidth * 0.8f
            val aimX = kneeApex.x - 10f - r.root.x
            val aimY = kneeApex.y + 15f - r.root.y
            val aimZN = aimZ - r.root.z
            val cx = r.declared.x - r.root.x
            val cy = r.declared.y - r.root.y
            val cz = r.declared.z - r.root.z
            val cm = sqrt(cx * cx + cy * cy + cz * cz)
            val am = sqrt(aimX * aimX + aimY * aimY + aimZN * aimZN)
            val cosA = ((cx * aimX + cy * aimY + cz * aimZN) / (cm * am)).coerceIn(-1f, 1f)
            if (cosA < 0.999999f) {
                bad += "${where(r)}: declared aim deviates from the knee-composed ray " +
                    "(cos = ${f(cosA)}, declared ${vec(r.declared)})"
            }
        }
        assertTrue(
            "the fix must keep the arms' authored aim at the front knee (only the radius moves):\n" +
                bad.joinToString("\n"),
            bad.isEmpty()
        )
    }

    @Test
    fun theHamstringStretchKeepsItsAuthoredTuckFoldAndExtendedLeg() {
        val rs = readings(HamstringStretchPose())
        // (a) the extended leg (the stretched one) is untouched: same literal, same band.
        val front = rs.filter { it.chain == Joint.ANKLE_F }
        assertTrue(
            "the extended leg's authored target (thigh + shin − 5 = 205) must be untouched: " +
                front.map { f(it.declaredDistance) }.distinct().joinToString(),
            front.all { abs(it.declaredDistance - (def.thighLength + def.shinLength - 5f)) < 1e-4f }
        )
        // (b) the tucked leg's authored DIRECTION is preserved: the declared target still lies on the
        //     pose's own hip→(pelvisX + 35, 15, hipWidth/2) ray.
        val tucked = rs.filter { it.chain == Joint.ANKLE_B }
        val badDir = mutableListOf<String>()
        for (r in tucked) {
            val t = Vector3(-30f + 35f, 15f, def.hipWidth * 0.5f)
            val rx = t.x - r.root.x; val ry = t.y - r.root.y; val rz = t.z - r.root.z
            val rm = sqrt(rx * rx + ry * ry + rz * rz)
            val dx = r.declared.x - r.root.x; val dy = r.declared.y - r.root.y; val dz = r.declared.z - r.root.z
            val dm = sqrt(dx * dx + dy * dy + dz * dz)
            val cosA = ((dx * rx + dy * ry + dz * rz) / (dm * rm)).coerceIn(-1f, 1f)
            if (cosA < 0.999999f) badDir += "${where(r)}: tucked-leg aim cos = ${f(cosA)}"
        }
        assertTrue(
            "the tucked leg must keep the pose's authored tuck direction:\n" + badDir.joinToString("\n"),
            badDir.isEmpty()
        )
        // (c) the fold still travels (the hold is a moving hinge) and the hips stay on the mat.
        val hands = rs.filter { it.chain == Joint.HAND_A }
        val travel = hands.maxOf { it.effector.x } - hands.minOf { it.effector.x }
        assertTrue(
            "the forward fold must still travel (hand x spread ${f(travel)})",
            travel >= 100f
        )
        assertTrue(
            "the seated root must stay on the mat (pelvis y ${f(rs.first().pelvis.y)})",
            abs(rs.first().pelvis.y - 15f) < 1e-3f
        )
    }

    @Test
    fun theProneCobraKeepsItsAuthoredExtensionAndHandSweep() {
        val rs = readings(ProneCobraStretchPose())
        // (a) the arms still sweep from the chest back toward the hips/heels (the authored motion).
        val hands = rs.filter { it.chain == Joint.HAND_A }
        val travel = hands.maxOf { it.declared.x } - hands.minOf { it.declared.x }
        assertTrue(
            "the authored hand sweep must survive the fix (declared x travel ${f(travel)})",
            travel >= 150f
        )
        assertTrue(
            "the sweep must still rise (declared y ${f(hands.minOf { it.declared.y })} … " +
                "${f(hands.maxOf { it.declared.y })})",
            hands.maxOf { it.declared.y } - hands.minOf { it.declared.y } >= 20f
        )
        // (b) the legs are not part of this fix.
        val legs = rs.filter { it.chain == Joint.ANKLE_F || it.chain == Joint.ANKLE_B }
        assertTrue(
            "the legs' authored target (pelvisX − thigh − shin + 15 = −195) must be untouched: " +
                legs.map { f(it.declaredDistance) }.distinct().joinToString(),
            legs.all { abs(it.declaredDistance - 195f) < 1e-4f }
        )
        // (c) anti-collateral: the declaration surface is untouched by a reach-authoring fix.
        for (r in rs) {
            assertEquals(
                "${where(r)}: the declared support model must be unchanged",
                "LEFT_FOOT,LEFT_HAND,RIGHT_FOOT,RIGHT_HAND", r.support
            )
            assertEquals("${where(r)}: the declared ground level must be unchanged", 0f, r.groundLevel, 0f)
            assertTrue(
                "${where(r)}: no joint may be published below the declared ground level " +
                    "(min joint y = ${f(r.minJointY)})",
                r.minJointY >= r.groundLevel - 1e-3f
            )
        }
    }

    // ----------------------------------------------------------------------------------------------
    // 5 — THE FAMILY-SCOPE PIN: the sibling that shares the site's authoring is NOT re-scoped
    // ----------------------------------------------------------------------------------------------

    /**
     * `CouchStretchPose` composes its hands through the SAME `BaseHipFlexorPose.solveArmsOnKnee`
     * choreography and carries the same class of out-of-band site. It is out of this batch, so the
     * batch opts the projection in per variant: this test fails if someone "fixes" the shared base
     * (which would silently re-scope the sibling into a pose-only pass), and equally if the sibling's
     * site ever becomes in-band without its own measurement record.
     */
    @Test
    fun theSharedFamilyChoreographyIsNotReScopedByThisBatch() {
        val sibling = readings(CouchStretchPose())
        val arms = sibling.filter { it.chain == Joint.HAND_A || it.chain == Joint.HAND_P }
        val worst = arms.maxOf { it.relocation }
        assertTrue(
            "CouchStretchPose's arm site is out of this batch's scope and must read as measured " +
                "(13.5995 u relocation; measured ${f(worst)}) — a base-scoped change is its own decision",
            abs(worst - 13.5995f) < 0.01f
        )
        assertTrue(
            "the sibling's superseded declaration must still be the pre-batch one (worst declared " +
                "${f(arms.maxOf { it.declaredDistance })} of the ${f(arms.first().maxReach)} cap)",
            arms.maxOf { it.declaredDistance } > arms.first().maxReach
        )
        // …and its legs, which never had a site, stay in band.
        assertTrue(
            "CouchStretchPose's front leg must stay in band",
            sibling.filter { it.chain == Joint.ANKLE_F }.all { bandExcess(it) <= bandTolerance }
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 6 — NON-VACUITY: the same measurement still rejects the PRE-BATCH authoring, and the fixed
    //     poses publish the SAME geometry the pre-batch declaration did
    // ----------------------------------------------------------------------------------------------

    /**
     * The sensitivity control. All three poses are FINAL classes, so the pre-batch authoring is
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
                "HalfKneelingStretchPose" -> {
                    // The base's shared arm choreography, verbatim: hands at the front knee's
                    // planning apex + (−10, +15, ±0.8 · shoulderWidth).
                    val knee = planFrontKneeFromHip(built.getJoint(Joint.HIP_F), def)
                    setTarget(built, Joint.HAND_A, knee.x - 10f, knee.y + 15f, -def.shoulderWidth * 0.8f)
                    setTarget(built, Joint.HAND_P, knee.x - 10f, knee.y + 15f, def.shoulderWidth * 0.8f)
                }
                "HamstringStretchPose" -> {
                    // The pose's own tucked-leg literal, verbatim: (pelvisX + 35, 15, hipWidth/2).
                    setTarget(built, Joint.ANKLE_B, -30f + 35f, 15f, def.hipWidth * 0.5f)
                }
                "ProneCobraStretchPose" -> {
                    // The pose's own sweep, verbatim: lerp(chest.x, −50) / lerp(15, 40).
                    val chestX = built.getJoint(Joint.CHEST).x
                    val x = chestX + (-50f - chestX) * p
                    val y = 15f + (40f - 15f) * p
                    setTarget(built, Joint.HAND_A, x, y, -def.shoulderWidth * 1.5f)
                    setTarget(built, Joint.HAND_P, x, y, def.shoulderWidth * 1.5f)
                }
            }
            return built
        }
    }

    @Test
    fun theSameMeasurementStillRejectsThePreBatchAuthoring() {
        // The pre-batch relocation each pose's authoring read on `origin/main` @ `cf8a14f`.
        val preBatch = mapOf(
            "HalfKneelingStretchPose" to 23.788f,
            "HamstringStretchPose" to 19.321f,
            "ProneCobraStretchPose" to 17.134f
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
            // …and the production poses must NOT read like the control (the harness discriminates).
            val worstProduction = production.maxOf { it.relocation }
            if (worstProduction >= worstControl / 10f) {
                failures += "$name: the production pose must differ from the pre-batch control on " +
                    "the measured property (${f(worstProduction)} vs ${f(worstControl)})"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue(
            "anti-vacuity: the control must have produced readings (got $controlReadings)",
            controlReadings >= 100
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
    // 7 — the corpus's other measured out-of-band sites are untouched (blast radius, reach dimension)
    // ----------------------------------------------------------------------------------------------

    /** pose → the worst relocation its authoring read on the base tree (`origin/main` @ `cf8a14f`). */
    private val corpusSites = mapOf(
        "CouchStretchPose" to 13.5995f,
        "GluteBridgePose" to 10.9979f,
        "PelvicTiltPose" to 10.9979f,
        "SupermanPose" to 4.2000f,
        "ThoracicExtensionPose" to 5.5162f,
        "QuadrupedThoracicRotationsPose" to 32.5176f,
        "DynamicWorldsGreatestStretchPose" to 21.6410f,
        "AlternatingForwardLungesPose" to 1.2263f,
        "AlternatingReverseLungesPose" to 1.2263f,
        "AlternatingSideLungesPose" to 1.2263f,
        "CossackSquatPose" to 1.2000f,
        "MountainClimberPose" to 0.5640f,
        "DeadBugPose" to 2.9200f
    )

    @Test
    fun everyOtherPosesMeasuredReachSiteIsUnchanged() {
        val bad = mutableListOf<String>()
        for ((name, expected) in corpusSites) {
            if (name in fixed().keys) continue
            val worst = readings(MotionProbe.build(name)).maxOf { it.relocation }
            if (abs(worst - expected) > 0.01f) {
                bad += "$name: measured worst relocation ${f(worst)}, base-tree value ${f(expected)}"
            }
        }
        assertTrue(
            "this batch is confined to its three poses: every other pose's measured reach site must " +
                "read byte-identically (a base-class or solver change would move them):\n" +
                bad.joinToString("\n"),
            bad.isEmpty()
        )
    }

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
}

/** Overwrites a declared limb target in place (test-side only; see [PreBatchAuthoredTargets]). */
private fun setTarget(pose: SkeletonPose, joint: Joint, x: Float, y: Float, z: Float) {
    val target = pose.limbTargets.firstOrNull { it.joint == joint } ?: return
    target.world.set(x, y, z)
}

/**
 * Replays `BaseHipFlexorPose.planFrontLegKnee`'s sanctioned planning solve from a built frame:
 * the front knee's apex for the pose's own authored front-leg target, used to re-compose the base's
 * pre-batch arm choreography.
 */
private fun planFrontKneeFromHip(hip: Vector3, def: SkeletonDefinition): Vector3 {
    val ankle = Vector3(65f, 25f, -def.hipWidth)
    val out = SkeletonMath.IKResult()
    planLimbPlacement(
        Vector3(hip.x, hip.y, hip.z), ankle, def.thighLength, def.shinLength,
        Vector3(1f, 0f, -0.5f), def.legIKConstraint, out
    )
    return out.joint
}
