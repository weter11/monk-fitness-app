package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * M6 (KettlebellSwing) + M7 (Burpee) — the two poses' rep geometry, gated on the PUBLISHED frame.
 *
 * Both findings are the same defect class, measured rather than inferred: the phase authoring
 * placed limb targets that the rig's own chains cannot realize, so the solver's reachable-length
 * clamp (`SkeletonMath.solveIK` -> `clampTargetToReach`) relocated every effector it was handed.
 *
 * * **M6.** `pelvisY = lerp(175, 210)` against a 20-unit backward travel forced the hip->ankle
 *   span down to `166.57` of the 210-unit leg — **75° of knee flexion at the hinge bottom**, the
 *   squat the BPS calls the swing's first mistake — and the hand target, lerped in world space to
 *   "chest height", left only `41.0` units of shoulder->hand distance at the top, so the elbow
 *   folded onto the solver's minimum-flexion stop (`30.0°` interior = 150° of flexion) with the
 *   elbow flared `80.61` units sideways.
 * * **M7.** The squat authored `pelvisY = 35` with the ankle target at `15` — inside the knee's
 *   minimum reach (`55.99`) — so the leg IK pushed the foot along its own ray and the published
 *   ankle/heel/toe sat `-7.88 … -21.01` units UNDER the declared ground; the "plank" put the feet
 *   `65` units behind the hips (the leg is 210) and the hands `48.9` behind the shoulders, so the
 *   published plank was a tucked crouch (knee interior `37.40°`, elbow `57.53°`, hips `14` off the
 *   straight shoulder->ankle line) and the jump-top had the arms folded below the shoulders.
 *
 * Every assertion below reads the pipeline's published frame (`SkeletonPipeline.produceFrame`, the
 * production entry point), snapshotted BY VALUE — `produceFrame(...).pose` is the Finalizer's reused
 * output buffer and collecting its references aliases every sample to the last frame.
 */
class M6M7SwingBurpeeGeometryTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val ground = 0f

    /** The rep sweep (includes both phase seams at 0 / 1 and every planted-phase boundary). */
    private val sweep = (0..20).map { it / 20f }

    private val armSpan = (def.upperArmLength + def.forearmLength) * def.armIKConstraint.effectiveExtensionRatio
    private val armMinSpan = minReach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
    private val legMinSpan = minReach(def.thighLength, def.shinLength, def.legIKConstraint)
    private val legMaxSpan = (def.thighLength + def.shinLength) * def.legIKConstraint.effectiveExtensionRatio

    // -------------------------------------------------------------------------------------------
    // Published-frame harness
    // -------------------------------------------------------------------------------------------

    /**
     * One warmed pipeline per pose, sampled in ascending order (the production playback order a
     * renderer drives), each frame copied out by value. Warming matches `MotionProbe`: the pipeline
     * has a cold-start fixed point, and only warmed frames are asserted.
     */
    private fun publish(pb: PoseBuilder): List<SkeletonPose> {
        val pipe = SkeletonPipeline(def)
        for (k in 0..10) pipe.produceFrame(pb, context(0.3f))
        return sweep.map { p ->
            SkeletonPose().also { it.copyFrom(pipe.produceFrame(pb, context(p)).pose) }
        }
    }

    private fun context(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun joint(f: SkeletonPose, j: Joint) = f.getJoint(j)

    /** Interior angle at [b] in degrees. */
    private fun interior(a: Vector3, b: Vector3, c: Vector3): Float {
        val u = Vector3().set(a).subtract(b)
        val w = Vector3().set(c).subtract(b)
        val m = u.mag() * w.mag()
        if (m < 1e-6f) return 0f
        return Math.toDegrees(acos((u.dot(w) / m).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /** Inclination of a -> b from the vertical, degrees. */
    private fun tilt(a: Vector3, b: Vector3): Float {
        val d = Vector3().set(b).subtract(a)
        val m = d.mag()
        if (m < 1e-6f) return 0f
        return Math.toDegrees(acos((d.y / m).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /** Perpendicular distance from point p to the infinite line a -> b. */
    private fun offLine(a: Vector3, b: Vector3, p: Vector3): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) return 0f
        return abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
    }

    private fun minReach(l1: Float, l2: Float, c: IKConstraint): Float {
        val minCos = cos(c.minimumFlexionAngle * PI.toFloat() / 180f)
        return sqrt(l1 * l1 + l2 * l2 - 2f * l1 * l2 * minCos)
    }

    private fun List<SkeletonPose>.indexOfMostHinged(pb: Joint): Int = indices.maxByOrNull {
        tilt(joint(this[it], Joint.PELVIS), joint(this[it], Joint.CHEST))
    }!!

    private fun List<SkeletonPose>.indexOfLeastHinged(): Int = indices.minByOrNull {
        tilt(joint(this[it], Joint.PELVIS), joint(this[it], Joint.CHEST))
    }!!

    // -------------------------------------------------------------------------------------------
    // M6 — KettlebellSwing
    // -------------------------------------------------------------------------------------------

    /**
     * BPS §7 "knees bend only slightly (the shin angle barely changes)" / §9 "Knee flexion: only
     * slight (a few degrees more than the top) — this distinguishes the swing from a squat" /
     * §12 mistake #1 ("squatting instead of hinging").
     */
    @Test
    fun kettlebellSwingHingeBendsTheKneesOnlySlightly() {
        val frames = publish(KettlebellSwingPose())
        val knee = frames.map { interior(joint(it, Joint.HIP_F), joint(it, Joint.KNEE_F), joint(it, Joint.ANKLE_F)) }
        val minKnee = knee.min()
        assertTrue(
            "the swing's knees must stay nearly straight through the whole rep (BPS §7/§9); the " +
                "hinge bottom is the swing, not a squat. measured minimum knee interior = ${minKnee.fmt()}° " +
                "at p=${sweep[knee.indexOf(minKnee)]} (pre-fix 104.77° at the hinge)",
            minKnee >= 140f
        )
        val hinged = frames.indexOfMostHinged(Joint.PELVIS)
        val top = frames.indexOfLeastHinged()
        val delta = abs(knee[hinged] - knee[top])
        assertTrue(
            "knee flexion at the hinge may exceed the top's by only a few degrees (BPS §9): measured " +
                "${knee[hinged].fmt()}° at the hinge vs ${knee[top].fmt()}° at the top (delta ${delta.fmt()}°, " +
                "pre-fix 40.19°)",
            delta <= 12f
        )
    }

    /**
     * BPS §5/§7 "the hinge is at the hips: the torso folds forward over the pelvis while the hips
     * push back" — the pelvis's path must be dominated by BACKWARD travel, not by a vertical drop
     * (which is what forces the knee bend above).
     */
    @Test
    fun kettlebellSwingHipsPushBackInsteadOfDropping() {
        val frames = publish(KettlebellSwingPose())
        val hinged = frames.indexOfMostHinged(Joint.PELVIS)
        val top = frames.indexOfLeastHinged()
        val pelHinge = joint(frames[hinged], Joint.PELVIS)
        val pelTop = joint(frames[top], Joint.PELVIS)
        val back = abs(pelHinge.x - pelTop.x)
        val drop = abs(pelTop.y - pelHinge.y)
        assertTrue(
            "the hips must push BACK (BPS §7): measured ${back.fmt()} units of backward travel vs " +
                "${drop.fmt()} units of vertical drop (pre-fix 20.0 back vs 35.0 down — the profile " +
                "the audit calls inverted, and what collapsed the leg to 166.57 of 210)",
            back >= 45f && back >= 3f * drop
        )
    }

    /**
     * BPS §6 "the arms … with straight elbows; they are relaxed conduits, not active lifters" /
     * §9 "arms travel from low (between legs) to ~horizontal/chest height" / §11 "The arms are
     * straight — they do not curl or press the load" / §12 ("using the arms to curl/press the load
     * up").
     */
    @Test
    fun kettlebellSwingArmsStayAStraightPendulum() {
        val frames = publish(KettlebellSwingPose())
        for ((i, f) in frames.withIndex()) {
            val elbow = interior(joint(f, Joint.SHOULDER_A), joint(f, Joint.ELBOW_A), joint(f, Joint.HAND_A))
            val span = Vector3().set(joint(f, Joint.HAND_A)).subtract(joint(f, Joint.SHOULDER_A)).mag()
            assertTrue(
                "p=${sweep[i]}: the swing's arms are a straight strap (BPS §6/§11) — measured elbow " +
                    "interior ${elbow.fmt()}° and a shoulder->hand span of ${span.fmt()} of the " +
                    "${(def.upperArmLength + def.forearmLength).fmt()}-unit arm (pre-fix: 30.00° / 40.13 at the top)",
                elbow >= 140f && span >= 0.95f * (def.upperArmLength + def.forearmLength)
            )
        }
    }

    /**
     * The load's path: behind the knees at the hinge bottom (BPS §7/§8/§11), riding at chest height
     * in front of the body at the top (§9 "~horizontal/chest height", §11 "load at chest/eye
     * height … driven by the hips, not an arm raise").
     */
    @Test
    fun kettlebellSwingLoadTravelsFromBehindTheKneesToChestHeight() {
        val frames = publish(KettlebellSwingPose())
        val hinged = frames.indexOfMostHinged(Joint.PELVIS)
        val top = frames.indexOfLeastHinged()
        val handH = joint(frames[hinged], Joint.HAND_A)
        val kneeH = joint(frames[hinged], Joint.KNEE_F)
        assertTrue(
            "the load swings BEHIND the knees at the bottom (BPS §8/§11): measured hand x " +
                "${handH.x.fmt()} vs knee x ${kneeH.x.fmt()}",
            handH.x <= kneeH.x
        )
        val handT = joint(frames[top], Joint.HAND_A)
        val chestT = joint(frames[top], Joint.CHEST)
        assertTrue(
            "the load rides at chest height in front of the body at the top (BPS §9/§11): measured " +
                "hand (${handT.x.fmt()}, ${handT.y.fmt()}) vs chest y ${chestT.y.fmt()} (pre-fix: the " +
                "hand was 40.0 in front at chest height but with the elbow folded to its stop)",
            handT.x >= 100f && abs(handT.y - chestT.y) <= 45f
        )
    }

    /** BPS §5 "the spine stays neutral … the trunk is rigid" — the trunk is one rigid segment. */
    @Test
    fun kettlebellSwingTrunkStaysRigid() {
        val frames = publish(KettlebellSwingPose())
        for ((i, f) in frames.withIndex()) {
            val trunk = Vector3().set(joint(f, Joint.CHEST)).subtract(joint(f, Joint.PELVIS)).mag()
            assertEquals(
                "p=${sweep[i]}: the trunk is a rigid segment (BPS §5) — pelvis->chest must stay the " +
                    "definition's torso length",
                def.torsoLength.toDouble(), trunk.toDouble(), 0.5
            )
        }
        val hinged = frames.indexOfMostHinged(Joint.PELVIS)
        val top = frames.indexOfLeastHinged()
        val hingeTilt = tilt(joint(frames[hinged], Joint.PELVIS), joint(frames[hinged], Joint.CHEST))
        val topTilt = tilt(joint(frames[top], Joint.PELVIS), joint(frames[top], Joint.CHEST))
        assertTrue(
            "the hinge bottom folds the trunk ~45°+ (BPS §5) and the top returns it upright: measured " +
                "${hingeTilt.fmt()}° at the hinge, ${topTilt.fmt()}° at the top",
            hingeTilt >= 45f && topTilt <= 8f
        )
    }

    // -------------------------------------------------------------------------------------------
    // M7 — Burpee
    // -------------------------------------------------------------------------------------------

    /**
     * The published feet must never be under the declared ground. Pre-fix the authored ankle target
     * (`pelvisY = 35` with the ankle at `15`) sat inside the knee's minimum reach (`55.99`), so the
     * leg IK pushed the effector out along its ray and the ankle/heel/toe published `-7.88 … -21.01`.
     */
    @Test
    fun burpeeFeetNeverSinkThroughTheirFloor() {
        val frames = publish(BurpeePose())
        for ((i, f) in frames.withIndex()) {
            for (j in listOf(Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B)) {
                val y = joint(f, j).y
                assertTrue(
                    "p=${sweep[i]}: ${j.name} is ${y.fmt()} — under the pose's own ground level " +
                        "(${ground.fmt()}); a foot authored inside the leg's minimum reach is pushed " +
                        "there by the solver (pre-fix minimum -21.01)",
                    y >= ground - 0.5f
                )
            }
        }
    }

    /** BPS §8: "Plank/push-up: hands (palms) on the floor"; the plant must be a fixed contact. */
    @Test
    fun burpeeHandsStayPlantedWhileTheyBearThePlank() {
        val frames = publish(BurpeePose())
        val planted = sweep.indices.filter { sweep[it] >= 0.2f - 1e-4f && sweep[it] <= 0.8f + 1e-4f }
        val anchor = joint(frames[planted.first()], Joint.HAND_A)
        for (i in planted) {
            val h = joint(frames[i], Joint.HAND_A)
            assertEquals("p=${sweep[i]}: the planted hand rests on the floor", ground.toDouble(), h.y.toDouble(), 0.5)
            assertEquals("p=${sweep[i]}: the planted hand must not slide in x", anchor.x.toDouble(), h.x.toDouble(), 0.5)
            assertEquals("p=${sweep[i]}: the planted hand must not slide in z", anchor.z.toDouble(), h.z.toDouble(), 0.5)
        }
    }

    /**
     * BPS §3 "Plank: straight line head-to-heels, hands and toes on floor" / §11 "The plank is a
     * straight head-to-heel line, hands under shoulders". Measured on the planted window (the
     * plank proper is p = 0.4 … 0.6; the transitions are asserted only for the line's straightness).
     */
    @Test
    fun burpeePlankIsAStraightExtendedLineUnderTheShoulders() {
        val frames = publish(BurpeePose())
        for (p in listOf(0.4f, 0.6f)) {
            val i = sweep.indexOfFirst { abs(it - p) < 1e-4f }
            val f = frames[i]
            val sh = joint(f, Joint.SHOULDER_A); val hip = joint(f, Joint.HIP_F); val ank = joint(f, Joint.ANKLE_F)
            val knee = interior(joint(f, Joint.HIP_F), joint(f, Joint.KNEE_F), joint(f, Joint.ANKLE_F))
            val elbow = interior(joint(f, Joint.SHOULDER_A), joint(f, Joint.ELBOW_A), joint(f, Joint.HAND_A))
            val hipOff = offLine(sh, ank, hip)
            assertTrue(
                "p=$p: the plank's body must be one straight line (BPS §3/§11) — measured hip " +
                    "${hipOff.fmt()} units off the shoulder->ankle line (pre-fix 14.02)",
                hipOff <= 5f
            )
            assertTrue(
                "p=$p: the plank's legs are extended (BPS §3) — measured knee interior ${knee.fmt()}° " +
                    "(pre-fix 37.40°)",
                knee >= 140f
            )
            assertTrue(
                "p=$p: the plank's arms are extended (BPS §3/§6) — measured elbow interior " +
                    "${elbow.fmt()}° (pre-fix 57.53°)",
                elbow >= 130f
            )
            assertTrue(
                "p=$p: the hands are UNDER the shoulders in the plank (BPS §11) — measured shoulder x " +
                    "${sh.x.fmt()} vs hand x ${joint(f, Joint.HAND_A).x.fmt()} (pre-fix 48.9 apart)",
                abs(sh.x - joint(f, Joint.HAND_A).x) <= 15f
            )
            val hand = joint(f, Joint.HAND_A)
            assertTrue(
                "p=$p: the plank is a full body length, feet a leg-span behind the hands — measured " +
                    "${(hand.x - ank.x).fmt()} units (pre-fix 135.0; the leg alone is " +
                    "${(def.thighLength + def.shinLength).fmt()})",
                hand.x - ank.x >= 250f
            )
        }
    }

    /**
     * The optional push-up (BPS §6 "chest lowers toward the floor and presses back up") is the one
     * sampled phase where the elbows SHOULD bend — but the body stays one straight line and both
     * planted contacts stay put (a pose that lowers the hips alone kinks the plank).
     */
    @Test
    fun burpeePushUpDipBendsTheElbowsWithBothPlantsHeld() {
        val frames = publish(BurpeePose())
        val plank = frames[sweep.indexOf(0.4f)]
        val dip = frames[sweep.indexOf(0.5f)]
        val elbow = interior(joint(dip, Joint.SHOULDER_A), joint(dip, Joint.ELBOW_A), joint(dip, Joint.HAND_A))
        assertTrue(
            "the push-up bottom bends the elbows (BPS §6): measured elbow interior ${elbow.fmt()}° " +
                "(the plank's is ${interior(joint(plank, Joint.SHOULDER_A), joint(plank, Joint.ELBOW_A), joint(plank, Joint.HAND_A)).fmt()}°)",
            elbow in 60f..125f
        )
        assertTrue(
            "the dip lowers the body (BPS §6): measured shoulder y ${joint(dip, Joint.SHOULDER_A).y.fmt()} " +
                "vs the plank's ${joint(plank, Joint.SHOULDER_A).y.fmt()}",
            joint(dip, Joint.SHOULDER_A).y <= joint(plank, Joint.SHOULDER_A).y - 20f
        )
        val hipOff = offLine(
            joint(dip, Joint.SHOULDER_A), joint(dip, Joint.ANKLE_F), joint(dip, Joint.HIP_F)
        )
        assertTrue("the dip keeps the body a straight line: measured hip ${hipOff.fmt()} units off the line", hipOff <= 5f)
        for (j in listOf(Joint.HAND_A, Joint.ANKLE_F, Joint.TOE_F)) {
            val d = Vector3().set(joint(dip, j)).subtract(joint(plank, j)).mag()
            assertTrue("the dip must not move the planted ${j.name}: measured ${d.fmt()} units", d <= 0.5f)
        }
    }

    /** BPS §7 "Feet move together on the jump-back/forward transitions" — and they really move. */
    @Test
    fun burpeeFeetShootBackAFullPlankLength() {
        val frames = publish(BurpeePose())
        val atSquat = joint(frames[sweep.indexOf(0.2f)], Joint.ANKLE_F)
        val atPlank = joint(frames[sweep.indexOf(0.4f)], Joint.ANKLE_F)
        val travel = abs(atPlank.x - atSquat.x)
        assertTrue(
            "the kick-back moves the feet a plank's worth back (BPS §1/§7), not the 110 units the " +
                "pre-fix pose translated: measured ${travel.fmt()}",
            travel >= 180f
        )
        val f = frames[sweep.indexOf(0.5f)]
        assertEquals(
            "the feet stay together in the plank (BPS §7)",
            joint(f, Joint.ANKLE_F).x.toDouble(), joint(f, Joint.ANKLE_B).x.toDouble(), 0.5
        )
    }

    /**
     * The squat/hands-down bottom (BPS §3 "hips hinge and knees flex, hands reach the floor"). Its
     * leg span must stay inside the solver's reachable annulus, whatever the authored depth is: a
     * span inside the knee's minimum reach is exactly what relocated the feet pre-fix.
     */
    @Test
    fun burpeeSquatBottomStaysInsideTheLegsReachableSpan() {
        val frames = publish(BurpeePose())
        for (p in listOf(0.0f, 0.2f, 0.8f)) {
            val f = frames[sweep.indexOfFirst { abs(it - p) < 1e-4f }]
            val span = Vector3().set(joint(f, Joint.ANKLE_F)).subtract(joint(f, Joint.HIP_F)).mag()
            assertTrue(
                "p=$p: the authored hip->ankle span must sit inside the solver's reachable annulus " +
                    "(${legMinSpan.fmt()} … ${legMaxSpan.fmt()}) — otherwise the solver relocates the " +
                    "foot; measured ${span.fmt()} (pre-fix 56.01 at p=0.2 / 125.00 at p=0)",
                span >= legMinSpan * 1.02f && span <= legMaxSpan * 0.995f
            )
            val knee = interior(joint(f, Joint.HIP_F), joint(f, Joint.KNEE_F), joint(f, Joint.ANKLE_F))
            assertTrue(
                "p=$p: the squat's knees flex, they do not collapse onto the solver's minimum " +
                    "(${def.legIKConstraint.minimumFlexionAngle.fmt()}°) — measured ${knee.fmt()}° " +
                    "(pre-fix 30.00° at p=0.2)",
                knee >= def.legIKConstraint.minimumFlexionAngle + 5f
            )
        }
    }

    /** BPS §3/§11 "The jump-top achieves full hip/knee/ankle extension with arms overhead". */
    @Test
    fun burpeeJumpTopIsAFullExtensionWithTheArmsOverhead() {
        val frames = publish(BurpeePose())
        val f = frames[sweep.indexOf(0.9f)]
        val knee = interior(joint(f, Joint.HIP_F), joint(f, Joint.KNEE_F), joint(f, Joint.ANKLE_F))
        val elbow = interior(joint(f, Joint.SHOULDER_A), joint(f, Joint.ELBOW_A), joint(f, Joint.HAND_A))
        val hand = joint(f, Joint.HAND_A); val head = joint(f, Joint.HEAD_POS)
        val stand = joint(frames[sweep.indexOf(0f)], Joint.PELVIS)
        assertTrue(
            "the jump-top's legs are fully extended (BPS §11) — measured knee interior ${knee.fmt()}° " +
                "(pre-fix 47.17°)",
            knee >= 140f
        )
        assertTrue(
            "the jump-top's arms are extended (BPS §6) — measured elbow interior ${elbow.fmt()}° " +
                "(pre-fix 34.47°)",
            elbow >= 130f
        )
        assertTrue(
            "the jump-top drives the arms OVERHEAD (BPS §6/§11): measured hand y ${hand.y.fmt()} vs " +
                "head y ${head.y.fmt()} (pre-fix: the hand sat 81 units BELOW the head)",
            hand.y > head.y
        )
        assertTrue(
            "the jump leaves the floor (BPS §7 jump) — measured pelvis ${joint(f, Joint.PELVIS).y.fmt()} " +
                "vs the stand's ${stand.y.fmt()}",
            joint(f, Joint.PELVIS).y >= stand.y + 30f
        )
    }

    /** BPS §3 "Stand: tall, feet hip-width, trunk upright" at the loop's own seam pose. */
    @Test
    fun burpeeStandsTallAtTheSeam() {
        val frames = publish(BurpeePose())
        val f = frames[sweep.indexOf(0f)]
        val knee = interior(joint(f, Joint.HIP_F), joint(f, Joint.KNEE_F), joint(f, Joint.ANKLE_F))
        val pel = joint(f, Joint.PELVIS)
        assertTrue(
            "the stand's hips sit near the top of the legs (BPS §3): measured pelvis y ${pel.y.fmt()} vs " +
                "ankle y ${joint(f, Joint.ANKLE_F).y.fmt()} + the leg's reachable " +
                "${(def.thighLength + def.shinLength).fmt()} (pre-fix 140.00 — a quarter-squat)",
            pel.y >= joint(f, Joint.ANKLE_F).y + 0.9f * (def.thighLength + def.shinLength)
        )
        assertTrue("the stand's legs are extended: measured knee interior ${knee.fmt()}° (pre-fix 72.71°)", knee >= 140f)
        assertTrue(
            "the stand's trunk is upright (BPS §3): measured ${tilt(pel, joint(f, Joint.CHEST)).fmt()}°",
            tilt(pel, joint(f, Joint.CHEST)) <= 8f
        )
    }

    // -------------------------------------------------------------------------------------------
    // Both poses: the LOOP seam (LoopMode.LOOP), which no other test asserts
    // -------------------------------------------------------------------------------------------

    @Test
    fun bothRepsCloseTheirLoopSeam() {
        for ((name, pb) in listOf("KettlebellSwingPose" to KettlebellSwingPose(), "BurpeePose" to BurpeePose())) {
            val frames = publish(pb)
            val first = frames.first(); val last = frames.last()
            for (j in Joint.entries) {
                val a = joint(first, j); val b = joint(last, j)
                val d = Vector3().set(a).subtract(b).mag()
                assertTrue(
                    "$name is a LOOP: p=0 and p=1 must publish the same frame (measured ${j.name} " +
                        "delta ${d.fmt()})",
                    d <= 0.01f
                )
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Blast radius: the correction is confined to the two poses it owns
    // -------------------------------------------------------------------------------------------

    /**
     * Every OTHER production pose publishes byte-identical geometry (a digest over every joint of
     * every sampled frame). Measured with this exact recipe on the pre-fix tree (`main` @
     * `fc65695`) and on the corrected tree: equal (`-2275091341366878044`). The direct,
     * non-inferred version of the same claim is the whole-corpus dump (51 classes × 5 progress ×
     * every joint XYZ = `8415` rows, full float bits), which differs in exactly **268 rows — all of
     * them `KettlebellSwingPose` (140 = 28 joints × 5 samples) and `BurpeePose` (128 = 28 joints at
     * each interior sample, 22 at each seam sample — the seam keeps the pre-fix foot contact). The other 49 classes do not move a single
     * float; the largest single-joint move is `188.41` (`BurpeePose` `KNEE_F`, a leg that was
     * folded to 37° interior) and `126.41` (`KettlebellSwingPose` `FINGERTIPS_A`, a load that was
     * curled to the chest instead of swung).
     */
    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val digest = corpusDigest()
        assertEquals(
            "geometry of the production poses outside the corrected swing/burpee pair must be " +
                "byte-identical to the pre-fix tree (every joint of every sampled frame of every " +
                "other pose class); a change here means the correction leaked outside its scope. " +
                "measured=$digest pinned=$UNAFFECTED_CORPUS_DIGEST",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    /** Every concrete production pose class in `poses/` except the two this correction owns. */
    private fun corpusDigest(): Long {
        var dir = java.io.File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        var moduleRoot: java.io.File? = null
        for (attempt in 0 until 8) {
            if (java.io.File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) { moduleRoot = dir; break }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate the app module root")
        val names = java.io.File(root, "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || it in corrected }
            .sorted()
        assertTrue("anti-vacuity: the digest corpus must contain the other poses (found ${names.size})", names.size >= 45)

        var hash = 1125899906842597L
        for (name in names) {
            val pipeline = SkeletonPipeline(def)
            val builder = MotionProbe.build(name)
            hash = hash * 31 + name.hashCode()
            for (p in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
                val frame = SkeletonPose()
                frame.copyFrom(pipeline.produceFrame(builder, context(p)).pose)
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

    private fun Float.fmt() = String.format("%.2f", this).replace(',', '.')

    private companion object {
        /** The two poses this correction owns. */
        val corrected = setOf("KettlebellSwingPose", "BurpeePose")

        /**
         * Digest of the **49** production pose classes outside the corrected pair (every joint,
         * every sampled frame). Measured **equal on the pre-fix tree (`fc65695`) and on the
         * corrected tree** — i.e. the M6/M7 change is confined to the two poses it owns. Computed
         * with the repository's existing corpus-digest recipe (fresh pipeline + builder per class,
         * progress 0/0.25/0.5/0.75/1.0, every `Joint.entries` XYZ as raw float bits).
         *
         * **Re-baselined by the M8/M9/M10 support-declaration pass**
         * (`fix/m8-m9-m10-support-declaration`, itself rebased onto this M6/M7 merge `eea705c`):
         * that pass's 17 declared / re-authored classes are all inside this "every pose except the
         * swing/burpee pair" corpus, so its arrivals move this digest. Observed RED on the pre-fix
         * value `-2275091341366878044` before the re-baseline. Attribution is direct, not inferred:
         * the whole-corpus dump (51 classes × 5 progress × every joint XYZ, `8415` rows, full float
         * bits) measured on the rebased base and on the pass's tree differs in exactly `717` rows,
         * every one of them inside the pass's own classes (which this guard's corpus includes); the
         * pass's own blast-radius guard
         * (`M8M9M10SupportDeclarationTest.UNAFFECTED_CORPUS_DIGEST`) excludes those classes and is
         * measured equal on both trees.
         *
         * **Re-baselined by the M13 hamstring forward-reach correction** (`fix/m13-hamstring-reach`,
         * off `0301563`): this corpus is "every production pose except the swing/burpee pair the M6/M7 pass owns", so it includes `HamstringStretchPose`, the one class that
         * correction owns. Observed RED on the pre-fix value `-8892365611399986406` before the re-baseline (this
         * live run measured `-5046569167321454212` below); the five scope digests were re-run with the pose file
         * stashed and all 50 of their tests were GREEN, so the delta is attributable to M13 and not
         * to a drifted base. Attribution is direct, not inferred from this digest: the whole-corpus
         * dump (49 registry poses × 5 progress × every joint XYZ, `245` pose-frames) differs in
         * exactly `1` frame — `hamstring_stretch_hold` at `p=0.0`, 12 arm-chain joints, max `0.8930`
         * u at `FINGERTIPS_A` — with the other `244` frames (including the subject's `p ≥ 0.05`)
         * byte-identical and `supportedPoints`/`maxIkClampAmount` unchanged everywhere. M13's own
         * blast-radius guard is `HamstringForwardReachTest.UNAFFECTED_CORPUS_DIGEST`.
         *
         * **Re-baselined by the M11/M12 limb-realization migration**
         * (`fix/m11-m12-limb-realization-migration`, off `a8d07cf`): the corpus means "every production
         * pose class except this pair's own corrected classes", so it contains `LatStretchPose` (M11 —
         * the canonical authored hierarchy replaces the hand-rolled tree, publishing `LUMBAR`/
         * `CLAVICLE_*`/`SCAPULA_*` instead of the world origin) and `CatCowPose` (M12 — the four-point
         * support declaration and the reachable-by-construction leg targets). Observed RED on the
         * previous value before this re-baseline. Attribution is direct, not inferred: the whole-corpus
         * dump (`50` classes × `5` samples × every joint, `8415` rows, `git stash` round-trip on the two
         * pose files) differs in exactly `95` xyz rows — `70` in `CatCowPose`, `25` in `LatStretchPose` —
         * and the other `48` classes are byte-identical. That pass's own blast-radius guard is
         * `M11M12LimbRealizationMigrationTest.UNAFFECTED_CORPUS_DIGEST`.
                  *
         * **Re-baselined by the M15 wall/forearm contact-plane correction**
         * (`fix/m15-wallslides-wall-geometry`, off `4203fff`): this corpus contains `WallSlidesPose`, the
         * pose M15 corrects — its arm chain is now authored in the wall prop's own contact plane, its
         * elbow is placed on that plane, and the wall prop itself spans the athlete instead of stopping
         * below the pelvis. Observed RED on the previous value `-7395791808799176758` before the re-baseline (this live
         * run measured `-9151034365136083044`). Attribution is direct, not inferred: the whole-corpus dump (`51`
         * classes × `9` samples × every joint XYZ, plus every `maxIkClampAmount` /
         * `boneLengthsVerified` / `supportedPoints` stamp, the environment props and the declared limb
         * targets — `16524` rows — over a `git stash` round-trip on the corrected pose file with
         * `md5sum -c` on restore) differs in exactly `126` rows, ALL of them inside `WallSlidesPose`:
         * the two arm chains' `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/`KNUCKLES_*`/`FINGERTIPS_*`
         * (`12` joints × `9` samples = `108`) plus the `9` `TARGETS` and `9` `ENV` rows — with the other
         * `50` classes byte-identical and the reachability stamp unchanged
         * (`maxIkClampAmount` `0.047028` on both trees). M15's own blast-radius guard is
         * `M15WallSlidesWallGeometryTest.UNAFFECTED_CORPUS_DIGEST`.
         * **Re-baselined by the B2 runner's-lunge back-knee correction**
         * (`fix/b2-wgs-back-knee-plane`, off `2fb6079`): this corpus contains
         * `DynamicWorldsGreatestStretchPose`, the pose B2 corrects. Its back leg's stance is now the
         * extension the pose's own KDoc declares — the ankle authored one full chain reach behind the
         * hip, at the definition's own floor-contact height, with the knee's bend side derived from the
         * hip→ankle chord — so the realized `KNEE_B` sits `+13.162892` ABOVE the mat instead of the
         * `−46.105583` BELOW it that T2 pinned.
         * Observed RED on the previous value `-9151034365136083044` before the re-baseline (this live run measured
         * `-6796955727995826703`). Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5`
         * samples × every joint XYZ = `8415` rows, taken in a pristine `origin/main` @ `2fb6079`
         * worktree and on this tree, then diffed) differs in exactly `20` rows, ALL of them inside
         * `DynamicWorldsGreatestStretchPose` (`KNEE_B`/`ANKLE_B`/`HEEL_B`/`TOE_B` × the `5` samples,
         * max `82.2520` u at `HEEL_B`), with the other `50` classes byte-identical and the pose's
         * reachability stamp unchanged (`maxIkClampAmount` `21.640945` / `13.396454` / `7.5872955` at
         * p = 0 / 0.25 / 0.5 on BOTH trees — that clamp is the pose's support arm, and B2 does not
         * touch the arms). B2's own blast-radius guard is `WorldsGreatestStretchBackKneePlaneTest`.
         */
        const val UNAFFECTED_CORPUS_DIGEST = -6796955727995826703L
    }
}
