package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class DiamondPushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 0.1f

    /**
     * The elbow bend side for the diamond grip: the pose's own **trunk long axis** (+X in world;
     * the flat plank lies along +X at every progress — measured `CHEST -> PELVIS = (+1, 0, 0)`).
     *
     * The family's default pole is Z-dominant (`(1, 0.5, -1)`), which is correct for every grip whose
     * hands sit at or outside the shoulder line: there the shoulder->hand chord is a lateral line and the
     * pole's Z component selects the elbow's lateral side. This variant's grip is `0.1`, so the hands come
     * to the fused diamond base at the midline (`shoulderWidth * 0.1 = 4.6` against the shoulder joint's
     * `46`) — the chord itself runs INWARD and a Z-dominant pole no longer selects a lateral side.
     *
     * Measured with the inherited shape: at the bottom (p = 0.5) the pole's perpendicular residual collapses
     * to `phat = (0.388, -0.553, -0.737)` — pointing DOWN — and the elbow was realized at
     * `(-34.29, -19.91, -62.75)`: **19.91 u below the mat this pose declares**, from p ~ 0.33 to 0.67,
     * flared 16.8 u outside the shoulder line.
     *
     * BPS `Push-Up (Diamond).md` §6/§11/§13 ("humeri adducted and close to the torso … elbows tucked tightly
     * to ribs, not flared") fix the bend side: the diamond press folds the elbow in the **sagittal plane**,
     * along the trunk. Authoring the pole as the trunk's long axis states exactly that, and it is the same
     * bend side the family's in-line-grip member authors (`MilitaryPushUpPose` = `(1, 0.2, -0.1)`).
     *
     * Measured with this pole (201 published frames, 0.005 steps): the elbow's worst published height is
     * `+16.3014` at p = 0.505, tracking the ribs laterally at `|z| = 17.1 … 22.5`, with the humerus at
     * `36.4 deg` from the trunk at the bottom — the chain's own maximum adduction for this grip (see the
     * `DiamondPushUpElbowClearanceTest` regression). No lateral component, so both sides take the same world
     * direction and the A/P chains realize exactly mirrored. Keeping a lateral component large enough to
     * seat the elbow outside the shoulder line is geometrically impossible above the plane here: at that
     * phase the whole `|elbow.z| >= shoulderWidth` branch of the chain's circle lies below `y = 0`
     * (measured — the hand is only `68.04` from the shoulder against an arm of `146`).
     */
    override val poleA = Vector3(1f, 0f, 0f)
    override val poleP = Vector3(1f, 0f, 0f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_TOES),
                SupportContact(SupportPoint.RIGHT_TOES)
            )
        ),
        exerciseFamily = "push-up",
        motionType = "Press",
        bodyOrientation = "Prone"
    )
}
