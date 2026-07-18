package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BasePushUpPose — shared engine for the prone push-up family
 * (Standard / Wide / Military / Diamond / Decline).
 *
 * Rewritten from scratch for the most natural-looking result. A push-up is a
 * prone plank: a straight shoulder→hip→knee→ankle line, hands and the balls
 * of the feet as the four supports, with only the elbows bending to drive the rep.
 *
 * Authoring model (per docs/POSE_AUDIT_AND_FIX_PLAYBOOK.md §2 — this is a
 * *rigid kinematic plank*, so it stays engine-**contact-less**; registering engine
 * ContactSpecs would fire the ConstraintSolver relaxation and regress the suite):
 *  - PushUpGeometrySolver owns the sagittal plank math (pelvis height / leg pitch /
 *    hand-anchor X) as a function of progress — kept because it is sound.
 *  - Legs: near-straight (tiny knee flexion) via solveNearStraightLimb; the foot
 *    is PLANTED through buildAnkleArticulation (plantar-flexed toe, neutral
 *    inversion) so heel/toe read as on the mat instead of floating.
 *  - Arms: IK-baked shoulder→floor-hand via bakeIkLimb (registers the §1.1
 *    limbTargets carrier). Rep depth comes from the solver's progress-driven pelvis
 *    offset, so the elbows flex as the chest drops toward the floor.
 *  - Scapulae: slight protraction via buildClavicularRotation for a loaded look.
 *  - Wrists: mirrored HAND→WRIST at finalize — the renderer consumes WRIST_*,
 *    the established plank-family convention (see BasePlankPose.finalizePlankPose).
 *  - Gaze: forward-and-down, cervical spine in line with the plank.
 *
 * IkStage byte-identity: the arm IK roots are read from the ACTUAL shoulder
 * node world positions (after scapular protraction + an FK update), exactly as the
 * engine-owned IkStage does — so the on/off stage contract stays at maxDev 0.0.
 */
abstract class BasePushUpPose : BasePose() {

    // Subclasses specify only their parameters + metadata.
    abstract val gripWidthMultiplier: Float
    open val handAnchorXOffset: Float = 0f
    // IK pole (elbow-bend plane) per side; overridable for stance variety.
    open val poleA: Vector3 = Vector3(1f, 0.5f, -1f)
    open val poleP: Vector3 = Vector3(1f, 0.5f, 1f)

    protected var roots: List<SkeletonNode>? = null
    protected var ankleF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var hipF: SkeletonNode? = null; protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null
    protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null
    protected var clavicleA: SkeletonNode? = null; protected var clavicleP: SkeletonNode? = null

    protected val armAIK = SkeletonMath.IKResult()
    protected val armPIK = SkeletonMath.IKResult()
    protected val geometryResult = PushUpSolverResult()

    protected val targetHandABuffer = Vector3()
    protected val targetHandPBuffer = Vector3()
    protected val armAPoleLocal = Vector3()
    protected val armPPoleLocal = Vector3()

    // Forward-down gaze so the cervical spine stays in line with the plank (no neck crane).
    protected val pushUpHeadDirection = Vector3(-1f, -0.15f, 0f).normalize()

    // Scapular protraction magnitude — glenoids reach forward around the rib cage for a
    // loaded look without over-articulating.
    protected val scapularProtraction = 0.12f

    // Plantar flexion of the planted toe so the foot reads as resting on the mat.
    protected val footPlantarFlexion = 0.25f

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
        clavicleA = nodes.clavicleA
        clavicleP = nodes.clavicleP
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // Shape-driven root → CUSTOM (solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val shinL = def.shinLength
        val thighL = def.thighLength

        // Tiny knee flexion keeps the leg from hyper-extending and reads as a living,
        // natural plank rather than a locked rod.
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
            val shinPitch = PushUpGeometrySolver.SHIN_PITCH_ANGLE // Shins point 45° up

