package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Child's Pose (`child_pose_hold`) — the kneeling fold-and-hold with its own transition in and out, on the
 * canonical `SkeletonFactory` tree through [BasePose].
 *
 * **There is no BPS for this exercise**: its own copy is the only specification in the repository, and the
 * pose authors its four `steps` in order (kneel, sit-back, fold, hold) and then the reverse, because the
 * copy says "then slowly rise back up". `ChildPoseTest` measures each clause.
 *
 * The whole cycle is one scalar, [foldFraction] `s(p) ∈ [0,1]`: `0` at `p = 0` (the tall kneel), `1` from
 * [ENTRY_END] to [EXIT_START] (the hold), `0` again at `p = 1`.
 *
 *  * **The knee is the axis and does not move.** The hip is authored on the circle of radius `thighLength`
 *    about the pinned knee at ([KNEE_X], [KNEE_Y]) through `α = `[FOLD_ALPHA]`·s`. Because the hip is
 *    exactly `thighLength` from the knee, the leg chain's own IK puts the middle joint *at* the knee for
 *    every `α`, so the shins stay flat on the mat and the ankles do not move while the hips travel — the
 *    drill's actual biomechanics, and what makes the pose's ground contacts stable.
 *  * **The trunk folds** as the two-segment spine: [TRUNK_FOLD] at the root plus [THORACIC_FOLD] at the
 *    chest, so the shoulders come low instead of a single rigid hinge.
 *  * **The cervical chain tucks** a further [CERVICAL_TUCK], both bones authored as their own directions
 *    in the chest's frame (`resolveHeadTarget` is the sole writer of those offsets and only runs for a
 *    pose that declared a gaze target, so this authored chain reaches the published frame verbatim).
 *  * **The arms** travel from the tall kneel's hanging rest to a constant offset in front of the fold's
 *    shoulder, so at the hold the hands rest on the mat well ahead of the head.
 *  * **The feet lie along the shins** (the top of the foot down): the heading channel carries them
 *    backward, which is why the derivation lays them flat instead of standing them up.
 *
 * Bounds, measured: the hip→ankle chord runs `148.8 u` (kneel) → `64.5 u` (hold) of the leg chain's
 * `[56.01, 205.80] u` band, so the sit-back could not pass `58°` without folding past the chain's own stop
 * (the copy's "forcing the hips to the heels when mobility is limited", honoured structurally). The hands'
 * reach is `123.2 u` of the arm chain's `[40.13, 143.08] u` band; the knee layer is [KNEE_Y] `= 15 u`.
 *
 * Recorded gaps: `metadata.support` is per-pose, not per-frame, so the kneeling base is the HOLD's — the
 * hands are lifted through the entry and the rise, while the knees are on the mat all cycle. The forehead
 * has no `SupportPoint` (`SupportMath.jointsFor` carries no head entry), so its mat relationship is
 * authored geometry ([headTipY]) and not declared; the rig has no grip DOF, so the hands "reach long" by
 * placement, not by curling; the conditional knee widening is recorded rather than authored (a second
 * pose would be a second exercise); and there is no breath channel, so the hold's plateau carries that
 * cue's kinematics only.
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

        /** The knees sit under their own hips (the neutral, un-widened kneel; widening is recorded, not authored). */
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

        fun kneelHipY(def: SkeletonDefinition): Float = hipY(def, 0f)

        /** The hold's hip X (`alpha = `[FOLD_ALPHA]). */
        fun foldHipX(def: SkeletonDefinition): Float = hipX(def, FOLD_ALPHA)

        fun foldHipY(def: SkeletonDefinition): Float = hipY(def, FOLD_ALPHA)

        /** The fold's chest X: the hip plus the trunk's own fold. */
        fun foldChestX(def: SkeletonDefinition): Float =
            foldHipX(def) + def.torsoLength * sin(TRUNK_FOLD)

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
