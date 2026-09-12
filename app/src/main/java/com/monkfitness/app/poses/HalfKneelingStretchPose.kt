package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class HalfKneelingStretchPose : BaseHipFlexorPose() {

    override val metadata = PoseMetadata(
        camera = hipFlexorCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = hipFlexorGround,
        // M9 — both planted feet (the facing foot flat at the floor line, the rear foot's toes
        // tucked under it) on the ONE canonical support channel (`metadata.support`). The kneeling
        // rear knee is not declarable in a consumed family (no `*_KNEE` derivation).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        )
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

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
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, -leanAngle)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, -leanAngle))

        setUpperBodyLocal(def)
        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Fixed Kinematics for Back Leg: knee pinned, shin lies flat on the ground
        // (Phase 4: lean-cancel removed; the thigh/shin vectors are written directly)
        thighVecB.set(kneeBX - pelvisX, kneeBY - pelvisY, 0f)
        shinVecB.set(-def.shinLength, 0f, 0f)
        kneeB!!.localPosition.set(thighVecB)
        ankleB!!.localPosition.set(shinVecB)

        // 3. Front leg intent (declared through the registered bake; the returned knee apex is
        // the §12.4b planning-solve value used ONLY to compose the arm targets below).
        targetAnkleF.set(65f, 25f, -def.hipWidth)
        val legFPlan = planFrontLegKnee(def)

        // 4. Arms Rest on Front Knee
        solveArmsOnKnee(legFPlan.joint, def)

        // 5. Extremity / Foot Orientation
        applyFrontFoot(def)
        applyBackFoot(backFootBackDir, def)

        return finalizeHipFlexorPose()
    }
}
