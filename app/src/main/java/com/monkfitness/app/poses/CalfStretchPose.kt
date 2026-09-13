package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Calf Stretch (`calf_stretch_hold`) — the standing wall calf stretch, authored on the canonical
 * `BaseSquatPose` pipeline (SkeletonFactory tree + declared pelvis tilt + registered package bake
 * limbs). The unilateral stretch's one side, like the other unilateral registry poses: the F leg is
 * the front (bent) leg, the B leg the back (straight) one.
 *
 *  - **"Face a wall and place both hands on it"** — a real [WallProp] ahead of the athlete; both hands
 *    are authored ON its contact plane and stay there for the whole stretch (the arms re-solve as the
 *    body travels, i.e. the elbows bend, which is what leaning into a wall does).
 *  - **"Step one leg back with the heel flat … keep the back leg straight and the foot pointed ahead"**
 *    — the back ankle is authored `130` u behind the front one, its foot declared (`RIGHT_FOOT`) and
 *    planted for the whole cycle, with the toes pointed ahead; the back leg's extension is pinned
 *    ≥ `140°` interior at every phase, and the back knee is never allowed to bend into the stretch
 *    (`mistakes`: "Letting the back heel lift. Turning the back foot out.").
 *  - **"Bend the front knee and shift forward until the back calf stretches"** — the body travels
 *    forward `20` u while both feet stay planted; the front knee's interior angle closes by ~`14°`
 *    and the back leg's stays open. The hold's cycle is the shift-and-return of the LOOP convention.
 *  - **"Square the hips toward the wall"** — the stride is a pure fore/aft split, so the two hip
 *    joints stay level (no pelvic roll is authored) and the stance is front/back rather than lateral.
 *
 * ## Reach (`everyAuthoredTargetIsInsideItsChainsReach` pins this)
 *
 * The forward travel is bounded by the BACK leg's own band: at the end of the shift the back
 * hip→ankle chord measures `202.66` u against the leg's `205.80` u cap (`0.98 · 210`). The two arm
 * targets stay inside the arm chain's `[40.13, 143.00]` band at both ends of the shift (measured
 * chords `121.53` and `101.37`), so the reach stamp reads `0` with no projection helper.
 *
 * ## The wall's contact plane
 *
 * In front of the athlete, so the plane facing the body is the slab's `-X` face ([wallFaceX]); the
 * hands are authored on it. The hands are deliberately NOT declared as support contacts: a declared
 * contact whose centroid falls in a wall's footprint is re-oriented onto the wall (the M15 residual),
 * and this stretch's wall relationship is authored geometry — the same treatment `LatStretchPose` and
 * `WallSlidesPose` ship.
 */
class CalfStretchPose : BaseSquatPose() {

    override val squatH = HOLD_PELVIS_Y
    override val pelvisXEnd = HOLD_PELVIS_X
    override val leanAngleEnd = HOLD_LEAN
    override val armLeanEnd = 0f

    override val legPoleF = Vector3(1f, 0f, -0.25f)
    override val legPoleB = Vector3(1f, 0f, 0.2f)
    override val armPoleA = Vector3(0f, 1f, -1f)
    override val armPoleP = Vector3(0f, 1f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.15f),
        durationSeconds = 3.0f,
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
        motionType = "Hold",
        bodyOrientation = "upright"
    )

    /** The shift: forward `20` u and down `10` u, on a front/back split. */
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
        // Front foot under the body, back foot a stride behind — both planted for the whole hold.
        outF.set(FRONT_FOOT_X, FLOOR_ANKLE_Y, -def.hipWidth * 1.5f)
        outB.set(BACK_FOOT_X, FLOOR_ANKLE_Y, def.hipWidth * 1.5f)
    }

    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        // Both hands on the wall — a fixed world position the traveling body re-solves against.
        outA.set(WALL_FACE_X, HAND_Y, -def.shoulderWidth * 0.8f)
        outP.set(WALL_FACE_X, HAND_Y, def.shoulderWidth * 0.8f)
    }

    override fun articulateExtras(def: SkeletonDefinition, progress: Float, leanAngle: Float, footLift: Float) {
        // "the foot pointed ahead" — both feet, explicitly (this is also the mistake "turning the back
        // foot out", so the contract is declared rather than left to the neutral derivation).
        setHeading(Extremity.FOOT_F, Vector3(1f, 0f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(1f, 0f, 0f))
    }

    companion object {
        /**
         * The wall's contact plane: the face TOWARD the athlete (the slab's `-X` face — this wall is
         * in front of the body, mirroring M15's behind-the-back `+X` convention).
         */
        fun wallFaceX(prop: WallProp): Float = prop.center.x - prop.width / 2f

        private const val WALL_FACE_X = 62f
        private const val WALL_THICKNESS = 8f
        private const val WALL_HEIGHT = 300f
        private const val WALL_DEPTH = 150f

        private fun wallProp() = WallProp(
            center = Vector3(WALL_FACE_X + WALL_THICKNESS * 0.5f, WALL_HEIGHT * 0.5f, 0f),
            width = WALL_THICKNESS,
            height = WALL_HEIGHT,
            depth = WALL_DEPTH
        )

        /** Stride: front ankle `130` u ahead of the back one. */
        private const val FRONT_FOOT_X = 35f
        private const val BACK_FOOT_X = -95f
        private const val FLOOR_ANKLE_Y = 25f

        /** Chest-height wall contact for a leaning stretch. */
        private const val HAND_Y = 230f

        private const val START_PELVIS_X = -5f
        private const val START_PELVIS_Y = 205f
        private const val START_LEAN = 0.06f

        private const val HOLD_PELVIS_X = 15f
        private const val HOLD_PELVIS_Y = 195f
        private const val HOLD_LEAN = 0.14f
    }
}
