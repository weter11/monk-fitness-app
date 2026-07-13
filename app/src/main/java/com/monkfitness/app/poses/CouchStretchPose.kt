package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class CouchStretchPose : BaseHipFlexorPose() {

    override val metadata = PoseMetadata(
        camera = hipFlexorCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            // The box face rests exactly at X = -40, providing the wall for the back shin
            props = listOf(BoxProp(center = Vector3(-65f, 50f, 0f), width = 50f, height = 100f, depth = 80f))
        )
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // 1. Rigid Pythagorean Pelvis Solver
        val kneeBX = -40f
        val kneeBY = 15f

        // Pelvis pushes backward into the couch to increase the quad stretch
        val pelvisX = SkeletonMath.lerp(10f, -15f, context.progress)
        val dx = pelvisX - kneeBX

        // Dynamically compute Pelvis Y so the thigh bone distance mathematically locks to exactly def.thighLength
        val dy = sqrt(def.thighLength * def.thighLength - dx * dx)
        val pelvisY = kneeBY + dy

        // Torso starts leaned slightly forward, and pushes completely upright/back during peak stretch
        leanAngle = SkeletonMath.lerp(0.2f, -0.05f, context.progress)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        setUpperBodyLocal(def)
        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Fixed Kinematics for Back Leg (Side B): knee pinned at the wall, shin vertical
        thighVecB.set(kneeBX - pelvisX, kneeBY - pelvisY, 0f)
        shinVecB.set(0f, def.shinLength, 0f)
        SkeletonMath.rotAround(thighVecB, axisZ, leanAngle, kneeB!!.localPosition)
        SkeletonMath.rotAround(shinVecB, axisZ, leanAngle, ankleB!!.localPosition)

        // 3. Inverse Kinematics for Front Leg (Side F)
        targetAnkleF.set(55f, 25f, -def.hipWidth)
        val legFIK = solveFrontLeg(def)

        // 4. Arms Rest on Front Knee
        solveArmsOnKnee(legFIK.joint, def)

        // 5. Extremity / Foot Orientation
        applyFrontFoot(def)
        applyBackFoot(backFootUpDir, def)

        return finalizeHipFlexorPose()
    }
}
