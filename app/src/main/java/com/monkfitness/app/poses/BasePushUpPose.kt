package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

abstract class BasePushUpPose : BasePose() {

    // Subclasses only specify their parameters / metadata + configuration
    abstract val gripWidthMultiplier: Float
    open val handAnchorXOffset: Float = 0f
    open val poleA: Vector3 = Vector3(1f, 0.5f, -1f)
    open val poleP: Vector3 = Vector3(1f, 0.5f, 1f)
    open val handDirA: Vector3 = Vector3(-1f, 0f, -0.2f).normalize()
    open val handDirP: Vector3 = Vector3(-1f, 0f, 0.2f).normalize()

    protected var roots: List<SkeletonNode>? = null
    protected var ankleF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var hipF: SkeletonNode? = null; protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null
    protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val armAIK = SkeletonMath.IKResult()
    protected val armPIK = SkeletonMath.IKResult()
    protected val geometryResult = PushUpSolverResult()

    protected val targetHandABuffer = Vector3()
    protected val targetHandPBuffer = Vector3()
    protected val armAPoleLocal = Vector3()
    protected val armPPoleLocal = Vector3()

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

    // Registers a planted-toe floor contact so the engine honours the declared support.
    // Mirrors the ContactSpec a bakeIkLimb(contact=...) call would add for a 1-bone foot
    // (ANKLE -> TOE). The toe target is the toe node's current world position projected to the
    // floor plane (y = 0); the ConstraintSolver then pins it there and re-bakes the leg.
    protected fun registerToeContact(toe: SkeletonNode, ankle: SkeletonNode, footLength: Float, constraint: IKConstraint) {
        val chain = ConstraintSolver.chainForEnd(toe.joint) ?: return
        val tw = toe.worldPosition
        jointsBuffer.contacts.add(
            ContactSpec(
                endJoint = toe.joint,
                rootJoint = chain.rootJoint,
                parentRotationJoint = chain.parentRotationJoint,
                middleJoint = chain.middleJoint,
                targetWorld = Vector3(tw.x, 0f, tw.z),
                pole = Vector3(0f, -1f, 0f),
                length1 = footLength,
                length2 = 0f,
                constraint = constraint,
                straight = false,
                contact = ContactConstraint.ground(0f)
            )
        )
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val shinL = def.shinLength
        val thighL = def.thighLength

        // Target a small knee flexion for a visual and anatomically natural, barely-perceptible knee bend
        val targetFlexionDegrees = PushUpGeometrySolver.TARGET_KNEE_FLEXION_DEGREES
        val limbResult = SkeletonMath.solveNearStraightLimb(shinL, thighL, targetFlexionDegrees, legScratch)
        val legTargetLen = limbResult.d

        val solverGeometry = PushUpGeometrySolver.solve(
            definition = def,
            support = metadata.support,
            gripWidthMultiplier = gripWidthMultiplier,
            progress = context.progress,
            result = geometryResult
        )

        val theta = solverGeometry.theta
        val ankleX = solverGeometry.ankleX
        val handAnchorX = solverGeometry.handAnchorX
        val ankleHeightVal = solverGeometry.ankleHeight

        val isKneePivot = metadata.support.pivot == PivotType.KNEES

        if (isKneePivot) {
            val shinPitch = PushUpGeometrySolver.SHIN_PITCH_ANGLE // Shins point 45 degrees up

            // 1. Root Anchoring
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // The engine derives heel/toe from the shank + the neutral ankle articulation. The
            // planted flat foot is intentionally NOT hand-authored here; any visual shortfall is
            // an engine limitation left exposed.

            // 2. Main Plank (Side F)
            kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
            kneeF!!.localRotation.set(axisZ, -theta - shinPitch)

            hipF!!.localPosition.set(-def.thighLength, 0f, 0f)
            // R2 (reach target authoring): the knee rotation above pitches the whole downstream
            // chain (thigh → pelvis → torso) up by (theta + shinPitch). Left uncorrected the torso
            // stands nearly vertical (shoulders ~300 units up) and the hand IK target — authored on
            // the floor — becomes physically unreachable (dMag ~318 >> arm reach). Counter-rotate at
            // the hip so the torso returns to the horizontal plank the exercise actually is; the
            // shin keeps its 45° upward pitch (knee-on-ground, ankle raised) while the trunk is level.
            // B4a — carrier-backed hip ROM via the documented buildHipFlexion helper (records the
            // HIP_F joint intent; mixed mode, byte-identical to the bare localRotation.set).
            buildHipFlexion(hipF!!, theta + shinPitch)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            // 3. Perfect Symmetry (Side B)
            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            // Phase 6 (W15/G7): symmetry reset via the documented helper (no-op flexion).
            buildHipFlexion(hipB!!, 0f)

            // Shin B must counter-rotate the -theta to match the 45 degree upward pitch
            kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
            kneeB!!.localRotation.set(axisZ, shinPitch + theta)
            ankleB!!.localPosition.set(def.shinLength, 0f, 0f)
        } else {
            // Feet Pivot push-up leg orientation (Standard, Wide, Decline, Diamond, Military)
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // The engine derives heel/toe from the shank + the neutral ankle articulation. The
            // planted flat foot is intentionally NOT hand-authored here; any visual shortfall is
            // an engine limitation left exposed.

            // Precompute local knee flexion coordinates (F-leg: ankle is the parent, hip is child)
            val kX = -limbResult.x
            val kY = limbResult.y

            kneeF!!.localPosition.set(kX, kY, 0f)
            hipF!!.localPosition.set(-legTargetLen - kX, -kY, 0f)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            // B-leg: hip is the parent, ankle is the child
            val bXResult = SkeletonMath.solveNearStraightLimb(thighL, shinL, targetFlexionDegrees, legScratch)
            val bX = bXResult.x
            val bY = bXResult.y

            kneeB!!.localPosition.set(bX, bY, 0f)
            ankleB!!.localPosition.set(legTargetLen - bX, -bY, 0f)
        }

        buildGaze(neck!!, head!!, def.neckLength, pushUpHeadDirection)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        val chestW = chest!!.worldPosition
        val shoulderAW = SkeletonMath.rotAround(tempV1.set(0f, 0f, -def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV2).add(chestW)
        val shoulderPW = SkeletonMath.rotAround(tempV1.set(0f, 0f, def.shoulderWidth), axisZ, chest!!.worldRotation.angle, tempV3).add(chestW)

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


        SkeletonMath.toLocalDirection(poleA, chest!!.worldRotation, armAPoleLocal)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        val armAPoleWorld = SkeletonMath.toWorldDirection(armAPoleLocal, elbowA!!.parent!!.worldRotation, tempPoleWorld)
        // Floor contact: hands are planted on the ground, so the engine owns keeping them there
        // (registers a ContactSpec so the ConstraintSolver runs and honours the declared contact).
        val groundContact = ContactConstraint.ground(0f)
        val armA = bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK, contact = groundContact)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        SkeletonMath.toLocalDirection(poleP, chest!!.worldRotation, armPPoleLocal)
        val armPPoleWorld = SkeletonMath.toWorldDirection(armPPoleLocal, elbowP!!.parent!!.worldRotation, tempPoleWorld)
        val armP = bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK, contact = groundContact)

        // Flat-hand push-up: the wrist extends so the palm (not the knuckles/fist) bears the load.
        // Authored through the Branch C wrist-articulation carrier; the engine derives palm/knuckles/
        // fingertips from the forearm + this articulation. No manual WRIST=HAND copy.
        val wristExtension = 0.35f
        buildWristArticulation(Extremity.HAND_A, wristExtension, 0f, handA!!)
        buildWristArticulation(Extremity.HAND_P, wristExtension, 0f, handP!!)

        // Toes are planted on the ground: register foot ContactSpecs so the solver pins all four
        // supports (hands via bakeIkLimb above, toes here). Without this hasContacts() stays false
        // and the declared toe contacts are never honoured by the engine.
        registerToeContact(toeF!!, ankleF!!, def.footLength, def.legIKConstraint)
        registerToeContact(toeB!!, ankleB!!, def.footLength, def.legIKConstraint)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        return jointsBuffer
    }
}
