package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class StaticBirdDogHoldPose : BaseBirdDogPose() {

    override val metadata = PoseMetadata(
        camera = birdDogCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = birdDogEnvironment
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        anchorTabletop(def)

        // Base (neutral tabletop) and extended limb targets
        val baseHandX = basePelvisX + def.torsoLength
        val baseHandY = 0f
        val baseAnkleX = basePelvisX - def.shinLength
        val baseAnkleY = 15f

        val extHandX = baseHandX + 140f
        val extHandY = basePelvisY
        val extAnkleX = basePelvisX - 190f
        val extAnkleY = basePelvisY

        val ext = context.progress

        // Right arm (SHOULDER_P) + Left leg (HIP_F) extend; the opposite diagonal stays neutral.
        targetP.set(
            SkeletonMath.lerp(baseHandX, extHandX, ext),
            SkeletonMath.lerp(baseHandY, extHandY, ext),
            def.shoulderWidth
        )
        poleP.set(
            SkeletonMath.lerp(-1f, 0f, ext),
            SkeletonMath.lerp(0f, -1f, ext),
            0.5f
        )
        SkeletonMath.toLocalDirection(poleP, chest!!.worldRotation, poleP)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, SkeletonMath.toWorldDirection(poleP, chest!!.worldRotation, Vector3()), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        targetA.set(baseHandX, baseHandY, -def.shoulderWidth)
        poleA.set(-1f, 0f, -0.5f)
        SkeletonMath.toLocalDirection(poleA, chest!!.worldRotation, poleA)
        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, SkeletonMath.toWorldDirection(poleA, chest!!.worldRotation, Vector3()), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)

        targetF.set(
            SkeletonMath.lerp(baseAnkleX, extAnkleX, ext),
            SkeletonMath.lerp(baseAnkleY, extAnkleY, ext),
            -def.hipWidth
        )
        poleF.set(0f, -1f, -0.5f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        targetB.set(baseAnkleX, baseAnkleY, def.hipWidth)
        poleB.set(1f, -1f, 0.5f)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        applyBirdDogExtremities(def)
        return finalizeBirdDogPose()
    }
}
