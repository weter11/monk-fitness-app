package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.PelvicTiltPose
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **`PelvicTiltPose`'s authored tilt is carried by the pelvis AND the low back.**
 *
 * BPS `Pelvic Tilt (Standard)` §9 is explicit about the split — *"Pelvic rotation: posterior tilt to
 * anterior tilt, a small arc (often only a few degrees of true pelvic rotation, with the lumbar spine
 * moving through its lordosis range)"* — and §5 locates the motion at the lumbopelvic junction. The
 * pose used to publish the whole authored `angleOffset` on the **PELVIS** and leave the canonical
 * lower-spine segment rigid: measured through `SkeletonPipeline.produceFrame`, the `LUMBAR`'s world
 * rotation was `bit-identical` to the `PELVIS`'s at every sampled phase (the articulation rode the
 * root — the M3/M4 defect class the prone family was corrected for), so the drill read as a rigid
 * trunk hinged at the pelvis instead of a pelvic tilt with the low back moving.
 *
 * The authored arc is now SPLIT between the two segments, in the same sense and with the SAME total
 * (`pelvisRotation + lumbarRotation == torsoAngle` at every phase), so the rep's `0.12`-rad world arc
 * (the B4 trunk gate's own tolerance-checked quantity), the pelvis's static base, the published trunk
 * chain, the legs' quietness and the pose's floor/contact behaviour are all preserved — only the joint
 * that carries the motion changes. This file pins that split:
 *
 *  * `PELVIS_TILT_SHARE = 0.35` — the pelvis's own few degrees (BPS §9);
 *  * the LUMBAR carries the remainder (65% of the arc) — the low back's lordosis range (BPS §5/§9/§10);
 *  * nothing else about the pose moves: positions, contacts, plane, legs and the neck's authored
 *    counter-articulation are the pose's own and are asserted here as guards.
 *
 * Every measurement is taken on the **published frame** (`SkeletonPipeline.produceFrame(pose, ctx)`),
 * captured BY VALUE (the pipeline reuses its output buffer — the T-7 aliasing trap).
 */
class PelvicTiltSpineArticulationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** A dense sweep: the split is a function of the phase, so a few samples could step over it. */
    private val sweep = (0..20).map { it * 0.05f }

    /** The pose's authored supine tilt and tilt amplitude (its own literals). */
    private val supineTilt = 1.5708f
    private val tiltAmplitude = 0.12f

    /** The pelvis's own share of the authored arc; the LUMBAR carries `1 − share`. */
    private val pelvisTiltShare = 0.35f

    /** The pelvis's authored resting layer (X = 0, Y = 14) — the pose's static base. */
    private val restingLayer = 14f

    /** The pose's authored static hand placement ("prevents hand sliding"). */
    private val handX = -35f
    private val handY = 12f

    private val angleBand = 0.005f
    private val posBand = 0.05f

    /** The pelvis's declaration: a static base at the resting layer, both feet planted. */
    private val ground = 0f

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun snapshot(frame: SkeletonPose): SkeletonPose =
        SkeletonPose().apply { copyFrom(frame) }

    /** A genuinely COLD frame: a fresh pose instance on a fresh pipeline (the T2 COLD leg). */
    private fun coldFrame(p: Float): SkeletonPose =
        snapshot(SkeletonPipeline(def).produceFrame(PelvicTiltPose(), ctx(p)).pose)

    /** One builder + one pipeline advancing through the sweep; every frame captured by value. */
    private fun playingFrames(): List<Pair<Float, SkeletonPose>> {
        val pipeline = SkeletonPipeline(def)
        val builder = PelvicTiltPose()
        return sweep.map { p -> p to snapshot(pipeline.produceFrame(builder, ctx(p)).pose) }
    }

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun deg(v: Float) = String.format(Locale.US, "%.3f", Math.toDegrees(v.toDouble()))

    private fun dist(a: Vector3, b: Vector3) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    /** The signed rotation of a published joint about +Z (every articulation here is about Z). */
    private fun zAngle(pose: SkeletonPose, joint: Joint): Float {
        val r = pose.getJointRotation(joint)
        val sign = if (r.axis.z < 0f) -1f else 1f
        return r.angle * sign
    }

    /** The authored total: the trunk chord's world angle at a phase (the pose's own `1.5708 − 0.12p`). */
    private fun authoredTorso(p: Float) = supineTilt - tiltAmplitude * p

    /** The authored pelvis rotation (the pelvis's own share of that total). */
    private fun authoredPelvis(p: Float) = supineTilt - pelvisTiltShare * tiltAmplitude * p

    /** The authored LUMBAR articulation (its own rotation, relative to the pelvis). */
    private fun authoredLumbar(p: Float) = authoredTorso(p) - authoredPelvis(p)

    /** Rotate a 2-D vector about +Z by [angle] — the pose's own geometry model. */
    private fun rot(x: Float, y: Float, angle: Float) =
        (-y * sin(angle) + x * cos(angle)) to (x * sin(angle) + y * cos(angle))

    /** The signed smallest rotation carrying one angle onto another, in radians. */
    private fun normalizeRadians(a: Float): Float {
        var x = a
        while (x > Math.PI) x -= (2 * Math.PI).toFloat()
        while (x < -Math.PI) x += (2 * Math.PI).toFloat()
        return x
    }

    // ------------------------------------------------------------------------------------------
    // 1. The defect itself — which joint carries the tilt
    // ------------------------------------------------------------------------------------------

    @Test
    fun theTiltIsCarriedByTheLowBackAndNotByThePelvisAlone() {
        val wrongLumbar = mutableListOf<String>()
        val wrongPelvis = mutableListOf<String>()
        val witnesses = mutableListOf<String>()

        for ((p, frame) in playingFrames()) {
            val pelvisRot = zAngle(frame, Joint.PELVIS)
            val lumbarRot = zAngle(frame, Joint.LUMBAR)
            val lumbarLocal = lumbarRot - pelvisRot
            witnesses.add(
                "p=$p pelvis=${deg(pelvisRot)}° = ${deg(pelvisRot - supineTilt)}° of its own, " +
                    "lumbar=${deg(lumbarLocal)}° of its own, chord=${deg(lumbarRot - supineTilt)}°"
            )
            if (abs(lumbarLocal - authoredLumbar(p)) > angleBand) {
                wrongLumbar.add(
                    "p=$p the LUMBAR's own articulation is ${f(lumbarLocal)} rad " +
                        "(${deg(lumbarLocal)}°); the authored low-back share of the arc is " +
                        "${f(authoredLumbar(p))} (${deg(authoredLumbar(p))}°)"
                )
            }
            if (abs(pelvisRot - authoredPelvis(p)) > angleBand) {
                wrongPelvis.add(
                    "p=$p the PELVIS's own tilt is ${f(pelvisRot)} rad (${deg(pelvisRot)}°); the " +
                        "authored pelvis share is ${f(authoredPelvis(p))} (${deg(authoredPelvis(p))}°)"
                )
            }
        }

        assertTrue(
            "the LUMBAR must carry the low back's share of the tilt as its OWN articulation — the " +
                "defect was a rigid lower-spine segment publishing the PELVIS's rotation verbatim:\n" +
                wrongLumbar.take(6).joinToString("\n") + "\nwitnesses:\n" + witnesses.joinToString("\n"),
            wrongLumbar.isEmpty()
        )
        assertTrue(
            "the PELVIS must carry its own few degrees (BPS §9) and not the whole arc:\n" +
                wrongPelvis.take(6).joinToString("\n"),
            wrongPelvis.isEmpty()
        )

        // Anti-vacuity: at the top of the rep the two segments are DIFFERENT motions, and the low back
        // is the larger of the two — the pelvis alone can no longer explain the published tilt.
        val end = playingFrames().last().second
        val pelvisArc = abs(zAngle(end, Joint.PELVIS) - supineTilt)
        val lumbarArc = abs(zAngle(end, Joint.LUMBAR) - zAngle(end, Joint.PELVIS))
        assertTrue(
            "anti-vacuity: at p=1.0 the pelvis carries ${deg(pelvisArc)}° and the low back " +
                "${deg(lumbarArc)}° — both must be real, and the low back the larger",
            pelvisArc > angleBand * 4f && lumbarArc > pelvisArc && abs(lumbarArc - pelvisArc) > angleBand
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2. The split adds no range: the two segments sum to the pose's authored rep
    // ------------------------------------------------------------------------------------------

    @Test
    fun theTwoSegmentsSumToTheAuthoredArcAndAddNoRange() {
        val frames = playingFrames()
        val wrong = mutableListOf<String>()
        for ((p, frame) in frames) {
            val pelvis = zAngle(frame, Joint.PELVIS)
            val lumbar = zAngle(frame, Joint.LUMBAR)
            if (abs(lumbar - authoredTorso(p)) > angleBand) {
                wrong.add(
                    "p=$p pelvis ${f(pelvis)} + lumbar-local ${f(lumbar - pelvis)} sums to " +
                        "${f(lumbar)} rad, the authored total is ${f(authoredTorso(p))}"
                )
            }
        }
        assertTrue(
            "the split must not add range: the pelvis's articulation plus the LUMBAR's always equals " +
                "the pose's authored trunk angle (`1.5708 − 0.12·p`):\n" + wrong.take(6).joinToString("\n"),
            wrong.isEmpty()
        )

        // The rep's world arc is the authored one in FULL (0.12 rad) — the B4 trunk gate's own
        // quantity, re-derived here from the published rotations rather than from the model.
        val flat = Math.PI.toFloat()
        val maxSwing = frames.maxOf { (_, frame) ->
            val pel = frame.getJoint(Joint.PELVIS); val chest = frame.getJoint(Joint.CHEST)
            abs(normalizeRadians(atan2(chest.y - pel.y, chest.x - pel.x) - flat))
        }
        assertEquals(
            "the authored tilt arc must still be spent in full by the trunk chord (the split moves " +
                "the articulation, not the range); measured ${f(maxSwing)}",
            tiltAmplitude, maxSwing, 0.01f
        )
        assertTrue(
            "the low back must carry the MAJORITY of the arc (BPS §9: the pelvis's own rotation is " +
                "'only a few degrees'; the lumbar moves through its lordosis range)",
            abs(authoredLumbar(1f)) > abs(pelvisTiltShare * tiltAmplitude)
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3. The published trunk IS the split authoring (with the rigid-lumbar authoring as control)
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePublishedTrunkIsTheSplitAuthoringAndTheRigidLumbarIsTheControl() {
        val wrong = mutableListOf<String>()
        val controlExplains = mutableListOf<String>()

        for ((p, frame) in playingFrames()) {
            val pelvis = frame.getJoint(Joint.PELVIS)
            val chest = frame.getJoint(Joint.CHEST)
            val pelvisRot = zAngle(frame, Joint.PELVIS)
            val lumbarLocal = zAngle(frame, Joint.LUMBAR) - pelvisRot

            // The published chest is the pelvis's frame carried through the LUMBAR's articulation and
            // the authored trunk bone: chest = pelvis + Rz(pelvisRot + lumbarLocal)·(0, torsoLength, 0).
            val (ox, oy) = rot(0f, def.torsoLength, pelvisRot + lumbarLocal)
            if (abs(chest.x - (pelvis.x + ox)) > posBand || abs(chest.y - (pelvis.y + oy)) > posBand) {
                wrong.add(
                    "p=$p chest=(${f(chest.x)}, ${f(chest.y)}) vs the authored split model " +
                        "(${f(pelvis.x + ox)}, ${f(pelvis.y + oy)})"
                )
            }

            // Control: the pre-correction authoring (the whole arc on the PELVIS, a rigid lumbar) does
            // NOT reproduce this chest — the split is load-bearing, not a re-description. (Skipped at
            // the resting phase, where the authored arc is zero and the two authorings coincide by
            // construction.)
            if (abs(lumbarLocal) > angleBand) {
                val (cx, cy) = rot(0f, def.torsoLength, pelvisRot)
                if (abs(chest.x - (pelvis.x + cx)) < posBand && abs(chest.y - (pelvis.y + cy)) < posBand) {
                    controlExplains.add("p=$p the rigid-lumbar authoring also reproduces the chest")
                }
            }
        }
        assertTrue(
            "the published trunk must be the split authoring (the pelvis's frame + the low back's own " +
                "articulation + the authored bone):\n" + wrong.take(6).joinToString("\n"),
            wrong.isEmpty()
        )
        assertTrue(
            "control: the rigid-lumbar authoring (the whole arc on the pelvis) must NOT reproduce the " +
                "published trunk — it is the authoring this correction removes",
            controlExplains.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4. Everything else is the pose's own: placement, planted legs, hands, plane, neck
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePlacementTheLegsAndTheFloorBehaviourAreUnchanged() {
        val frames = playingFrames()
        val first = frames.first().second
        val wrong = mutableListOf<String>()
        val drifting = mutableListOf<String>()

        val feet = listOf(
            Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F,
            Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B
        )
        for ((p, frame) in frames) {
            val pelvis = frame.getJoint(Joint.PELVIS)
            if (abs(pelvis.x) > posBand || abs(pelvis.y - restingLayer) > posBand) {
                wrong.add("p=$p the pelvis must stay the pose's static base; measured (${f(pelvis.x)}, ${f(pelvis.y)})")
            }
            for (hand in listOf(Joint.HAND_A, Joint.HAND_P)) {
                val h = frame.getJoint(hand)
                if (abs(h.x - handX) > posBand || abs(h.y - handY) > posBand) {
                    wrong.add("p=$p $hand=(${f(h.x)}, ${f(h.y)}) against the authored ($handX, $handY)")
                }
            }
            for (joint in feet) if (dist(frame.getJoint(joint), first.getJoint(joint)) > posBand) {
                drifting.add("p=$p $joint moved (BPS §7: the legs remain quiet)")
            }
            for (joint in Joint.entries) {
                if (frame.getJoint(joint).y < ground - posBand) {
                    wrong.add("p=$p $joint y=${f(frame.getJoint(joint).y)} below the declared plane")
                }
            }
        }
        assertTrue("the pose's placement and planted hands must be its own:\n" + wrong.take(8).joinToString("\n"), wrong.isEmpty())
        assertTrue("the legs must stay quiet through the tilt:\n" + drifting.take(8).joinToString("\n"), drifting.isEmpty())

        // The neck's authored articulation still cancels the trunk's tilt exactly: the head chain
        // keeps the flat supine orientation its authoring intends (the B4 trunk correction's property).
        val wrongNeck = mutableListOf<String>()
        for ((p, frame) in frames) {
            val neckWorld = zAngle(frame, Joint.NECK_END)
            if (abs(neckWorld - supineTilt) > angleBand) {
                wrongNeck.add("p=$p the neck's world rotation is ${deg(neckWorld)}°, expected ${deg(supineTilt)}°")
            }
        }
        assertTrue(
            "the neck's authored counter-articulation must still hold the head chain flat:\n" +
                wrongNeck.take(6).joinToString("\n"),
            wrongNeck.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 5. Sagittal-only, and not frame-condition sensitive (the B-8 cold-frame class)
    // ------------------------------------------------------------------------------------------

    @Test
    fun theSplitIsSagittalAndFrameConditionInvariant() {
        val leaking = mutableListOf<String>()
        val drifting = mutableListOf<String>()
        val frameBand = 0.1f

        val cold = sweep.map { coldFrame(it) }
        val playing = playingFrames().map { it.second }

        for (i in sweep.indices) {
            for (joint in listOf(Joint.PELVIS, Joint.LUMBAR, Joint.CHEST, Joint.NECK_END, Joint.HEAD_POS,
                Joint.SHOULDER_A, Joint.SHOULDER_P, Joint.ELBOW_A, Joint.ELBOW_P, Joint.HAND_A, Joint.HAND_P)) {
                val d = dist(cold[i].getJoint(joint), playing[i].getJoint(joint))
                if (d > frameBand) drifting.add("p=${sweep[i]} $joint cold-vs-playing ${f(d)}")
                val r = playing[i].getJointRotation(joint)
                if (joint == Joint.PELVIS || joint == Joint.LUMBAR) {
                    if (abs(r.axis.x) > 1e-4f || abs(r.axis.y) > 1e-4f) {
                        leaking.add("p=${sweep[i]} $joint axis=(${f(r.axis.x)}, ${f(r.axis.y)}, ${f(r.axis.z)})")
                    }
                }
            }
            if (abs(zAngle(cold[i], Joint.LUMBAR) - zAngle(playing[i], Joint.LUMBAR)) > frameBand) {
                drifting.add("p=${sweep[i]} the LUMBAR's world rotation is frame-condition sensitive")
            }
            if (abs(zAngle(cold[i], Joint.PELVIS) - zAngle(playing[i], Joint.PELVIS)) > frameBand) {
                drifting.add("p=${sweep[i]} the PELVIS's rotation is frame-condition sensitive")
            }
        }

        assertTrue(
            "both articulations must be about the pose's own lateral (Z) axis:\n" + leaking.take(6).joinToString("\n"),
            leaking.isEmpty()
        )
        assertTrue(
            "the split's geometry must not be frame-condition sensitive (the B-8 cold-frame class):\n" +
                drifting.take(8).joinToString("\n"),
            drifting.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 6. The BPS's own wording, in numbers
    // ------------------------------------------------------------------------------------------

    @Test
    fun thePelvisKeepsAFewDegreesAndTheLowBackCarriesTheLordosisRange() {
        val end = playingFrames().last().second
        val pelvisArc = abs(zAngle(end, Joint.PELVIS) - supineTilt)
        val lumbarArc = abs(zAngle(end, Joint.LUMBAR) - zAngle(end, Joint.PELVIS))

        assertTrue(
            "BPS §9: the pelvis's own rotation is 'only a few degrees' — measured ${deg(pelvisArc)}°",
            pelvisArc <= Math.toRadians(3.0).toFloat()
        )
        assertTrue(
            "BPS §9/§5: the low back moves through its lordosis range — the LUMBAR must carry the " +
                "larger share (measured ${deg(lumbarArc)}° against the pelvis's ${deg(pelvisArc)}°)",
            lumbarArc > pelvisArc
        )
        assertTrue(
            "anti-vacuity: the arc must be a real rep (measured pelvis ${deg(pelvisArc)}°, low back " +
                "${deg(lumbarArc)}°)",
            pelvisArc > 0.02f && lumbarArc > 0.05f
        )
    }
}
