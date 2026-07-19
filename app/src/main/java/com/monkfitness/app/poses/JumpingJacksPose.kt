package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Jumping Jacks — a rhythmic plyometric cardio movement. The defining motion is a
 * coronal-plane "star": the legs abduct (feet spread laterally on the ground) while the
 * arms elevate overhead, then return to a narrow standing position with arms at the sides.
 *
 * Authored as a smooth loop (cosine ease) from closed (narrow stance, arms down) to open
 * (wide stance, arms overhead) and back. The feet stay planted on the ground; only their
 * lateral spread and the arm raise change, so the pose stays within a STANDING posture and
 * the ConstraintSolver owns the pelvis height (no ballistic flight).
 */
class JumpingJacksPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.0f,
        loopMode = LoopMode.LOOP,
        // LINEAR: the cosine phase map below already produces a smooth open/close ease.
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    // Closed (narrow) stance targets — feet together, arms down at the sides.
    private val closedAnkleF = Vector3()
    private val closedAnkleB = Vector3()
    private val closedHandA = Vector3()
    private val closedHandP = Vector3()

    // Open (wide star) targets — feet spread laterally, arms overhead.
    private val openAnkleF = Vector3()
    private val openAnkleB = Vector3()
    private val openHandA = Vector3()
    private val openHandP = Vector3()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        pelvis = SkeletonNode(Joint.PELVIS)
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END))
        head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A))
        elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A))
        handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A))
        palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A))
        knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A))
        fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))

        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P))
        elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P))
        handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P))
        palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P))
        knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P))
        fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F))
        kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F))
        ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F))
        heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F))
        toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))

        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B))
        kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B))
        ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))
        heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B))
        toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))

        roots = listOf(pelvis!!)
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // B3 — coarse pelvis height is owned by the ConstraintSolver (STANDING seed == standH).
        // The feet stay planted, so the solver pins pelvis.y = standH byte-identically.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.STANDING)

        // Smooth 0 -> 1 -> 0 open/close ease so the loop is continuous at the seam.
        val open = 0.5f * (1f - cos(context.progress * 2f * PI.toFloat()))

        pelvis!!.localPosition = Vector3(0f, 0f, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), 0f)

        // standH is retained only for the hand kinematics (an overhead-reach shape decision),
        // not for the root height the solver now owns.
        val standH = def.shinLength + def.thighLength + 25f

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // --- Leg targets: feet stay on the ground; spread laterally with the open factor. ---
        val spread = lerp(def.hipWidth * 0.6f, def.hipWidth * 2.0f, open)
        closedAnkleF.set(0f, def.foot.ankleHeight, -def.hipWidth * 0.6f)
        closedAnkleB.set(0f, def.foot.ankleHeight, def.hipWidth * 0.6f)
        openAnkleF.set(0f, def.foot.ankleHeight, -spread)
        openAnkleB.set(0f, def.foot.ankleHeight, spread)

        val targetAnkleF = Vector3(lerp(closedAnkleF.x, openAnkleF.x, open), lerp(closedAnkleF.y, openAnkleF.y, open), lerp(closedAnkleF.z, openAnkleF.z, open))
        val targetAnkleB = Vector3(lerp(closedAnkleB.x, openAnkleB.x, open), lerp(closedAnkleB.y, openAnkleB.y, open), lerp(closedAnkleB.z, openAnkleB.z, open))

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0.5f, 1f, -0.3f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0.3f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // --- Arm targets: down at the sides (closed) to overhead & slightly out (open). ---
        // The humerus+forearm nearly straighten for the overhead reach; a slight bend keeps the
        // arm IK constraint satisfiable.
        val reach = def.upperArmLength + def.forearmLength
        closedHandA.set(0f, standH + def.torsoLength - reach * 0.55f, -def.shoulderWidth - 15f)
        closedHandP.set(0f, standH + def.torsoLength - reach * 0.55f, def.shoulderWidth + 15f)
        openHandA.set(0f, standH + def.torsoLength + reach * 0.78f, -def.shoulderWidth - 10f)
        openHandP.set(0f, standH + def.torsoLength + reach * 0.78f, def.shoulderWidth + 10f)

        val targetHandA = Vector3(lerp(closedHandA.x, openHandA.x, open), lerp(closedHandA.y, openHandA.y, open), lerp(closedHandA.z, openHandA.z, open))
        val targetHandP = Vector3(lerp(closedHandP.x, openHandP.x, open), lerp(closedHandP.y, openHandP.y, open), lerp(closedHandP.z, openHandP.z, open))

        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -1f), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 1f), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // W1: engine derives foot/hand orientation from the limb + extremity articulations.

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}