package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class PikePushUpPose : BasePushUpPose() {
    private val poleFront = Vector3(1f, 1f, -2f)
    private val poleBack = Vector3(1f, 1f, 2f)
    private val handDirFront = Vector3(-1f, 0f, -0.1f).normalize()
    private val handDirBack = Vector3(-1f, 0f, 0.1f).normalize()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val totalLegLen = def.shinLength + def.thighLength

        val ankleX = 135f // Moved feet further back to widen the V-angle
        val ankleHeight = 25f

        // Chest trajectory pulled forward slightly to give arms breathing room
        val chestX = lerp(-40f, 10f, context.progress)
        val chestY = lerp(130f, 35f, context.progress)

        // Kinematic Triangle Solver
        val dx = chestX - ankleX
        val dy = chestY - ankleHeight
        val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)

        val r1 = totalLegLen
        val r2 = def.torsoLength
        val dClamped = d.coerceIn(abs(r1 - r2), r1 + r2 - 0.1f)

        val a = (r1 * r1 - r2 * r2 + dClamped * dClamped) / (2f * dClamped)
        val h = sqrt(max(0f, r1 * r1 - a * a))

        val dirX = dx / dClamped
        val dirY = dy / dClamped

        val nx = dirY
        val ny = -dirX

        val px = ankleX + dirX * a + nx * h
        val py = ankleHeight + dirY * a + ny * h

        val legVecX = px - ankleX
        val legVecY = py - ankleHeight
        val legPitch = atan2(-legVecY, -legVecX)

        val torsoVecX = chestX - px
        val torsoVecY = chestY - py
        val torsoGlobalPitch = atan2(-torsoVecY, -torsoVecX)

        ankleF!!.localPosition = Vector3(ankleX, ankleHeight, -def.hipWidth)
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), legPitch)

        val worldFootDir = Vector3(0f, -1f, 0f)
        val localFootDir = rotAround(worldFootDir, Vector3(0f, 0f, 1f), -legPitch, Vector3())
        heelF!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeF!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)
        heelB!!.localPosition = Vector3(localFootDir.x * -def.foot.footLength * 0.29f, localFootDir.y * -def.foot.footLength * 0.29f, localFootDir.z * -def.foot.footLength * 0.29f)
        toeB!!.localPosition = Vector3(localFootDir.x * def.foot.footLength * 0.71f, localFootDir.y * def.foot.footLength * 0.71f, localFootDir.z * def.foot.footLength * 0.71f)

        kneeF!!.localPosition = Vector3(-def.shinLength, 0f, 0f)
        hipF!!.localPosition = Vector3(-def.thighLength, 0f, 0f)

        pelvis!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), torsoGlobalPitch - legPitch)
        chest!!.localPosition = Vector3(-def.torsoLength, 0f, 0f)

        // 1. Correcting the Right-Side (Side B) Floating Leg Asymmetry
        // By subtracting the torso pitch, we isolate and enforce the exact same global leg pitch on both sides.
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        hipB!!.localRotation.set(Vector3(0f, 0f, 1f), legPitch - torsoGlobalPitch)
        kneeB!!.localPosition = Vector3(def.thighLength, 0f, 0f)
        kneeB!!.localRotation.set(Vector3(0f, 0f, 1f), 0f)
        ankleB!!.localPosition = Vector3(def.shinLength, 0f, 0f)

        val headDir = Vector3(-1f, -0.6f, 0f).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        val handAnchorX = -25f

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
                handWidthMultiplier = 1.2f,
                poleFront = poleFront,
                poleBack = poleBack,
                handRotation = -torsoGlobalPitch,
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
