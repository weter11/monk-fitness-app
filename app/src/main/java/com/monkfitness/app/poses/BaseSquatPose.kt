package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseSquatPose — single realized geometry pipeline for the Squat family (Level 6 Family Upgrade).
 *
 * Every squat variant shares one build() pipeline: hierarchy setup, posture intent, pelvis
 * placement, chest/gaze, pelvis-build, shoulders, world transforms, and the WRIST<-HAND copy.
 * Variants override only their distinctive geometry through the open hooks below
 * (computePelvis / fillLegTargets / fillArmTargets / articulateExtras) and the pole/parameter
 * fields. This guarantees consistency across all family members (RFC_MONKENGINE_EXECUTION_MODES
 * Level 6) and removes the per-variant build() duplication that previously drifted.
 */
abstract class BaseSquatPose : BasePose() {

    abstract val squatH: Float
    abstract val pelvisXEnd: Float
    abstract val leanAngleEnd: Float
    abstract val armLeanEnd: Float

    // --- open geometry hooks (variants override only what differs) ---
    protected open fun computePelvis(progress: Float, def: SkeletonDefinition): Triple<Float, Float, Float> {
        val standH = def.shinLength + def.thighLength + 25f
        val t = progress // linear descent preserves the visual curve (no double-easing)
        return Triple(
            SkeletonMath.lerp(standH, squatH, t),
            SkeletonMath.lerp(0f, pelvisXEnd, t),
            SkeletonMath.lerp(0f, leanAngleEnd, t)
        )
    }

