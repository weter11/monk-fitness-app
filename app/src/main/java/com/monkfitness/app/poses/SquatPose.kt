package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class SquatPose : BaseSquatPose() {

    override val squatH = 55f // 40f + 15f ankle height
    override val pelvisXEnd = 0f
    override val leanAngleEnd = 0.40f
    override val armLeanEnd = 1.0f

    /**
     * Two family-default targets fall outside their own chain's annulus at this variant's
     * parameters: the standing-phase ankle is a locked-out leg (`210.288` against the `0.98` cap →
     * `205.800`) and the counterbalance reach never leaves a `9.2 … 11.4` u radius (the arm chain's
     * `minReach = 40.134`). The solver relocated the realized end-effector along the authored ray
     * (`4.488 … 30.934`, up to `24.056` at this variant's bottom of rep), so the published geometry was
     * the projection, not the authoring. Project each target onto its own annulus (family convention).
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
        camera = CameraDefinition(
            defaultYaw = 1.19f,
            defaultPitch = 0.22f,
            defaultZoom = 1.2f
        ),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.SLOW_DOWN_FAST_UP,
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )
    )
}
