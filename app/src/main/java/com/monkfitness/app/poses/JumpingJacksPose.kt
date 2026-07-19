package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Jumping Jacks — a continuous, branchless ballistic loop.
 *
 * The body oscillates between two extremes driven by a single sine wave:
 *   - CLOSED:  feet together (z = ±hipWidth), arms down at the sides (low, near hips).
 *   - OPEN:    feet jumped out to the sides (z = ±hipWidth * 2.4), arms abducted
 *              overhead to meet above the head (high +Y, wide ±Z).
 *
 * A small pelvic hop (vertical oscillation) rides the same wave so the feet and hands
 * lift/settle together, matching the ballistic bounce of a real jumping jack.
 *
 * Follows the current carrier-intent model: posture intent CUSTOM (shape-driven root),
 * IK via [bakeIkLimb], ankle/wrist articulations via the §1.3 intent carriers.
 */
class JumpingJacksPose : BaseSquatPose() {

    // BaseSquatPose abstract shape params (unused by the custom ballistic build below,
    // which authors its own open/close wave, but required by the contract).
    override val squatH = 0f
    override val pelvisXEnd = 0f
    override val leanAngleEnd = 0.12f
    override val armLeanEnd = 0f

    // Continuous math: progress [0..1] maps to [0..2PI]; offset so progress=0 starts CLOSED.
    private val cycleOffset = -PI.toFloat() / 2f
    private val openAmount: (Float) -> Float = { progress ->
        // sin ranges -1 (CLOSED) .. +1 (OPEN); clamp to [0,1] for the open factor.
        max(0f, sin(progress * 2f * PI.toFloat() + cycleOffset))
    }

    private val legFPole = Vector3(1f, 0f, -0.2f)
    private val legBPole = Vector3(1f, 0f, 0.2f)
    private val armAPole = Vector3(0f, -1f, -1f)
    private val armPPole = Vector3(0f, -1f, 1f)

    private val legTargetF = Vector3()
    private val legTargetB = Vector3()
    private val armTargetA = Vector3()
    private val armTargetP = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.25f),
        durationSeconds = 2.0f, loopMode = LoopMode.LOOP,
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
        // B3 — shape-driven root, opt into CUSTOM (solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val standH = def.shinLength + def.thighLength + 25f
        val open = openAmount(context.progress) // 0 (closed) .. 1 (open)

        // 1. Pelvis: small hop oscillation + slight forward lean as the body opens.
        val hop = open * 18f                 // peak lift at full open
        val pelvisY = standH + hop
        val leanAngle = open * 0.12f        // gentle trunk lean while opened

        pelvis!!.localPosition.set(0f, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, -leanAngle)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, -leanAngle))

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Legs: feet abduct from together (±hipWidth) to jumped-out (±hipWidth*2.4).
        val footZ = def.hipWidth * (1f + open * 1.4f)
        val footY = 25f + hop
        legTargetF.set(0f, footY, -footZ)
        legTargetB.set(0f, footY, footZ)

        bakeIkLimb(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, legFPole, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, legBPole, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // Feet stay plantigrade (flat) — small dorsiflexion as they land/settle.
        val footPitch = open * 0.15f
        buildAnkleArticulation(Extremity.FOOT_F, footPitch, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, footPitch, 0f, ankleB!!)

        // 3. Arms: abduct from the sides (low, near hips) to overhead (high, wide).
        //    Closed: hands hang just outside the hips. Open: hands meet above the head.
        val handX = 0f
        val handY = pelvisY + def.torsoLength - 10f + open * (def.shoulderWidth + 20f)
        val handZ = def.shoulderWidth * (1f + open * 1.6f)

        armTargetA.set(handX, handY, -handZ)
        armTargetP.set(handX, handY, handZ)

        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // Slight wrist extension as the arms reach overhead (natural for a clap/star shape).
        val wristFlex = open * 0.25f
        buildWristArticulation(Extremity.HAND_A, wristFlex, 0f, handA!!)
        buildWristArticulation(Extremity.HAND_P, wristFlex, 0f, handP!!)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
