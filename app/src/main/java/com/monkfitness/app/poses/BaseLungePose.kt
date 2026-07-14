package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseLungePose is the single owner of all Lunges & Step-Ups biomechanical scaffolding.
 *
 * Biomechanics-first design:
 *  - Movement is built around the SUPPORT FOOT, never around a hand-tuned pelvis translation.
 *    A variant declares the planted support-foot world target and the swing-foot trajectory;
 *    the pelvis COM is then derived from those foot targets (see [anchorSpine]).
 *  - The pelvis follows the hips, the chest follows the pelvis, and the head follows the chest.
 *  - Knee tracking is forward over the foot (sagittal pole vectors) to avoid valgus.
 *  - The rear foot plantarflexes (heel lift) while the support foot stays flat.
 *  - Arms swing contra-laterally as natural counterbalance and are never frozen.
 *
 * The base owns ONLY genuine shared mechanics. Choreography (foot trajectories, stride,
 * stance, leg sequencing, arm swing, step mechanics, hip mechanics) lives in each variant.
 *
 * Shared responsibilities:
 *  - Skeleton hierarchy via SkeletonFactory.createStandardSkeleton()
 *  - Shared camera + environment metadata
 *  - COM / grounding helpers (anchorFromFeet)
 *  - Leg + arm IK baking via BasePose.bakeIkLimb()
 *  - Foot + hand extremity geometry via FootDefinition ratios and the family hand convention
 *  - Allocation-free scratch buffers and finalization
 */
abstract class BaseLungePose : BasePose() {

    companion object {
        // Standing hip height above the ankle. Slightly under full extension so the
        // standing leg stays comfortably inside the IK limit (thigh+shin = 210).
        const val STAND_HIP_RISE = 205f
        const val ANKLE_Y = 15f // FootDefinition.ankleHeight
        const val STAND_PELVIS_Y = ANKLE_Y + STAND_HIP_RISE // 220
        const val LUNGE_DROP = 70f
        const val DEFAULT_STRIDE = 80f
        const val SIDE_STRIDE = 100f
        const val FOOT_PITCH_FLAT = 0.04f
        const val FOOT_PITCH_HEEL_LIFT = 0.5f
    }

    // --- Shared metadata (single-sourced; removed duplicated literals from every variant) ---
    protected val lungeCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.3f
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

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    // Allocation-free scratch (targets + poles) reused every frame.
    protected val targetF = Vector3(); protected val targetB = Vector3()
    protected val targetA = Vector3(); protected val targetP = Vector3()
    protected val poleF = Vector3(); protected val poleB = Vector3()
    protected val poleA = Vector3(); protected val poleP = Vector3()

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
     * Head gaze direction: looks up and follows the thorax forward as the torso leans.
     * Neutral at standing (straight up), tilts forward with the lean so the head follows the chest.
     */
    private fun headGaze(lean: Float): Vector3 {
        val a = lean * 0.6f
        return Vector3(sin(a), cos(a), 0f).normalize()
    }

