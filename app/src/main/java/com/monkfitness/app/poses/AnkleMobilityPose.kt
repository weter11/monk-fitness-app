package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Ankle Mobility (`ankle_mobility_standard`) — the knee-over-toe drill in a split stance facing a
 * wall, authored on the canonical `BaseSquatPose` pipeline (SkeletonFactory tree + declared pelvis
 * tilt + registered package bake limbs). This is the drill's one (unilateral) side, like the other
 * unilateral registry poses (Cossack, Hip CARs, hip-flexor stretch): the F leg is the front foot, the
 * B leg the back one.
 *
 *  - **"Face a wall in a split stance"** — the F ankle is authored `125` u ahead of the B ankle; the
 *    wall is a real [WallProp] ahead of the athlete, on the side the drill drives toward.
 *  - **"Keep the front heel down"** — the front foot is declared (`LEFT_FOOT`) and its ankle target is
 *    fixed for the whole cycle, so the heel cannot lift (`mistakes`: "Heel lifting off the floor").
 *  - **"drive the front knee toward the wall"** — the body travels forward (`pelvisX 0 → 20`) while
 *    the feet stay planted; the front shin therefore tips over the foot and the knee advances toward
 *    the wall. The motion is a rep (`steps` §2–§4: drive, pause, ease back), so the cycle is the
 *    drive-and-return of the LOOP convention.
 *  - **"Let the knee track over the second toe"** — the front knee's forward position stays over its
 *    own foot (asserted against the toe) and its lateral drift stays inside the hip width, so the
 *    "foot collapsing inward" mistake cannot appear silently.
 *  - **the back leg** stays near-straight with the heel down and the foot pointed ahead: it is
 *    declared `RIGHT_FOOT`, planted, and its extension is pinned ≥ `140°` interior.
 *
 * ## Reach (`everyAuthoredTargetIsInsideItsChainsReach` pins this)
 *
 * The forward travel is bounded by the BACK leg's own band, not by taste: at the end of the drive the
 * back hip→ankle chord measures `202.66` u against the leg's `205.80` u extension cap (`0.98 · 210`),
 * i.e. the drill stops where the back leg does. Both foot targets stay inside their bands by
 * construction, so the reach stamp reads `0` and no projection helper is used.
 *
 * ## The wall's contact plane
 *
 * This wall stands in FRONT of the athlete, so the face toward the body is the slab's `-X` face
 * ([wallFaceX]) — the mirror of M15's "the +X face is the contact plane" (which assumes a wall
 * behind the back). The knee's approach is measured against that plane. No wall contact is declared
 * (the drill's hands are free, on the hips): a declared contact inside a wall's footprint would be
 * re-oriented onto the wall (the M15 residual), which no part of this drill asks for.
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

    /** The drive: the body travels forward and sinks slightly; both feet stay planted. */
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
        // Front foot forward, back foot behind: a real split, planted for the whole drill.
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
        /**
         * The wall's contact plane: the face TOWARD the athlete. This wall is in front of the body,
         * so the plane is the slab's `-X` face (mirror of M15's behind-the-back convention).
         */
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
