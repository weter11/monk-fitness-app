package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

abstract class BasePushUpPose : BasePose() {

    // Subclasses only specify their parameters / metadata + configuration
    abstract val gripWidthMultiplier: Float
    open val handAnchorXOffset: Float = 0f
    // IK elbow poles are authored in WORLD space (MIGRATION_RULES B6: the pose never
    // converts frames — `bakeIkLimb` treats the pole as world, the Finalizer owns any
    // local→world conversion). A positive Z component seats the elbow outward; the
    // Y component biases the bend plane upward (never sagging through the floor).
    open val poleA: Vector3 = Vector3(1f, 0.5f, -1f)
    open val poleP: Vector3 = Vector3(1f, 0.5f, 1f)
    // Decline tilt: a downward trunk pitch (radians, about the spine's mediolateral Z
    // axis) so the head-to-heels plank slopes downward for feet-elevated decline
    // variants (STABILIZATION_AUDIT M14). 0 for all flat-planar members.
    open val declineTrunkPitch: Float = 0f

    // Knee-pivot depth: sagittal hip flexion (radians) at the bottom of the rep, i.e. how far the
    // trunk pitches over the planted knee so the shoulders descend and the elbows press. Applied at
    // the hip on a plank whose pelvis the solver places (`PushUpPlank.solve`, KNEES), instead of
    // bending the trunk about the pelvis. Calibrated against the arm: the deepest trunk pitch must
    // still leave the authored hand target inside `SkeletonMath`'s reachable annulus.
    private val kneePivotDepthHipFlexion: Float = 0.42f

    /**
     * The flat plank's trunk-frame roll, in radians, about the chest's lateral Z axis.
     *
     * `buildTorso` lays the chest one torso length along the pelvis's LOCAL -X while the chest's local
     * +Y is its anatomical spine (up) axis, so the matching trunk frame is the chest's local frame
     * rolled +pi/2. That is exactly the rotation `reconstructChestFrame` derives for this layout
     * (verified bit-equal: axis (0,0,1), angle `PI.toFloat() / 2f`), so declaring it does not change
     * the published frame — it only makes it authoritative BEFORE the limb realization.
     */
    private val trunkFrameRoll: Float = PI.toFloat() / 2f

    /**
     * Declares this family's trunk (chest) frame as pose-owned intent: the node write plus
     * the paired §1.1 chest carrier, the two-effect form every authored articulation in [BasePose] uses.
     *
     * Why here and not in the engine: the frozen pipeline assigns the chest intent to the POSE
     * (`ARCHITECTURE_V2` §4.1) and runs limb realization first, while the Finalizer's chest-frame
     * reconstruction is a Phase-3 read-only-on-settled *fallback*. A pose that leaves the frame
     * unauthored therefore realizes its limbs in the pelvis-only frame and is corrected afterwards —
     * the trunk-frame defect. A member that authors its own trunk rotation ([declineTrunkPitch]) keeps it: the
     * engine treats a non-identity chest as authored intent (Issue F) and never reconstructs it.
     */
    protected fun declareFlatPlankTrunkFrame() {
        if (declineTrunkPitch != 0f) return
        chest!!.localRotation.set(axisZ, trunkFrameRoll)
        declareJointIntent(Joint.CHEST, JointRotation(axisZ, trunkFrameRoll))
    }

    protected var roots: List<SkeletonNode>? = null
    protected var ankleF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var hipF: SkeletonNode? = null; protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null
    protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val armAIK = SkeletonMath.IKResult()
    protected val armPIK = SkeletonMath.IKResult()
    protected val geometryResult = PushUpPlankResult()

    protected val targetHandABuffer = Vector3()
    protected val targetHandPBuffer = Vector3()

    // Head gaze direction for the prone push-up posture (read-only, shared across frames).
    protected val pushUpHeadDirection = Vector3(-1f, 0.2f, 0f).normalize()

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createPushUpSkeleton()
        roots = nodes.roots
        ankleF = nodes.ankleF
        heelF = nodes.heelF
        toeF = nodes.toeF
        kneeF = nodes.kneeF
        hipF = nodes.hipF
        pelvis = nodes.pelvis
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

        val shinL = def.shinLength
        val thighL = def.thighLength

        // Target a small knee flexion for a visual and anatomically natural, barely-perceptible knee bend
        val targetFlexionDegrees = PushUpPlank.TARGET_KNEE_FLEXION_DEGREES
        val limbResult = SkeletonMath.solveNearStraightLimb(shinL, thighL, targetFlexionDegrees, legScratch)
        val legTargetLen = limbResult.d

        val isKneePivot = metadata.support.pivot == PivotType.KNEES

