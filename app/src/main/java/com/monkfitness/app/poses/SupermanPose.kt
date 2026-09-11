package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * P12 (§12.6): converted from the legacy world-position-built representation
 * (`solveIK -> setJoint(result) -> fromJointPositions`) to the authored-hierarchy idiom
 * (SkeletonFactory tree + declared root/lean + registered package bake limbs +
 * `fromHierarchy`). The limb realization runs through the registered authoring bake; no
 * direct `solveIK` bypass and no legacy reconstruction remains.
 *
 * **P5 correction — the body basis was WRONG.** The P12 WP-D conversion reproduced the legacy
 * world-position layout as "trunk toward −X with a +90° root tilt", which is the **supine**
 * basis this engine uses for DeadBug/LegRaise (`declarePelvisTilt(..., +π/2)`): measured on the
 * produced frame, the body's facing axis pointed **+Y (face up)**, i.e. Superman rendered
 * lying on its back, while `docs/Biomechanical Pose Specification (BPS)/Superman (Prone).md` §1/§3
 * specifies a **prone (face-down)** exercise whose floor fulcrum is the anterior body. The
 * symptoms were measurable and CI-invisible: at the rest phase the head sat **24.5 units below
 * the declared ground (level 0)** and both hands/fingertips 4–10 units below it, because the
 * legacy gaze direction `(−1, +0.3, 0)` is expressed in the head-end axis — un-mirrored, it
 * points INTO the floor once the body is rotated to lie horizontally.
 *
 * The corrected layout mirrors the authored layout onto the **prone** basis used by the sibling
 * prone pose (ReverseSnowAngelPose): `rotZ(−π/2)` maps the spine's local +Y to world +X (head
 * end) and the body's facing (local +X) to world −Y (face down). The head-end axis is therefore
 * +X: the head/gaze continue along **+X with the same +0.3 lift** as the legacy `(−1, +0.3, 0)`,
 * the legs extend **−X** away from the head, and the arms reach **+X** overhead.
 *
 * **M4 (`docs/STABILIZATION_AUDIT.md`) — extension is now ARTICULATED at the spine.** The
 * previous form carried the whole rep's extension on the ROOT (`±π/2 + chestLean`), i.e. it
 * rotated the pelvis→chest vector rigidly with no thoracolumbar articulation. The root now
 * carries the prone LAYOUT only and is constant across the rep (BPS §5: "the pelvis remains
 * neutral and stays on the floor; the extension originates from the paraspinals, not from
 * tilting the pelvis"); the tempo-dependent arch is authored on the **chest** node and declared
 * through the §1.1 carrier, so the chest/head/shoulders lift while the pelvis stays flat.
 *
 * BPS-driven layout: pelvis + thighs on the floor (fulcrum), chest/head/arms/legs lifted, arms
 * resting a few units above the floor and lifting with the arch — asserted numerically by
 * `SupermanPoseProneInvariantTest`.
 */
class SupermanPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var lumbar: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFIK = SkeletonMath.IKResult()
    private val legBIK = SkeletonMath.IKResult()
    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()

    private val tempV1 = Vector3()
    private val tempV2 = Vector3()
    private val tempV3 = Vector3()

    private fun ensureHierarchy(definition: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; lumbar = nodes.lumbar; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB
    }

    override fun build(context: PoseContext): SkeletonPose {
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        // B3 — every production pose declares its posture intent. Shape-driven root, so CUSTOM.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)
        val progress = context.progress
        val definition = context.definition
        ensureHierarchy(definition)

        val axisZ = Vector3(0f, 0f, 1f)
        val ground = metadata.environment.ground.level

        // PRONE LAYOUT (BPS §3): the root carries the layout only and is CONSTANT across the rep.
        // rotZ(-π/2): spine local +Y -> world +X (head end), facing local +X -> world -Y
        // (face DOWN). Same basis the sibling prone pose (ReverseSnowAngelPose) uses.
        val pelvisPos = tempV1.set(0f, 10f, 0f)
        pelvis!!.localPosition.set(pelvisPos)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, -PI.toFloat() / 2f)
        neck!!.localPosition.set(0f, definition.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        hipF!!.localPosition.set(0f, 0f, -definition.hipWidth)
        hipB!!.localPosition.set(0f, 0f, definition.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -definition.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, definition.shoulderWidth)

        // M4 (BPS §5): the tempo-dependent extension is ARTICULATED through the two-segment spine
        // (PELVIS -> LUMBAR -> CHEST), not carried as a rigid pelvis->chest rotation on the root.
        // This mirrors `buildSpineCurve(lumbar, chest, lower, thoracic, axisZ)` — the pattern the
        // repaired thoracic poses use — with the arch originating at the thoracolumbar junction so
        // the chest tips up while the pelvis stays neutral and flat on the floor. The member helper
        // is not reachable from a `PoseBuilder`-direct pose, so the same node writes + carrier
        // declarations are made explicitly here.
        val extension = lerp(0f, 0.2f, progress)
        lumbar!!.localPosition.set(0f, 0f, 0f)          // pass-through junction (Issue E)
        chest!!.localPosition.set(0f, definition.torsoLength, 0f)
        lumbar!!.localRotation.set(axisZ, extension)
        chest!!.localRotation.set(axisZ, extension * 0.4f)
        SkeletonPose.IntentBuilder(jointsBuffer).spine(extension, extension * 0.4f, axisZ)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.LUMBAR, JointRotation(axisZ, extension))
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.CHEST, JointRotation(axisZ, extension * 0.4f))

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // LEG TARGETS (BPS §7): legs extend back — AWAY from the head end, i.e. toward -X — and
        // lift with the rep through the same authored lean sweep as before.
        val totalLegLen = definition.thighLength + definition.shinLength
        val legLean = lerp(0f, 0.3f, progress)
        val toeF = tempV2.set(
            hipF!!.worldPosition.x - totalLegLen * cos(legLean),
            hipF!!.worldPosition.y + totalLegLen * sin(legLean),
            hipF!!.worldPosition.z
        )
        val toeB = tempV3.set(
            hipB!!.worldPosition.x - totalLegLen * cos(legLean),
            hipB!!.worldPosition.y + totalLegLen * sin(legLean),
            hipB!!.worldPosition.z
        )

        // Q12 (§12.6): legs declared through the registered authoring bake (the legacy
        // IKConstraint.LegConstraint constant is preserved verbatim).
        bakeIkLimb(hipF!!.worldPosition, toeF, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFIK, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, toeB, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBIK, jointsBuffer)

        // ARM TARGETS (BPS §6): arms reach forward/overhead — toward the head end, i.e. +X — and
        // lift together with the chest. The authored sweep parameter is preserved; the elevation
        // is anchored to the declared ground so the resting arms hover just above the floor
        // instead of passing through it (the pre-correction layout put hands/fingertips 4–10
        // units under the floor at the rest phase — see `SupermanPoseProneInvariantTest`).
        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val armLean = lerp(0.1f, -0.4f, progress)
        // Authored tempo-driven elevation, measured from the resting clearance above the floor.
        val armLift = max(0f, -totalArmLen * sin(armLean))
        val handY = ground + ARM_REST_CLEARANCE + armLift
        val targetHandA = Vector3(
            shoulderA!!.worldPosition.x + totalArmLen * cos(armLean),
            handY,
            shoulderA!!.worldPosition.z
        )
        val targetHandP = Vector3(
            shoulderP!!.worldPosition.x + totalArmLen * cos(armLean),
            handY,
            shoulderP!!.worldPosition.z
        )

        // P12 (§12.6): arms declared through the registered authoring bake.
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, definition.upperArmLength, definition.forearmLength, Vector3(0f, 1f, -1f), IKConstraint.ArmConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, definition.upperArmLength, definition.forearmLength, Vector3(0f, 1f, 1f), IKConstraint.ArmConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK, jointsBuffer)

        // HEAD (BPS §4): a neutral extension of the cervical spine, lifted with the thoracic arch —
        // i.e. carried by the spine chain itself, exactly as the sibling prone pose
        // (ReverseSnowAngelPose) carries its head.
        //
        // The legacy form declared a WORLD gaze point along (-1, +0.3, 0) so the head craned 16.7°
        // above the spine axis. That world-space form cannot be carried onto a body whose root is
        // rolled to lie horizontally: `SkeletonPoseFinalizer.resolveHeadTarget` derives its
        // direction from the world delta (target - neck.worldPosition) but then writes it as a
        // LOCAL offset (`neck.localPosition = dir * neckLength`, interpretion in the head's parent
        // frame), so on a prone-rolled chest the same direction aims the head INTO the floor —
        // measured: NECK_END y=-7.24, HEAD_POS y=-24.48 at the rest phase with the pose's own
        // ground declared at level 0 (this is what buried the head before this fix). Recovering it
        // would require a pose-side world->local frame conversion, which MIGRATION_RULES A8
        // prohibits, so the head is authored in the chain's own frame instead. The cervical
        // extension follows from the articulated spine; the head therefore lifts with the arch and
        // never leaves the floor plane's upper side.

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    private companion object {
        /**
         * How far the resting arms hover above the declared ground (skeleton units). BPS §9:
         * "arms lifted a few centimetres off the floor"; the sibling prone pose hovers its hand
         * targets a comparable margin above the pelvis line.
         */
        const val ARM_REST_CLEARANCE = 6f
    }
}
