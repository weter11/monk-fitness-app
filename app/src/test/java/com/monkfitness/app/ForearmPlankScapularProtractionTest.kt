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
 * **The forearm plank's scapulae: the exercise's stated girdle, on the canonical channel.**
 *
 * `StaticForearmPlankPose`'s own copy and BPS `Plank (Forearm)` both put the girdle inside the
 * exercise — the pose's KDoc lists *"the shoulder girdle (scapular protraction)"* under MOVING joints
 * and states *"SCAPULAE: protracted ('push the mat away'), never winged/retracted"*; the BPS
 * §"Scapular strategy"/§5/§8/§11 state *"Scapulae protracted (serratus anterior) and depressed, flat
 * against the rib cage; the shoulder girdle is set, not collapsed or shrugged"* and *"Scapula:
 * Protracted (serratus), depressed, flat; no winging, no shrug"*. Measured on the pre-correction tree
 * through `SkeletonPipeline.produceFrame`, **both canonical `SCAPULA_A`/`SCAPULA_P` joints published
 * `0.0000` rad at every sampled phase** with zero glenoid travel off the chest: the stated protraction
 * was carried by the thorax's rounding alone. That is the animation-logic defect this file gates — a
 * pose can be biomechanically valid, fully engine-driven, and still animate the wrong thing.
 *
 * The authored correction is `driveScapula(-A·breath, +A·breath)` with
 * `A = 0.60` activation units (the pose's own authored amplitude — `BasePlankPose.GIRDLE_PROTRACTION`)
 * and `breath = sin(π·progress)` (the pose's own
 * zero-at-the-endpoints stabilization driver). What this file pins, everything measured on the
 * **published frame** and captured BY VALUE (`produceFrame` hands back a reused mutable buffer):
 *
 *  1. [theGirdleIsCarriedByTheCanonicalScapulaJoints] — each blade's own articulation equals the
 *     authored activation at every phase, rest at both authored endpoints (RED pre-fix: `0.0000`);
 *  2. [bothBladesProtractTowardTheirPlantedElbowsAndNeitherWings] — the anatomy, read off the chest's
 *     own anterior axis (the one quantity the girdle cannot move, because the thorax is authored):
 *     both glenoids gain the SAME anterior displacement, i.e. both blades wrap flat toward their
 *     planted elbows and neither wings;
 *  3. [theThoraxDoesNotAbsorbTheGirdle] — the audit's rule: an authored thorax keeps the girdle out of
 *     `SkeletonPoseFinalizer.reconstructChestFrame`, so the published thoracic articulation and the
 *     chest's world frame stay exactly the pose's own;
 *  4. [thePlantedForearmStaysFlatAndThePillarIsUntouched] — the plant contract the amplitude is
 *     DERIVED from: one flat forearm on the mat, the elbow directly under its shoulder at the braced
 *     endpoint, the declared targets realized, no solver relocation;
 *  5. [theGirdleCycleIsFrameConditionInvariantAndTheSamplesAreIndependent] — the B-8 cold-frame class
 *     and the T-7 aliasing class (anti-vacuity: independent copies whose content differs).
 */
class ForearmPlankScapularProtractionTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val poseName = "StaticForearmPlankPose"
    private val blades = listOf("A", "P")

    /** A dense sweep, so the girdle cycle's shape is measured rather than sampled. */
    private val sweep = (0..12).map { it / 12f }

    /** The angular comparison band: 24 % of the authored peak (`0.0210` rad). */
    private val angleBand = 0.005f

    /** The pose's positional tolerance for the trunk/girdle models. */
    private val posBand = 0.05f

    /** The family's planted-forearm height (`BasePlankPose.contactY`). */
    private val contactY = 15f

    /** `PlankForearmSupportGeometryTest`'s flat-forearm band — the amplitude's own budget. */
    private val flatForearmBand = 1.5f

    /** How far the plant's realized footprint may drift from the authored one (measured 0.5136 u). */
    private val footprintBand = 1.0f

    /** The authored plant footprint (`StaticForearmPlankPose.forearmPlantX`). */
    private val plantX = 120f

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)
    private fun deg(v: Float) = String.format(Locale.US, "%.4f", Math.toDegrees(v.toDouble()))

    /**
     * The authored protraction this file pins — the pose's declaration (`BasePlankPose.
     * GIRDLE_PROTRACTION`), RESTATED here as the contract, exactly as the engine's other
     * animation-logic gates restate the pose's own authored amplitudes.
     */
    private val authoredUnits = 0.60f

    /** The pose's own stabilization driver — the schedule the girdle is authored on. */
    private fun breath(p: Float) = sin(p * PI.toFloat())

    /** The authored articulation (radians) at [p]. */
    private fun authoredAngle(p: Float) = authoredUnits * breath(p) * SkeletonMath.SCAPULA_RETRECTION_TO_RAD

    /** The pose's own authored thoracic articulation at [p]. */
    private fun authoredThoracic(p: Float) = 0.09f * p + breath(p) * 0.02f

    // ---- harness ------------------------------------------------------------------------------

    /**
     * One published frame, read into primitives DURING the single pass — `produceFrame` publishes a
     * reused buffer and the node tree behind it is reused too, so a retained reference would alias
     * every sample to the last frame (the T-7 class).
     */
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
        Joint.SCAPULA_A, Joint.SCAPULA_P
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

    /** One builder + one pipeline advancing through the sweep; every frame captured by value. */
    private fun playingFrames(): List<Pair<Float, Frame>> {
        val builder = MotionProbe.build(poseName)
        val env = builder.metadata.environment
        val supported = builder.metadata.support.supportPoints
        val pipeline = SkeletonPipeline(def)
        for (k in 0..10) pipeline.produceFrame(builder.build(ctx(0.3f)), env, supported)
        return sweep.map { p -> p to snapshot(pipeline.produceFrame(builder, ctx(p)).pose) }
    }

    /** A genuinely COLD frame: a fresh pose instance on a fresh pipeline (the B-8 class). */
    private fun coldFrame(p: Float): Frame {
        val builder = MotionProbe.build(poseName)
        return snapshot(
            SkeletonPipeline(def)
                .produceFrame(builder.build(ctx(p)), builder.metadata.environment, builder.metadata.support.supportPoints).pose
        )
    }

    /** The chest's own local +X axis in world — the "anterior" a prone girdle drives its glenoid along. */
    private fun chestAnterior(frame: Frame): Vector3 =
        SkeletonMath.toWorldDirection(Vector3(1f, 0f, 0f), frame.world.getValue(Joint.CHEST), Vector3())

    /** A joint's offset from the chest, projected on the chest's own anterior axis. */
    private fun glenoidAnterior(frame: Frame, blade: String): Float {
        val chest = frame.joints.getValue(Joint.CHEST)
        val shoulder = frame.joints.getValue(Joint.valueOf("SHOULDER_$blade"))
        val off = Vector3().set(shoulder).subtract(chest)
        val ax = chestAnterior(frame)
        return off.x * ax.x + off.y * ax.y + off.z * ax.z
    }

    /** A rotation's signed angle about the lateral (Z) axis — the trunk/girdle planes' own axis. */
    private fun zSigned(r: JointRotation): Float = r.angle * (if (r.axis.z < 0f) -1f else 1f)

    private fun dist(a: Vector3, b: Vector3) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    // ------------------------------------------------------------------------------------------
    // 1. The defect itself — which joint carries the exercise's stated girdle
    // ------------------------------------------------------------------------------------------

    @Test
    fun theGirdleIsCarriedByTheCanonicalScapulaJoints() {
        val frames = playingFrames()
        val wrong = mutableListOf<String>()
        val witnesses = mutableListOf<String>()
        for ((p, frame) in frames) {
            for (blade in blades) {
                val joint = Joint.valueOf("SCAPULA_$blade")
                val measured = frame.localOf(joint).angle
                // The articulation is about the chest's own long axis; the stored axis/angle pair's
                // sign is representation-dependent, so the MAGNITUDE is the contract here and the
                // direction is asserted anatomically in the next test.
                witnesses.add("p=$p SCAPULA_$blade own=${deg(abs(measured))}°")
                if (abs(abs(measured) - authoredAngle(p)) > angleBand) {
                    wrong.add(
                        "p=$p the SCAPULA_$blade's own articulation is ${f(abs(measured))} rad " +
                            "(${deg(abs(measured))}°), the authored protraction requires " +
                            "${f(authoredAngle(p))} (${deg(authoredAngle(p))}°)"
                    )
                }
            }
        }
        assertTrue(
            "the exercise's stated scapular protraction must be carried by the canonical SCAPULA_A/P " +
                "joints — the pre-correction pose published 0.0000 rad there at every phase, leaving " +
                "only the thorax's rounding to stand for it:\n" +
                wrong.take(6).joinToString("\n") + "\nwitnesses:\n" + witnesses.take(14).joinToString("\n"),
            wrong.isEmpty()
        )

        // Anti-vacuity: the girdle must really be driven, and it must live INSIDE the hold — at rest at
        // both authored endpoints, where the pose's own frames (and their guards) are.
        val peak = frames.maxBy { authoredAngle(it.first) }
        assertTrue(
            "anti-vacuity: the hold's peak must carry a real protraction (authored " +
                "${deg(authoredAngle(peak.first))}° = ${f(authoredAngle(peak.first))} rad, comparison band ${f(angleBand)})",
            // The floor is the COMPARISON BAND itself (4.2× below the authored peak of `0.0210`
            // rad), not a multiple calibrated to the amplitude: a floor sitting within a few percent
            // of a decided amplitude breaks the moment the amplitude is re-decided (batch 1's lesson).
            authoredAngle(peak.first) > angleBand
        )
        for ((p, frame) in frames.filter { it.first < 1e-6f || it.first > 1f - 1e-6f }) {
            for (blade in blades) {
                assertEquals(
                    "p=$p: the girdle must be at rest at BOTH authored endpoints — the rep's " +
                        "entering/exiting contract, and what keeps the braced hold's vertical pillar",
                    0f, frame.localOf(Joint.valueOf("SCAPULA_$blade")).angle, 1e-5f
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. The anatomy — both blades protract, neither wings
    // ------------------------------------------------------------------------------------------

    @Test
    fun bothBladesProtractTowardTheirPlantedElbowsAndNeitherWings() {
        val frames = playingFrames()
        val wrong = mutableListOf<String>()
        val asymmetric = mutableListOf<String>()
        val witnesses = mutableListOf<String>()

        for ((p, frame) in frames) {
            val a = glenoidAnterior(frame, "A")
            val b = glenoidAnterior(frame, "P")
            witnesses.add("p=$p glenoid anterior A=${f(a)} P=${f(b)}")
            // The glenoid travels ANTERIORLY — toward the mat and its own planted elbow in this prone
            // layout, which is the protracted / "flat against the rib cage" direction; the posterior
            // direction is the winging both BPS files name as a fault.
            for ((blade, v) in listOf("A" to a, "P" to b)) {
                val want = def.shoulderWidth * sin(authoredAngle(p))
                if (abs(v - want) > posBand) {
                    wrong.add(
                        "p=$p the SHOULDER_$blade's anterior displacement is ${f(v)} u, the authored " +
                            "protraction requires ${f(want)}"
                    )
                }
            }
            // …and the blades move EQUALLY: an unmirrored (same-signed) drive is a twist about the
            // trunk's long axis in this layout — one blade protracted, the other winged.
            if (abs(a - b) > posBand) asymmetric.add("p=$p A=${f(a)} P=${f(b)}")
        }

        assertTrue(
            "both scapulae must protract (flat, toward their planted elbows) — the direction the BPS " +
                "files demand, published as 0.0000 u before this correction:\n" +
                wrong.take(6).joinToString("\n"),
            wrong.isEmpty()
        )
        assertTrue(
            "the two blades must protract TOGETHER (equal displacement; a same-signed drive twists the " +
                "shoulder line, flattening one blade while winging the other):\n" +
                asymmetric.take(6).joinToString("\n") + "\nwitnesses:\n" + witnesses.take(14).joinToString("\n"),
            asymmetric.isEmpty()
        )

        // The rigid plant chain the protraction rides on: the glenoid sits exactly one upper arm from
        // its elbow — which is why the glenoid's vertical travel IS the planted elbow's travel.
        for ((p, frame) in frames) {
            for (blade in blades) {
                val d = dist(
                    frame.joints.getValue(Joint.valueOf("SHOULDER_$blade")),
                    frame.joints.getValue(Joint.valueOf("ELBOW_$blade"))
                )
                assertEquals(
                    "p=$p the SHOULDER_$blade→ELBOW_$blade segment must hold the definition's length",
                    def.upperArmLength, d, 1e-3f
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. The thorax is the author's — never the reconstruction's
    // ------------------------------------------------------------------------------------------

    @Test
    fun theThoraxDoesNotAbsorbTheGirdle() {
        val wrongAngle = mutableListOf<String>()
        val wrongAxis = mutableListOf<String>()
        val wrongWorld = mutableListOf<String>()
        for ((p, frame) in playingFrames()) {
            val authored = authoredThoracic(p)
            val local = frame.localOf(Joint.CHEST)
            if (abs(zSigned(local) - authored) > 1e-4f) {
                wrongAngle.add("p=$p the chest's own articulation is ${f(zSigned(local))} rad, the pose authored ${f(authored)}")
            }
            if (abs(local.axis.x) > 1e-4f || abs(local.axis.y) > 1e-4f) {
                wrongAxis.add("p=$p chest axis=(${f(local.axis.x)}, ${f(local.axis.y)}, ${f(local.axis.z)})")
            }
            // The trunk is rigid and the lumbar is a pass-through: the chest's WORLD articulation is
            // the pelvis's own plus the thoracic value, with no room for a girdle-driven frame.
            val pelvis = frame.localOf(Joint.PELVIS)
            val chestWorld = frame.world.getValue(Joint.CHEST)
            if (abs(zSigned(chestWorld) - (zSigned(pelvis) + authored)) > 1e-3f) {
                wrongWorld.add(
                    "p=$p chest world ${f(zSigned(chestWorld))} rad vs pelvis ${f(zSigned(pelvis))} + " +
                        "authored thoracic ${f(authored)}"
                )
            }
        }
        assertTrue(
            "the published thoracic articulation must be the pose's own authored value — if " +
                "`reconstructChestFrame` had absorbed the girdle, this is where it would show:\n" +
                wrongAngle.take(6).joinToString("\n"),
            wrongAngle.isEmpty()
        )
        assertTrue("the thoracic articulation must stay about the lateral axis:\n" + wrongAxis.take(6).joinToString("\n"), wrongAxis.isEmpty())
        assertTrue(
            "the chest's world frame must be the pelvis's plus the authored thoracic value:\n" +
                wrongWorld.take(6).joinToString("\n"),
            wrongWorld.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4. The plant — the contract the amplitude is derived from
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePlantedForearmStaysFlatAndThePillarIsUntouched() {
        val frames = playingFrames()
        val declared = MotionProbe.build(poseName).metadata.support.contacts.map { it.point }.toSet()
        val wrongFlat = mutableListOf<String>()
        val wrongFootprint = mutableListOf<String>()
        val relocated = mutableListOf<String>()
        val witnesses = mutableListOf<String>()
        var worstFlat = 0f

        for ((p, frame) in frames) {
            assertEquals("p=$p: the declared support model must reach the published frame", declared, frame.supportedPoints)
            assertEquals("p=$p: the solver must not relocate a limb", 0f, frame.clamp, 1e-4f)
            for ((blade, z) in listOf("A" to -def.shoulderWidth, "P" to def.shoulderWidth)) {
                val elbow = frame.joints.getValue(Joint.valueOf("ELBOW_$blade"))
                val hand = frame.joints.getValue(Joint.valueOf("HAND_$blade"))
                val flat = abs(elbow.y - hand.y)
                worstFlat = maxOf(worstFlat, flat)
                witnesses.add("p=$p ELBOW_$blade.y=${f(elbow.y)} HAND_$blade.y=${f(hand.y)} flat=${f(flat)}")
                // The declared `*_FOREARM` contact is ONE flat forearm: this is the band the authored
                // amplitude is derived from, so it must hold at every phase.
                if (flat > flatForearmBand) {
                    wrongFlat.add("p=$p ELBOW_$blade/HAND_$blade are ${f(flat)} u apart (band ${f(flatForearmBand)})")
                }
                if (abs(hand.y - contactY) > 1e-3f) {
                    wrongFlat.add("p=$p HAND_$blade.y=${f(hand.y)} off the family's contact height")
                }
                // The girdle's own second-order lateral/in-line term: measured 0.1626 u in X and
                // 0.5136 u in Z at the peak, against the footprint itself.
                if (abs(elbow.x - plantX) > footprintBand || abs(elbow.z - z) > footprintBand) {
                    wrongFootprint.add("p=$p ELBOW_$blade=(${f(elbow.x)}, ${f(elbow.z)}) against the authored (${f(plantX)}, ${f(z)})")
                }
                val target = frame.limbTargets[Joint.valueOf("HAND_$blade")]
                    ?: error("the published frame must carry the arm's declared target (the §1.1 carrier)")
                if (dist(hand, target) > posBand) relocated.add("p=$p HAND_$blade relocated ${f(dist(hand, target))}")
            }
        }
        assertTrue(
            "the declared forearm must stay ONE flat contact (the amplitude's own budget):\n" +
                wrongFlat.take(6).joinToString("\n") + "\nwitnesses:\n" + witnesses.take(6).joinToString("\n"),
            wrongFlat.isEmpty()
        )
        assertTrue("the plant's authored footprint must be untouched:\n" + wrongFootprint.take(6).joinToString("\n"), wrongFootprint.isEmpty())
        assertTrue("the realized hands must be the declared targets (no relocation):\n" + relocated.take(6).joinToString("\n"), relocated.isEmpty())
        // Anti-vacuity: the girdle must really load the plant. The floor sits above the pre-fix pose's
        // own residual (measured `0.3973` u on the untouched tree) and well below the authored value.
        // Anti-vacuity: see the sibling plank's record — the floor sits above the pre-fix pose's own
        // residual (`0.0000` u: its elbow is exactly on the mat) and well below the authored value.
        assertTrue("anti-vacuity: the stabilization must really load the plant (worst flatness ${f(worstFlat)} u)", worstFlat > 0.6f)

        // The endpoints are the pose's own authored frames, and the braced hold's pillar is exactly the
        // BPS §6/§11 one — the invariant that makes the girdle's schedule what it is.
        for ((p, frame) in frames.filter { it.first < 1e-6f || it.first > 1f - 1e-6f }) {
            for (blade in blades) {
                val shoulder = frame.joints.getValue(Joint.valueOf("SHOULDER_$blade"))
                val elbow = frame.joints.getValue(Joint.valueOf("ELBOW_$blade"))
                val offset = sqrt((shoulder.x - elbow.x) * (shoulder.x - elbow.x) + (shoulder.z - elbow.z) * (shoulder.z - elbow.z))
                if (p > 1f - 1e-6f) {
                    assertEquals(
                        "p=$p: the braced hold's support elbow must be DIRECTLY under its shoulder — the " +
                            "girdle is therefore authored to vanish at this endpoint",
                        0f, offset, 0.01f
                    )
                } else {
                    assertTrue(
                        "p=$p: the settled frame's authored pillar lean must be preserved (offset ${f(offset)})",
                        offset > 1f && offset <= 27.4f
                    )
                }
            }
        }
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
            for (j in listOf(Joint.SCAPULA_A, Joint.SCAPULA_P, Joint.SHOULDER_A, Joint.SHOULDER_P,
                Joint.ELBOW_A, Joint.ELBOW_P, Joint.HAND_A, Joint.HAND_P, Joint.CHEST)) {
                val d = dist(cold.joints.getValue(j), sample.second.joints.getValue(j))
                if (d > 0.1f) drifting.add("p=${sweep[i]} $j cold-vs-playing ${f(d)}")
            }
            val dAngle = abs(cold.localOf(Joint.SCAPULA_A).angle - sample.second.localOf(Joint.SCAPULA_A).angle)
            if (dAngle > 1e-4f) drifting.add("p=${sweep[i]} SCAPULA_A articulation cold-vs-playing ${f(dAngle)}")
        }
        assertTrue(
            "the girdle is authored in `onBuild` and must not be frame-condition sensitive (the B-8 " +
                "cold-frame class):\n" + drifting.take(8).joinToString("\n"),
            drifting.isEmpty()
        )

        // T-7: the samples must be INDEPENDENT copies whose CONTENT differs — the trap this file's
        // harness is written around (a retained `PipelineResult.pose` aliases every sample).
        assertEquals("every sampled phase must be captured", sweep.size, playing.size)
        fun signature(fr: Frame) = Joint.entries.joinToString(",") {
            "%.6f/%.6f/%.6f".format(Locale.ROOT, fr.joints.getValue(it).x, fr.joints.getValue(it).y, fr.joints.getValue(it).z)
        }
        assertEquals(
            "the sampled frames must be distinct geometry, not one frame measured 13 times",
            sweep.size, playing.map { signature(it.second) }.distinct().size
        )
        val first = playing.first().second
        val before = Vector3().set(first.joints.getValue(Joint.SHOULDER_A))
        val scapulaBefore = first.localOf(Joint.SCAPULA_A).angle
        repeat(3) { coldFrame(0.7f) }
        assertEquals("a captured frame must be immutable: producing further frames may not change it", 0f, dist(before, first.joints.getValue(Joint.SHOULDER_A)), 0f)
        assertEquals("a captured frame's girdle articulation must be immutable", scapulaBefore, first.localOf(Joint.SCAPULA_A).angle, 0f)

        // …and the girdle really is inside the measured geometry (not a constant).
        val spans = blades.map { blade ->
            val v = playing.map { abs(it.second.localOf(Joint.valueOf("SCAPULA_$blade")).angle) }
            v.max() - v.min()
        }
        assertTrue("anti-vacuity: both blades must sweep a real articulation (spans ${spans.map { f(it) }})", spans.all { it > angleBand })
    }
}
