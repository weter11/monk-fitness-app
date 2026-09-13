package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **The side plank's support scapula: the exercise's stated active girdle stabilization.**
 *
 * `IsometricSidePlankPose`'s own copy and BPS `Plank (Side)` both make the down-side girdle part of
 * the exercise — the pose's KDoc lists *"the down-side shoulder girdle (must stay depressed +
 * protracted, not shrug to the ear)"* under MOVING joints and states *"SCAPULA (down side): actively
 * stabilized — the shoulder is pushed away from the ear (never collapsed into the joint)"*; the BPS
 * §"Scapular strategy"/§5/§8/§11 state *"On the supporting side, the scapula is protracted and
 * depressed, flat against the rib cage; the shoulder is set and stable"*, *"Supporting elbow under
 * shoulder; scapula flat/depressed, no winging/shrug"*. Measured on the pre-correction tree through
 * `SkeletonPipeline.produceFrame`, **both canonical `SCAPULA_A`/`SCAPULA_P` joints published
 * `0.0000` rad at every sampled phase** with zero glenoid travel off the chest: the pose's stated
 * active stabilization was carried by the propped plant's static geometry alone.
 *
 * The authored correction is `driveScapula(a = 0, p = -A·breath)` with
 * `A = 0.60` activation units (the pose's own authored amplitude — `BasePlankPose.GIRDLE_PROTRACTION`)
 * and `breath = sin(π·progress)`: the SUPPORT blade only (the
 * copy's subject is the down-side girdle), on the hold's own zero-at-the-endpoints stabilization
 * driver, in this layout's protraction direction (measured: toward its own planted elbow).
 *
 * What this file pins, all on the **published frame** captured BY VALUE:
 *
 *  1. [theSupportScapulaCarriesTheStatedStabilization] — the support blade's own articulation equals
 *     the authored activation at every phase and is at rest at both authored endpoints (RED pre-fix:
 *     `0.0000`), while the TOP blade is deliberately untouched (the scope pin);
 *  2. [theSupportGlenoidIsPushedTowardItsElbowAndNotUpToTheEar] — the anatomy: the glenoid travels
 *     along the chest's own support-side axis, toward the planted elbow (the copy's "pushed away from
 *     the ear"), and the rib cage keeps its authored line;
 *  3. [theThoraxDoesNotAbsorbTheGirdle] — the audit's rule on a pose that authors its thorax frame
 *     outright (the B-8 roll): the published chest articulation is the pose's own;
 *  4. [thePlantedSupportForearmAndThePlantedFootAreUntouched] — the plant contract the amplitude is
 *     derived from, plus the pose's own choreography (the hips' lift, the stacked foot);
 *  5. [theGirdleCycleIsFrameConditionInvariantAndTheSamplesAreIndependent] — the B-8 cold-frame class
 *     and the T-7 aliasing class.
 */
class SidePlankScapularStabilizationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val poseName = "IsometricSidePlankPose"

    /** A dense sweep, so the girdle cycle's shape is measured rather than sampled. */
    private val sweep = (0..12).map { it / 12f }

    /** The angular comparison band: 24 % of the authored peak (`0.0210` rad). */
    private val angleBand = 0.005f

    /** The pose's positional tolerance for the trunk/girdle models. */
    private val posBand = 0.05f

    /** The family's planted contact height (`BasePlankPose.contactY`); the support foot rests on it. */
    private val contactY = 15f

    /** `PlankForearmSupportGeometryTest`'s flat-forearm band — the amplitude's own budget. */
    private val flatForearmBand = 1.5f

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)
    private fun deg(v: Float) = String.format(Locale.US, "%.4f", Math.toDegrees(v.toDouble()))

    /**
     * The authored stabilization this file pins — the pose's declaration (`BasePlankPose.
     * GIRDLE_PROTRACTION`), RESTATED here as the contract, exactly as the engine's other
     * animation-logic gates restate the pose's own authored amplitudes.
     */
    private val authoredUnits = 0.60f

    /** The pose's own stabilization driver — the schedule the girdle is authored on. */
    private fun breath(p: Float) = sin(p * PI.toFloat())

    /** The authored articulation (radians) at [p]. */
    private fun authoredAngle(p: Float) = authoredUnits * breath(p) * SkeletonMath.SCAPULA_RETRECTION_TO_RAD

    // ---- harness ------------------------------------------------------------------------------

    private class Frame(
        val joints: Map<Joint, Vector3>,
        /** World rotations of the watched joints. */
        val world: Map<Joint, JointRotation>,
        /** Parent-relative rotations of the spine/girdle joints. */
        val local: Map<Joint, JointRotation>,
        val limbTargets: Map<Joint, Vector3>,
        val clamp: Float,
        val supportedPoints: Set<SupportPoint>
    ) {
        fun localOf(j: Joint) = local.getValue(j)
    }

    private fun node(n: SkeletonNode, joint: Joint): SkeletonNode? {
        if (n.joint == joint) return n
        for (c in n.children) node(c, joint)?.let { return it }
        return null
    }

    private val watched = listOf(
        Joint.PELVIS, Joint.LUMBAR, Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS,
        Joint.SHOULDER_A, Joint.SHOULDER_P, Joint.ELBOW_A, Joint.ELBOW_P, Joint.HAND_A, Joint.HAND_P,
        Joint.SCAPULA_A, Joint.SCAPULA_P, Joint.HIP_B, Joint.ANKLE_B, Joint.ANKLE_F
    )

    private fun snapshot(published: SkeletonPose): Frame {
        val joints = HashMap<Joint, Vector3>(Joint.entries.size)
        for (j in Joint.entries) joints[j] = Vector3().set(published.getJoint(j))
        val world = watched.associateWith { j ->
            val n = node(published.roots.first(), j) ?: error("$j must be published on the tree")
            JointRotation().also { it.copyFrom(n.worldRotation) }
        }
        val local = listOf(Joint.SCAPULA_A, Joint.SCAPULA_P, Joint.CHEST, Joint.PELVIS).associateWith { j ->
            val n = node(published.roots.first(), j)!!
            JointRotation().also { it.copyFrom(n.localRotation) }
        }
        val targets = published.limbTargets.associate { it.joint to Vector3().set(it.world) }
        return Frame(joints, world, local, targets, published.maxIkClampAmount, published.supportedPoints.toSet())
    }

    private fun playingFrames(): List<Pair<Float, Frame>> {
        val builder = MotionProbe.build(poseName)
        val env = builder.metadata.environment
        val supported = builder.metadata.support.supportPoints
        val pipeline = SkeletonPipeline(def)
        for (k in 0..10) pipeline.produceFrame(builder.build(ctx(0.3f)), env, supported)
        return sweep.map { p -> p to snapshot(pipeline.produceFrame(builder, ctx(p)).pose) }
    }

    private fun coldFrame(p: Float): Frame {
        val builder = MotionProbe.build(poseName)
        return snapshot(
            SkeletonPipeline(def)
                .produceFrame(builder.build(ctx(p)), builder.metadata.environment, builder.metadata.support.supportPoints).pose
        )
    }

    /**
     * The chest's own local +Z axis in world. In this pose's rolled layout that is the support
     * side's own direction along the girdle's DOF — world `(0.577, -0.817, 0)`, i.e. downward toward
     * the planted elbow — so the support glenoid's projection on it is "toward its own elbow".
     */
    private fun supportAxis(frame: Frame): Vector3 =
        SkeletonMath.toWorldDirection(Vector3(0f, 0f, 1f), frame.world.getValue(Joint.CHEST), Vector3())

    private fun supportGlenoid(frame: Frame): Float {
        val chest = frame.joints.getValue(Joint.CHEST)
        val shoulder = frame.joints.getValue(Joint.SHOULDER_P)
        val off = Vector3().set(shoulder).subtract(chest)
        val ax = supportAxis(frame)
        return off.x * ax.x + off.y * ax.y + off.z * ax.z
    }

    private fun zSigned(r: JointRotation): Float = r.angle * (if (r.axis.z < 0f) -1f else 1f)

    private fun dist(a: Vector3, b: Vector3) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    // ------------------------------------------------------------------------------------------
    // 1. The defect itself — the support girdle's stabilization
    // ------------------------------------------------------------------------------------------

    @Test
    fun theSupportScapulaCarriesTheStatedStabilization() {
        val frames = playingFrames()
        val wrong = mutableListOf<String>()
        val witnesses = mutableListOf<String>()
        for ((p, frame) in frames) {
            val measured = abs(frame.localOf(Joint.SCAPULA_P).angle)
            witnesses.add("p=$p SCAPULA_P own=${deg(measured)}°")
            if (abs(measured - authoredAngle(p)) > angleBand) {
                wrong.add(
                    "p=$p the SCAPULA_P's own articulation is ${f(measured)} rad (${deg(measured)}°), the " +
                        "authored stabilization requires ${f(authoredAngle(p))} (${deg(authoredAngle(p))}°)"
                )
            }
        }
        assertTrue(
            "the pose's stated active support-girdle stabilization must be carried by the canonical " +
                "SCAPULA_P joint — the pre-correction pose published 0.0000 rad there at every phase:\n" +
                wrong.take(6).joinToString("\n") + "\nwitnesses:\n" + witnesses.take(14).joinToString("\n"),
            wrong.isEmpty()
        )

        val peak = frames.maxBy { authoredAngle(it.first) }
        assertTrue(
            "anti-vacuity: the hold's peak must carry a real stabilization (authored ${deg(authoredAngle(peak.first))}°, " +
                "comparison band ${f(angleBand)} rad)",
            // The floor is the COMPARISON BAND itself (4.2× below the authored peak of `0.0210`
            // rad), not a multiple calibrated to the amplitude: a floor sitting within a few percent
            // of a decided amplitude breaks the moment the amplitude is re-decided (batch 1's lesson).
            authoredAngle(peak.first) > angleBand
        )
        for ((p, frame) in frames.filter { it.first < 1e-6f || it.first > 1f - 1e-6f }) {
            assertEquals(
                "p=$p: the girdle must be at rest at BOTH authored endpoints — the rep's entering/exiting " +
                    "contract, and what keeps the braced hold's vertical support pillar",
                0f, frame.localOf(Joint.SCAPULA_P).angle, 1e-5f
            )
        }

        // Scope pin: the copy's subject is the DOWN-SIDE girdle only; the top arm's blade is not part
        // of this exercise's girdle statement and must stay untouched.
        for ((p, frame) in frames) {
            assertEquals(
                "p=$p: the top (non-support) blade must stay exactly as authored — this pose drives the " +
                    "support girdle only",
                0f, frame.localOf(Joint.SCAPULA_A).angle, 1e-6f
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. The anatomy — toward the planted elbow, never up to the ear
    // ------------------------------------------------------------------------------------------

    @Test
    fun theSupportGlenoidIsPushedTowardItsElbowAndNotUpToTheEar() {
        val frames = playingFrames()
        val wrong = mutableListOf<String>()
        val witnesses = mutableListOf<String>()
        for ((p, frame) in frames) {
            val v = supportGlenoid(frame)
            val want = def.shoulderWidth * sin(authoredAngle(p))
            val shoulder = frame.joints.getValue(Joint.SHOULDER_P)
            val elbow = frame.joints.getValue(Joint.ELBOW_P)
            witnesses.add("p=$p support glenoid=${f(v)} shoulder.y=${f(shoulder.y)} elbow.y=${f(elbow.y)}")
            // The support glenoid must travel along the pose's own support direction — toward its
            // planted elbow, which in this layout is *down* (the copy's "pushed away from the ear").
            if (abs(v - want) > posBand) {
                wrong.add("p=$p the support glenoid's travel along its own support axis is ${f(v)} u, the authored stabilization requires ${f(want)}")
            }
            if (v > 0f && shoulder.y > elbow.y + def.upperArmLength) {
                wrong.add("p=$p the support shoulder rose more than one upper arm above its elbow (${f(shoulder.y - elbow.y)}) — a shrug")
            }
        }
        assertTrue(
            "the support glenoid must be pushed toward its own planted elbow (the direction the " +
                "exercise's copy names), published as 0.0000 u before this correction:\n" +
                wrong.take(6).joinToString("\n") + "\nwitnesses:\n" + witnesses.take(14).joinToString("\n"),
            wrong.isEmpty()
        )

        // The rigid support chain the stabilization rides on.
        for ((p, frame) in frames) {
            assertEquals(
                "p=$p the SHOULDER_P→ELBOW_P segment must hold the definition's length",
                def.upperArmLength,
                dist(frame.joints.getValue(Joint.SHOULDER_P), frame.joints.getValue(Joint.ELBOW_P)),
                1e-3f
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. The thorax is the author's — the audit's rule on an authored frame
    // ------------------------------------------------------------------------------------------

    @Test
    fun theThoraxDoesNotAbsorbTheGirdle() {
        val wrong = mutableListOf<String>()
        for ((p, frame) in playingFrames()) {
            val local = frame.localOf(Joint.CHEST)
            // The pose authors its thorax frame outright (the B-8 roll: `chest.localRotation = (Y, π/2)`),
            // so the Finalizer's fallback never runs and the frame must be constant at every phase.
            if (abs(abs(local.angle) - (PI.toFloat() / 2f)) > 1e-4f) {
                wrong.add("p=$p the chest's authored roll is ${f(local.angle)} rad against the authored ${f(PI.toFloat() / 2f)}")
            }
            if (abs(local.axis.y) < 1f - 1e-4f) {
                wrong.add("p=$p the chest's roll axis is (${f(local.axis.x)}, ${f(local.axis.y)}, ${f(local.axis.z)}) — not the spine's own axis")
            }
        }
        assertTrue(
            "the chest frame is pose-owned Phase-0 intent (the B-8 roll) and the girdle must not move " +
                "it: any drift here is the Finalizer's reconstruction absorbing the drive:\n" +
                wrong.take(6).joinToString("\n"),
            wrong.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4. The plant — the contract the amplitude is derived from
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePlantedSupportForearmAndThePlantedFootAreUntouched() {
        val frames = playingFrames()
        val declared = MotionProbe.build(poseName).metadata.support.contacts.map { it.point }.toSet()
        val wrongFlat = mutableListOf<String>()
        val relocated = mutableListOf<String>()
        val witnesses = mutableListOf<String>()
        var worstFlat = 0f

        for ((p, frame) in frames) {
            assertEquals("p=$p: the declared support model must reach the published frame", declared, frame.supportedPoints)
            assertEquals("p=$p: the solver must not relocate a limb", 0f, frame.clamp, 1e-4f)
            val elbow = frame.joints.getValue(Joint.ELBOW_P)
            val hand = frame.joints.getValue(Joint.HAND_P)
            val flat = abs(elbow.y - hand.y)
            worstFlat = maxOf(worstFlat, flat)
            witnesses.add("p=$p ELBOW_P.y=${f(elbow.y)} HAND_P.y=${f(hand.y)} flat=${f(flat)}")
            if (flat > flatForearmBand) {
                wrongFlat.add("p=$p ELBOW_P/HAND_P are ${f(flat)} u apart (band ${f(flatForearmBand)})")
            }
            if (abs(hand.y - contactY) > 1e-3f) wrongFlat.add("p=$p HAND_P.y=${f(hand.y)} off the family's contact height")
            val target = frame.limbTargets[Joint.HAND_P] ?: error("the published frame must carry the declared target (§1.1)")
            if (dist(hand, target) > posBand) relocated.add("p=$p HAND_P relocated ${f(dist(hand, target))}")
            // The planted support foot is not this correction's subject: it holds its mat height.
            val ankle = frame.joints.getValue(Joint.ANKLE_B)
            if (abs(ankle.y - contactY) > posBand) wrongFlat.add("p=$p ANKLE_B.y=${f(ankle.y)} off the family's floor-contact height")
        }
        assertTrue(
            "the declared support forearm must stay ONE flat contact (the amplitude's own budget):\n" +
                wrongFlat.take(6).joinToString("\n") + "\nwitnesses:\n" + witnesses.take(6).joinToString("\n"),
            wrongFlat.isEmpty()
        )
        assertTrue("the realized support hand must be the declared target (no relocation):\n" + relocated.take(6).joinToString("\n"), relocated.isEmpty())
        // Anti-vacuity: the girdle must really load the plant. The floor sits above the pre-fix pose's
        // own residual (measured `0.3973` u on the untouched tree) and well below the authored value.
        // Anti-vacuity: see the sibling plank's record — the floor sits above the pre-fix pose's own
        // residual (`0.0000` u: its elbow is exactly on the mat) and well below the authored value.
        assertTrue("anti-vacuity: the stabilization must really load the plant (worst flatness ${f(worstFlat)} u)", worstFlat > 0.6f)

        // The braced endpoint keeps the BPS §11 "supporting elbow under shoulder" pillar exactly, which
        // is why the girdle is authored to vanish there.
        for ((p, frame) in frames.filter { it.first > 1f - 1e-6f }) {
            val shoulder = frame.joints.getValue(Joint.SHOULDER_P)
            val elbow = frame.joints.getValue(Joint.ELBOW_P)
            val offset = sqrt((shoulder.x - elbow.x) * (shoulder.x - elbow.x) + (shoulder.z - elbow.z) * (shoulder.z - elbow.z))
            assertEquals("p=$p: the braced hold's support elbow must be DIRECTLY under its shoulder", 0f, offset, 0.01f)
        }

        // The pose's own choreography is untouched: the hips' lift and the stacked top foot's settle.
        val hipYs = frames.map { it.second.joints.getValue(Joint.HIP_B).y }
        assertTrue("the authored hip lift must be preserved (measured ${f(hipYs.max() - hipYs.min())}, floor 40)", hipYs.max() - hipYs.min() >= 40f)
        val footYs = frames.map { it.second.joints.getValue(Joint.ANKLE_F).y }
        assertTrue("the top foot's authored settle must be preserved (spread ${f(footYs.max() - footYs.min())}, floor 10)", footYs.max() - footYs.min() >= 10f)
    }

    // ------------------------------------------------------------------------------------------
    // 5. Frame condition + the aliasing class
    // ------------------------------------------------------------------------------------------

    @Test
    fun theGirdleCycleIsFrameConditionInvariantAndTheSamplesAreIndependent() {
        val playing = playingFrames()
        val drifting = mutableListOf<String>()
        for ((i, sample) in playing.withIndex()) {
            val cold = coldFrame(sample.first)
            for (j in listOf(Joint.SCAPULA_P, Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P, Joint.CHEST)) {
                val d = dist(cold.joints.getValue(j), sample.second.joints.getValue(j))
                if (d > 0.1f) drifting.add("p=${sweep[i]} $j cold-vs-playing ${f(d)}")
            }
            val dAngle = abs(cold.localOf(Joint.SCAPULA_P).angle - sample.second.localOf(Joint.SCAPULA_P).angle)
            if (dAngle > 1e-4f) drifting.add("p=${sweep[i]} SCAPULA_P articulation cold-vs-playing ${f(dAngle)}")
        }
        assertTrue(
            "the girdle is authored in `onBuild` and must not be frame-condition sensitive (the B-8 " +
                "cold-frame class):\n" + drifting.take(8).joinToString("\n"),
            drifting.isEmpty()
        )

        assertEquals("every sampled phase must be captured", sweep.size, playing.size)
        fun signature(fr: Frame) = Joint.entries.joinToString(",") {
            "%.6f/%.6f/%.6f".format(Locale.ROOT, fr.joints.getValue(it).x, fr.joints.getValue(it).y, fr.joints.getValue(it).z)
        }
        assertEquals(
            "the sampled frames must be distinct geometry, not one frame measured 13 times",
            sweep.size, playing.map { signature(it.second) }.distinct().size
        )
        val first = playing.first().second
        val before = Vector3().set(first.joints.getValue(Joint.SHOULDER_P))
        val scapulaBefore = first.localOf(Joint.SCAPULA_P).angle
        repeat(3) { coldFrame(0.7f) }
        assertEquals("a captured frame must be immutable: producing further frames may not change it", 0f, dist(before, first.joints.getValue(Joint.SHOULDER_P)), 0f)
        assertEquals("a captured frame's girdle articulation must be immutable", scapulaBefore, first.localOf(Joint.SCAPULA_P).angle, 0f)

        val span = playing.map { abs(it.second.localOf(Joint.SCAPULA_P).angle) }.let { it.max() - it.min() }
        assertTrue("anti-vacuity: the support blade must sweep a real articulation (span ${f(span)})", span > angleBand)
    }
}
