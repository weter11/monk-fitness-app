package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class AirSquatPose : BaseSquatPose() {

    override val squatH = 65f
    override val pelvisXEnd = -25f
    override val leanAngleEnd = 0.45f
    override val armLeanEnd = 1.0f // 1.0 * 40f = 40f reach

/**
 * Both authored targets fall OUTSIDE their own chain's reachable annulus at this variant's
 * parameters: the standing-phase ankle is a locked-out leg (`210.288` against the engine's `0.98`
 * extension cap → `205.800`), and the counterbalance reach never leaves a `9.2 … 15.9` radius
 * (the arm chain's `minReach = 40.134`). Each target is therefore projected onto its chain's own
 * annulus — the family's reachable-by-construction convention.
 */
    override fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        super.fillLegTargets(def, pelvisY, pelvisX, leanAngle, progress, outF, outB)
        projectLegTargetsToReach(def, outF, outB)
    }

    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        super.fillArmTargets(def, pelvisY, pelvisX, leanAngle, progress, outA, outP)
        projectArmTargetsToReach(def, outA, outP)
    }

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
}
