package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Piriformis Stretch (`piriformis_stretch_hold`) — the supine figure-4. No BPS exists: the exercise's
 * own copy is the only specification in the repository.
 *
 * The arms are the binding constraint: the near hand can clasp the pulled thigh only near the hip, so
 * the grip is at [GRIP_T] = `0.2` of the thigh (`131 … 140 u` chord, inside the band) — a grip at the
 * thigh's middle would demand `~147 u` and be relocated by the solver. The hands hold the thigh by
 * being PLACED on its line: the rig has no grip/hand-closure DOF.
 */
class PiriformisStretchPose : BasePose() {

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
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // "Keep the tailbone heavy": the pelvis region is what the athlete lies on. The crossed foot and
        // the pulled leg are off the mat by design, so only the core point is declared — the same
        // reasoning `YTRaisesPose` records for its lifted hands.
        support = SupportDefinition(
            pivot = PivotType.PELVIS,
            contacts = setOf(SupportContact(SupportPoint.HIPS))
        ),
        exerciseFamily = "hip_mobility",
        motionType = "Static Stretch",
        bodyOrientation = "supine"
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
        // Shape-driven root (the supine layout is authored arithmetic), so the solver leaves it alone.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        pelvis!!.localPosition.set(PELVIS_X, PELVIS_Y, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, SUPINE_ROLL)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // The head continues the supine trunk's own line (never a world gaze — the chain-frame
        // authoring the rolled-body family uses; a world-space head target would aim into the mat).
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        val pull = pullAmount(context.progress)
        val flexionB = Math.toRadians(
            (PULL_ENTRY_DEG + (PULL_HOLD_DEG - PULL_ENTRY_DEG) * pull).toDouble()
        ).toFloat()
        val thighB = legDirection(flexionB, Math.toRadians(THIGH_AZIMUTH_B_DEG.toDouble()).toFloat())
        val shinB = legDirection(
            flexionB - (PI.toFloat() - Math.toRadians(KNEE_ANGLE_B_DEG.toDouble()).toFloat()),
            Math.toRadians(THIGH_AZIMUTH_B_DEG.toDouble()).toFloat()
        )
        val hipBWorld = hipB!!.worldPosition
        val kneeBWorld = Vector3(
            hipBWorld.x + thighB.x * def.thighLength,
            hipBWorld.y + thighB.y * def.thighLength,
            hipBWorld.z + thighB.z * def.thighLength
        )
        val ankleBTarget = Vector3(
            kneeBWorld.x + shinB.x * def.shinLength,
            kneeBWorld.y + shinB.y * def.shinLength,
            kneeBWorld.z + shinB.z * def.shinLength
        )
        bakeIkLimb(hipBWorld, ankleBTarget, def.thighLength, def.shinLength, poleFor(hipBWorld, kneeBWorld, ankleBTarget, def), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        val contact = thighLinePoint(hipBWorld, thighB, def.thighLength, CONTACT_T)
        val ankleFTarget = Vector3(
            contact.x + CONTACT_STANDOFF_X,
            contact.y,
            contact.z + CONTACT_OUT
        )
        bakeIkLimb(hipF!!.worldPosition, ankleFTarget, def.thighLength, def.shinLength, crossPole, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        // The CROSSED leg (F) is the one whose foot the copy asks about; the pulled foot (B) carries
        // no instruction and is left to the engine's neutral derivation.
        buildAnkleArticulation(Extremity.FOOT_F, CROSSED_ANKLE_DORSIFLEXION, 0f, ankleF!!)

        val grip = thighLinePoint(hipBWorld, thighB, def.thighLength, GRIP_T)
        bakeIkLimb(shoulderA!!.worldPosition, Vector3(grip.x, grip.y + GRIP_LIFT, grip.z - GRIP_SPREAD), def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, shoulderA!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, Vector3(grip.x, grip.y + GRIP_LIFT, grip.z + GRIP_SPREAD), def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, shoulderP!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /**
     * The pole that states where the 2-bone chain bends, derived from the authored knee's own offset
     * off the hip→ankle chord (the convention: the pose owns the root, the target AND the bend side).
     */
    private fun poleFor(hip: Vector3, knee: Vector3, ankle: Vector3, def: SkeletonDefinition): Vector3 {
        val ux = ankle.x - hip.x; val uy = ankle.y - hip.y; val uz = ankle.z - hip.z
        val d = sqrt(ux * ux + uy * uy + uz * uz)
        val a = (d * d + def.thighLength * def.thighLength - def.shinLength * def.shinLength) / (2f * d)
        return Vector3(
            knee.x - (hip.x + ux / d * a),
            knee.y - (hip.y + uy / d * a),
            knee.z - (hip.z + uz / d * a)
        )
    }

    companion object {
        /** The supine mat layer — the corpus's own supine value (`DeadBugPose`/`LegRaisePose`). */
        const val PELVIS_Y = 12f
        const val PELVIS_X = 15f

        /** `§1`'s supine layout: `rotZ(+90°)` puts the spine's `+Y` along the world `−X`, face up. */
        const val SUPINE_ROLL = (PI / 2f).toFloat()

        /** The pulled leg's hip flexion: the entry, and the hold the pull reaches. */
        const val PULL_ENTRY_DEG = 78f
        const val PULL_HOLD_DEG = 100f

        /** The pull's rhythm: a ramp, a HOLD plateau (§4/§5), and a release. */
        const val PULL_RAMP = 0.30f
        const val PULL_FALL = 0.25f

        /** The pulled leg's knee angle and azimuth (the leg lifts up its own side, out of the way). */
        const val KNEE_ANGLE_B_DEG = 95f
        const val THIGH_AZIMUTH_B_DEG = 8f

        /** Where the crossed ankle lands on the pulled thigh: near its far end (`§2`). */
        const val CONTACT_T = 0.85f
        /**
         * The crossed shin rests on the pulled thigh's ANTERIOR surface. The two limbs' axes are
         * nearly perpendicular in this configuration, so the direction that actually separates them is
         * the one perpendicular to BOTH — measured to be the fore/aft axis: the standoff is therefore
         * authored along `X` (toward the head, i.e. the thigh's quadriceps side), with a small inboard
         * `Z` so the ankle lands just across the thigh rather than exactly on its axis.
         */
        private const val CONTACT_STANDOFF_X = -14f
        private const val CONTACT_OUT = 8f

        /** Where the hands hold the pulled thigh (`§3`). */
        const val GRIP_T = 0.2f
        private const val GRIP_LIFT = 4f
        private const val GRIP_SPREAD = 11f

        /**
         * The crossed foot's dorsiflexion — `tech`: *"Flex the crossed foot to protect the knee"* —
         * authored through Branch C (`buildAnkleArticulation`), the only channel this rig has for it.
         * The engine composes this articulation AFTER it derives the foot from the shank, so only part
         * of the authored `20°` reaches the published foot; the authoring is live (the test pins the
         * difference) and the unrealized remainder is an engine residual, recorded not tuned away.
         */
        const val CROSSED_ANKLE_DORSIFLEXION = 0.35f

        private val crossPole = Vector3(-0.35f, 0.55f, -0.75f)
        /**
         * The supine arm poles: the elbows bow UP and outboard. The standing family's `(0, -1,...)`
         * convention would aim the perpendicular into the mat here — this layout is rolled to lie on
         * it, so the "away from the body" direction is `+Y` (the ceiling), which is also the side the
         * hands reach toward.
         */
        private val armPoleA = Vector3(0f, 1f, -1f)
        private val armPoleP = Vector3(0f, 1f, 1f)

        /**
         * The pull's shape at [progress]: entry → **hold** → release. The plateau is what the exercise
         * actually is (`steps` §5 *"Hold, then change sides"*; `desc` *"Ease into it"*), and it is
         * measurably flat by construction.
         */
        fun pullAmount(progress: Float): Float = when {
            progress <= 0f -> 0f
            progress < PULL_RAMP -> smootherStep(progress / PULL_RAMP)
            progress <= 1f - PULL_FALL -> 1f
            progress < 1f -> smootherStep((1f - progress) / PULL_FALL)
            else -> 0f
        }

        private fun smootherStep(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * x * (x * (x * 6f - 15f) + 10f)
        }

        /**
         * A leg direction in this pose's supine frame: [flexion] measured from the flat (`+X`, in line
         * with the body) direction, rotating the limb up toward the ceiling, in the vertical plane at
         * [azimuthRad] (`0` = the body's own feet-direction, `±π/2` = lateral).
         */
        fun legDirection(flexion: Float, azimuthRad: Float): Vector3 = Vector3(
            cos(azimuthRad) * cos(flexion),
            sin(flexion),
            sin(azimuthRad) * cos(flexion)
        )

        /** The point [t] along a thigh: `hip + t · thighLength · thighDir`. */
        fun thighLinePoint(hip: Vector3, thighDir: Vector3, thighLength: Float, t: Float): Vector3 =
            Vector3(
                hip.x + thighDir.x * thighLength * t,
                hip.y + thighDir.y * thighLength * t,
                hip.z + thighDir.z * thighLength * t
            )

        /** The B leg's authored thigh direction at [progress] — the reader the tests use. */
        fun thighDirectionB(progress: Float): Vector3 = legDirection(
            Math.toRadians((PULL_ENTRY_DEG + (PULL_HOLD_DEG - PULL_ENTRY_DEG) * pullAmount(progress)).toDouble()).toFloat(),
            Math.toRadians(THIGH_AZIMUTH_B_DEG.toDouble()).toFloat()
        )
    }
}
