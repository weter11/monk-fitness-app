package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Jumping Jacks (`jumping_jack_standard`) — the standing ballistic open/close cycle. There is no BPS
 * and no exercise copy for this drill (`WorkoutGenerator` passes the title as every field), so the
 * shape is authored from the legacy two-frame illustration and the exercise's own name.
 */
class JumpingJacksPose : BasePose() {

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

    override val metadata = PoseMetadata(
        camera = STANDING_CAMERA,
        // The legacy illustration's own cycle (`ExerciseSkeletonData`: `animation(1400, …)`).
        durationSeconds = 1.4f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = STANDING_GROUND,
        support = jumpStance,
        exerciseFamily = "jumping_jacks",
        motionType = "Ballistic Full-Body",
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
        // Shape-driven root (the cycle is authored arithmetic), so the solver leaves it untouched.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val progress = context.progress
        val open = openness(progress)
        val fly = flightArc(progress)
        val absorb = absorbArc(progress)

        pelvis!!.localPosition.set(0f, STANCE_PELVIS_Y + FLIGHT_RISE * fly - LANDING_ABSORB * absorb, 0f)
        // The body is upright for the whole drill (a jack does not lean or twist).
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, HEAD_BONE_LENGTH, 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        val half = stanceHalfWidth(def, open)
        val ankleY = FLOOR_ANKLE_Y + FLIGHT_LIFT * fly
        bakeIkLimb(
            hipF!!.worldPosition, Vector3(0f, ankleY, -half), def.thighLength, def.shinLength,
            legPoleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer
        )
        bakeIkLimb(
            hipB!!.worldPosition, Vector3(0f, ankleY, half), def.thighLength, def.shinLength,
            legPoleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer
        )
        // Both feet keep the standing heading: the drill moves them apart and back, never around.
        setHeading(Extremity.FOOT_F, Vector3(1f, 0f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(1f, 0f, 0f))
        // Toes ride the flight plantar-flexed, and are flat (a touch dorsiflexed) as the feet land.
        val ankleAngle = ankleDorsiflexion(fly, absorb)
        buildAnkleArticulation(Extremity.FOOT_F, ankleAngle, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, ankleAngle, 0f, ankleB!!)

        val psi = PI.toFloat() * open
        val sweepY = -cos(psi) * ARM_RADIUS
        val sweepZ = sin(psi) * ARM_RADIUS
        val shoulderAWorld = shoulderA!!.worldPosition
        bakeIkLimb(
            shoulderAWorld,
            Vector3(shoulderAWorld.x, shoulderAWorld.y + sweepY, shoulderAWorld.z - sweepZ),
            def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint,
            chest!!.worldRotation, elbowA!!, handA!!, armABuffer
        )
        val shoulderPWorld = shoulderP!!.worldPosition
        bakeIkLimb(
            shoulderPWorld,
            Vector3(shoulderPWorld.x, shoulderPWorld.y + sweepY, shoulderPWorld.z + sweepZ),
            def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint,
            chest!!.worldRotation, elbowP!!, handP!!, armPBuffer
        )

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /** The standing family's own camera (`ArmCirclesPose`/`HipCirclesPose`); framing is not tuned here. */
        val STANDING_CAMERA = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f)

        val STANDING_GROUND = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

        /**
         * The drill's base of support: both feet. The body leaves this floor twice per cycle (the two
         * [flightArc] windows) and lands on it twice — the declaration channel is per-pose, so the
         * declaration names the ground the drill repeatedly returns to, while the flights are the two
         * windows below. `JumpSquatPose` (the corpus's other jump) declares exactly this.
         */
        val jumpStance = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )

        /** The standing height the cycle is authored about: the standing corpus's own `226 u`. */
        const val STANCE_PELVIS_Y = 226f

        /** The planted ankles' floor frame (`HipCirclesPose`/`BaseCervicalPose` author the same). */
        const val FLOOR_ANKLE_Y = 25f

        /** The head bone's authored length — the engine's own `NECK_END → HEAD_POS` length. */
        const val HEAD_BONE_LENGTH = 18f

        /**
         * The arm's swinging radius: `136 u` of the `80 + 66 = 146 u` chain, i.e. a long arm
         * (`139.07°` interior — see [elbowInteriorDeg]) whose elbow cannot bend through the sweep.
         * `7.08 u` inside the chain's `0.98` extension cap (`143.08 u`).
         */
        const val ARM_RADIUS = 136f

