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
    private var pelvis: SkeletonNode? = null; private var lumbar: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
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
        pelvis = nodes.pelvis; lumbar = nodes.lumbar; chest = nodes.chest; neck = nodes.neck; head = nodes.head
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

        // ---- animation-logic correction: the tilt is carried by the pelvis AND the low back ----
        //
        // BPS §9 ("Pelvic rotation: posterior tilt to anterior tilt, a small arc — often only a few
        // degrees of true pelvic rotation, with the lumbar spine moving through its lordosis range")
        // and §5 ("the motion is concentrated at the lumbopelvic junction") are explicit that the
        // drill's articulation is the LUMBAR's lordosis range with the pelvis's own rotation a small
        // share of it. The pose published the whole `angleOffset` on the PELVIS and left the canonical
        // lower-spine segment rigid (measured through `SkeletonPipeline.produceFrame`: `LUMBAR`'s
        // world rotation was bit-identical to the `PELVIS`'s at every sampled phase — the articulation
        // was authored on the root, the M3/M4 defect class the prone family was corrected for).
        //
        // The authored arc is therefore SPLIT between the two segments, in the same sense and with the
        // SAME total: `pelvisRotation + lumbarRotation == torsoAngle` at every phase, so the published
        // trunk chain, the rep's 0.12-rad world arc, the pelvis's static base, the legs' quietness and
        // the pose's floor/contact behaviour are all untouched — only the joint that carries the
        // motion changes. `PELVIS_TILT_SHARE` is the pelvis's own few degrees; the LUMBAR carries the
        // remainder (BPS §9/§10: the low back flattens/arches as the pelvis tilts).
        val pelvisRotation = 1.5708f - PELVIS_TILT_SHARE * angleOffset
        val lumbarRotation = torsoAngle - pelvisRotation

        pelvis!!.localPosition = Vector3(0f, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), pelvisRotation)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.PELVIS, JointRotation(Vector3(0f, 0f, 1f), pelvisRotation))

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)

        // The lower-spine segment's share of the same arc: the LUMBAR articulation the drill is about
        // (`PELVIS -> LUMBAR -> CHEST`, Issue E). Its node offsets the CHEST exactly as the rigid
        // authoring did (a pass-through lumbar is coincident with the pelvis), so the trunk chain's
        // published geometry is reproduced to the digit; what changes is that the low back now
        // articulates instead of the root rotating the whole chain rigidly.
        lumbar!!.localRotation.set(Vector3(0f, 0f, 1f), lumbarRotation)
        // B4a — carrier-backed articulation (the pose-side equivalent of the base poses'
        // `buildSpineCurve`, which this `PoseBuilder`-direct pose cannot call): the LUMBAR/CHEST
        // intents are recorded on the ONE intent channel, so the Finalizer's consumer re-derives
        // exactly the node written here (mixed mode, byte-identical to the bare write).
        SkeletonPose.IntentBuilder(jointsBuffer).spine(lumbarRotation, 0f, Vector3(0f, 0f, 1f))
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.LUMBAR, JointRotation(Vector3(0f, 0f, 1f), lumbarRotation))

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

        // R2/R4 — reach-band authoring (fourth reach-band cleanup batch). Same authored stance as
        // the supine sibling `GluteBridgePose` and the same class, only clearer here because this
        // pose's pelvis is STATIC (the B4 trunk half keeps `pelvisY = 14`): the hip→ankle chord is
        // `45.0111` at EVERY one of the 15 sampled phases, `10.9979` u INSIDE the leg chain's
        // minimum-flexion reach `SkeletonMath.minReach(112, 98, 30°) = 56.0090` — an authored
        // interior knee angle of `23.52°` against the `IKConstraint`'s own `30°` stop (150° of
        // flexion) — so the solver relocated both feet along the authored rays at every phase (the
        // realized knees read exactly `30.0000°` interior) and the reachability stamp carried the
        // same `10.9979`.
        //
        // Verdict: unintended authoring error, not a deliberate ROM limit — the pose's own BPS
        // (§3/§7) specifies "knees bent ~90°, feet flat hip-width apart ... no lifting the feet or
        // sliding the feet", and a 90° interior is a `148.81` u chord, comfortably inside the band.
        // The projection preserves each authored ray and only moves the radius onto the annulus —
        // the position the solver already publishes — so the static pelvis, the trunk tilt arc (the
        // B4 correction), the arm authoring and the B4 elbow plane are untouched.
        //
        // Residual, recorded and NOT resolved here: the PUBLISHED stance is unchanged by this
        // declaration fix — the feet still publish at `(55.9952, 15.2443)` with the knees on the
        // 150° fold — because moving them to the BPS's ~90° setup (a ~`148.8` u chord) moves the
        // visible geometry and is a pose-design (owner) decision. The reach-band convention fixes
        // the TARGET, not the stance.
        SkeletonMath.clampTargetToReach(
            hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength,
            def.legIKConstraint, targetAnkleF, REACH_MARGIN
        )
        SkeletonMath.clampTargetToReach(
            hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength,
            def.legIKConstraint, targetAnkleB, REACH_MARGIN
        )

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

    private companion object {
        /**
         * The pelvis's own share of the authored tilt arc (BPS §9: "posterior tilt to anterior tilt,
         * a small arc — often only a few degrees of true pelvic rotation, with the lumbar spine moving
         * through its lordosis range"). The remaining `1 − 0.35` of the same arc is the LUMBAR's, i.e.
         * the low back carries the lordosis range the drill is about (BPS §5/§10). The two segments
         * always sum to the authored total, so the rep's range and the published trunk chain are
         * unchanged by the split — only the joint that carries the motion is.
         */
        const val PELVIS_TILT_SHARE = 0.35f

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
         * measurement that chose `1e-4` for the squat, hip-flexor and hamstring families, and for
         * the sibling `GluteBridgePose`).
         */
        const val REACH_MARGIN = 1e-4f
    }
}
