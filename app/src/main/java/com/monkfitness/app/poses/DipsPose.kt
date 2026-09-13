package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Parallel-Bar Dip (`dip_parallel_bar`) — the bar-supported VERTICAL PRESS, authored on the
 * bar-support pipeline ([BaseBarSupportPose]): the canonical `SkeletonFactory` tree, the hands FIXED
 * on the bars, and the body placed from an authored shoulder→bar reach.
 *
 * ## What the exercise is (the catalog's own statement, `WorkoutGenerator` + `strings.xml`)
 *
 * `baseRepExercise("dips", "dips", "dip_parallel_bar", …, requiredEquipment = setOf(Equipment.BAR))`.
 * The steps are explicit about the machine and the depth:
 *
 *  * `ex_dips_steps` §1 *"Grip parallel bars and lift yourself up with locked arms"* — the TOP of the
 *    rep: the arms are at the chain's near-full extension, and the body is SUPPORTED on the hands
 *    (the shoulders are ABOVE the bars, which is what distinguishes this movement from the
 *    vertical-pull family's hang below the bar).
 *  * §2 *"Lower your body by bending elbows until they are at a 90-degree angle"* — the depth is
 *    authored as the exercise states it: the bottom of the rep is the reach whose elbow interior
 *    angle IS 90° ([ninetyDegreeReach]: for a two-bone chain that is exactly
 *    `sqrt(upperArm² + forearm²)`, so the depth is derived from the definition, not tuned).
 *  * §3 *"Keep your torso slightly leaned forward for chest emphasis or upright for triceps"* — the
 *    copy itself presents the torso angle as a CHOICE. This pose authors the first option
 *    ([TRUNK_LEAN]) as a constant through the rep (the copy does not say it changes) and records the
 *    alternative rather than resolving it.
 *  * §4 *"Push back up to the starting position"* — the rep returns to the lockout.
 *  * `ex_dips_tech` *"Keep your shoulders down and away from your ears… Ensure elbows don't flare out
 *    too much"* — the two girdle/elbow invariants the pose authors: a maintained scapular DEPRESSION,
 *    and an elbow-bend plane that puts the elbows BEHIND the shoulder→grip chord
 *    ([elbowPoleA]/[elbowPoleP], dorsal in the shoulder's own frame) instead of out to the sides.
 *  * `ex_dips_mistakes` *"dipping too deep (below 90 degrees)"* — the authored depth is a FLOOR as
 *    well: the reach never goes below [ninetyDegreeReach], so the rep cannot dip past the 90° the
 *    copy forbids.
 *
 * ## The authored movement
 *
 *  * **Two real parallel bars.** `ExerciseSkeletonData` groups `dip_parallel_bar` with
 *    `pike_pushup_standard`, but the exercise is performed on a pair of bars, one under each hand, so
 *    the environment carries TWO `BoxProp` slabs running front-to-back at `±gripZ` plus one
 *    `PARALLEL_BARS` anchor per hand. The two declared hand contacts name their OWN anchor (not one
 *    shared bar id), which is the honest statement of a parallel-bar grip.
 *  * **The hands never move; the body does.** Both hands are declared supports
 *    (`PivotType.HANDS`) and are IK'd to constants; the body's height is the authored reach.
 *  * **The body hangs below the shoulders**, so nothing supports the feet: the legs hang from the
 *    pelvis along the same trunk line with the family's parked knee bend and a plantar-flexed foot
 *    (the Vertical-Pull family's own hanging-foot convention), clear of the ground at every phase
 *    (measured minimum clearance is asserted by the test).
 *  * **The trunk leans forward by [TRUNK_LEAN]**, which sweeps the hips/shins back behind the bars —
 *    the visible consequence of the lean the copy names.
 *
 * ## Reach
 *
 * The shoulder→grip distance runs [LOCKOUT_REACH] (arms locked out at the top) → [ninetyDegreeReach]
 * (the elbow at 90° at the bottom), both inside the arm chain's `[40.134, 143.080]` band, and the
 * hip–ankle chord is [LEG_LINE] `= 204`, inside the leg chain's `[56.010, 205.800]` — so the engine's
 * reachability stamp reads `0` and no target is projected.
 *
 * ## Gaps recorded (not invented)
 *
 *  * **The foot posture.** The copy says nothing about the legs/feet. The pose hangs the legs with
 *    the family's parked knee bend and a plantar-flexed foot, and records that the "crossed ankles" /
 *    "knees bent 90°" variants the exercise is often taught with are not stated by the app's copy.
 *  * **The bar height.** The copy names the machine, not the height. [BAR_Y] is authored so the
 *    athlete's feet clear the floor with the ground still visible under them (measured clearance
 *    asserted by the test) — a bar height a few units either way is a product decision, not a
 *    biomechanical one, and is recorded as such.
 *  * **The trunk angle is a copy-stated choice** (§3 above): the upright (triceps) variant is not
 *    authored and is recorded rather than resolved.
 */
class DipsPose : BaseBarSupportPose() {

    override val barGripY = BAR_Y
    override val barGripX = 0f
    override val gripWidthFactor = GRIP_WIDTH_FACTOR

    /**
     * The elbows travel BACKWARD (dorsal) and only slightly outboard: the shoulder's own frame's +X is
     * ventral (the direction the chest faces), so `-X` is the dorsal pole that keeps the elbows
     * behind the chord — `ex_dips_tech`'s "elbows don't flare out too much".
     */
    override val elbowPoleA = Vector3(-1f, 0f, -0.2f)
    override val elbowPoleP = Vector3(-1f, 0f, 0.2f)

    override val metadata = PoseMetadata(
        // A hanging body below a bar: the vertical-pull family's own camera frames exactly this shape.
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.1f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            // Two parallel bars, one under each hand, running front-to-back (X) at ±gripZ.
            props = listOf(
                BoxProp(center = Vector3(0f, BAR_Y - BAR_SLAB_HALF, -barZ()), width = 240f, height = 10f, depth = 8f),
                BoxProp(center = Vector3(0f, BAR_Y - BAR_SLAB_HALF, barZ()), width = 240f, height = 10f, depth = 8f)
            ),
            anchors = listOf(
                EnvironmentAnchor(id = BAR_A_ANCHOR_ID, type = EnvironmentAnchorType.PARALLEL_BARS, worldPosition = Vector3(0f, BAR_Y, -barZ())),
                EnvironmentAnchor(id = BAR_P_ANCHOR_ID, type = EnvironmentAnchorType.PARALLEL_BARS, worldPosition = Vector3(0f, BAR_Y, barZ()))
            )
        ),
        // The hands are the ONLY support: the athlete hangs from the bars, nothing under the feet.
        support = SupportDefinition(
            pivot = PivotType.HANDS,
            contacts = setOf(
                SupportContact(point = SupportPoint.LEFT_HAND, anchorId = BAR_A_ANCHOR_ID),
                SupportContact(point = SupportPoint.RIGHT_HAND, anchorId = BAR_P_ANCHOR_ID)
            )
        ),
        exerciseFamily = "bar_support",
        motionType = "Vertical Press",
        bodyOrientation = "Hanging",
        defaultGrip = "overhand"
    )

    private val scratchGaze = Vector3()

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy()
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val progress = context.progress
        // Lockout at the top of the rep, the copy's own 90° elbow at the bottom.
        val reach = SkeletonMath.lerp(LOCKOUT_REACH, ninetyDegreeReach(def), progress)
        val span = sagittalSpan(def, reach)

        // --- 1. The body: hung below the shoulders, on the authored trunk line ----------------
        // The shoulder sits squarely above the fixed grip (only the lateral gap separates them), and
        // the trunk continues down-forward from it, so the hips/shins sweep back as the copy's lean
        // implies. The pelvis is the root: place it one torso length down the trunk from the chest.
        val ux = sin(TRUNK_LEAN)
        val uy = cos(TRUNK_LEAN)
        val chestX = barGripX
        val chestY = barGripY + span
        pelvis!!.localPosition.set(chestX - ux * def.torsoLength, chestY - uy * def.torsoLength, 0f)
        // The trunk is one straight line leaning forward by TRUNK_LEAN, its pitch split across the two
        // spine segments ([BaseBarSupportPose.trunkThoracicFraction]).
        buildBarTrunk(-TRUNK_LEAN)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, 0f, 0f)
        head!!.localPosition.set(0f, 0f, 0f)

        // `tech`: "Keep your shoulders down and away from your ears". The girdle is left NEUTRAL —
        // which is that cue's own content, since nothing in the rep elevates the shoulders (the test
        // asserts the shoulders never rise). A driven DEPRESSION is expressible but not publishable
        // faithfully here: `SkeletonPoseFinalizer.reconstructChestFrame` re-derives an unauthored
        // thorax from the shoulder line, so the girdle's rotation is read back into the thorax's roll
        // and carried by the shoulders twice (measured `1.5 u` of shoulder→grip error at 1.5
        // activation units, which would put the copy's own "90-degree" bottom at `88.3°`).
        driveScapula(retraction = 0f, depression = 0f)
        buildShoulders(def)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        authoringFk()

        // --- 2. The legs hang from the pelvis along the same line (nothing supports them) ------
        val stance = def.hipWidth * STANCE_WIDTH_FACTOR
        val hipFWorld = hipF!!.worldPosition
        targetF.set(hipFWorld.x - ux * LEG_LINE, hipFWorld.y - uy * LEG_LINE, -stance)
        val hipBWorld = hipB!!.worldPosition
        targetB.set(hipBWorld.x - ux * LEG_LINE, hipBWorld.y - uy * LEG_LINE, stance)
        bakeLeg(def, hipF!!, kneeF!!, ankleF!!, targetF, -1f, legFBuffer)
        bakeLeg(def, hipB!!, kneeB!!, ankleB!!, targetB, 1f, legBBuffer)
        // A hanging foot points: the family's own plantar flexion, authored through the ankle
        // articulation so it survives the neutral-direction pitch clamp (R1 guards the NEUTRAL
        // direction only — the authored articulation is what points the toe).
        buildAnkleArticulation(Extremity.FOOT_F, -PLANTAR_FLEXION, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, -PLANTAR_FLEXION, 0f, ankleB!!)

        // --- 3. The arms: FIXED grips on the two parallel bars ----------------------------------
        barHandTargets(def)
        bakeArmsToBar(def)
        // The grip is left to the engine's own derivation: a dip's hands stay PLANTED throughout
        // (the elbow is above the grip at every phase), so declaring the hand contact is enough for
        // the Finalizer to lay the complete hand flat in the bar's own plane — the same path every
        // pressing pose uses. Authoring a wrist articulation here would disable that derivation and
        // publish the hand off the bar's face (measured `3.96 u` of penetration at the lockout).

        // --- 4. Gaze: forward and slightly down over the bars (authored by convention; the copy is
        // silent on the head — recorded in the KDoc rather than presented as specification). ------
        scratchGaze.set(0.97f, -0.24f, 0f).normalize()
        buildGaze(neck!!, head!!, def.neckLength, scratchGaze)

        return finalizeBarSupportPose()
    }

    private fun bakeLeg(
        def: SkeletonDefinition,
        hip: SkeletonNode,
        knee: SkeletonNode,
        ankle: SkeletonNode,
        target: Vector3,
        sideSign: Float,
        buffer: SkeletonMath.IKResult
    ) {
        poleF.set(1f, 0f, -0.2f * sideSign)
        val poleWorld = SkeletonMath.toWorldDirection(poleF, knee.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(hip.worldPosition, target, def.thighLength, def.shinLength, poleWorld, def.legIKConstraint, pelvis!!.worldRotation, knee, ankle, buffer)
    }

    companion object {
        /** The bars' top face — where the hands grip and the declared hand contacts rest. */
        const val BAR_Y = 260f

        /** The slab half-thickness (`10` u thick, top face AT [BAR_Y]). */
        const val BAR_SLAB_HALF = 5f

        /** The two anchors the declared hand contacts name (one per hand: parallel bars). */
        const val BAR_A_ANCHOR_ID = "dip_bar_a"
        const val BAR_P_ANCHOR_ID = "dip_bar_p"

        /** Grip width: the bars just outside the shoulders (`1.1 × shoulderWidth`). */
        const val GRIP_WIDTH_FACTOR = 1.1f

        /** The bars' own lateral position — the grip width as a world Z (the same value the hands take). */
        fun barZ(): Float = 1.1f * SkeletonDefinition.DEFAULT_ADULT.shoulderWidth

        /** The arms locked out at the top: the chain's near-full extension (`143.080` is its own cap). */
        const val LOCKOUT_REACH = 138f

        /**
         * `ex_dips_steps` §2's depth: the reach whose elbow interior angle is exactly 90°, which for a
         * two-bone chain is `sqrt(L1² + L2²)` — derived from the definition, so the authored bottom of
         * the rep IS the "90-degree angle" the copy names (and `mistakes`' "below 90 degrees" is
         * therefore unreachable by construction).
         */
        fun ninetyDegreeReach(def: SkeletonDefinition): Float =
            sqrt(def.upperArmLength * def.upperArmLength + def.forearmLength * def.forearmLength)

        /** `ex_dips_steps` §3's first option ("torso slightly leaned forward"), held constant. */
        const val TRUNK_LEAN = 0.1745f

        /** The ankle→hip chord: just inside the leg chain's `[56.010, 205.800]` band. */
        const val LEG_LINE = 204f

        /** The hanging feet: `1.2 × hipWidth` apart (the family's own hanging stance). */
        const val STANCE_WIDTH_FACTOR = 1.2f

        /** The Vertical-Pull family's own hanging-foot plantar flexion (`BaseVerticalPullPose`). */
        const val PLANTAR_FLEXION = 0.6f
    }
}
