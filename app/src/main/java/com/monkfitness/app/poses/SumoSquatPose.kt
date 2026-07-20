package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class SumoSquatPose : BaseSquatPose() {

    override val squatH = 60f
    override val pelvisXEnd = -10f
    override val leanAngleEnd = 0.15f
    override val armLeanEnd = 0f

    // Wide sumo stance + near-upright torso poles.
    override val legPoleF = Vector3(1f, 0f, -2.0f)
    override val legPoleB = Vector3(1f, 0f, 2.0f)
    override val armPoleA = Vector3(0f, 0f, -1f)
    override val armPoleP = Vector3(0f, 0f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
        // Controlled bodyweight sumo squat tempo: 1.5s eccentric descent, 0.5s pause, 1.0s concentric ascent
        durationSeconds = 3.0f, loopMode = LoopMode.LOOP,
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

    // Wide sumo foot track: marginally beyond leg reach when standing tall -> project onto the
    // reachable band (R2) so the wide stance is honoured without solver clamping.
    override fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        outF.set(0f, 25f, -def.hipWidth * 2.8f)
        outB.set(0f, 25f, def.hipWidth * 2.8f)
        SkeletonMath.clampTargetToReach(hipF!!.worldPosition, outF, def.thighLength, def.shinLength, def.legIKConstraint, outF)
        SkeletonMath.clampTargetToReach(hipB!!.worldPosition, outB, def.thighLength, def.shinLength, def.legIKConstraint, outB)
    }

    // Hands drop vertically toward the crotch (R2: kept inside reachable band).
    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        val handTargetX = pelvisX + 10f
        val handTargetY = SkeletonMath.lerp(pelvisY, pelvisY - 20f, progress)
        outA.set(handTargetX, handTargetY, -10f)
        outP.set(handTargetX, handTargetY, 10f)
        SkeletonMath.clampTargetToReach(shoulderA!!.worldPosition, outA, def.upperArmLength, def.forearmLength, def.armIKConstraint, outA)
        SkeletonMath.clampTargetToReach(shoulderP!!.worldPosition, outP, def.upperArmLength, def.forearmLength, def.armIKConstraint, outP)
    }
}
