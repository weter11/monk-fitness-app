package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.*
import org.junit.Test

/**
 * B-8 — cold-frame limb realization.
 *
 * The defect: a pose that leaves its trunk (chest) frame UNAUTHORED realized its limbs in the
 * pelvis-only frame on a builder's first build, because the engine's trunk frame for a non-upright
 * trunk was established by `SkeletonPoseFinalizer.reconstructChestFrame` — a Phase-3, Finalization-
 * phase operation (ARCHITECTURE_V2 §3 PHASE 3, §2.4, INVARIANT 4) — i.e. AFTER the Phase-1 limb
 * realization had already baked the arm chain. The fallback then re-FK'd the chest subtree and
 * dragged the realized arms with it. Measured on the pre-fix tree at progress 0, cold first frame
 * vs the same pose once settled: `ELBOW_A`/`HAND_A` deltas 74.31/160.33 (`pushup_standard`),
 * 79.50/157.05 (`wide`), 92.91/155.33 (`military`), 89.80/157.05 (`diamond`), 82.01/108.22
 * (`knee`), 92.21/145.00 (`pike`), 62.22/28.00 (`side_plank`); the standard plank's hands sat
 * 108.84 units ABOVE the floor. Frames >= 1 only looked correct because the fallback's node write
 * survived in the reused builder buffer and was read back as authored intent — so steady-state
 * correctness was a property of cross-build buffer reuse, not of the frame.
 *
 * The fix (production side): the trunk frame is POSE-owned Phase-0 intent (ARCHITECTURE_V2 §4.1
 * "Chest/hip/girdle/ankle/wrist intent | Pose (relative)"), so the affected families declare it
 * through the single existing path (the chest node write plus its §1.1 joint carrier — the form
 * every other authored articulation uses): `BasePushUpPose.declareFlatPlankTrunkFrame` (both pivot
 * branches + `PikePushUpPose`, which authors its own `onBuild`) and `IsometricSidePlankPose`
 * (whose girdle roll is the layout statement the frame must follow). The Finalizer's fallback is
 * untouched and its trigger condition (an identity chest) is simply no longer met for these poses.
 *
 * What these tests prove, and how:
 *  1. The trunk frame is authoritative BEFORE the limb realization — asserted on a bare
 *     `build()` result (no pipeline call at all): the frame is non-identity, and it is bit-equal to
 *     the frame the published first frame and the settled frame carry, i.e. the Phase-3 fallback
 *     did not write it.
 *  2. The genuinely cold first frame (first `produceFrame` call of a FRESH pose instance on a
 *     FRESH pipeline — no warm-up step anywhere in this file) agrees with the same pose once
 *     settled at the same progress, and lands its hands on the world targets the pose declared.
 *  3. Later frames stay stable (frame 1 == frame 20 == frame 150).
 *  4. Poses that already author their trunk frame are untouched (the graded control), and poses
 *     whose layout makes the engine's derivation a no-op are not rewritten either.
 *  5. The one family the task's matrix lists whose residual is NOT a trunk-frame defect
 *     (`thoracic_extension_reps`) is pinned with its measured attribution to the open item B-8b,
 *     so the residual cannot be silently mis-attributed to B-8 or hidden by a warm-up.
 *
 * Tolerance: the fix makes the cold frame and the settled frame agree EXACTLY (0.000000 measured
 * for every joint of every family below). [COLD_TOLERANCE] is set at 0.25 units — 1/60 of the
 * validator's 15-unit `POSITION_DISCONTINUITY` threshold and 1/112 of the SMALLEST pre-fix delta in
 * the fixed set (28.00, `side_plank_standard` HAND_A) — so this suite cannot pass on the pre-fix
 * implementation (see the PR: RED 3 failed / 4 passed pre-fix — the three cold-frame assertions
 * fail with the pre-fix numbers quoted above — GREEN 7/7 post-fix).
 */
class ColdFrameLimbRealizationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** Max cold-vs-settled deviation accepted for the limb chain (see the class KDoc). */
    private val COLD_TOLERANCE = 0.25f

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    /** Every joint the Phase-1 limb realization writes (both arms, whole end-effector chain). */
    private val limbChain = listOf(
        Joint.SHOULDER_A, Joint.SHOULDER_P, Joint.ELBOW_A, Joint.ELBOW_P,
        Joint.HAND_A, Joint.HAND_P, Joint.WRIST_A, Joint.WRIST_P,
        Joint.PALM_A, Joint.PALM_P, Joint.KNUCKLES_A, Joint.KNUCKLES_P,
        Joint.FINGERTIPS_A, Joint.FINGERTIPS_P
    )

    /** The B-8 targets: trunks laid along the pelvis's local -X (flat plank) or rolled laterally. */
    private val flatPlankFamily = listOf(
        "StandardPushUpPose", "WidePushUpPose", "MilitaryPushUpPose", "DiamondPushUpPose",
        "KneePushUpPose", "PikePushUpPose"
    )
    private val b8Targets = flatPlankFamily + listOf("IsometricSidePlankPose")

    /** Pre-fix cold-vs-settled ELBOW_A/HAND_A (measured on origin/main) — quoted in failures. */
    private val preFixDeltas = mapOf(
        "StandardPushUpPose" to (74.31f to 160.33f),
        "WidePushUpPose" to (79.50f to 157.05f),
        "MilitaryPushUpPose" to (92.91f to 155.33f),
        "DiamondPushUpPose" to (89.80f to 157.05f),
        "KneePushUpPose" to (82.01f to 108.22f),
        "PikePushUpPose" to (92.21f to 145.00f),
        "IsometricSidePlankPose" to (62.22f to 28.00f)
    )

    // ---- harness -----------------------------------------------------------------------------

    private class Frame(
        val world: Map<Joint, Vector3>,
        val chestLocal: JointRotation,
        val chestWorld: JointRotation
    )

    private fun node(n: SkeletonNode, joint: Joint): SkeletonNode? {
        if (n.joint == joint) return n
        for (c in n.children) node(c, joint)?.let { return it }
        return null
    }

    private fun chestNode(pose: SkeletonPose): SkeletonNode? =
        pose.roots.firstOrNull()?.let { node(it, Joint.CHEST) }

    /** Snapshot BY VALUE: `produceFrame` publishes a reused buffer and the node tree is reused too. */
    private fun snapshot(pose: SkeletonPose): Frame {
        val m = HashMap<Joint, Vector3>()
        for (j in Joint.entries) m[j] = pose.getJoint(j).copy()
        val chest = chestNode(pose)
        val cl = JointRotation()
        val cw = JointRotation()
        if (chest != null) {
            cl.copyFrom(chest.localRotation)
            cw.copyFrom(chest.worldRotation)
        }
        return Frame(m, cl, cw)
    }

    /**
     * A genuinely cold sequence: FRESH pose instance, FRESH pipeline, and snapshot #0 is the very
     * first `produceFrame` call. No warm-up step is performed anywhere — the cold frame under test
     * is the pipeline's first published frame.
     */
    private fun frames(simpleName: String, progress: Float, count: Int): List<Frame> {
        val pose = MotionProbe.build(simpleName)
        val pipeline = SkeletonPipeline(def)
        val ctx = ctx(progress)
        val out = ArrayList<Frame>(count)
        for (i in 0 until count) out.add(snapshot(pipeline.produceFrame(pose, ctx).pose))
        return out
    }

    /** The world limb targets the pose DECLARES (read from a separate fresh instance's build). */
    private fun declaredTargets(simpleName: String, progress: Float): Map<Joint, Vector3> {
        val built = MotionProbe.build(simpleName).build(ctx(progress))
        return built.limbTargets.associate { it.joint to it.world.copy() }
    }

    private fun maxDelta(a: Map<Joint, Vector3>, b: Map<Joint, Vector3>, joints: List<Joint>): Float =
        joints.maxOf { j ->
            val x = a[j]!!; val y = b[j]!!
            max(abs(x.x - y.x), max(abs(x.y - y.y), abs(x.z - y.z)))
        }

    private fun dist(a: Vector3, b: Vector3): Float =
        max(abs(a.x - b.x), max(abs(a.y - b.y), abs(a.z - b.z)))

    private fun rotationDelta(a: JointRotation, b: JointRotation): Float =
        max(abs(a.angle - b.angle), max(abs(a.axis.x - b.axis.x), max(abs(a.axis.y - b.axis.y), abs(a.axis.z - b.axis.z))))

    // ---- 1. the frame is authoritative before the limb realization ---------------------------

    @Test
    fun trunkFrameIsAuthoritativeBeforeTheLimbRealization() {
        for (name in b8Targets) {
            val built = MotionProbe.build(name).build(ctx(0f))
            val chest = chestNode(built)
            assertNotNull("$name: build() must publish a CHEST node", chest)
            // Copied out of the node: the node tree is reused across builds.
            val authored = JointRotation().also { it.copyFrom(chest!!.localRotation) }

            // (a) The frame is authored in Phase 0: non-identity at build time, i.e. the Phase-3
            // fallback's trigger condition (`chest.localRotation.angle == 0`) is no longer met.
            assertTrue(
                "$name: the trunk frame must be declared during build (angle=${f(authored.angle)}); " +
                    "an identity chest here is exactly the pre-fix B-8 state in which the Phase-3 " +
                    "fallback `reconstructChestFrame` supplies the frame AFTER limb realization",
                abs(authored.angle) > 1.0f
            )

            // (b) The declaration runs through the SINGLE existing path: the chest §1.1 carrier.
            val chestIntents = built.jointIntents.filter { it.joint == Joint.CHEST }
            assertEquals("$name: exactly one CHEST intent must be declared", 1, chestIntents.size)
            assertEquals(
                "$name: the declared CHEST intent must be the frame the node carries",
                0f, rotationDelta(chestIntents[0].rotation, authored), 1e-6f
            )

            // (c) The frame the limb realization sees IS the frame the pose publishes: the Phase-3
            // fallback did not rewrite it, on the cold frame or later.
            val seq = frames(name, 0f, 151)
            for ((label, frame) in listOf("cold first frame" to seq[0], "settled frame" to seq[150])) {
                assertEquals(
                    "$name: the published $label trunk frame must be the Phase-0 declaration " +
                        "(a difference means the Finalizer's fallback wrote the frame after the " +
                        "limb realization — the B-8 mechanism)",
                    0f, rotationDelta(authored, frame.chestLocal), 1e-6f
                )
            }
        }
    }

    // ---- 2. the cold first frame agrees with the settled frame --------------------------------

    @Test
    fun coldFirstFrameRealizesTheLimbChainInTheSameFrameAsTheSettledRep() {
        for (name in b8Targets) {
            val seq = frames(name, 0f, 151)
            val cold = seq[0]
            val settled = seq[150]
            val pre = preFixDeltas.getValue(name)

            val delta = maxDelta(cold.world, settled.world, limbChain)
            assertTrue(
                "$name: cold first frame vs settled frame limb-chain delta = ${f(delta)} " +
                    "(pre-fix ${f(pre.second)}); the realized chain must be a function of the " +
                    "authoritative trunk frame, not of which frame index it happens to be",
                delta < COLD_TOLERANCE
            )

            // The trunk frame itself must be frame-invariant (same statement, cold vs settled).
            assertEquals(
                "$name: the trunk frame must not move between the cold frame and the settled rep",
                0f, rotationDelta(cold.chestLocal, settled.chestLocal), 1e-6f
            )
            assertEquals(
                "$name: the trunk WORLD frame must not move between the cold frame and the settled rep",
                0f, rotationDelta(cold.chestWorld, settled.chestWorld), 1e-6f
            )
        }
    }

    @Test
    fun coldFirstFrameHandsLandOnTheTargetsThePoseDeclares() {
        for (name in b8Targets) {
            val targets = declaredTargets(name, 0f)
            val seq = frames(name, 0f, 151)
            for (j in listOf(Joint.HAND_A, Joint.HAND_P)) {
                val t = targets[j] ?: continue
                val coldMiss = dist(seq[0].world[j]!!, t)
                val settledMiss = dist(seq[150].world[j]!!, t)
                assertTrue(
                    "$name/$j: cold first frame must land on the declared target " +
                        "(miss=${f(coldMiss)}, settled miss=${f(settledMiss)}, target=(${f(t.x)},${f(t.y)},${f(t.z)}))",
                    coldMiss < COLD_TOLERANCE
                )
                assertTrue(
                    "$name/$j: settled frame must land on the declared target (miss=${f(settledMiss)})",
                    settledMiss < COLD_TOLERANCE
                )
            }
        }
    }

    // ---- 3. later frames stay stable ---------------------------------------------------------

    @Test
    fun laterFramesRemainStable() {
        for (name in b8Targets) {
            val seq = frames(name, 0f, 21)
            val frame1 = seq[1]
            for (i in 2 until seq.size) {
                val d = maxDelta(seq[i].world, frame1.world, limbChain)
                assertTrue(
                    "$name: frame $i must stay on the frame-1 solution (delta=${f(d)}); " +
                        "the cold frame is corrected by establishing the frame up front, not by a " +
                        "multi-frame convergence the test could hide behind a warm-up",
                    d < 1e-3f
                )
            }
            val settled = frames(name, 0f, 151)[150]
            assertTrue(
                "$name: frame 1 must already equal the settled rep (delta=${f(maxDelta(frame1.world, settled.world, limbChain))})",
                maxDelta(frame1.world, settled.world, limbChain) < 1e-3f
            )
        }
    }

    // ---- 4. controls: authored / already-correct trunk frames stay untouched -----------------

    @Test
    fun authoredTrunkFrameControlIsUnchanged() {
        // DeclinePushUpPose authors its own trunk pitch (`declineTrunkPitch`): a non-identity chest
        // is authored intent, the engine never reconstructs it, and it is the graded control the
        // B-8 finding used (pre-fix cold-frame delta exactly 0.00/0.00).
        val name = "DeclinePushUpPose"
        val pose = MotionProbe.build(name)
        val built = pose.build(ctx(0f))
        val chest = JointRotation().also { it.copyFrom(chestNode(built)!!.localRotation) }
        assertTrue(
            "$name: the member's own trunk pitch must be kept (angle=${f(chest.angle)})",
            abs(chest.angle) > 1e-4f && abs(chest.angle - PI.toFloat() / 2f) > 0.5f
        )
        val intents = built.jointIntents.filter { it.joint == Joint.CHEST }
        assertEquals("$name: the member's own CHEST intent must be kept (and not doubled)", 1, intents.size)
        assertEquals("$name: the kept CHEST intent must be the member's own pitch", 0f, rotationDelta(intents[0].rotation, chest), 1e-6f)

        val seq = frames(name, 0f, 151)
        val delta = maxDelta(seq[0].world, seq[150].world, limbChain)
        assertTrue("$name: cold first frame must already equal the settled rep (delta=${f(delta)})", delta < COLD_TOLERANCE)
    }

    @Test
    fun trunkFramesTheEngineDerivesAsIdentityAreNotRewritten() {
        // ThoracicExtension lays its trunk along the parent's +Y and StaticForearmPlank carries an
        // authored pelvis roll with no girdle roll: for both layouts the Finalizer's derivation
        // computes an identity chest frame, so the pose publishing an identity chest IS the
        // authoritative frame. B-8's fix must not rewrite those (that would change established
        // geometry), and their limb chains must already be cold-frame consistent.
        for (name in listOf("StaticForearmPlankPose")) {
            val built = MotionProbe.build(name).build(ctx(0f))
            val chest = chestNode(built)!!.localRotation
            assertTrue(
                "$name: this layout's trunk frame is identity by construction (angle=${f(chest.angle)}); " +
                    "it must not be rewritten by the flat-plank declaration",
                abs(chest.angle) < 1e-4f
            )
            val seq = frames(name, 0f, 151)
            val delta = maxDelta(seq[0].world, seq[150].world, limbChain)
            assertTrue("$name: cold first frame must equal the settled rep (delta=${f(delta)})", delta < COLD_TOLERANCE)
        }
    }

    // ---- 5. the one matrix family whose residual is a DIFFERENT defect ------------------------

    @Test
    fun thoracicExtensionResidualIsAttributedToItsArmTargetNotItsTrunkFrame() {
        // Task matrix note (measured, recorded, NOT fixed here): `thoracic_extension_reps` is not a
        // B-8 victim. Its trunk frame is already authoritative (identity — see the control above),
        // so B-8's mechanism cannot explain its cold/settled arm difference. The measured cause is
        // the pose's own Phase-0 authoring: it derives both arm targets from
        // `neck!!.worldPosition`, and the neck's local offsets are written by the ENGINE
        // (`SkeletonPoseFinalizer.resolveHeadTarget` is the sole writer, Phase 7) — so on the first
        // build the pose reads an un-established neck (zero local offset) and realizes its arms
        // against a target it will never see again. Recorded as the open item B-8b (see the audit
        // doc); fixing it changes that pose's authored reach choreography and is a separate change.
        val name = "ThoracicExtensionPose"
        val seq = frames(name, 0f, 151)
        val cold = seq[0]
        val settled = seq[150]

        assertEquals(
            "$name: trunk frame must be frame-invariant (identity on both frames) — B-8 does not apply here",
            0f, rotationDelta(cold.chestLocal, settled.chestLocal), 1e-6f
        )
        assertEquals(
            "$name: trunk WORLD frame must be frame-invariant — the residual is not a trunk-frame effect",
            0f, rotationDelta(cold.chestWorld, settled.chestWorld), 1e-6f
        )

        val coldTargets = declaredTargets(name, 0f)
        val settledTargets = settledBuildTargets(name)
        val targetDelta = dist(coldTargets.getValue(Joint.HAND_A), settledTargets.getValue(Joint.HAND_A))
        val handDelta = maxDelta(cold.world, settled.world, listOf(Joint.HAND_A))
        assertTrue(
            "$name: B-8b is still open — the declared arm target itself is not frame-invariant " +
                "(delta=${f(targetDelta)}): it is derived from the engine-owned neck node. If this " +
                "assertion fails because the target no longer moves, B-8b has been fixed — move this " +
                "family into the cold-frame-consistency set above.",
            targetDelta > 10f
        )
        assertTrue(
            "$name: the realized hand moves with that target (hand delta=${f(handDelta)} >= target delta=${f(targetDelta)})",
            handDelta > 10f
        )
    }

    /**
     * The declared arm target of the SETTLED frame: emitted by the same instance that produced the
     * settled frame (the target is a property of the build, not of the pipeline).
     */
    private fun settledBuildTargets(simpleName: String): Map<Joint, Vector3> {
        val pose = MotionProbe.build(simpleName)
        val pipeline = SkeletonPipeline(def)
        val ctx = ctx(0f)
        for (i in 0..150) pipeline.produceFrame(pose, ctx)
        // One more build on the same (now warm) instance reproduces the settled frame's authoring.
        val built = pose.build(ctx)
        return built.limbTargets.associate { it.joint to it.world.copy() }
    }
}
