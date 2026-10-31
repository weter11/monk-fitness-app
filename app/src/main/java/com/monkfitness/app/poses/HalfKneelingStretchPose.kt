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
        // Both planted feet (the facing foot flat at the floor line, the rear foot's toes tucked under
        // it) on the ONE canonical support channel. The kneeling rear knee is not declarable (no `*_KNEE`
        // derivation).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        )
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // Every production pose declares its posture intent. This pose authors a
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

        // 2. Fixed Kinematics for Back Leg: knee pinned, shin lies flat on the ground.
        thighVecB.set(kneeBX - pelvisX, kneeBY - pelvisY, 0f)
        shinVecB.set(-def.shinLength, 0f, 0f)
        kneeB!!.localPosition.set(thighVecB)
        ankleB!!.localPosition.set(shinVecB)

        // 3. Front leg intent (declared through the registered bake; the returned knee apex is
        // the planning-solve value used ONLY to compose the arm targets below).
        targetAnkleF.set(65f, 25f, -def.hipWidth)
        val legFPlan = planFrontLegKnee(def)

        solveArmsOnKnee(legFPlan.joint, def)

        applyFrontFoot(def)
        applyBackFoot(backFootBackDir, def)

        return finalizeHipFlexorPose()
    }

    /**
     * Reach-band authoring for this variant.
     *
     * [BaseHipFlexorPose.solveArmsOnKnee] composes both hands at the front knee's planning apex +
     * `(−10, +15, ±0.8 · shoulderWidth)`. Measured, that target sits `155.160` (p = 0) … `166.868` (p = 1) u
     * from its own shoulder — LONGER than the arm's `80 + 66 = 146` u, so no chain geometry can honour it
     * (the annulus cap is `143.080`). The solver answered by relocating both hands `12.080 … 23.788` u along
     * the authored ray and published them exactly ON the `0.98` cap with the elbow at `156.94°` interior — a
     * straight arm, not the "arms rest on the front knee" the choreography authors.
     *
     * Unintended authoring error, not a deliberate ROM limit: hands on the knee is a reachable pose in
     * principle, and the request here is past the limb itself. The projection preserves the authored RAY
     * toward the knee and only moves the radius onto the annulus — exactly what the solver already published
     * — so root, stance, front-leg target and choreography are untouched.
     *
     * Family scope: `CouchStretchPose` composes through the SAME base helper and carries a `13.600` u site of
     * the same class; that site is left for its own correction, so the projection is opted in here
     * (variant-level) rather than in the shared base — see
     * `ReachBandBatch3AuthoringTest.theSharedFamilyChoreographyIsNotReScopedByThisBatch`.
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
