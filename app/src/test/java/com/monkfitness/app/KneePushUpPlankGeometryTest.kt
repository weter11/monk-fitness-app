package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.KneePushUpPose
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * B-1 regression gate — the **published frame** of the kneeling push-up must realize the plank
 * geometry `PushUpPlank.solve` declares for the `PivotType.KNEES` pivot.
 *
 * Why this test exists (P11 whole-system audit §B-1, `docs/AUDIT_P11_WHOLE_SYSTEM.md`):
 * `PushUpPlank.solve` computes `kneeHeight`, `kneeX` and `pelvisHeight` for the knee pivot, and
 * `BasePushUpPose.onBuild` published a frame that used none of them — the KNEES branch placed the
 * knee from `ankleHeight` with a pure-X offset instead. The measured produced frame therefore had
 * `KNEE_F.y = 84.30` (the ankle height, 69.30 above the declared knee height 15), a horizontal
 * shin (0° instead of the declared `SHIN_PITCH_ANGLE = 45°`), the pelvis ~129u above the solver's
 * declared plank height, and `|shoulder→hand|` pinned at the clamped maximum at *every* progress
 * (the elbow never flexed: the target was out of reach, so `clampTargetToReach` relocated it).
 *
 * The instruments that stayed green while production was broken, and why this one does not:
 * * `PushUpPlankTest` asserts the solver's own outputs (`kneeHeight == 15`) — that value never
 *   reached the skeleton, so the assertion was true about a dead computation.
 * * `EnvironmentPenetrationTest` covers this pose but asserts penetration only (`y < surface - 2`),
 *   and it explicitly disclaims float — a knee 84u in the air is not a penetration.
 * * `PushUpMotionTest` asserts chest travel, which the broken trunk pitch supplied on its own.
 *
 * Every assertion below is made on the frame the production pipeline publishes (`produceFrame`) and
 * is compared against either the solver's own declared geometry or the engine's own reach band —
 * never against a re-derivation of the pose's internals, and never against solver-only values.
 */
class KneePushUpPlankGeometryTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val pose = KneePushUpPose()
    private val support = pose.metadata.support
    private val samples = arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** Declared floor contact height for the knee pivot (solver constant + support elevation). */
    private val declaredKneeHeight = PushUpPlank.BASE_KNEE_HEIGHT + support.supportHeight

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /**
     * Frames the engine actually publishes, sampled across the rep (pipeline warmed first).
     *
     * Each frame is snapshotted: `produceFrame` publishes a REUSED buffer, so holding the returned
     * poses aliases every sample to the last frame (the trap documented in the pose-QA notes).
     */
    private fun publishedFrames(): List<Pair<Float, SkeletonPose>> {
        val pipe = SkeletonPipeline(def)
        for (k in 0..10) pipe.produceFrame(pose.build(ctx(0.3f)))
        return samples.map { p ->
            val snapshot = SkeletonPose()
            snapshot.copyFrom(pipe.produceFrame(pose.build(ctx(p))).pose)
            p to snapshot
        }
    }

    /** The geometry the solver declares for this pivot, sampled at the top of the rep. */
    private fun solverTop() = PushUpPlank.solve(def, support, pose.gripWidthMultiplier, 0f, PushUpPlankResult())

    private fun solverBottom() = PushUpPlank.solve(def, support, pose.gripWidthMultiplier, 1f, PushUpPlankResult())

    private fun dist(a: Vector3, b: Vector3) = sqrt(
        (a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2)
    )

    /** Elevation of the segment a→b off the horizontal, magnitude only (0° = horizontal). */
    private fun pitchDeg(a: Vector3, b: Vector3) = Math.toDegrees(atan2(abs(b.y - a.y), abs(b.x - a.x)).toDouble()).toFloat()

    /** Interior angle at [vertex] for the effector chain [a]→[vertex]→[b]. */
    private fun interiorAngleDeg(a: Vector3, vertex: Vector3, b: Vector3): Float {
        val l1 = dist(a, vertex); val l2 = dist(vertex, b); val base = dist(a, b)
        return Math.toDegrees(
            acos(((l1 * l1 + l2 * l2 - base * base) / (2f * l1 * l2)).coerceIn(-1f, 1f).toDouble())
        ).toFloat()
    }

    /** Signed perpendicular offset of [p] from the infinite line a→b, in the sagittal (XY) plane. */
    private fun sagittalLineDeviation(a: Vector3, b: Vector3, p: Vector3): Float {
        val vx = b.x - a.x; val vy = b.y - a.y
        val len = sqrt(vx * vx + vy * vy)
        if (len < 1e-4f) return 0f
        return (vx * (p.y - a.y) - vy * (p.x - a.x)) / len
    }

    /** p=0 is the declared top of the rep: the solver's own top-of-rep ratios apply there. */
    private fun frameAtTop() = publishedFrames().first().second

    // ---------------------------------------------------------------------------------------
    // 1. The pivot contact is ON its declared support surface (the B-1 core).
    // ---------------------------------------------------------------------------------------
    @Test
    fun kneesRestOnTheirDeclaredSupportSurface() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((p, f) in publishedFrames()) {
            val kneeF = f.getJoint(Joint.KNEE_F)
            val kneeB = f.getJoint(Joint.KNEE_B)
            rows.add("p=%.2f KNEE_F.y=%.2f KNEE_B.y=%.2f (declared %.2f)".format(p, kneeF.y, kneeB.y, declaredKneeHeight))
            if (abs(kneeF.y - declaredKneeHeight) > 2f) {
                failures.add("KNEE_F.y=%.2f at p=%.2f is %.2f off the declared support surface (%.2f)"
                    .format(kneeF.y, p, kneeF.y - declaredKneeHeight, declaredKneeHeight))
            }
            if (abs(kneeB.y - declaredKneeHeight) > 2f) {
                failures.add("KNEE_B.y=%.2f at p=%.2f is %.2f off the declared support surface (%.2f)"
                    .format(kneeB.y, p, kneeB.y - declaredKneeHeight, declaredKneeHeight))
            }
        }
        // The knee is the pivot: its sagittal placement is the solver's declared kneeX, not a
        // reconstruction from the ankle height. Checked at the top of the rep, where the solver's
        // declared top-of-rep values are the unambiguous oracle.
        val top = frameAtTop()
        val solverKneeX = solverTop().kneeX
        val kneeFx = top.getJoint(Joint.KNEE_F).x
        rows.add("top KNEE_F.x=%.2f (solver kneeX=%.2f, delta=%.2f)".format(kneeFx, solverKneeX, kneeFx - solverKneeX))
        if (abs(kneeFx - solverKneeX) > 2f) {
            failures.add("top-of-rep KNEE_F.x=%.2f does not realize the solver's declared kneeX=%.2f (delta %.2f)"
                .format(kneeFx, solverKneeX, kneeFx - solverKneeX))
        }
        assertTrue(
            "Kneeling push-up knees are not planted at the declared support height:\n" +
                failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2. The pelvis realizes the solver's declared plank height (the previously dead output).
    // ---------------------------------------------------------------------------------------
    @Test
    fun pelvisRealizesTheSolverDeclaredPlankHeight() {
        val solverTopHeight = solverTop().pelvisHeight
        val solverRom = solverTopHeight - solverBottom().pelvisHeight
        val frames = publishedFrames()
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val topPelvisY = frames.first().second.getJoint(Joint.PELVIS).y
        rows.add("top PELVIS.y=%.2f (solver pelvisHeight=%.2f)".format(topPelvisY, solverTopHeight))
        if (abs(topPelvisY - solverTopHeight) > 2f) {
            failures.add("top-of-rep PELVIS.y=%.2f does not realize the solver's declared pelvisHeight=%.2f (delta %.2f)"
                .format(topPelvisY, solverTopHeight, topPelvisY - solverTopHeight))
        }

        // The rep must descend at least the ROM the solver declares (the pose may descend further —
        // the arm-driven depth — but realizing less means the declared plank geometry is not consumed).
        val pelvisMin = frames.minOf { it.second.getJoint(Joint.PELVIS).y }
        val realisedRom = topPelvisY - pelvisMin
        rows.add("realised pelvis ROM=%.2f (solver declares %.2f)".format(realisedRom, solverRom))
        if (realisedRom < solverRom * 0.8f) {
            failures.add("realised pelvis ROM=%.2f is below 80%% of the solver's declared ROM=%.2f"
                .format(realisedRom, solverRom))
        }

        assertTrue(
            "Kneeling push-up pelvis does not follow the solver's declared plank geometry:\n" +
                failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------
    // 3. The shins hold the declared kneeling pitch (the engine's own SHIN_PITCH_ANGLE).
    // ---------------------------------------------------------------------------------------
    @Test
    fun shinsHoldTheDeclaredKneelingPitch() {
        val declaredPitch = Math.toDegrees(PushUpPlank.SHIN_PITCH_ANGLE.toDouble()).toFloat()
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((p, f) in publishedFrames()) {
            val shinF = pitchDeg(f.getJoint(Joint.ANKLE_F), f.getJoint(Joint.KNEE_F))
            val shinB = pitchDeg(f.getJoint(Joint.ANKLE_B), f.getJoint(Joint.KNEE_B))
            rows.add("p=%.2f shinF=%.2f° shinB=%.2f° (declared %.2f°)".format(p, shinF, shinB, declaredPitch))
            if (abs(shinF - declaredPitch) > 2f) {
                failures.add("front shin pitch %.2f° at p=%.2f is not the declared %.2f°".format(shinF, p, declaredPitch))
            }
            if (abs(shinB - declaredPitch) > 2f) {
                failures.add("mirror shin pitch %.2f° at p=%.2f is not the declared %.2f°".format(shinB, p, declaredPitch))
            }
        }
        assertTrue(
            "Kneeling push-up shins do not hold the declared shin pitch:\n" +
                failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------
    // 4. The hands are on the floor and the arm chain stays inside its reachable band — i.e. the
    //    pressed hand target is NOT silently relocated by clampTargetToReach.
    // ---------------------------------------------------------------------------------------
    @Test
    fun handsRestOnTheFloorInsideTheReachableBand() {
        val lo = SkeletonMath.minReach(def.upperArmLength, def.forearmLength, def.armIKConstraint) * 1.02f
        val hi = SkeletonMath.maxReach(def.upperArmLength, def.forearmLength, def.armIKConstraint) * 0.98f
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((p, f) in publishedFrames()) {
            val handA = f.getJoint(Joint.HAND_A); val handP = f.getJoint(Joint.HAND_P)
            val shoulderA = f.getJoint(Joint.SHOULDER_A); val shoulderP = f.getJoint(Joint.SHOULDER_P)
            val dA = dist(shoulderA, handA); val dP = dist(shoulderP, handP)
            rows.add("p=%.2f HAND_A.y=%.2f HAND_P.y=%.2f |sh->hand|A=%.2f P=%.2f (band %.2f..%.2f)"
                .format(p, handA.y, handP.y, dA, dP, lo, hi))
            for ((name, hand) in listOf("HAND_A" to handA, "HAND_P" to handP)) {
                if (abs(hand.y) > 2f) {
                    failures.add("$name.y=%.2f at p=%.2f is not on its declared floor contact".format(hand.y, p))
                }
            }
            for ((name, d) in listOf("A" to dA, "P" to dP)) {
                if (d < lo + 2f || d > hi - 2f) {
                    failures.add("|shoulder$name->hand$name|=%.2f at p=%.2f is on/over the reachable-band edge (%.2f..%.2f) — the authored target was relocated"
                        .format(d, p, lo, hi))
                }
            }
        }
        assertTrue(
            "Kneeling push-up hands/arm chain are not expressible on the floor:\n" +
                failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------
    // 5. The rep actually presses (elbow travels) and the body moves between top and bottom.
    // ---------------------------------------------------------------------------------------
    @Test
    fun repExpressesTheElbowPressAndMovesBetweenTopAndBottom() {
        val pipe = SkeletonPipeline(def)
        for (k in 0..10) pipe.produceFrame(pose.build(ctx(0.3f)))
        // Single pass: read the numbers out as primitives (produceFrame returns a reused buffer).
        var elbowTop = Float.NaN; var elbowBottom = Float.NaN
        var chestTop = Float.NaN; var chestMin = Float.MAX_VALUE; var chestMax = -Float.MAX_VALUE
        for (p in samples) {
            val f = pipe.produceFrame(pose.build(ctx(p))).pose
            val elbow = interiorAngleDeg(f.getJoint(Joint.SHOULDER_A), f.getJoint(Joint.ELBOW_A), f.getJoint(Joint.HAND_A))
            val chestY = f.getJoint(Joint.CHEST).y
            chestMin = minOf(chestMin, chestY); chestMax = maxOf(chestMax, chestY)
            if (p == 0f) { elbowTop = elbow; chestTop = chestY }
            if (p == 0.5f) elbowBottom = elbow
        }
        val elbowTravel = elbowTop - elbowBottom
        val chestTravel = chestMax - chestMin
        val msg = "elbow interior top=%.2f° bottom=%.2f° travel=%.2f° | chest y top=%.2f min=%.2f max=%.2f travel=%.2f" +
            " (family motion contract requires chest travel >= 40u)"
        val diag = msg.format(elbowTop, elbowBottom, elbowTravel, chestTop, chestMin, chestMax, chestTravel)

        assertTrue(
            "Kneeling push-up does not press — the elbow does not flex through the rep: $diag",
            elbowTravel >= 15f
        )
        assertTrue(
            "Kneeling push-up top/bottom frames are not meaningfully different: $diag",
            chestTravel >= 40f
        )
    }

    // ---------------------------------------------------------------------------------------
    // 6. The top of the rep is the declared kneeling plank: one straight knee→hip→shoulder line
    //    (BPS §3/§7/§11 — the femur is in line with the trunk at the top).
    // ---------------------------------------------------------------------------------------
    @Test
    fun topOfRepIsTheDeclaredStraightKneeHipShoulderLine() {
        val top = frameAtTop()
        val knee = top.getJoint(Joint.KNEE_F)
        val hip = top.getJoint(Joint.HIP_F)
        val chest = top.getJoint(Joint.CHEST)
        val deviation = sagittalLineDeviation(knee, chest, hip)
        assertTrue(
            "Top-of-rep kneeling plank is not a straight knee→hip→shoulder line: HIP_F deviates %.2f from the KNEE_F→CHEST line (knee=(%.2f,%.2f) hip=(%.2f,%.2f) chest=(%.2f,%.2f))"
                .format(deviation, knee.x, knee.y, hip.x, hip.y, chest.x, chest.y),
            abs(deviation) <= 2f
        )
    }

    // ---------------------------------------------------------------------------------------
    // 7. The rep closes at the loop seam. The pose loops (`LoopMode.LOOP`) over a full-cycle depth
    //    curve, so the top of the rep published at progress 0 and at progress 1 must be the same
    //    plank: the solver's offsets are declared as top-of-rep / bottom-of-rep ratios, and feeding
    //    it the linear animation phase instead of the rep's depth phase pops the chest at the seam.
    // ---------------------------------------------------------------------------------------
    @Test
    fun repClosesAtTheLoopSeam() {
        val frames = publishedFrames()
        val seam = frames.first().second
        val wrap = frames.last().second
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (j in listOf(
            Joint.KNEE_F, Joint.KNEE_B, Joint.PELVIS, Joint.ANKLE_F, Joint.ANKLE_B, Joint.CHEST, Joint.HAND_A
        )) {
            val a = seam.getJoint(j); val b = wrap.getJoint(j)
            val d = dist(a, b)
            rows.add("%s p=0 (%.2f,%.2f,%.2f) vs p=1 (%.2f,%.2f,%.2f) delta=%.3f".format(j.name, a.x, a.y, a.z, b.x, b.y, b.z, d))
            if (d > 1f) {
                failures.add("$j differs by %.3f between the top of the rep at p=0 and p=1 (LOOP seam pop)".format(d))
            }
        }
        assertTrue(
            "Kneeling push-up does not close its loop:\n" + failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }
}
