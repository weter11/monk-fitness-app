package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Wall Sit (`wall_sit_hold`) — the isometric leg hold with the back on a wall, authored on the
 * canonical `BaseSquatPose` pipeline (SkeletonFactory tree + declared pelvis tilt + registered
 * package bake limbs). What this variant owns is the wall-seat geometry:
 *
 *  - **the back on the wall, the hips sliding DOWN its plane** — `ex_wall_sit_steps` §1/§2. The
 *    pelvis is pinned at one X ([PELVIS_X], `20` u of body-volume standoff from the wall's contact
 *    face) and travels only in Y, so the descent is a slide along the wall rather than the family's
 *    forward-hinging squat. The wall is a real [WallProp] in the environment (H1's lesson from
 *    `WallSlidesPose`: an exercise defined "against the wall" must carry the wall).
 *  - **"until the knees are about 90 degrees"** — the hold's authored foot position is exactly one
 *    thigh length in front of the pinned hip, so at the hold the thigh is horizontal, the shin
 *    vertical and the interior knee angle `~90°`. Reaching that geometry is why the feet sit `112` u
 *    forward of the wall: with the shins vertical the ankle is under the knee, and the knee is a
 *    thigh length ahead of the seat. (`mistakes` "Feet set too close" is exactly this distance
 *    under-shot.)
 *  - **"Keep the feet flat"** — both feet are declared (`LEFT_FOOT`/`RIGHT_FOOT`) and authored at the
 *    family's floor frame, so the engine flattens them onto the ground plane they rest on.
 *  - **"Spread the weight across the full foot"** — the ankles sit at the family's natural stance
 *    width (`±1.5 · hipWidth`); the steps state no width for this exercise.
 *  - **`mistakes`: "Hands pushing on the thighs"** — the arms hang beside the body, `12` u below the
 *    hip line and just outboard of it, clear of the thigh segment.
 *
 * ## The wall contract, and what is NOT declared
 *
 * The wall's contact plane is the face TOWARD the athlete — here the `+X` face of the slab (the wall
 * is behind the body, so its face pointing back at the athlete is `center.x + width/2`, M15's
 * convention). [wallFaceX] is that plane and is the reference the test measures the standoff against.
 *
 * The back itself is deliberately NOT declared as a support contact, for the reason `WallSlidesPose`
 * and `LatStretchPose` record: the declaration channel resolves a contact's surface from its prop
 * footprint, and M15 measured that a declared contact whose centroid falls inside a wall's footprint
 * is re-oriented onto the wall's face — an engine-side residual, not something a pose should paper
 * over. The back-on-wall relationship is therefore authored geometry with a measured standoff, and
 * the residual is recorded here rather than resolved.
 *
 * ## Reach
 *
 * Both ends of the slide are inside the leg chain's `[56.01, 205.80]` band by construction (measured
 * chords `203.53` at the top of the slide, `148.99` at the hold), so the reach stamp reads `0` and no
 * projection helper is needed — the naive clone this batch's RED run measured instead clamped the
 * standing leg `30.93` u and slid the planted ankle `10.74` u.
 */
class WallSitPose : BaseSquatPose() {

    override val squatH = HOLD_PELVIS_Y
    override val pelvisXEnd = PELVIS_X
    override val leanAngleEnd = HOLD_LEAN
    override val armLeanEnd = 0f

    /** Knees track with the thighs, not inward (`tech`: "over the middle toes"). */
    override val legPoleF = Vector3(1f, 0f, -0.2f)
    override val legPoleB = Vector3(1f, 0f, 0.2f)
    override val armPoleA = Vector3(0f, -1f, -1f)
    override val armPoleP = Vector3(0f, -1f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.1f),
        durationSeconds = 3.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(wallProp())
        ),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        ),
        exerciseFamily = "wall_sit",
        motionType = "Isometric Hold",
        bodyOrientation = "upright"
    )

    /** The slide: pinned X, `TALL_PELVIS_Y → HOLD_PELVIS_Y`, trunk vertical throughout. */
    override fun computePelvis(progress: Float, def: SkeletonDefinition): Triple<Float, Float, Float> =
        Triple(
            SkeletonMath.lerp(TALL_PELVIS_Y, HOLD_PELVIS_Y, progress),
            PELVIS_X,
            SkeletonMath.lerp(TALL_LEAN, HOLD_LEAN, progress)
        )

    override fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        // Planted for the whole hold: the seat slides, the feet do not.
        outF.set(FOOT_X, FLOOR_ANKLE_Y, -def.hipWidth * 1.5f)
        outB.set(FOOT_X, FLOOR_ANKLE_Y, def.hipWidth * 1.5f)
    }

    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        // Relaxed at the sides (`tech`: "Relax the shoulders and jaw"), clear of the thighs.
        val handX = pelvisX + HAND_FORWARD
        val handY = pelvisY + HAND_BELOW_HIP
        outA.set(handX, handY, -(def.shoulderWidth - HAND_INSET))
        outP.set(handX, handY, def.shoulderWidth - HAND_INSET)
    }

    override fun articulateExtras(def: SkeletonDefinition, progress: Float, leanAngle: Float, footLift: Float) {
        // "Keep the feet flat" with the toes pointed ahead (the family's neutral heading, declared
        // explicitly so a future edit cannot drift them outward).
        setHeading(Extremity.FOOT_F, Vector3(1f, 0f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(1f, 0f, 0f))
    }

    companion object {
        /**
         * The wall's contact plane: the face toward the athlete. `WallProp.width` is the slab's X
         * extent, and this wall stands BEHIND the body, so the plane is its `+X` face.
         */
        fun wallFaceX(prop: WallProp): Float = prop.center.x + prop.width / 2f

        /** The athlete-side face of the wall and the slab behind it. */
        private const val WALL_FACE_X = -62f
        private const val WALL_THICKNESS = 8f
        /** Tall enough to back the whole trunk, and above every joint (so no validator rule keys on its top edge). */
        private const val WALL_HEIGHT = 300f
        private const val WALL_DEPTH = 150f

        private fun wallProp() = WallProp(
            center = Vector3(WALL_FACE_X - WALL_THICKNESS * 0.5f, WALL_HEIGHT * 0.5f, 0f),
            width = WALL_THICKNESS,
            height = WALL_HEIGHT,
            depth = WALL_DEPTH
        )

        /** The pinned seat: the hips stay on this X plane for the whole slide (wall + standoff). */
        const val PELVIS_X = -42f

        /** The hold: thigh horizontal + shin vertical ⇒ the interior knee angle is ~90°. */
        private const val HOLD_PELVIS_Y = 123f
        private const val HOLD_LEAN = 0.0f

        /** The top of the slide: legs nearly extended, back still on the wall. */
        private const val TALL_PELVIS_Y = 195f
        private const val TALL_LEAN = 0.03f

        /** `PELVIS_X + thighLength` — one thigh length ahead of the seat, so the shins go vertical. */
        private const val FOOT_X = PELVIS_X + 112f
        private const val FLOOR_ANKLE_Y = 25f

        private const val HAND_FORWARD = 10f
        private const val HAND_BELOW_HIP = -12f
        private const val HAND_INSET = 2f
    }
}