    /**
     * Anchors the spine from the pelvis: sets pelvis position + forward-lean rotation, the
     * chest, the head gaze, the hips and shoulders, then refreshes world transforms so the
     * limb IK roots (hips / shoulders) are current. This is the "pelvis follows support" link:
     * the pelvis passed in already encodes the COM derived from the support foot.
     */
    protected fun anchorSpine(def: SkeletonDefinition, pelvisX: Float, pelvisY: Float, pelvisZ: Float, leanAngle: Float) {
        pelvis!!.localPosition.set(pelvisX, pelvisY, pelvisZ)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildHead(neck!!, head!!, def.neckLength, headGaze(leanAngle))

        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }
    }

    /**
     * Bakes both legs from the hip world positions to the given ankle targets.
     * The forward(+X)/down(-Y) pole with the foot's Z splay keeps the knee tracking forward
     * over the foot (no valgus). [leanAngle] propagates the leaned pelvis frame to the limbs.
     * [targetForF] is the world target for the Side-F (rear) leg, [targetForB] for Side-B (front).
     */
    protected fun bakeLegs(def: SkeletonDefinition, leanAngle: Float, targetForF: Vector3, targetForB: Vector3) {
        // Sagittal poles: knee tracks forward (+X) over the foot (no valgus).
        poleF.set(1f, -1f, -0.2f)
        poleB.set(1f, -1f, 0.2f)
        bakeLegsPoles(def, leanAngle, targetForF, targetForB, poleF, poleB)
    }

    /**
     * Like [bakeLegs] but with caller-supplied pole vectors (used by lateral/curtsy lunges where
     * the working knee must track outward over the foot rather than purely forward).
     */
    protected fun bakeLegsPoles(def: SkeletonDefinition, leanAngle: Float, targetForF: Vector3, targetForB: Vector3, pf: Vector3, pb: Vector3) {
        bakeIkLimb(hipF!!.worldPosition, targetForF, def.thighLength, def.shinLength, pf, def.legIKConstraint, leanAngle, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetForB, def.thighLength, def.shinLength, pb, def.legIKConstraint, leanAngle, kneeB!!, ankleB!!, legBBuffer)
    }

    /**
     * Bakes both arms from the shoulder world positions to contra-lateral swing targets.
     * [armSwing] > 0 swings the Side-A (left) arm forward and the Side-P (right) arm back.
     */
    protected fun bakeArms(def: SkeletonDefinition, leanAngle: Float, armSwing: Float, pelvisX: Float, pelvisY: Float) {
        val handY = pelvisY - 12f
        targetA.set(pelvisX + armSwing * 32f, handY, -def.shoulderWidth)
        targetP.set(pelvisX - armSwing * 32f, handY, def.shoulderWidth)
        poleA.set(0.2f, -1f, -1f)
        poleP.set(0.2f, -1f, 1f)
        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, poleA, def.armIKConstraint, leanAngle, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, poleP, def.armIKConstraint, leanAngle, elbowP!!, handP!!, armPBuffer)
    }

    /**
     * Applies constant extremity geometry. The support (Side-B / front) foot stays flat; the
     * rear (Side-F) foot plantarflexes to lift the heel. Hand offsets use the standard family
     * convention (palm/knuckles 6, fingertips 10) matching BasePushUpPose / BaseSquatPose.
     * Heel/toe ratios are owned by FootDefinition (no duplicated literals).
     */
    protected fun applyExtremities(def: SkeletonDefinition, leanAngle: Float, frontFootPitch: Float, rearFootPitch: Float) {
        // Side-B is the front / support foot (flat); Side-F is the rear foot (heel lift).
        ankleB!!.localRotation.set(axisZ, leanAngle - frontFootPitch)
        ankleF!!.localRotation.set(axisZ, leanAngle - rearFootPitch)

        val hl = def.foot.footLength * def.foot.heelRatio
        val tl = def.foot.footLength * def.foot.toeRatio
        heelF!!.localPosition.set(-hl, 0f, 0f); toeF!!.localPosition.set(tl, 0f, 0f)
        heelB!!.localPosition.set(-hl, 0f, 0f); toeB!!.localPosition.set(tl, 0f, 0f)

        handA!!.localRotation.set(axisZ, leanAngle); handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)
    }

    /**
     * Finalizes the pose: flatten the hierarchy into the compatible joint map, sync the
     * wrist joints to the hands (the standard skeleton has no WRIST node), and record the max
     * IK clamp so the validator's reachability rule sees a real value.
     */
    protected fun smootherStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    /**
     * Shared rear-foot step-up trajectory. Horizontal travel happens ONLY while the foot is lifted
     * (y >= ~15); while it is near the ground (y = 10) it is stationary, so there is never any foot
     * slide during ground contact. Used by both StepUp and HighStepUp so the trajectory logic lives
     * in exactly one place.
     */
    protected fun stepBackFoot(prog: Float, zB: Float, out: Vector3) {
        if (prog <= 0.5f) {
            when {
                prog <= 0.1f -> out.set(-15f, 10f, zB)
                prog <= 0.15f -> {
                    val v = smootherStep(0.1f, 0.15f, prog)
                    out.set(-15f, SkeletonMath.lerp(10f, 15f, v), zB)
                }
                prog <= 0.35f -> {
                    val v = smootherStep(0.15f, 0.35f, prog)
                    val tx = SkeletonMath.lerp(-15f, 10f, v)
                    val ty = SkeletonMath.lerp(15f, 22f, v)
                    val lift = sin(v * PI.toFloat()) * 12f
                    out.set(tx, ty + lift, zB)
                }
                else -> out.set(10f, 22f, zB)
            }
        } else {
            when {
                prog <= 0.6f -> out.set(10f, 22f, zB)
                prog <= 0.85f -> {
                    val v = smootherStep(0.6f, 0.85f, prog)
                    val tx = SkeletonMath.lerp(10f, -15f, v)
                    val ty = SkeletonMath.lerp(22f, 15f, v)
                    val lift = sin(v * PI.toFloat()) * 12f
                    out.set(tx, ty + lift, zB)
                }
                prog <= 0.9f -> {
                    val v = smootherStep(0.85f, 0.9f, prog)
                    out.set(-15f, SkeletonMath.lerp(15f, 10f, v), zB)
                }
                else -> out.set(-15f, 10f, zB)
            }
        }
    }

    protected fun finalizeLunge(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        jointsBuffer.maxIkClampAmount = maxOf(
            legFBuffer.clampAmount, legBBuffer.clampAmount,
            armABuffer.clampAmount, armPBuffer.clampAmount
        )
        return jointsBuffer
    }
}
