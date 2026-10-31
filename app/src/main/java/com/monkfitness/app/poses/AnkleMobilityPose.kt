package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Ankle Mobility (`ankle_mobility_standard`): the unilateral knee-over-toe drill in a split stance
 * facing a [WallProp] ahead of the athlete, on the canonical `BaseSquatPose` pipeline. The F leg is
 * the front foot, the B leg the back one.
 *
 * The front foot is declared and its ankle target fixed for the whole cycle, so the heel cannot lift
 * while the body travels forward into the knee. The forward travel is bounded by the BACK leg's own
 * band, not by taste: at the end of the drive the back hip→ankle chord is `202.66` u against the
 * `205.80` u cap (`0.98 · 210`), so the drill stops where the back leg does and no projection helper
 * is used.
 *
 * This wall stands in FRONT of the athlete, so its contact plane is the slab's `-X` face
 * ([wallFaceX]) — the mirror of the behind-the-back convention. No wall contact is declared: the
 * hands are free, on the hips, and a declared contact inside a wall's footprint would be re-oriented
 * onto the wall (the wall-plane residual), which no part of this drill asks for.
 */
class AnkleMobilityPose : BaseSquatPose() {

    override val squatH = HOLD_PELVIS_Y
    override val pelvisXEnd = HOLD_PELVIS_X
    override val leanAngleEnd = HOLD_LEAN
    override val armLeanEnd = 0f

    override val legPoleF = Vector3(1f, 0f, -0.3f)
    override val legPoleB = Vector3(1f, 0f, 0.2f)
    override val armPoleA = Vector3(0f, -1f, -1f)
    override val armPoleP = Vector3(0f, -1f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.15f),
        durationSeconds = 2.5f,
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
        exerciseFamily = "ankle_mobility",
        motionType = "Repetition",
        bodyOrientation = "upright"
    )

    override fun computePelvis(progress: Float, def: SkeletonDefinition): Triple<Float, Float, Float> =
        Triple(
            SkeletonMath.lerp(START_PELVIS_Y, HOLD_PELVIS_Y, progress),
            SkeletonMath.lerp(START_PELVIS_X, HOLD_PELVIS_X, progress),
            SkeletonMath.lerp(START_LEAN, HOLD_LEAN, progress)
        )

    override fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        outF.set(FRONT_FOOT_X, FLOOR_ANKLE_Y, -def.hipWidth * 1.5f)
        outB.set(BACK_FOOT_X, FLOOR_ANKLE_Y, def.hipWidth * 1.5f)
    }

    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        // Hands on the hips: the drill's range is measured at the knee, not braced with the hands.
        val handX = pelvisX + 6f
        val handY = pelvisY + 6f
        outA.set(handX, handY, -(def.hipWidth + 5f))
        outP.set(handX, handY, def.hipWidth + 5f)
    }

    override fun articulateExtras(def: SkeletonDefinition, progress: Float, leanAngle: Float, footLift: Float) {
        // The back foot is "pointed ahead" and the front foot keeps its own line: no toe-out here
        // (`mistakes`: "Foot collapsing inward" is a tracking fault, not a heading one).
        setHeading(Extremity.FOOT_F, Vector3(1f, 0f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(1f, 0f, 0f))
    }

    companion object {
        fun wallFaceX(prop: WallProp): Float = prop.center.x - prop.width / 2f

        /** The drill's target surface: just ahead of the knee's end-of-drive position. */
        private const val WALL_FACE_X = 92f
        private const val WALL_THICKNESS = 8f
        private const val WALL_HEIGHT = 300f
        private const val WALL_DEPTH = 150f

        private fun wallProp() = WallProp(
            center = Vector3(WALL_FACE_X + WALL_THICKNESS * 0.5f, WALL_HEIGHT * 0.5f, 0f),
            width = WALL_THICKNESS,
            height = WALL_HEIGHT,
            depth = WALL_DEPTH
        )

        /** Split stance: front ankle `125` u ahead of the back one. */
        private const val FRONT_FOOT_X = 30f
        private const val BACK_FOOT_X = -95f
        private const val FLOOR_ANKLE_Y = 25f

        /** The drive: forward `20` u, sinking `10` u as the front knee takes the load. */
        private const val START_PELVIS_X = 0f
        private const val START_PELVIS_Y = 205f
        private const val START_LEAN = 0.05f

        private const val HOLD_PELVIS_X = 20f
        private const val HOLD_PELVIS_Y = 195f
        private const val HOLD_LEAN = 0.12f
    }
}
