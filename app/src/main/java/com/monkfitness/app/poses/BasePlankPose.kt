package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BasePlankPose is the single owner of all shared Plank-family scaffolding.
 *
 * The Plank family is a *biomechanics-first rewrite* (not a modernization of the
 * old rigid-object math). Both members — the prone forearm plank and the lateral
 * side plank — are isometric, floor-supported holds. What they genuinely share is
 * engine plumbing, not choreography:
 *
 *  - Skeleton hierarchy via [SkeletonFactory.createStandardSkeleton]
 *  - Reusable, allocation-free IK buffers and scratch targets/poles
 *  - IK baking via [BasePose.bakeIkLimb] (replaces manual solveIK + rotAround)
 *  - A breathing/stabilization micro-driver that is *zero at the pose endpoints*
 *    so the entering/exiting contract (pelvis 15 → 35) is preserved exactly
 *  - Shared camera + ground environment
 *  - Finalization (FK flatten, wrist mirroring, IK-clamp reporting)
 *
 * Everything that differs — prone vs. rolled anchoring, two-forearm vs.
 * single-forearm support, straight legs vs. stacked legs, scapular behaviour —
 * lives in the concrete variant, because it is Plank biomechanics, not engine
 * knowledge. No speculative helpers are added here.
 */
abstract class BasePlankPose : BasePose() {

    // --- Shared vertical contract (tests assert pelvis 15f resting -> 35f held) ---
    protected val restingPelvisY = 15f
    protected val plankPelvisY = 35f

    // Height at which a planted forearm / plantar-flexed toe rests on the mat.
    // Mirrors FootDefinition.ankleHeight so limbs sit *on* the ground, not through it.
    protected val contactY = 15f

    /**
     * Shared camera + environment (single source — no per-variant literals).
     *
     * Pitch lowered 0.22 -> 0.16 so the eye travels *along* the near-horizontal
     * plank line instead of looking down onto it, and zoom widened 1.30 -> 1.22
     * so the long forearm-to-toe span stays framed. Yaw unchanged (no re-aim).
     */
    protected val plankCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.16f,
        defaultZoom = 1.22f
    )
    protected val plankEnvironment = EnvironmentDefinition(
        ground = GroundDefinition(visible = true, level = 0f)
    )

    // --- Skeleton nodes (bound once via SkeletonFactory) ---
    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    // --- Reusable IK result buffers (no per-frame allocation) ---
    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    // --- Reusable scratch targets / poles ---
    protected val targetA = Vector3(); protected val targetP = Vector3()
    protected val targetF = Vector3(); protected val targetB = Vector3()
    protected val poleA = Vector3(); protected val poleP = Vector3()
    protected val poleF = Vector3(); protected val poleB = Vector3()
    protected val scratchShoulderA = Vector3(); protected val scratchShoulderP = Vector3()

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
     * Breathing / postural-stabilization micro-driver in [0, 1].
     *
     * It is a half-sine of progress, so it is exactly **0 at progress 0 and 1**.
     * That guarantees the entering/exiting contract (pelvis 15 -> 35, planted
     * supports settled) is untouched at the endpoints, while the mid-hold gets a
     * gentle rib-cage swell and weight-shift that reads as a living, stabilizing
     * body rather than a frozen statue. It never snaps because PING_PONG feeds a
     * FastOutSlowIn progress and this curve is smooth with zero endpoint velocity.
     */
    protected fun breathingSwell(progress: Float): Float = sin(progress * PI.toFloat())

    /**
     * Flattens the hierarchy, mirrors wrist joints onto the hand joints (the
     * renderer expects WRIST_*), and surfaces the worst IK clamp for validation.
     */
    protected fun finalizePlankPose(): SkeletonPose {
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
