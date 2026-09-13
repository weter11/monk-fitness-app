package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class GluteBridgePose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // M10 — the planted feet (supine bridge: the feet are the ground contact through the whole
        // rep), on the ONE canonical support channel (`metadata.support`). The pelvis/shoulder
        // contact with the mat is not declarable in a consumed family (see the finding record).
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
        // Canonical hierarchy (`SkeletonFactory.createStandardSkeleton()` — the M11 shape the migrated
        // families publish). The factory's added nodes are pass-throughs between the links this pose
        // already authored: LUMBAR is coincident with the PELVIS (identity rotation) and
        // CLAVICLE_*/SCAPULA_* are coincident with the CHEST, so every transform authored below
        // resolves exactly as before. What changes is that the five canonical joints carry their
        // authored transforms instead of publishing at the world origin.
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)

        // Supine glute bridge: lying on the back
        // progress 0 (resting on floor) to 1 (bridge peak)
        val pelvisY = lerp(14f, 54f, context.progress)
        val angleOffset = lerp(0f, 0.35f, context.progress)
        val torsoAngle = 1.5708f + angleOffset

        pelvis!!.localPosition = Vector3(0f, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), torsoAngle)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.PELVIS, JointRotation(Vector3(0f, 0f, 1f), torsoAngle))

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)

        // Neck/Head stay flat on the ground.
        // World rotation of neck/head is horizontal (1.5708f pointing along negative X).
        // Since chest world rotation is torsoAngle, localRotation of neck is set to -angleOffset.
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        neck!!.localRotation.set(Vector3(0f, 0f, 1f), -angleOffset)
        // B4a — carrier-backed neck ROM: record the neck articulation as a joint intent so the
        // Finalizer (B2) consumes it idempotently (mixed mode, byte-identical to the bare write).
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.NECK_END, JointRotation(Vector3(0f, 0f, 1f), -angleOffset))
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Compute Spine transforms
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 1. Static Foot Placements (prevent foot sliding and penetration)
        val targetAnkleF = Vector3(45f, def.foot.ankleHeight, -def.hipWidth)
        val targetAnkleB = Vector3(45f, def.foot.ankleHeight, def.hipWidth)

        // R2/R4 — reach-band authoring (fourth reach-band cleanup batch). The authored stance above
        // is a resting place, not a request to fold: measured through the production entry point on
        // the PUBLISHED frame, the supine hip sits at y = 14 (p = 0) while both feet are authored at
        // (±45, ankleHeight 15), so the hip→ankle chord is 45.0111 at the rep's start — 10.9979 u
        // INSIDE the leg chain's own minimum-flexion reach
        // `SkeletonMath.minReach(112, 98, 30°) = 56.0090`, i.e. the authored stance asks for an
        // interior knee angle of 23.52° against the `IKConstraint`'s own 30° stop (150° of flexion),
        // for 12 of the 15 sampled phases. The solver answered by relocating both feet along the
        // authored rays (the realized knees read exactly 30.0000° interior) and the reachability
        // stamp carried the same number.
        //
        // Verdict: unintended authoring error, not a deliberate ROM limit — the pose's own BPS
        // (§7/§9) specifies "knees bent ~90°, feet flat ... shins near vertical", a configuration
        // that sits comfortably INSIDE the band (a 90° interior is a 148.81 u chord), so nothing in
        // this exercise requires the chain's fold stop. The projection preserves each authored ray
        // and only moves the radius onto the annulus — the position the solver already publishes —
        // so the stance, the pelvis arc (14 → 54), the arm authoring and the B4 elbow plane are
        // untouched.
        //
        // Residual, recorded and NOT resolved here: the PUBLISHED stance is unchanged by this
        // declaration fix, so the feet still publish at `(55.9952, 15.2443)` with the knees on the
        // 150° fold — the BPS's ~90° setup needs the feet authored further from the hip (a ~148.8 u
        // chord), which moves the visible geometry and is a pose-design (owner) decision. The
        // reach-band convention fixes the TARGET, not the stance.
        SkeletonMath.clampTargetToReach(
            hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength,
            def.legIKConstraint, targetAnkleF, REACH_MARGIN
        )
        SkeletonMath.clampTargetToReach(
            hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength,
            def.legIKConstraint, targetAnkleB, REACH_MARGIN
        )

        // P12 (§12.6): legs declared through the registered authoring bake (was direct
        // solveIK + raw-offset writes — the bypass family; knee points upwards/forwards via
        // the same upper pole vector).
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // W1 migration: the engine now derives heel/toe from the shank + the ankle articulation
        // relative to the shank, cancelling the inherited pelvis/torso rotation automatically. The
        // old `ankle.localRotation = -torsoAngle` counter-rotation and the manual 0.29/0.71 heel/toe
        // authoring are removed; a neutral (identity) ankle lays the foot flat against the floor.

        // 2. Arm placement (lying flat alongside the body)
        // Hand coordinates are static (prevents hand sliding)
        val targetHandA = Vector3(-35f, 12f, -def.shoulderWidth - 5f)
        val targetHandP = Vector3(-35f, 12f, def.shoulderWidth + 5f)

        // B4 — the elbow's BEND SIDE is this pose's own LATERAL axis, not the standing family's
        // downward pole. A supine pose lays its arms in the floor plane, so the shoulder->hand chord
        // is horizontal (shoulder y = 12.9 … 14.0, hand y = 12.0) and the family's pole
        // (0, -1, ∓1) spends its -Y component on the chord's DOWNWARD basis vector: measured
        // phat_y = -0.7069 … -0.7075 against h = 58.49 … 60.74, which realized ELBOW_A/P 28.6 … 30.7
        // units BELOW this pose's own mat (declared level 0) at EVERY phase of the rep. The pose
        // rotates about world Z only (see declarePelvisTilt above), so world ∓Z IS the body's lateral
        // axis at every phase: the pole keeps the outward side the old Z sign already selected (the
        // elbow bows outboard, never across the torso) and the arm's plane becomes the floor plane
        // ("lying flat alongside the body"). The elbow then reads +7.1 … +12.9 u, its residual bow
        // horizontal. Only the bend side changes: the hand target, the stance, the plant and the leg
        // authoring are untouched (SupineArmElbowPlaneTest).
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 0f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 0f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // W1 migration: the engine derives palm/knuckles/fingertips from the forearm + the wrist
        // articulation relative to the forearm, so the old `hand.localRotation = -torsoAngle`
        // counter-rotation and the manual 6/6/10 hand authoring are removed; a neutral wrist lays
        // the hand flat along the forearm.

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    private companion object {
        /**
         * The R2/R4 projection margin — a hundredth of a percent of the chain's span.
         *
         * The authored target is placed just INSIDE its annulus, not exactly on the boundary: the
         * carrier stores coordinates at ~`3e-5` absolute resolution at these radii, so a
         * boundary-exact target re-fires a float-noise relocation (the reachability stamp reads
         * `8e-6`), while this margin makes "inside the band" hold by construction and the stamp read
         * exactly `0`.
         *
         * The published geometry cost is bounded by the margin itself (`0.006` u at the leg's
         * `minReach`), because the solver's own relocation WAS the boundary projection this
         * replaces. The helper's canonical `0.02` is NOT used: here it would pull the feet a further
         * `1.12` u out along the stance ray — geometry the reach defect does not require (the same
         * measurement that chose `1e-4` for the squat, hip-flexor and hamstring families).
         */
        const val REACH_MARGIN = 1e-4f
    }
}
