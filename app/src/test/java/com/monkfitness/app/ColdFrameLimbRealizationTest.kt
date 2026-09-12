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
 *     whose layout makes the engine's derivation a no-op are not rewritten either —
 *     `ThoracicExtensionPose` joined that set once B-8b (below) fixed its arm-target source.
 *  5. The one family the task's matrix lists whose residual was NOT a trunk-frame defect
 *     (`thoracic_extension_reps`) was pinned here with its measured attribution to the open item
 *     B-8b. **B-8b is fixed** (the pose now derives its arm target from geometry it owns instead of
 *     the engine-owned neck node — `resolveHeadTarget`, Phase 7), so the pin is REMOVED and the
 *     family is asserted as a cold-frame-consistent control like the rest. The focused B-8b
 *     regression is `ThoracicExtensionArmTargetTest`, which goes RED if the target source regresses.
 *
 * Tolerance: the fix makes the cold frame and the settled frame agree EXACTLY (0.000000 measured
 * for every joint of every family below). [COLD_TOLERANCE] is set at 0.25 units — 1/60 of the
 * validator's 15-unit `POSITION_DISCONTINUITY` threshold and 1/112 of the SMALLEST pre-fix delta in
 * the fixed set (28.00, `side_plank_standard` HAND_A) — so this suite cannot pass on the pre-B-8
 * implementation (see the PR: RED 3 failed / 4 passed pre-B-8, the three cold-frame assertions
 * failing with the numbers quoted above — GREEN 7/7 post-B-8).
 *
 * B-8b then added `ThoracicExtensionPose` to the identity-trunk control, because that family is
 * cold-frame consistent only once its arm target is pose-owned: on the B-8b baseline
 * (`origin/main` @ `691c6a7`, B-8 landed, B-8b open) this file is **RED 1 failed / 5 passed** —
 * exactly `trunkFramesTheEngineDerivesAsIdentityAreNotRewritten`, with the arm-chain delta the
 * B-8b numbers below quote — and **GREEN 6/6 post-B-8b**. The removal of the old attribution pin
 * removed one test; no assertion was weakened.
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
        //
        // `ThoracicExtensionPose` was held OUT of this set while B-8b was open (its arm chain was
        // NOT cold-frame consistent, for a reason that had nothing to do with the trunk frame); it
        // now belongs here — B-8b fixed the arm TARGET source, not the frame, and the focused
        // regression is `ThoracicExtensionArmTargetTest`.
        for (name in listOf("StaticForearmPlankPose", "ThoracicExtensionPose")) {
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

    // ---- 5. B-8b (the one matrix family whose residual was a DIFFERENT defect) -----------------
    //
    // This file used to PIN `ThoracicExtensionPose`'s residual here: its declared arm target was
    // derived from the engine-owned `neck!!.worldPosition` (written by
    // `SkeletonPoseFinalizer.resolveHeadTarget`, Phase 7), so the cold frame realized a target the
    // pose never sees again (declared-target delta 17.8718u, published ELBOW_A/HAND_A 29.9277 /
    // 17.9135u, cold-frame `maxIkClampAmount` 15.4668 against 5.5162 in the rep).
    //
    // B-8b is FIXED (pose-side: the target is projected from the authored gaze, `def.neckLength`
    // and the declared chest frame), so the pin is gone and the family is asserted as a
    // cold-frame-consistent control in `trunkFramesTheEngineDerivesAsIdentityAreNotRewritten`
    // above. The measured residual is NOT carried as an accepted exception: the focused regression
    // `ThoracicExtensionArmTargetTest` fails if the target source regresses, and no assertion here
    // was weakened to accommodate the old value.
}
