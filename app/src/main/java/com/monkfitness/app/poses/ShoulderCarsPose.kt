package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Shoulder CARs (`shoulder_cars_standard`) — the standing **controlled articular rotation of one
 * shoulder**, authored on the canonical `SkeletonFactory` tree through [BasePose] (the same tree,
 * declared-pelvis-tilt and registered package bake limbs every migrated family uses).
 *
 * ## What the exercise is, and where that comes from
 *
 * **There is no BPS for this exercise.** `WorkoutGenerator` files it as a `MOBILITY` drill in the
 * `shoulder_mobility` family, and its own copy is the only specification in the repository:
 *
 *  * `desc` — *"A controlled shoulder circle that trains active range without momentum. Move slowly
 *    enough to own every part of the arc."*
 *  * `steps` — *"1. Stand tall and brace your ribs down. 2. Lift one arm straight in front of you.
 *    3. Rotate and circle the arm overhead and behind the body as far as you can control. 4. Reverse
 *    the path back to the start. 5. Repeat, then switch arms."*
 *  * `tech` — *"Move one shoulder at a time. Keep the torso quiet. Make the circle smooth, not fast."*
 *  * `mistakes` — *"Twisting through the spine. Bending the elbow to cheat the range. Speeding
 *    through the sticky spots."*
 *
 * Every clause is authored below as a *structural* property rather than prose; the assertion that
 * measures it is named per clause in `ShoulderCarsPoseTest`:
 *
 * | the copy says | the pose authors | measured by |
 * |---|---|---|
 * | "Move **one** shoulder at a time" | one arm circles; the other hangs inert | `onlyTheWorkingArmMoves` |
 * | "circle the arm **overhead and behind**" | a full `2π` of hand azimuth about the shoulder, through all four quadrants | `theHandReallyCirclesTheShoulderThroughAllFourQuadrants` |
 * | "Lift one arm **straight**" / "Bending the elbow to cheat" | a **constant** shoulder→hand radius, so the elbow's interior angle cannot change by construction | `theElbowNeverCheatsTheArc` |
 * | "Keep the torso **quiet**" / "Twisting through the spine" | a still, vertical trunk on a planted stance: the pelvis, chest, neck and both girdles hold one value for the whole cycle | `theTorsoStaysQuietAndDoesNotTwist` |
 * | "**without momentum**" / "Speeding through the sticky spots" | a monotone azimuth schedule that is slowest through the posterior quadrant (the sticky spot) and fastest in front | `theArcIsControlledAndSlowsThroughTheStickySpot` |
 *
 * ## The motion, exactly
 *
 *  * The working hand's target is `shoulder + R · (sin θ, −cos θ, 0)` with `θ = `[azimuth]: `θ = 0`
 *    is the arm hanging at the side, `θ = π/2` the arm **straight in front** (`steps` §2), `θ = π`
 *    **overhead**, `θ = 3π/2` **behind the body** (`steps` §3) and `θ = 2π` the return to the side —
 *    i.e. *"reverse the path back to the start"* is the second half of the same revolution. The arc
 *    lies in the shoulder's own sagittal plane, offset a hair outboard ([CAR_OUTBOARD]) so it clears
 *    the ribcage on its way behind.
 *  * The radius is **constant**, so the shoulder's own rotation is the *only* thing that moves the
 *    hand: the hand's locus is a circle centred on the shoulder and the elbow's interior angle is
 *    fixed at [elbowInteriorDeg] (`140.76°`). That is what makes this a CAR rather than an arm swing,
 *    and it is the direct counter to the copy's *"bending the elbow to cheat the range"* — there is
 *    no elbow geometry left to cheat with.
 *  * **The torso is still**: the pelvis is authored vertical on the standing family's floor frame and
 *    never moves, both feet are planted ([standingStance] — the corpus's narrow standing stance,
 *    `1.2 × hipWidth`, the same the batch-4 neck pair stands in) and the non-working arm hangs at the
 *    side. A CAR is a *joint* drill: everything the shoulder does not own stays where it is. The
 *    stillness is a measured property, not a claim — see the recorded girdle gap below.
 *
 * ## The authored amplitude, with its bound measured
 *
 * `R` = [CAR_RADIUS] with the plane offset gives a realised shoulder→hand distance of
 * [STRAIGHT_ARM_RADIUS] = `137.6 u`, i.e. the arm chain's own **straight-arm** convention
 * (`BandPullApartPose.ARM_RADIUS = 0.96 × maxReach`, measured `137.600 u`), against the cap
 * `143.08 u` (`0.98` extension) and the fold stop `40.13 u`. The solver therefore never relocates
 * the reach (`maxIkClampAmount == 0` at every phase — asserted), and the arc's lowest hand position
 * is `346 − 137.6 = 208.4 u` above the floor, so the circle cannot reach the ground.
 *
 * ## Recorded gaps (no invented specification)
 *
 *  * **The working girdle does not carry the arm, and that is a measured engine residual, not an
 *    oversight.** A real CAR includes the scapulohumeral rhythm (the shoulder rises through the
 *    overhead half of the arc). This rig's thorax is *unauthored* here, so
 *    `SkeletonPoseFinalizer.reconstructChestFrame` re-derives it from the shoulder line — which a
 *    one-sided clavicular elevation tilts. Measured with a driven elevation of `0.6` activation
 *    units: the working shoulder rises `13.8 u` and the thorax is read back into a **`5.4°` roll**
 *    that the neck carries (the same residual `DipsPose` records for its driven depression, and the
 *    same reason `BandPullApartPose` drives the girdle *symmetrically*). Publishing that roll would
 *    break this drill's own cue (*"Keep the torso quiet"*, `mistakes` *"Twisting through the
 *    spine"*), so the girdle is left **neutral** and the scapular component is recorded rather than
 *    faked. The pose's own test pins the consequence: both shoulders hold one height for the whole
 *    cycle and no shrug exists.
 *  * **The drill is one arm at a time and this pose authors the near (`A`) arm.** The catalog's
 *    `alternating` flag is `false` and the copy says *"then switch arms"* — two sides for one
 *    exercise. The far side is available as the mirror of the same authored geometry and is
 *    **recorded here rather than duplicated into a second pose class** (the same call the neck pair's
 *    supine option records).
 *  * **The rig carries no humeral axial-rotation channel.** A real CAR also *owns* the rotation of the
 *    humerus through its range; this rig's arm is two bones whose bend plane is the IK pole's, so the
 *    humeral roll is not representable and is not faked.
 *  * **"Brace your ribs down"** is a *stated* cue about the ribcage: it is honoured structurally (the
 *    trunk is authored vertical and never extends) — the rig has no separate rib channel to drive.
 *  * The **radius**, the **sticky-spot schedule** ([STICKY_SLOWDOWN]), the **outboard plane offset**
 *    and the **cycle length** are authored constants: the copy names no number, saying only "as far as
 *    you can control" / "smooth, not fast". The cycle is `4.0 s`, slow enough to read as controlled at
 *    the catalog's `4–8` rep prescription.
 *  * The feet keep the standing family's forward heading; the copy says nothing about them.
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

        // --- The still trunk: vertical, planted, and identical at every phase -------------------
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

        // --- The stance: both ankles pinned on the standing family's floor frame ------------------
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

        // --- The CAR itself: the working arm's hand on a constant radius about its own shoulder ---
        val shoulderWorld = shoulderA!!.worldPosition
        bakeIkLimb(
            shoulderWorld,
            handTarget(shoulderWorld, azimuth(context.progress)),
            def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint,
            chest!!.worldRotation, elbowA!!, handA!!, armABuffer
        )

        // --- The other shoulder is not being drilled: that arm simply hangs ----------------------
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

        /** The arc's own in-plane radius (see [STRAIGHT_ARM_RADIUS]). */
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
