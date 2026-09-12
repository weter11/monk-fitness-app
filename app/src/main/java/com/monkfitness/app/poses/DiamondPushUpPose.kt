package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class DiamondPushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 0.1f

    /**
     * B1 — the elbow bend side for the diamond grip: the pose's own **trunk long axis** (+X in the
     * world frame; the flat plank lies along +X at every progress of this pose's rep — measured
     * `CHEST -> PELVIS = (+1, 0, 0)` at every sampled frame).
     *
     * The family's default pole shape is Z-dominant (`BasePushUpPose`: `(1, 0.5, -1)`, and `Wide`
     * authors `(0.2, 0.8, -2.0)`), which is correct for every grip whose hands sit **at or outside**
     * the shoulder line — there the shoulder->hand chord is a lateral line and the pole's Z
     * component selects the elbow's lateral side. This variant's grip is `0.1`: the hands come to
     * the fused diamond base at the midline, `shoulderWidth * 0.1 = 4.6` against the shoulder
     * joint's `46`, i.e. the chord itself runs **inward**, and a Z-dominant pole no longer selects a
     * lateral side.
     *
     * Measured with the inherited `(0.5, 0.5, -2.0)` shape (published frames): at the bottom of the
     * rep (p = 0.5) the chain's triangle is `d = 68.04`, `h = 63.21`, `u = (0.025, -0.793, 0.608)`,
     * and the pole's perpendicular residual collapses to `phat = (0.388, -0.553, -0.737)` — pointing
     * DOWN — so the elbow was realized at `(-34.29, -19.91, -62.75)`: **19.91 u below the mat this
     * pose declares** (`metadata.environment.ground.level = 0`), from p ~ 0.33 to p ~ 0.67, flared
     * 16.8 u outside the shoulder line.
     *
     * The exercise's own declaration (`docs/Biomechanical Pose Specification (BPS)/Push-Up
     * (Diamond).md` §6 "Humeri adducted and close to the torso; at the bottom the upper arms are
     * near-parallel to the trunk (~0-20 deg from the ribs)", §11 "Elbows tucked tightly to ribs ...
     * not flared", §13 "elbows tucked to ribs throughout") fixes the bend side: the diamond press
     * folds the elbow in the **sagittal plane**, along the trunk. Authoring the pole as the trunk's
     * long axis is the pose's statement of exactly that, and it is the same bend side the family's
     * in-line-grip member authors (`MilitaryPushUpPose` = `(1, 0.2, -0.1)`).
     *
     * Measured with this pole (dense progress sweep 0 -> 1 in 0.005 steps, 201 published frames): the elbow's
     * worst published height is `+16.3014` at p = 0.505 (the phase with the largest triangle height),
     * tracking the ribs laterally at `|z| = 17.1 ... 22.5` (outside the fused hand base's `4.6`,
     * inside the shoulder line's `46`), with the humerus at `36.4 deg` from the trunk at the bottom
     * — the chain's own maximum adduction for this grip (the circle's tangent point; see the
     * regression `DiamondPushUpElbowClearanceTest`).
     *
     * No lateral component: this bend is sagittal, so both sides take the same world direction and
     * the A/P chains realize exactly mirrored (asserted by that regression). The alternative —
     * keeping a lateral component large enough to seat the elbow outside the shoulder line — is
     * geometrically impossible above the plane here: at that rep phase the whole
     * `|elbow.z| >= shoulderWidth` branch of the chain's circle lies below `y = 0` (measured; the
     * hand is only `68.04` from the shoulder against an arm of `146`, so the elbow's lateral arc is
     * low). The floor and the adduction win over the flare, which is what the BPS asks for anyway.
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
