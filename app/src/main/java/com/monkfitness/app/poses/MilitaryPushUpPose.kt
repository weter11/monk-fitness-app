package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class MilitaryPushUpPose : BasePushUpPose() {
    private val poleFront = Vector3(1f, 0.2f, -0.1f)
    private val poleBack = Vector3(1f, 0.2f, 0.1f)
    private val handDirFront = Vector3(-1f, 0f, 0f).normalize()
    private val handDirBack = Vector3(-1f, 0f, 0f).normalize()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // Depth stops at 38f so the forearm and bicep have geometric room to fold back
        val height = lerp(60f, 38f, context.progress)
        val totalLegLen = def.shinLength + def.thighLength
        val ankleHeight = 25f
        val drivingHeight = (height - ankleHeight).coerceAtLeast(0f)
        val theta = asin((drivingHeight / totalLegLen).coerceIn(-1f, 1f))
        val ankleX = 60f + (totalLegLen * cos(theta))

        ankleF!!.localPosition = Vector3(ankleX, ankleHeight, -def.hipWidth)
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), -theta)

        val worldFootDir = Vector3(0f, -1f, 0f)
        val localFootDir = rotAround(worldFootDir, Vector3(0f, 0f, 1f), theta, Vector3())
        heelF!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)

        kneeF!!.localPosition = Vector3(-def.shinLength, 0f, 0f)
        hipF!!.localPosition = Vector3(-def.thighLength, 0f, 0f)
        pelvis!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        chest!!.localPosition = Vector3(-def.torsoLength, 0f, 0f)
        val headDir = Vector3(-1f, 0.2f, 0f).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        kneeB!!.localPosition = Vector3(def.thighLength, 0f, 0f)
        ankleB!!.localPosition = Vector3(def.shinLength, 0f, 0f)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        val maxDrivingHeight = (60f - ankleHeight).coerceAtLeast(0f)
        val maxTheta = asin((maxDrivingHeight / totalLegLen).coerceIn(-1f, 1f))

        // Push hand anchor slightly forward (+5f) to force triceps engagement
        val handAnchorX = 60f - def.torsoLength * cos(maxTheta) + 5f

        GroundArmSupport.solve(
            definition = def,
            chest = chest!!,
            shoulderA = shoulderA!!,
            elbowA = elbowA!!,
            handA = handA!!,
            shoulderP = shoulderP!!,
            elbowP = elbowP!!,
            handP = handP!!,
            palmA = palmA,
            knucklesA = knucklesA,
            fingertipsA = fingertipsA,
            palmP = palmP,
            knucklesP = knucklesP,
            fingertipsP = fingertipsP,
            targetX = handAnchorX,
            settings = GroundArmSupport.Settings(
                handWidthMultiplier = 1.0f,
                poleFront = poleFront,
                poleBack = poleBack,
                handRotation = theta,
                handDirectionFront = handDirFront,
                handDirectionBack = handDirBack
            ),
            ikFront = armAIK,
            ikBack = armPIK
        )

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
