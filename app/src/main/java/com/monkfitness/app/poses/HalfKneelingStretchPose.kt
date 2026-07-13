package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class HalfKneelingStretchPose : BaseHipFlexorPose() {

    override val metadata = PoseMetadata(
        camera = hipFlexorCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = hipFlexorGround
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // 1. Rigid Pythagorean Pelvis Solver
        val kneeBX = -20f
        val kneeBY = 15f

        // Pelvis lunges forward to drive the stretch
        val pelvisX = SkeletonMath.lerp(0f, 25f, context.progress)
        val dx = pelvisX - kneeBX

        // Pelvis naturally drops in Y space as the X vector lengthens
        val dy = sqrt(def.thighLength * def.thighLength - dx * dx)
        val pelvisY = kneeBY + dy
        leanAngle = SkeletonMath.lerp(0f, -0.1f, context.progress)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        setUpperBodyLocal(def)
        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Fixed Kinematics for Back Leg: knee pinned, shin lies flat on the ground
        thighVecB.set(kneeBX - pelvisX, kneeBY - pelvisY, 0f)
        shinVecB.set(-def.shinLength, 0f, 0f)
        SkeletonMath.rotAround(thighVecB, axisZ, leanAngle, kneeB!!.localPosition)
        SkeletonMath.rotAround(shinVecB, axisZ, leanAngle, ankleB!!.localPosition)

        // 3. Inverse Kinematics for Front Leg
        targetAnkleF.set(65f, 25f, -def.hipWidth)
        val legFIK = solveFrontLeg(def)

        // 4. Arms Rest on Front Knee
        solveArmsOnKnee(legFIK.joint, def)

        // 5. Extremity / Foot Orientation
        applyFrontFoot(def)
        applyBackFoot(backFootBackDir, def)

        return finalizeHipFlexorPose()
    }
}
