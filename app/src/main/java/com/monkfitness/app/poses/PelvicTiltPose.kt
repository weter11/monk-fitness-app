package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class PelvicTiltPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // M10 — the planted feet (supine pelvic tilt: the feet are the ground contact through the
        // whole rep), on the ONE canonical support channel (`metadata.support`).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        )
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        pelvis = SkeletonNode(Joint.PELVIS)
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END))
        head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A))
        elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A))
        handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A))
        palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A))
        knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A))
        fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))

        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P))
        elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P))
        handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P))
        palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P))
        knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P))
        fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F))
        kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F))
        ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F))
        heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F))
        toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))

        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B))
        kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B))
        ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))
        heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B))
        toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))

        roots = listOf(pelvis!!)
    }

    override fun build(context: PoseContext): SkeletonPose {
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)

        // Posterior pelvic tilt: lying on the back (supine) with bent knees
        // Pelvis Y remains static on the floor (14f) — the pelvis joint IS this pose's resting layer:
        // the whole authored trunk chain (chest 120, neck 18, head 18) lies ON it at the flat supine
        // tilt, so a trunk joint leaving that layer downward leaves the body's volume in the mat.
        val pelvisY = 14f
        // Dynamic subtle tilt of the pelvis: the rep's authored arc, 0 -> 0.12 rad (EASE_IN_OUT).
        //
        // The SIGN of this arc is the pose's whole relationship to the mat (B4 — trunk half). The
        // trunk is rigid and hangs off the static pelvis, so a published trunk joint is
        // `pelvis + Rz(θ)·(0, chainLength, 0)`, i.e. `y = 14 + chain·cos θ`: the old `+0.12` spent the
        // arc on that chain's sin — the model gives 120·sin(0.12) = 14.3655 off the chest and
        // 138·sin(0.12) = 16.5203 off neck/head, and the pipeline measured CHEST/SHOULDER_A/P
        // −0.3659, NECK_END −2.5295, HEAD_POS −2.5384 at p = 1.0 (14.3655 / 16.5290 / 16.5378 below
        // this pose's own layer; the head chain sits at the flat orientation, so its own offset adds
        // no vertical drop) — against a pelvis that only HAS 14 of it. The upper body was therefore
        // published UNDER the mat from p ≈ 0.846 (the chest crosses at p ≈ 0.974); that was the T2
        // pin, and the whole-body gate is PelvicTiltTrunkPlaneTest. The authored arc tips the pelvis's
        // superior axis AWAY from the mat instead: same pivot, same 0.12-rad amplitude, same rep shape
        // and the same start configuration (the pose's own supine rest), with the trunk's swing in the
        // half-space the resting layer allows (CHEST/SHOULDER_A/P +28.3650, NECK_END +30.5198,
        // HEAD_POS +30.5197 at p = 1.0) — and nothing else moves (pelvis, arms' B4 bend side, legs'
        // authored stance and the support model are untouched). Bounding the DOWNWARD arc instead
        // cannot work: keeping the trunk's spine centres legal needs sin(offset) ≤ 14/138, which rests
        // them on the mat's own surface (their layer is 14) while the trunk's volume sinks into it, for
        // ≤ 14 of travel.
        val angleOffset = lerp(0f, 0.12f, context.progress)
        val torsoAngle = 1.5708f - angleOffset

        pelvis!!.localPosition = Vector3(0f, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), torsoAngle)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.PELVIS, JointRotation(Vector3(0f, 0f, 1f), torsoAngle))

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)

        // Neck and Head stay horizontal, resting on the floor: the neck's articulation cancels the
        // trunk's tilt exactly (its signed value follows the trunk's), so the neck's world rotation is
        // the flat supine 1.5708 under either tilt direction and the head chain keeps the pose's
        // supine orientation.
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        neck!!.localRotation.set(Vector3(0f, 0f, 1f), angleOffset)
        // B4a — carrier-backed neck ROM: record the neck articulation as a joint intent so the
        // Finalizer (B2) consumes it idempotently (mixed mode, byte-identical to the bare write).
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.NECK_END, JointRotation(Vector3(0f, 0f, 1f), angleOffset))
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Compute transforms
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 1. Static Foot Placements (knees bent, feet flat on the floor)
        val targetAnkleF = Vector3(45f, def.foot.ankleHeight, -def.hipWidth)
        val targetAnkleB = Vector3(45f, def.foot.ankleHeight, def.hipWidth)

        // P12 (§12.6): legs declared through the registered authoring bake (was direct
        // solveIK + raw-offset writes — the bypass family).
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // 2. Arms flat on the floor alongside the body
        val targetHandA = Vector3(-35f, 12f, -def.shoulderWidth - 5f)
        val targetHandP = Vector3(-35f, 12f, def.shoulderWidth + 5f)

        // B4 — the elbow's BEND SIDE is this pose's own LATERAL axis, not the standing family's
        // downward pole (see GluteBridgePose; the two supine poses share this arm authoring). The
        // chord is horizontal (shoulder y = 3.2 … 14.0 over the rep, hand y = 12.0), so the family's
        // pole (0, -1, ∓1) spent its -Y component on the chord's DOWNWARD basis vector: measured
        // phat_y = -0.6995 … -0.7079 against h = 58.48 … 58.51, which realized ELBOW_A/P
        // 28.6 … 33.4 units BELOW this pose's own mat (declared level 0) at EVERY phase. The pose
        // rotates about world Z only (see declarePelvisTilt above), so world ∓Z IS the body's lateral
        // axis at every phase: the pole keeps the outward side the old Z sign already selected and
        // the arm's plane becomes the floor plane ("arms flat on the floor"). The elbow then reads
        // +7.1 … +12.9 u, its residual bow horizontal, and nothing else about the pose moves.
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 0f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 0f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
