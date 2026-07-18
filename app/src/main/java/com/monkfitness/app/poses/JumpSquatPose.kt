package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class JumpSquatPose : BaseSquatPose() {

    override val squatH = 0f
    override val pelvisXEnd = -25f
    override val leanAngleEnd = 0.45f
    override val armLeanEnd = 0f

    private val legFPole = Vector3(1f, 0f, -0.3f)
    private val legBPole = Vector3(1f, 0f, 0.3f)
    private val armAPole = Vector3(0f, -1f, -1f)
    private val armPPole = Vector3(0f, -1f, 1f)

    private val legTargetF = Vector3()
    private val legTargetB = Vector3()
    private val armTargetA = Vector3()
    private val armTargetP = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        // LINEAR preserves the internal ballistic sine wave without double-easing the physics clock.
        motionCurve = MotionCurve.LINEAR,
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
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        // ====================================================================
        // CONTINUOUS MATHEMATICAL PHASE MAP (Branchless Physics)
        // ====================================================================

        // Maps progress [0..1] to [0..2PI].
        // We offset it so progress=0 starts exactly at the deepest point of the squat (-PI/2)
        val cycle = (context.progress * 2f * PI.toFloat()) - (PI.toFloat() / 2f)
        val rawSin = sin(cycle) // Ranges from -1 (Deep Squat) to 1 (Peak Flight)

        // Continuous functional clamps to isolate phase behaviors without IF statements
        val squatFactor = max(0f, -rawSin)  // Returns [0 to 1] ONLY during the squat/landing phases
        val flightFactor = max(0f, rawSin)  // Returns [0 to 1] ONLY during the liftoff/flight phases

        val standH = def.shinLength + def.thighLength + 25f

        // 1. Core Ballistic Pelvis Arc
        // Drop 40f into the squat, jump 35f into the air. Perfectly smooth transition through standH.
        val pelvisY = standH + (rawSin * 40f)
        val pelvisX = squatFactor * -25f
        val leanAngle = squatFactor * 0.45f

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, -leanAngle)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, -leanAngle))

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // ====================================================================
        // INVERSE KINEMATICS: DYNAMIC RECOVERY
        // ====================================================================

        // 2. Flight Kinematics (Legs)
        // The feet follow the pelvis up, but trail behind by mathematically lifting less than the pelvis
        // (40f jump - 25f foot lift = 15f compression -> natural bent knees in flight)
        val footLift = flightFactor * 25f
        legTargetF.set(0f, 25f + footLift, -def.hipWidth * 1.5f)
        legTargetB.set(0f, 25f + footLift, def.hipWidth * 1.5f)

        // Solve IK to force legs to reach the computed ankle targets
        bakeIkLimb(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, legFPole, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, legBPole, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // 3. Plantar Flexion (Toes point down during flight)
        val footPitch = flightFactor * 0.6f
        ankleF!!.localRotation.set(axisZ, leanAngle - footPitch)
        ankleB!!.localRotation.set(axisZ, leanAngle - footPitch)
        // W1: engine now derives heel/toe from the shank + this plantar-flexed ankle.

        // 4. Arm Ballistics
        // Arms are driven smoothly by the inverse of the rawSin wave.
        // Peak forward (+40) during deep squat (-1). Peak backward (-30) during flight (1).
        val handTargetX = pelvisX + (-rawSin * 35f) + 5f
        val handTargetY = pelvisY + def.torsoLength - 10f + (-rawSin * 15f)

        armTargetA.set(handTargetX, handTargetY, -def.shoulderWidth * 1.5f)
        armTargetP.set(handTargetX, handTargetY, def.shoulderWidth * 1.5f)

        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // Slight wrist flick backward during jump apex to match momentum
        handA!!.localRotation.set(axisZ, leanAngle + (flightFactor * 0.3f))
        handP!!.localRotation.set(axisZ, leanAngle + (flightFactor * 0.3f))
        // W1: engine now derives palm/knuckles/fingertips from the forearm + this wrist flick.

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
