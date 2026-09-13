package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Child's Pose (`child_pose_hold`) — the kneeling **fold-and-hold with its own transition in and out**,
 * authored on the canonical `SkeletonFactory` tree through [BasePose] (the same tree, declared-spine
 * curve and registered package bake limbs every migrated family uses).
 *
 * ## What the exercise is, and where that comes from
 *
 * **There is no BPS for this exercise.** `WorkoutGenerator` files it as a `STRETCHING` timer drill of
 * the `child_pose`/`SPINE` family, and its own copy is the only specification in the repository:
 *
 *  * `desc` — *"A restorative stretch that gently lengthens the back and helps relax the hips and
 *    shoulders."*
 *  * `steps` — *"1. Kneel on the floor and sit the hips back toward the heels. 2. Fold the torso
 *    forward and reach the arms long in front. 3. Let the forehead rest down and breathe into the
 *    ribcage. 4. Hold the stretch, then slowly rise back up."*
 *  * `tech` — *"Relax the shoulders and keep the breath slow. Widen the knees if you need more space
 *    for the torso."*
 *  * `mistakes` — *"Holding tension in the neck, bouncing into the stretch, and forcing the hips to
 *    the heels when mobility is limited."*
 *
 * The pose authors all four `steps` in that order — a **kneel**, a **sit-back**, a **fold**, a
 * **hold** — and then the reverse of it, because §4 says *"then slowly rise back up"*. Every clause is
 * a structural property below; the assertion that measures it is named per clause in
 * `ChildPoseTest`:
 *
 * | the copy says | the pose authors | measured by |
 * |---|---|---|
 * | "1. **Kneel** on the floor" | the knee is pinned on the mat's knee layer for the *whole* cycle and the shin lies flat from it back to the pinned ankle | `theShinsLieFlatOnTheMatForTheWholeCycle` |
 * | "**sit the hips back** toward the heels" | the hip travels back and down **on the circle of radius `thighLength` about the pinned knee**, so the knee cannot move while the hips travel (`92.0 u` back, `47.8 u` down) | `theHipsSitBackOverTheHeelsWithoutMovingTheKnees` |
 * | "2. **Fold the torso forward**" | the two-segment spine folds `109°` at the root and a further `26°` at the thorax | `theTorsoFoldsForwardAndDown` |
 * | "reach the **arms long in front**" | both hands travel to the mat `123.2 u` from their own shoulder, ahead of the head | `theArmsReachLongInFrontOntoTheMat` |
 * | "3. Let the **forehead rest down**" | the cervical chain carries a further `15°` of tuck; the head's tip publishes at the corpus's own mat layer (`9.0 u`) | `theHeadRestsAtTheMatLayer` |
 * | "4. **Hold** the stretch" | the fold plateau is flat for `45 %` of the cycle | `theHoldPlateauIsFlat` |
 * | "4. then **slowly rise back up**" | the cycle closes: the rise is the mirror of the entry and `progress 1 ≡ 0` | `theCycleClosesAndRisesBackUp` |
 * | "**bouncing** into the stretch" (a mistake) | the entry/rise ramps are monotone — nothing returns mid-ramp | `nothingBounces` |
 * | "**forcing the hips to the heels** when mobility is limited" (a mistake) | the sit-back stops where the *leg chain* stops: `55°` of femur, leaving `8.5 u` of its `56.01 u` fold stop | `theSitBackStopsAtTheLegChainsOwnFoldBound` |
 *
 * ## The motion, exactly
 *
 * The whole cycle is one scalar, [foldFraction] `s(p) ∈ [0,1]`: `0` at `p = 0` (the tall kneel), `1`
 * from [ENTRY_END] to [EXIT_START] (the hold), `0` again at `p = 1` (the rise completes). Everything
 * else is `s`:
 *
 *  * **The knee is the axis and does not move.** The hip is authored on the circle of radius
 *    `thighLength` about the pinned knee at ([KNEE_X], [KNEE_Y]), through `α = `[FOLD_ALPHA]`·s`. Because
 *    the hip is exactly `thighLength` from the knee, the leg chain's own IK puts the middle joint *at*
 *    the knee for every `α` — so the shins stay flat on the mat and the ankles do not move while the
 *    hips travel. This is the drill's actual biomechanics ("sit the hips back": the femur rotates on a
 *    knee that is planted), and it is what makes the pose's own ground contacts stable.
 *  * **The trunk folds** as the two-segment spine: [TRUNK_FOLD] at the root plus [THORACIC_FOLD] at
 *    the chest, i.e. the torso is carried `109°` forward by the pelvis and the thorax rounds a further
 *    `26°` — the copy's "fold the torso forward" with the thoracic component that puts the *shoulders*
 *    low rather than a single rigid hinge.
 *  * **The cervical chain tucks** a further [CERVICAL_TUCK] on top of the thorax's direction, and both
 *    bones are authored as their own directions in the chest's frame (the batch-4 neck pair's channel:
 *    `resolveHeadTarget` is the sole writer of those offsets and it only runs for a pose that declared
 *    a gaze target, so this authored chain reaches the published frame verbatim). The head's tip then
 *    publishes at [headTipY] ≈ `9.0 u` — the corpus's own mat layer (the prone family's `10 u`).
 *  * **The arms** travel from the tall kneel's hanging rest to a constant offset in front of the fold's
 *    shoulder ([HAND_FORWARD] forward, onto the mat at [HAND_Y], [HAND_INWARD] inboard), so at the hold
 *    the hands rest on the mat `123.2 u` from their own shoulder and well ahead of the head — *"reach
 *    the arms long in front"*. The interpolation is in the same `s`, so the arms arrive with the fold.
 *  * **The feet lie along the shins** (the kneel's own configuration, the top of the foot down): the
 *    foot heading channel (`setHeading`) carries them backward, which is also why the derivation lays
 *    them flat on the mat instead of standing them up.
 *
 * ## Physics/bounds, measured rather than asserted in prose
 *
 * * The authored hip→ankle chord runs `148.8 u` (the tall kneel) → `64.5 u` (the hold) of the leg
 *   chain's `[56.01, 205.80] u` band: every phase is reachable by construction, and the sit-back could
 *   not go past `58°` without the chain folding past its own stop — which is the copy's own *"forcing
 *   the hips to the heels when mobility is limited"* mistake, honoured structurally (see the KDoc row).
 * * The hands' authored reach is `123.2 u` of the arm chain's `[40.13, 143.08] u` band.
 * * The knee layer is [KNEE_Y] `= 15 u`, the corpus's own knee-contact layer
 *   (`PushUpPlank.BASE_KNEE_HEIGHT`, the layer `KneePushUpPose` and `CatCowPose` rest their knees on).
 *
 * ## Recorded gaps (no invented specification)
 *
 *  * **The drill's subject is the hold, and its contacts are declared for it.** `metadata.support` is
 *    per-pose, not per-frame: the kneeling base (both knees and both hands, pivot `KNEES` — the
 *    `KneePushUpPose`/`CatCowPose` declaration, and the quadrupeds' own four-point base) is the
 *    hold's. Through the entry and the rise the hands are *lifted* (they hang at the sides at `p = 0`),
 *    exactly as `YTRaisesPose` records for its lifted hands and `NinetyNinetyHipsPose` for its
 *    picked-up feet. The knees, by contrast, are on the mat for the whole cycle and their declaration
 *    is true at every phase.
 *  * **The forehead has no `SupportPoint`**: `SupportMath.jointsFor` carries no head entry, so the
 *    head's mat relationship is authored geometry ([headTipY]) and is **not declared** — the same
 *    vocabulary gap `YTRaisesPose` records for its supported forehead.
 *  * **The clasp/grip is not expressed**: the rig has no grip DOF (`HandDefinition` is one long axis),
 *    so the hands "reach long" by being placed on the mat, not by being curled into it.
 *  * **The knees' widening** (`tech`: *"Widen the knees if you need more space for the torso"*) is a
 *    *conditional* instruction for a lifter whose torso needs room; the authored stance is the neutral
 *    one (`KNEE_Z = hipWidth`, the thighs in their own sagittal planes) and the wider variant is
 *    recorded rather than authored — a second pose would be a second exercise.
 *  * **"Breathe into the ribcage"** and the breath cycle are not representable (the rig carries no
 *    breath channel); the hold's platean is the kinematics' part of that cue.
 *  * The fold angles, the hold's fraction of the cycle, the hands' placement and the cycle length are
 *    authored constants: the copy names no number, saying only "gently" / "slowly".
 */
class ChildPose : BasePose() {

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
        camera = KNEELING_CAMERA,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = MAT_GROUND,
        support = kneelingBase,
        exerciseFamily = "child_pose",
        motionType = "Fold & Hold",
        bodyOrientation = "Kneeling"
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
        // Shape-driven root (the kneel and the fold are authored arithmetic), so the solver leaves the
        // authored root untouched.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val s = foldFraction(context.progress)
        val alpha = FOLD_ALPHA * s
        val trunk = TRUNK_FOLD * s
        val thoracic = THORACIC_FOLD * s
        val cervical = CERVICAL_TUCK * s

        // --- The kneel: the hip sits back on the circle of radius thighLength about the PINNED knee ---
        pelvis!!.localPosition.set(hipX(def, alpha), hipY(def, alpha), 0f)
        // "Fold the torso forward": the trunk's own two segments, both about the mediolateral axis.
        buildSpineCurve(pelvis!!, chest!!, -trunk, -thoracic, axisZ)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // The head follows the fold and tucks a further CERVICAL_TUCK, authored as the chain's own
        // directions in the chest's frame (the channel `resolveHeadTarget` writes for a declared gaze).
        neck!!.localPosition.set(def.neckLength * sin(cervical), def.neckLength * cos(cervical), 0f)
        head!!.localPosition.set(HEAD_BONE_LENGTH * sin(cervical), HEAD_BONE_LENGTH * cos(cervical), 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        // --- The shins: pinned flat on the mat, so the knee stays where the drill put it -----------
        val ankleX = kneeX(def) - def.shinLength
        bakeIkLimb(
            hipF!!.worldPosition, Vector3(ankleX, KNEE_Y, -kneeZ(def)), def.thighLength, def.shinLength,
            legPoleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer
        )
        bakeIkLimb(
            hipB!!.worldPosition, Vector3(ankleX, KNEE_Y, kneeZ(def)), def.thighLength, def.shinLength,
            legPoleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer
        )
        // The feet lie along the shins (the top of the foot down): the kneel's own configuration. The
        // heading is ROOT-RELATIVE (the channel's own contract — the Finalizer rotates it by the
        // pelvis's published rotation), so the fold's own angle is composed out of it here; that keeps
        // the feet pointing back along the mat in WORLD space at every phase of the fold.
        setHeading(Extremity.FOOT_F, footHeading(trunk))
        setHeading(Extremity.FOOT_B, footHeading(trunk))

        // --- The arms: hanging at the tall kneel, long in front on the mat at the fold --------------
        val travel = s
        val shoulderAWorld = shoulderA!!.worldPosition
        bakeIkLimb(
            shoulderAWorld, armTarget(shoulderAWorld, def, travel, -1f),
            def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint,
            chest!!.worldRotation, elbowA!!, handA!!, armABuffer
        )
        val shoulderPWorld = shoulderP!!.worldPosition
        bakeIkLimb(
            shoulderPWorld, armTarget(shoulderPWorld, def, travel, 1f),
            def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint,
            chest!!.worldRotation, elbowP!!, handP!!, armPBuffer
        )

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /** The hand's world target: from the hanging rest to the mat in front of the fold's shoulder. */
    private fun armTarget(shoulderWorld: Vector3, def: SkeletonDefinition, s: Float, sideSign: Float): Vector3 {
        val f = 0.08f
        val o = 0.03f
        val len = sqrt(f * f + 1f + o * o)
        restDir.set(f / len, -1f / len, sideSign * o / len)
        // Both ends are authored as offsets FROM THE LIVE SHOULDER, so the arms ride every
        // configuration the fold takes them through instead of pointing at stale world coordinates.
        val foldForward = HAND_FORWARD
        val foldDown = HAND_Y - foldShoulderY(def)
        val foldInward = -sideSign * HAND_INWARD
        return Vector3(
            shoulderWorld.x + SkeletonMath.lerp(restDir.x * ARM_REST_RADIUS, foldForward, s),
            shoulderWorld.y + SkeletonMath.lerp(restDir.y * ARM_REST_RADIUS, foldDown, s),
            shoulderWorld.z + SkeletonMath.lerp(restDir.z * ARM_REST_RADIUS, foldInward, s)
        )
    }

    companion object {
        /** The kneeling family's own camera (`CatCowPose`/`KneePushUpPose`); framing is not tuned here. */
        val KNEELING_CAMERA = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f)

        val MAT_GROUND = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

        /**
         * The hold's own base of support: both knees and both hands on the mat, pivot `KNEES` — the
         * declaration the corpus's kneeling quadruped family already carries (`KneePushUpPose`,
         * `CatCowPose`).
         *
         * **The feet are declared on the canonical foot channel as well**, because in a kneel the
         * foot is on the mat (its *dorsum*, the top of the foot down) and all three joints the channel
         * names — `{ANKLE, HEEL, TOE}` — really do rest at the mat's layer for the whole cycle. That
         * declaration is also what activates the foot's own derivation (`declaredFootSupportPoint` →
         * the support plane and the authored heading), so the feet publish the kneel's real
         * configuration: lying along the shins, toes back. Without it the channel's neutral fallback
         * lays the feet *forward* (measured `TOE_F` at `+24.85`, i.e. the foot's line running back
         * under the shin it is supposed to continue).
         *
         * The knees are true at every phase; the hands are lifted through the entry and the rise
         * (recorded in the class KDoc).
         */
        val kneelingBase = SupportDefinition(
            pivot = PivotType.KNEES,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_KNEE),
                SupportContact(SupportPoint.RIGHT_KNEE),
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )

        /** The head bone's authored length — the engine's own `NECK_END → HEAD_POS` length. */
        const val HEAD_BONE_LENGTH = 18f

        /**
         * The knee's contact layer on the mat: `15 u`, the corpus's own knee layer
         * (`PushUpPlank.BASE_KNEE_HEIGHT`), i.e. the kneecap's joint centre above the surface.
         */
        const val KNEE_Y = 15f

        /**
         * The knee's sagittal placement: a `shinLength` forward of the mid-line, so the pinned ankle
         * lands exactly on `x = 0` and the shin lies flat along the mat's negative X direction.
         */
        fun kneeX(def: SkeletonDefinition): Float = def.shinLength

        /** The knees sit under their own hips (the neutral, un-widened kneel — see the KDoc's gap). */
        fun kneeZ(def: SkeletonDefinition): Float = def.hipWidth

        /**
         * The deepest sit-back: `55°` of femur rotation from vertical. Bounded by the *leg chain's*
         * band — at `55°` the hip→ankle chord is `64.5 u` of the chain's `56.01 u` fold stop, so the
         * hips stop where the body's own fold stops (`mistakes`: *"forcing the hips to the heels when
         * mobility is limited"*). `58°` would sit exactly on the stop.
         */
        const val FOLD_ALPHA = 0.9599f // 55°

        /** The trunk's own fold at the hold: `109°` from vertical (the torso carried over the thighs). */
        const val TRUNK_FOLD = 1.9024f // 109°

        /** The thorax's additional rounding at the hold: `26°` (the shoulders follow the fold down). */
        const val THORACIC_FOLD = 0.4538f // 26°

        /** The cervical tuck at the hold: `15°` on top of the thorax's direction ("forehead rests down"). */
        const val CERVICAL_TUCK = 0.2618f // 15°

        /** How far in front of the fold's shoulder the hands reach. */
        const val HAND_FORWARD = 137f

        /** The hands' own mat layer (the corpus's contact-joint layer; the palm lies on it). */
        const val HAND_Y = 15f

        /** How far inboard of the shoulders the reaching hands come (`shoulderWidth − this`). */
        const val HAND_INWARD = 12f

        /** The hanging arm's radius at the tall kneel: `140 u` of the `80 + 66 = 146 u` chain. */
        const val ARM_REST_RADIUS = 140f

        /** `0 → 1` by this phase (the entry), `1` until [EXIT_START], `→ 0` by the seam (the rise). */
        const val ENTRY_END = 0.35f
        const val EXIT_START = 0.80f

        /**
         * The feet's heading at a trunk fold of [trunk], authored in the PELVIS's own frame (the
         * channel's root-relative contract — `SkeletonPoseFinalizer.adjustFootOrientation` rotates the
         * declared value by the pelvis's published rotation and projects it onto the support plane).
         * Composing the fold out of it leaves the published feet pointing back along the mat in world
         * space at every phase: measured without it, the fold's `93°` rotates the plain backward
         * heading up to `(0.055, 0.9985, 0)`, whose projection onto the mat degenerates to the `+X`
         * fallback and turns both feet forward under the shins they are meant to continue.
         */
        fun footHeading(trunk: Float): Vector3 = Vector3(-cos(trunk), -sin(trunk), 0f)

        /** The classic 6-15-10 easing step, the corpus's shared ramp shape. */
        fun smootherStep(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * x * (x * (x * 6f - 15f) + 10f)
        }

        /**
         * The cycle's single scalar: the kneel (`0`) → the fold (`1`, held from [ENTRY_END] to
         * [EXIT_START]) → the kneel again (`0` at the seam). Both ramps are monotone, so nothing in the
         * cycle ever bounces (`mistakes`).
         */
        fun foldFraction(progress: Float): Float = when {
            progress <= 0f -> 0f
            progress < ENTRY_END -> smootherStep(progress / ENTRY_END)
            progress <= EXIT_START -> 1f
            progress < 1f -> 1f - smootherStep((progress - EXIT_START) / (1f - EXIT_START))
            else -> 0f
        }

        /** The hip's X at femur angle [alpha]: the knee's X minus the femur's own projection. */
        fun hipX(def: SkeletonDefinition, alpha: Float): Float = kneeX(def) - def.thighLength * sin(alpha)

        /** The hip's Y at femur angle [alpha]: the knee's layer plus the femur's own projection. */
        fun hipY(def: SkeletonDefinition, alpha: Float): Float = KNEE_Y + def.thighLength * cos(alpha)

        /** The tall kneel's hip X (`alpha = 0`): the hip directly above the pinned knee. */
        fun kneelHipX(def: SkeletonDefinition): Float = hipX(def, 0f)

        /** The tall kneel's hip Y: a whole femur above the knee layer. */
        fun kneelHipY(def: SkeletonDefinition): Float = hipY(def, 0f)

        /** The hold's hip X (`alpha = `[FOLD_ALPHA]). */
        fun foldHipX(def: SkeletonDefinition): Float = hipX(def, FOLD_ALPHA)

        /** The hold's hip Y. */
        fun foldHipY(def: SkeletonDefinition): Float = hipY(def, FOLD_ALPHA)

        /** The fold's chest X: the hip plus the trunk's own fold. */
        fun foldChestX(def: SkeletonDefinition): Float =
            foldHipX(def) + def.torsoLength * sin(TRUNK_FOLD)

        /** The fold's chest Y. */
        fun foldChestY(def: SkeletonDefinition): Float =
            foldHipY(def) + def.torsoLength * cos(TRUNK_FOLD)

        /** The fold's shoulder Y (the shoulders ride the chest; their Z is the chest's own ±shoulderWidth). */
        fun foldShoulderY(def: SkeletonDefinition): Float = foldChestY(def)

        /** The head's tip Y at the hold — the mat layer the forehead rests at. */
        fun headTipY(def: SkeletonDefinition): Float {
            val dir = TRUNK_FOLD + THORACIC_FOLD + CERVICAL_TUCK
            return foldChestY(def) + (def.neckLength + HEAD_BONE_LENGTH) * cos(dir)
        }

        /** The hands' authored reach from the fold's shoulder: the arm chain's own band member. */
        fun foldHandReach(def: SkeletonDefinition): Float {
            // The hands come HAND_INWARD inboard of their own shoulder, so the lateral leg of the
            // reach is the inboard offset itself (the shoulder's own half-width is already spent).
            val dz = HAND_INWARD
            val dy = HAND_Y - foldShoulderY(def)
            return sqrt(HAND_FORWARD * HAND_FORWARD + dy * dy + dz * dz)
        }

        /** The elbow's interior angle (degrees) at the hold, from the chain's own two bones. */
        fun foldElbowInteriorDeg(def: SkeletonDefinition): Float {
            val r = foldHandReach(def)
            val cos = (def.upperArmLength * def.upperArmLength + def.forearmLength * def.forearmLength - r * r) /
                (2f * def.upperArmLength * def.forearmLength)
            return Math.toDegrees(acos(cos.coerceIn(-1f, 1f).toDouble())).toFloat()
        }

        /**
         * The kneel's leg poles are **in the thigh's own sagittal plane**: the corpus's standing
         * `(1, 0, ∓0.2)` pole carries an out-of-plane term that splays the knee outboard (measured
         * `19.2 u` of ankle→knee lateral offset, an `11.3°` twist of the shin), while a kneel's shins
         * lie parallel to the mid-line with the knees under their own hips. The pole only has to be
         * non-parallel to the hip→ankle chord, and this one puts the middle joint exactly on the
         * pinned knee for every femur angle.
         */
        private val legPoleF = Vector3(1f, 0f, 0f)
        private val legPoleB = Vector3(1f, 0f, 0f)
        private val armPoleA = Vector3(0f, 1f, -1f)
        private val armPoleP = Vector3(0f, 1f, 1f)
    }
}
