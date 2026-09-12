package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Reverse Snow Angel (prone) — `docs/Biomechanical Pose Specification (BPS)/Reverse Snow Angel (Prone).md`.
 *
 * Prone scapular/thoracic exercise: the pelvis and legs stay grounded, the thoracic spine is held in
 * a gentle maintained extension, and the arms sweep through the "angel" arc along the floor.
 *
 * ## The authored hierarchy (M5 — corrected here)
 *
 * The pose previously built its own hand-rolled node tree (`pelvis → chest → shoulders/hips…`) and
 * so published a trunk chain with **no lower-spine segment**: `Joint.LUMBAR` (the engine's
 * two-segment `PELVIS → LUMBAR → CHEST` model) was absent from the tree and therefore published at
 * the **world origin** `(0, 0, 0)` — 18.03 units from the pelvis it belongs to — together with
 * `CLAVICLE_A/P`, `SCAPULA_A/P` and `WRIST_A/P`. The pose's own KDoc already claimed the §12.6
 * conversion "converted it to the authored-hierarchy idiom (SkeletonFactory tree …)"; the tree was
 * never migrated. It now uses the canonical factory tree like every other production pose, which
 * restores the trunk's lower-spine junction (and the clavicle/scapula girdle nodes) at their real
 * published positions. The migration is geometry-neutral for everything the pose authors: the
 * factory's added nodes are pass-throughs (coincident, identity rotation) between the chest and the
 * shoulder, so the limb targets, the arm sweep and the legs realize exactly as before.
 *
 * ## The maintained extension is a SPINE posture (M5)
 *
 * BPS §5/§9: "Thoracic spine maintained in gentle extension: the chest is long and open, the upper
 * back does not round"; "Thoracic extension: a maintained posture (small ROM), held isometrically";
 * "Lumbar spine neutral (natural lordosis), pelvis neutral and grounded — no arching or tucking".
 * The pose's authored maintained tilt (its own `leanAngle = 1.50` against the family's prone layout
 * of `−π/2`, i.e. `0.0708 rad` ≈ `4.1°`) was carried entirely by the ROOT, which tilted the pelvis
 * out of the prone layout and left the chest node with `localRotation.angle == 0.0000` at every
 * sampled frame — one rigid segment. It is now held on the two-segment spine (the same shape the
 * dynamic prone members use), so the pelvis stays neutral and the "chest long and open" is a real
 * thoracic posture. The amount is unchanged.
 */
class ReverseSnowAngelPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // M9 — the prone body's floor line: the sweeping hands (they stay on the mat through the
        // whole arc) and the legs. One canonical channel, `metadata.support`. Declaring the hands
        // is what resolves their contact at all (measured pre-fix the un-declared fingertips sat
        // 1.3–6.4 units off the plane at the sweep's extremes).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact.LEFT_HAND,
                SupportContact.RIGHT_HAND,
                SupportContact.LEFT_FOOT,
                SupportContact.RIGHT_FOOT
            )
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

    private val axisZ = Vector3(0f, 0f, 1f)

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        // The canonical factory tree (PELVIS → LUMBAR → CHEST → girdle/limbs). Its LUMBAR defaults to
        // a pass-through (coincident with the pelvis, identity rotation) and its CLAVICLE → SCAPULA →
        // SHOULDER chain defaults to a zero offset, so this tree realizes exactly what the previous
        // hand-rolled one did while also carrying the trunk's lower-spine segment.
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; lumbar = nodes.lumbar; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
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

        // 1. Prone core positioning: the root carries the whole-body PRONE LAYOUT only and is CONSTANT
        // across the rep — BPS §5/§7 "pelvis neutral and grounded — no arching or tucking", "The
        // pelvis stays grounded and neutral"; "the pelvis stays grounded". No second PELVIS joint
        // intent is declared: `declarePelvisTilt` already records that carrier.
        val pelvisX = 15f
        val pelvisY = 10f

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, PRONE_LAYOUT_PITCH)

        // The trunk chain: pass-through lower-spine junction, chest owns the trunk length, head/neck
        // authored in the chain's own frame (BPS §4: "The cervical spine is in neutral/gentle extension
        // consistent with the thoracic lift").
        lumbar!!.localPosition = Vector3(0f, 0f, 0f)
        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f); head!!.localPosition = Vector3(0f, 18f, 0f)

        // The maintained thoracic extension is a held SPINE posture (BPS §5/§9), authored once on the
        // two-segment spine: the thoracolumbar junction carries the trunk's maintained tilt and the
        // chest node holds the rib cage open above it. Isometric — constant across the rep by contract.
        lumbar!!.localRotation.set(axisZ, MAINTAINED_EXTENSION_RAD)
        chest!!.localRotation.set(axisZ, MAINTAINED_EXTENSION_RAD * THORACIC_SHARE)
        SkeletonPose.IntentBuilder(jointsBuffer).spine(MAINTAINED_EXTENSION_RAD, MAINTAINED_EXTENSION_RAD * THORACIC_SHARE, axisZ)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.LUMBAR, JointRotation(axisZ, MAINTAINED_EXTENSION_RAD))
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.CHEST, JointRotation(axisZ, MAINTAINED_EXTENSION_RAD * THORACIC_SHARE))

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Flush Spine FK to get precise Hip and Shoulder origins
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. LEG TARGETS (Completely planted on the floor)
        val totalLegLen = def.thighLength + def.shinLength
        val targetAnkleF = Vector3(pelvis!!.worldPosition.x - totalLegLen * 0.94f, 10f, -def.hipWidth)
        val targetAnkleB = Vector3(pelvis!!.worldPosition.x - totalLegLen * 0.94f, 10f, def.hipWidth)

        // P12 (§12.6): legs declared through the registered authoring bake (was direct
        // solveIK + raw-offset writes — the bypass family).
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The flat
        // foot on the forward-leaning shank is intentionally NOT hand-authored here; if the engine
        // derivation lands the foot imperfectly that is an engine limitation left exposed.

        // 3. ARM TARGETS (Wide sweeping arc from sides to above the head)
        // Smooth C2 cosine wave mapping progress 0.0 -> 0.5 (overhead hands) -> 1.0 (arms back)
        val u = (1f - cos(context.progress * 2f * PI.toFloat())) * 0.5f
        val maxSweep = 170f * PI.toFloat() / 180f
        val alpha = u * maxSweep

        val totalArmLen = (def.upperArmLength + def.forearmLength) * 0.94f
        val targetHandA = Vector3(
            shoulderA!!.worldPosition.x + totalArmLen * cos(alpha),
            15f, // Sweeping slightly lifted above the ground
            shoulderA!!.worldPosition.z - totalArmLen * sin(alpha)
        )
        val targetHandP = Vector3(
            shoulderP!!.worldPosition.x + totalArmLen * cos(alpha),
            15f,
            shoulderP!!.worldPosition.z + totalArmLen * sin(alpha)
        )

        // P12 (§12.6): arms declared through the registered authoring bake.
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    private companion object {
        /**
         * The whole-body prone LAYOUT (`rotZ(−π/2)`): spine local `+Y` → world `+X` (the head end),
         * ventral `+X` → world `−Y` (face down). The root carries this and only this, constant across
         * the rep (BPS §5/§7: pelvis neutral and grounded).
         */
        private const val PRONE_LAYOUT_PITCH = -PI.toFloat() / 2f

        /**
         * The pose's authored maintained thoracic extension above that layout, in radians: the pre-fix
         * authored root tilt was `leanAngle = 1.50` against the layout's `π/2`, i.e.
         * `π/2 − 1.5000 = 0.0708` (`4.06°`). Held isometrically (BPS §9) — not re-tuned by this
         * correction, only moved from the root onto the spine.
         */
        private val MAINTAINED_EXTENSION_RAD = (PI.toFloat() / 2f) - 1.50f

        /**
         * Thoracic share of that maintained extension carried by the chest node above the trunk line —
         * the repository's canonical two-segment shape (the S3 `ThoracicExtensionPose` repair of the
         * same defect class uses 0.4).
         */
        private const val THORACIC_SHARE = 0.4f
    }
}
