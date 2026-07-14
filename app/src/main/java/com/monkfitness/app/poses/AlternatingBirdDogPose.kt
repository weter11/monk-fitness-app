package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class AlternatingBirdDogPose : BaseBirdDogPose() {

    override val metadata = PoseMetadata(
        camera = birdDogCamera,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        // LINEAR is intentional: the sine-wave phase map below governs acceleration
        // continuously, so an eased curve would double-modulate the motion.
        motionCurve = MotionCurve.LINEAR,
        environment = birdDogEnvironment
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        anchorTabletop(def)

        val baseHandX = basePelvisX + def.torsoLength
        val baseHandY = 0f
        val baseAnkleX = basePelvisX - def.shinLength
        val baseAnkleY = 15f

        val extHandX = baseHandX + 140f
        val extHandY = basePelvisY
        val extAnkleX = basePelvisX - 190f
        val extAnkleY = basePelvisY

        // Diagonal pair extraction via stateless MotionDrivers (replaces manual max(0, sin)).
        // rightExt peaks at progress 0.25, leftExt peaks at progress 0.75.
        val rightExt = MotionDrivers.PositiveHalfSine(context.progress)
        val leftExt = MotionDrivers.NegativeHalfSine(context.progress)

        // Right arm (SHOULDER_P) + Left leg (HIP_F) extend with rightExt
        targetP.set(
            SkeletonMath.lerp(baseHandX, extHandX, rightExt),
            SkeletonMath.lerp(baseHandY, extHandY, rightExt),
            def.shoulderWidth
        )
        poleP.set(
            SkeletonMath.lerp(-1f, 0f, rightExt),
            SkeletonMath.lerp(0f, -1f, rightExt),
            0.5f
        )
        SkeletonMath.toLocalDirection(poleP, chest!!.worldRotation, poleP)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, chest!!.worldRotation, poleP, def.armIKConstraint, elbowP!!, handP!!, armPBuffer)

        // Left arm (SHOULDER_A) + Right leg (HIP_B) extend with leftExt
        targetA.set(
            SkeletonMath.lerp(baseHandX, extHandX, leftExt),
            SkeletonMath.lerp(baseHandY, extHandY, leftExt),
            -def.shoulderWidth
        )
        poleA.set(
            SkeletonMath.lerp(-1f, 0f, leftExt),
            SkeletonMath.lerp(0f, -1f, leftExt),
            -0.5f
        )
        SkeletonMath.toLocalDirection(poleA, chest!!.worldRotation, poleA)
        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, chest!!.worldRotation, poleA, def.armIKConstraint, elbowA!!, handA!!, armABuffer)

        targetF.set(
            SkeletonMath.lerp(baseAnkleX, extAnkleX, rightExt),
            SkeletonMath.lerp(baseAnkleY, extAnkleY, rightExt),
            -def.hipWidth
        )
        poleF.set(SkeletonMath.lerp(1f, 0f, rightExt), -1f, -0.5f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        targetB.set(
            SkeletonMath.lerp(baseAnkleX, extAnkleX, leftExt),
            SkeletonMath.lerp(baseAnkleY, extAnkleY, leftExt),
            def.hipWidth
        )
        poleB.set(SkeletonMath.lerp(1f, 0f, leftExt), -1f, 0.5f)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        applyBirdDogExtremities(def)
        return finalizeBirdDogPose()
    }
}
