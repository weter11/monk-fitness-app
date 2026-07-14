package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Step-Up — a completely separate model from the lunge family. The defining
 * biomechanics of a step-up:
 *
 *  - **Body rises because the support leg extends.** The working (front) foot is
 *    fixed on top of the step. The hip/pelvis height is *derived* from the support
 *    leg's extension: at the bottom the support knee is bent (hip low); as the leg
 *    straightens the hip rises. The pelvis is never translated as a rigid block.
 *  - **The rear leg unloads.** The rear foot is driven by the same rep parameter:
 *    it lifts off the floor and steps up to meet the support foot at the top, then
 *    lowers back down. It naturally leaves the floor (airborne arc), so there is no
 *    foot sliding, no floating, no teleport.
 *  - **The pelvis rises after the support extension** — i.e. the hip follows the
 *    support foot, the chest follows the hip, the head follows the chest.
 *
 * The rep parameter `e` ramps 0→1 across the cycle; [LoopMode.PING_PONG] plays it
 * bottom→top→bottom with smooth turnarounds.
 */
class StepUpPose : BasePose() {

    private val stepTopY = 12f          // top surface of the step prop
    private val ankleOnStep = stepTopY + 10f   // ankle rests 10 above the step
    private val groundFootY = 10f
    private val liftHeight = 20f

    private val supportX = 20f
    private val supportZ = -22f         // front (F / -Z) leg is the working leg
    private val rearGroundX = -15f
    private val rearGroundZ = 30f
    private val rearStepX = 15f
    private val rearStepZ = 24f

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    private val supportAnkle = Vector3()
    private val rearAnkle = Vector3()
    private val targetHandA = Vector3(); private val targetHandP = Vector3()
    private val poleF = Vector3(); private val poleB = Vector3(); private val poleA = Vector3(); private val poleP = Vector3()
    private val gaze = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.5f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                StepProp(center = Vector3(15f, 6.0f, 0f), width = 40f, height = 12f, depth = 40f)
            )
        )
    )

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // Rep parameter: 0 = bottom (support bent, rear on floor), 1 = top (support extended, rear on step).
        val e = context.progress
        val s = SkeletonMath.easeIO(e)            // eased placement of the rear foot
        val parabola = 4f * e * (1f - e)          // airborne arc of the rear foot

        // --- Support (front) foot fixed on the step ------------------------------
        supportAnkle.set(supportX, ankleOnStep, supportZ)

        // --- Pelvis derived from support-leg extension --------------------------
        // distance hip->ankle grows from (bent) to (near-straight) as the leg extends.
        val legDist = SkeletonMath.lerp(152f, 200f, e)
        val legTilt = (1f - e) * 0.42f            // support thigh angled forward when bent
        val hipX = supportX - sin(legTilt) * legDist
        val hipY = ankleOnStep + cos(legTilt) * legDist
        val leanAngle = SkeletonMath.lerp(0.04f, 0.16f, e) * (1f - e) + 0.02f

        pelvis!!.localPosition.set(hipX, hipY, 0f)
        pelvis!!.localRotation.set(axisZ, -leanAngle)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        gaze.set(sin(leanAngle), cos(leanAngle), 0f)
        buildHead(neck!!, head!!, def.neckLength, gaze)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // --- Front (support) leg: IK to the fixed step foot ---------------------
        poleF.set(0.25f, 1f, -0.2f)
        bakeIkLimb(hipF!!.worldPosition, supportAnkle, def.thighLength, def.shinLength,
            poleF, pelvis!!.worldRotation, def.legIKConstraint, leanAngle, kneeF!!, ankleF!!, legFBuffer)
        ankleF!!.localRotation.set(axisZ, leanAngle + 0.05f)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f)
        toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // --- Rear leg: unloads and steps up to meet the support foot ------------
        rearAnkle.set(
            SkeletonMath.lerp(rearGroundX, rearStepX, s),
            SkeletonMath.lerp(groundFootY, ankleOnStep, s) + parabola * liftHeight,
            SkeletonMath.lerp(rearGroundZ, rearStepZ, s)
        )
        poleB.set(0.25f, 1f, 0.2f)
        bakeIkLimb(hipB!!.worldPosition, rearAnkle, def.thighLength, def.shinLength,
            poleB, pelvis!!.worldRotation, def.legIKConstraint, leanAngle, kneeB!!, ankleB!!, legBBuffer)
        ankleB!!.localRotation.set(axisZ, leanAngle + 0.05f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f)
        toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // --- Arms: natural counterbalance swing ---------------------------------
        val handY = (shoulderA!!.worldPosition.y + shoulderP!!.worldPosition.y) * 0.5f - 30f
        val reach = 18f + 14f * sin(e * PI.toFloat())
        targetHandA.set(shoulderA!!.worldPosition.x - reach, handY, -def.shoulderWidth * 1.4f)
        targetHandP.set(shoulderP!!.worldPosition.x + reach, handY, def.shoulderWidth * 1.4f)
        poleA.set(0f, -1f, -1.2f); poleP.set(0f, -1f, 1.2f)
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength,
            poleA, chest!!.worldRotation, def.armIKConstraint, leanAngle, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength,
            poleP, chest!!.worldRotation, def.armIKConstraint, leanAngle, elbowP!!, handP!!, armPBuffer)
        handA!!.localRotation.set(axisZ, leanAngle); handP!!.localRotation.set(axisZ, leanAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

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
