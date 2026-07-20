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

    // Fully locked static geometry (ignores progress interpolation).
    override fun computePelvis(progress: Float, def: SkeletonDefinition): Triple<Float, Float, Float> {
        return Triple(60f, -30f, 0.5f)
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
