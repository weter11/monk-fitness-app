package com.monkfitness.app.animation

/**
 * Generic interface for skeleton body proportions and anatomical metadata.
 */
interface SkeletonDefinition {
    val torsoLength: Float
    val neckLength: Float
    val thighLength: Float
    val shinLength: Float
    val footLength: Float
    val foot: FootDefinition
    val upperArmLength: Float
    val forearmLength: Float
    val hand: HandDefinition
    val shoulderWidth: Float
    val hipWidth: Float
    val defaultCamera: CameraDefinition

    // Biomechanical constraints
    val armIKConstraint: IKConstraint
    val legIKConstraint: IKConstraint

    // Angular joint-limit vocabulary (shared, general — never per-exercise magic numbers).
    // Carried by the definition so the solver and validator read a single source of truth.
    val armAngularLimits: AngularJointLimits
        get() = armIKConstraint.angularLimits
    val legAngularLimits: AngularJointLimits
        get() = legIKConstraint.angularLimits

    // Hip (acetabular ball-and-socket) range of motion. Named, shared human-range caps — the
    // single source of truth for UNI-3's over-range-hip detection (validator + optional clamp).
    val hipRomLimits: HipRomLimits
        get() = HipRomLimits.DEFAULT

    // Phase 10 (R13) — the definition's anatomical forward axis. RFC §5 R13: the default axis
    // of Spine Intent is defined by the Skeleton Definition's anatomical axes, not by call-site
    // defaults; call-site defaults derive from this property (the authoring base resolves its
    // axis-less spine default through it). Deliberately abstract: every definition must state
    // its own anatomical forward — there is no inherited fallback that would let the owner
    // silently revert to a call-site constant.
    val anatomicalForward: Vector3

    companion object {
        val DEFAULT_ADULT: SkeletonDefinition = HumanSkeletonDefinition()
    }
}

/**
 * Default implementation for the Monk Fitness human model.
 */
data class HumanSkeletonDefinition(
    override val torsoLength: Float = 120f,
    override val neckLength: Float = 18f,
    override val thighLength: Float = 112f,
    override val shinLength: Float = 98f,
    override val footLength: Float = 35f,
    override val foot: FootDefinition = FootDefinition(footLength),
    override val upperArmLength: Float = 80f,
    override val forearmLength: Float = 66f,
    override val hand: HandDefinition = HandDefinition(),
    override val shoulderWidth: Float = 46f,
    override val hipWidth: Float = 22f,
    override val defaultCamera: CameraDefinition = CameraDefinition.DEFAULT,

    override val armIKConstraint: IKConstraint = IKConstraint.ArmConstraint,
    override val legIKConstraint: IKConstraint = IKConstraint.LegConstraint,
    override val hipRomLimits: HipRomLimits = HipRomLimits.DEFAULT
) : SkeletonDefinition {
    // Phase 10 (R13): the human model's anatomical forward is the historical +Z call-site
    // default (bit-exact), so standard-definition axis-less spine authoring stays unchanged.
    // A class-body property (not a data-class constructor parameter): adding one to the
    // primary constructor would silently widen equals/hashCode/copy — out of P10's scope.
    override val anatomicalForward: Vector3 = Vector3(0f, 0f, 1f)
}
