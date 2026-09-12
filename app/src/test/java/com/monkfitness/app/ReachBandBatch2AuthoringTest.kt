package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second reach-band cleanup batch — `DeepSquatHoldPose`, `JumpSquatPose` (production geometry) and the
 * `CossackSquatPose` deliberate-limit control (test-only; its authoring is unchanged).
 *
 * ## What this batch measured (base `origin/main` @ `ef9400f`, the #253 merge)
 *
 * Every authored limb target of the three poses was measured through the production entry point
 * `SkeletonPipeline.produceFrame(pose, ctx)` — `15` phases (`0.00, 0.05, 0.10, 0.20, 0.25, 0.30, 0.40,
 * 0.50, 0.60, 0.70, 0.75, 0.80, 0.90, 0.95, 1.00`) × the four limb chains × both frame conditions —
 * against the chain's own `[SkeletonMath.minReach, maxReach]`.
 *
 * | pose | chain | authored root→target | band | relocation |
 * |---|---|---|---|---|
 * | `DeepSquatHoldPose` | both ankles | `47.392` (every frame; the pose is a locked hold) | `≥ 56.009` | **`8.617`** |
 * | `JumpSquatPose` | both ankles | `210.288` (seam frames) … `225.269` (apex flight) | `≤ 205.800` | **`4.488 … 19.469`** |
 * | `JumpSquatPose` | both hands | `23.396` … `45.321` | `≥ 40.134` | **`0.605 … 16.739`** |
 * | `CossackSquatPose` | both ankles | `207.000` (the extended-straight-leg stance) | `≤ 205.800` | **`1.200`** |
 * | `CossackSquatPose` | both hands | `91.010` … `95.111` | in band | `0.000` |
 * | `DeepSquatHoldPose` | both hands | `46.487` (the clasped-hands centre-chest target) | in band | `0.000` |
 *
 * ## Classification — measured, per SITE, on the realized interior angle
 *
 * The discriminator is whether the authored request is realizable by an IDEAL (`L1 + L2`) chain at all:
 *
 *  * **Unintended authoring error** — the request is past the pose's own model, so it could not be
 *    honoured by ANY chain geometry: `DeepSquatHoldPose`'s ankles ask for an interior knee angle of
 *    `24.80°` against the constraint's own `30°` stop (`docs/Biomechanical Pose Specification (BPS)/
 *    Squat (Deep Hold).md` states maximal knee flexion of `130–150°`, i.e. `30–50°` interior — the
 *    authored depth is past its own spec); `JumpSquatPose`'s flight ankles ask for `225.269` from a
 *    `112 + 98 = 210` leg, i.e. `107 %` of the limb's own length; its hands ask for an interior elbow
 *    angle of `15.36°` against the same `30°` stop. These are the same class as the first batch's
 *    sites (`210.288` from a `210` leg; a `15.8°` knee against the stop).
 *  * **Deliberate model limit** — `CossackSquatPose`'s straight-leg ankle asks for `207.000`, an
 *    interior knee angle of `160.6°`: a REAL straight leg, and the BPS demands exactly that
 *    ("the opposite (straight) leg is **fully extended** … straight-leg knee fully extended … the
 *    straight foot remains flat"). Nothing here is unrealizable — the engine's deliberate
 *    `IKConstraint.effectiveExtensionRatio = 0.98` cap trims the last `1.2` u so that the 2-bone chain
 *    never reaches its singular fully-collinear configuration. The relocation is the cap doing its job
 *    on a correct request, so this pose's authoring is NOT changed: the published straight leg reads
 *    the cap, and the `1.2` u is the trim. Its sibling stance in `BaseLungePose` (the three
 *    alternating lunge variants, out of this batch's scope) carries the same pattern and the same
 *    verdict.
 *
 * ## The fix (pose-side only, reusing the first batch's family helpers)
 *
 * `DeepSquatHoldPose` and `JumpSquatPose` extend `BaseSquatPose`, which already carries the R2/R4
 * projection (first batch, PR #253): `projectLegTargetsToReach` / `projectArmTargetsToReach`, one copy
 * rooted at `hip*` / `shoulder*`, at `REACH_MARGIN = 1e-4` of the chain's span. These two poses call it
 * after `super` — no solver, engine, carrier or stamp semantics change, and the published geometry is
 * preserved (the solver's own relocation IS the boundary projection this replaces).
 *
 * A pose's `build()` is the sole declaration channel and `IK_STAGE_ACTIVE` re-bakes from the carrier,
 * so re-declaring the PRE-BATCH targets through [PreBatchAuthoredTargets] reproduces the pre-fix
 * published frame through the same production entry point — that is the sensitivity control below.
 */
class ReachBandBatch2AuthoringTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** Dense enough to bracket both the seam/standing frames and the ballistic apex. */
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

    /** The poses this batch author-fixes (both `BaseSquatPose` subclasses). */
    private fun fixed(): Map<String, () -> PoseBuilder> = mapOf(
        "DeepSquatHoldPose" to { DeepSquatHoldPose() as PoseBuilder },
        "JumpSquatPose" to { JumpSquatPose() as PoseBuilder }
    )

    private val bandTolerance = 1e-3f
    private val relocationTolerance = 0.05f

    /** The pre-batch relocation the fix removes (measured on `origin/main` @ `ef9400f`). */
    private val preBatchRelocation = 4.488f

    private class Reading(
        val pose: String,
        val frame: String,
        val phase: Float,
        val chain: Joint,
        val declaredDistance: Float,
        val minReach: Float,
        val maxReach: Float,
        val relocation: Float,
        val publishedDistance: Float,
        val interiorDegrees: Float,
        val clampStamp: Float,
        val effectorY: Float,
        val pelvisY: Float,
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
        val pelvisY = published.getJoint(Joint.PELVIS).y
        var minJointY = Float.MAX_VALUE
        for (j in Joint.entries) minJointY = minOf(minJointY, published.getJoint(j).y)
        val out = mutableListOf<Reading>()
        for ((end, rootJoint, midJoint) in chains) {
            val target = published.limbTargets.firstOrNull { it.joint == end } ?: continue
            val constraint = target.constraint!!
            val root = published.getJoint(rootJoint)
            val mid = published.getJoint(midJoint)
            val effector = published.getJoint(end)
            val dx = target.world.x - root.x
            val dy = target.world.y - root.y
            val dz = target.world.z - root.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val rx = effector.x - target.world.x
            val ry = effector.y - target.world.y
            val rz = effector.z - target.world.z
            out += Reading(
                pose = name, frame = frame, phase = phase, chain = end,
                declaredDistance = d,
                minReach = SkeletonMath.minReach(target.length1, target.length2, constraint),
                maxReach = SkeletonMath.maxReach(target.length1, target.length2, constraint),
                relocation = sqrt(rx * rx + ry * ry + rz * rz),
                publishedDistance = distance(effector, root),
                interiorDegrees = interiorAngleDegrees(root, mid, effector),
                clampStamp = stamp,
                effectorY = effector.y,
                pelvisY = pelvisY,
                support = support,
                groundLevel = groundLevel,
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

    private fun where(r: Reading) = "${r.pose}/${r.chain.name} @ p=${r.phase} (${r.frame})"

    // ----------------------------------------------------------------------------------------------
    // 1 — every authored target of the fixed poses is inside its own chain's reachable band
    // ----------------------------------------------------------------------------------------------

    @Test
    fun everyFixedTargetSitsInsideItsChainsOwnReachBand() {
        val violations = mutableListOf<String>()
        for (r in fixedReadings()) {
            val excess = when {
                r.declaredDistance > r.maxReach -> r.declaredDistance - r.maxReach
                r.declaredDistance < r.minReach -> r.minReach - r.declaredDistance
                else -> 0f
            }
            if (excess > bandTolerance) {
                violations += "${where(r)}: authored root→target ${"%.6f".format(r.declaredDistance)} " +
                    "outside [${"%.6f".format(r.minReach)}, ${"%.6f".format(r.maxReach)}] by " +
                    "${"%.6f".format(excess)}"
            }
        }
        assertTrue(
            "the fixed poses' authored limb targets must be reachable-by-construction (R2/R4) — the " +
                "solver relocates anything outside the chain's own annulus:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 2 — the published effector IS the authored target (no relocation)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun thePublishedEffectorFollowsTheAuthoredTargetWithoutRelocation() {
        val bad = mutableListOf<String>()
        for (r in fixedReadings()) {
            if (r.relocation > relocationTolerance) {
                bad += "${where(r)}: |published − declared| = ${"%.4f".format(r.relocation)} " +
                    "(declared ${"%.4f".format(r.declaredDistance)} of the chain's band, " +
                    "interior ${"%.2f".format(r.interiorDegrees)}°)"
            }
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
        val bad = mutableListOf<String>()
        for (r in fixedReadings()) {
            if (r.clampStamp > relocationTolerance) bad += "${where(r)}: reachability stamp = ${"%.4f".format(r.clampStamp)}"
        }
        assertTrue(
            "a reachable-by-construction target must solve without a relocation stamp:\n" +
                bad.distinct().joinToString("\n"),
            bad.isEmpty()
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 4 — the fix is target-side: the poses keep their authored hold depth, ballistic motion and
    //     declaration surface
    // ----------------------------------------------------------------------------------------------

    @Test
    fun theFixedPosesKeepTheirAuthoredDepthMotionAndDeclarationSurface() {
        // (a) DeepSquatHoldPose is a LOCKED hold: the fix must not have moved its depth (a root-side
        //     "fix" would have — this is the anti-scope-creep guard for the pose's identity).
        val hold = readings(DeepSquatHoldPose())
        val holdPelvis = hold.map { it.pelvisY }
        assertTrue(
            "DeepSquatHoldPose's authored locked depth must be untouched (pelvis y spread " +
                "${"%.4f".format(holdPelvis.max() - holdPelvis.min())})",
            holdPelvis.max() - holdPelvis.min() <= 1f
        )
        assertTrue(
            "DeepSquatHoldPose must still hold the deep bottom (pelvis y ${"%.2f".format(holdPelvis.min())})",
            holdPelvis.min() <= 61f
        )

        // (b) JumpSquatPose keeps its ballistic cycle: the pelvis travels and the feet follow.
        val jump = readings(JumpSquatPose())
        val playing = jump.filter { it.frame == "playing" }
        val perPhase = playing.groupBy { it.phase }
        assertEquals("every sampled phase must contribute all four chains", phases.size, perPhase.size)
        val pelvis = playing.map { it.pelvisY }
        assertTrue(
            "JumpSquatPose's ballistic pelvis travel must survive the fix (spread " +
                "${"%.4f".format(pelvis.max() - pelvis.min())})",
            pelvis.max() - pelvis.min() >= 35f
        )
        val feet = playing.filter { it.chain == Joint.ANKLE_F }.map { it.effectorY }
        assertTrue(
            "JumpSquatPose's flight must still lift the feet clear of the floor rest (spread " +
                "${"%.4f".format(feet.max() - feet.min())})",
            feet.max() - feet.min() >= 30f
        )

        // (c) non-aliasing, where the pose actually moves. An aliased harness (storing the reused
        //     buffer) reports ONE repeated position for the whole rep — which the travel spreads above
        //     already discriminate. JumpSquatPose's ballistic cycle is symmetric about its apex, so its
        //     DISTINCT phase set is half the sample count; that is asserted explicitly here.
        val jumpHands = perPhase.values.map { group ->
            val hands = group.filter { it.chain == Joint.HAND_A }
            assertTrue("JumpSquatPose p=${group.first().phase}: HAND_A must be declared", hands.size == 1)
            "%.4f/%.4f".format(hands.first().declaredDistance, hands.first().effectorY)
        }.distinct()
        assertTrue(
            "JumpSquatPose: the sampled frames must be distinct frames, not one aliased buffer " +
                "(${jumpHands.size} distinct of ${phases.size})",
            jumpHands.size >= 5
        )
        // DeepSquatHoldPose is a LOCKED hold — every frame is the same frame by design, so it carries
        // no aliasing signal; its own invariants (constant depth, in-band declarations) are the gates.
        val holdPhases = hold.filter { it.frame == "playing" }.groupBy { it.phase }
        assertEquals("DeepSquatHoldPose must be sampled at every phase", phases.size, holdPhases.size)

        // (d) anti-collateral: the declaration surface is untouched by a reach-authoring fix.
        for (r in hold + jump) {
            assertEquals("${where(r)}: the declared support model must be unchanged", "LEFT_FOOT,RIGHT_FOOT", r.support)
            assertEquals("${where(r)}: the declared ground level must be unchanged", 0f, r.groundLevel, 0f)
            assertTrue(
                "${where(r)}: no joint may be published below the declared ground level " +
                    "(min joint y = ${"%.4f".format(r.minJointY)})",
                r.minJointY >= r.groundLevel
            )
        }
    }

    // ----------------------------------------------------------------------------------------------
    // 5 — the DELIBERATE limit stays deliberate: `CossackSquatPose`'s straight leg is spec'd fully
    //     extended, the model's `0.98` cap trims it by `1.2` u, and that must NOT be "cleaned"
    // ----------------------------------------------------------------------------------------------

    @Test
    fun theIntentionalCossackStraightLegLimitIsPreserved() {
        val cossack = readings(CossackSquatPose())
        val legSpan = def.thighLength + def.shinLength
        val bad = mutableListOf<String>()
        var siteFrames = 0
        for ((phase, frameReadings) in cossack.groupBy { it.phase }) {
            // The deliberate-limit site is the frame where the authored leg request sits BEYOND the
            // model's 0.98 extension cap while still asking for a real leg pose (the seam/standing
            // frames: `BaseLungePose.standingPelvisY = thigh + shin + 22 = 232` with `footRestY = 25`
            // ⇒ a `207` chord from a `210` limb).
            val legs = frameReadings.filter { it.chain == Joint.ANKLE_F || it.chain == Joint.ANKLE_B }
            val straight = legs.maxByOrNull { it.declaredDistance } ?: continue
            if (straight.declaredDistance <= straight.maxReach + bandTolerance) continue
            siteFrames++
            // (a) the request is REALIZABLE by the limb itself — this is what makes the trim the
            //     model's deliberate numeric cap and not an anatomical impossibility.
            if (straight.declaredDistance > legSpan) {
                bad += "p=$phase: the request ${"%.3f".format(straight.declaredDistance)} u EXCEEDS the " +
                    "limb's own ${"%.0f".format(legSpan)} u length — no longer a realizable pose"
            }
            // (b) the realized straight leg reads the model's extension limit (≈157° interior), i.e.
            //     the cap trims a straight leg, not a fold.
            if (straight.interiorDegrees < 150f) {
                bad += "p=$phase: the realized straight leg reads ${"%.2f".format(straight.interiorDegrees)}° — " +
                    "not the model's extension limit"
            }
            // (c) the trim stays the deliberate cap's 1.2 u.
            if (straight.relocation > 1.5f) {
                bad += "p=$phase: the straight-leg trim reads ${"%.4f".format(straight.relocation)} u — " +
                    "larger than the deliberate extension cap's trim"
            }
        }
        assertTrue(
            "CossackSquatPose's spec-mandated straight-leg stance must still be sampled at its " +
                "deliberate extension-cap site (sampled $siteFrames frames) — if this reads 0 the " +
                "deliberate limit was 'cleaned', which is a scope regression",
            siteFrames > 0
        )
        assertTrue(
            "CossackSquatPose's straight leg is spec'd FULLY EXTENDED and the model's 0.98 cap answers " +
                "with a 1.2-u trim; the deliberate trim must stay a trim on a realizable request:\n" +
                bad.joinToString("\n"),
            bad.isEmpty()
        )
        // (d) and the rest of the pose stays inside its bands (the cap is the pose's only out-of-band site).
        val others = cossack.filter { !(it.chain == Joint.ANKLE_F || it.chain == Joint.ANKLE_B) }
        assertTrue(
            "CossackSquatPose's non-leg chains must stay inside their own bands",
            others.all { it.declaredDistance in it.minReach..it.maxReach }
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 6 — NON-VACUITY: the same measurement still rejects the PRE-BATCH authoring
    // ----------------------------------------------------------------------------------------------

    /**
     * The sensitivity control. `DeepSquatHoldPose`/`JumpSquatPose` are FINAL classes, so the pre-batch
     * authoring is restored through the sanctioned builder-delegation shape (the carrier is the sole
     * declaration channel and `IK_STAGE_ACTIVE` re-bakes from it, so declaring the pre-batch targets
     * reproduces the pre-batch published frame through the SAME production entry point).
     */
    private class PreBatchAuthoredTargets(
        private val inner: PoseBuilder,
        private val def: SkeletonDefinition
    ) : PoseBuilder by inner {
        override fun build(context: PoseContext): SkeletonPose {
            val built = inner.build(context)
            val p = context.progress
            built.limbTargets.clear()
            val legsAreJumpFlight = inner.javaClass.simpleName == "JumpSquatPose"
            if (legsAreJumpFlight) {
                // The pose's own pre-batch flight kinematics, verbatim.
                val cycle = (p * 2f * PI.toFloat()) - (PI.toFloat() / 2f)
                val rawSin = sin(cycle)
                val squatFactor = maxOf(0f, -rawSin)
                val standH = def.shinLength + def.thighLength + 25f
                val pelvisY = standH + (rawSin * 40f)
                val pelvisX = squatFactor * -25f
                val footLift = maxOf(0f, rawSin) * 25f
                built.limbTargets.add(
                    WorldTarget(Joint.ANKLE_F, Vector3(0f, 25f + footLift, -def.hipWidth * 1.5f),
                        Vector3(1f, 0f, -0.3f), false, null, def.thighLength, def.shinLength, def.legIKConstraint)
                )
                built.limbTargets.add(
                    WorldTarget(Joint.ANKLE_B, Vector3(0f, 25f + footLift, def.hipWidth * 1.5f),
                        Vector3(1f, 0f, 0.3f), false, null, def.thighLength, def.shinLength, def.legIKConstraint)
                )
                val handX = pelvisX + (-rawSin * 35f) + 5f
                val handY = pelvisY + def.torsoLength - 10f + (-rawSin * 15f)
                built.limbTargets.add(
                    WorldTarget(Joint.HAND_A, Vector3(handX, handY, -def.shoulderWidth * 1.5f),
                        Vector3(0f, -1f, -1f), false, null, def.upperArmLength, def.forearmLength, def.armIKConstraint)
                )
                built.limbTargets.add(
                    WorldTarget(Joint.HAND_P, Vector3(handX, handY, def.shoulderWidth * 1.5f),
                        Vector3(0f, -1f, 1f), false, null, def.upperArmLength, def.forearmLength, def.armIKConstraint)
                )
            } else {
                // `BaseSquatPose.fillLegTargets` (the DeepSquatHold' inherited authoring), verbatim.
                built.limbTargets.add(
                    WorldTarget(Joint.ANKLE_F, Vector3(0f, 25f, -def.hipWidth * 1.5f),
                        Vector3(1f, 0f, -0.4f), false, null, def.thighLength, def.shinLength, def.legIKConstraint)
                )
                built.limbTargets.add(
                    WorldTarget(Joint.ANKLE_B, Vector3(0f, 25f, def.hipWidth * 1.5f),
                        Vector3(1f, 0f, 0.4f), false, null, def.thighLength, def.shinLength, def.legIKConstraint)
                )
            }
            return built
        }
    }

    @Test
    fun theSameMeasurementStillRejectsThePreBatchAuthoring() {
        val control = fixed().values.flatMap { readings(PreBatchAuthoredTargets(it(), def)) }

        val worstRelocation = control.maxOf { it.relocation }
        val worstStamp = control.maxOf { it.clampStamp }
        val outOfBand = control.count { it.declaredDistance < it.minReach || it.declaredDistance > it.maxReach }

        assertTrue(
            "NON-VACUITY: the pre-batch authoring must still read as relocated by this harness " +
                "(measured relocation ${"%.4f".format(worstRelocation)}, stamp ${"%.4f".format(worstStamp)}, " +
                "out-of-band readings $outOfBand of ${control.size})",
            worstRelocation >= preBatchRelocation && worstStamp >= preBatchRelocation && outOfBand > 0
        )
        // …and the production poses must NOT read like the control (the harness discriminates).
        val production = fixedReadings()
        assertTrue(
            "the production poses must differ from the pre-batch control on the measured property",
            production.maxOf { it.relocation } < worstRelocation / 10f
        )
    }

    /** The interior angle at the middle joint of a realized (published) chain. */
    private fun interiorAngleDegrees(a: Vector3, b: Vector3, c: Vector3): Float {
        val ux = a.x - b.x; val uy = a.y - b.y; val uz = a.z - b.z
        val vx = c.x - b.x; val vy = c.y - b.y; val vz = c.z - b.z
        val um = sqrt(ux * ux + uy * uy + uz * uz)
        val vm = sqrt(vx * vx + vy * vy + vz * vz)
        val cosA = ((ux * vx + uy * vy + uz * vz) / (um * vm)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosA.toDouble())).toFloat()
    }

    private fun distance(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
