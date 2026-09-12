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
         */
        const val UNAFFECTED_CORPUS_DIGEST = 6921547823364851041L
    }
}

private fun distance(a: Vector3, b: Vector3) =
    sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2))

private fun copy(v: Vector3) = Vector3().set(v)
