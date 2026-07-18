package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BasePushUpPose is the single owner of all Standard-push-up-family scaffolding.
 *
 * Redesigned from scratch for a *natural-looking* standard push-up. The exercise is a
 * prone plank that lowers (chest toward the floor) and pushes back up; the hands and
 * the balls of the feet are the four supports. Biomechanically the body is a rigid
 * plank: a straight line shoulder → hip → knee → ankle, with only the elbows
 * bending to drive the rep.
 *
 * Engine contract (per docs/POSE_AUDIT_AND_FIX_PLAYBOOK.md §2): this is a *rigid
 * kinematic plank* — its four supports are declared in `metadata.support` for the
 * renderer's support-polygon and must stay engine-**contact-less** (registering
 * engine `ContactSpec`s fires the ConstraintSolver relaxation and regresses the suite).
 *
 * Authoring model used:
 *  - `PushUpGeometrySolver` owns the sagittal plank math (pelvis height / leg pitch /
 *    hand-anchor X) as a function of `progress` — kept because it is sound.
 *  - Legs: near-straight (tiny knee flexion) via `solveNearStraightLimb`; the foot
 *    is **planted** through `buildAnkleArticulation` (plantar-flexed toe, neutral
 *    inversion) so heel/toe read as on the mat instead of floating.
 *  - Arms: IK-baked from shoulder to a floor hand target via `bakeIkLimb` (registers
 *    the §1.1 `limbTargets` carrier). The rep depth comes from the solver's
 *    `progress`-driven pelvis offset (the elbows flex as the chest drops).
 *  - Wrists: mirrored HAND→WRIST at finalize — the renderer consumes WRIST_*, this
 *    is the established plank-family convention (see BasePlankPose.finalizePlankPose).
 *  - Scapulae: slight protraction via `buildClavicularRotation` for a loaded look.
 *  - Gaze: forward-and-down, cervical spine in line with the plank.
 */
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
    protected var clavicleA: SkeletonNode? = null; protected var clavicleP: SkeletonNode? = null

    protected val armAIK = SkeletonMath.IKResult()
    protected val armPIK = SkeletonMath.IKResult()
    protected val geometryResult = PushUpSolverResult()

    protected val targetHandABuffer = Vector3()
    protected val targetHandPBuffer = Vector3()
    protected val armAPoleLocal = Vector3()
    protected val armPPoleLocal = Vector3()

    // Head gaze direction for the prone push-up posture (read-only, shared across frames).
    // Forward and slightly down, so the cervical spine stays in line with the plank
    // (no awkward neck crane) while the eyes track the floor ahead.
    protected val pushUpHeadDirection = Vector3(-1f, -0.15f, 0f).normalize()

    // Scapular protraction magnitude (glenoid reaches forward around the rib cage) — a
    // small, constant value reads as a "loaded" push-up without over-articulating.
    protected val scapularProtraction = 0.12f

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
        clavicleA = nodes.clavicleA
        clavicleP = nodes.clavicleP
        hipB = nodes.hipB
        kneeB = nodes.kneeB
        ankleB = nodes.ankleB
        heelB = nodes.heelB
        toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val shinL = def.shinLength
        val thighL = def.thighLength

        // A barely-perceptible knee flexion keeps the leg from hyper-extending and reads
        // as a living, natural plank rather than a locked rod.
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

            // 1. Root Anchoring — knee on the floor, ankle raised.
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // 2. Main Plank (Side F): thigh up from the knee, hip, then the trunk.
            kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)
            kneeF!!.localRotation.set(axisZ, -theta - shinPitch)

            hipF!!.localPosition.set(-def.thighLength, 0f, 0f)
            // The knee rotation pitches the whole downstream chain (thigh → pelvis → torso) up
            // by (theta + shinPitch). Counter-rotate at the hip so the torso is the level
            // plank the exercise actually is (the shins keep their 45° upward pitch).
            buildHipFlexion(hipF!!, theta + shinPitch)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            // 3. Perfect Symmetry (Side B)
            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            buildHipFlexion(hipB!!, 0f)

            kneeB!!.localPosition.set(def.thighLength, 0f, 0f)
            kneeB!!.localRotation.set(axisZ, shinPitch + theta)
            ankleB!!.localPosition.set(def.shinLength, 0f, 0f)
        } else {
            // Feet-Pivot push-up leg orientation (Standard, Wide, Decline, Diamond, Military).
            ankleF!!.localPosition.set(ankleX, ankleHeightVal, -def.hipWidth)

            // Precompute local knee flexion coordinates (F-leg: ankle is the parent, hip is child).
            val kX = -limbResult.x
            val kY = limbResult.y

            kneeF!!.localPosition.set(kX, kY, 0f)
            hipF!!.localPosition.set(-legTargetLen - kX, -kY, 0f)
            pelvis!!.localPosition.set(0f, 0f, def.hipWidth)
            buildTorso(pelvis!!, chest!!, def.torsoLength)

            hipB!!.localPosition.set(0f, 0f, def.hipWidth)
            // B-leg: hip is the parent, ankle is the child.
            val bXResult = SkeletonMath.solveNearStraightLimb(thighL, shinL, targetFlexionDegrees, legScratch)
            val bX = bXResult.x
            val bY = bXResult.y

            kneeB!!.localPosition.set(bX, bY, 0f)
            ankleB!!.localPosition.set(legTargetLen - bX, -bY, 0f)
        }

        // Planted feet: plantar-flex the toes slightly and keep the ankle neutral in inversion
        // so the heel/toe read as resting on the mat (Branch C ankle articulation carrier).
        buildAnkleArticulation(Extremity.FOOT_F, 0.25f, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, 0.25f, 0f, ankleB!!)

        // Scapular protraction — the glenoids reach forward around the rib cage for a
        // loaded push-up look (Branch B girdle intent via buildClavicularRotation).
        buildClavicularRotation(clavicleA!!, scapularProtraction, 0f, 0f, -1f)
        buildClavicularRotation(clavicleP!!, scapularProtraction, 0f, 0f, +1f)

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

        // Keep the authored hand target inside the arm's reachable band so a compact stance
        // cannot ask for a target closer than the elbow's minimum-flexion reach.
        SkeletonMath.clampTargetToReach(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        SkeletonMath.toLocalDirection(poleA, chest!!.worldRotation, armAPoleLocal)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        val armAPoleWorld = SkeletonMath.toWorldDirection(armAPoleLocal, elbowA!!.parent!!.worldRotation, tempPoleWorld)
        val armA = bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK)

        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        SkeletonMath.toLocalDirection(poleP, chest!!.worldRotation, armPPoleLocal)
        val armPPoleWorld = SkeletonMath.toWorldDirection(armPPoleLocal, elbowP!!.parent!!.worldRotation, tempPoleWorld)
        val armP = bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPoleWorld, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        // The renderer consumes WRIST_*; mirror the solved hand orientation onto the wrist
        // joints (established plank-family convention — see BasePlankPose.finalizePlankPose).
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    // Clavicle nodes live under the chest; surfaced as bound fields above.
}
