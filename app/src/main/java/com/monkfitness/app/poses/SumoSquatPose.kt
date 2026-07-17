package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class SumoSquatPose : BaseSquatPose() {

    override val squatH = 60f
    override val pelvisXEnd = -10f
    override val leanAngleEnd = 0.15f
    override val armLeanEnd = 0f

    private val legFPole = Vector3(1f, 0f, -2.0f)
    private val legBPole = Vector3(1f, 0f, 2.0f)
    private val armAPole = Vector3(0f, 0f, -1f)
    private val armPPole = Vector3(0f, 0f, 1f)

    private val legTargetF = Vector3()
    private val legTargetB = Vector3()
    private val armTargetA = Vector3()
    private val armTargetP = Vector3()

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

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val standH = def.shinLength + def.thighLength + 25f

        val pelvisY = SkeletonMath.lerp(standH, squatH, context.progress)
        val pelvisX = SkeletonMath.lerp(0f, -10f, context.progress) // Less shifting needed for wide base
        val leanAngle = SkeletonMath.lerp(0f, 0.15f, context.progress) // Upright torso

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        legTargetF.set(0f, 25f, -def.hipWidth * 2.8f)
        legTargetB.set(0f, 25f, def.hipWidth * 2.8f)

        // R2 (reach target authoring): the wide sumo stance places the authored foot target
        // marginally beyond leg reach when standing tall (top of rep). Project it onto the
        // reachable band so the wide track is honoured without the solver clamping (which would
        // fire IK_TARGET_UNREACHABLE). Direction/stance intent is preserved; only the radius is
        // pulled inside the anatomical limit.
        SkeletonMath.clampTargetToReach(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, def.legIKConstraint, legTargetF)
        SkeletonMath.clampTargetToReach(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, def.legIKConstraint, legTargetB)

        // Wide track pole vectors
        bakeIkLimb(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, legFPole, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, legBPole, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The sumo
        // 45-degree toe flare is intentionally NOT hand-authored here; a foot aligned with the
        // shank (no flare) is the engine's derivation, and any visual shortfall is an engine
        // limitation left exposed.

        // Arms drop vertically toward crotch
        val handTargetX = pelvisX + 10f
        val handTargetY = SkeletonMath.lerp(pelvisY, pelvisY - 20f, context.progress)

        armTargetA.set(handTargetX, handTargetY, -10f)
        armTargetP.set(handTargetX, handTargetY, 10f)

        // R2 (reach target authoring): hands dropping toward the crotch sit closer to the shoulder
        // than the elbow's minimum-flexion reach; keep the target inside the reachable band.
        SkeletonMath.clampTargetToReach(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, def.armIKConstraint, armTargetA)
        SkeletonMath.clampTargetToReach(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, def.armIKConstraint, armTargetP)


        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // W1: engine now derives hand orientation (removed wrist tilt counter-rotation + 6/6/10 offsets).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
