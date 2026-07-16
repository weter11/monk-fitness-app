package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseThoracicPose is the single owner of all Thoracic Mobility scaffolding.
 *
 * Unlike the previous (incorrect) thoracic implementations — which rotated the
 * shoulder offsets while leaving the rib cage, neck and head essentially static —
 * this base drives the motion from the rib cage itself. The chest node is rotated
 * about the spine's long axis (rotation drills) or the lateral axis (extension),
 * so the WHOLE upper chain (rib cage, neck, head, both shoulders) rotates as one
 * coherent segment, exactly as a real thoracic rotation/extension does.
 *
 * What is owned here (engine responsibilities, per the architecture mandate):
 *  - Skeleton hierarchy via SkeletonFactory.createStandardSkeleton()
 *  - buildHead / buildPelvis / buildShoulders for the upper body
 *  - Leg IK via BasePose.bakeIkLimb() (pelvis rotation is always about Z, so the
 *    engine helper's Z counter-rotation is exact for the legs)
 *  - Arm IK via bakeThoracicArm(): the arm root (shoulder) lives under the rotating
 *    chest, so after solving IK in world space the elbow/hand local offsets are
 *    expressed in the chest's local frame using the exact inverse of the chest world
 *    rotation (a single axis-angle -> rotAround by -angle is exact). This keeps the
 *    arms reaching to world targets (planted hand, or a thorax-following reach) while
 *    the rib cage drives the movement.
 *  - Constant open-hand extremity offsets (palm/knuckles 6, fingertips 10) — the family
 *    convention shared with Push-Up / Squat / Bird-Dog / Hip-Flexor.
 *  - Foot heel/toe ratios owned by FootDefinition (no 0.29/0.71 literals).
 *  - Shared thoracic camera (single source of truth for yaw 1.19) and ground.
 *
 * What intentionally stays local (thoracic biomechanics, NOT engine knowledge):
 *  - The per-exercise root anchoring (tabletop, tall-kneel, runner's lunge)
 *  - The per-exercise thoracic drive (twist about the spine vs. extension about the
 *    lateral axis) and the reach choreography.
 *  - No ThoracicGeometrySolver is introduced (would be a single-family abstraction).
 */
abstract class BaseThoracicPose : BasePose() {

    /** Shared camera construction. Subclasses choose pitch/zoom for framing only (Phase 4). */
    protected fun thoracicCamera(pitch: Float, zoom: Float) = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = pitch,
        defaultZoom = zoom
    )
    protected val thoracicGround = EnvironmentDefinition(
        ground = GroundDefinition(visible = true, level = 0f)
    )

    /** Pelvis Z-rotation (spine pitch). Legs are children of the pelvis, whose rotation is
     *  always about Z in this family, so bakeIkLimb's Z counter-rotation is exact for them. */
    protected var spinePitch = 0f

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var lumbar: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    protected val targetA = Vector3(); protected val targetP = Vector3()
    protected val targetF = Vector3(); protected val targetB = Vector3()
    protected val poleA = Vector3(); protected val poleP = Vector3()
    protected val poleF = Vector3(); protected val poleB = Vector3()
    protected val reachLocal = Vector3(); protected val reachWorld = Vector3()

    protected fun ensureHierarchy(def: SkeletonDefinition) {
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

    /**
     * Transforms a point expressed in the chest's LOCAL frame into world space, using the
     * exact chest world rotation. Used so a reaching-hand target rotates WITH the thorax
     * (the arm follows the spine, instead of being animated independently).
     */
    protected fun chestLocalToWorld(local: Vector3, out: Vector3) {
        SkeletonMath.rotAround(local, chest!!.worldRotation.axis, chest!!.worldRotation.angle, tempV1)
        out.set(tempV1).add(chest!!.worldPosition)
    }

    /**
     * Bakes an arm whose root (shoulder) is a child of the rotating chest.
     * IK is solved in world space; the elbow/hand local offsets are then expressed in the
     * chest's local frame via the exact inverse of the chest world rotation (single
     * axis-angle, so rotAround by -angle is exact). This keeps the rib cage the driver of
     * the motion while the arms still reach to world targets.
     *
     * The pole is authored in the chest's LOCAL frame and transformed into world space using
     * the chest's current world rotation (see SkeletonMath.toWorldDirection). Because both the
     * shoulder and the reaching target live in the chest frame, a constant local pole yields a
     * constant local elbow direction as the thorax twists — eliminating pole-vector flips and
     * the jerky arm motion those cause.
     */
    protected fun bakeThoracicArm(
        rootWorld: Vector3,
        targetWorld: Vector3,
        def: SkeletonDefinition,
        poleLocal: Vector3,
        elbowNode: SkeletonNode,
        handNode: SkeletonNode,
        buffer: SkeletonMath.IKResult
    ): SkeletonMath.IKResult {
        val ik = solveArmIK(rootWorld, targetWorld, def.upperArmLength, def.forearmLength, poleLocal, chest!!.worldRotation, def.armIKConstraint, buffer)
        tempV1.set(ik.joint).subtract(rootWorld)
        SkeletonMath.rotAround(tempV1, chest!!.worldRotation.axis, -chest!!.worldRotation.angle, elbowNode.localPosition)
        tempV1.set(ik.end).subtract(ik.joint)
        SkeletonMath.rotAround(tempV1, chest!!.worldRotation.axis, -chest!!.worldRotation.angle, handNode.localPosition)
        return ik
    }

    /** W1: the engine now derives hand orientation; the open-hand offsets and tilt counter-rotation are removed. */
    protected fun applyThoracicHands() {
    }

    protected fun finalizeThoracicPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
