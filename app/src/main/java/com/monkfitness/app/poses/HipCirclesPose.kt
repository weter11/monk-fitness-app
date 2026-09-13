package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Hip Circles (`hip_circles_hold`) — the standing **pelvic-circle** mobility drill, authored on the
 * canonical `SkeletonFactory` tree through [BasePose] (the same tree, declared-pelvis-tilt and
 * registered package bake limbs every migrated family uses).
 *
 * ## What the exercise is, and where that comes from
 *
 * **There is no BPS for this exercise and no exercise copy either**: `WorkoutGenerator` passes
 * `R.string.ex_hip_circles` as *every* field (name/description/technique/steps), so the catalog entry
 * carries the title and nothing else. The identity is therefore derived from the two channels that do
 * state it, and this is the one authored-by-convention decision of this pose — recorded rather than
 * presented as specification:
 *
 *  1. **The localized names name the PELVIS, not the leg.** `values-ru` `ex_hip_circles` =
 *     *"Круговые движения тазом"* ("circular movements of the pelvis"), `values-uk` =
 *     *"Обертання тазом"* ("rotation of the pelvis"). Two independent translations agree that the
 *     subject of the circling is the hip/pelvis complex.
 *  2. **The catalog already owns the leg-circling drill** — `hip_cars_standard`
 *     (`HipCarsPose`) is the standing single-leg controlled articular rotation, and its own copy says
 *     so: `ex_hip_cars_desc` *"A slow hip circle that builds control through flexion, rotation, and
 *     extension. Treat each rep like a mobility drill, **not a swing**."* Authoring `hip_circles` as
 *     another leg circle would duplicate that pose and contradict the catalog's own separation of the
 *     two exercises (they are also filed under different sub-categories: `hip_circles` → `HIPS`).
 *
 * So the movement authored here is the classic pelvic circle: **both feet planted, standing tall, and
 * the pelvis describing a circle in the horizontal plane** while the trunk rides upright over it and
 * the two legs absorb the displacement. The exercise's own name is the specification; nothing about
 * the movement is invented beyond its radius/depth, which are recorded below as authored constants.
 *
 * ## The motion
 *
 *  * The pelvis's world `(x, z)` traces a **circle** of radius [CIRCLE_RADIUS] centred on the
 *    stance: `px = R·cos φ`, `pz = R·sin φ`, `φ = 2π·progress`. Equal amplitude on both axes with a
 *    quarter-cycle phase difference is what makes it a circle rather than a sway (the test measures
 *    the locus and the accumulated sweep angle), and one full cycle per rep closes the loop
 *    (`progress 0 ≡ 1`).
 *  * Its height is **constant** ([STANCE_PELVIS_Y]) — the hips circle, they do not bob — so the
 *    circle is a horizontal one and the legs' work is a length change, not a lift.
 *  * The feet do **not** move: both are declared (`LEFT_FOOT`/`RIGHT_FOOT`) and their ankle targets
 *    are fixed for the whole cycle, so the engine derives their headings and flattening against the
 *    floor they stand on. A pelvic circle that dragged the feet would be a step, not a circle.
 *  * The trunk stays upright ([declarePelvisTilt] with no lean) — the hips travel *under* the trunk,
 *    which is what makes the drill a hip mobility drill rather than a whole-body sway.
 *  * Hands rest on the hips for the whole cycle, travelling with the pelvis they are planted on.
 *
 * ## Physically coherent root (measured, not asserted by prose)
 *
 * The COM stays over the base of support by construction: the feet are [FOOT_Z] out and ±`footLength`
 * fore/aft of the mid-line, while the pelvis moves at most `R` = [CIRCLE_RADIUS] in any direction —
 * inside the stance on both axes. The legs never straighten past their own band: the worst hip→ankle
 * chord over the whole circle is `sqrt(201² + (R + 4.4)²)` = **203.02 u** against the leg chain's
 * `205.80 u` extension cap, i.e. the pelvis depth is authored so a locked-out leg is impossible at
 * every phase (the reach stamp reads `0` — no solver relocation anywhere in the cycle).
 *
 * ## Recorded gaps
 *
 *  * The stance's exact width and depth are **not** in the catalog (no copy exists); they are
 *    authored constants chosen so the circle is realizable without clamping, and are stated as such
 *    at their declarations.
 *  * Applying the classic cue *"hands on the hips"* is a convention too — the copy says nothing —
 *    but it is the drill's own convention and it keeps the arms out of the pelvis's path.
 */
