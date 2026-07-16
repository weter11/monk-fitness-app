package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseLungePose owns ONLY the genuine shared Lunges & Step-Ups plumbing.
 *
 * It deliberately encodes NO choreography. The chain of command for every family
 * member is the inverse of the legacy rigid-translation model:
 *
 *   support foot (fixed) -> swing foot (trajectory) -> COM moves naturally ->
 *   hip follows support -> pelvis follows hip -> chest follows pelvis -> head follows chest
 *
 * The pelvis is therefore a *consequence* of where the feet are, never the driver.
 *
 * What is genuinely shared and therefore lives here:
 *  - Standard skeleton via [SkeletonFactory.createStandardSkeleton]
 *  - Allocation-free IK buffers + scratch targets/poles
 *  - [bakeLeg] / [bakeArm] helpers wrapping [BasePose.bakeIkLimb] with frame-relative
 *    poles (stable in the pelvis / chest frame) plus foot + hand finalization
 *  - A shared breathing / stabilisation micro-driver that is ZERO at the rep endpoints
 *  - A shared COM helper that distributes the pelvis over the support base
 *  - Shared ground camera + environment
 *  - Shared finalization (FK flatten, wrist mirror, IK clamp reporting)
 *
 * Everything else — leg sequencing, weight transfer, stance, stride, arm swing,
 * step mechanics and hip mechanics — is supplied by the concrete variant, because
 * it is Lunges/Step-Ups *biomechanics*, not engine knowledge.
 */
abstract class BaseLungePose : BasePose() {

    protected val footRestY = 25f

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult(); protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult(); protected val armPBuffer = SkeletonMath.IKResult()

    protected val targetA = Vector3(); protected val targetP = Vector3()
    protected val targetF = Vector3(); protected val targetB = Vector3()

