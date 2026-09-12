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
 * ## The body basis (M4 — corrected here)
 *
 * The P12 WP-D conversion reproduced the legacy world-position layout as "trunk toward −X with a
 * +90° root tilt", which is the **supine** basis this engine uses for DeadBug/LegRaise
 * (`declarePelvisTilt(..., +π/2)`). Measured on the published frame: the body's ventral axis pointed
 * **+Y (face up)** — Superman rendered lying on its back — while
 * `docs/Biomechanical Pose Specification (BPS)/Superman (Prone).md` §1/§3/§8 specify a **prone
 * (face-down)** exercise whose floor fulcrum is the **anterior** body ("The contact surface is the
 * front of the body (anterior), unlike supine exercises"). The symptoms were measurable and
 * CI-invisible: at the rest phase the head sat **24.5 units below the declared ground (level 0)**
 * with 12 joints (head/neck/hands/wrists/palms/knuckles/fingertips) under it and the hands 4.3
 * units under it, because the legacy gaze direction `(−1, +0.3, 0)` is expressed along the
 * head-end axis — un-mirrored it points INTO the floor once the body lies down.
 *
 * The corrected layout uses the **prone** basis of the sibling prone poses
 * (`ReverseSnowAngelPose`/`ProneCobraStretchPose`): `rotZ(−π/2)` maps the spine's local `+Y` to
 * world `+X` (head end) and the body's ventral `+X` to world `−Y` (face down). The head-end axis is
 * therefore `+X`: the head/gaze continue along `+X`, the legs extend `−X` away from the head, and
 * the arms reach `+X` overhead.
 *
 * ## The extension is articulated on the spine (M4's second half)
 *
 * The previous form carried the whole rep's extension on the ROOT (`±π/2 + chestLean`), i.e. it
 * rotated the pelvis→chest vector rigidly — the pelvis, which BPS §5/§7 name as the floor fulcrum,
 * was the hinge — and the chest node carried `localRotation.angle == 0.0000` at every frame. The
 * root now carries the prone LAYOUT only and is CONSTANT across the rep (BPS §5: "The pelvis
 * remains neutral (not posteriorly tilted/tucked) and stays on the floor; the extension originates
 * from the paraspinals, not from tilting the pelvis"); the tempo-dependent arch is authored on the
 * two-segment spine below, with the chest node carrying the thoracic share above the trunk line.
 *
 * BPS-driven layout: pelvis + thighs on the floor (the fulcrum), chest/head/arms/legs lifted, arms
 * level with the body's own floor line at rest and rising with the arch — asserted numerically by
 * `M3M5ProneTrunkGeometryTest`.
 */
class SupermanPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // M9 — the hands' floor line at the prone seam (the bow lifts the arms off the mat and the
        // hand derivation is self-gating: it only orients a hand that is BELOW its elbow, so the
        // lifted phase is untouched). One canonical channel, `metadata.support`. The FEET are
        // deliberately NOT declared: the bow lifts the legs as one rigid line well clear of the
        // floor, and the foot derivation has no "planted" gate (measured: declaring them would
        // re-aim the lifted foot's long axis, a 17.57-unit change on a limb that has left the mat).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND)
        )
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

        // PRONE LAYOUT (BPS §3): the root carries the layout only and is CONSTANT across the rep.
        // rotZ(−π/2): spine local +Y → world +X (the head end), ventral local +X → world −Y (face
        // DOWN) — the same basis the sibling prone poses (ReverseSnowAngel/ProneCobra) publish.
        val pelvisPos = tempV1.set(0f, PRONE_BODY_Y, 0f)
        pelvis!!.localPosition.set(pelvisPos)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, PRONE_LAYOUT_PITCH)

        // The trunk: the lower-spine junction is the factory's pass-through (coincident, identity);
        // the CHEST owns the trunk length; the head/neck are authored in the chain's OWN frame so
        // the articulated spine carries them (BPS §4 — "Head is a neutral extension of the cervical
        // spine"). No `headTarget` is declared: `SkeletonPoseFinalizer.resolveHeadTarget` derives its
        // direction from a WORLD delta but writes it as a LOCAL offset, which cannot express a gaze
        // on a body whose root is rolled to lie down, and the pose-side world→local conversion that
        // would recover it is prohibited by `MIGRATION_RULES` A8.
        lumbar!!.localPosition.set(0f, 0f, 0f)
        chest!!.localPosition.set(0f, definition.torsoLength, 0f)
        neck!!.localPosition.set(0f, definition.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        hipF!!.localPosition.set(0f, 0f, -definition.hipWidth)
        hipB!!.localPosition.set(0f, 0f, definition.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -definition.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, definition.shoulderWidth)

        // THE REP'S ARCH — articulated on the two-segment spine (PELVIS → LUMBAR → CHEST), not on the
        // root: the thoracolumbar junction lifts the chest off the floor and the chest node opens the
        // rib cage/girdle above that line (the repository's canonical `buildSpineCurve` shape and its
        // 0.4 thoracic share — the S3 ThoracicExtension repair of this same defect class). The
        // member helper is not reachable from a `PoseBuilder`-direct pose, so the same node writes
        // plus carrier declarations are made explicitly here (the pattern `ThoracicExtensionPose`
        // documents for the direct-implementation gap).
        val extension = lerp(0f, EXTENSION_RAD, progress)
        lumbar!!.localRotation.set(axisZ, extension)
        chest!!.localRotation.set(axisZ, extension * THORACIC_SHARE)
        SkeletonPose.IntentBuilder(jointsBuffer).spine(extension, extension * THORACIC_SHARE, axisZ)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.LUMBAR, JointRotation(axisZ, extension))
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.CHEST, JointRotation(axisZ, extension * THORACIC_SHARE))

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // LEG TARGETS (BPS §7/§9): the legs extend BACK — away from the head end, i.e. toward −X —
        // and lift together through the authored lean sweep (hip extension, ~10–20° of lift).
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

        // P12 (§12.6): legs declared through the registered authoring bake (the legacy
        // IKConstraint.LegConstraint constant is preserved verbatim).
        bakeIkLimb(hipF!!.worldPosition, toeF, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFIK, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, toeB, definition.thighLength, definition.shinLength, Vector3(0f, 1f, 0f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBIK, jointsBuffer)

        // ARM TARGETS (BPS §6/§9): the arms extend forward/overhead — toward the head end, i.e. +X —
        // along the floor line, and lift together with the arch. At rest the hands ride the pose's own
        // prone floor line (`PRONE_BODY_Y`, the height the pelvis's declared fulcrum sits at) instead
        // of hanging through the floor: the pre-correction layout put the hands 4.3 units and the
        // fingertips 9.8 units under the pose's declared ground at the rest phase. The authored
        // elevation amplitude is unchanged — only its datum is the floor line rather than a level
        // that does not exist for a prone body.
        val totalArmLen = definition.upperArmLength + definition.forearmLength
        val armLean = lerp(0.1f, -0.4f, progress)
        val handY = PRONE_BODY_Y + max(0f, -totalArmLen * sin(armLean))
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

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    private companion object {
        /**
         * The prone whole-body LAYOUT (`rotZ(−π/2)`): spine local `+Y` → world `+X` (the head end),
         * ventral `+X` → world `−Y` (face down). Constant across the rep — BPS §5: the pelvis is the
         * floor fulcrum and stays neutral; the extension originates from the paraspinals.
         */
        private const val PRONE_LAYOUT_PITCH = -PI.toFloat() / 2f

        /**
         * The height the pose's prone body line rests at (its authored pelvis position, unchanged):
         * the anterior body is the fulcrum, so this is also the floor line every extremity is measured
         * from (the arm rest datum below).
         */
        private const val PRONE_BODY_Y = 10f

        /**
         * The rep's authored arch (rad), unchanged from the pre-correction `chestLean =
         * lerp(0, −0.2)`: the correction moves the rotation's OWNERSHIP onto the spine, it does not
         * re-tune the depth (BPS §9: "Spinal extension: moderate (thoracic + lumbar), short of
         * end-range … the pose is a hover").
         */
        private const val EXTENSION_RAD = 0.2f

        /**
         * Thoracic share carried by the chest node above the trunk line — the repository's canonical
         * two-segment shape (`ThoracicExtensionPose`, the S3 repair of this same defect class, uses
         * 0.4).
         */
        private const val THORACIC_SHARE = 0.4f
    }
}
