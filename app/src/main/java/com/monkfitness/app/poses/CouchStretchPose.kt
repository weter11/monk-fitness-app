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
        ),
        // The FRONT foot (the facing knee-up leg, planted at the floor line for the whole hold) on the
        // ONE canonical support channel. The rear shin/knee contact is not declarable: the engine has no
        // derivation for `*_KNEE` (measured: it is a decoration).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT)
        )
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // Every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

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
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, -leanAngle)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, -leanAngle))

        setUpperBodyLocal(def)
        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Fixed Kinematics for Back Leg (Side B): knee pinned at the wall, shin vertical
        // (lean-cancel removed; the thigh/shin vectors are written directly)
        thighVecB.set(kneeBX - pelvisX, kneeBY - pelvisY, 0f)
        shinVecB.set(0f, def.shinLength, 0f)
        kneeB!!.localPosition.set(thighVecB)
        ankleB!!.localPosition.set(shinVecB)

        // 3. Front leg intent (declared through the registered bake; the returned knee apex is
        // the planning-solve value used ONLY to compose the arm targets below).
        targetAnkleF.set(55f, 25f, -def.hipWidth)
        val legFPlan = planFrontLegKnee(def)

        solveArmsOnKnee(legFPlan.joint, def)

        applyFrontFoot(def)
        applyBackFoot(backFootUpDir, def)

        return finalizeHipFlexorPose()
    }

    /**
     * Reach-band authoring for this variant.
     *
     * [BaseHipFlexorPose.solveArmsOnKnee] composes both hands at the front knee's planning apex +
     * `(−10, +15, ±0.8 · shoulderWidth)`. Measured, that target sits `142.0487` (p = 0.10) … `156.6795`
     * (p = 1.00) u from its own shoulder — past the arm chain's `143.0800` cap (`(80 + 66) · 0.98`) at 13
     * of the 15 sampled phases — so the solver relocated both hands up to `13.5995` u along the authored ray
     * and published them exactly ON the cap with the elbow at `156.9356°`: a straight arm, not the "arms
     * rest on the front knee" the choreography authors. Unintended authoring error, not a deliberate ROM
     * limit — the sibling `HalfKneelingStretchPose` composes the same target and needed the same correction.
     * The projection preserves the authored RAY toward the knee and only moves the radius onto the annulus
     * (where the solver already published), so root, stance, front-leg target and choreography are
     * untouched. Post-fix both hands declare `143.0657` at every out-of-band phase and the stamp reads `0`.
     */
    override fun projectArmTargetToReach(
        def: SkeletonDefinition,
        shoulderWorld: Vector3,
        target: Vector3
    ) {
        SkeletonMath.clampTargetToReach(
            shoulderWorld, target, def.upperArmLength, def.forearmLength, def.armIKConstraint,
            target, REACH_MARGIN
        )
    }
}
