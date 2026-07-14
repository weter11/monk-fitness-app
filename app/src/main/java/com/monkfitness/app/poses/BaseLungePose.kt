package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseLungePose owns only the *genuine* shared mechanics of the Lunges & Step-Ups
 * family. It is a biomechanics-first rewrite (not a modernization of the old
 * pelvis-translation math).
 *
 * What is truly shared and therefore lives here:
 *  - Skeleton hierarchy via [SkeletonFactory.createStandardSkeleton]
 *  - Allocation-free IK buffers and scratch targets / poles
 *  - [buildPelvis] / [buildShoulders] / [buildHead] helpers
 *  - Frame-relative IK ([bakeIkLimb]) with poles expressed in the pelvis / chest frame
 *  - A shared, support-leg-driven motion model (see below)
 *  - Shared camera + ground environment
 *  - Finalization (FK flatten, wrist mirroring, IK-clamp reporting)
 *
 * What each concrete pose owns (and this base deliberately does NOT abstract):
 *  - Stance / stride (sagittal forward/back vs. lateral)
 *  - Which foot leads (forward lunge vs. reverse lunge mirror)
 *  - Leg sequencing / weight transfer
 *  - Step mechanics (the airborne arc of the travelling foot)
 *  - Hip / knee / ankle interaction specifics
 *  - Arm swing style
 *
 * ## Movement model (support leg is the driver, never the pelvis)
 *
 * The motion is built *around the support foot*, exactly as real lunges are:
 *
 *  1. The support foot is **fixed on the ground** while it is the planted anchor.
 *  2. The travelling foot **leaves the floor** (vertical lift arc) while it steps,
 *     then plants and becomes the next support — so feet never "slide" while loaded.
 *  3. The COM follows the support: the pelvis X/Z shift is derived toward the
 *     support foot, and the pelvis **height is lowered as a consequence** of the
 *     support knee flexing (the hip drops because the leg shortens), never as a
 *     rigid-body translation of the whole body.
 *  4. The hip follows the support, the pelvis follows the hip, the chest follows
 *     the pelvis (FK), the head follows the chest (gaze tilts with the thorax).
 *
 * Because the pelvis height is derived from `lerp(standH, bottomH, depth)` where the
 * *depth* (knee flexion) is the driver — not the pelvis being moved and legs IK'd to
 * it after the fact — the body reads as weight transferring through the legs, not as
 * a block bobbing up and down.
 */
enum class LungeMode { FORWARD, REVERSE, LATERAL }

abstract class BaseLungePose : BasePose() {

    /** Which movement this pose performs (drives stance, lead foot and leg poles). */
    protected abstract val mode: LungeMode

    /** Half of the sagittal stride (distance from centre to a fully-placed forward foot). */
    protected open val strideHalf: Float get() = 46f

    /** Lateral stride (distance the working foot travels out to the side). */
    protected open val lateralStride: Float get() = 58f

    /** Forward torso lean (radians) at full depth. */
    protected open val torsoLean: Float get() = 0.16f

    /** How strongly the pelvis shifts toward the support foot (0..1). */
    protected open val supportBias: Float get() = 0.55f

    /** Pelvis (hip) height at standing / mid-stance. */
    protected open val standHeight: Float get() = 200f

    /** Pelvis (hip) height at the deepest part of the lunge. */
    protected open val bottomHeight: Float get() = 112f

    /** Reach of the counter-swinging hands at full depth. */
    protected open val armReach: Float get() = 30f

