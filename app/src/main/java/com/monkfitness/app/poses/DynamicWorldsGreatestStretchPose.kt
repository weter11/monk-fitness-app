package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Dynamic World's Greatest Stretch — rewritten thoracic rotation (lunge foundation reused).
 *
 * The runner's-lunge base (front foot flat, back leg extended, toe on floor, torso leaning
 * forward) is retained. The thoracic rotation is now real: the chest node rotates about the
 * spine's long axis so the rib cage, neck, head and shoulders all turn together, and the
 * reaching arm lives in the chest's rotating frame so the hand sweeps from the low instep to
 * the sky BECAUSE the thorax rotates — not because the shoulder is animated on its own. The
 * support hand is pinned to the floor as a stable pillar. (The dead WorldGreatestStretchPose's
 * correct "rotate about the spine axis, head follows" concept is realized here via the chest
 * node and then the dead class is removed.)
 */
class DynamicWorldsGreatestStretchPose : BaseThoracicPose() {

    // Head follows the rotation (gaze up, slightly forward, toward the active side) — constant.
    private val headDir = Vector3(0.3f, 1.0f, -0.2f).normalize()

    override val metadata = PoseMetadata(
        camera = thoracicCamera(0.24f, 1.25f),
        durationSeconds = 3.8f,
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

        // Runner's-lunge anchoring: torso leans forward over the front knee.
        val leanAngle = 0.5f
        spinePitch = -leanAngle
        val pelvisX = -10f
        val pelvisY = 55f

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, spinePitch)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, spinePitch))

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Thoracic rotation about the spine (chest-local +Y). Rib cage/neck/head/shoulders follow.
        val twist = lerp(-0.2f, 1.4f, progress)
        buildChestTwist(chest!!, twist)

        buildGaze(neck!!, head!!, def.neckLength, headDir)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs: front foot flat forward, back leg extended with toe on the floor.
        targetF.set(pelvisX + 95f, 15f, -def.hipWidth)
        targetB.set(pelvisX - 120f, 15f, def.hipWidth)
        poleF.set(1f, 0.2f, -0.2f); poleB.set(0.1f, -1f, 0.2f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // Feet: front foot flat (toe forward), back foot plantar-flexed (toe down).
        // W1: the inherited torso/spine tilt is now removed by the engine, so only the intentional
        // back-foot plantar flexion remains (front foot lays flat via engine derivation).
        val footPitchB = 0.6f
        ankleB!!.localRotation.set(axisZ, -footPitchB)
        // W1: engine now derives heel/toe from the shank + these ankle articulations.

        // Support arm (P): planted on the floor beside the front foot -> stable pillar.
        targetP.set(targetF.x * 0.5f, 0f, def.shoulderWidth)
        poleP.set(1f, 1f, 2f)
        bakeThoracicArm(shoulderP!!.worldPosition, targetP, def, poleP, elbowP!!, handP!!, armPBuffer)

        // Reaching arm (A): sweeps from low (front instep) up to the sky, FOLLOWING the
        // thorax rotation (target lives in the chest's rotating frame).
        reachLocal.set(lerp(0.5f, -0.1f, progress), lerp(-0.4f, 1.3f, progress), -0.35f).normalize()
        val reachLen = (def.upperArmLength + def.forearmLength) * 0.85f
        reachWorld.set(reachLocal.x * reachLen, reachLocal.y * reachLen, reachLocal.z * reachLen)
        chestLocalToWorld(reachWorld, targetA)
        poleA.set(1f, 1f, -2f)
        if (targetA.y < 6f) targetA.y = 6f
        bakeThoracicArm(shoulderA!!.worldPosition, targetA, def, poleA, elbowA!!, handA!!, armABuffer)

        applyThoracicHands()
        return finalizeThoracicPose()
    }
}
