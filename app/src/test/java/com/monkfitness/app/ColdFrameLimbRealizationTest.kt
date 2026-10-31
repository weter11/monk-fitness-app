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
 * Cold-frame limb realization.
 *
 * The defect: a pose that leaves its trunk (chest) frame UNAUTHORED realized its limbs in the
 * pelvis-only frame on a builder's first build, because the engine's trunk frame for a non-upright
 * trunk was established by `SkeletonPoseFinalizer.reconstructChestFrame` — a Finalization-phase
 * operation, i.e. AFTER the Phase-1 limb realization had already baked the arm chain. The fallback
 * then re-FK'd the chest subtree and dragged the realized arms with it. Measured at progress 0, cold
 * first frame vs the same pose once settled: `ELBOW_A`/`HAND_A` deltas 74.31/160.33
 * (`pushup_standard`), 92.91/155.33 (`military`), 62.22/28.00 (`side_plank`); the standard plank's
 * hands sat 108.84 units ABOVE the floor. Frames >= 1 only looked correct because the fallback's node
 * write survived in the reused builder buffer and was read back as authored intent — so steady-state
 * correctness was a property of cross-build buffer reuse, not of the frame.
 *
 * The fix (production side): the trunk frame is POSE-owned intent, so the affected families declare it
 * through the single existing path (the chest node write plus its joint carrier — the form every other
 * authored articulation uses): `BasePushUpPose.declareFlatPlankTrunkFrame` (both pivot branches +
 * `PikePushUpPose`, which authors its own `onBuild`) and `IsometricSidePlankPose`. The Finalizer's
 * fallback is untouched; its trigger condition (an identity chest) is simply no longer met for these
 * poses.
 *
 * What these tests prove: the trunk frame is authoritative BEFORE the limb realization (asserted on a
 * bare `build()`, bit-equal to the frame the cold and the settled published frames carry, i.e. the
 * fallback did not write it); the genuinely cold first frame (first `produceFrame` of a FRESH pose on a
 * FRESH pipeline — no warm-up step anywhere in this file) agrees with the same pose settled at the
 * same progress and lands its hands on the world targets the pose declared; later frames stay stable
 * (frame 1 == 20 == 150); and poses that already author their trunk frame — or whose layout makes the
 * engine's derivation a no-op — are untouched.
 *
 * Tolerance: the fix makes the cold frame and the settled frame agree EXACTLY (0.000000 measured for
 * every joint of every family below). [COLD_TOLERANCE] is 0.25 units — 1/60 of the validator's 15-unit
 * `POSITION_DISCONTINUITY` threshold and 1/112 of the SMALLEST pre-fix delta in the fixed set (28.00,
 * `side_plank_standard` HAND_A) — so this suite cannot pass on the pre-fix implementation.
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

    @Test
    fun trunkFrameIsAuthoritativeBeforeTheLimbRealization() {
        for (name in b8Targets) {
            val built = MotionProbe.build(name).build(ctx(0f))
            val chest = chestNode(built)
            assertNotNull("$name: build() must publish a CHEST node", chest)
            // Copied out of the node: the node tree is reused across builds.
            val authored = JointRotation().also { it.copyFrom(chest!!.localRotation) }

            // (a) The frame is authored before the limb realization: non-identity at build time, i.e.
            // the fallback's trigger condition (`chest.localRotation.angle == 0`) is no longer met.
            assertTrue(
                "$name: the trunk frame must be declared during build (angle=${f(authored.angle)}); " +
                    "an identity chest here is exactly the pre-fix B-8 state in which the Phase-3 " +
                    "fallback `reconstructChestFrame` supplies the frame AFTER limb realization",
                abs(authored.angle) > 1.0f
            )

            // (b) The declaration runs through the SINGLE existing path: the chest joint carrier.
            val chestIntents = built.jointIntents.filter { it.joint == Joint.CHEST }
            assertEquals("$name: exactly one CHEST intent must be declared", 1, chestIntents.size)
            assertEquals(
                "$name: the declared CHEST intent must be the frame the node carries",
                0f, rotationDelta(chestIntents[0].rotation, authored), 1e-6f
            )

            // (c) The frame the limb realization sees IS the frame the pose publishes: the fallback
            // did not rewrite it, on the cold frame or later.
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
    @Test
    fun authoredTrunkFrameControlIsUnchanged() {
        // DeclinePushUpPose authors its own trunk pitch (`declineTrunkPitch`): a non-identity chest
        // is authored intent, the engine never reconstructs it, and it is the graded control the
        // finding used (pre-fix cold-frame delta exactly 0.00/0.00).
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
        // authoritative frame. The fix must not rewrite those (that would change established
        // geometry), and their limb chains must already be cold-frame consistent.
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

    // The one matrix family whose residual was a DIFFERENT defect.
    //
    // `ThoracicExtensionPose`'s residual was never a trunk-frame defect: its declared arm target was
    // derived from the engine-owned `neck!!.worldPosition`, so the cold frame realized a target the
    // pose never sees again. That target source is now pose-owned (projected from the authored gaze,
    // `def.neckLength` and the declared chest frame), so the family is asserted as a
    // cold-frame-consistent control above. The measured residual is NOT carried as an accepted
    // exception: `ThoracicExtensionArmTargetTest` fails if the target source regresses.
}
