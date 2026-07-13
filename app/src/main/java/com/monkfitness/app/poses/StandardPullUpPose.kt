package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import kotlin.math.*

class StandardPullUpPose : BasePose() {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.6f), // Zoomed out to see the bar
        durationSeconds = 2.8f, loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            // The physical Pull-Up Bar rendered in the environment
            props = listOf(BoxProp(center = Vector3(0f, 500f, 15f), width = 200f, height = 6f, depth = 6f))
        )
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        pelvis = SkeletonNode(Joint.PELVIS); chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END)); head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A)); elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A)); handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A)); palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A)); knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A)); fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))
        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P)); elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P)); handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P)); palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P)); knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P)); fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F)); kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F)); ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F)); heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F)); toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))
        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B)); kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B)); ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B)); heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B)); toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))
        roots = listOf(pelvis!!)
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // 1. Suspension Mechanics
        val lift = context.progress
        // Bar is at Y=500. Deep hang puts Pelvis at 230. Full contraction lifts it to 380.
        val pelvisY = lerp(230f, 380f, lift)
        val pelvisX = lerp(-10f, 15f, lift) // Body arcs slightly forward under the bar

        // Slight hollow-body pitch transitioning to a small arch
        val torsoPitch = lerp(0.1f, -0.15f, lift)

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), torsoPitch)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)

        // Head tilts up slightly to look at the bar
        val headTilt = lerp(0f, -0.3f, lift)
        val headDir = rotAround(Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f), headTilt, Vector3()).normalize()
        neck!!.localPosition = Vector3(headDir.x * def.neckLength, headDir.y * def.neckLength, headDir.z * def.neckLength)
        head!!.localPosition = Vector3(headDir.x * 18f, headDir.y * 18f, headDir.z * 18f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. Fixed Absolute Hand Anchors
        val barY = 500f
        val gripWidth = def.shoulderWidth * 1.6f // Wide grip

        val targetHandA = Vector3(pelvisX, barY, -gripWidth)
        val targetHandP = Vector3(pelvisX, barY, gripWidth)

        // Overhand pull-up: Pole vectors push elbows OUTWARD laterally (-Z and +Z)
        val poleArmA = Vector3(0f, -1f, -2f)
        val poleArmP = Vector3(0f, -1f, 2f)

        val armA = bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, poleArmA, def.armIKConstraint, -torsoPitch, elbowA!!, handA!!, armABuffer)
        val armP = bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, poleArmP, def.armIKConstraint, -torsoPitch, elbowP!!, handP!!, armPBuffer)

        // Hands wrap over the bar
        handA!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch - 1.57f); handP!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch - 1.57f)
        palmA!!.localPosition = Vector3(6f, 0f, 0f); knucklesA!!.localPosition = Vector3(6f, 0f, 0f); fingertipsA!!.localPosition = Vector3(10f, 0f, 0f)
        palmP!!.localPosition = Vector3(6f, 0f, 0f); knucklesP!!.localPosition = Vector3(6f, 0f, 0f); fingertipsP!!.localPosition = Vector3(10f, 0f, 0f)

        // 3. Floating Legs (Knees bent slightly backward)
        // IK targets pull the ankles up and back to create a natural hanging sway
        val ankleY = pelvisY - 140f
        val ankleX = pelvisX - 40f
        val targetAnkleF = Vector3(ankleX, ankleY, -def.hipWidth * 0.8f)
        val targetAnkleB = Vector3(ankleX, ankleY, def.hipWidth * 0.8f)

        val legFIK = bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, -1f, 0f), def.legIKConstraint, -torsoPitch, kneeF!!, ankleF!!, legFBuffer)
        val legBIK = bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, -1f, 0f), def.legIKConstraint, -torsoPitch, kneeB!!, ankleB!!, legBBuffer)

        val plantarFlexion = 0.5f // Point toes to floor
        ankleF!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch - plantarFlexion)
        ankleB!!.localRotation.set(Vector3(0f, 0f, 1f), -torsoPitch - plantarFlexion)
        heelF!!.localPosition = Vector3(-def.foot.footLength * 0.29f, 0f, 0f); toeF!!.localPosition = Vector3(def.foot.footLength * 0.71f, 0f, 0f)
        heelB!!.localPosition = Vector3(-def.foot.footLength * 0.29f, 0f, 0f); toeB!!.localPosition = Vector3(def.foot.footLength * 0.71f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
