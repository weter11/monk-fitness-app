package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Hip Circles (`hip_circles_hold`) — the standing **pelvic-circle** mobility drill on the canonical
 * `SkeletonFactory` tree through [BasePose].
 *
 * **There is no BPS for this exercise and no exercise copy either**: `WorkoutGenerator` passes
 * `R.string.ex_hip_circles` as *every* field, so the catalog entry carries the title and nothing else. The
 * identity is derived from the two channels that do state it: the localized names name the PELVIS
 * (`values-ru` "circular movements of the pelvis", `values-uk` "rotation of the pelvis"), and the catalog
 * already owns the leg-circling drill (`hip_cars_standard` / `HipCarsPose`), so authoring `hip_circles` as
 * another leg circle would duplicate that pose. The exercise's own name is the specification; nothing about
 * the movement is invented beyond its radius/depth, recorded below as authored constants.
 *
 * The pelvis's world `(x, z)` traces a **circle** of radius [CIRCLE_RADIUS] centred on the stance — equal
 * amplitude on both axes with a quarter-cycle phase difference is what makes it a circle rather than a sway,
 * and one full cycle per rep closes the loop. Its height is constant ([STANCE_PELVIS_Y]): the hips circle,
 * they do not bob, so the circle is horizontal and the legs' work is a length change, not a lift. The feet
 * do NOT move (both declared, ankle targets fixed) — a pelvic circle that dragged the feet would be a step,
 * not a circle. The trunk stays upright ([declarePelvisTilt] with no lean), so the hips travel *under* it,
 * and the hands rest on the hips, travelling with the pelvis they are planted on.
 *
 * The root is physically coherent by construction: the COM stays over the base of support (the feet are
 * [FOOT_Z] out and ±`footLength` fore/aft, while the pelvis moves at most `R` on either axis), and the worst
 * hip→ankle chord over the whole circle is `203.02 u` against the leg chain's `205.80 u` cap — a locked-out
 * leg is impossible at every phase, and the reach stamp reads `0`.
 *
 * Recorded gaps: the stance's exact width and depth are not in the catalog, so they are authored constants
 * chosen so the circle is realizable without clamping (stated at their declarations); "hands on the hips"
 * is a convention too — the copy says nothing — but it is the drill's own and keeps the arms out of the
 * pelvis's path.
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
