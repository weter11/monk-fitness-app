package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.*
import org.junit.Test

/**
 * The thoracic-extension arm target is authored from geometry the POSE owns.
 *
 * The defect: `ThoracicExtensionPose` derived both arm targets from `neck!!.worldPosition`
 * (`target = neckW + (-12, +6, ±shoulderWidth·0.55)`). The neck's local offsets are written by the
 * engine — `SkeletonPoseFinalizer.resolveHeadTarget` is the sole writer — and that write lands at the
 * END of a frame, so the pose can only ever read the PREVIOUS frame's neck. On a builder's FIRST
 * build the template still carries a zero neck offset, so the cold frame anchored its arms to the
 * CHEST and realized a target the pose never sees again. Measured, cold first frame vs the same
 * instance once settled (progress 0): declared target `(-12.000000, 253.000000, ∓25.300000)` vs
 * `(-14.144614, 270.871796, ∓25.300000)` = delta 17.8718u; published `ELBOW_A`/`HAND_A` delta
 * 29.9277 / 17.9135u; published `maxIkClampAmount` 15.4668 on the cold frame against 5.5162 in the
 * rep. Steady state only looked right because the reused node tree carried the engine's previous
 * write — correctness by cross-build buffer reuse, not by the frame.
 *
 * The fix (pose-side only; no engine file, no phase order, no head/neck ownership change): the engine
 * places the neck along the gaze the pose declares (`buildGaze`, the pose's own `headDir` and
 * `def.neckLength`) inside the declared chest frame, so the same point is expressible from authored
 * intent — `headDir · def.neckLength` rotated to world by the declared chest frame through the
 * family's existing `BaseThoracicPose.chestLocalToWorld`. The authored hand offset is untouched, so
 * every settled frame is byte-identical (whole-corpus dump: 490 rows, the other 48 registry poses
 * byte-identical at 1e-6, `thoracic_extension_reps` byte-identical on every settled frame — only its
 * 5 COLD frames change).
 *
 * Asserted: the declared target in the published `limbTargets` carrier is frame-invariant (cold first
 * frame == frame 1 == the settled rep at every progress); the published arm chain is frame-invariant
 * at the sibling cold-frame suite's tolerance; the authored base sits ON the `NECK_END` the engine
 * realizes, not on an arbitrary chest offset and not on the stale node read; the authored target
 * provably differs from the old expression (recomputed from the pose's own build, > 5u); the pose
 * source may not read the neck node's world position (source scan); and the carriers stay honest —
 * `limbTargets` reports the authored target with its realization context, `boneLengthsVerified` stays
 * true, and no NEW clamp appears.
 *
 * Tolerance: [TARGET_TOLERANCE] = 1e-4u for declared-value identity — the projection is the same
 * expression from the same declared chest frame, measured 0.000000; the margin is float
 * re-projection (the engine's `dir` is normalized from `gazeDir·100`, the pose's from `gazeDir`), and
 * it is still 4 orders of magnitude below the pre-fix defect. [CHAIN_TOLERANCE] = 0.25u is exactly
 * `ColdFrameLimbRealizationTest`'s cold-frame limb tolerance — not a new one.
 *
 * Residual: this pose authored its hands INSIDE the arm's minimum-reach annulus (the minimum flexion
 * of 30° fixes the closest reachable end-effector distance at 40.134u, while the authored target sits
 * 34.618u from the shoulder at p=0), so the solver honestly reported `maxIkClampAmount` 5.5162 and
 * placed the hand on the target's own ray. The declaration is now projected onto the annulus along
 * that ray (`projectArmTargetToReach`, `REACH_MARGIN = 1e-4`): the realized hand is unchanged
 * (`≤ 0.0038` u), the declaration IS the published position and the clamp reads `0`. The clamp values
 * below stay as an upper bound so a future change cannot hide a WORSE clamp behind this record.
 */
class ThoracicExtensionArmTargetTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val name = "ThoracicExtensionPose"

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    /** Every progress sample of the rep (the pose's own choreography driver). */
    private val progresses = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    /** Both arm chains: shoulder → end-effector, including the engine-derived hand chain. */
    private val armChain = listOf(
        Joint.SHOULDER_A, Joint.SHOULDER_P, Joint.ELBOW_A, Joint.ELBOW_P,
        Joint.HAND_A, Joint.HAND_P, Joint.WRIST_A, Joint.WRIST_P,
        Joint.PALM_A, Joint.PALM_P, Joint.KNUCKLES_A, Joint.KNUCKLES_P,
        Joint.FINGERTIPS_A, Joint.FINGERTIPS_P
    )

    /** Declared-value identity (see the class KDoc). */
    private val TARGET_TOLERANCE = 1e-4f

    /** `ColdFrameLimbRealizationTest.COLD_TOLERANCE` — the sibling suite's limb tolerance. */
    private val CHAIN_TOLERANCE = 0.25f

    /**
     * Published `maxIkClampAmount` of the settled rep per progress, measured PRE-FIX (unchanged by
     * this fix): the honest min-flexion residual of the authored placement. The fix may not exceed
     * these.
     */
    private val preFixSteadyStateClamp = mapOf(
        0f to 5.516174f, 0.25f to 4.573788f, 0.5f to 3.795033f, 0.75f to 3.192696f, 1f to 2.776337f
    )

    /** By-value snapshot: `produceFrame(...).pose` is the Finalizer's reused output buffer. */
    private class Frame(
        val world: Map<Joint, Vector3>,
        val targets: Map<Joint, Vector3>,
        val clamp: Float,
        val bonesVerified: Boolean,
        val declaredContext: Map<Joint, Triple<Float, Float, IKConstraint?>>
    )

    private fun snapshot(pose: SkeletonPose) = Frame(
        world = Joint.entries.associateWith { pose.getJoint(it).copy() },
        targets = pose.limbTargets.associate { it.joint to it.world.copy() },
        clamp = pose.maxIkClampAmount,
        bonesVerified = pose.boneLengthsVerified,
        declaredContext = pose.limbTargets.associate {
            it.joint to Triple(it.length1, it.length2, it.constraint)
        }
    )

    /** FRESH pose instance + FRESH pipeline; index 0 is genuinely cold (no warm-up anywhere). */
    private fun frames(progress: Float, count: Int): List<Frame> {
        val pose = MotionProbe.build(name)
        val pipeline = SkeletonPipeline(def)
        val c = ctx(progress)
        val out = ArrayList<Frame>(count)
        for (i in 0 until count) out.add(snapshot(pipeline.produceFrame(pose, c).pose))
        return out
    }

    /** The pose's own authoring with NO pipeline call at all (a fresh instance's first build). */
    private fun authoredBuild(progress: Float): SkeletonPose = MotionProbe.build(name).build(ctx(progress))

    private fun dist(a: Vector3, b: Vector3): Float =
        max(abs(a.x - b.x), max(abs(a.y - b.y), abs(a.z - b.z)))

    private fun maxDelta(a: Map<Joint, Vector3>, b: Map<Joint, Vector3>, joints: List<Joint>): Float =
        joints.maxOf { dist(a[it]!!, b[it]!!) }

    private fun xyz(v: Vector3) = "(${f(v.x)},${f(v.y)},${f(v.z)})"

    @Test
    fun declaredArmTargetIsFrameInvariant() {
        for (p in progresses) {
            val seq = frames(p, 161)
            val cold = seq[0].targets
            val settled = seq[160].targets
            for (j in listOf(Joint.HAND_A, Joint.HAND_P)) {
                val c = cold[j]
                val s = settled[j]
                assertNotNull("$name p=$p: the limb target carrier must declare $j", c)
                assertNotNull("$name p=$p: the limb target carrier must declare $j", s)
                assertEquals(
                    "$name p=$p/$j: the declared arm target must be frame-invariant — cold " +
                        "${xyz(c!!)} vs settled ${xyz(s!!)}, delta=${f(dist(c, s))}. The pre-fix " +
                        "implementation read `neck.worldPosition`, which the engine rewrites AFTER " +
                        "the limb realization (`resolveHeadTarget`, Phase 7), so the cold frame " +
                        "authored a different target (17.87u at p=0 measured on origin/main).",
                    0f, dist(c, s), TARGET_TOLERANCE
                )
            }
            // Frame 1 must already be the settled solution: no multi-frame convergence.
            for (j in listOf(Joint.HAND_A, Joint.HAND_P)) {
                assertEquals(
                    "$name p=$p/$j: frame 1 must already carry the settled declared target",
                    0f, dist(seq[1].targets.getValue(j), settled.getValue(j)), TARGET_TOLERANCE
                )
            }
        }
    }

    @Test
    fun coldFrameRealizesTheSameArmChainAsTheRep() {
        for (p in progresses) {
            val seq = frames(p, 161)
            val cold = seq[0].world
            val settled = seq[160].world
            val delta = maxDelta(cold, settled, armChain)
            assertTrue(
                "$name p=$p: cold first frame vs settled rep arm-chain delta = ${f(delta)} " +
                    "(pre-fix 29.9277 at ELBOW_A / 17.9135 at HAND_A, p=0). The realized chain must " +
                    "be a function of the pose's authored geometry, not of which frame index it is.",
                delta < CHAIN_TOLERANCE
            )
            val frameOne = maxDelta(seq[1].world, settled, armChain)
            assertTrue(
                "$name p=$p: frame 1 must already equal the settled rep (delta=${f(frameOne)})",
                frameOne < CHAIN_TOLERANCE
            )
        }
    }

    /**
     * Both arm targets are anchored to the head base the ENGINE resolves (`NECK_END`) — not to an
     * arbitrary chest offset, and not to the stale node read.
     *
     * The declared target was then projected ALONG its own authored ray onto the arm chain's
     * annulus: this pose authors a hands-behind-the-head clasp `34.6182 … 37.3581` u from its own
     * shoulder against the chain's `40.1344` minimum-flexion reach, so the solver had been relocating
     * the declaration `5.5162 … 2.7763` u. The anchor pinned here is therefore the RAY:
     * `declared − shoulder` must stay parallel to `authored − shoulder`, where `authored` is the
     * engine-resolved neck base + the pose's own `(−12, +6, ±0.55 · shoulderWidth)`.
     */
    @Test
    fun authoredBaseSitsOnTheEngineResolvedHeadBase() {
        for (p in progresses) {
            val seq = frames(p, 161)
            val cold = seq[0]
            val settled = seq[160]
            for ((label, fr) in listOf("cold first frame" to cold, "settled rep" to settled)) {
                val neck = fr.world.getValue(Joint.NECK_END)
                for ((j, shoulderJoint, sign) in listOf(
                    Triple(Joint.HAND_A, Joint.SHOULDER_A, -1f),
                    Triple(Joint.HAND_P, Joint.SHOULDER_P, 1f)
                )) {
                    val t = fr.targets.getValue(j)
                    val authored = Vector3(neck.x - 12f, neck.y + 6f, sign * def.shoulderWidth * 0.55f)
                    val root = fr.world.getValue(shoulderJoint)
                    val cos = rayCos(root, t, authored)
                    assertTrue(
                        "$name p=$p/$j ($label): the authored base (the engine-resolved neck base + " +
                            "(−12, +6, ±0.55·w)) must stay the declared target's own ray — cos=${f(cos)}, " +
                            "declared=${xyz(t)} authored=${xyz(authored)} neck=${xyz(neck)}. Pre-fix the " +
                            "cold frame anchored to the chest (2.14u off at p=0); the reach projection " +
                            "moves the radius, never this ray.",
                        cos >= 1f - 1e-5f
                    )
                }
            }
        }
    }

    /** cos of the angle between `declared − root` and `authored − root`. */
    private fun rayCos(root: Vector3, declared: Vector3, authored: Vector3): Float {
        val a = Vector3(declared.x - root.x, declared.y - root.y, declared.z - root.z)
        val b = Vector3(authored.x - root.x, authored.y - root.y, authored.z - root.z)
        val am = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        val bm = kotlin.math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        if (am < 1e-6f || bm < 1e-6f) return 0f
        return ((a.x * b.x + a.y * b.y + a.z * b.z) / (am * bm)).coerceIn(-1f, 1f)
    }

    @Test
    fun authoredTargetIsNotAnchoredToTheEngineMutatedNeckNode() {
        for (p in progresses) {
            val built = authoredBuild(p)
            // The value the PRE-FIX code read: the neck node's world position, on a build that
            // never ran the engine's resolver (fresh instance ⇒ template zero neck offset).
            val neck = built.getJoint(Joint.NECK_END).copy()
            val oldExprA = Vector3(neck.x - 12f, neck.y + 6f, -def.shoulderWidth * 0.55f)
            val oldExprP = Vector3(neck.x - 12f, neck.y + 6f, def.shoulderWidth * 0.55f)
            val targets = built.limbTargets.associate { it.joint to it.world.copy() }
            for ((j, old) in listOf(Joint.HAND_A to oldExprA, Joint.HAND_P to oldExprP)) {
                val t = targets.getValue(j)
                assertTrue(
                    "$name p=$p/$j: the authored target ${xyz(t)} must NOT be the pre-fix " +
                        "engine-neck expression ${xyz(old)} — if it is, the arm target is being " +
                        "read from the engine-owned neck node again (B-8b regression).",
                    dist(t, old) > 5f
                )
            }
        }
    }

    @Test
    fun poseDoesNotReadTheEngineOwnedNeckNodePosition() {
        var dir = File(System.getProperty("user.dir"))
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate app module root from ${System.getProperty("user.dir")}")
        val source = File(root, "src/main/java/com/monkfitness/app/poses/$name.kt")
        assertTrue("$name.kt must exist at ${source.path}", source.isFile)
        val offenders = source.readLines().withIndex().filter { (_, line) ->
            val code = line.substringBefore("//")
            code.contains("neck") && code.contains("worldPosition")
        }
        assertTrue(
            "$name must not read the engine-owned neck node's world position — the engine writes " +
                "the neck's local offsets in Phase 7 (`resolveHeadTarget`), after the limb " +
                "realization, so any such read makes the arm target frame-dependent (B-8b). " +
                "Offending line(s): " + offenders.joinToString("; ") { "${it.index + 1}: ${it.value.trim()}" },
            offenders.isEmpty()
        )
    }

    @Test
    fun publishedCarriersStayHonestAndNoNewClampAppears() {
        for (p in progresses) {
            val seq = frames(p, 161)
            for ((label, fr) in listOf("cold first frame" to seq[0], "settled rep" to seq[160])) {
                assertEquals(
                    "$name p=$p ($label): both arms must be declared in the limb target carrier",
                    2, fr.targets.size
                )
                for (j in listOf(Joint.HAND_A, Joint.HAND_P)) {
                    val (l1, l2, constraint) = fr.declaredContext.getValue(j)
                    assertEquals("$name p=$p/$j ($label): declared length1", def.upperArmLength, l1, 0f)
                    assertEquals("$name p=$p/$j ($label): declared length2", def.forearmLength, l2, 0f)
                    assertEquals(
                        "$name p=$p/$j ($label): declared IK constraint",
                        def.armIKConstraint, constraint
                    )
                }
                assertTrue(
                    "$name p=$p ($label): boneLengthsVerified must stay true (the realized chain " +
                        "must preserve both authored bone lengths)",
                    fr.bonesVerified
                )
            }
            val coldClamp = seq[0].clamp
            val settledClamp = seq[160].clamp
            assertEquals(
                "$name p=$p: the cold frame's clamp must be the rep's clamp (pre-fix the cold frame " +
                    "reported 15.4668 against 5.5162 because it realized a different target)",
                0f, coldClamp - settledClamp, TARGET_TOLERANCE
            )
            assertTrue(
                "$name p=$p: the fix must not create a new unreachable arm target or IK clamp — the " +
                    "rep's clamp ${f(settledClamp)} must not exceed the pre-fix steady-state value " +
                    "${f(preFixSteadyStateClamp.getValue(p))}",
                settledClamp <= preFixSteadyStateClamp.getValue(p) + 1e-3f
            )
        }
    }
}
