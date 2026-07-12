package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.solveNearStraightLimb
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class KneePushUpPose : BasePushUpPose() {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.KNEES,
            contacts = setOf(
                SupportContact.LEFT_HAND,
                SupportContact.RIGHT_HAND,
                SupportContact.LEFT_KNEE,
                SupportContact.RIGHT_KNEE
            )
        )
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val shinL = def.shinLength
        val thighL = def.thighLength

        // The effective "leg" for knee pushup is the thigh segment (knee to pelvis)
        // Use 8 degrees of hip flexion for natural slight bend
        val targetFlexionDegrees = 8f
        val limbResult = solveNearStraightLimb(thighL, def.torsoLength, targetFlexionDegrees, legScratch)
        val legTargetLen = limbResult.d

        // Support height is knee height on ground (15f). The geometry function treats this
        // as the ankle height, but for knee pushup the pivot is at knees.
        val kneeHeight = 15f
        val geometry = SupportMath.derivePushUpGeometry(
            progress = context.progress,
            supportHeight = kneeHeight - 25f, // Adjust so "ankleHeight" = 25 + supportHeight = kneeHeight
            legTargetLen = legTargetLen,
            torsoLength = def.torsoLength,
            pelvisOffsetTop = 35f,
            pelvisOffsetBottom = 15f
        )

        val pelvisHeight = geometry.pelvisHeight
        val theta = geometry.theta
        val kneeX = geometry.ankleX // In this context, "ankleX" is the knee X position
        val handAnchorX = geometry.handAnchorX

        val shinPitch = (Math.PI / 4.0).toFloat() // Shins fixed at 45 degrees upward

        // 1. Root Anchoring - knees are the pivot on ground at (kneeX, kneeHeight)
        // Hierarchy: ankleF (root) -> kneeF -> hipF -> pelvis -> chest
        // Position ankleF so that kneeF ends up at the pivot point
        val ankleX = kneeX + shinL * cos(shinPitch)
        val ankleY = kneeHeight + shinL * sin(shinPitch)
        ankleF!!.localPosition.set(ankleX, ankleY, -def.hipWidth)
        ankleF!!.localRotation.set(axisZ, shinPitch)

        // kneeF is child of ankleF. In ankle's local space (rotated 45°), knee is at (0, -shinL)
        kneeF!!.localPosition.set(0f, -shinL, 0f)
        // kneeF world rotation = ankleF rot (45°) + kneeF local rot = -theta
        // So kneeF local rot = -theta - 45°
        kneeF!!.localRotation.set(axisZ, -theta - shinPitch)

        val footDir = rotAround(tempV1.set(1f, -1f, 0f).normalize(), axisZ, -shinPitch, tempV2)
        heelF!!.localPosition.set(footDir.x * -def.foot.footLength * 0.29f, footDir.y * -def.foot.footLength * 0.29f, footDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition.set(footDir.x * def.foot.footLength * 0.71f, footDir.y * def.foot.footLength * 0.71f, footDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition.set(footDir.x * -def.foot.footLength * 0.29f, footDir.y * -def.foot.footLength * 0.29f, footDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition.set(footDir.x * def.foot.footLength * 0.71f, footDir.y * def.foot.footLength * 0.71f, footDir.z * def.foot.footLength * 0.71f)

        // 2. Main Plank (Side F) - Build from knee up
        // limbResult gives hip position relative to knee (knee at origin, chest direction +X)
        val kX = limbResult.x
        val kY = limbResult.y

        hipF!!.localPosition.set(kX, kY, 0f)
        pelvis!!.localPosition.set(-legTargetLen - kX, -kY, def.hipWidth)
        chest!!.localPosition.set(-def.torsoLength, 0f, 0f)

        val headDir = tempV1.set(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition.set(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition.set(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        // 3. Perfect Symmetry (Side B)
        // B-leg: hip is parent, knee is child — different traversal than F-leg
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        hipB!!.localRotation.set(axisZ, 0f)

        // For B-leg, solve with segments swapped (hip->knee uses torso as "thigh", thigh as "shin")
        // since the traversal direction is opposite to F-leg
        val bXResult = solveNearStraightLimb(def.torsoLength, thighL, targetFlexionDegrees, legScratch)
        val bX = bXResult.x
        val bY = bXResult.y

        kneeB!!.localPosition.set(bX, bY, 0f)
        ankleB!!.localPosition.set(legTargetLen - bX, -bY, 0f)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        val chestW = chest!!.worldPosition
        val shoulderAW = rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV2).add(chestW)
        val shoulderPW = rotAround(tempV1.set(0f, 0f, def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV3).add(chestW)

        val targetHandA = targetHandABuffer.set(handAnchorX, 0f, -def.shoulderWidth * 1.5f)
        val targetHandP = targetHandPBuffer.set(handAnchorX, 0f, def.shoulderWidth * 1.5f)

        val armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, poleABuffer.set(1f, 0.5f, -1f), def.armIKConstraint, armAIK)
        val armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, polePBuffer.set(1f, 0.5f, 1f), def.armIKConstraint, armPIK)

        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        rotAround(tempV1.set(armA.joint.x - shoulderAW.x, armA.joint.y - shoulderAW.y, armA.joint.z - shoulderAW.z), axisZ, theta, elbowA!!.localPosition)
        rotAround(tempV1.set(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z), axisZ, theta, handA!!.localPosition)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        rotAround(tempV1.set(armP.joint.x - shoulderPW.x, armP.joint.y - shoulderPW.y, armP.joint.z - shoulderPW.z), axisZ, theta, elbowP!!.localPosition)
        rotAround(tempV1.set(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z), axisZ, theta, handP!!.localPosition)

        handA!!.localRotation.set(axisZ, theta)
        val handDirA = tempV1.set(-1f, 0f, -0.2f).normalize()
        palmA!!.localPosition.set(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); knucklesA!!.localPosition.set(handDirA.x * 6f, handDirA.y * 6f, handDirA.z * 6f); fingertipsA!!.localPosition.set(handDirA.x * 10f, handDirA.y * 10f, handDirA.z * 10f)

        handP!!.localRotation.set(axisZ, theta)
        val handDirP = tempV1.set(-1f, 0f, 0.2f).normalize()
        palmP!!.localPosition.set(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); knucklesP!!.localPosition.set(handDirP.x * 6f, handDirP.y * 6f, handDirP.z * 6f); fingertipsP!!.localPosition.set(handDirP.x * 10f, handDirP.y * 10f, handDirP.z * 10f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        jointsBuffer.maxIkClampAmount = maxOf(armAIK.clampAmount, armPIK.clampAmount)
        return jointsBuffer
    }
}
