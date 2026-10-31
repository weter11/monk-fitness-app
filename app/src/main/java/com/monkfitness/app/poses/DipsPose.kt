package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Parallel-Bar Dip (`dip_parallel_bar`) — the bar-supported VERTICAL PRESS on the bar-support pipeline
 * ([BaseBarSupportPose]): the canonical tree, the hands FIXED on the bars, and the body placed from an
 * authored shoulder→bar reach. The shoulders sit ABOVE the bars, which is what distinguishes this
 * movement from the vertical-pull family's hang below the bar.
 *
 * Depth is authored as the copy states it: the bottom of the rep is the reach whose elbow interior angle
 * IS 90° ([ninetyDegreeReach] — for a two-bone chain exactly `sqrt(upperArm² + forearm²)`, so the depth is
 * derived from the definition, not tuned). That depth is also a FLOOR, so `mistakes`' "dipping below 90
 * degrees" is unreachable by construction. The copy presents the torso angle as a CHOICE; this pose
 * authors the forward option ([TRUNK_LEAN]) as a constant and records the upright (triceps) alternative
 * rather than resolving it.
 *
 * `tech`'s two invariants are both authored: a maintained scapular state, and an elbow-bend plane that
 * puts the elbows BEHIND the shoulder→grip chord ([elbowPoleA]/[elbowPoleP], dorsal in the shoulder's own
 * frame) instead of out to the sides.
 *
 * The environment carries TWO real parallel bars — a `BoxProp` slab running front-to-back at each `±gripZ`
 * plus one `PARALLEL_BARS` anchor per hand, with each declared hand contact naming its OWN anchor (the
 * honest statement of a parallel-bar grip). The hands never move; the body's height is the authored reach.
 * The legs hang from the pelvis along the same trunk line with the family's parked knee bend and a
 * plantar-flexed foot, clear of the ground at every phase (asserted by the test).
 *
 * Reach: the shoulder→grip distance runs [LOCKOUT_REACH] → [ninetyDegreeReach], both inside the arm
 * chain's `[40.134, 143.080]` band, and the hip–ankle chord is [LEG_LINE] `= 204`, inside the leg chain's
 * `[56.010, 205.800]` — so the reachability stamp reads `0` and no target is projected.
 *
 * Recorded gaps: the copy says nothing about the legs/feet (parked knee bend + plantar flexion authored,
 * the crossed-ankle / knees-90 variants recorded as unstated); [BAR_Y] is authored so the feet clear the
 * floor with the ground still visible (a height a few units either way is a product decision).
 */
class DipsPose : BaseBarSupportPose() {

    override val barGripY = BAR_Y
    override val barGripX = 0f
    override val gripWidthFactor = GRIP_WIDTH_FACTOR

    /**
     * The elbows travel BACKWARD (dorsal) and only slightly outboard: the shoulder frame's +X is ventral,
     * so `-X` is the dorsal pole that keeps the elbows behind the chord — `ex_dips_tech`'s "elbows don't
     * flare out too much".
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

        // `tech`: "Keep your shoulders down and away from your ears". The girdle is left NEUTRAL, which is
        // that cue's own content — nothing in the rep elevates the shoulders (the test asserts they never
        // rise). A driven DEPRESSION is expressible but not publishable faithfully here:
        // `reconstructChestFrame` re-derives an unauthored thorax from the shoulder line, so the girdle's
        // rotation is read back into the thorax's roll and carried twice (measured `1.5 u` of shoulder→grip
        // error at 1.5 activation units, which would put the copy's own 90° bottom at `88.3°`).
        driveScapula(retraction = 0f, depression = 0f)
        buildShoulders(def)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        authoringFk()

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

        barHandTargets(def)
        bakeArmsToBar(def)
        // The grip is left to the engine's own derivation: a dip's hands stay PLANTED throughout, so
        // declaring the hand contact is enough for the Finalizer to lay the complete hand flat in the bar's
        // plane. Authoring a wrist articulation here would disable that derivation and publish the hand off
        // the bar's face (measured `3.96 u` of penetration at the lockout).

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
         * `ex_dips_steps` §2's depth: the reach whose elbow interior angle is exactly 90° — for a two-bone
         * chain `sqrt(L1² + L2²)`, derived from the definition, so the authored bottom IS the "90-degree
         * angle" the copy names (and "below 90 degrees" is therefore unreachable by construction).
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
