package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseHipFlexorPose is the single owner of the shared Hip Flexor scaffolding.
 *
 * Both family members (CouchStretch, HalfKneelingStretch) previously duplicated the
 * manual SkeletonNode construction, manual solveIK + rotAround IK, the camera literal,
 * the hand geometry and the foot-ratio constants. This base consolidates all of that and
 * delegates to the engine: SkeletonFactory for the hierarchy, BasePose.bakeIkLimb() for the
 * front-leg and arm IK (replacing manual solveIK + rotAround), and the head/pelvis construction
 * helpers for the upper body, and FootDefinition for the heel/toe ratios.
 *
 * What intentionally remains local (Bird-Dog-style pose biomechanics, not engine knowledge):
 *  - The rigid Pythagorean pelvis solver (pelvis slides along a fixed back-knee constraint).
 *  - The back-leg fixed kinematics (knee is pinned; shin is vertical or flat on the ground).
 *  - The per-variant back-foot world direction (up the wall vs. flat backward).
 */
abstract class BaseHipFlexorPose : BasePose() {

    // Shared camera (duplicated literal removed). Default pitch raised ~10% (0.22 -> 0.242)
    // so the view tilts down slightly and the upper body stays comfortably in frame
    // (yaw/zoom unchanged, no camera redesign).
    protected val hipFlexorCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.242f,
        defaultZoom = 1.3f
    )
    protected val hipFlexorGround = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

    // Counter-rotation for bakeIkLimb / rotAround, set by each variant per frame.
    protected var leanAngle = 0f

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()
    // P12 (§12.4b): scratch for the sanctioned planning solve (front-knee apex → arm targets).
    // Composition-only: never written to the carrier, never a realization result.
    private val kneePlanBuffer = SkeletonMath.IKResult()

    // Reusable scratch to avoid any per-frame allocations.
    protected val thighVecB = Vector3()
    protected val shinVecB = Vector3()
    protected val targetAnkleF = Vector3()
    protected val handTarget = Vector3()

    // Constant IK poles (allocated once).
    protected val frontLegPole = Vector3(1f, 0f, -0.5f)
    protected val armAPole = Vector3(1f, 1f, -2f)
    protected val armPPole = Vector3(1f, 1f, 2f)
    protected val backFootUpDir = Vector3(0f, 1f, 0f)   // Couch: back shin up the wall
    protected val backFootBackDir = Vector3(-1f, 0f, 0f) // Half-kneeling: back foot flat backward
    protected val uprightHeadDir = Vector3(0f, 1f, 0f)

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
     * Sets the constant upper-body local offsets after the pelvis has been anchored.
     * Uses the engine head/pelvis construction helpers to avoid duplicated head/pelvis setup.
     */
    protected fun setUpperBodyLocal(def: SkeletonDefinition) {
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildGaze(neck!!, head!!, def.neckLength, uprightHeadDir)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)
    }

    /** Front-leg intent. P12 (§12.5/B-1): the returned knee apex comes from the sanctioned
     *  planning solve (§12.4b) — consumed ONLY to compose the arm targets — never from a
     *  realization result. The front leg itself is declared through the registered bake
     *  (registration + state-gated realization), exactly like every other limb. */
    protected fun planFrontLegKnee(def: SkeletonDefinition): SkeletonMath.IKResult {
        val plan = planLimbPlacement(
            hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength,
            frontLegPole, def.legIKConstraint, kneePlanBuffer
        )
        bakeIkLimb(
            hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength,
            frontLegPole, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer
        )
        return plan
    }

    /** Both arms rest on the front knee — identical choreography for both variants.
     *
     *  R2/R4 reach-band authoring (third reach-band cleanup batch): the composed target is passed
     *  through [projectArmTargetToReach] for EACH arm — after composition, before its bake — so a
     *  variant whose own root puts the front knee beyond its arm chain's annulus can correct the
     *  declaration without re-scoping its sibling. */
    protected fun solveArmsOnKnee(kneeJointWorld: Vector3, def: SkeletonDefinition) {
        // The composed x/y are SHARED intent; each arm's z is its own. `handTarget` is a reused
        // scratch and the projection below rewrites it in place, so the shared terms are re-established
        // per arm rather than inherited from the other side's projected result.
        val targetX = kneeJointWorld.x - 10f
        val targetY = kneeJointWorld.y + 15f

        handTarget.set(targetX, targetY, -def.shoulderWidth * 0.8f)
        projectArmTargetToReach(def, shoulderA!!.worldPosition, handTarget)
        bakeIkLimb(shoulderA!!.worldPosition, handTarget, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)

        handTarget.set(targetX, targetY, def.shoulderWidth * 0.8f)
        projectArmTargetToReach(def, shoulderP!!.worldPosition, handTarget)
        bakeIkLimb(shoulderP!!.worldPosition, handTarget, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // W1: engine now derives hand orientation (removed wrist tilt counter-rotation + 6/6/10 offsets).
    }

    /**
     * R2/R4 reach-band authoring hook for the family's shared arm choreography.
     *
     * [solveArmsOnKnee] composes both hands from the front knee's planning apex — `(knee.x − 10,
     * knee.y + 15, ±0.8 · shoulderWidth)`. Whether that composed target lies inside its own chain's
     * reachable annulus `[SkeletonMath.minReach, maxReach]` depends on the VARIANT's root: the
     * half-kneeling variant's pelvis sits at `kneeBY + thighLength` with the torso upright, which
     * puts the front knee `155.160 … 166.868` u from the shoulder of a `80 + 66` u arm (measured on
     * the production entry point; the annulus cap is `143.080`), so the solver relocated both hands
     * along the authored ray instead of publishing them where the pose declared them.
     *
     * The default is the family's pre-batch behaviour (declare the composed target verbatim):
     * `CouchStretchPose` carries a site of the same class through this same helper and is out of
     * this batch's scope, so the projection is opted into per variant rather than re-scoping both
     * siblings from the base.
     */
    protected open fun projectArmTargetToReach(
        def: SkeletonDefinition,
        shoulderWorld: Vector3,
        target: Vector3
    ) {
    }

    /** Front foot flat on the floor. */
    protected fun applyFrontFoot(def: SkeletonDefinition) {
        // W1: engine now derives heel/toe + foot orientation from the shank + neutral ankle.
    }

    /** Back foot orientation. The engine now derives the back foot from the shank + the neutral
     *  ankle articulation; the intentional up-the-wall / flat-backward direction is intentionally
     *  NOT hand-authored here, leaving any visual shortfall as an exposed engine limitation. */
    protected fun applyBackFoot(worldDir: Vector3, def: SkeletonDefinition) {
    }

    protected fun finalizeHipFlexorPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /**
         * The R2/R4 projection margin — a hundredth of a percent of the chain's span.
         *
         * The authored target is placed just INSIDE its annulus, not exactly on the boundary: the
         * carrier stores coordinates at ~`3e-5` absolute resolution at these radii, so a
         * boundary-exact target re-fires a float-noise relocation (the reachability stamp reads
         * `8e-6`), while this margin makes "inside the band" hold by construction and the stamp read
         * exactly `0`.
         *
         * The published geometry cost is bounded by the margin itself (`0.004` u at the arm's
         * `minReach`), i.e. ~`1/10000` of the athlete's height, because the solver's own relocation
         * WAS the boundary projection this replaces. The helper's canonical `0.02` is NOT used: on
         * this family's `143.080` cap it would pull the hands `2.86` u further in than the engine
         * already publishes them — geometry the reach defect does not require.
         */
        const val REACH_MARGIN = 1e-4f
    }
}
