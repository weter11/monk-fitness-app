package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * High Step-Up — a taller step than [StepUpPose], sharing the exact support-leg-driven model and
 * the rear-foot trajectory (via BaseLungePose.stepBackFoot). The support foot is FIXED on top of a
 * 24-unit step; the body rises because that leg extends and the rear foot leaves the floor only
 * while lifted. No floating: the support foot rests exactly on the step surface.
 */
class HighStepUpPose : BaseLungePose() {

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 3.4f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                StepProp(
                    center = Vector3(15f, 12f, 0f),
                    width = 44f,
                    height = 24f,
                    depth = 44f
                )
            )
        )
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val prog = context.progress
        val u = (1f - cos(prog * 2f * PI.toFloat())) * 0.5f

        val pelvisY = SkeletonMath.lerp(210f, 230f, u)
        val pelvisX = SkeletonMath.lerp(-5f, 15f, u)
        val leanAngle = SkeletonMath.lerp(0.04f, 0.18f, sin(prog * PI.toFloat())) * (1f - u)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f); head!!.localPosition.set(0f, 18f, 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)
        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Support (front) foot FIXED on top of the 24-unit step (step surface at y = 24).
        targetF.set(20f, 24f, -def.hipWidth * 1.5f)
        stepBackFoot(prog, def.hipWidth * 1.5f, targetB)

        bakeLegs(def, leanAngle, targetF, targetB)

        val handTargetX = SkeletonMath.lerp(-10f, 20f, u)
        val handTargetY = pelvisY + def.torsoLength - 50f
        targetA.set(handTargetX, handTargetY, -def.shoulderWidth * 1.2f)
        targetP.set(handTargetX, handTargetY, def.shoulderWidth * 1.2f)
        poleA.set(0f, -1f, -1f); poleP.set(0f, -1f, 1f)
        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, leanAngle, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, leanAngle, elbowP!!, handP!!, armPBuffer)

        applyExtremities(def, leanAngle, 0f, 0f)

        return finalizeLunge()
    }
}
