package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Inverted Bodyweight Row (`row_standard`) — the bar-supported horizontal pull on
 * [BaseBarSupportPose]: the hands are FIXED on the bar and the body is placed from an authored
 * shoulder→bar reach.
 *
 * The catalog's `steps`/`tech` describe a bent-over weighted row; the id, title, BAR equipment and
 * illustration contradict it, and the coverage audit recorded that as stale copy. This pose
 * implements the bar-supported movement the id declares.
 */
class RowsPose : BaseBarSupportPose() {

    override val barGripY = BAR_Y
    override val barGripX = BAR_X
    override val gripWidthFactor = GRIP_WIDTH_FACTOR

    /**
     * The elbow travels DOWN the trunk and behind the shoulder→grip chord (the pulldown path of a
     * horizontal pull): [BaseBarSupportPose] authors the pole in the shoulder's own frame, whose +Y
     * is "up the trunk toward the neck", so `-Y` is down the trunk and `-X` is dorsal. Both
     * components are authored as the pull's own elbow path — a mostly-`-Z` pole flared the elbows
     * `49 u` outboard of the shoulders at the top of the rep (measured), which is a row's classic
     * "elbows out" fault, not the movement.
     */
    override val elbowPoleA = Vector3(-0.45f, -1f, -0.1f)
    override val elbowPoleP = Vector3(-0.45f, -1f, 0.1f)

    /**
     * The athlete's chest stays proud through the pull: a small maintained thoracic extension on top
     * of the body's incline. Authored for the reason [BaseBarSupportPose.trunkThoracicBrace] records —
     * the pose drives the girdle, so its own thoracic value has to be authored rather than inferred.
     */
    override val trunkThoracicBrace = TRUNK_BRACE

