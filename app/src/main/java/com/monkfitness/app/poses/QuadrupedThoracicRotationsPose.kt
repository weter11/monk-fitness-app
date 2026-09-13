package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Quadruped Thoracic Rotations — rewritten for correct biomechanics.
 *
 * The motion is now driven by the rib cage: the chest node rotates about the spine's
 * long axis (chest-local +Y), so the rib cage, neck, head and BOTH shoulders rotate
 * together as one segment. The supporting hand is pinned to the floor (a stable pillar),
 * while the reaching hand lives in the chest's rotating frame so the arm follows the
 * thorax as it threads under and opens to the sky. The pelvis and both planted legs
 * stay fixed in tabletop.
 */
class QuadrupedThoracicRotationsPose : BaseThoracicPose() {

    // Gaze up, slightly forward, toward the active side (constant — follows the thorax via FK).
    private val headDir = Vector3(0.3f, 1.0f, -0.25f).normalize()

    override val metadata = PoseMetadata(
        camera = thoracicCamera(0.24f, 1.2f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = thoracicGround
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val progress = context.progress

        // Tabletop anchoring: spine flat to the floor, pelvis stable.
        spinePitch = -PI.toFloat() / 2f
        val basePelvisX = -20f
        val basePelvisY = 127f

        pelvis!!.localPosition.set(basePelvisX, basePelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, spinePitch)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, spinePitch))

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Thoracic rotation about the spine (chest-local +Y). Negative twist lifts the
        // active (-Z) shoulder up toward the sky; positive tucks it down/under.
        val twist = lerp(0.35f, -1.35f, progress)
        buildChestTwist(chest!!, twist)

        buildGaze(neck!!, head!!, def.neckLength, headDir)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs: tabletop — knees and shins planted on the floor behind the hips.
        targetF.set(basePelvisX - def.shinLength, 15f, -def.hipWidth)
        targetB.set(basePelvisX - def.shinLength, 15f, def.hipWidth)
        poleF.set(0f, -1f, -0.5f); poleB.set(0f, -1f, 0.5f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        val chestW = chest!!.worldPosition

        // Support arm (P): hand pinned to the floor directly under the support shoulder -> stable pillar.
        targetP.set(chestW.x, 0f, def.shoulderWidth)
        poleP.set(0f, -1f, 1f)
        bakeThoracicArm(shoulderP!!.worldPosition, targetP, def, poleP, elbowP!!, handP!!, armPBuffer)

        // Reaching arm (A): the horizontal reach follows the thorax (target in the chest's
        // rotating frame), while the vertical component is authored in world space so the hand
        // demonstrably sweeps from threaded-under (low) at the start to reaching-for-the-sky
        // (high) at the end — the defining biomechanics of the drill.
        reachLocal.set(0.35f, 0f, -0.45f).normalize()
        val reachLen = (def.upperArmLength + def.forearmLength) * 0.82f
        reachWorld.set(reachLocal.x * reachLen, 0f, reachLocal.z * reachLen)
        chestLocalToWorld(reachWorld, targetA)
        // World-space vertical sweep: low (threaded under the torso) -> high (open to the sky).
        targetA.y = lerp(20f, chestW.y + reachLen * 0.9f, progress)
        poleA.set(0.2f, -0.6f, -1f)
        if (targetA.y < 6f) targetA.y = 6f
        bakeThoracicArm(shoulderA!!.worldPosition, targetA, def, poleA, elbowA!!, handA!!, armABuffer)

        // Extremities
        applyThoracicHands()
        // W1: engine now derives foot/hand orientation (removed tilt counter-rotation + endpoints).

        return finalizeThoracicPose()
    }

    /**
     * R2/R4 — reach-band authoring (fourth reach-band cleanup batch).
     *
     * This pose's two arms are its whole rep, and BOTH of them were authored outside their own
     * chain's annulus at the opposite ends of that rep. Measured through the production entry point
     * (`SkeletonPipeline.produceFrame(pose, ctx)`) at `origin/main` @ `ca011ad`, `15` phases × both
     * arms:
     *
     *  * the SUPPORT arm (P, the authored floor pillar) runs `111.2617` … `175.5976` u from its own
     *    shoulder — the last request is `120.3 %` of the `80 + 66 = 146` u limb, i.e. impossible for
     *    ANY chain geometry, because the chest twist carries the shoulder up and away while the
     *    target stays pinned at the authored floor point. The solver answered by relocating the
     *    stabilized hand up to `32.5176` u along the ray at `8` of the `15` phases;
     *  * the REACHING arm (A) runs `22.9198` (p = 0.40) … `164.4045` (p = 1.00). Its composition
     *    builds the reach in the chest's rotating frame at
     *    `0.82 · (L1 + L2) = 119.7` u and then OVERWRITES its `y` with the world-space sweep value
     *    (`lerp(20, chestY + reachLen·0.9)`), so the resulting chord is no longer the intended
     *    radius: it collapses to a hand AT the shoulder at mid-sweep (the drill's own choreography
     *    sends the hand UNDER the torso and then overhead — BPS §6/§9 "reaches under the body
     *    (threading) then sweeps overhead/upward") and grows past the limb at the end.
     *
     * Verdict: unintended authoring error on BOTH sites, not an intentional ROM limit. Neither
     * request can describe what the pose means: a "stable pillar" arm is an EXTENDED arm (BPS §6
     * "Supporting (down) arm: extended, shoulder stable"), so its request belongs inside the band,
     * and a hand that threads under the torso cannot be a hand at the shoulder. The projection
     * preserves each arm's authored ray and only moves the radius onto the annulus — exactly the
     * position the solver already published — so the pose's root, the tabletop stance, the leg
     * targets (`148.8220`, in band at every phase) and the thorax-driven sweep are untouched.
     */
    override fun projectArmTargetToReach(
        def: SkeletonDefinition,
        shoulderWorld: Vector3,
        target: Vector3
    ) {
        SkeletonMath.clampTargetToReach(
            shoulderWorld, target, def.upperArmLength, def.forearmLength, def.armIKConstraint,
            target, REACH_MARGIN
        )
    }
}
