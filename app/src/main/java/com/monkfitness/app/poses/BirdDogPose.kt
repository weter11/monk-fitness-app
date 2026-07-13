package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Single-diagonal Bird Dog rep. One diagonal (arm + opposite leg) extends and
 * lowers on a sine loop; the active diagonal is chosen by [PoseContext.side].
 *
 * Migrated onto [BaseBirdDogPose] so it shares the family's tabletop anchoring,
 * IK baking and extremity geometry instead of reimplementing them.
 */
class BirdDogPose : BaseBirdDogPose() {

    override val metadata = PoseMetadata(
        camera = birdDogCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.SINE
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

        val ext = context.progress
        val rightSide = context.side == Side.RIGHT

        // RIGHT side -> left arm (A) + right leg (B) extend; LEFT side -> right arm (P) + left leg (F).
        val armExtA = if (rightSide) ext else 0f
        val armExtP = if (rightSide) 0f else ext
        val legExtF = if (rightSide) 0f else ext
        val legExtB = if (rightSide) ext else 0f

        targetA.set(
            SkeletonMath.lerp(baseHandX, extHandX, armExtA),
            SkeletonMath.lerp(baseHandY, extHandY, armExtA),
            -def.shoulderWidth
        )
        poleA.set(-1f, 0f, -0.5f)
        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, inverseTorsoPitch, elbowA!!, handA!!, armABuffer)

        targetP.set(
            SkeletonMath.lerp(baseHandX, extHandX, armExtP),
            SkeletonMath.lerp(baseHandY, extHandY, armExtP),
            def.shoulderWidth
        )
        poleP.set(-1f, 0f, 0.5f)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, inverseTorsoPitch, elbowP!!, handP!!, armPBuffer)

        targetF.set(
            SkeletonMath.lerp(baseAnkleX, extAnkleX, legExtF),
            SkeletonMath.lerp(baseAnkleY, extAnkleY, legExtF),
            -def.hipWidth
        )
        poleF.set(0f, -1f, -0.5f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, inverseTorsoPitch, kneeF!!, ankleF!!, legFBuffer)

        targetB.set(
            SkeletonMath.lerp(baseAnkleX, extAnkleX, legExtB),
            SkeletonMath.lerp(baseAnkleY, extAnkleY, legExtB),
            def.hipWidth
        )
        poleB.set(1f, -1f, 0.5f)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, inverseTorsoPitch, kneeB!!, ankleB!!, legBBuffer)

        applyBirdDogExtremities(def)
        return finalizeBirdDogPose()
    }
}