    override val metadata = PoseMetadata(
        // The plank family's camera (yaw 1.19, pitch 0.16, zoom 1.22) is the corpus's frame for a body
        // laid out along X; the row is that body, inclined, plus the bar above it.
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.16f, defaultZoom = 1.22f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                BoxProp(center = Vector3(BAR_X, BAR_Y - BAR_SLAB_HALF, 0f), width = 8f, height = 10f, depth = 240f)
            ),
            anchors = listOf(
                EnvironmentAnchor(id = BAR_ANCHOR_ID, type = EnvironmentAnchorType.BAR, worldPosition = Vector3(BAR_X, BAR_Y, 0f))
            )
        ),
        // The hands are the fixed contacts (the pull happens relative to them) and the heels are planted.
        support = SupportDefinition(
            pivot = PivotType.HANDS,
            contacts = setOf(
                SupportContact(point = SupportPoint.LEFT_HAND, anchorId = BAR_ANCHOR_ID),
                SupportContact(point = SupportPoint.RIGHT_HAND, anchorId = BAR_ANCHOR_ID),
                SupportContact(point = SupportPoint.LEFT_FOOT),
                SupportContact(point = SupportPoint.RIGHT_FOOT)
            )
        ),
        exerciseFamily = "bar_support",
        motionType = "Horizontal Pull",
        bodyOrientation = "Inclined",
        defaultGrip = "overhand"
    )

    private val scratchGaze = Vector3()

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy()
        // Shape-driven root (the body is placed from the reach), so the solver leaves it untouched.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val progress = context.progress
        val reach = SkeletonMath.lerp(BOTTOM_REACH, TOP_REACH, progress)
        val incline = inclineFor(reach, def)
        val ux = cos(incline)
        val uy = sin(incline)

        // The pelvis sits on the circle of radius LEG_LINE about the fixed ankles, and the pelvis's
        // own rotation carries the WHOLE body's pitch (the hips/legs inherit it, which is why the
        // single-leg spine rule puts the lower segment on the pelvis).
        pelvis!!.localPosition.set(ANKLE_X + ux * LEG_LINE, FLOOR_ANKLE_Y + uy * LEG_LINE, 0f)
        // One straight body line: the pitch is split across the two spine segments (see
        // [BaseBarSupportPose.trunkThoracicFraction]) so the ankle→hip→shoulder chord is exactly
        // colinear with the trunk — the shoulder the reach is solved against sits at
        // `u(incline) · (LEG_LINE + torsoLength)`.
        buildBarTrunk(incline - halfPi)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // The neck/head bones are written by the gaze resolver (buildGaze below); start them clean so
        // the authoring FK never reads a previous frame's resolved offsets.
        neck!!.localPosition.set(0f, 0f, 0f)
        head!!.localPosition.set(0f, 0f, 0f)

        // The girdle drive is the pull's own driver, and it is authored on the RETRACTION axis the
        // copy names ("squeezing shoulder blades together", `steps` §2). See [TOP_RETRACTION] for why
        // the depression axis is deliberately NOT driven here.
        driveScapula(
            retraction = SkeletonMath.lerp(0f, TOP_RETRACTION, progress),
            depression = 0f
        )
        buildShoulders(def)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        authoringFk()

        val stance = def.hipWidth * STANCE_WIDTH_FACTOR
        targetF.set(ANKLE_X, FLOOR_ANKLE_Y, -stance)
        targetB.set(ANKLE_X, FLOOR_ANKLE_Y, stance)
        bakeLeg(def, hipF!!, kneeF!!, ankleF!!, targetF, -1f, legFBuffer)
        bakeLeg(def, hipB!!, kneeB!!, ankleB!!, targetB, 1f, legBBuffer)
        // Both feet flat on the mat with the toes pointing the way the athlete faces: the engine
        // rotates a declared heading by the pelvis's rotation, so the root-relative direction that
        // resolves to the world +X under this frame's pitch is (sin, cos) of the incline.
        setHeading(Extremity.FOOT_F, Vector3(ux, uy, 0f))
        setHeading(Extremity.FOOT_B, Vector3(ux, uy, 0f))

        barHandTargets(def)
        bakeArmsToBar(def)
        // The grip: a hand lying IN the bar's plane with the fingers along the bar, away from the
        // athlete — the orientation the engine's forearm-composed completion cannot state here (see
        // [BaseBarSupportPose.authorFlatBarGrip]).
        authorFlatBarGrip(def, Extremity.HAND_A, handA!!, palmA!!, knucklesA!!, fingertipsA!!, GRIP_DIRECTION)
        authorFlatBarGrip(def, Extremity.HAND_P, handP!!, palmP!!, knucklesP!!, fingertipsP!!, GRIP_DIRECTION)

        val chestWorld = chest!!.worldPosition
        scratchGaze.set(BAR_X - chestWorld.x, BAR_Y - chestWorld.y, 0f)
        if (scratchGaze.mag() < 1e-4f) scratchGaze.set(0f, 1f, 0f)
        buildGaze(neck!!, head!!, def.neckLength, scratchGaze.normalize())

        return finalizeBarSupportPose()
    }

    /** A planted-leg IK bake: the same sanctioned path, with the family's ventrally-tracking knee. */
    private fun bakeLeg(
        def: SkeletonDefinition,
        hip: SkeletonNode,
        knee: SkeletonNode,
        ankle: SkeletonNode,
        target: Vector3,
        sideSign: Float,
        buffer: SkeletonMath.IKResult
    ) {
        // The knees track over the feet, never inward (the same authored pole convention the standing
        // families use), expressed in the pelvis's frame the legs hang from.
        poleF.set(1f, 0f, -0.2f * sideSign)
        val poleWorld = SkeletonMath.toWorldDirection(poleF, knee.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(hip.worldPosition, target, def.thighLength, def.shinLength, poleWorld, def.legIKConstraint, pelvis!!.worldRotation, knee, ankle, buffer)
    }

    companion object {
        /**
         * The bar the athlete rows from: its top face (the plane the declared hand contacts rest on,
         * and the Y the hands grip at) and the X plane it stands in. Chosen so the body's authored
         * reaches land as [BOTTOM_INCLINE] → [TOP_INCLINE] of incline (measured, asserted by the
         * test), i.e. the bar sits at the chest height of a standing figure (the rig's standing
         * pelvis is `235`, its shoulders `~355`) and far enough in front of the planted heels for a
         * straight body to take the load at both ends of the rep.
         */
        const val BAR_X = 291f
        const val BAR_Y = 285f

        /** The bar's slab: `10` u thick with its top face AT [BAR_Y] — the pull-up family's own prop shape. */
        const val BAR_SLAB_HALF = 5f

        /** The anchor both declared hand contacts name. */
        const val BAR_ANCHOR_ID = "row_bar"

        /** The planted heels (the fixed end of the body line) and their rest height on the mat. */
        const val ANKLE_X = 0f
        const val FLOOR_ANKLE_Y = 25f

        /**
         * The ankle→hip chord: the leg chain's own reachable band is `[56.010, 205.800]`, and the
         * straight-body line needs the longest one the chain can actually take, so it is authored
         * just inside the band's cap (the `0.98` extension cap is the band's own limit, not a pose
         * constant — the corpus's "straight" legs all stop there).
         */
        const val LEG_LINE = 204f

        /** The stance: the ankles at `1.4 × hipWidth`, a stable base the legs can take without a lateral splay. */
        const val STANCE_WIDTH_FACTOR = 1.4f

        /** The grip: `1.35 × shoulderWidth` — the family's own overhand pull-up width (`StandardPullUpPose`). */
        const val GRIP_WIDTH_FACTOR = 1.35f

        /** Arms at the chain's near-full extension (`143.080` is the chain's own cap): the bottom of the row. */
        const val BOTTOM_REACH = 138f

        /** The folded-elbow top: the chest comes to the bar (`~62°` interior elbow, measured). */
        const val TOP_REACH = 76f

        /**
         * `steps` §2's "squeezing shoulder blades together": the girdle's own activation units (the
         * shared `SkeletonMath` constants, as the vertical-pull family authors its pull).
         *
         * **The DEPRESSION axis is deliberately not driven.** `SkeletonPoseFinalizer.
         * reconstructChestFrame` re-derives an UNAUTHORED thorax from the shoulder line, so a girdle
         * rotation whose composition is not a pure retraction is read back into the thorax's roll —
         * and the published shoulders then carry the girdle twice (measured: `12.709 u` of dorsal
         * travel at a 4-unit retraction against the girdle's own `6.4 u`, and with the depression
         * added the two shoulders swing `±12.7 u` asymmetrically). A pure retraction is the fixed
         * point of that fallback (the inferred frame reproduces it exactly), so the grip stays on the
         * bar — which is why the pose drives retraction alone.
         */
        const val TOP_RETRACTION = 4f

        /**
         * The thorax's own maintained extension (radians): the "chest proud" posture of a pull, at the
         * corpus's braced-upper-back scale (`StaticForearmPlankPose`: `0.09`). See
         * [BaseBarSupportPose.trunkThoracicBrace] for why the pose authors it rather than leaving the
         * thorax for the engine's fallback.
         */
        const val TRUNK_BRACE = 0.06f

        const val BOTTOM_INCLINE = 0.3906f

        const val TOP_INCLINE = 0.6360f

        /** How close to the bar's own X plane the shoulder arrives at the top of the pull (measured `34.2 u`). */
        const val BAR_ARRIVAL_X = 40f

        /**
         * The grip's long axis — along the bar, away from the athlete's own body (the direction the
         * fingers wrap over the bar). The bar runs along Z and the athlete's head end is +X.
         */
        val GRIP_DIRECTION = Vector3(1f, 0f, 0f)

        /**
         * The body's incline for a shoulder→bar [reach]: the shoulder rides the circle of radius
         * `LEG_LINE + torsoLength` about the FIXED heels, and the reach names the circle of radius
         * `sagittalSpan(reach)` about the FIXED grip, so the incline is their intersection —
         * `cos(incline − φ) = (Ls² + D² − R²) / (2·Ls·D)`, with `D`/`φ` the fixed ankle→grip vector.
         * The lower branch is the physical one (the body hangs below the bar and rises as the reach
         * shortens); the singular upper branch is where the shoulder would swing past the bar.
         */
        fun inclineFor(reach: Float, def: SkeletonDefinition): Float {
            val lateral = GRIP_WIDTH_FACTOR * def.shoulderWidth - def.shoulderWidth
            val r = sqrt(max(reach * reach - lateral * lateral, 1f))
            val dx = BAR_X - ANKLE_X
            val dy = BAR_Y - FLOOR_ANKLE_Y
            val d = sqrt(dx * dx + dy * dy)
            val ls = LEG_LINE + def.torsoLength
            val c = ((ls * ls + d * d - r * r) / (2f * ls * d)).coerceIn(-1f, 1f)
            val phi = atan2(dy, dx)
            return phi - acos(c)
        }
    }
}
