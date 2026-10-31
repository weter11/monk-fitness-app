package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Shoulder CARs (`shoulder_cars_standard`) — one shoulder's controlled articular rotation. There is
 * no BPS for it: the catalogue `desc`/`steps`/`tech`/`mistakes` copy is the only spec.
 *
 * The working girdle is left NEUTRAL: the thorax is unauthored here, so a one-sided clavicular
 * elevation is read back into a `5.4°` thorax roll that would break "keep the torso quiet" — the
 * scapular component is recorded rather than faked.
 */
class ShoulderCarsPose : BasePose() {

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null
    private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()
    private val restDir = Vector3()

    override val metadata = PoseMetadata(
        camera = STANDING_CAMERA,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = STANDING_GROUND,
        support = standingStance,
        exerciseFamily = "shoulder_mobility",
        motionType = "Controlled Articular Rotation",
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

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy()
        // Shape-driven root (the stance and the arc are authored arithmetic), so the solver leaves
        // the authored root untouched.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        pelvis!!.localPosition.set(0f, STANCE_PELVIS_Y, 0f)
        // "Keep the torso quiet": the root is vertical and, like the hips, never travels.
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // A neutral cervical chain, authored in the chain's own frame: both bones are rigid and the
        // published frame shows their authored direction, so an upright head is one statement per bone.
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, HEAD_BONE_LENGTH, 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        val footZ = footZ(def)
        bakeIkLimb(
            hipF!!.worldPosition, Vector3(0f, FLOOR_ANKLE_Y, -footZ), def.thighLength, def.shinLength,
            legPoleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer
        )
        bakeIkLimb(
            hipB!!.worldPosition, Vector3(0f, FLOOR_ANKLE_Y, footZ), def.thighLength, def.shinLength,
            legPoleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer
        )
        // The feet keep their own line for the whole drill (the drill moves one shoulder, not the body).
        setHeading(Extremity.FOOT_F, Vector3(1f, 0f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(1f, 0f, 0f))

        val shoulderWorld = shoulderA!!.worldPosition
        bakeIkLimb(
            shoulderWorld,
            handTarget(shoulderWorld, azimuth(context.progress)),
            def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint,
            chest!!.worldRotation, elbowA!!, handA!!, armABuffer
        )

        armRestDirection(restDir)
        val shoulderPWorld = shoulderP!!.worldPosition
        bakeIkLimb(
            shoulderPWorld,
            Vector3(
                shoulderPWorld.x + restDir.x * ARM_REST_RADIUS,
                shoulderPWorld.y + restDir.y * ARM_REST_RADIUS,
                shoulderPWorld.z + restDir.z * ARM_REST_RADIUS
            ),
            def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint,
            chest!!.worldRotation, elbowP!!, handP!!, armPBuffer
        )

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /** A hanging arm's direction: a hair forward of the shoulder's vertical, that side outboard. */
    private fun armRestDirection(out: Vector3): Vector3 {
        val f = 0.08f
        val o = 0.03f
        val len = sqrt(f * f + 1f + o * o)
        out.set(f / len, -1f / len, REST_SIDE_SIGN * o / len)
        return out
    }

    companion object {
        /** The standing family's own camera (`ArmCirclesPose`/`HipCirclesPose`); framing is not tuned here. */
        val STANDING_CAMERA = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f)

        val STANDING_GROUND = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

        /** The planted-feet base, on the ONE canonical support channel (`metadata.support`). */
        val standingStance = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )

        /**
         * The standing height: `226 u`, i.e. `201 u` of leg drop under the hip — the light athletic
         * stance the standing corpus publishes (`HipCirclesPose`/`ArmCirclesPose`). The authored chord
         * measures `201.05 u` of the leg chain's `205.80 u` cap, so the stance is reachable by
         * construction and the drill's stillness is not bought from the solver.
         */
        const val STANCE_PELVIS_Y = 226f

        /** The planted ankles: the standing family's floor frame (`HipCirclesPose` authors the same). */
        const val FLOOR_ANKLE_Y = 25f

        /** The head bone's authored length — the engine's own `NECK_END → HEAD_POS` length. */
        const val HEAD_BONE_LENGTH = 18f

        /** The hanging arm's radius: `140 u` of the `80 + 66 = 146 u` chain (a relaxed arm). */
        const val ARM_REST_RADIUS = 140f

        /**
         * The realised shoulder→hand distance the arc is authored at: `137.6 u`, the arm chain's own
         * **straight-arm** convention (`BandPullApartPose.ARM_RADIUS = 0.96 × maxReach = 137.6 u`),
         * `5.48 u` inside the chain's `0.98` extension cap (`143.08 u`). The hand's own outboard plane
         * offset ([CAR_OUTBOARD]) is the other leg of that distance, so [CAR_RADIUS] is the arc's own
         * in-plane radius: `sqrt(137.6² − 8²) = 137.37 u`.
         */
        const val STRAIGHT_ARM_RADIUS = 137.6f

        /**
         * How far outboard of the shoulder's own sagittal plane the hand travels (`8 u`): the arc
         * passes *behind* the body, so the plane must clear the ribcage. Authored; the copy says
         * nothing about it.
         */
        const val CAR_OUTBOARD = 8f

        val CAR_RADIUS: Float = sqrt(STRAIGHT_ARM_RADIUS * STRAIGHT_ARM_RADIUS - CAR_OUTBOARD * CAR_OUTBOARD)

        /**
         * The sticky-spot schedule: `20°` of azimuth by which the uniform turn is re-distributed, so the
         * arc advances *slowest* through the posterior quadrant (`mistakes`: *"Speeding through the
         * sticky spots"*) and fastest through the front, where the shoulder is strong. `1 rad` would make
         * the schedule non-monotone, so the authored value is a fraction of it and the schedule stays
         * strictly increasing and closed at the seam (both asserted by the pose's own test).
         */
        const val STICKY_SLOWDOWN = 0.3491f

        /** The phase the slow-down is centred on: three quarters of the way round — the arm behind. */
        const val STICKY_PHASE = 0.75f

        /** The near/foreground limb family (`A`/`F`) is the model's LEFT side (`SupportMath`'s map). */
        const val WORKING_SIDE_SIGN = -1f
        private const val REST_SIDE_SIGN = 1f

        /** `1.2 × hipWidth` each side of the mid-line (the corpus's narrow standing stance). */
        fun footZ(def: SkeletonDefinition): Float = def.hipWidth * 1.2f

        private val legPoleF = Vector3(1f, 0f, -0.2f)
        private val legPoleB = Vector3(1f, 0f, 0.2f)
        private val armPoleA = Vector3(0f, -1f, -1f)
        private val armPoleP = Vector3(0f, -1f, 1f)

        private const val TWO_PI = (2f * PI).toFloat()

        /**
         * The hand's azimuth about the shoulder at [progress]: one full revolution per cycle,
         * re-distributed so the angular rate is minimal at [STICKY_PHASE] (the arm behind the body) and
         * maximal a half-cycle later (the arm in front). Strictly increasing for
         * `|STICKY_SLOWDOWN| < 1`, and closed at the seam (`azimuth(0) = 0`, `azimuth(1) = 2π`).
         */
        fun azimuth(progress: Float): Float =
            TWO_PI * progress + STICKY_SLOWDOWN * (1f - sin(TWO_PI * (progress - STICKY_PHASE)))

        /** The arc's own angular rate `dθ/dprogress` — the instrument the control assertion reads. */
        fun azimuthRate(progress: Float): Float =
            TWO_PI * (1f - STICKY_SLOWDOWN * cos(TWO_PI * (progress - STICKY_PHASE)))

        /** The hand's target: a constant radius about [shoulderWorld], in the shoulder's sagittal plane. */
        fun handTarget(shoulderWorld: Vector3, theta: Float): Vector3 = Vector3(
            shoulderWorld.x + CAR_RADIUS * sin(theta),
            shoulderWorld.y - CAR_RADIUS * cos(theta),
            shoulderWorld.z + WORKING_SIDE_SIGN * CAR_OUTBOARD
        )

        /**
         * The elbow's interior angle (degrees) the authored radius implies, from the chain's own two
         * bones: `cos(interior) = (L1² + L2² − r²) / (2·L1·L2)` with `r = `[STRAIGHT_ARM_RADIUS] the
         * realised root→hand distance. A constant by construction — that is the point of the CAR.
         */
        fun elbowInteriorDeg(def: SkeletonDefinition): Float {
            val r2 = STRAIGHT_ARM_RADIUS * STRAIGHT_ARM_RADIUS
            val cos = (def.upperArmLength * def.upperArmLength + def.forearmLength * def.forearmLength - r2) /
                (2f * def.upperArmLength * def.forearmLength)
            return Math.toDegrees(acos(cos.coerceIn(-1f, 1f).toDouble())).toFloat()
        }
    }
}
