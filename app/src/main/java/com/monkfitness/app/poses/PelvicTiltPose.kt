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
        // The planted feet (supine pelvic tilt: the feet are the ground contact through the
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
        // Canonical hierarchy (`SkeletonFactory.createStandardSkeleton()`): LUMBAR is coincident with
        // PELVIS and CLAVICLE_*/SCAPULA_* with CHEST, so every transform authored below resolves as before.
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
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)
        // Every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)

        // Pelvis Y remains static on the floor (14f) — the pelvis joint IS this pose's resting layer:
        // the whole authored trunk chain (chest 120, neck 18, head 18) lies ON it at the flat supine
        // tilt, so a trunk joint leaving that layer downward leaves the body's volume in the mat.
        val pelvisY = 14f
        // Dynamic subtle tilt of the pelvis: the rep's authored arc, 0 -> 0.12 rad (EASE_IN_OUT).
        //
        // The SIGN of this arc is the pose's whole relationship to the mat (the trunk half): the trunk
        // is rigid and hangs off the static pelvis, so the old `+0.12` spent the arc on the chain's sin
        // and published the upper body UNDER the mat from p ≈ 0.846 (the T2 pin). The arc tips the
        // pelvis's superior axis AWAY from the mat instead: same pivot, same amplitude, same rep shape.
        val angleOffset = lerp(0f, 0.12f, context.progress)
        val torsoAngle = 1.5708f - angleOffset

        //
        // BPS §9/§5 are explicit that the drill's articulation is the LUMBAR's lordosis range with the
        // pelvis's own rotation a small share of it. The pose published the whole `angleOffset` on the
        // PELVIS and left the lower-spine segment rigid (`LUMBAR`'s world rotation bit-identical to the
        // `PELVIS`'s at every phase — a defect class this correction removes). The arc is therefore
        // SPLIT between the two segments in the same sense and with the SAME total: `pelvisRotation + lumbarRotation ==
        // torsoAngle` at every phase, so the published trunk chain and the rep are untouched — only the
        // joint that carries the motion changes.
        val pelvisRotation = 1.5708f - PELVIS_TILT_SHARE * angleOffset
        val lumbarRotation = torsoAngle - pelvisRotation

        pelvis!!.localPosition = Vector3(0f, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), pelvisRotation)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.PELVIS, JointRotation(Vector3(0f, 0f, 1f), pelvisRotation))

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)

        // The lower-spine segment's share of the same arc (`PELVIS -> LUMBAR -> CHEST`, Issue E): a
        // pass-through lumbar is coincident with the pelvis, so the published chain is reproduced.
        lumbar!!.localRotation.set(Vector3(0f, 0f, 1f), lumbarRotation)
        // Carrier-backed articulation (the pose-side equivalent of `buildSpineCurve`, which this
        // `PoseBuilder`-direct pose cannot call): the LUMBAR/CHEST intents go on the ONE intent channel
        // and the Finalizer re-derives exactly the node written here (byte-identical to the bare write).
        SkeletonPose.IntentBuilder(jointsBuffer).spine(lumbarRotation, 0f, Vector3(0f, 0f, 1f))
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.LUMBAR, JointRotation(Vector3(0f, 0f, 1f), lumbarRotation))

        // Neck and Head stay horizontal, resting on the floor: the neck's articulation cancels the
        // trunk's tilt exactly (its signed value follows the trunk's), so the neck's world rotation is
        // the flat supine 1.5708 under either tilt direction and the head chain keeps the pose's
        // supine orientation.
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        neck!!.localRotation.set(Vector3(0f, 0f, 1f), angleOffset)
        // Carrier-backed neck ROM: record the neck articulation as a joint intent so the
        // Finalizer consumes it idempotently (mixed mode, byte-identical to the bare write).
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.NECK_END, JointRotation(Vector3(0f, 0f, 1f), angleOffset))
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        val targetAnkleF = Vector3(45f, def.foot.ankleHeight, -def.hipWidth)
        val targetAnkleB = Vector3(45f, def.foot.ankleHeight, def.hipWidth)

        // The hip→ankle chord is `45.0111` u at every phase, `10.9979` u INSIDE the leg chain's
        // `minReach = 56.0090`, so the solver relocated both feet along the authored rays (the realized
        // knees read exactly `30.0000°` interior). Verdict: unintended authoring error, not a ROM limit —
        // the BPS's ~90° setup is a `148.81` u chord, comfortably inside the band. The projection
        // preserves each authored ray and only moves the radius onto the annulus. Residual, recorded and
        // NOT resolved here: the PUBLISHED stance is unchanged (moving to ~90° is a pose-design call).
        SkeletonMath.clampTargetToReach(
            hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength,
            def.legIKConstraint, targetAnkleF, REACH_MARGIN
        )
        SkeletonMath.clampTargetToReach(
            hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength,
            def.legIKConstraint, targetAnkleB, REACH_MARGIN
        )

        // Legs declared through the registered authoring bake (was direct
        // solveIK + raw-offset writes — the bypass family).
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        val targetHandA = Vector3(-35f, 12f, -def.shoulderWidth - 5f)
        val targetHandP = Vector3(-35f, 12f, def.shoulderWidth + 5f)

        // The elbow's BEND SIDE is this pose's own LATERAL axis, not the standing family's
        // downward pole (see GluteBridgePose). The chord is horizontal, so the family's `(0, -1, ∓1)`
        // pole spent its -Y component on the chord's DOWNWARD basis vector and realized ELBOW_A/P
        // `28.6 … 33.4` units BELOW this pose's own mat at every phase. The pose rotates about world Z
        // only, so world ∓Z IS the body's lateral axis: the elbow now reads `+7.1 … +12.9` u.
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 0f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 0f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    private companion object {
        /**
         * The pelvis's own share of the authored tilt arc (BPS §9: often only a few degrees of true
         * pelvic rotation). The remaining `1 − 0.35` is the LUMBAR's — the low back carries the lordosis
         * range the drill is about — and the two always sum to the authored total, so the rep's range and
         * the published trunk chain are unchanged by the split.
         */
        const val PELVIS_TILT_SHARE = 0.35f

        /**
         * The projection margin — a hundredth of a percent of the chain's span. The target is
         * placed just INSIDE its annulus: the carrier stores ~`3e-5` absolute resolution at these radii,
         * so a boundary-exact target re-fires a float-noise relocation (`8e-6` stamp) while this margin
         * makes "inside the band" hold by construction and the stamp read exactly `0`. The helper's
         * canonical `0.02` is NOT used: it would pull the feet a further `1.12` u out along the stance
         * ray, geometry the reach defect does not require.
         */
        const val REACH_MARGIN = 1e-4f
    }
}
