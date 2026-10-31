package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Wall Sit (`wall_sit_hold`) — the isometric leg hold with the back on a wall. What this variant owns is
 * the wall-seat geometry: the pelvis is pinned at one X ([PELVIS_X], `20` u of body-volume standoff from
 * the wall's face) and travels only in Y, so the descent SLIDES along the wall rather than hinging
 * forward; the hold's foot position is one thigh length ahead of the hip, so the thigh is horizontal and
 * the shin vertical (~90° knee).
 *
 * The back is deliberately NOT declared as a support contact: a declared contact whose centroid falls
 * inside a wall's footprint is re-oriented onto the wall's face (as `WallSlidesPose` records), so
 * the back-on-wall relationship is authored geometry with a measured standoff.
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
        /** The wall's contact plane: the face toward the athlete. `WallProp.width` is the slab's X extent,
         *  and this wall stands BEHIND the body, so the plane is its `+X` face. */
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
