package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseHipFlexorPose is the single owner of the shared Hip Flexor scaffolding.
 *
 * Both family members (CouchStretch, HalfKneelingStretch) previously duplicated the
 * manual SkeletonNode construction, manual solveIK + rotAround IK, the camera literal,
 * the hand geometry and the foot-ratio constants. This base consolidates all of that and
 * delegates to the engine: SkeletonFactory for the hierarchy, BasePose.bakeIkLimb() for the
 * front-leg and arm IK (replacing manual solveIK + rotAround), buildHead/buildPelvis for the
 * upper body, and FootDefinition for the heel/toe ratios.
 *
 * What intentionally remains local (Bird-Dog-style pose biomechanics, not engine knowledge):
 *  - The rigid Pythagorean pelvis solver (pelvis slides along a fixed back-knee constraint).
 *  - The back-leg fixed kinematics (knee is pinned; shin is vertical or flat on the ground).
 *  - The per-variant back-foot world direction (up the wall vs. flat backward).
 */
abstract class BaseHipFlexorPose : BasePose() {

    // Shared camera (duplicated literal removed). Default pitch raised ~10% (0.22 -> 0.242)
    // so the view tilts down slightly and the upper body stays comfortably in frame
    // (yaw/zoom unchanged, no camera redesign).
    protected val hipFlexorCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.242f,
        defaultZoom = 1.3f
    )
    protected val hipFlexorGround = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

    // Counter-rotation for bakeIkLimb / rotAround, set by each variant per frame.
    protected var leanAngle = 0f

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    // Reusable scratch to avoid any per-frame allocations.
    protected val thighVecB = Vector3()
    protected val shinVecB = Vector3()
    protected val targetAnkleF = Vector3()
    protected val handTarget = Vector3()
    protected val worldFootDir = Vector3()
    protected val localFoot = Vector3()

    // Constant IK poles (allocated once).
    protected val frontLegPole = Vector3(1f, 0f, -0.5f)
    protected val armAPole = Vector3(1f, 1f, -2f)
    protected val armPPole = Vector3(1f, 1f, 2f)
    protected val backFootUpDir = Vector3(0f, 1f, 0f)   // Couch: back shin up the wall
    protected val backFootBackDir = Vector3(-1f, 0f, 0f) // Half-kneeling: back foot flat backward
    protected val uprightHeadDir = Vector3(0f, 1f, 0f)

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
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

    /**
     * Sets the constant upper-body local offsets after the pelvis has been anchored.
     * Uses engine helpers buildHead / buildPelvis to remove duplicated head/pelvis construction.
     */
    protected fun setUpperBodyLocal(def: SkeletonDefinition) {
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildHead(neck!!, head!!, def.neckLength, uprightHeadDir)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)
    }

    /** Front leg IK via engine bakeIkLimb; returns the IK result (knee joint world pos) for the arms. */
    protected fun solveFrontLeg(def: SkeletonDefinition): SkeletonMath.IKResult {
        return bakeIkLimb(
            hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength,
            frontLegPole, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer
        )
    }

    /** Both arms rest on the front knee — identical choreography for both variants. */
    protected fun solveArmsOnKnee(kneeJointWorld: Vector3, def: SkeletonDefinition) {
        handTarget.set(kneeJointWorld.x - 10f, kneeJointWorld.y + 15f, 0f)

        handTarget.z = -def.shoulderWidth * 0.8f
        bakeIkLimb(shoulderA!!.worldPosition, handTarget, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)

        handTarget.z = def.shoulderWidth * 0.8f
        bakeIkLimb(shoulderP!!.worldPosition, handTarget, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // W1: engine now derives hand orientation (removed wrist tilt counter-rotation + 6/6/10 offsets).
    }

    /** Front foot flat on the floor. */
    protected fun applyFrontFoot(def: SkeletonDefinition) {
        // W1: engine now derives heel/toe + foot orientation from the shank + neutral ankle.
    }

    /** Back foot orientation, parameterized by its world direction (up the wall or flat backward). */
    protected fun applyBackFoot(worldDir: Vector3, def: SkeletonDefinition) {
        // Explicit override: the back foot points along an intentional direction (toes up the wall
        // for the couch stretch, flat backward for the half-kneeling stretch) that the engine's
        // perpendicular-to-shank derivation cannot express; opt the feet out of auto-derivation.
        worldFootDir.set(worldDir)
        SkeletonMath.rotAround(worldFootDir, axisZ, leanAngle, localFoot)
        heelB!!.localPosition.set(localFoot).multiply(-def.foot.footLength * def.foot.heelRatio)
        toeB!!.localPosition.set(localFoot).multiply(def.foot.footLength * def.foot.toeRatio)
        ankleB!!.localRotation.set(axisZ, leanAngle)
    }

    protected fun finalizeHipFlexorPose(): SkeletonPose {
        overrideExtremityOrientation(jointsBuffer, Extremity.FOOT_B)
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
