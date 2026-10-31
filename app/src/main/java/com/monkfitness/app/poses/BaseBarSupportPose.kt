package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseBarSupportPose owns the BAR-SUPPORT family's shared machinery.
 *
 * The family is the movements in which the hands are FIXED on a bar and the body moves, with the
 * body's placement DERIVED from an authored shoulder→bar reach. Shared here because it is engine
 * knowledge, not exercise knowledge: the skeleton hierarchy, the fixed hand world targets, the
 * flat-in-the-bar's-plane grip ([authorFlatBarGrip]), the scapular drive on the one canonical
 * channel, IK baking via [BasePose.bakeIkLimb], and finalization. The bar's layout, the reach
 * schedule and the second contact stay in the concrete member.
 */
abstract class BaseBarSupportPose : BasePose() {

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var scapulaA: SkeletonNode? = null; protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var scapulaP: SkeletonNode? = null; protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult(); protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult(); protected val armPBuffer = SkeletonMath.IKResult()

    protected val targetA = Vector3(); protected val targetP = Vector3()
    protected val targetF = Vector3(); protected val targetB = Vector3()
    protected val poleF = Vector3(); protected val poleB = Vector3()
    protected val scratchShoulderA = Vector3(); protected val scratchShoulderP = Vector3()

    protected val halfPi = PI.toFloat() / 2f