    // Shared engine scaffolding
    protected val lungeCamera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f)
    protected val lungeEnvironment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    /**
     * Endpoint-zero breathing / stabilisation wave. Zero at progress 0 and 1 so a
     * PING_PONG or LOOP turnaround produces no snap.
     */
    protected fun breathWave(p: Float): Float = 0.5f - 0.5f * cos(p * 2f * PI.toFloat())

    /**
     * Centre-of-mass X given the two foot X positions and the load fraction.
     * The pelvis sits midway between the feet (balanced base) biased toward the
     * loaded foot as the rep deepens.
     */
    protected fun comX(plantX: Float, swingX: Float, load: Float): Float =
        (plantX + swingX) * 0.5f + (swingX - plantX) * 0.12f * load

    protected fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Bakes one leg from its hip to a world-space ankle target using a frame-relative
     * pole (stable in the pelvis frame) and writes the heel/toe + ankle orientation.
     * The knee/ankle are counter-rotated by the pelvis lean so the planted foot stays
     * level with the ground while the torso pitches.
     */
    protected fun bakeLeg(
        def: SkeletonDefinition,
        hipWorld: Vector3,
        targetAnkle: Vector3,
        poleLocal: Vector3,
        parentRotation: JointRotation,
        knee: SkeletonNode,
        ankle: SkeletonNode,
        heel: SkeletonNode,
        toe: SkeletonNode,
        buffer: SkeletonMath.IKResult
    ) {
        // W1: engine now derives foot orientation (removed ankle tilt counter-rotation + manual heel/toe).
        // M1 (Gap 5 / F4): frame-relative pole converted to world space here, then the world-only
        // bakeIkLimb overload is used (the frame-relative overload was deleted).
        val worldPoleLeg = SkeletonMath.toWorldDirection(poleLocal, parentRotation, tempPoleWorld)
        bakeIkLimb(hipWorld, targetAnkle, def.thighLength, def.shinLength, worldPoleLeg, def.legIKConstraint, parentRotation, knee, ankle, buffer)
    }

    /**
     * Bakes one arm from its shoulder to a world-space hand target using a frame-relative
     * pole (stable in the chest frame).
     */
    protected fun bakeArm(
        def: SkeletonDefinition,
        shoulderWorld: Vector3,
        targetHand: Vector3,
        poleLocal: Vector3,
        parentRotation: JointRotation,
        elbow: SkeletonNode,
        hand: SkeletonNode,
        palm: SkeletonNode,
        knuckles: SkeletonNode,
        fingertips: SkeletonNode,
        buffer: SkeletonMath.IKResult
    ) {
        // W1: engine now derives hand orientation (removed wrist tilt counter-rotation + 6/6/10 offsets).
        // M1 (Gap 5 / F4): frame-relative pole converted to world space here, then the world-only
        // bakeIkLimb overload is used (the frame-relative overload was deleted).
        val worldPoleArm = SkeletonMath.toWorldDirection(poleLocal, parentRotation, tempPoleWorld)
        bakeIkLimb(shoulderWorld, targetHand, def.upperArmLength, def.forearmLength, worldPoleArm, def.armIKConstraint, parentRotation, elbow, hand, buffer)
    }

    protected fun finishPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /**
     * Assembles the full skeleton from already-computed, per-frame, per-exercise
     * targets. This is plumbing only — the *values* come from the concrete
     * choreography. The support leg is the fixed/loaded leg; the swing leg follows
     * its own trajectory.
     */
    protected fun assemble(
        def: SkeletonDefinition,
        plantAnkle: Vector3,
        swingAnkle: Vector3,
        plantUsesFrontHip: Boolean,
        pelvisX: Float,
        pelvisY: Float,
        pelvisZ: Float,
        pelvisAngle: Float,
        chestPitch: Float,
        armAmt: Float,
        armPmt: Float,
        poleFrontLocal: Vector3,
        poleBackLocal: Vector3,
        poleArmALocal: Vector3,
        poleArmPLocal: Vector3
    ): SkeletonPose {
        pelvis!!.localPosition.set(pelvisX, pelvisY, pelvisZ)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Phase 5 (W13/G4, W14/G5): single spine-intent call. Lower segment is the
        // PELVIS (hips attach here, so they inherit the lean); chest adds the upper bend.
        buildSpineCurve(pelvis!!, chest!!, pelvisAngle, chestPitch)

        // Head follows the thorax: counter-rotate the gaze so the head stays upright
        // (eyes forward) while the torso leans.
        val headTilt = pelvisAngle + chestPitch
        val gaze = SkeletonMath.rotAround(Vector3(0f, 1f, 0f), axisZ, -headTilt, tempV3)
        gaze.normalize()
        buildHead(neck!!, head!!, def.neckLength, gaze)

        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // --- Legs: front hip uses the front pole, back hip uses the back pole ---
        val parentRot = pelvis!!.worldRotation
        bakeLeg(def, hipF!!.worldPosition, if (plantUsesFrontHip) plantAnkle else swingAnkle, poleFrontLocal, parentRot, kneeF!!, ankleF!!, heelF!!, toeF!!, legFBuffer)
        bakeLeg(def, hipB!!.worldPosition, if (plantUsesFrontHip) swingAnkle else plantAnkle, poleBackLocal, parentRot, kneeB!!, ankleB!!, heelB!!, toeB!!, legBBuffer)

        // --- Arms: counterbalance swing, solved after FK so shoulders are known ---
        val shA = shoulderA!!.worldPosition
        val shP = shoulderP!!.worldPosition
        val baseHandY = minOf(shA.y, shP.y) - 95f
        targetA.set(shA.x + armAmt, baseHandY + abs(armAmt) * 0.3f, -def.shoulderWidth * 1.1f)
        targetP.set(shP.x + armPmt, baseHandY + abs(armPmt) * 0.3f, def.shoulderWidth * 1.1f)

        val chestRot = chest!!.worldRotation
        bakeArm(def, shA, targetA, poleArmALocal, chestRot, elbowA!!, handA!!, palmA!!, knucklesA!!, fingertipsA!!, armABuffer)
        bakeArm(def, shP, targetP, poleArmPLocal, chestRot, elbowP!!, handP!!, palmP!!, knucklesP!!, fingertipsP!!, armPBuffer)

        return finishPose()
    }

    companion object {
        // Frame-relative poles (authored in the pelvis / chest local frame)
        val POLE_LEG_FRONT = Vector3(0.6f, 1f, -0.15f)
        val POLE_LEG_BACK = Vector3(0.6f, 1f, 0.15f)
        val POLE_ARM_A = Vector3(0f, -1f, -1f)
        val POLE_ARM_P = Vector3(0f, -1f, 1f)

        // Standing pelvis height (soft knees so the leg is never driven past full extension)
        fun standingPelvisY(def: SkeletonDefinition): Float = def.thighLength + def.shinLength + 22f
    }
}
