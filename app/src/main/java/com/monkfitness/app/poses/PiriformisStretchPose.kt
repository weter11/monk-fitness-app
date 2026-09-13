package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Piriformis Stretch (`piriformis_stretch_hold`) — the supine figure-4, authored on the canonical
 * `SkeletonFactory` tree through [BasePose].
 *
 * ## The exercise (its own copy — the only specification in the repository; no BPS exists)
 *
 * `ex_piriformis_stretch_steps`: *"1. Lie on your back with both knees bent. 2. Cross one ankle over
 * the opposite thigh. 3. Pull the uncrossed leg toward your chest. 4. Keep the tailbone heavy as the
 * outer hip opens up. 5. Hold, then change sides."* `desc` *"A glute-focused stretch that targets the
 * back and outside of the hip. Ease into it and keep the low back quiet."* `tech` *"Flex the crossed
 * foot to protect the knee. Pull the legs in only until the hip stretches. Exhale slowly to release
 * tension."* `mistakes` *"Yanking the knee toward the chest. Letting the crossed foot go limp.
 * Twisting the pelvis off the floor."*
 *
 * ## The authored movement, one statement per copy line
 *
 *  * **§1 "Lie on your back with both knees bent"** — the supine layout the corpus's supine family
 *    authors (`DeadBugPose`/`LegRaisePose`): the trunk lies along the world `−X` with its ventral face
 *    up, carried by a declared pelvis tilt of `+90°` about `Z`.
 *  * **§2 "Cross one ankle over the opposite thigh"** — the F (near) leg's ankle target IS the pulled
 *    thigh's own line at [CONTACT_T], derived from the pulled leg's authored geometry (not read from
 *    any engine-written node), and the F leg's pole states that its knee goes up and outboard: the
 *    drill's crossed configuration, whose shin crosses the body's midline to lie across the opposite
 *    thigh. Its hip is *externally rotated* in the only way this rig realises hip rotation — the
 *    geometry itself: the thigh's horizontal projection points outboard (`−Z`) while its shin's points
 *    inboard (`+Z`), so the femur's twist is visible in the published chain.
 *  * **§3 "Pull the uncrossed leg toward your chest"** — the pull is the B leg's authored hip flexion
 *    ([PULL_ENTRY_DEG] → [PULL_HOLD_DEG], `tech`: *"only until the hip stretches"* — a measured `22°`),
 *    and the hands are target-authored **onto that same thigh's line** ([GRIP_T]), so they travel with
 *    the leg they are pulling rather than gesturing at it.
 *  * **§4/§5 "Hold, then change sides"** — the cycle is an entry, a **hold plateau**
 *    ([PULL_RAMP]/[PULL_FALL] bracket a `45 %` plateau in which the pull is constant) and a release,
 *    which is what a `30–60 s` timer stretch actually does. The pose is one side of the drill (the
 *    corpus's unilateral convention): the other side is the same pose mirrored, which the exercise's
 *    own catalog entry schedules as the second set.
 *  * **`tech` "Flex the crossed foot to protect the knee"** — authored through the sanctioned Branch-C
 *    ankle articulation on the crossed foot ([CROSSED_ANKLE_DORSIFLEXION]) and witnessed on the
 *    published frame by a `0`-articulation twin of this pose (the test measures the difference).
 *  * **`mistakes` "Twisting the pelvis off the floor" / "keep the tailbone heavy" / `desc` "keep the
 *    low back quiet"** — the pelvis is authored at one height, with **no roll and no twist** about the
 *    body's long axis, and the trunk stays in the mat's plane for the whole cycle: the test measures
 *    the hips' lateral line (`2 × hipWidth`), the absence of any hip-height asymmetry, and the trunk's
 *    own flatness.
 *
 * ## Reach — the honest limit this pose is authored against (measured)
 *
 * The arms are the binding constraint, and it is worth stating exactly: the shoulders sit at the
 * trunk's far end (`≈ torsoLength` = `120 u` from the pelvis in `X`), the pulled leg's hip is a further
 * `hipWidth` = `22` out laterally, and this rig's whole arm is `146` (`40.13 … 143.08` reachable). So
 * the near-side hand can clasp the pulled thigh **only near the hip**: at the authored grip
 * ([GRIP_T] = `0.2` of the thigh) the hand's chord to its own shoulder measures `131 … 140 u` across
 * the cycle (inside the band, so the reach stamp reads `0`), while a grip at the thigh's middle would
 * demand `~147 u` and be relocated by the solver. That is why the hands clasp low and the pull is the
 * `22°` the copy's own *"only until the hip stretches"* asks for — a bigger pull is not what the copy
 * wants, and a higher grip is not something this rig can place.
 *
 * ## Recorded gaps (no invented specification)
 *
 *  * The supine layer (`PELVIS_Y`), the crossed leg's angles, the pull depths and the grip fraction are
 *    **authored constants**: the copy states none of them. The supine layer is the corpus's own
 *    (`DeadBugPose`/`LegRaisePose` publish at `12`).
 *  * **The clasp itself is not expressed.** The hands hold the thigh by being *placed* on its line; the
 *    rig has no grip/hand-closure DOF (`HandDefinition` is a single long axis), so "grip" is geometry,
 *    not a channel. Recorded, not invented.
 *  * The foot's own **flexion** is authored (Branch C); its *yaw* ("protect the knee" also implies the
 *    ankle tracks) has no representation on this rig (the ankle vocabulary is dorsiflexion + inversion
 *    only), which the batch record already documents for the whole phase.
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

        // --- 1. The supine layout (§1): trunk along -X, ventral face up, mat layer ---------------
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

        // --- 2. The pulled (uncrossed) leg B: both knees bent, the pull cycle ---------------------
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

        // --- 3. The crossed leg F: its ankle lands on the pulled thigh's own line (§2) -----------
        val contact = thighLinePoint(hipBWorld, thighB, def.thighLength, CONTACT_T)
        val ankleFTarget = Vector3(
            contact.x + CONTACT_STANDOFF_X,
            contact.y,
            contact.z + CONTACT_OUT
        )
        bakeIkLimb(hipF!!.worldPosition, ankleFTarget, def.thighLength, def.shinLength, crossPole, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        // --- 4. "Flex the crossed foot to protect the knee" (Branch-C ankle articulation) --------
        // The CROSSED leg (F) is the one whose foot the copy asks about; the pulled foot (B) carries
        // no instruction and is left to the engine's neutral derivation.
        buildAnkleArticulation(Extremity.FOOT_F, CROSSED_ANKLE_DORSIFLEXION, 0f, ankleF!!)

        // --- 5. The hands: they hold the thigh they are pulling (§3) ------------------------------
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
     * off the hip→ankle chord (the B1/B7 convention: the pose owns the root, the target AND the bend
     * side; the solver's perpendicular is this vector's component off the chord).
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

        /** Where the hands hold the pulled thigh (`§3`) — see the reach record in the KDoc. */
        const val GRIP_T = 0.2f
        private const val GRIP_LIFT = 4f
        private const val GRIP_SPREAD = 11f

        /**
         * The crossed foot's dorsiflexion — `tech`: *"Flex the crossed foot to protect the knee"* —
         * authored through Branch C (`buildAnkleArticulation`), the only channel this rig has for it.
         *
         * **Measured realization (recorded, not assumed):** the engine's extremity derivation builds
         * the foot's direction from the shank + the authored hint and composes this articulation after
         * it, so only part of the authored `20°` reaches the published foot, and the fraction depends
         * on the phase: the crossed foot's own shank→foot angle measures `87.00°` at the cycle's entry
         * and `90.04°` at the hold with this value, against `90.00°` at every phase with the
         * articulation removed. The authoring is live (it is the difference the test pins); the
         * unrealized remainder is an engine-side residual, recorded here rather than tuned away.
         */
        const val CROSSED_ANKLE_DORSIFLEXION = 0.35f

        private val crossPole = Vector3(-0.35f, 0.55f, -0.75f)
        /**
         * The supine arm poles: the elbows bow UP and outboard. The standing family's `(0, -1, ...)`
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