    protected fun ensureHierarchy() {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        scapulaA = nodes.scapulaA; shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        scapulaP = nodes.scapulaP; shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    /** The Y of the bar's top face — where the hands grip and the declared hand contacts rest. */
    protected abstract val barGripY: Float

    protected open val barGripX: Float = 0f

    /** Grip width as a multiple of the shoulder width (the family's own grip unit). */
    protected abstract val gripWidthFactor: Float

    /** The elbow-bend plane selector, authored in the shoulder's own (trunk) frame. */
    protected abstract val elbowPoleA: Vector3
    protected abstract val elbowPoleP: Vector3

    /** The lateral (Z) reach the hands take: `±gripZ` about the body's mid-line. */
    protected fun gripZ(def: SkeletonDefinition): Float = gripWidthFactor * def.shoulderWidth

    /**
     * The part of the shoulder→grip distance spent LATERALLY: the shoulder sits `shoulderWidth` off
     * the mid-line and the grip `gripZ`, so the sagittal component is `sqrt(reach² − lateral²)` — the
     * Vertical-Pull family's own derivation, reused so both author the same reach quantity.
     */
    protected fun sagittalSpan(def: SkeletonDefinition, reach: Float): Float {
        val lateral = gripZ(def) - def.shoulderWidth
        return sqrt(max(reach * reach - lateral * lateral, 1f))
    }

    /** The two FIXED hand world targets on the bar. Constants of the pose, not of the frame. */
    protected fun barHandTargets(def: SkeletonDefinition) {
        targetA.set(barGripX, barGripY, -gripZ(def))
        targetP.set(barGripX, barGripY, gripZ(def))
    }

    /** Authors the socket rotations, then propagates the authoring tree so world reads are current. */
    protected fun authoringFk() {
        val list = roots!!
        for (i in list.indices) list[i].updateWorldTransforms(zeroVector, identityRotation)
    }

    /**
     * The thorax's own authored rotation on top of the trunk's pitch: a small MAINTAINED extension
     * (the chest stays proud), at the corpus's braced-upper-back scale (`StaticForearmPlankPose`
     * authors `0.09 rad`).
     *
     * The body's pitch must live on the PELVIS: a node's `localPosition` is expressed in its parent's
     * frame, so the chest's offset from the pelvis is rotated by the pelvis — putting any share of the
     * pitch on the chest moves it off the body's line (measured: a `0.75/0.25` split put the published
     * chest `18 u` off its colinear position). The chest's `localRotation` is an ADDITIONAL value.
     *
     * A member that drives the girdle must author a non-zero brace: `reconstructChestFrame` is a
     * fallback for an UNAUTHORED thorax and infers the chest frame from the shoulder line, which the
     * girdle's rotation tilts — the published shoulders then carry the girdle twice (measured
     * `12.709 u` of retraction travel against the girdle's own `6.4 u`).
     */
    protected open val trunkThoracicBrace: Float = 0f

    /**
     * Authors the trunk: the pelvis carries the pitch (so the hips/legs inherit the whole bend and the
     * ankle→hip→shoulder chord stays colinear), the chest carries [trunkThoracicBrace].
     */
    protected fun buildBarTrunk(pitch: Float) {
        buildSpineCurve(pelvis!!, chest!!, pitch, trunkThoracicBrace)
    }

    /**
     * The scapular girdle drive on the ONE canonical channel: retraction and depression are real
     * scapula rotations, so the glenoid's position is DERIVED from the girdle, not translated by hand.
     */
    protected fun driveScapula(retraction: Float, depression: Float) {
        SkeletonMath.buildScapularRotation(retraction, depression, -1f, scapulaA!!.localRotation)
        SkeletonMath.buildScapularRotation(retraction, depression, 1f, scapulaP!!.localRotation)
    }

    /** Places the shoulders at their anatomical lateral offset from the (rotated) girdle. */
    protected fun buildShoulders(def: SkeletonDefinition) {
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
    }

    /** IK both arms to the FIXED bar grips. The hand targets are constants; the body moved. */
    protected fun bakeArmsToBar(def: SkeletonDefinition) {
        scratchShoulderA.set(shoulderA!!.worldPosition)
        scratchShoulderP.set(shoulderP!!.worldPosition)
        val poleA = SkeletonMath.toWorldDirection(elbowPoleA, elbowA!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(scratchShoulderA, targetA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, shoulderA!!.worldRotation, elbowA!!, handA!!, armABuffer)
        val poleP = SkeletonMath.toWorldDirection(elbowPoleP, elbowP!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(scratchShoulderP, targetP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, shoulderP!!.worldRotation, elbowP!!, handP!!, armPBuffer)
        // The bake writes the chain's LOCAL offsets without re-running FK, so the elbow's world
        // position is still the authoring pass's until this re-propagation — and the grip below is
        // derived from the REALIZED forearm (measured: reading the elbow before this pass left the
        // palm chain `24°` out of the bar's plane).
        authoringFk()
    }

    /**
     * The **flat-in-the-bar's-plane grip**, authored through the one channel that can state it.
     *
     * A bar-supported hand grips with its long axis IN the bar's own plane; a hand sloping out of it
     * drives the palm/knuckles/fingertips through the bar's face. The default completion derives the
     * hand from the forearm plus an authored wrist angle, and the forearm sweeps across a right angle
     * through the rep (measured `72.6°` above the plane at the inverted row's bottom, `5.2°` below it
     * at the top), so no constant wrist angle lays the hand flat — and under `IK_STAGE_ACTIVE` the
     * pose cannot read the realized forearm inside `build()` anyway. So the endpoint orientations are
     * authored outright (`MANUAL_OVERRIDE` channel, as `PikePushUpPose` does for its grip) with
     * the definition's own hand segments.
     *
     * The offsets are authored in each node's parent frame from the WORLD segment deltas
     * (`toLocalDirection` against the parent's rotation, which the limb solve never touches — an IK
     * bake writes positions only), so the published hand lies along [worldDirection] exactly.
     */
    protected fun authorFlatBarGrip(
        def: SkeletonDefinition,
        extremity: Extremity,
        handNode: SkeletonNode,
        palmNode: SkeletonNode,
        knucklesNode: SkeletonNode,
        fingertipsNode: SkeletonNode,
        worldDirection: Vector3
    ) {
        val dir = tempV1.set(worldDirection).normalize()
        // The definition's own hand segments (HandDefinition: the palm is half the palm length, the
        // knuckles the full palm length, the fingertips palm + finger length from the wrist).
        val hand = def.hand
        setSegment(palmNode, dir, hand.palmLength * 0.5f)
        setSegment(knucklesNode, dir, hand.palmLength * 0.5f)
        setSegment(fingertipsNode, dir, hand.fingerLength)
        overrideExtremityOrientation(jointsBuffer, extremity)
        handNode.localRotation.axis.set(0f, 0f, 1f)
        handNode.localRotation.angle = 0f
    }

    private fun setSegment(node: SkeletonNode, dir: Vector3, length: Float) {
        val parentRot = node.parent!!.worldRotation
        tempV2.set(dir.x * length, dir.y * length, dir.z * length)
        SkeletonMath.toLocalDirection(tempV2, parentRot, node.localPosition)
    }

    protected fun finalizeBarSupportPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