        /** The jumped stance's half-widths (each side of the mid-line), as hipWidth factors. */
        const val CLOSED_STANCE_FACTOR = 0.55f
        const val OPEN_STANCE_FACTOR = 2.4f

        const val FLIGHT_LIFT = 20f

        /**
         * How far the pelvis rises at the flight's apex. Deliberately **less** than [FLIGHT_LIFT]: the
         * legs tuck as the body rises, which is what a hop looks like and what keeps the hip→ankle
         * chord inside the leg chain's extension cap at every phase of the flight.
         */
        const val FLIGHT_RISE = 14f

        const val LANDING_ABSORB = 14f

        /** The plantar-flexion of the feet through the flight (radians; the toes carry the shape). */
        const val FLIGHT_PLANTAR = 0.45f

        /** The dorsiflexion of the feet at the middle of a grounded stance (radians; the shin absorbs). */
        const val LANDING_DORSIFLEX = 0.10f

        const val TAKEOFF_1 = 0.08f
        const val TOUCHDOWN_1 = 0.42f
        const val TAKEOFF_2 = 0.58f
        const val TOUCHDOWN_2 = 0.92f

        /** `1.2 × hipWidth`: the corpus's narrow standing stance (the drill's own neutral). */
        fun footZ(def: SkeletonDefinition): Float = def.hipWidth * 1.2f

        fun stanceHalfWidth(def: SkeletonDefinition, open: Float): Float =
            SkeletonMath.lerp(def.hipWidth * CLOSED_STANCE_FACTOR, def.hipWidth * OPEN_STANCE_FACTOR, open)

        /** The classic 6-15-10 easing step the corpus's coverage poses share. */
        fun smootherStep(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * x * (x * (x * 6f - 15f) + 10f)
        }

        /** A half-sine across `[from, to]`, zero outside it — the windowed bump both signals use. */
        private fun windowBump(progress: Float, from: Float, to: Float): Float {
            if (progress <= from || progress >= to) return 0f
            return sin(PI.toFloat() * (progress - from) / (to - from))
        }

        /**
         * How "open" the drill is at [progress]: `0` = the closed shape (the illustration's first
         * frame), `1` = the open one (its second). The ramp runs across the *flights*, so the extreme
         * is reached exactly at a touch-down and held through the stance that follows it.
         */
        fun openness(progress: Float): Float = when {
            progress <= TAKEOFF_1 -> 0f
            progress < TOUCHDOWN_1 -> smootherStep((progress - TAKEOFF_1) / (TOUCHDOWN_1 - TAKEOFF_1))
            progress <= TAKEOFF_2 -> 1f
            progress < TOUCHDOWN_2 -> 1f - smootherStep((progress - TAKEOFF_2) / (TOUCHDOWN_2 - TAKEOFF_2))
            else -> 0f
        }

        fun flightArc(progress: Float): Float =
            max(windowBump(progress, TAKEOFF_1, TOUCHDOWN_1), windowBump(progress, TAKEOFF_2, TOUCHDOWN_2))

        fun absorbArc(progress: Float): Float = maxOf(
            windowBump(progress, 0f, TAKEOFF_1),
            windowBump(progress, TOUCHDOWN_1, TAKEOFF_2),
            windowBump(progress, TOUCHDOWN_2, 1f)
        )

        fun ankleDorsiflexion(fly: Float, absorb: Float): Float =
            -FLIGHT_PLANTAR * fly + LANDING_DORSIFLEX * absorb

        /**
         * The elbow's interior angle (degrees) the authored [ARM_RADIUS] implies, from the chain's own
         * two bones: a constant by construction — a jack's arms are long lines the shoulder swings.
         */
        fun elbowInteriorDeg(def: SkeletonDefinition): Float {
            val cos = (def.upperArmLength * def.upperArmLength + def.forearmLength * def.forearmLength -
                ARM_RADIUS * ARM_RADIUS) / (2f * def.upperArmLength * def.forearmLength)
            return Math.toDegrees(acos(cos.coerceIn(-1f, 1f).toDouble())).toFloat()
        }

        private val legPoleF = Vector3(1f, 0f, -0.2f)
        private val legPoleB = Vector3(1f, 0f, 0.2f)
        private val armPoleA = Vector3(0f, -1f, -1f)
        private val armPoleP = Vector3(0f, -1f, 1f)
    }
}
