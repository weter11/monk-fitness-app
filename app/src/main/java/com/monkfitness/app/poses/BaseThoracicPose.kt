package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseThoracicPose owns the Thoracic Mobility scaffolding, driving the motion from the rib cage itself:
 * the chest node is rotated about the spine's long axis (rotation drills) or the lateral axis
 * (extension), so the WHOLE upper chain — rib cage, neck, head, both shoulders — rotates as one
 * coherent segment. Earlier implementations rotated the shoulder offsets and left the cage static.
 *
 * Owned here (engine knowledge): the hierarchy, the head/pelvis/shoulder construction, leg IK through
 * [BasePose.bakeIkLimb] (the pelvis rotation is always about Z, so its counter-rotation is exact),
 * [bakeThoracicArm], and the shared camera/ground. Local to each variant: the root anchoring (tabletop,
 * tall-kneel, runner's lunge), the thoracic drive and the reach choreography. No single-family
 * geometry solver is introduced.
 */
abstract class BaseThoracicPose : BasePose() {

    /** Shared camera construction. Subclasses choose pitch/zoom for framing only. */
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
     * Transforms a point expressed in the chest's LOCAL frame into world space, using the exact chest
     * world rotation — so a reaching-hand target rotates WITH the thorax instead of being animated
     * independently.
     */
    protected fun chestLocalToWorld(local: Vector3, out: Vector3) {
        SkeletonMath.rotAround(local, chest!!.worldRotation.axis, chest!!.worldRotation.angle, tempV1)
        out.set(tempV1).add(chest!!.worldPosition)
    }

    /**
     * Bakes an arm whose root (shoulder) is a child of the rotating chest, through the registered
     * package-level authoring bake: the former direct `solveIK` + manual `rotAround(-angle)`
     * was a bypass with no `limbTargets`/stamp registration. The conversion is semantics-preserving —
     * the shoulder's immediate parent (SCAPULA/CLAVICLE) carries identity rotations in this family, so
     * the bake's parent frame IS the chest world rotation the manual path inverted, and
     * `toLocalDirection` == the exact single-axis `rotAround(-angle)`. The pole stays authored in the
     * chest's LOCAL frame and is transformed to world before the bake (constant local pole ⇒ constant
     * local elbow direction under thorax twist — the flip/jerk fix this helper exists for). The returned
     * result is consumed ONLY as the reachability diagnostic the subclasses log.
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
        // Reach-band authoring: the composed target is projected BEFORE the bake, so a variant
        // whose own root / thorax composition puts the hand outside its chain's annulus can correct the
        // declaration without re-scoping its siblings. Default: the hand is declared verbatim.
        projectArmTargetToReach(def, rootWorld, targetWorld)
        val poleWorld = SkeletonMath.toWorldDirection(poleLocal, chest!!.worldRotation, tempPoleWorld)
        return bakeIkLimb(
            rootWorld, targetWorld, def.upperArmLength, def.forearmLength,
            poleWorld, def.armIKConstraint, chest!!.worldRotation,
            elbowNode, handNode, buffer, jointsBuffer
        )
    }

    /**
     * Reach-band authoring hook for the family's arm choreography.
     *
     * Each variant composes its arm targets in the frame the pose owns (the chest's rotating frame for the
     * reaches, the floor for a planted pillar) and hands them to [bakeThoracicArm]. Whether that point lies
     * inside the chain's annulus `[40.1344, 143.0800]` depends on the VARIANT's root, lean and twist: two
     * variants were measured past the band's ends (see their overrides). The default is the family's
     * previous behaviour, so a variant outside it (`DynamicWorldsGreatestStretchPose`, `21.6410` u,
     * measured and reported, not changed) keeps its authoring byte-identically.
     */
    protected open fun projectArmTargetToReach(
        def: SkeletonDefinition,
        shoulderWorld: Vector3,
        target: Vector3
    ) {
    }

    /** The engine now derives hand orientation; the open-hand offsets and tilt counter-rotation are removed. */
    protected fun applyThoracicHands() {
    }

    protected fun finalizeThoracicPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        // Return an independent snapshot, not the reused internal buffer: a caller that retains one build's
        // result must not see it mutated by a later build. A shared buffer would alias two samples and make
        // the thoracic twist appear to leave the shoulder chain static.
        val out = SkeletonPose()
        out.copyFrom(jointsBuffer)
        return out
    }

    companion object {
        /**
         * The projection margin — a hundredth of a percent of the chain's span.
         *
         * The target is placed just INSIDE its annulus, not on the boundary: the carrier stores coordinates
         * at ~`3e-5` resolution at these radii, so a boundary-exact target re-fires a float-noise relocation
         * (stamp `8e-6`), while this margin makes "inside the band" hold by construction (stamp exactly `0`).
         * The published cost is bounded by the margin (`0.004` u at the arm's `minReach`). The canonical `0.02`
         * is NOT used: here it would move the hands `2.8` u further in than the engine already publishes them.
         */
        const val REACH_MARGIN = 1e-4f
    }
}
