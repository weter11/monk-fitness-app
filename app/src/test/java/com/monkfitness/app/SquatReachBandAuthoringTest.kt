package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * First reach-band cleanup batch — `AirSquatPose` + `SquatPose` (R2/R4 reach-target authoring).
 *
 * Both poses inherit `BaseSquatPose`'s default authored geometry: the ankle target is the FLOOR-frame
 * rest position `(0, 25, ±1.5·hipWidth)` and the hand target is the counterbalance reach
 * (`x: 0 → armLeanEnd·40`, `y: the shoulder's own height − 10·p`, `z: ±1.2·shoulderWidth`).
 * Measured on `origin/main` @ `d6f4f7f` through the production entry point
 * `SkeletonPipeline.produceFrame(pose, ctx)`, four of those targets sit OUTSIDE their own chain's
 * reachable annulus `[SkeletonMath.minReach, maxReach]`, so the solver relocated the realized
 * end-effector along the authored ray:
 *
 * | target | phase | declared root→target | band | relocation |
 * |---|---|---|---|---|
 * | `AirSquatPose` / `SquatPose` ankle | 0.00 | `210.288` | `≤ 205.800` | **`4.488`** |
 * | `AirSquatPose` ankle | 0.95 … 1.00 | `55.112 … 48.435` | `≥ 56.009` | **`0.897 … 7.574`** |
 * | `SquatPose` ankle | 0.90 … 1.00 | `49.244 … 31.953` | `≥ 56.009` | **`6.765 … 24.056`** |
 * | both, hands (the WHOLE rep) | 0.00 … 1.00 | `9.200 … 15.886` | `≥ 40.134` | **`24.248 … 30.934`** |
 *
 * Verdict (measured per site, see the batch record in `docs/STABILIZATION_AUDIT.md`): **unintended
 * authoring error**, not an intentional ROM limit. The authored numbers do not describe the pose's
 * own stated intent — a standing leg locked out past the engine's `0.98` extension cap, a squat
 * bottom folded past the chain's `30°` flexion stop, and a "`40`-unit counterbalance reach"
 * (`armLeanEnd = 1.0f`) authored at the shoulder's own height so the hand never leaves a `9 … 16`
 * unit radius. The published geometry is the projection, not the authoring.
 *
 * Fix: the family's own R2/R4 convention (`SumoSquatPose`'s wide track, `BaseVerticalPullPose`'s
 * pendulum legs) — the authored target is projected onto its own chain's annulus along its own ray.
 * The margin is a hundredth of a percent of the chain's span (`BaseSquatPose.REACH_MARGIN`), not the
 * helper's canonical `0.02` and not `0`: each of these targets *is* the intended end position (the
 * ankle's floor rest height, the authored reach direction) and the solver's own relocation is exactly
 * a boundary projection, so the realized geometry is preserved to `0.021` u — while the declaration
 * becomes the realized position and the relocation stamp returns to exactly `0`.
 *
 * The guard is measured on the PUBLISHED frame, on both frame conditions (a fresh pipeline's cold
 * first frame and an advancing pipeline's frames — the engine's cold-frame class, B-8/B-8b), and
 * reads each joint's primitives during the single pass (`produceFrame(...).pose` is a reused buffer).
 */
class SquatReachBandAuthoringTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** The batch: the two variants that inherit the family's default authored geometry. */
    private fun batch(): Map<String, () -> PoseBuilder> = mapOf(
        "AirSquatPose" to { AirSquatPose() as PoseBuilder },
        "SquatPose" to { SquatPose() as PoseBuilder }
    )

    /** Dense enough to bracket both band edges — the measured violations live at `p ≈ 0` and `p ≳ 0.9`. */
    private val phases = floatArrayOf(
        0.0f, 0.01f, 0.02f, 0.05f, 0.1f, 0.25f, 0.5f, 0.75f, 0.85f, 0.9f, 0.95f, 0.98f, 0.99f, 1.0f
    )

    /** end joint → (chain root, middle joint). */
    private val chains = listOf(
        Triple(Joint.ANKLE_F, Joint.HIP_F, Joint.KNEE_F),
        Triple(Joint.ANKLE_B, Joint.HIP_B, Joint.KNEE_B),
        Triple(Joint.HAND_A, Joint.SHOULDER_A, Joint.ELBOW_A),
        Triple(Joint.HAND_P, Joint.SHOULDER_P, Joint.ELBOW_P)
    )

    /** The pre-fix relocation the batch fixes (measured on `origin/main` @ `d6f4f7f`). */
    private val preFixRelocation = 4.488f

    /**
     * Band-membership tolerance. The projection's margin (`1e-4` of the chain's span) is what makes
     * "inside the annulus" hold by construction; this tolerance only absorbs the carrier's own
     * coordinate resolution (~`3e-5` at these radii) so the assertion has margin to spare, and must
     * stay far below `1/10` of the smallest pre-fix relocation (`4.488`).
     */
    private val bandTolerance = 1e-3f

    private class Reading(
        val pose: String,
        val frame: String,
        val phase: Float,
        val chain: Joint,
        val declaredDistance: Float,
        val minReach: Float,
        val maxReach: Float,
        val relocation: Float,
        val clampStamp: Float,
        val effectorY: Float,
        val support: String,
        val minJointY: Float,
        val groundLevel: Float
    )

    private fun context(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** Reads one published frame's primitives during the call (never stores the reused buffer). */
    private fun measure(builder: PoseBuilder, pipeline: SkeletonPipeline, frame: String, phase: Float): List<Reading> {
        val name = builder.javaClass.simpleName
        val published = pipeline.produceFrame(builder, context(phase)).pose
        val stamp = published.maxIkClampAmount
        val support = published.supportedPoints.sortedBy { it.name }.joinToString(",") { it.name }
        val groundLevel = builder.metadata.environment.ground.level
        var minJointY = Float.MAX_VALUE
        for (j in Joint.entries) minJointY = minOf(minJointY, published.getJoint(j).y)
        val out = mutableListOf<Reading>()
        for ((end, rootJoint, _) in chains) {
            val target = published.limbTargets.firstOrNull { it.joint == end } ?: continue
            val constraint = target.constraint!!
            val root = published.getJoint(rootJoint)
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
                clampStamp = stamp,
                effectorY = effector.y,
                support = support,
                minJointY = minJointY,
                groundLevel = groundLevel
            )
        }
        return out
    }

    /** Every sampled frame of one builder: the cold first frame, then an advancing pipeline's rep. */
    private fun readings(builder: PoseBuilder): List<Reading> {
        val out = mutableListOf<Reading>()
        // (a) the cold frame — a fresh pipeline's very first frame (B-8b: the first frame realizes
        //     from a stale trunk frame, so the batch is measured on both frame conditions).
        out += measure(builder, SkeletonPipeline(def), "cold", phases.first())
        // (b) the playing frames — an advancing pipeline, warmed exactly as MotionProbe warms it.
        val pipeline = SkeletonPipeline(def)
        for (k in 0..10) pipeline.produceFrame(builder, context(0.3f))
        for (p in phases) out += measure(builder, pipeline, "playing", p)
        return out
    }

    private fun allReadings(): List<Reading> = batch().values.flatMap { readings(it()) }

    private fun where(r: Reading) = "${r.pose}/${r.chain.name} @ p=${r.phase} (${r.frame})"

    // ----------------------------------------------------------------------------------------------
    // 1 — the authored target is inside its own chain's reachable band
    // ----------------------------------------------------------------------------------------------

    @Test
    fun everyAuthoredLimbTargetSitsInsideItsChainsOwnReachBand() {
        val violations = mutableListOf<String>()
        for (r in allReadings()) {
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
            "the batch's authored limb targets must be reachable-by-construction (R2/R4) — the " +
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
        for (r in allReadings()) {
            // 0.05 u: the R2 projection's own float noise. Pre-fix the same reading is 4.488 … 30.934.
            if (r.relocation > 0.05f) {
                bad += "${where(r)}: |published − declared| = ${"%.4f".format(r.relocation)} " +
                    "(declared ${"%.4f".format(r.declaredDistance)} of the chain's band)"
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
        for (r in allReadings()) {
            if (r.clampStamp > 0.05f) bad += "${where(r)}: maxIkClampAmount = ${"%.4f".format(r.clampStamp)}"
        }
        assertTrue(
            "a reachable-by-construction target must solve without a relocation stamp " +
                "(the stamp has to stay a signal, not noise):\n" + bad.distinct().joinToString("\n"),
            bad.isEmpty()
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 4 — NON-VACUITY: the same measurement still rejects the pre-fix authoring
    // ----------------------------------------------------------------------------------------------

    /**
     * The sensitivity control. `AirSquatPose`/`SquatPose` are FINAL classes, so the pre-fix authoring is
     * restored through the sanctioned builder-delegation shape (the carrier is the sole declaration
     * channel and `IK_STAGE_ACTIVE` re-bakes from it, so declaring the pre-fix targets reproduces the
     * pre-fix published frame through the SAME production entry point).
     */
    private class PreFixAuthoredTargets(
        private val inner: PoseBuilder,
        private val def: SkeletonDefinition
    ) : PoseBuilder by inner {
        override fun build(context: PoseContext): SkeletonPose {
            val built = inner.build(context)
            val p = context.progress
            val handY = built.getJoint(Joint.PELVIS).y + def.torsoLength
            built.limbTargets.clear()
            built.limbTargets.add(
                WorldTarget(Joint.ANKLE_F, Vector3(0f, 25f, -def.hipWidth * 1.5f),
                    Vector3(1f, 0f, -0.2f), false, null, def.thighLength, def.shinLength, def.legIKConstraint)
            )
            built.limbTargets.add(
                WorldTarget(Joint.ANKLE_B, Vector3(0f, 25f, def.hipWidth * 1.5f),
                    Vector3(1f, 0f, 0.2f), false, null, def.thighLength, def.shinLength, def.legIKConstraint)
            )
            built.limbTargets.add(
                WorldTarget(Joint.HAND_A, Vector3(40f * p, handY - 10f * p, -def.shoulderWidth * 1.2f),
                    Vector3(0f, -1f, -1f), false, null, def.upperArmLength, def.forearmLength, def.armIKConstraint)
            )
            built.limbTargets.add(
                WorldTarget(Joint.HAND_P, Vector3(40f * p, handY - 10f * p, def.shoulderWidth * 1.2f),
                    Vector3(0f, -1f, 1f), false, null, def.upperArmLength, def.forearmLength, def.armIKConstraint)
            )
            return built
        }
    }

    @Test
    fun theSameMeasurementStillRejectsThePreFixAuthoring() {
        val control = batch().values.flatMap { readings(PreFixAuthoredTargets(it(), def)) }

        val worstRelocation = control.maxOf { it.relocation }
        val worstStamp = control.maxOf { it.clampStamp }
        val outOfBand = control.count { it.declaredDistance < it.minReach || it.declaredDistance > it.maxReach }

        assertTrue(
            "NON-VACUITY: the pre-fix authoring must still read as relocated by this harness " +
                "(measured relocation ${"%.4f".format(worstRelocation)}, stamp ${"%.4f".format(worstStamp)}, " +
                "out-of-band readings $outOfBand of ${control.size})",
            worstRelocation >= preFixRelocation && worstStamp >= preFixRelocation && outOfBand > 0
        )
        // …and the two production poses must NOT read like the control (the harness discriminates).
        val production = allReadings()
        assertTrue(
            "the production poses must differ from the pre-fix control on the measured property",
            production.maxOf { it.relocation } < worstRelocation / 10f
        )
    }

    // ----------------------------------------------------------------------------------------------
    // 5 — NON-VACUITY of the sample set, and the anti-collateral guards
    // ----------------------------------------------------------------------------------------------

    @Test
    fun theSampledFramesAreRealFramesAndTheEffectorsMove() {
        for ((name, factory) in batch()) {
            val builder = factory()
            val playing = readings(builder).filter { it.frame == "playing" }
            val perPhase = playing.groupBy { it.phase }
            assertEquals("every sampled phase must contribute all four chains", phases.size, perPhase.size)

            // Distinct effector positions per phase: an aliased harness (storing the reused buffer)
            // would report ONE repeated position for the whole rep.
            val handPositions = perPhase.values.map { readings ->
                val hands = readings.filter { it.chain == Joint.HAND_A }
                assertTrue("$name p=${readings.first().phase}: HAND_A must be declared", hands.size == 1)
                "%.4f/%.4f".format(hands.first().declaredDistance, hands.first().effectorY)
            }.distinct()
            assertTrue(
                "$name: the sampled frames must be distinct frames, not one aliased buffer " +
                    "(${handPositions.size} distinct of ${phases.size})",
                handPositions.size >= phases.size - 1
            )

            // …and the reach-band property must be measured across a rep that actually moves.
            val effectorYs = playing.filter { it.chain == Joint.HAND_A }.map { it.effectorY }
            assertTrue(
                "$name: the arm effector must travel through the rep (spread " +
                    "${"%.4f".format(effectorYs.max() - effectorYs.min())})",
                effectorYs.max() - effectorYs.min() > 40f
            )
        }
    }

    @Test
    fun theSupportAndGroundBehaviourIsUntouched() {
        for (r in allReadings()) {
            assertEquals(
                "${where(r)}: the declared support model must be unchanged by a reach-authoring fix",
                "LEFT_FOOT,RIGHT_FOOT", r.support
            )
            assertEquals("${where(r)}: the declared ground level must be unchanged", 0f, r.groundLevel, 0f)
            assertTrue(
                "${where(r)}: no joint may be published below the declared ground level " +
                    "(min joint y = ${"%.4f".format(r.minJointY)})",
                r.minJointY >= r.groundLevel
            )
        }
    }

    /**
     * The realized middle joint may sit at the chain's own flexion stop (these depths and reaches are
     * at the model's limit by construction) — but the effector must still be exactly the declared
     * target. The realized interior angle is reported in the failure message so the limit is explicit
     * rather than implied.
     */
    @Test
    fun theRealizedChainNeverBreachesTheFlexionStop() {
        val bad = mutableListOf<String>()
        for ((name, factory) in batch()) {
            val builder = factory()
            val pipeline = SkeletonPipeline(def)
            for (k in 0..10) pipeline.produceFrame(builder, context(0.3f))
            for (p in phases) {
                val published = pipeline.produceFrame(builder, context(p)).pose
                for ((end, rootJoint, midJoint) in chains) {
                    val a = published.getJoint(rootJoint)
                    val b = published.getJoint(midJoint)
                    val c = published.getJoint(end)
                    val angle = interiorAngleDegrees(a, b, c)
                    val limit = when (end) {
                        Joint.ANKLE_F, Joint.ANKLE_B -> def.legAngularLimits.minFlexionDegrees
                        else -> def.armAngularLimits.minFlexionDegrees
                    }
                    if (angle < limit - 0.05f) {
                        bad += "$name/${end.name} @ p=$p: realized ${"%.3f".format(angle)}° " +
                            "folds past the model's ${"%.0f".format(limit)}° stop"
                    }
                }
            }
        }
        assertTrue(
            "no realized chain may fold past its own flexion stop:\n" + bad.joinToString("\n"),
            bad.isEmpty()
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
}
