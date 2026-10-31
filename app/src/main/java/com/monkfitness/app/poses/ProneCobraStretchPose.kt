package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class ProneCobraStretchPose : BasePose() {

    /**
     * The whole-body **prone LAYOUT**: spine local `+Y` → world `+X` (head end), ventral `+X` → world
     * `−Y` (face down). The root carries this and only this, constant across the rep — BPS §5/§7:
     * the pelvis stays neutral and grounded, the extension originates from the paraspinals. (The
     * pre-fix pose drove `lerp(−1.57, −0.9)` into THIS declaration, so the pelvis was the hinge.)
     */
    private val proneLayoutPitch = -1.57f

    /** The rep's authored trunk extension (rad), measured on the pre-fix tree as the pelvis
     *  rotation's own span — `lerp(−1.57, −0.9)` ⇒ `1.5700 − 0.9000 = 0.6700`. The correction below
     *  moves the OWNERSHIP of this rotation onto the spine; it does not re-tune its amount. */
    private val extensionRad = 0.67f

    /** Thoracic share of the extension carried by the chest node above the trunk line. The
     *  repository's canonical two-segment shape — the S3 `ThoracicExtensionPose` repair of this same
     *  defect class uses `extAngle` on the thoracolumbar junction and `extAngle * 0.4` on the
     *  thoracic spine. */
    private val thoracicShare = 0.4f

    // Shared camera; the default pitch is raised ~10% (0.22 -> 0.242) so the prone arch stays framed.
    private val cobraCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.242f,
        defaultZoom = 1.25f
    )
    private val cobraGround = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

    override val metadata = PoseMetadata(
        camera = cobraCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = cobraGround,
        // The planted hands (the pillar the chest is pressed and lifted from) and the feet
        // (the legs lie along the floor line), on the ONE canonical support channel. Declaring the
        // hands is what makes the engine's hand derivation resolve the contact at all: measured
        // pre-fix, the un-declared `FINGERTIPS_A/P` hung 6.99 units BELOW the floor at the seam.
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

    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis
        lumbar = nodes.lumbar
        chest = nodes.chest
        neck = nodes.neck
        head = nodes.head
        shoulderA = nodes.shoulderA
        elbowA = nodes.elbowA
        handA = nodes.handA
        palmA = nodes.palmA
        knucklesA = nodes.knucklesA
        fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP
        elbowP = nodes.elbowP
        handP = nodes.handP
        palmP = nodes.palmP
        knucklesP = nodes.knucklesP
        fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF
        kneeF = nodes.kneeF
        ankleF = nodes.ankleF
        heelF = nodes.heelF
        toeF = nodes.toeF
        hipB = nodes.hipB
        kneeB = nodes.kneeB
        ankleB = nodes.ankleB
        heelB = nodes.heelB
        toeB = nodes.toeB
    }

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // Every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        // 1. Core Anchoring: the root carries the whole-body PRONE LAYOUT only, constant across the
        // rep (see the constant's KDoc above; `declarePelvisTilt` already records the PELVIS
        // joint intent, so no second, stale declaration is made here).
        val pelvisX = 0f
        val pelvisY = 15f // Rest perfectly flat on the ground

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, proneLayoutPitch)

        // Trunk geometry: the lower-spine junction stays the factory's pass-through (coincident with
        // the pelvis, identity rotation — Issue E) and the CHEST owns the trunk length.
        lumbar!!.localPosition.set(0f, 0f, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)

        // 2. The rep's extension is ARTICULATED on the two-segment spine, not carried on the root.
        // BPS §5: "Thoracic: gently extended as the chest lifts — the upper back is the prime
        // mover" / "The extension is smooth along the spine, not a hinge at one segment"; §13: "The
        // chest lifts off the floor via thoracic extension while the lumbar spine stays neutral."
        // The thoracolumbar junction lifts the chest off the floor (the rep's authored amount, see
        // `extensionRad`) and the chest node opens the rib cage/girdle above that line. This is the
        // repository's single authorized trunk-lean path (`buildSpineCurve`, the same shape the S3
        // ThoracicExtension repair uses) — one call, both segments, carriers included.
        val extension = SkeletonMath.lerp(0f, extensionRad, context.progress)
        buildSpineCurve(lumbar!!, chest!!, extension, extension * thoracicShare, axisZ)

        // Head tilts up dynamically to follow the cobra stretch. Declared as a gaze target
        // while the legacy direction path still writes the head.
        val headTilt = SkeletonMath.lerp(0f, -0.3f, context.progress)
        val headDir = SkeletonMath.rotAround(Vector3(0.2f, 1f, 0f), axisZ, headTilt, Vector3()).normalize()
        buildGaze(neck!!, head!!, def.neckLength, headDir)

        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // IK pulls the ankles backward along the floor line (-X direction)
        val targetAnkleX = pelvisX - def.thighLength - def.shinLength + 15f
        val targetAnkleF = Vector3(targetAnkleX, 15f, -def.hipWidth)
        val targetAnkleB = Vector3(targetAnkleX, 15f, def.hipWidth)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        val chestW = chest!!.worldPosition

        // Hands lift off the ground and sweep back towards the hips/heels
        val reachX = SkeletonMath.lerp(chestW.x, pelvisX - 50f, context.progress)
        val reachY = SkeletonMath.lerp(15f, 40f, context.progress)

        val targetHandA = Vector3(reachX, reachY, -def.shoulderWidth * 1.5f)
        val targetHandP = Vector3(reachX, reachY, def.shoulderWidth * 1.5f)

        // The authored sweep leaves the arm chain's annulus at BOTH ends: its start
        // (`23.000` from the shoulder) asks for a `14.39°` interior elbow against the `30°` stop, and its
        // end (`154.027`) is longer than the `80 + 66 = 146` u arm, so the solver relocated the hands by
        // `17.134 … 10.947` u along the authored ray. Unintended authoring error at both sites: the
        // authored sweep — direction, extent and its `x`/`y` choreography — is preserved, only the radius
        // moves onto the annulus, which is where the engine already publishes the hands.
        SkeletonMath.clampTargetToReach(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA, REACH_MARGIN)
        SkeletonMath.clampTargetToReach(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP, REACH_MARGIN)

        // Pole vectors orient elbows upward and outward to squeeze the shoulder blades
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // Hands parallel to the floor, palms facing down
        // The engine now derives hand orientation (removed tilt counter-rotation + 6/6/10 offsets).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /**
         * Projection margin for the sweep's two endpoints — a hundredth of a percent of the
         * chain's span. Both endpoints ARE effector positions the engine already publishes, so the
         * margin only keeps the target strictly inside the annulus: at `3e-5` carrier resolution a
         * boundary-exact target re-fires a float-noise relocation. The helper's `0.02` would instead
         * move the hands `0.81` / `2.87` u away from where the engine publishes them.
         */
        const val REACH_MARGIN = 1e-4f
    }
}
