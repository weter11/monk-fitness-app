package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class DeclinePushUpPose : BasePushUpPose() {
    private val poleFront = Vector3(1f, 0.5f, -1f)
    private val poleBack = Vector3(1f, 0.5f, 1f)
    private val handDirFront = Vector3(-1f, 0f, -0.2f).normalize()
    private val handDirBack = Vector3(-1f, 0f, 0.2f).normalize()

    // Calculates top anchor dynamically for environment metadata initialization
    private val boxHeight = 40f
    private val ankleHeight = boxHeight + 25f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            // The box is strictly centered at Z=0, aligned beneath the ankle's X projection
            props = listOf(
                BoxProp(
                    center = Vector3(60f + 210f * cos(asin(((60f - 65f) / 210f).coerceIn(-1f,1f))) + 10f, boxHeight / 2f, 0f),
                    width = 70f,
                    height = boxHeight,
                    depth = 60f
                )
            )
        )
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val height = lerp(60f, 20f, context.progress)
        val totalLegLen = def.shinLength + def.thighLength

        val drivingHeight = (height - ankleHeight)
        val theta = asin((drivingHeight / totalLegLen).coerceIn(-1f, 1f))
        val ankleX = 60f + (totalLegLen * cos(theta))

        frontLeg!!.ankle.localPosition = Vector3(ankleX, ankleHeight, -def.hipWidth)
        frontLeg!!.ankle.localRotation.set(Vector3(0f, 0f, 1f), -theta)

        val worldFootDir = Vector3(0f, -1f, 0f)
        val localFootDir = rotAround(worldFootDir, Vector3(0f, 0f, 1f), theta, Vector3())
        frontLeg!!.heel!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        frontLeg!!.toe!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)
        backLeg!!.heel!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        backLeg!!.toe!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)

        frontLeg!!.knee.localPosition = Vector3(-def.shinLength, 0f, 0f)
        frontLeg!!.hip.localPosition = Vector3(-def.thighLength, 0f, 0f)
        spine!!.pelvis.localPosition = Vector3(0f, 0f, def.hipWidth)
        spine!!.chest.localPosition = Vector3(-def.torsoLength, 0f, 0f)

        val headDir = Vector3(-1f, 0.2f, 0f).normalize()
        spine!!.neck.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        spine!!.head.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        backLeg!!.hip.localPosition = Vector3(0f, 0f, def.hipWidth)
        backLeg!!.knee.localPosition = Vector3(def.thighLength, 0f, 0f)
        backLeg!!.ankle.localPosition = Vector3(def.shinLength, 0f, 0f)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        val maxDrivingHeight = (60f - ankleHeight)
        val maxTheta = asin((maxDrivingHeight / totalLegLen).coerceIn(-1f, 1f))

        // Correcting Hand Target: Pulling target inward +5f guarantees elbows do not hyperextend at the peak height
        val handAnchorX = 60f - def.torsoLength * cos(maxTheta) + 5f

        GroundArmSupport.solve(
            definition = def,
            chest = spine!!.chest,
            shoulderA = frontArm!!.shoulder,
            elbowA = frontArm!!.elbow,
            handA = frontArm!!.hand,
            shoulderP = backArm!!.shoulder,
            elbowP = backArm!!.elbow,
            handP = backArm!!.hand,
            palmA = frontArm!!.palm,
            knucklesA = frontArm!!.knuckles,
            fingertipsA = frontArm!!.fingertips,
            palmP = backArm!!.palm,
            knucklesP = backArm!!.knuckles,
            fingertipsP = backArm!!.fingertips,
            targetX = handAnchorX,
            settings = GroundArmSupport.Settings(
                handWidthMultiplier = 1.5f,
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