        // The rep's depth phase — a full raised-cosine cycle: 0 at the top (progress 0 and 1), 1 at the
        // bottom. The knee pivot feeds this phase to the plank solver, whose offsets are declared as
        // top/bottom-of-rep ratios, so the linear animation phase would put its bottom-of-rep geometry on
        // the animation's loop seam.
        val kneeDepth = 0.5f - 0.5f * cos(context.progress * 2f * PI.toFloat())
        val plankPhase = if (isKneePivot) kneeDepth else context.progress

        val solverGeometry = PushUpPlank.solve(
            definition = def,
            support = metadata.support,
            gripWidthMultiplier = gripWidthMultiplier,
            progress = plankPhase,
            result = geometryResult
        )

        val theta = solverGeometry.theta
        val ankleX = solverGeometry.ankleX
        val handAnchorX = solverGeometry.handAnchorX
        val ankleHeightVal = solverGeometry.ankleHeight

        if (isKneePivot) {
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // The planted flat foot is owned by the engine: the Finalizer derives heel/toe
            // from the shank + the neutral ankle articulation (automatic). The pose declares
            // the leg chain; it does not touch the foot endpoints.

            // 2. Main Plank (Side F). The knee is the pivot and the declared floor contact, so it is
            // placed at the solver's declared knee placement (`kneeX` / `kneeHeight`) measured from
            // the planted ankle. That offset *is* the declared shin pitch (`SHIN_PITCH_ANGLE`, 45°
            // above the floor): kneeF is a shin length back-and-down from the ankle, so the shin
            // stops being a horizontal mid-air segment and the knee lands on its support surface.
            kneeF!!.localPosition.set(
                solverGeometry.kneeX - solverGeometry.ankleX,
                solverGeometry.kneeHeight - solverGeometry.ankleHeight,
                0f
            )
            // The femur then rises from that planted knee to the pelvis at the solver's declared
            // `theta`, so the pelvis lands on the solver's declared `pelvisHeight`
            // (= kneeHeight + thighLength * sin(theta)) instead of inheriting the ankle height.
            kneeF!!.localRotation.set(axisZ, -theta)
            hipF!!.localPosition.set(-def.thighLength, 0f, 0f)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            // 3. Knee-pivot descent: the trunk flexes at the hip with progress, so the shoulders descend
            // toward the hands and the arm IK bends the elbows — the BPS §9 rep. The flexion is authored on
            // the hip (buildHipFlexion, UNI-10) so the pelvis stays neutral and in line with the plank (BPS
            // §5) instead of the trunk bending about the pelvis.
            val hipFlexion = kneeDepth * kneePivotDepthHipFlexion
            buildHipFlexion(hipF!!, hipFlexion)

            // 4. Perfect Symmetry (Side B). The pelvis-frame pitch is cancelled on the mirror hip first,
            // otherwise the planted mirror leg rides the trunk rotation up into the air. Its offsets are the
            // solver's declared knee→hip and ankle→knee deltas traversed down the other leg (bone-exact), so
            // the mirror knee lands on the same support surface and both feet stay floor-aligned.
            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            hipB!!.localRotation.set(axisZ, theta - hipFlexion)
            declareJointIntent(Joint.HIP_B, JointRotation(axisZ, theta - hipFlexion))
            kneeB!!.localPosition.set(
                solverGeometry.kneeX - PushUpPlank.PELVIS_ANCHOR_X,
                solverGeometry.kneeHeight - solverGeometry.pelvisHeight,
                0f
            )
            kneeB!!.localRotation.set(axisZ, 0f)
            ankleB!!.localPosition.set(
                solverGeometry.ankleX - solverGeometry.kneeX,
                solverGeometry.ankleHeight - solverGeometry.kneeHeight,
                0f
            )
        } else {
            // Feet Pivot push-up (Standard, Wide, Decline, Diamond, Military). The body is a rigid plank
            // supported on straight arms; depth comes from the ARMS (elbow flexion), not from dropping the
            // pelvis. So the plank rides at ARM height and descends with progress while the feet stay planted
            // and the (near-straight) legs slope up from the floor to the raised hips.
            val depth = 0.5f - 0.5f * cos(context.progress * 2f * PI.toFloat())
            val armLen = def.upperArmLength + def.forearmLength
            val topH = armLen * 0.92f            // shoulders near full extension at the top
            val botH = def.shinLength * 0.55f     // chest just above the floor at the bottom
            val bodyH = topH + (botH - topH) * depth   // shoulder/chest/hip height this frame

            // Foot stays planted on the floor (engine owns heel/toe).
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // Rigid (near-straight) front leg: ankle (root) -> knee (shin, length shinL) -> hip
            // (thigh, length thighL), each segment along the ankle->hip unit direction, so
            // |ankle->hip| == legTargetLen and BONE_LENGTH stays constant.
            val riseF = bodyH - ankleHeightVal
            val runF = -sqrt(max(legTargetLen * legTargetLen - riseF * riseF, 1f))
            val lenF = sqrt(runF * runF + riseF * riseF)
            val uxF = runF / lenF; val uyF = riseF / lenF
            kneeF!!.localPosition.set(uxF * def.shinLength, uyF * def.shinLength, 0f)
            hipF!!.localPosition.set(uxF * def.thighLength, uyF * def.thighLength, 0f)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            // Decline tilt: slope the plank by pitching the chest (the pelvis is left untouched so the
            // planted legs stay on the box; the IK below re-solves the hands to the floor, preserving both
            // contacts).
            if (declineTrunkPitch != 0f) {
                chest!!.localRotation.set(axisZ, declineTrunkPitch)
                declareJointIntent(Joint.CHEST, JointRotation(axisZ, declineTrunkPitch))
            }

            // Back leg mirrors the slope but is rooted at the raised hip: hip (root) -> knee (thigh)
            // -> ankle (shin), each segment along the hip->ankle unit direction down to the floor.
            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            val riseB = bodyH - ankleHeightVal
            val runB = sqrt(max(legTargetLen * legTargetLen - riseB * riseB, 1f))
            val lenB = sqrt(runB * runB + riseB * riseB)
            val uxB = runB / lenB; val uyB = -riseB / lenB
            kneeB!!.localPosition.set(uxB * def.thighLength, uyB * def.thighLength, 0f)
            ankleB!!.localPosition.set(uxB * def.shinLength, uyB * def.shinLength, 0f)
        }