    protected open fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        outF.set(0f, 25f, -def.hipWidth * 1.5f)
        outB.set(0f, 25f, def.hipWidth * 1.5f)
    }

    protected open fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        val handTargetX = SkeletonMath.lerp(0f, armLeanEnd * 40f, progress)
        val handTargetY = SkeletonMath.lerp(pelvisY + def.torsoLength, pelvisY + def.torsoLength - 10f, progress)
        outA.set(handTargetX, handTargetY, -def.shoulderWidth * 1.2f)
        outP.set(handTargetX, handTargetY, def.shoulderWidth * 1.2f)
    }

    /** Optional extra articulation (e.g. jump plantar-flexion / wrist flick). No-op by default. */
    protected open fun articulateExtras(def: SkeletonDefinition, progress: Float, leanAngle: Float, footLift: Float) {}

    // --- R2/R4 reach-band authoring (see the helpers' KDoc) ---

    /**
     * R2/R4 — reach-band authoring for the family's DEFAULT geometry.
     *
     * The default hooks above author their targets in the FLOOR frame (the ankle at the definition's
     * rest height, the hands sweeping forward from the shoulder line). Measured on the production
     * entry point, two of those targets sit OUTSIDE their own chain's reachable annulus
     * `[SkeletonMath.minReach, maxReach]`: the standing-phase ankle is a locked-out leg
     * (`210.288` against the engine's `0.98` extension cap → `205.800`), and the counterbalance reach
     * never leaves a `9.2 … 15.9` unit radius (the arm chain's `minReach = 40.134`). The solver
     * answers by relocating the realized end-effector along the authored ray, so the published frame
     * is the projection and not the authoring.
     *
     * These helpers apply the reachable-by-construction convention the family already uses
     * (`SumoSquatPose`'s wide track, `BaseVerticalPullPose`'s pendulum legs): the authored target is
     * projected onto its own chain's annulus along its own ray. The projection is taken to the
     * annulus BOUNDARY (`margin = 0`) — each of these targets *is* the intended end position, and the
     * solver's own relocation is exactly that boundary projection, so the realized geometry is
     * preserved to float noise while the declared target becomes the realized position.
     *
     * Pose-side only: no solver, engine, carrier or stamp semantics change, and `maxIkClampAmount`
     * stays live for any target that is genuinely out of band.
     */
    protected fun projectLegTargetsToReach(def: SkeletonDefinition, outF: Vector3, outB: Vector3) {
        SkeletonMath.clampTargetToReach(
            hipF!!.worldPosition, outF, def.thighLength, def.shinLength, def.legIKConstraint,
            outF, REACH_MARGIN
        )
        SkeletonMath.clampTargetToReach(
            hipB!!.worldPosition, outB, def.thighLength, def.shinLength, def.legIKConstraint,
            outB, REACH_MARGIN
        )
    }

    protected fun projectArmTargetsToReach(def: SkeletonDefinition, outA: Vector3, outP: Vector3) {
        SkeletonMath.clampTargetToReach(
            shoulderA!!.worldPosition, outA, def.upperArmLength, def.forearmLength, def.armIKConstraint,
            outA, REACH_MARGIN
        )
        SkeletonMath.clampTargetToReach(
            shoulderP!!.worldPosition, outP, def.upperArmLength, def.forearmLength, def.armIKConstraint,
            outP, REACH_MARGIN
        )
    }

    // --- pole vectors (variant-tunable) ---
    protected open val legPoleF: Vector3 = Vector3(1f, 0f, -0.2f)
    protected open val legPoleB: Vector3 = Vector3(1f, 0f, 0.2f)
    protected open val armPoleA: Vector3 = Vector3(0f, -1f, -1f)
    protected open val armPoleP: Vector3 = Vector3(0f, -1f, 1f)

    // Symmetrical scratch/buffers
    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    protected val legTargetF = Vector3()
    protected val legTargetB = Vector3()
    protected val armTargetA = Vector3()
    protected val armTargetP = Vector3()

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

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val (pelvisY, pelvisX, leanAngle) = computePelvis(context.progress, def)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, -leanAngle)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, -leanAngle))

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs — variant supplies foot targets (stance width / flight lift / clamp).
        fillLegTargets(def, pelvisY, pelvisX, leanAngle, context.progress, legTargetF, legTargetB)
        val footLift = legTargetF.y - 25f // captured for articulateExtras (0 for grounded variants)

        bakeIkLimb(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, legPoleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, legPoleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // W1: engine now derives foot orientation (removed ankle tilt counter-rotation + manual heel/toe).

        // Arms — variant supplies hand targets (counterbalance reach / crotch drop / clasp / ballistic).
        fillArmTargets(def, pelvisY, pelvisX, leanAngle, context.progress, armTargetA, armTargetP)

        bakeIkLimb(shoulderA!!.worldPosition, armTargetA, def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, armTargetP, def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // W1: engine now derives hand orientation (removed wrist tilt counter-rotation + 6/6/10 offsets).

        // Optional variant articulation (jump ankle/wrist flick).
        articulateExtras(def, context.progress, leanAngle, max(0f, footLift))

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A)); jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /**
         * The R2/R4 projection margin — a hundredth of a percent of the chain's span.
         *
         * The authored target is placed just INSIDE its annulus, not exactly on the boundary: the
         * carrier stores coordinates at ~`3e-5` absolute resolution at these radii, so a
         * boundary-exact target re-fires a float-noise relocation (measured: `3e-6` outside the bound,
         * with the reachability stamp reading `8e-6`), while this margin makes "inside the band" hold by
         * construction and the relocation stamp read exactly `0` (measured `0.000000` on every frame).
         *
         * The published geometry cost is bounded by the margin itself — `0.004` u at the arm's
         * `minReach` and `0.021` u at the leg's `maxReach`, i.e. ~`1/10000` of the athlete's height.
         * The helper's canonical `0.02` is NOT used here on purpose: it would lift the standing-phase
         * ankle a further `4.12` u off this family's floor and pull the counterbalance hand `0.80` u
         * in — geometry changes the reach defect does not require (measured; see the batch record in
         * `docs/STABILIZATION_AUDIT.md`).
         */
        const val REACH_MARGIN = 1e-4f
    }
}
