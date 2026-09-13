package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class HamstringStretchPose : BasePose() {

    // Shared camera (duplicated literal removed). Default pitch raised ~10% (0.22 -> 0.242)
    // so the view tilts down slightly and the seated forward fold stays comfortably framed
    // (yaw/zoom unchanged, no camera redesign). Mirrors the Hip Flexor audit.
    private val hamstringCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.242f,
        defaultZoom = 1.25f
    )
    private val hamstringGround = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

    // M9 — NO `metadata.support` declaration for this pose, deliberately. Its only floor contact
    // whose kind the engine derives is the foot, and the pose authors BOTH feet's articulation
    // ("Front foot points to sky, back foot lays flat sideways" — `buildAnkleArticulation`): a
    // `*_FOOT` declaration means "this whole foot rests in the surface plane", and the engine's
    // declaration-driven derivation would then drive the authored pointed foot through the floor
    // (measured: `TOE_F` 21.55 → −2.57 at p=0.5, i.e. the declaration's own derivation creates the
    // penetration). The seated pose's real support — pelvis and both legs lying on the mat — has
    // no consumable point in the current vocabulary (`HIPS`/`PELVIS`/`BACK` resolve to joints but
    // no derivation consumes them). Recorded as the pass's vocabulary gap, not silently declared.
    override val metadata = PoseMetadata(
        camera = hamstringCamera,
        durationSeconds = 3.5f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = hamstringGround
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    // Constant seated head gaze (forward + slight upward tilt), reused across frames.
    private val headDir = Vector3(0.1f, 1f, 0f).normalize()

    private fun ensureHierarchy(def: SkeletonDefinition) {
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

        // 1. Static Seated Root Anchor
        val pelvisX = -30f
        val pelvisY = 15f // Rest perfectly flat on the ground

        // Torso dynamically folds forward towards the leg
        val torsoPitch = SkeletonMath.lerp(0.1f, 0.9f, context.progress)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, -torsoPitch)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, -torsoPitch))

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildGaze(neck!!, head!!, def.neckLength, headDir)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Asymmetric Leg Geometry
        // Leg F (Front Leg): Stretched perfectly straight forward
        val targetAnkleF = Vector3(pelvisX + def.thighLength + def.shinLength - 5f, 15f, -def.hipWidth)
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        // Leg B (Tucked Leg): Ankle pulled close to groin, knee falls outwards (Side Z)
        val targetAnkleB = Vector3(pelvisX + 35f, 15f, def.hipWidth * 0.5f)
        // R2/R4 reach-band authoring (third reach-band cleanup batch) — the tucked leg's authored
        // resting place is projected onto its own chain's annulus along its own ray. Measured through
        // the production entry point at `origin/main` @ `cf8a14f`, this target sits `36.688` u from
        // the hip at EVERY frame — an interior knee angle of `18.89°` against the `IKConstraint`'s own
        // `30°` stop (`161°` of knee flexion, past the BPS's "knee flexed, foot tucked in" model), so
        // the solver relocated the realized ankle `19.321` u outward along the authored ray and pinned
        // it on the stop (`30.00°` exactly). Same class as the second batch's `DeepSquatHoldPose`
        // site (`24.80°` against the same stop): an unrealizable request the authoring cannot mean.
        // The pose's own tuck DIRECTION is preserved and only the radius moves onto the model's
        // flexion floor, which is exactly where the engine already publishes the foot — the fold, the
        // seated root, the extended leg and the arm reach are untouched.
        SkeletonMath.clampTargetToReach(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, def.legIKConstraint, targetAnkleB, REACH_MARGIN)
        // Pole vector heavily points to +Z to force the knee outwards in a seated butterfly fold
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0f, 0f, 2f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // Front foot points to sky, back foot lays flat sideways
        // Branch C: ankle articulations route through the §1.3 intent carrier.
        buildAnkleArticulation(Extremity.FOOT_F, torsoPitch - 1.57f, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, torsoPitch, 0f, ankleB!!)
        // W1: engine now derives heel/toe from the shank + these intentional ankle articulations.

        // 3. Dynamic Forward Reach
        val chestW = chest!!.worldPosition
        val startHandX = chestW.x + 30f
        val startHandY = chestW.y + 20f
        val reachX = targetAnkleF.x - 10f
        val reachY = targetAnkleF.y + 20f

        val handTargetX = SkeletonMath.lerp(startHandX, reachX, context.progress)
        val handTargetY = SkeletonMath.lerp(startHandY, reachY, context.progress)

        val targetHandA = Vector3(handTargetX, handTargetY, -def.shoulderWidth * 0.8f)
        val targetHandP = Vector3(handTargetX, handTargetY, def.shoulderWidth * 0.8f)

        // M13 — the forward reach is realized by the ARM chain, so its target must lie in that
        // chain's own reachable band at every phase of the fold. Measured on the pre-fix authoring
        // (published frame, `progress = 0`): the start hand sat `37.2108` from the shoulder —
        // INSIDE the arm's minimum-flexion reach `SkeletonMath.minReach(80, 66, 30°) = 40.1344` —
        // so the solver relocated the authored target by `2.9237` u along its own ray
        // (published `HAND_A (14.337, 155.972, −36.077)` vs declared `(11.980, 154.400, −36.800)`)
        // and the realized arm landed exactly on its `30.00°` interior-angle stop with
        // `ELBOW_A.y − SHOULDER_A.y = +64.583`, i.e. the elbows flung above the shoulders
        // (BPS §6/§11 "Shoulders are relaxed and down, not shrugged"). From `p = 0.05` the declared
        // target is already inside the band (`41.0122 … 115.1790` of the `143.0800` cap — the
        // reach is never near/beyond the arm's maximum, contrary to the audit's reading of M13), so
        // only the fold's start is corrected. Projecting the declared target onto the band (R2
        // reach-target helper — the same reachable-by-construction fix the M8 pass applied to
        // WallSlides/FacePull/ScapularRetraction) keeps the authored reach direction and makes the
        // realized hand exactly what the pose declared, with the clamp signal left live rather than
        // muted.
        SkeletonMath.clampTargetToReach(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        // Pole vectors flare elbows slightly outward and upward
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // W1: engine now derives hand orientation, cancelling the inherited chest tilt automatically
        // (removed the -torsoPitch wrist tilt-counter-rotation + the 6/6/10 offsets). A neutral wrist
        // lays the hand flat along the forearm for the forward reach.

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /**
         * R2/R4 projection margin for the tucked leg's authored resting place — a hundredth of a
         * percent of the chain's span (`0.006` u on this pose's `210` u leg).
         *
         * The tucked ankle IS the effector's real resting place, and the solver's own relocation was
         * already the boundary projection, so the margin only has to keep the target strictly inside
         * the annulus: at `3e-5` carrier resolution a boundary-exact target re-fires a float-noise
         * relocation and a non-zero stamp. The helper's canonical `0.02` would instead pull the foot
         * `1.12` u further out than the engine already publishes it — a geometry change the reach
         * defect does not require.
         *
         * The M13 arm projection above keeps the helper's canonical `0.02`: that site's own record
         * (`see HamstringForwardReachTest`) pins its `40.937` declaration, and the arms are inside
         * the band — this batch does not touch them.
         */
        const val REACH_MARGIN = 1e-4f
    }
}