    protected val lungeCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.5f
    )

    protected val lungeEnvironment = EnvironmentDefinition(
        ground = GroundDefinition(visible = true, level = 0f)
    )

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult(); protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult(); protected val armPBuffer = SkeletonMath.IKResult()

    protected val targetF = Vector3(); protected val targetB = Vector3()
    protected val targetHandA = Vector3(); protected val targetHandP = Vector3()
    protected val poleF = Vector3(); protected val poleB = Vector3()
    protected val poleA = Vector3(); protected val poleP = Vector3()
    protected val gaze = Vector3()

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
     * Quintic smootherstep — zero 1st and 2nd derivative at both ends, so a foot
     * eases out of and into each position with no velocity or acceleration spike.
     */
    protected fun smootherstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    /**
     * Right (+Z) leg forward amount. The leg has explicit *stance* windows (foot
     * planted + stationary at centre or fully forward) and *swing* windows (foot
     * travelling). Because the foot is only grounded (Y < 12) during the stance
     * windows — where its position is constant — it can never "slide" while loaded.
     */
    protected fun footFwdRight(p: Float): Float = when {
        p <= 0.06f -> 0f
        p <= 0.19f -> smootherstep(0.06f, 0.19f, p)
        p <= 0.31f -> 1f
        p <= 0.44f -> 1f - smootherstep(0.31f, 0.44f, p)
        else -> 0f
    }

    /** Left (-Z) leg forward amount — same staged pattern, shifted to the 2nd half. */
    protected fun footFwdLeft(p: Float): Float = when {
        p < 0.56f -> 0f
        p <= 0.69f -> smootherstep(0.56f, 0.69f, p)
        p <= 0.81f -> 1f
        p <= 0.94f -> 1f - smootherstep(0.81f, 0.94f, p)
        else -> 0f
    }

    /** Right foot lift arc: airborne only during the two swing windows. */
    protected fun liftRight(p: Float): Float {
        return when {
            p in 0.06f..0.19f -> LIFT_HEIGHT * sin(PI.toFloat() * (p - 0.06f) / 0.13f)
            p in 0.31f..0.44f -> LIFT_HEIGHT * sin(PI.toFloat() * (p - 0.31f) / 0.13f)
            else -> 0f
        }
    }

    /** Left foot lift arc: airborne only during the two swing windows (2nd half). */
    protected fun liftLeft(p: Float): Float {
        return when {
            p in 0.56f..0.69f -> LIFT_HEIGHT * sin(PI.toFloat() * (p - 0.56f) / 0.13f)
            p in 0.81f..0.94f -> LIFT_HEIGHT * sin(PI.toFloat() * (p - 0.81f) / 0.13f)
            else -> 0f
        }
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val p = context.progress
        // Foot-forward amount (0 = centre/standing, 1 = fully placed) for each leg.
        // Smootherstep ramps give ZERO velocity at the settles, so the foot eases
        // into/out of each position — no snap, no acceleration spike.
        val fR = footFwdRight(p)   // right (B / +Z) leg, active in first half
        val fL = footFwdLeft(p)    // left  (F / -Z) leg, active in second half
        val depth = max(fR, fL)                    // 0 = standing, 1 = deepest
        val forwardSign = if (fR >= fL) 1f else -1f
        val rightActive = p < 0.5f                 // right leg steps during first half

        val dir = if (mode == LungeMode.REVERSE) -1f else 1f

        // --- Foot placement (support foot fixed; travelling foot lifted) ----------
        // Sagittal X for each foot (0 while the foot is the planted pivot).
        val sRightX = if (rightActive) dir * strideHalf * fR else 0f
        val sLeftX = if (!rightActive) dir * strideHalf * fL else 0f
        // Lateral Z for each foot (working foot steps out; other stays under its hip).
        val lRightZ = if (rightActive) (def.hipWidth + lateralStride * fR) else def.hipWidth
        val lLeftZ = if (!rightActive) -(def.hipWidth + lateralStride * fL) else -def.hipWidth

        val rightX = if (mode == LungeMode.LATERAL) 0f else sRightX
        val leftX = if (mode == LungeMode.LATERAL) 0f else sLeftX
        val rightZ = if (mode == LungeMode.LATERAL) lRightZ else def.hipWidth
        val leftZ = if (mode == LungeMode.LATERAL) lLeftZ else -def.hipWidth

        // The travelling foot leaves the floor only during its swing windows, so it
        // is airborne for the whole step and only touches down stationary (at the
        // stance windows), which keeps a planted foot from ever sliding.
        val rightLift = liftRight(p)
        val leftLift = liftLeft(p)

        // --- COM shift toward the support foot ------------------------------------
        val forwardFootX = max(rightX, leftX)
        val backFootX = min(rightX, leftX)
        val comX = 0.5f * (rightX + leftX) + supportBias * (forwardFootX - backFootX) * 0.5f
        val comZ = if (mode == LungeMode.LATERAL) {
            forwardSign * lateralStride * depth * 0.5f
        } else 0f

        // --- Pelvis height derived from depth (support-knee flexion) -------------
        val pelvisY = SkeletonMath.lerp(standHeight, bottomHeight, depth)
        val leanAngle = torsoLean * depth

        pelvis!!.localPosition.set(comX, pelvisY, comZ)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Head follows the thorax by FK; gaze tilts forward with the lean.
        gaze.set(sin(leanAngle), cos(leanAngle), 0f)
        buildHead(neck!!, head!!, def.neckLength, gaze)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // --- Legs: IK to fixed / lifted foot targets, poles in pelvis frame -------
        targetF.set(rightX, GROUND_FOOT_Y + rightLift, rightZ)
        if (mode == LungeMode.LATERAL) {
            // Both knees track outward (over their own toes) in a side lunge.
            poleF.set(0f, 1f, 0.5f)
        } else {
            poleF.set(0.25f, 1f, 0.15f)
        }
        bakeIkLimb(hipB!!.worldPosition, targetF, def.thighLength, def.shinLength,
            poleF, pelvis!!.worldRotation, def.legIKConstraint, leanAngle, kneeB!!, ankleB!!, legBBuffer)

        targetB.set(leftX, GROUND_FOOT_Y + leftLift, leftZ)
        if (mode == LungeMode.LATERAL) {
            poleB.set(0f, 1f, -0.5f)
        } else {
            poleB.set(0.25f, 1f, -0.15f)
        }
        bakeIkLimb(hipF!!.worldPosition, targetB, def.thighLength, def.shinLength,
            poleB, pelvis!!.worldRotation, def.legIKConstraint, leanAngle, kneeF!!, ankleF!!, legFBuffer)

        // Foot pitch: slight dorsiflexion when grounded so toes clear the floor.
        val pitchF = if (rightLift > 1f) 0f else 0.1f
        val pitchB = if (leftLift > 1f) 0f else 0.1f
        ankleB!!.localRotation.set(axisZ, leanAngle + pitchF)
        ankleF!!.localRotation.set(axisZ, leanAngle + pitchB)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f)
        toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f)
        toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // --- Arms: contralateral counter-swing (opposite the lead/support leg) --
        // The lead (forward) foot is the right (+Z) leg when (mode==FORWARD)==(forwardSign>0);
        // in that case the left arm swings forward. For lateral the working leg (right when
        // forwardSign>0) is lead, so the same rule gives the correct contralateral swing.
        val leadLegIsRight = if (mode == LungeMode.LATERAL) forwardSign > 0f
        else (mode == LungeMode.FORWARD) == (forwardSign > 0f)
        val armDir = if (leadLegIsRight) 1f else -1f
        val swingAmt = armDir * depth
        val handY = (shoulderA!!.worldPosition.y + shoulderP!!.worldPosition.y) * 0.5f - 28f
        targetHandA.set(shoulderA!!.worldPosition.x + swingAmt * armReach,
            handY + abs(swingAmt) * 6f, -def.shoulderWidth * 1.4f)
        targetHandP.set(shoulderP!!.worldPosition.x - swingAmt * armReach,
            handY + abs(swingAmt) * 6f, def.shoulderWidth * 1.4f)

        poleA.set(0f, -1f, -1.2f)
        poleP.set(0f, -1f, 1.2f)
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength,
            poleA, chest!!.worldRotation, def.armIKConstraint, leanAngle, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength,
            poleP, chest!!.worldRotation, def.armIKConstraint, leanAngle, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, leanAngle); handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        return finalizeLungePose()
    }

    protected fun finalizeLungePose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        jointsBuffer.maxIkClampAmount = maxOf(
            legFBuffer.clampAmount, legBBuffer.clampAmount,
            armABuffer.clampAmount, armPBuffer.clampAmount
        )
        return jointsBuffer
    }

    protected companion object {
        const val GROUND_FOOT_Y = 10f
        const val LIFT_HEIGHT = 20f
    }
}