        // Declare the flat plank's trunk frame (§4.1): the chest frame is POSE-owned
        // intent, so the pose must make it authoritative before the limb realization runs. Left unauthored,
        // the frame was established only by the Finalizer's PHASE-3 fallback — AFTER the Phase-1 limb
        // realization had baked the arms in the pelvis-only frame — and the fallback then re-FK'd the chest
        // subtree and dragged the realized arms with it: on a builder's first build the hands landed ~109
        // units above the floor instead of on their authored target. Frames >= 1 only looked correct because
        // the fallback's write survived in the reused node buffer and was read back as authored intent, so
        // the fallback early-returned — steady-state correctness was a property of cross-build buffer reuse,
        // not of the frame.
        declareFlatPlankTrunkFrame()

        buildGaze(neck!!, head!!, def.neckLength, pushUpHeadDirection)

        // Seat the shoulder girdle on the chest (clavicle/scapula are identity, so this places
        // shoulderA/P at chest ± shoulderWidth). Required before the FK pass so the arm IK reads
        // the correct world shoulder root (MIGRATION_RULES A6: no hand-computed rotAround).
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        // Authoring-FK: establish world frames so the arm IK bakes below read correct parent/world
        // rotations. The Finalizer re-runs FK idempotently, so this is the pose's one legitimate FK pass.
        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        // Shoulder world positions come straight from the FK-updated hierarchy (MIGRATION_RULES A6: no
        // hand-computed rotAround — the clothed clavicle/scapula are identity, so the geometry is unchanged).
        val shoulderAW = shoulderA!!.worldPosition
        val shoulderPW = shoulderP!!.worldPosition

        val finalHandAnchorX = handAnchorX + handAnchorXOffset
        val targetHandA = targetHandABuffer.set(finalHandAnchorX, 0f, -def.shoulderWidth * gripWidthMultiplier)
        val targetHandP = targetHandPBuffer.set(finalHandAnchorX, 0f, def.shoulderWidth * gripWidthMultiplier)

        // Reach-target authoring: keep the authored hand target inside the arm's reachable band so a
        // compact stance (narrow grip / shallow shoulder drop) cannot ask for a target closer than the
        // elbow's minimum-flexion reach, which would fire IK_TARGET_UNREACHABLE. Only the radius is nudged;
        // the grip direction is preserved.
        SkeletonMath.clampTargetToReach(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        // Elbow poles are authored in WORLD space (MIGRATION_RULES A8: the pose never converts frames).
        // The former `val armA`/`armP` IK-result captures were bookkeeping-only (never read) and
        // are dropped — the bake's effects run identically without them (byte-identical).
        bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK)
        bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK)

        // The palm/knuckles/fingertips and the wrist are OWNED by the engine: the Finalizer derives
        // palm/wrist/hand (automatic) from the limb plus the neutral articulation. The pose declares the
        // hand IK target and never copies wrist onto hand or hand onto wrist.

        // Declare the support planes for the engine's automatic extremity orientation, so palms lie
        // flat instead of slicing into the ground and feet plant instead of floating. Declared here (not via
        // a contact-bearing bake) because the push-up authors its plank by FK and bakes the arms without a
        // ContactConstraint.

        // Heading (exercise intent): the forward direction in root-relative space; the Finalizer transforms
        // it to world space using the pelvis rotation and projects it onto the support plane. BODY_FORWARD
        // = -X in the pelvis frame (the skeleton faces -X).
        val bodyForward = Vector3(-1f, 0f, 0f)
        setHeading(Extremity.HAND_A, bodyForward)
        setHeading(Extremity.HAND_P, bodyForward)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        return jointsBuffer
    }
}
