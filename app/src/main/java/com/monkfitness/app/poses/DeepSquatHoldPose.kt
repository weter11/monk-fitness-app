package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class DeepSquatHoldPose : BaseSquatPose() {

    override val squatH = 60f
    override val pelvisXEnd = -30f
    override val leanAngleEnd = 0.5f
    override val armLeanEnd = 0f

    override val legPoleF = Vector3(1f, 0f, -0.4f)
    override val legPoleB = Vector3(1f, 0f, 0.4f)
    override val armPoleA = Vector3(0f, -0.5f, -1f)
    override val armPoleP = Vector3(0f, -0.5f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )
    )

    override fun computePelvis(progress: Float, def: SkeletonDefinition): Triple<Float, Float, Float> {
        return Triple(60f, -30f, 0.5f)
    }

    /**
     * Reach-band authoring for the locked hold.
     *
     * The pose inherits `BaseSquatPose`'s floor-frame leg targets `(0, 25, ±1.5 · hipWidth)` while its own
     * `computePelvis` locks the root at `(60, −30, 0.5)`, so the authored hip→ankle chord is `47.392` at
     * EVERY frame — an interior knee angle of `24.80°` against the `IKConstraint`'s own `30°` stop (BPS
     * `Squat (Deep Hold).md` states a `30–50°` interior). The request is past the pose's own model, so the
     * solver relocated both ankles by `8.617` u (stamp `8.617031`, realized knee exactly `30.00°`).
     *
     * Unintended authoring error, not a deliberate ROM limit: the DEPTH is the pose's authored intent (a
     * locked maximal-depth hold) and the floor-frame ankle is the base default, so the
     * reachable-by-construction convention is applied to the target. The hold's depth, pelvis and stance
     * are untouched (a root-side "fix" would have shallowed the hold) and the published geometry is
     * preserved — the realized feet stay exactly where the engine already put them.
     */
    override fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        super.fillLegTargets(def, pelvisY, pelvisX, leanAngle, progress, outF, outB)
        projectLegTargetsToReach(def, outF, outB)
    }

    // Clasp hands together at centre chest (no counterbalance reach).
    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        val hx = chest!!.worldPosition.x + 15f
        val hy = chest!!.worldPosition.y
        outA.set(hx, hy, -2f)
        outP.set(hx, hy, 2f)
    }
}