            // 1. Root anchoring — knee on the floor, ankle raised.
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // 2. Main plank (side F): thigh up from the knee, hip, then the trunk.
            kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
            kneeF!!.localRotation.set(axisZ, -theta - shinPitch)

            hipF!!.localPosition.set(-def.thighLength, 0f, 0f)
            // The knee rotation pitches the whole downstream chain up by (theta + shinPitch);
            // counter-rotate at the hip so the torso returns to the horizontal plank.
            buildHipFlexion(hipF!!, theta + shinPitch)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            // 3. Symmetry (side B)
            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            buildHipFlexion(hipB!!, 0f)

            kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
            kneeB!!.localRotation.set(axisZ, shinPitch + theta)
            ankleB!!.localPosition.set(def.shinLength, 0f, 0f)
        } else {
            // Feet-pivot plank (Standard / Wide / Military / Diamond / Decline).
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // Near-straight leg: ankle is the parent, hip is the child.
            val kX = -limbResult.x
            val kY = limbResult.y

            kneeF!!.localPosition.set(kX, kY, 0f)
            hipF!!.localPosition.set(-legTargetLen - kX, -kY, 0f)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            // Side B: hip is the parent, ankle is the child.
            val bXResult = SkeletonMath.solveNearStraightLimb(thighL, shinL, targetFlexionDegrees, legScratch)
            val bX = bXResult.x
            val bY = bXResult.y

            kneeB!!.localPosition.set(bX, bY, 0f)
            ankleB!!.localPosition.set(legTargetLen - bX, -bY, 0f)
        }

        // Plant the feet: plantar-flex the toes slightly, neutral inversion, so the
        // heel/toe read as resting on the mat (Branch C ankle articulation carrier).
        buildAnkleArticulation(Extremity.FOOT_F, footPlantarFlexion, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, footPlantarFlexion, 0f, ankleB!!)

        // Scapular protraction — glenoids reach forward around the rib cage for a
        // loaded push-up look (Branch B girdle intent).
        buildClavicularRotation(clavicleA!!, scapularProtraction, 0f, 0f, -1f)
        buildClavicularRotation(clavicleP!!, scapularProtraction, 0f, 0f, +1f)

        buildGaze(neck!!, head!!, def.neckLength, pushUpHeadDirection)

        val rSize = roots!!.size
        for (i in 0 until rSize) {
            roots!![i].updateWorldTransforms(zeroVector, identityRotation)
        }

        // Arm IK roots are read from the ACTUAL shoulder nodes (after scapular
        // protraction moved them), matching the engine-owned IkStage's source — so the
        // on/off byte-identity contract holds at maxDev 0.0.
        val shoulderAW = shoulderA!!.worldPosition
        val shoulderPW = shoulderP!!.worldPosition

        val finalHandAnchorX = handAnchorX + handAnchorXOffset
        val targetHandA = targetHandABuffer.set(finalHandAnchorX, 0f, -def.shoulderWidth * gripWidthMultiplier)
        val targetHandP = targetHandPBuffer.set(finalHandAnchorX, 0f, def.shoulderWidth * gripWidthMultiplier)

        // Keep the hand target inside the arm's reachable band so a compact stance
        // cannot ask for an unreachable target (fires IK_TARGET_UNREACHABLE).
        SkeletonMath.clampTargetToReach(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        SkeletonMath.toLocalDirection(poleA, chest!!.worldRotation, armAPoleLocal)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        val armAPoleWorld = SkeletonMath.toWorldDirection(armAPoleLocal, elbowA!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        SkeletonMath.toLocalDirection(poleP, chest!!.worldRotation, armPPoleLocal)
        val armPPoleWorld = SkeletonMath.toWorldDirection(armPPoleLocal, elbowP!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        // Renderer consumes WRIST_*; mirror the solved hand orientation onto the wrist
        // joints (established plank-family convention).
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
