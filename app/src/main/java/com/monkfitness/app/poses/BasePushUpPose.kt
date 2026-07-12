package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

/**
 * Base class for all push-up family poses.
 *
 * Geometry is derived from biomechanical support math (see [SupportMath]) instead of
 * per-pose magic constants:
 *  - The body lever (pivot -> shoulder) comes from [SupportMath.computeLeverLength], which is
 *    selected by [PushUpConfig.pivot] (FEET -> shin+thigh+torso, KNEES -> thigh+torso).
 *  - The leg is built at the straightest length the [IKConstraint.LegConstraint] allows
 *    (maximumExtensionRatio), so the knees are as locked-out as the model permits.
 *  - The shoulder height is derived from the ARM reach and the chosen grip: at the top of the
 *    rep the arm is extended and the hand sits on the ground directly beneath the shoulder, which
 *    is a geometrically valid IK target (the solver never asks the arm to span an impossible gap).
 *  - The descent depth is bounded so the hand stays within the validator's HAND_SHOULDER_ALIGNMENT
 *    window and the arm never folds past its minimum flexion.
 */
abstract class BasePushUpPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = emptyList()
        )
    )

    /** Biomechanical description of a push-up variant. No pose-specific magic numbers. */
    protected abstract val pushUpConfig: PushUpConfig

    protected var roots: List<SkeletonNode>? = null
    protected var ankleF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var hipF: SkeletonNode? = null; protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null
    protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val jointsBuffer = SkeletonPose()
    protected val armAIK = SkeletonMath.IKResult()
    protected val armPIK = SkeletonMath.IKResult()

    protected val zeroVector = Vector3(0f, 0f, 0f)
    protected val identityRotation = JointRotation()
    protected val axisZ = Vector3(0f, 0f, 1f)
    protected val tempV1 = Vector3()
    protected val tempV2 = Vector3()
    protected val tempV3 = Vector3()

    protected val shoulderAWBuffer = Vector3()
    protected val shoulderPWBuffer = Vector3()
    protected val targetHandABuffer = Vector3()
    protected val targetHandPBuffer = Vector3()
    protected val poleABuffer = Vector3()
    protected val polePBuffer = Vector3()

    /** Height of the support joint above the ground when planted (body-proportion constants). */
    private val FOOT_SUPPORT_HEIGHT = 25f
    private val KNEE_SUPPORT_HEIGHT = 15f
    /** Knee-push-up shin angle relative to the ground (knee is the pivot, shin points up/back). */
    private val SHIN_PITCH = PI / 4.0
    /**
     * Safety margin inside the validator's HAND_SHOULDER_ALIGNMENT lower bound (-5f).
     * The descent is clamped so the hand never ends up more than this far behind the shoulder.
     */
    private val HAND_ALIGN_MIN = -4.5f

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        ankleF = SkeletonNode(Joint.ANKLE_F)
        heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F)); toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))
        kneeF = ankleF!!.addChild(SkeletonNode(Joint.KNEE_F)); hipF = kneeF!!.addChild(SkeletonNode(Joint.HIP_F))
        pelvis = hipF!!.addChild(SkeletonNode(Joint.PELVIS)); chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END)); head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A)); elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A)); handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A)); palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A)); knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A)); fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))
        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P)); elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P)); handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P)); palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P)); knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P)); fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B)); kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B)); ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))
        heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B)); toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))
        roots = listOf(ankleF!!)
    }

    /**
     * Derives all push-up geometry from [PushUpConfig] and the skeleton proportions.
     * Returns the precomputed values needed to build the skeleton for a given [progress].
     */
    protected fun solvePushUp(config: PushUpConfig, def: SkeletonDefinition, progress: Float): PushUpGeometry {
        val leverLength = SupportMath.computeLeverLength(config.pivot, def)
        val supportBase = if (config.pivot == PivotType.KNEES) KNEE_SUPPORT_HEIGHT else FOOT_SUPPORT_HEIGHT
        val supportHeight = supportBase + config.supportElevation

        val armReach = def.upperArmLength + def.forearmLength
        val armMaxExt = armReach * def.armIKConstraint.maximumExtensionRatio
        val minCos = cos(def.armIKConstraint.minimumFlexionAngle * PI.toFloat() / 180f)
        val armMinDist = sqrt(max(def.upperArmLength * def.upperArmLength + def.forearmLength * def.forearmLength - 2f * def.upperArmLength * def.forearmLength * minCos, 0f))

        val gripZ = config.gripFactor * def.shoulderWidth
        val dz = gripZ - def.shoulderWidth

        // Top of the rep: arm extended, hand on the ground directly beneath the shoulder.
        val shoulderYTop = sqrt(max(armMaxExt * armMaxExt - dz * dz, 0f))
        val topTheta = asin(((shoulderYTop - supportHeight) / leverLength).coerceIn(-1f, 1f))

        // Bottom of the rep: descend until either the HAND_SHOULDER_ALIGNMENT window or the arm's
        // minimum flexion is reached (whichever keeps the hand higher / the arm more extended).
        val cosThetaBottomAlign = (cos(topTheta) + (HAND_ALIGN_MIN / def.torsoLength)).coerceIn(-1f, 1f)
        val sinThetaBottomAlign = sqrt(max(1f - cosThetaBottomAlign * cosThetaBottomAlign, 0f))
        val shoulderYBottomAlign = supportHeight + leverLength * sinThetaBottomAlign
        val shoulderYBottomArm = sqrt(max(armMinDist * armMinDist - dz * dz, 0f))
        val shoulderYBottom = max(shoulderYBottomAlign, shoulderYBottomArm)

        val handX = config.pelvisX - def.torsoLength * cos(topTheta)
        val pivotXTop = config.pelvisX + (leverLength - def.torsoLength) * cos(topTheta)

        return PushUpGeometry(
            supportHeight = supportHeight,
            leverLength = leverLength,
            armMaxExt = armMaxExt,
            armMinDist = armMinDist,
            dz = dz,
            shoulderYTop = shoulderYTop,
            shoulderYBottom = shoulderYBottom,
            topTheta = topTheta,
            handX = handX,
            handY = config.handElevation,
            pivotXTop = pivotXTop
        )
    }

    /**
     * Builds the full skeleton for [progress] using the derived [geom].
     * Straight (constraint-max) legs, body inclination from the support lever, and an IK arm whose
     * target is a geometrically valid straight-arm reach to the ground.
     */
    protected fun buildPushUpBody(config: PushUpConfig, geom: PushUpGeometry, def: SkeletonDefinition, progress: Float): SkeletonPose {
        val shinL = def.shinLength
        val thighL = def.thighLength
        val totalLegLen = shinL + thighL
        // Straightest biomechanically valid leg: maximum extension ratio from the constraint.
        val legTargetLen = totalLegLen * def.legIKConstraint.maximumExtensionRatio

        val shoulderY = lerp(geom.shoulderYTop, geom.shoulderYBottom, progress)
        val theta = asin(((shoulderY - geom.supportHeight) / geom.leverLength).coerceIn(-1f, 1f))
        val pivotX = config.pelvisX + (geom.leverLength - def.torsoLength) * cos(theta)

        if (config.pivot == PivotType.FEET) {
            ankleF!!.localPosition.set(pivotX, geom.supportHeight, -def.hipWidth)
            ankleF!!.localRotation.set(axisZ, -theta)

            val worldFootDir = tempV1.set(0f, -1f, 0f)
            val localFootDir = rotAround(worldFootDir, axisZ, theta, tempV2)
            heelF!!.localPosition.set(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
            toeF!!.localPosition.set(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)
            heelB!!.localPosition.set(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
            toeB!!.localPosition.set(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)

            val kX = (thighL * thighL - shinL * shinL - legTargetLen * legTargetLen) / (2f * legTargetLen)
            val kY = -sqrt(max(shinL * shinL - kX * kX, 0f))
            kneeF!!.localPosition.set(kX, kY, 0f)
            hipF!!.localPosition.set(-legTargetLen - kX, -kY, 0f)

            val bX = (thighL * thighL - shinL * shinL + legTargetLen * legTargetLen) / (2f * legTargetLen)
            val bY = -sqrt(max(thighL * thighL - bX * bX, 0f))
            kneeB!!.localPosition.set(bX, bY, 0f)
            ankleB!!.localPosition.set(legTargetLen - bX, -bY, 0f)
        } else {
            // KNEES: the knee is the support pivot; the shin points up/back at SHIN_PITCH.
            val ankleX = pivotX + shinL * cos(SHIN_PITCH)
            val ankleY = geom.supportHeight + shinL * sin(SHIN_PITCH)
            ankleF!!.localPosition.set(ankleX, ankleY, -def.hipWidth)
            ankleF!!.localRotation.set(axisZ, -SHIN_PITCH.toFloat())

            val footDir = rotAround(tempV1.set(1f, -1f, 0f).normalize(), axisZ, -SHIN_PITCH.toFloat(), tempV2)
            heelF!!.localPosition.set(footDir.x * -def.foot.footLength * 0.29f, footDir.y * -def.foot.footLength * 0.29f, footDir.z * -def.foot.footLength * 0.29f)
            toeF!!.localPosition.set(footDir.x * def.foot.footLength * 0.71f, footDir.y * def.foot.footLength * 0.71f, footDir.z * def.foot.footLength * 0.71f)
            heelB!!.localPosition.set(footDir.x * -def.foot.footLength * 0.29f, footDir.y * -def.foot.footLength * 0.29f, footDir.z * -def.foot.footLength * 0.29f)
            toeB!!.localPosition.set(footDir.x * def.foot.footLength * 0.71f, footDir.y * def.foot.footLength * 0.71f, footDir.z * def.foot.footLength * 0.71f)

            kneeF!!.localPosition.set(-shinL, 0f, 0f)
            kneeF!!.localRotation.set(axisZ, (-theta - SHIN_PITCH).toFloat())
            hipF!!.localPosition.set(-thighL, 0f, 0f)

            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            hipB!!.localRotation.set(axisZ, 0f)
            kneeB!!.localPosition.set(thighL, 0f, 0f)
            kneeB!!.localRotation.set(axisZ, (SHIN_PITCH + theta).toFloat())
            ankleB!!.localPosition.set(shinL, 0f, 0f)
        }

        pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
        chest!!.localPosition.set(-def.torsoLength, 0f, 0f)

        val headDir = tempV1.set(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition.set(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition.set(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV2).add(chestW)
        val shoulderPW = rotAround(tempV1.set(0f, 0f, def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV3).add(chestW)

        val gripZ = config.gripFactor * def.shoulderWidth
        val targetHandA = targetHandABuffer.set(geom.handX, geom.handY, -gripZ)
        val targetHandP = targetHandPBuffer.set(geom.handX, geom.handY, gripZ)

        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, config.armPoleA, def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, config.armPoleP, def.armIKConstraint, armPIK)

        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(tempV1.set(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), axisZ, theta, elbowA!!.localPosition)
        rotAround(tempV1.set(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), axisZ, theta, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(tempV1.set(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), axisZ, theta, elbowP!!.localPosition)
        rotAround(tempV1.set(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), axisZ, theta, handP!!.localPosition)

        handA!!.localRotation.set(axisZ, theta)
        val handDirA = config.handDirA.normalizedCopy()
        palmA!!.localPosition.set(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); knucklesA!!.localPosition.set(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); fingertipsA!!.localPosition.set(handDirA.x * 10f, handDirA.y * 10f, handDirA.z * 10f)

        handP!!.localRotation.set(axisZ, theta)
        val handDirP = config.handDirP.normalizedCopy()
        palmP!!.localPosition.set(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); knucklesP!!.localPosition.set(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); fingertipsP!!.localPosition.set(handDirP.x * 10f, handDirP.y * 10f, handDirP.z * 10f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        val geom = solvePushUp(pushUpConfig, def, context.progress)
        return buildPushUpBody(pushUpConfig, geom, def, context.progress)
    }
}

/**
 * Biomechanical description of a push-up variant. All knobs are meaningful body/support parameters,
 * not per-pose magic numbers.
 */
data class PushUpConfig(
    val pivot: PivotType,
    /** Hand spacing as a multiple of shoulder width (1.0 = under shoulders, >1 = wider, <1 = narrower). */
    val gripFactor: Float,
    /** Height of the support surface under the feet/knees (decline/incline boxes). */
    val supportElevation: Float = 0f,
    /** Height of the surface under the hands (incline / parallettes). */
    val handElevation: Float = 0f,
    /** Shared scene anchor for the hip position along X. */
    val pelvisX: Float = 60f,
    /** Elbow pole direction (controls flare). */
    val armPoleA: Vector3 = Vector3(1f, 0.5f, -1f),
    val armPoleP: Vector3 = Vector3(1f, 0.5f, 1f),
    /** Hand orientation (controls splay). */
    val handDirA: Vector3 = Vector3(-1f, 0f, -0.2f),
    val handDirP: Vector3 = Vector3(-1f, 0f, 0.2f)
)

/**
 * Precomputed push-up geometry for a single frame.
 */
data class PushUpGeometry(
    val supportHeight: Float,
    val leverLength: Float,
    val armMaxExt: Float,
    val armMinDist: Float,
    val dz: Float,
    val shoulderYTop: Float,
    val shoulderYBottom: Float,
    val topTheta: Float,
    val handX: Float,
    val handY: Float,
    val pivotXTop: Float
)