class HipCirclesPose : BasePose() {

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.25f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        ),
        exerciseFamily = "hip_mobility",
        motionType = "Circular Mobility",
        bodyOrientation = "upright"
    )

    private fun ensureHierarchy() {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB
    }

    /** The pelvis's own circle: the drill's whole content, one statement each for its two axes. */
    private fun circleX(progress: Float): Float = CIRCLE_RADIUS * cos(progress * TWO_PI)

    private fun circleZ(progress: Float): Float = CIRCLE_RADIUS * sin(progress * TWO_PI)

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy()
        // Shape-driven root (the circle is authored arithmetic), so the solver leaves it untouched.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val px = circleX(context.progress)
        val pz = circleZ(context.progress)

        pelvis!!.localPosition.set(px, STANCE_PELVIS_Y, pz)
        // The trunk is carried upright by the root for the whole cycle: the hips travel under it.
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // The head continues the spine's line (upright body, no forward gaze to author).
        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        // The stance: both ankles pinned on the family's floor frame, through the whole circle.
        val footZ = footZ(def)
        val targetAnkleF = Vector3(FOOT_X, FLOOR_ANKLE_Y, -footZ)
        val targetAnkleB = Vector3(FOOT_X, FLOOR_ANKLE_Y, footZ)
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, legPoleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, legPoleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // The feet keep their own line for the whole drill (the drill moves the hips, not the feet).
        setHeading(Extremity.FOOT_F, Vector3(1f, 0f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(1f, 0f, 0f))

        // Hands on the hips: they ride the pelvis they are planted on (both axes of its circle).
        val hipLineY = STANCE_PELVIS_Y + HAND_ABOVE_HIP
        bakeIkLimb(shoulderA!!.worldPosition, Vector3(px + HAND_FORWARD, hipLineY, pz - (def.hipWidth + HAND_OUTBOARD)), def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, Vector3(px + HAND_FORWARD, hipLineY, pz + def.hipWidth + HAND_OUTBOARD), def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        private const val TWO_PI = 2f * PI.toFloat()

        /**
         * The circle's radius, in the horizontal plane: the pelvis travels `2R` = `48 u` peak-to-peak
         * (≈ `15 %` of this rig's stature) — inside the stance on both axes, so the COM stays over the
         * base of support, and small enough that the legs' hip→ankle chord never reaches their
         * extension cap (measured worst case `203.02 u` of `205.80`).
         */
        const val CIRCLE_RADIUS = 24f

        /**
         * The standing height: `201 u` of leg drop under the hip. `226 − 25 = 201` of the leg chain's
         * `210 u` span, i.e. a light athletic bend that leaves every phase of the circle inside the
         * `0.98` extension cap — a locked-out stance could not circle its own pelvis.
         */
        const val STANCE_PELVIS_Y = 226f

        /** The planted ankles: the family's floor frame, a little wider than the hips. */
        private const val FOOT_X = 0f
        private const val FLOOR_ANKLE_Y = 25f

        /** `1.2 × hipWidth` each side of the mid-line (the corpus's narrow standing stance). */
        fun footZ(def: SkeletonDefinition): Float = def.hipWidth * 1.2f

        private const val HAND_FORWARD = 6f
        private const val HAND_ABOVE_HIP = 6f
        private const val HAND_OUTBOARD = 5f

        private val legPoleF = Vector3(1f, 0f, -0.2f)
        private val legPoleB = Vector3(1f, 0f, 0.2f)
        private val armPoleA = Vector3(0f, -1f, -1f)
        private val armPoleP = Vector3(0f, -1f, 1f)
    }
}
