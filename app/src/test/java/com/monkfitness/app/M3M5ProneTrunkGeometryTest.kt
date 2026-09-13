package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * **M3 + M5 — the prone trunk family's geometry** (`docs/STABILIZATION_AUDIT.md` §3 P1 M3/M4/M5).
 *
 * The three prone spine/trunk poses — `ProneCobraStretchPose`, `SupermanPose`,
 * `ReverseSnowAngelPose` — author a **whole-body** trunk motion where the BPS specifies a
 * **spine-articulated** one, and one of them (Superman) is authored on the wrong body basis
 * entirely. This file gates the corrected invariants on the **published frames** of the production
 * entry point playback uses (`SkeletonPipeline.produceFrame(pose, ctx)`, the metadata-derived
 * overload), sampled at `progress` 0, 0.25, 0.5, 0.75, 1.0 after the pipeline is warmed (the
 * documented cold-start fixed point). Every number quoted below was measured on `main` @ `2bb4525`
 * with this exact harness.
 *
 * ## Measured on the pre-fix tree (published frames)
 *
 * | pose | basis (facing axis `rot·X`) | pelvis world rotation across the rep | chest `localRotation` | trunk chain |
 * |---|---|---|---|---|
 * | `ProneCobraStretchPose` | prone (`facing.y = −1.0000` flat, `−0.7833` at the arch) | **1.5700 → 0.9000** (Δ `0.6700` rad = `38.4°` — the extension rides the ROOT) | `0.0000` at every frame | complete |
 * | `SupermanPose` | **SUPINE** (`facing.y = +1.0000`; ventral axis `+Y`) | **1.5708 → 1.3708** (Δ `0.2000` rad — the arch rides the ROOT) | `0.0000` at every frame | complete |
 * | `ReverseSnowAngelPose` | prone (`facing.y = −0.9975`) | `1.5000` constant | `0.0000` at every frame — no spine segment exists | **`LUMBAR` missing from the tree**, published at the world origin `(0,0,0)` (`|LUMBAR − PELVIS| = 18.03`), with `CLAVICLE_A/P`, `SCAPULA_A/P`, `WRIST_A/P` |
 *
 * The measured consequences of the same authoring error:
 *
 *  * **Superman renders lying on its back** while `docs/Biomechanical Pose Specification (BPS)/Superman (Prone).md`
 *    §1/§3 specify a prone, face-down exercise whose floor fulcrum is the **anterior** body
 *    (§8: "The contact surface is the front of the body (anterior), unlike supine exercises").
 *    At the rest phase the head sits **24.5 units below the pose's own declared ground** and
 *    12 joints (head, neck, both hands/wrists/palms/knuckles/fingertips) are under it — the
 *    legacy `(−1, +0.3, 0)` gaze is a world-space direction written into a rolled body's local
 *    frame. `CoreMotionTest`'s travel contract and `EnvironmentPenetrationTest` both stay green
 *    through it (`facing.y > 0` is invisible to a travel threshold, and no support is declared).
 *  * **`chest.localRotation.angle == 0.0000` in all three poses at every sampled frame**: the
 *    trunk is a single rigid segment. `buildSpineCurve`'s two-segment
 *    (`PELVIS → LUMBAR → CHEST`) model exists precisely so a pose can express "thoracic extends
 *    while the lower back stays"; none of the three uses it, so the BPS §5 statements
 *    ("Thoracic: gently extended as the chest lifts — the upper back is the prime mover";
 *    "the extension is smooth along the spine, not a hinge at one segment") have no
 *    representation at all.
 *  * **The pelvis — the floor fulcrum — is the hinge.** For the cobra and Superman the pelvis's
 *    own frame rotates through the rep, which is the same class of error the
 *    `ThoracicExtensionPose` (S3) repair corrected for an upright body.
 *
 * ## The invariants this file asserts
 *
 *  1. [everyProneFamilyPosePublishesAProneBasis] — the body's ventral axis faces the floor
 *     (`facing.y < 0`), the trunk's head-ward axis is the family's `+X`, the head is on the `+X`
 *     side of the pelvis and the feet on the other side. BPS §3/§8 for all three poses.
 *  2. [thePelvisCarriesTheLayoutOnlyAndNotTheRepsExtension] — the pelvis's published world rotation
 *     and position are **constant across the rep** (the layout, not the articulation), its ventral
 *     axis faces the floor and its trunk base is horizontal. BPS §5 ("the pelvis remains neutral and
 *     stays on the floor; the extension originates from the paraspinals, not from tilting the
 *     pelvis"), §7 and §12 ("Lifting the pelvis/hips off the floor").
 *  3. [theDynamicProneRepsArticulateTheirExtensionOnTheSpine] /
 *     [theMaintainedProneExtensionIsHeldOnTheSpine] — the extension is **distributed**: the trunk
 *     segment lifts the chest off the floor (dynamic reps) **and** the chest node carries the
 *     thoracic share above that trunk line, at all times for the isometric hold. BPS §5/§11/§13.
 *  4. [theRepsAuthoredDepthIsNotRetuned] — the correction moves the *ownership* of the authored
 *     rotation, it does not change its amount (the pre-fix root span is preserved as the trunk
 *     segment's span).
 *  5. [theCorrectedPosesRespectTheFloorTheirBpsDeclares] — the grounded chain stays on the declared
 *     floor line (the pelvis is the fulcrum), the chest lifts off it, and in the Superman the arms
 *     hover above it instead of passing through it (BPS §8/§9).
 *  6. [thePublishedTrunkChainIsComplete] — the published frame carries a real `PELVIS → LUMBAR →
 *     CHEST` trunk chain (the engine's own two-segment spine model, `Joint.LUMBAR`), not a
 *     phantom joint at the world origin.
 *  7. [sampledFramesAreDistinctPublishedSnapshots] — anti-vacuity: distinct by-value published
 *     frames that really move.
 *
 * The declaration half of the audit's M5 row (`exerciseFamily`/`bodyOrientation`/support) is
 * deliberately **not** asserted here: those fields have no production consumer, the repository has
 * no canonical family vocabulary for this group, and the declaration channel for the contact-bearing
 * families is owned by separate tracker items (M8/M9/M10). See `docs/STABILIZATION_AUDIT.md`.
 */
class M3M5ProneTrunkGeometryTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** The family under correction. */
    private val cobra = "ProneCobraStretchPose"
    private val superman = "SupermanPose"
    private val snowAngel = "ReverseSnowAngelPose"

    /** The dynamic (rep-driven) members and the isometric hold. */
    private val dynamic = listOf(cobra, superman)
    private val all = listOf(cobra, superman, snowAngel)

    private val samples = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** The engine's prone convention for this family (`ReverseSnowAngelPose`/`ProneCobraStretchPose`):
     *  spine local `+Y` → world `+X` (the head end), ventral `+X` → world `−Y` (face down). */
    private val proneLayoutVentralY = -1f

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private fun vec(v: Vector3) = "(${f(v.x)},${f(v.y)},${f(v.z)})"

    private fun sub(a: Vector3, b: Vector3) = Vector3().set(a.x - b.x, a.y - b.y, a.z - b.z)

    private fun dist(a: Vector3, b: Vector3) = sqrt(
        (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)
    )

    /**
     * The pose's published frames at the sampled progress points, captured **by value** (the
     * pipeline publishes the Finalizer's reused buffer — see T-7) after warming the pipeline past
     * its documented cold-start fixed point. Measured: for these three poses the cold and settled
     * frames are identical, so warming is a harness property, not a confound.
     */
    private fun frames(name: String): Map<Float, SkeletonPose> {
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(name)
        repeat(11) { pipeline.produceFrame(builder, ctx(0.5f)) }
        val out = LinkedHashMap<Float, SkeletonPose>()
        for (p in samples) {
            val frame = pipeline.produceFrame(builder, ctx(p)).pose
            out[p] = SkeletonPose().apply { copyFrom(frame) }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Measurement primitives — all read from the PUBLISHED frame (never from raw build() output,
    // whose late-baked limb world positions are one frame stale).
    // ---------------------------------------------------------------------------------------------

    /** The body basis: `spine` = rot·Y (trunk, head-ward), `lateral` = rot·Z, `facing` = rot·X. */
    private fun basis(pose: SkeletonPose): Triple<Vector3, Vector3, Vector3> {
        val pel = pose.getJoint(Joint.PELVIS)
        val chest = pose.getJoint(Joint.CHEST)
        val hipF = pose.getJoint(Joint.HIP_F)
        val spine = Vector3().set(sub(chest, pel)).normalize()
        val lateral = Vector3().set(sub(pel, hipF)).normalize()
        val facing = Vector3().set(spine.x, spine.y, spine.z).cross(lateral)
        return Triple(spine, lateral, facing)
    }

    /** The pelvis's OWN frame axes in world space (the published world rotation of `Joint.PELVIS`). */
    private fun pelvisFrame(pose: SkeletonPose): Pair<Vector3, Vector3> {
        val r = pose.getJointRotation(Joint.PELVIS)
        val ventral = SkeletonMath.rotAround(Vector3(1f, 0f, 0f), r.axis, r.angle, Vector3())
        val base = SkeletonMath.rotAround(Vector3(0f, 1f, 0f), r.axis, r.angle, Vector3())
        return ventral to base
    }

    /** The chest node's OWN frame's trunk axis (the rib cage's "up the spine") in world space. */
    private fun chestFrameAxis(pose: SkeletonPose): Vector3 {
        val r = pose.getJointRotation(Joint.CHEST)
        return SkeletonMath.rotAround(Vector3(0f, 1f, 0f), r.axis, r.angle, Vector3())
    }

    /** The trunk segment's inclination above the floor plane, in radians (0 = lying flat). */
    private fun trunkTilt(pose: SkeletonPose): Float {
        val t = sub(pose.getJoint(Joint.CHEST), pose.getJoint(Joint.PELVIS))
        return atan2(t.y.toDouble(), t.x.toDouble()).toFloat()
    }

    /** The chest frame's inclination above the floor plane, in radians. */
    private fun chestFrameTilt(pose: SkeletonPose): Float {
        val a = chestFrameAxis(pose)
        return atan2(a.y.toDouble(), a.x.toDouble()).toFloat()
    }

    /** How far the rib cage is carried ABOVE the trunk segment — the thoracic share. 0 = rigid. */
    private fun thoracicShare(pose: SkeletonPose): Float = chestFrameTilt(pose) - trunkTilt(pose)

    private fun trunkLength(pose: SkeletonPose) =
        dist(pose.getJoint(Joint.CHEST), pose.getJoint(Joint.PELVIS))

    // ---------------------------------------------------------------------------------------------
    // 1. The prone basis
    // ---------------------------------------------------------------------------------------------

    /**
     * BPS §3 (all three): "Prone on the floor … the body's facing (ventral) side is the contact
     * side". On this engine's skeleton the ventral axis is `rot·X`, so a prone body has
     * `facing.y < 0`; the family's prone convention additionally puts the head end at `+X`
     * (the sibling `ReverseSnowAngelPose` and `ProneCobraStretchPose` both do — measured below).
     *
     * Measured pre-fix: `SupermanPose` publishes `facing.y = +1.0000` (SUPINE), `spine.x = −1.0000`
     * (head end at `−X`) and its head at `x = −130.3445` with the pelvis at `x = 0`.
     */
    @Test
    fun everyProneFamilyPosePublishesAProneBasis() {
        val failures = mutableListOf<String>()
        for (name in all) {
            for ((p, frame) in frames(name)) {
                val (spine, _, facing) = basis(frame)
                val pel = frame.getJoint(Joint.PELVIS)
                val head = frame.getJoint(Joint.HEAD_POS)
                val ankleF = frame.getJoint(Joint.ANKLE_F)
                val ankleB = frame.getJoint(Joint.ANKLE_B)
                if (facing.y > -0.5f) {
                    failures.add("$name @p=$p: body is NOT prone — facing axis ${vec(facing)} (facing.y=${f(facing.y)} > 0)")
                }
                if (spine.x < 0.5f) {
                    failures.add("$name @p=$p: head end is not the family's +X — trunk axis ${vec(spine)} (spine.x=${f(spine.x)})")
                }
                if (head.x <= pel.x) {
                    failures.add("$name @p=$p: head ${f(head.x)} is not on the +X side of the pelvis ${f(pel.x)}")
                }
                if (ankleF.x >= pel.x || ankleB.x >= pel.x) {
                    failures.add("$name @p=$p: feet are not on the −X side of the pelvis (ankles ${f(ankleF.x)}/${f(ankleB.x)} vs ${f(pel.x)})")
                }
            }
        }
        assertTrue(
            "the prone family must publish a prone basis on the production path:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 2. The pelvis is the floor fulcrum, not the hinge
    // ---------------------------------------------------------------------------------------------

    /**
     * BPS §5/§7/§12 (all three): the pelvis stays grounded and NEUTRAL — the extension originates
     * from the paraspinals, never from tilting the pelvis — and the hips do not lift.
     *
     * Measured pre-fix: the cobra's pelvis world rotation sweeps `1.5700 → 0.9000` (Δ `0.6700` rad)
     * and Superman's `1.5708 → 1.3708` (Δ `0.2000` rad); at the top of the cobra rep the pelvis's
     * own trunk base is `0.6226` (38.4°) out of the floor plane and Superman's ventral axis is
     * `+0.9801` (it points at the sky).
     */
    @Test
    fun thePelvisCarriesTheLayoutOnlyAndNotTheRepsExtension() {
        val failures = mutableListOf<String>()
        for (name in all) {
            val fr = frames(name)
            val base0 = fr[samples.first()]!!
            val rot0 = base0.getJointRotation(Joint.PELVIS)
            val pos0 = base0.getJoint(Joint.PELVIS)
            for ((p, frame) in fr) {
                val rot = frame.getJointRotation(Joint.PELVIS)
                val pos = frame.getJoint(Joint.PELVIS)
                val (ventral, base) = pelvisFrame(frame)
                if (abs(rot.angle - rot0.angle) > 1e-3f || dist(Vector3().set(rot.axis), Vector3().set(rot0.axis)) > 1e-3f) {
                    failures.add(
                        "$name @p=$p: the pelvis frame MOVES through the rep (axis ${vec(rot.axis)}/${f(rot.angle)} " +
                            "vs the seam ${vec(rot0.axis)}/${f(rot0.angle)}) — the layout must be constant and the " +
                            "articulation must be the spine's"
                    )
                }
                if (dist(pos, pos0) > 1e-3f) {
                    failures.add("$name @p=$p: the pelvis wanders off the floor line (${vec(pos)} vs ${vec(pos0)})")
                }
                if (ventral.y > -0.9f) {
                    failures.add("$name @p=$p: the pelvis's own ventral axis does not face the floor (${vec(ventral)})")
                }
                if (abs(base.y) > 0.08f) {
                    failures.add(
                        "$name @p=$p: the pelvis's own trunk base is ${f(Math.toDegrees(abs(base.y).toDouble()).toFloat())}° " +
                            "out of the floor plane (${vec(base)}) — the body is hinging at the pelvis, not at the spine"
                    )
                }
            }
        }
        assertTrue(
            "the pelvis is the floor fulcrum: it carries the whole-body LAYOUT only:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The extension is owned by the spine
    // ---------------------------------------------------------------------------------------------

    /**
     * BPS §5 ("Thoracic: gently extended as the chest lifts — the upper back is the prime mover";
     * "the extension is smooth along the spine, not a hinge at one segment") and §13 ("the chest
     * lifts off the floor via thoracic extension while the lumbar spine stays neutral").
     *
     * The engine's two-segment spine makes this measurable: the trunk segment
     * (`PELVIS → CHEST`) carries the chest off the floor, and the chest node carries the rib cage
     * ABOVE that line (`thoracicShare > 0`). Pre-fix `thoracicShare == 0.0000` exactly in both
     * dynamic members at every frame — one rigid segment, i.e. the BPS statement has no
     * representation.
     */
    @Test
    fun theDynamicProneRepsArticulateTheirExtensionOnTheSpine() {
        val failures = mutableListOf<String>()
        val seam = samples.first()
        val top = samples.last()
        for (name in dynamic) {
            val fr = frames(name)
            val seamFrame = fr[seam]!!
            val topFrame = fr[top]!!
            val seamTilt = trunkTilt(seamFrame)
            val topTilt = trunkTilt(topFrame)
            val seamShare = thoracicShare(seamFrame)
            val topShare = thoracicShare(topFrame)
            val chestRise = topFrame.getJoint(Joint.CHEST).y - seamFrame.getJoint(Joint.CHEST).y

            // (a) the rep really lifts the chest off the floor through the trunk segment
            if (chestRise < 0.1f * def.torsoLength) {
                failures.add("$name: the chest does not lift off the floor through the rep (rise ${f(chestRise)}u)")
            }
            if (topTilt < seamTilt) {
                failures.add("$name: the trunk segment does not extend (tilt ${f(seamTilt)} → ${f(topTilt)} rad)")
            }
            // (b) the rib cage is carried ABOVE the trunk line — the thoracic segment exists
            if (topShare <= 1e-2f) {
                failures.add(
                    "$name: the chest node carries NO thoracic extension above the trunk line " +
                        "(share ${f(topShare)} rad at p=$top) — the trunk is one rigid segment"
                )
            }
            // (c) at the seam the trunk is the prone layout, not an arch
            if (abs(seamShare) > 1e-3f || abs(seamTilt) > 1e-2f) {
                failures.add(
                    "$name: the seam is not the flat prone layout (trunk tilt ${f(seamTilt)} rad, " +
                        "thoracic share ${f(seamShare)} rad)"
                )
            }
        }
        assertTrue(
            "the rep's extension must be articulated on the spine (both segments), not carried as one rigid root rotation:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * BPS §5/§9 for the isometric member: "Thoracic spine maintained in gentle extension: the chest
     * is long and open"; "Thoracic extension: a maintained posture (small ROM), held isometrically".
     * The maintenance is constant, but it is still a SPINE posture: the chest node must carry it
     * (and hold it steady) rather than the whole body line hinging at the root.
     *
     * Measured pre-fix: `thoracicShare == 0.0000` at every sampled frame — the pose's 4.06° of
     * maintained extension is the root's tilt (`pelvisRot = 1.5000`), and its trunk chain has no
     * `LUMBAR` node at all.
     */
    @Test
    fun theMaintainedProneExtensionIsHeldOnTheSpine() {
        val fr = frames(snowAngel)
        val failures = mutableListOf<String>()
        val shares = fr.entries.sortedBy { it.key }.map { thoracicShare(it.value) }
        val tilts = fr.entries.sortedBy { it.key }.map { trunkTilt(it.value) }
        if (shares.any { it <= 1e-2f }) {
            failures.add("thoracic share per sample ${shares.map { f(it) }} — the maintained extension is not on the chest node")
        }
        if (shares.max() - shares.min() > 1e-3f) {
            failures.add("the maintained extension is not held steady: shares ${shares.map { f(it) }}")
        }
        if (tilts.max() - tilts.min() > 0.5f) {
            failures.add("the isometric hold's trunk moves through its samples: tilts ${tilts.map { f(it) }}")
        }
        assertTrue(
            "the maintained (isometric) prone extension must be a held spine posture:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 4. The authored depth is preserved
    // ---------------------------------------------------------------------------------------------

    /**
     * This correction moves the OWNERSHIP of the authored trunk rotation (root → spine), it does
     * not re-tune the rep. Both dynamic members' authored depth is pinned to the amount measured on
     * the pre-fix tree as the pelvis's own rotation span:
     *
     *  * `ProneCobraStretchPose`: `1.5700 → 0.9000` ⇒ `0.6700` rad (`38.4°`)
     *  * `SupermanPose`: `1.5708 → 1.3708` ⇒ `0.2000` rad (`11.5°`)
     *
     * (For the isometric member the maintained tilt is pinned in
     * [theMaintainedProneExtensionIsHeldOnTheSpine] — it is constant by contract.)
     */
    @Test
    fun theRepsAuthoredDepthIsNotRetuned() {
        val authoredDepth = mapOf(
            cobra to 0.6700f,     // pre-fix pelvis span 1.5700 − 0.9000 (the cobra's own authored lerp)
            superman to 0.2000f   // pre-fix pelvis span 1.5708 − 1.3708 (chestLean lerp(0, −0.2))
        )
        for ((name, depth) in authoredDepth) {
            val fr = frames(name)
            val seam = trunkTilt(fr[samples.first()]!!)
            val top = trunkTilt(fr[samples.last()]!!)
            assertEquals(
                "$name: the repaired rep must keep its authored trunk extension (measured as the trunk segment's span)",
                depth, top - seam, 2e-3f
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. The declared floor
    // ---------------------------------------------------------------------------------------------

    /**
     * The BPS's own floor contract, per pose:
     *
     *  * `ProneCobraStretchPose` §7/§8/§13: "Pelvis, thighs, and legs remain flat on the floor …
     *    Hips stay grounded"; "the chest lifts off the floor via thoracic extension".
     *  * `SupermanPose` §3/§8/§9: "pelvis, abdomen, and thighs rest on the ground at the start";
     *    "The chest, head, arms, and legs are OFF the floor during the hold" — the fulcrum is the
     *    anterior body, so NO joint may sit below the declared ground, and the arms hover a few
     *    centimetres above it.
     *  * `ReverseSnowAngelPose` §7/§8: "Legs extended straight back along the floor … the pelvis
     *    stays grounded"; nothing lifts.
     *
     * Measured pre-fix: `SupermanPose` publishes **12 joints below its own declared ground** at the
     * rest phase (`HEAD_POS y = −24.4818`, `HAND_A/P y = −4.2842`, `FINGERTIPS_A/P y = −9.8092`).
     */
    @Test
    fun theCorrectedPosesRespectTheFloorTheirBpsDeclares() {
        val failures = mutableListOf<String>()
        val ground = MotionProbe.build(cobra).metadata.environment.ground.level

        // The cobra: the grounded chain (pelvis + legs) stays on the floor line, the chest leaves it.
        for (name in listOf(cobra, snowAngel)) {
            for ((p, frame) in frames(name)) {
                for (j in listOf(
                    Joint.PELVIS, Joint.HIP_F, Joint.HIP_B, Joint.KNEE_F, Joint.KNEE_B,
                    Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B
                )) {
                    val y = frame.getJoint(j).y
                    if (y < ground) failures.add("$name @p=$p: grounded joint $j is below the floor (y=${f(y)} < $ground)")
                }
            }
        }
        // The cobra's chest must leave the floor line the pelvis rests on.
        val cobraFrames = frames(cobra)
        val pelY = cobraFrames[samples.last()]!!.getJoint(Joint.PELVIS).y
        val chestY = cobraFrames[samples.last()]!!.getJoint(Joint.CHEST).y
        if (chestY - pelY < 0.1f * def.torsoLength) {
            failures.add("$cobra: the chest does not lift off the floor line (chest ${f(chestY)} vs pelvis ${f(pelY)})")
        }
        // The Superman: the anterior body is the fulcrum and everything else leaves the floor.
        for ((p, frame) in frames(superman)) {
            var minY = Float.MAX_VALUE
            var minJoint: Joint? = null
            for (j in Joint.entries) {
                val y = frame.getJoint(j).y
                if (y < minY) { minY = y; minJoint = j }
            }
            if (minY < ground) {
                failures.add("$superman @p=$p: $minJoint is BELOW the declared ground (y=${f(minY)} < $ground) — the prone fulcrum is the anterior body")
            }
            for (j in listOf(Joint.HAND_A, Joint.HAND_P, Joint.HEAD_POS, Joint.CHEST)) {
                if (frame.getJoint(j).y < ground) {
                    failures.add("$superman @p=$p: $j is under the floor (y=${f(frame.getJoint(j).y)}) — BPS §8 lifts it")
                }
            }
        }
        // The Superman's arms hover above the floor and lift with the rep (BPS §9).
        val suFrames = frames(superman)
        val seamHand = suFrames[samples.first()]!!.getJoint(Joint.HAND_A).y
        val topHand = suFrames[samples.last()]!!.getJoint(Joint.HAND_A).y
        if (seamHand < ground) failures.add("$superman: the resting arms are under the floor (HAND_A y=${f(seamHand)})")
        if (topHand - seamHand < 0.1f * (def.upperArmLength + def.forearmLength)) {
            failures.add("$superman: the arms do not lift with the rep (HAND_A y ${f(seamHand)} → ${f(topHand)})")
        }
        assertTrue(
            "the corrected poses must respect the floor their own BPS declares:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 6. The published trunk chain is complete
    // ---------------------------------------------------------------------------------------------

    /**
     * The engine's skeleton model is a **two-segment** trunk —
     * `PELVIS → LUMBAR → CHEST` (`Joint.LUMBAR`: "the spine is modelled as two real segments …
     * so lumbar/pelvis-tilt and thoracic motion can differ"). Every published frame must therefore
     * carry a real lower-spine junction: `LUMBAR` coincident with the pelvis (the pass-through
     * default) and the chest one torso length away. A pose that builds its own hand-rolled tree
     * publishes `LUMBAR` at the **world origin** instead — a joint that is not part of the body.
     *
     * Measured pre-fix: `ReverseSnowAngelPose` publishes `LUMBAR = (0.0000, 0.0000, 0.0000)` while
     * its pelvis is at `(15.0000, 10.0000, 0.0000)` — `|LUMBAR − PELVIS| = 18.0278u`.
     */
    @Test
    fun thePublishedTrunkChainIsComplete() {
        val failures = mutableListOf<String>()
        for (name in all) {
            for ((p, frame) in frames(name)) {
                val pel = frame.getJoint(Joint.PELVIS)
                val lumbar = frame.getJoint(Joint.LUMBAR)
                val chest = frame.getJoint(Joint.CHEST)
                val lumbarGap = dist(lumbar, pel)
                if (lumbarGap > 1e-3f) {
                    failures.add(
                        "$name @p=$p: the lower-spine junction LUMBAR ${vec(lumbar)} is not at the pelvis " +
                            "${vec(pel)} (gap ${f(lumbarGap)}u) — the published trunk chain is missing its segment"
                    )
                }
                val trunk = dist(chest, pel)
                if (abs(trunk - def.torsoLength) > 1e-3f) {
                    failures.add(
                        "$name @p=$p: the trunk length ${f(trunk)}u is not the definition's torso length " +
                            "${def.torsoLength}u — the chest must own the trunk length"
                    )
                }
            }
        }
        assertTrue(
            "every prone-family pose must publish the engine's two-segment trunk chain:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Anti-vacuity
    // ---------------------------------------------------------------------------------------------

    /** The frames measured are distinct published snapshots that really move (never the aliased buffer). */
    @Test
    fun sampledFramesAreDistinctPublishedSnapshots() {
        for (name in all) {
            val fr = frames(name)
            assertEquals("$name: one distinct snapshot per sample", samples.size, fr.values.map { System.identityHashCode(it) }.distinct().size)
            val hands = fr.values.map { it.getJoint(Joint.HAND_A) }
            val travel = hands.maxOf { it.x } - hands.minOf { it.x } +
                (hands.maxOf { it.y } - hands.minOf { it.y }) +
                (hands.maxOf { it.z } - hands.minOf { it.z })
            assertTrue("$name: the sampled rep must actually move (HAND_A span ${f(travel)}u)", travel > 20f)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 8. Blast radius
    // ---------------------------------------------------------------------------------------------

    /**
     * The correction is confined to the three poses it owns: every OTHER production pose class
     * publishes **byte-identical** geometry (a digest over every joint of every sampled frame).
     *
     * Measured with this exact recipe on the pre-fix tree (`main` @ `2bb4525`) and on the corrected
     * tree: equal (`-517042293001259057`). The direct, non-inferred version of the same claim is the
     * whole-corpus dump (51 classes × 5 progress × every joint XYZ = `8415` rows, full float bits),
     * which differs in exactly **377 rows — all of them `SupermanPose` (153),
     * `ReverseSnowAngelPose` (143) and `ProneCobraStretchPose` (81)**; the other 48 classes do not
     * move a single float.
     */
    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val digest = corpusDigest()
        assertEquals(
            "geometry of the production poses outside the corrected prone family must be byte-identical " +
                "to the pre-fix tree (every joint of every sampled frame of every other pose class); a change " +
                "here means the correction leaked outside its scope. measured=$digest pinned=$UNAFFECTED_CORPUS_DIGEST",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    /** Every concrete production pose class in `poses/` except the three this correction owns. */
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
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || it in all }
            .sorted()
        assertTrue("anti-vacuity: the digest corpus must contain the other poses (found ${names.size})", names.size >= 45)

        var hash = 1125899906842597L
        for (name in names) {
            val pipeline = SkeletonPipeline(def)
            val builder = MotionProbe.build(name)
            hash = hash * 31 + name.hashCode()
            for (p in samples) {
                val frame = SkeletonPose()
                frame.copyFrom(pipeline.produceFrame(builder, ctx(p)).pose)
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

    private companion object {
        /**
         * Digest of the **48** production pose classes outside the corrected prone family (every joint,
         * every sampled frame). Measured **equal on the pre-fix tree `2bb4525` and on the corrected
         * tree** — i.e. the M3/M5 change is confined to the three poses it owns. Computed with the
         * repository's existing corpus-digest recipe (fresh pipeline + builder per class, progress
         * 0/0.25/0.5/0.75/1.0, every `Joint.entries` XYZ as raw float bits); validated against the two
         * older pinned digests before being used here.
         *
         * **Re-baselined by the M6/M7 swing/burpee correction** (`fix/m6-m7-swing-burpee-geometry`, off
         * `fc65695`): this corpus is "every pose except the three prone-family poses", so it includes the
         * two poses that correction owns. Observed RED on the previous value `-517042293001259057` before
         * the re-baseline. Attribution is direct, not inferred: the whole-corpus dump (51 classes × 5
         * progress × every joint XYZ, `8415` rows, full float bits) differs in exactly `268` rows, all of
         * them `KettlebellSwingPose` + `BurpeePose`; the other 49 classes are byte-identical, which
         * `M6M7SwingBurpeeGeometryTest.UNAFFECTED_CORPUS_DIGEST` (`-2275091341366878044`) gates directly.
         *
         * **Re-baselined by the M8/M9/M10 support-declaration pass**
         * (`fix/m8-m9-m10-support-declaration`): that pass owns the declaration of 17 poses (the 7
         * upper/dynamic poses, the stretch family and the core/hip poses) and the authored-frame
         * correction of 5 standing poses, all of which are inside this "every pose except the prone
         * family" corpus. Observed RED on the pre-fix value `-517042293001259057`, and again on the M6/M7
         * value `-5490451701131484798` after the pass was rebased onto the M6/M7 merge (this pass
         * originally branched off `fc65695`), before the re-baseline below. The pass's own blast-radius
         * guard (`M8M9M10SupportDeclarationTest.UNAFFECTED_CORPUS_DIGEST`) excludes its 17 classes and is
         * measured equal on the pre-fix and post-fix trees.
         *
         * **Re-baselined by the M13 hamstring forward-reach correction** (`fix/m13-hamstring-reach`,
         * off `0301563`): this corpus is "every production pose except the three prone classes the M3/M5 pass owns", so it includes `HamstringStretchPose`, the one class that
         * correction owns. Observed RED on the pre-fix value `5434130474548470574` before the re-baseline (this
         * live run measured `5051896512474775952` below); the five scope digests were re-run with the pose file
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
         * pose class except the three this pass corrects", so it contains `LatStretchPose` (M11 — the
         * canonical authored hierarchy replaces the hand-rolled tree, publishing `LUMBAR`/`CLAVICLE_*`/
         * `SCAPULA_*` instead of the world origin) and `CatCowPose` (M12 — the four-point support
         * declaration and the reachable-by-construction leg targets). Observed RED on the previous value
         * before this re-baseline. Attribution is direct, not inferred: the whole-corpus dump (`50`
         * classes × `5` samples × every joint, `8415` rows, `git stash` round-trip on the two pose files)
         * differs in exactly `95` xyz rows — `70` in `CatCowPose`, `25` in `LatStretchPose` — and the
         * other `48` classes are byte-identical. That pass's own blast-radius guard is
         * `M11M12LimbRealizationMigrationTest.UNAFFECTED_CORPUS_DIGEST`.
                  *
         * **Re-baselined by the M15 wall/forearm contact-plane correction**
         * (`fix/m15-wallslides-wall-geometry`, off `4203fff`): this corpus contains `WallSlidesPose`, the
         * pose M15 corrects — its arm chain is now authored in the wall prop's own contact plane, its
         * elbow is placed on that plane, and the wall prop itself spans the athlete instead of stopping
         * below the pelvis. Observed RED on the previous value `-6653724965262809122` before the re-baseline (this live
         * run measured `-8408967521599715408`). Attribution is direct, not inferred: the whole-corpus dump (`51`
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
         * Observed RED on the previous value `-8408967521599715408` before the re-baseline (this live run measured
         * `-967624648643503611`). Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5`
         * samples × every joint XYZ = `8415` rows, taken in a pristine `origin/main` @ `2fb6079`
         * worktree and on this tree, then diffed) differs in exactly `20` rows, ALL of them inside
         * `DynamicWorldsGreatestStretchPose` (`KNEE_B`/`ANKLE_B`/`HEEL_B`/`TOE_B` × the `5` samples,
         * max `82.2520` u at `HEEL_B`), with the other `50` classes byte-identical and the pose's
         * reachability stamp unchanged (`maxIkClampAmount` `21.640945` / `13.396454` / `7.5872955` at
         * p = 0 / 0.25 / 0.5 on BOTH trees — that clamp is the pose's support arm, and B2 does not
         * touch the arms). B2's own blast-radius guard is `WorldsGreatestStretchBackKneePlaneTest`.
         *
        * **Re-baselined by the B1 diamond push-up elbow-plane correction**
        * (`fix/b1-diamond-pushup-elbow-plane`, off `2fb6079` — the T2 merge): this corpus means "every
        * production pose class except the ones this pass corrects", so it contains `DiamondPushUpPose`,
        * whose elbow pole is re-authored onto the trunk's own long axis. The inherited Z-dominant pole
        * shape is correct for a grip whose hands sit at or outside the shoulder line; the diamond grip is
        * `0.1` (the hands come to the fused base `4.6` from the midline against the shoulder joint's
        * `46`), so the pole's perpendicular residual collapsed onto the chord's downward basis vector and
        * realized the elbow `19.91` u BELOW the pose's own declared plane at the bottom of the rep
        * (measured `p = 0.5`; the pose is a pinned, attributed open item in the T2 invariant, whose entry
        * this correction removes in the same change). Observed RED on the previous value `-8408967521599715408` before
        * the re-baseline (this live run measured `-5602313885035838638`). Attribution is direct, not
        * inferred
        * whole-corpus dump (`51` classes x `5` samples x every joint XYZ, `8415` rows, a `git stash`
        * round-trip on the corrected pose file with `md5sum -c` on restore) differs in exactly `60` rows,
        * ALL of them inside `DiamondPushUpPose` — `ELBOW_A`/`ELBOW_P` at all five samples
        * (`19.96 ... 46.79` u) plus the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` pair
        * (<= `8e-6` u float drift at four samples, and `5.29` / `10.57` / `19.38` u at `p = 0.5`, where
        * the engine's planted-hand flattening now fires because the elbow is above the hand) — with the
        * other `50` classes byte-identical. B1's own regression is `DiamondPushUpElbowClearanceTest`.
         *
         * **Re-measured by the B1 integration** (this branch merges `fix/b1-diamond-pushup-elbow-plane`
         * @ `29ee54b`, off `2fb6079`, onto the B2 merge `f8f8b24`): this corpus contains BOTH corrected
         * poses, so neither pass's committed value was valid on the merged tree. Observed RED on the
         * B2-merged value `-967624648643503611` before this re-baseline (this live run, with B1 integrated,
         * measured `1839028987920373159`). The digest's move from the B2-merged value is B1's own delta
         * (`DiamondPushUpPose`'s `ELBOW_A`/`ELBOW_P` plus the derived hand chain — `60` rows of the
         * whole-corpus dump, per B1's own record); from the pre-B2 value it is the union of B2's `20`
         * rows and B1's `60`.
         *
         * **Re-baselined by the B3 side-plank support-leg correction** (`fix/b3-sideplank-knee-plane`,
         * rebased onto the B2 merge `f8f8b24`, with B1 integrated): this corpus contains
         * `IsometricSidePlankPose`, the pose B3 corrects — the support leg's residual knee bend is
         * authored out of the mat now (the bend plane's pole `(0, -1, 0)` → `(0, 1, 0)`), so the pose
         * publishes `KNEE_B` above its own declared plane instead of `25.8897` below it. Observed RED on
         * the B1-integrated value `1839028987920373159` before this re-baseline (this live run measured `-1423510146238240711`).
         * Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5` samples × every
         * joint XYZ, `8415` rows, over a `git stash` round-trip on the corrected pose file with
         * `md5sum -c` on restore) differs from the pre-B3 tree in exactly `5` xyz rows — all `5` samples
         * of `IsometricSidePlankPose`'s `KNEE_B` — while B1's `60` `DiamondPushUpPose` rows and B2's `20`
         * `DynamicWorldsGreatestStretchPose` rows are untouched by this pass. B3's own gate is
         * `IsometricSidePlankKneePlaneTest`.
         * **Re-baselined by the B4 supine elbow-plane correction**
         * (`fix/b4-glute-bridge-pelvic-tilt-elbow-plane`, off `ba3928b` — the B1+B2+B3 tree): this corpus
         * contains `GluteBridgePose` and `PelvicTiltPose`, the two supine poses B4 corrects. Their arms' bend
         * side is re-authored onto the poses' own lateral axis: the family's `(0, -1, ∓1)` pole spent its `-Y`
         * component on the horizontal shoulder→hand chord's DOWNWARD basis vector (measured
         * `phat_y = -0.6995 … -0.7079` against `h = 58.48 … 60.74`), realizing `ELBOW_A/P` `28.6 … 33.4` u
         * below each pose's own declared mat at EVERY phase. Both poses' elbows now lie in the floor plane
         * (`+7.1 … +12.8` u) and the engine's planted-hand flattening fires with them. Observed RED on the
         * previous value `-1423510146238240711` before the re-baseline (this live run measured `8113583905073315959`). Attribution is direct,
         * not inferred: the whole-corpus dump (`51` classes × `5` samples × every joint XYZ, `8415` rows, over a
         * `git stash` round-trip on the two corrected pose files with `md5sum -c` on restore) differs in
         * exactly `116` rows, ALL of them inside those two poses (`GluteBridgePose` `56`, `PelvicTiltPose` `60`
         * — the two `ELBOW_*` plus each arm's derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain, max
         * `43.0165` u at `GluteBridgePose` `ELBOW_A` `p = 1.0`), with the other `49` classes byte-identical and
         * every pose's stamps
         * unchanged (`maxIkClampAmount` reads identically on both trees — it folds the LEGS' own
         * min-reach clamp — with `boneLengthsVerified = true` and `straightIntentDropped = false`
         * everywhere). B4's own gate is `SupineArmElbowPlaneTest`.
         *
         * **Re-baselined by the B4 trunk correction** (`fix/b4-pelvic-tilt-trunk-ground-plane`, off the
         * B4-elbow merge `7012ccb`): this corpus contains `PelvicTiltPose`, whose rigid trunk is
         * re-authored out of the mat (the tilt's DIRECTION — the pose was carrying
         * `CHEST`/`SHOULDER_A/P`/`NECK_END`/`HEAD_POS` `120/138·sin(0.12)` below a pelvis whose resting
         * layer is `14`). Observed RED on the previous value `8113583905073315959` before the
         * re-baseline (this live run measured `2834864332422035085`). Attribution is direct, not
         * inferred: the whole-corpus dump (`51` classes × `5` samples × every joint XYZ, `8415` rows,
         * over a `git stash` round-trip on the corrected pose file with `md5sum -c` on restore) differs
         * in exactly `56` rows, ALL of them inside `PelvicTiltPose` (its trunk chain plus the arm joints
         * that hang off the moved shoulder, max `33.0581` u at `HEAD_POS` `p = 1.0`; the pose's legs,
         * pelvis and the world-origin joints are unchanged, and the whole `p = 0.0` sample is
         * byte-identical because the authored tilt is zero at rest), with the other `50` classes
         * byte-identical. The trunk's own gate is `PelvicTiltTrunkPlaneTest`.
         *
         *
         * **Re-baselined by the first reach-band cleanup batch** (`fix/reach-band-batch1-airsquat-squat`,
         * off the C2 merge `d6f4f7f`): this corpus contains `AirSquatPose` and `SquatPose`, whose
         * authored limb targets are now projected onto their own chains' reachable annulus (R2/R4) — the
         * standing-phase ankle was authored as a locked-out leg (`210.288` against the engine's `0.98`
         * extension cap at `205.800`) and the counterbalance reach never left a `9.2 … 15.9` unit radius
         * against the arm chain's `minReach = 40.134`, so the solver relocated the realized
         * end-effector along the authored ray (`4.488 … 30.934` u) and the publishable geometry WAS the
         * projection. Observed RED on the previous value `2834864332422035085` before the re-baseline (this live run
         * measured `6978338595624143531`). Attribution is direct, not inferred: a whole-corpus dump (`51` classes ×
         * `5` samples × every joint XYZ plus every stamp, the declared limb targets, the supported
         * points and the environment, `255` rows) diffed between this branch and a `git worktree` of
         * `origin/main` @ `d6f4f7f` differs in exactly `152` rows — `76` in each of the two poses, ALL
         * of them limb-chain joints (`KNEE_*` max `0.0495` u, `ANKLE_*`/`HEEL_*`/`TOE_*` `0.0205` u, the
         * derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `<= 0.0056` u, `ELBOW_*`
         * `<= 0.0006` u) — with the other `49` classes byte-identical, every `support` and `env` row
         * byte-identical, and the two poses' reachability stamp dropping from `30.93442 / 30.00000 /
         * 24.24812` to exactly `0.000000` (the relocation this batch removes). The batch's own gate is
         * `SquatReachBandAuthoringTest`; the three pose files stashed on the base tree re-run this guard
         * GREEN on the pre-baseline value (measured in the same session).
          *
          * **Re-baselined by the second reach-band cleanup batch** (`fix/reach-band-batch2-deepsquat-jump-cossack`,
          * off the first reach-band batch's merge `ef9400f`): this corpus is "every production pose class
          * except the classes this correction owns", so it includes the batch's two corrected poses
          * (`DeepSquatHoldPose`, `JumpSquatPose`). Observed RED on the pre-baseline value `6978338595624143531`
          * before the re-baseline (this live run measured `6655628533102093923` below). Attribution is direct, not
          * inferred
          * from this digest: the whole-corpus dump (`51` classes × `14` phases × every `Joint.entries` XYZ ×
          * both frame conditions — `47,124` rows, full float bits) measured on a worktree of `origin/main` @
          * `ef9400f` and on this branch differs in exactly `584` rows — `224` in `DeepSquatHoldPose`, `360` in
          * `JumpSquatPose` — ALL of them limb-chain joints (`KNEE_*` max `0.0484` u, `ANKLE_*`/`HEEL_*`/`TOE_*`
          * `0.0206` u, the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `0.0053` u, `ELBOW_*`
          * `0.0006` u), with the other `49` classes byte-identical — `CossackSquatPose` included, which this
          * batch deliberately does NOT change — and every pelvis/spine/girdle/head joint byte-identical, i.e.
          * the batch is target-side only (a root-side "fix" would have moved the holds' depths). The batch's
          * own gate is `ReachBandBatch2AuthoringTest`.
          *
          * **Re-baselined by the third reach-band cleanup batch**
          * (`fix/reach-band-batch3-halfkneel-hamstring-cobra`, off the #254 merge `cf8a14f`): this corpus is
          * "every production pose class except the classes this correction owns", so it contains
          * `HalfKneelingStretchPose` and `HamstringStretchPose` — the batch's poses. Their authored limb targets sat outside their own
          * chains' reachable annulus and are now projected onto it (R2/R4). Observed RED on the pre-baseline value `6655628533102093923` before the re-baseline
          * (this live run measured `1787803362589852341`). Attribution is direct, not inferred: the whole-corpus A/B (`51`
          * classes × `15` phases × every `Joint.entries` XYZ × both frame conditions — `60,660` rows, full
          * float bits) measured on a worktree of `origin/main` @ `cf8a14f` and on this branch differs in
          * exactly `828` rows — `450` in `HalfKneelingStretchPose`, `180` in `HamstringStretchPose`, `198` in
          * `ProneCobraStretchPose`, ALL of them limb-chain joints (the arm chain's `ELBOW_*` max `0.0356` u,
          * the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `0.0206` u, the tucked leg's
          * `TOE_B`/`HEEL_B`/`ANKLE_B` `0.0059` u and `KNEE_B` `0.0002` u), with the other `48` production
          * classes byte-identical — `CouchStretchPose` included, which shares this site's authoring helper
          * and is deliberately NOT re-scoped — and every pelvis/spine/girdle/head joint of the three
          * byte-identical too, i.e. the batch is target-side only (a root-side "fix" would have moved their
          * stances). The batch's own gate is `ReachBandBatch3AuthoringTest`.
          *
          * **Re-baselined by the fourth (final) reach-band cleanup batch**
          * (`fix/reach-band-batch4-remaining-candidates`, off the #255 merge `ca011ad`): this corpus is "every
          * production pose class except the classes each correction owns", so it contains the batch's five poses
          * (`CouchStretchPose`, `QuadrupedThoracicRotationsPose`, `GluteBridgePose`, `PelvicTiltPose`,
          * `ThoracicExtensionPose`). Their authored limb targets sat outside their own chains' reachable annulus and
          * are now projected onto it (R2/R4). Observed RED on the pre-baseline value `1787803362589852341` before this re-baseline
          * (the live run measured `3584683981286548246`). Attribution is direct, not inferred: the whole-corpus A/B (`51` classes ×
          * `16` frames — a fresh pipeline's cold first frame plus `15` phases of an advancing pipeline — × every
          * `Joint.entries` XYZ AND every joint rotation, full float bits, `58,464` rows) measured on a worktree of
          * `origin/main` @ `ca011ad` and on this branch differs in exactly `852` rows — `240` in
          * `ThoracicExtensionPose`, `197` in `CouchStretchPose`, `176` in `PelvicTiltPose`, `146` in
          * `GluteBridgePose`, `93` in `QuadrupedThoracicRotationsPose` — i.e. `652` published joint rows, `128`
          * declared-target rows and `72` state rows whose ONLY moving field is the reachability stamp. Every differing
          * joint belongs to the pose's own corrected limb chain (arms: `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/
          * `KNUCKLES_*`/`FINGERTIPS_*`; legs: `KNEE_*`/`ANKLE_*`/`HEEL_*`/`TOE_*`), max published move `0.0324` u
          * (`QuadrupedThoracicRotationsPose`), and the other `46` production classes are byte-identical — every joint
          * ROTATION byte-identical everywhere, every pelvis/spine/girdle/head joint of the five unchanged, and every
          * support set, ground level, environment prop and state flag identical, i.e. the batch is target-side only.
          * The batch's own gate is `ReachBandBatch4AuthoringTest`.
          * **Re-baselined by the canonical-`SkeletonFactory` pose batch**
          * (`fix/canonical-skeletonfactory-pose-batch`, off the #256 merge `4a32d84`): the ten remaining
          * hand-rolled pose trees (`ArmCirclesPose`, `BurpeePose`, `FacePullPose`, `GluteBridgePose`,
          * `HipCarsPose`, `KettlebellSwingPose`, `MountainClimberPose`, `PelvicTiltPose`,
          * `ScapularRetractionPose`, `WallSlidesPose`) adopt `SkeletonFactory.createStandardSkeleton()` (the
          * M11 residue class — the last poses whose five canonical joints published at the world origin), and
          * all ten of them are inside this corpus. Observed RED on the previous value `<3584683981286548246>` before
          * this re-baseline (the live run measured `5910892914285978486`). Attribution is direct, not inferred: the
          * whole-corpus A/B (`51` classes × `16` frames — a fresh pipeline's cold first frame plus `15` advancing
          * phases of a long-lived pipeline — × every `Joint.entries` position AND rotation as FULL FLOAT BITS,
          * plus the published state/stamps, the declared limb targets, the built authoring intents, the support
          * declarations and the environment metadata — `32,403` rows) measured on the pristine base tree
          * (`4a32d84`) and on this tree differs in exactly `800` rows — `80` in each of the ten poses, ALL of
          * them the five canonical joints (`LUMBAR`, `CLAVICLE_A/P`, `SCAPULA_A/P`) × `16` frames, each moving
          * from the world origin to its authored pass-through transform — with every other joint position and
          * rotation, every declared limb target, every stamp, every support set and every environment row
          * byte-identical. The batch's own gate is `CanonicalSkeletonFactoryPoseBatchTest`.
         *
          * **Re-baselined by the animation-coverage phase, batch 1** (`feat/animation-coverage-01`, based on
          * `7df32c0`): this corpus is "every production pose class except its own corrected pose", so the four pose
          * classes the batch ADDED (`HorseStancePose`, `WallSitPose`, `AnkleMobilityPose`, `CalfStretchPose`) entered
          * it. Attribution measured, not inferred: the per-pose digest probe (this guard's own hashing recipe, a fresh
          * pipeline per pose) run on the pristine `origin/main` worktree and on this branch reported **all 51
          * pre-existing pose classes byte-identical** — `0` differing digests, `4` added, `0` removed — on both the
          * `4a32d84` and the rebased `7df32c0` base, so the observed RED was corpus membership, not geometry drift.
          * Pre-rebaseline measurement: `5910892914285978486`.
         *
         * **Re-baselined by the animation-coverage phase, batch 2** (`feat/animation-coverage-02`, based on the
          * #258 merge `6887738`): this corpus is "every production pose class except its own corrected pose", so the
          * four pose classes the batch ADDED (`RowsPose`, `DipsPose`, `BandPullApartPose`, `YTRaisesPose`) entered it
          * (the family base `BaseBarSupportPose` is filtered out of every corpus by the `Base*` rule). Attribution
          * measured, not inferred: the per-pose digest probe (this guard's own hashing recipe, a fresh pipeline per
          * pose) run on the pristine `origin/main` worktree (`6887738`) and on this branch reported **all `55`
          * pre-existing pose classes byte-identical** — `0` differing digests, `4` added, `0` removed — so the
          * observed RED was corpus membership, not geometry drift. Pre-rebaseline measurement: `-3703981788595110599`.
         *
         * **Re-baselined by the animation-coverage phase, batch 3** (`feat/animation-coverage-03`, based on the
         * #259 merge `139daf9`): this corpus is "every production pose class except its own corrected pose", so
         * the four pose classes the batch ADDED (`HipCirclesPose`, `LegSwingsPose`, `NinetyNinetyHipsPose`,
         * `PiriformisStretchPose`) entered it. Attribution measured, not inferred: the per-pose digest probe
         * (this guard's own hashing recipe, a fresh pipeline per pose) run on the pristine `origin/main` worktree
         * (`139daf9`) and on this branch reported **all `59` pre-existing pose classes byte-identical** — `0`
         * differing digests, `4` added, `0` removed — so the observed RED was corpus membership, not geometry
         * drift. Pre-rebaseline measurement: `2590577445452061467`.
                  *
         * **Re-baselined by the animation-coverage phase, batch 4** (`feat/animation-coverage-04`, based on the
         * #260 merge `7fed307`): this corpus is "every production pose class except its own corrected pose", so
         * the two pose classes the batch ADDED (`ChinTuckPose`, `NeckCirclesPose` — the cervical-mobility pair)
         * entered it. Attribution measured, not inferred: the per-pose digest probe (this guard's own hashing
         * recipe, a fresh pipeline per pose) run on the pristine `origin/main` worktree (`7fed307`) and on this
         * branch reported **all `63` pre-existing pose classes byte-identical** — `0` differing digests, `2`
         * added, `0` removed — so the observed RED was corpus membership, not geometry drift. Pre-rebaseline
         * measurement: `6938707527830955053`.
*/
        const val UNAFFECTED_CORPUS_DIGEST = 4676415585897278686L
    }
}
