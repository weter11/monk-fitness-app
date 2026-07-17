package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Thoracic Extension — full rewrite for natural kneeling thoracic mobility.
 *
 * Tall kneeling: thighs vertical, shins FLAT on the floor, ankles and toes planted,
 * pelvis neutral (no forward shift, no lumbar contribution). The extension originates
 * in the thoracic spine: the chest node arches up and back about the lateral (Z) axis,
 * the rib cage opens upward, the head follows the extension, and the hands rest behind
 * the head with the elbows flared outward to open the chest. The previous implementation
 * floated the knees/shins/feet off the floor and drove the lumbar into extension; both
 * are corrected here.
 */
class ThoracicExtensionPose : BaseThoracicPose() {

    // Head follows the extension (looks up and slightly back) — constant, rotates with thorax via FK.
    private val headDir = Vector3(-0.12f, 1.0f, 0f).normalize()

    override val metadata = PoseMetadata(
        camera = thoracicCamera(0.26f, 1.35f),
        durationSeconds = 3.2f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = thoracicGround
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val progress = context.progress

        // Tall kneeling: pelvis sits directly above the knees, neutral (no tilt, no shift).
        spinePitch = 0f
        val kneeFloorX = 0f
        val kneeFloorY = 15f
        val pelvisX = kneeFloorX
        val pelvisY = kneeFloorY + def.thighLength

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, 0f)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, 0f))

        // Thoracic extension about the lateral (Z) axis. The arch originates in the spine BELOW
        // the chest (the thoracolumbar junction), so the chest node itself tips up and BACK (-X)
        // and carries the neck/head/shoulders with it — that is what makes the rib cage open and
        // the gaze travel backward. Driving the chest's own localRotation alone would rotate the
        // children in place but never translate the chest, so the extension would be invisible at
        // the CHEST/HEAD joints (the previous defect).
        val extAngle = lerp(0f, 0.5f, progress)
        lumbar!!.localPosition.set(0f, 0f, 0f)
        lumbar!!.localRotation.set(axisZ, extAngle)
        declareJointIntent(Joint.LUMBAR, JointRotation(axisZ, extAngle))

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // A small additional chest-local extension keeps the upper rib cage opening relative to
        // the lower spine (the thorax is not perfectly rigid with the lumbar segment).
        chest!!.localRotation.set(axisZ, extAngle * 0.4f)
        declareJointIntent(Joint.CHEST, JointRotation(axisZ, extAngle * 0.4f))

        buildGaze(neck!!, head!!, def.neckLength, headDir)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs: knees and shins FIXED on the floor (no floating). Thighs vertical, shins flat back.
        targetF.set(pelvisX, kneeFloorY, -def.hipWidth)
        targetB.set(pelvisX, kneeFloorY, def.hipWidth)
        val thighVecF = Vector3(targetF.x - pelvis!!.worldPosition.x, targetF.y - pelvis!!.worldPosition.y, 0f)
        val thighVecB = Vector3(targetB.x - pelvis!!.worldPosition.x, targetB.y - pelvis!!.worldPosition.y, 0f)
        val shinVec = Vector3(-def.shinLength, 0f, 0f)
        kneeF!!.localPosition.set(thighVecF)
        kneeB!!.localPosition.set(thighVecB)
        ankleF!!.localPosition.set(shinVec)
        ankleB!!.localPosition.set(shinVec)

        // Feet flat on the floor, toes pointing back (no plantar flexion, no sky-pointing).
        ankleF!!.localRotation.set(axisZ, 0f)
        ankleB!!.localRotation.set(axisZ, 0f)
        // W1: engine now derives heel/toe from the (vertical) shank + neutral ankle.

        // Arms: hands behind the head, elbows flared outward/up to open the chest.
        val neckW = neck!!.worldPosition
        targetP.set(neckW.x - 12f, neckW.y + 6f, def.shoulderWidth * 0.55f)
        poleP.set(0f, 0.6f, 2f)
        bakeThoracicArm(shoulderP!!.worldPosition, targetP, def, poleP, elbowP!!, handP!!, armPBuffer)

        targetA.set(neckW.x - 12f, neckW.y + 6f, -def.shoulderWidth * 0.55f)
        poleA.set(0f, 0.6f, -2f)
        bakeThoracicArm(shoulderA!!.worldPosition, targetA, def, poleA, elbowA!!, handA!!, armABuffer)

        applyThoracicHands()
        return finalizeThoracicPose()
    }
}
