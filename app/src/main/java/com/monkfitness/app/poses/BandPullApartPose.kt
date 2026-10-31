package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Band Pull-Aparts (`band_pull_aparts_standard`) — the standing banded horizontal pull: the same
 * scapular-retraction drive as the bar-supported members, authored on the standing channel.
 *
 * The hands ride an arc of constant radius [ARM_RADIUS] (`0.96 ×` the arm chain's `maxReach`) about
 * each shoulder, sweeping from [START_SWEEP] to [END_SWEEP] in the plane through the shoulders —
 * that is "straight arms" and "out to the sides" in this rig's terms. At the end of the sweep the
 * hands stand just in front of the shoulder line, so the band spans the chest's face.
 *
 * The girdle drive is depression-only (never elevation): `mistakes`' "shrugged shoulders" is an
 * elevation this pose may never author. No wrist articulation is authored, so "bent wrists" cannot
 * exist — the hand follows the forearm.
 *
 * The band itself is NOT modeled: the rig's environment vocabulary has no band primitive, so the
 * resistance is implied by the sweep rather than authored as a prop (recorded, not invented).
 */
class BandPullApartPose : BasePose() {

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var scapulaA: SkeletonNode? = null; private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var scapulaP: SkeletonNode? = null; private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        ),
        exerciseFamily = "posture",
        motionType = "Horizontal Pull",
        bodyOrientation = "Upright"
    )

    private fun ensureHierarchy() {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        scapulaA = nodes.scapulaA; shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        scapulaP = nodes.scapulaP; shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy()

        // STANDING posture: the solver owns the coarse pelvis height (seed == standH), so the
        // pose authors everything relative to the chain roots it owns.
        declarePosture(jointsBuffer, PostureIntent.Kind.STANDING)
        pelvis!!.localPosition.set(0f, 0f, 0f)
        // tech: "Keep the ribs stacked over the pelvis and avoid arching the back" — a straight trunk:
        // no pelvic tilt and no thoracic extension (one spine-intent call, the single authorized path).
        buildSpineCurve(pelvis!!, chest!!, 0f, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)

        // desc: "reinforces scapular retraction" / steps §3: "the shoulder blades squeeze together".
        // Depression only — `mistakes`' "shrugged shoulders" is an elevation the pose may never author.
        val progress = context.progress
        SkeletonMath.buildScapularRotation(SkeletonMath.lerp(0f, TOP_RETRACTION, progress), SkeletonMath.lerp(0f, TOP_DEPRESSION, progress), -1f, scapulaA!!.localRotation)
        SkeletonMath.buildScapularRotation(SkeletonMath.lerp(0f, TOP_RETRACTION, progress), SkeletonMath.lerp(0f, TOP_DEPRESSION, progress), 1f, scapulaP!!.localRotation)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        hipF!!.localPosition.set(0f, 0f, -def.hipWidth)
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val legTargetF = Vector3(0f, hipF!!.worldPosition.y - legSpan, -def.hipWidth * STANDING_STANCE)
        val legTargetB = Vector3(0f, hipB!!.worldPosition.y - legSpan, def.hipWidth * STANDING_STANCE)
        bakeIkLimb(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer)

        val sweep = SkeletonMath.lerp(START_SWEEP, END_SWEEP, progress)
        val forward = cos(sweep) * ARM_RADIUS
        val lateral = sin(sweep) * ARM_RADIUS
        val shoulderAWorld = shoulderA!!.worldPosition
        val shoulderPWorld = shoulderP!!.worldPosition
        val targetA = Vector3(shoulderAWorld.x + forward, shoulderAWorld.y, shoulderAWorld.z - lateral)
        val targetP = Vector3(shoulderPWorld.x + forward, shoulderPWorld.y, shoulderPWorld.z + lateral)
        // Reachable by construction (the targets sit at [ARM_RADIUS] from the very root they are
        // solved from); projected anyway, so a future edit of the radius is projected rather than
        // silently solver-clamped (the frame rule).
        SkeletonMath.clampTargetToReach(shoulderAWorld, targetA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetA)
        SkeletonMath.clampTargetToReach(shoulderPWorld, targetP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetP)
        // The elbows hang below the extended arm, opening a touch outboard (no authored wrist, so
        // `mistakes`' "bent wrists" cannot exist — the hand follows the forearm).
        bakeIkLimb(shoulderAWorld, targetA, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -0.25f), def.armIKConstraint, shoulderA!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderPWorld, targetP, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 0.25f), def.armIKConstraint, shoulderP!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /**
         * The straight-arm radius: `0.96 ×` the arm chain's own `maxReach` (`143.080`), i.e. the
         * chain's near-full extension — "straight arms" in this rig's terms (the chain's `0.98`
         * extension cap means even a locked-out chain measures `~157°` interior, measured).
         */
        const val ARM_RADIUS = 137.6f

        /** The narrowest straight-arm sweep: the hands in front of the shoulders, band at the sternum. */
        const val START_SWEEP = 0.2618f

        /** A quarter turn of the extended arm: the hands out at the sides, the band across the chest. */
        const val END_SWEEP = 1.2566f

        /** desc's "scapular retraction": the shared girdle activation units (as the vertical-pull family). */
        const val TOP_RETRACTION = 4f
        const val TOP_DEPRESSION = 1f

        /** The standing stance this family uses (`1.2 × hipWidth`). */
        const val STANDING_STANCE = 1.2f
    }
}
