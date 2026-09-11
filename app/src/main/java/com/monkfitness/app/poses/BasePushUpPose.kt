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
    // trunk pitches over the planted knee so the shoulders descend and the elbows press. The
    // previously authored depth pitch amplitude, unchanged — but applied at the hip on a plank
    // whose pelvis the solver places (`PushUpPlank.solve`, KNEES), instead of bending the trunk
    // about the pelvis. Calibrated against the arm: the deepest trunk pitch must still leave the
    // authored hand target inside `SkeletonMath`'s reachable annulus.
    private val kneePivotDepthHipFlexion: Float = 0.42f

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
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val shinL = def.shinLength
        val thighL = def.thighLength

        // Target a small knee flexion for a visual and anatomically natural, barely-perceptible knee bend
        val targetFlexionDegrees = PushUpPlank.TARGET_KNEE_FLEXION_DEGREES
        val limbResult = SkeletonMath.solveNearStraightLimb(shinL, thighL, targetFlexionDegrees, legScratch)
        val legTargetLen = limbResult.d

        val isKneePivot = metadata.support.pivot == PivotType.KNEES

        // The rep's depth phase — a full raised-cosine cycle: 0 at the top of the rep (progress 0
        // and 1), 1 at the bottom (progress 0.5). It is the pose's authored *timing* (motionCurve
        // EASE_IN_OUT, loopMode LOOP) and is the phase the knee pivot also feeds the plank solver
        // (below) — the solver's offsets are declared as top-of-rep / bottom-of-rep ratios, so the
        // linear animation phase would put its bottom-of-rep geometry on the animation's loop seam.
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
            // 1. Root Anchoring — the planted ankle, at the solver's declared ankle placement.
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // The planted flat foot is owned by the engine: the Finalizer derives heel/toe
            // from the shank + the neutral ankle articulation (W1 automatic). The pose declares
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

            // 3. Knee-pivot descent: the knee is the floor pivot and the trunk flexes at the hip with
            // progress, so the shoulders descend toward the hands and the arm IK bends the elbows —
            // the BPS §9 rep ("the trunk rotates about the knee so the shoulder drops toward the
            // hands"), i.e. a real press rather than a static plank. The flexion is authored on the
            // hip (buildHipFlexion, the documented sagittal hip path, UNI-10) so the pelvis itself
            // stays neutral and in line with the plank (BPS §5) instead of the trunk bending about
            // the pelvis. Amplitude is the previously authored depth pitch, unchanged.
            val hipFlexion = kneeDepth * kneePivotDepthHipFlexion
            buildHipFlexion(hipF!!, hipFlexion)

            // 4. Perfect Symmetry (Side B). The mirror leg is planted on the same surface, so its
            // chain is laid out in the solver's own frame: the pelvis-frame pitch is cancelled on the
            // hip first, otherwise the planted mirror leg would ride the trunk rotation up into the
            // air. Its offsets are the solver's declared knee→hip and ankle→knee deltas traversed down
            // the other leg (bone-exact: thighLength and shinLength), which lands the mirror knee on
            // the same support surface as the front knee and keeps both feet floor-aligned.
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
            // Feet Pivot push-up (Standard, Wide, Decline, Diamond, Military).
            // BPS: the body is a rigid plank supported on straight arms; depth is created by the
            // ARMS (elbow flexion), not by dropping the pelvis. So the plank (hips -> chest ->
            // head) rides at ARM height and descends with progress, while the feet stay planted
            // on the floor and the (near-straight) legs slope up from the floor to the raised hips.
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

            // M14 — Decline tilt: slope the head-to-heels plank downward for feet-elevated
            // variants by pitching the chest about the spine's mediolateral Z axis. The pelvis is
            // left untouched so the planted legs stay on the box; the IK below re-solves the hands
            // to the floor, so both contacts are preserved. 0 for every flat-planar member.
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
        buildGaze(neck!!, head!!, def.neckLength, pushUpHeadDirection)

        // Seat the shoulder girdle on the chest (clavicle/scapula are identity, so this places
        // shoulderA/P at chest ± shoulderWidth). Required before the FK pass so the arm IK reads
        // the correct world shoulder root (MIGRATION_RULES A6: no hand-computed rotAround).
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        // Authoring-FK: establish world frames so the arm IK bakes below read
        // correct parent/world rotations. The Finalizer re-runs FK idempotently
        // (isTransformsUpdated is not set here), so this is the pose's one
        // legitimate forward-kinematics pass — it is NOT a duplicated solver pass.
        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        // Shoulder world positions are read straight from the FK-updated hierarchy
        // (MIGRATION_RULES A6: no hand-computed rotAround — the clothed clavicle/scapula
        // are identity, so shoulderA.worldPosition equals the old rotAround result, geometry
        // unchanged). The arm IK roots now sit where the engine owns them.
        val shoulderAW = shoulderA!!.worldPosition
        val shoulderPW = shoulderP!!.worldPosition

        val finalHandAnchorX = handAnchorX + handAnchorXOffset
        val targetHandA = targetHandABuffer.set(finalHandAnchorX, 0f, -def.shoulderWidth * gripWidthMultiplier)
        val targetHandP = targetHandPBuffer.set(finalHandAnchorX, 0f, def.shoulderWidth * gripWidthMultiplier)

        // R2 (reach target authoring): keep the authored hand target inside the arm's reachable
        // band so a compact stance (narrow grip / shallow shoulder drop) cannot ask for a target
        // closer than the elbow's minimum-flexion reach — which would fire IK_TARGET_UNREACHABLE.
        // A target already in band (Wide/Decline) is unchanged; only the radius is nudged, the
        // grip direction is preserved.
        SkeletonMath.clampTargetToReach(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        // Elbow poles are authored in WORLD space (MIGRATION_RULES A8: the pose never converts
        // frames — `bakeIkLimb` consumes the pole as world, the Finalizer owns any local→world
        // conversion). P12 §12.5: the former `val armA`/`armP` IK-result captures were
        // bookkeeping-only (never read) and are dropped — the bake's registration + (gated)
        // realization effects run identically without them (WP-C differential: byte-identical).
        bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK)
        bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK)

        // The palm/knuckles/fingertips and the wrist are OWNED by the engine:
        // the Finalizer derives heel/toe (foot) and palm/wrist/hand (W1 automatic)
        // from the limb + the neutral articulation. The pose declares the hand IK
        // target and lets the engine resolve the extremity — it never copies wrist
        // onto hand or hand onto wrist.

        // W1b — declare the support planes for the engine's automatic extremity orientation.
        // The hands rest on the floor (feet-pivot push-ups) or on the same plane the plank rides;
        // the planted feet/knee rest on the floor. The Finalizer flattens palm and foot geometry
        // onto these planes so palms lie flat instead of slicing into the ground and feet plant
        // instead of floating. Declared here (not via a contact-bearing bake) because the push-up
        // authors its plank by FK and bakes the arms without a ContactConstraint.

        // Heading (exercise intent): for planted push-up hands, declare the forward
        // direction in root-relative space. The Finalizer transforms this to world space
        // using the pelvis rotation and projects it onto the support plane (floor).
        // BODY_FORWARD = -X in pelvis frame (skeleton faces -X).
        val bodyForward = Vector3(-1f, 0f, 0f)
        setHeading(Extremity.HAND_A, bodyForward)
        setHeading(Extremity.HAND_P, bodyForward)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        return jointsBuffer
    }
}
