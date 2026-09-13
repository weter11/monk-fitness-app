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

    /**
     * R2/R4 — reach-band authoring (third reach-band cleanup batch).
     *
     * `BaseHipFlexorPose.solveArmsOnKnee` composes both hands at the front knee's planning apex +
     * `(−10, +15, ±0.8 · shoulderWidth)`. Measured through the production entry point
     * (`SkeletonPipeline.produceFrame(pose, ctx)`) at `origin/main` @ `cf8a14f`, that target sits
     * `155.160` (p = 0) … `166.868` (p = 1) u from its own shoulder — LONGER than the arm's
     * `80 + 66 = 146` u, so no chain geometry can honour it (the annulus cap is `143.080`). The
     * solver answered by relocating both hands `12.080 … 23.788` u along the authored ray and
     * published them exactly ON the `0.98` extension cap with the elbow reading `156.94°` interior —
     * a straight arm, not the "arms rest on the front knee" the family's choreography authors.
     *
     * Unintended authoring error, not a deliberate ROM limit: the pose's own intent (hands on the
     * knee) is a reachable pose in principle, and the request here is past the limb itself, so no
     * author could have meant the published number. The reachable-by-construction convention (R2/R4)
     * is applied to the target: the authored RAY toward the knee is preserved and only the radius
     * moves onto the annulus, which is exactly what the solver already published — the pose's root,
     * stance, front-leg target and choreography are untouched.
     *
     * Family scope: `CouchStretchPose` composes its hands through the SAME base helper and carries a
     * `13.600` u site of the same class; it is not part of this batch, so the projection is opted in
     * here (variant-level) rather than in the shared base — see
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
