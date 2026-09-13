package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.sqrt

/**
 * BaseCervicalPose — the single owner of the **cervical-mobility chassis** the animation-coverage
 * phase's neck pair is authored on (`ChinTuckPose`, `NeckCirclesPose`).
 *
 * ## Why a family base exists at all
 *
 * The two exercises are the catalog's whole `neck_mobility` family and they share one property no
 * earlier coverage batch met: **the movement lives entirely in the neck/head chain while the rest of
 * the body is a fixed, planted standing frame.** Everything below the head is therefore identical
 * between them — an upright trunk, both feet planted on the family's floor frame, the arms hanging
 * relaxed at the sides — and only the cervical driver differs (a retraction ramp, a cone). Authoring
 * that frame once, here, is what makes each member's own file read as its driver alone; it is the same
 * split `BaseThoracicPose` draws for the thoracic family (`Base*` files are excluded from the
 * repository's pose corpora, so this base adds no corpus membership).
 *
 * ## The rig's cervical chain, and the honest limit it imposes
 *
 * `SkeletonFactory.createStandardSkeleton()` builds `CHEST → NECK_END → HEAD_POS` as two rigid bones
 * (`definition.neckLength` = `18 u`, then a fixed `18 u` head), both of them children of the chest. So:
 *
 *  * **Cervical motion is authored as the chain's two BONE DIRECTIONS in the chain's own frame** — the
 *    neck's and the head's local offsets at their authored bone lengths ([authorCervicalChain]), which
 *    is the very arithmetic `SkeletonPoseFinalizer.resolveHeadTarget` writes for a declared gaze. Both
 *    bones therefore publish their direction, so an authored cervical position is observable in the
 *    published frame rather than hidden in a rotation. `resolveHeadTarget` is the *sole* writer of these
 *    offsets and it only runs for a pose that declared a world `headTarget`, so a pose that authors its
 *    own cervical geometry keeps it verbatim (a no-op resolver — stated at the resolver itself), which
 *    is also why neither member declares one.
 *  * **The chain is rigid, so a "retraction" is the neck's tilt carrying the head's base backwards**
 *    (see [ChinTuckPose]) and a "neck circle" is the head/tail axis describing a cone (see
 *    [NeckCirclesPose]). Neither is expressible as a translation channel: the rig has no cervical
 *    translation DOF, and the bone-length rule (`CHEST→NECK_END` = `neckLength`, `NECK_END→HEAD_POS` = `18`)
 *    is what keeps the authored motion anatomically bounded.
 *  * **A world-space `headTarget` is NOT usable here by construction** — for an upright chest the neck's
 *    local frame coincides with the world, and `resolveHeadTarget` writes `dir · 18` into the neck's
 *    *local* offset from a *world* delta. Declaring one would hand the head's placement to the resolver
 *    instead of to the exercise, so both members author their own chain (and the neck pair's tests
 *    measure the published chain against the authored angles).
 *
 * ## The shared standing chassis (authored, and why)
 *
 * * **Both feet planted, `1.2 × hipWidth` apart, on the family's floor frame** (`y = 25`, the standing
 *    corpus's ankle line — `HipCirclesPose`'s stance), declared on the one canonical support channel
 *    (`metadata.support`, `PivotType.FEET`), with each foot's heading authored forward so the
 *    declaration-driven derivation lays them flat and pointing the way the athlete stands.
 * * **An upright, still trunk**: the pelvis is authored at the standing height and tilted to vertical
 *    (no lean, no twist) and never moves — "Keep the torso quiet" / "Shrugging the shoulders" are both
 *    mistakes in the exercised copy, and a still frame is the only representation of the cue that this
 *    rig can publish.
 * * **The arms hang relaxed at the sides** ([armRestDirection], [ARM_REST_RADIUS]). The neck pair's copy
 *    says nothing about the arms (one member has no copy at all), so this is an authored convention —
 *    stated as such — chosen because a neck drill's arms are inert and must not shadow the cervical
 *    motion in the hero; it is also the standing rest the corpus already publishes
 *    (`ArmCirclesPose`'s hanging frame).
 *
 * ## Recorded gaps (no invented specification)
 *
 *  * The rig carries **no jaw, no gaze-target and no cervical-translation channel**: "Keep the jaw
 *    relaxed" (chin tucks' `tech`) and the neck pair's "feel long at the top" have no representation and
 *    are not faked.
 *  * The exercise copy's *"Stand tall **or lie on your back**"* (chin tucks §1) is a choice of two
 *    positions; the standing one is authored (the drill's catalog entry places it in a standing warm-up
 *    flow and the timer variant is performed standing), and the supine option is recorded here rather
 *    than duplicated into a second pose.
 *  * The stance width/depth, the arms' rest radius and each member's cervical amplitude are authored
 *    constants (the copy states none of them); every one is declared at its own constant with its
 *    reach/anatomy bound measured in the member's KDoc.
 */
abstract class BaseCervicalPose : BasePose() {

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null
    protected var chest: SkeletonNode? = null
    protected var neck: SkeletonNode? = null
    protected var head: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null
    protected var elbowA: SkeletonNode? = null
    protected var handA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null
    protected var elbowP: SkeletonNode? = null
    protected var handP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null
    protected var kneeF: SkeletonNode? = null
    protected var ankleF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null
    protected var kneeB: SkeletonNode? = null
    protected var ankleB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    /** The standing family's own camera (`ArmCirclesPose`/`HipCirclesPose`); framing is not tuned here. */
    protected val cervicalCamera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f)

    protected val cervicalGround = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

    /** The planted-feet base, on the ONE canonical support channel (`metadata.support`). */
    protected val cervicalSupport = SupportDefinition(
        pivot = PivotType.FEET,
        contacts = setOf(
            SupportContact(SupportPoint.LEFT_FOOT),
            SupportContact(SupportPoint.RIGHT_FOOT)
        )
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

    /**
     * The shared chassis: an upright still trunk, the planted stance, the resting arms and the neutral
     * cervical chain. Runs the authoring FK pass, so every chain root the member's own driver composes
     * against (the shoulders, the hips) carries THIS build's world transform.
     */
    protected fun buildCervicalChassis(def: SkeletonDefinition) {
        ensureHierarchy()
        // Shape-driven root (the stance is authored arithmetic), so the solver leaves it untouched.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        pelvis!!.localPosition.set(0f, STANCE_PELVIS_Y, 0f)
        // "Keep the torso quiet": the root is vertical and, like the hips, never travels.
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // The neutral cervical chain, authored in the chain's own frame (see the class KDoc).
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, HEAD_BONE_LENGTH, 0f)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        // The stance: both ankles pinned on the family's floor frame for the whole drill.
        val footZ = footZ(def)
        bakeIkLimb(
            hipF!!.worldPosition, Vector3(0f, FLOOR_ANKLE_Y, -footZ), def.thighLength, def.shinLength,
            legPoleF, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer
        )
        bakeIkLimb(
            hipB!!.worldPosition, Vector3(0f, FLOOR_ANKLE_Y, footZ), def.thighLength, def.shinLength,
            legPoleB, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer
        )
        // The feet keep their own line for the whole drill (the drill moves the neck, not the body).
        setHeading(Extremity.FOOT_F, Vector3(1f, 0f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(1f, 0f, 0f))

        // The resting arms: hanging at the sides, riding the still shoulders they hang from.
        val dirA = armRestDirection(-1f, tempV1)
        bakeIkLimb(
            shoulderA!!.worldPosition,
            Vector3(
                shoulderA!!.worldPosition.x + dirA.x * ARM_REST_RADIUS,
                shoulderA!!.worldPosition.y + dirA.y * ARM_REST_RADIUS,
                shoulderA!!.worldPosition.z + dirA.z * ARM_REST_RADIUS
            ),
            def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint,
            chest!!.worldRotation, elbowA!!, handA!!, armABuffer
        )
        val dirP = armRestDirection(1f, tempV2)
        bakeIkLimb(
            shoulderP!!.worldPosition,
            Vector3(
                shoulderP!!.worldPosition.x + dirP.x * ARM_REST_RADIUS,
                shoulderP!!.worldPosition.y + dirP.y * ARM_REST_RADIUS,
                shoulderP!!.worldPosition.z + dirP.z * ARM_REST_RADIUS
            ),
            def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint,
            chest!!.worldRotation, elbowP!!, handP!!, armPBuffer
        )
    }

    /**
     * Authors the cervical chain as **two bone directions in the chain's own frame**: `CHEST → NECK_END`
     * along [neckDir] and `NECK_END → HEAD_POS` along [headDir] (the head's offset is expressed in the
     * neck's frame, which is the chest's frame while the chain carries no independent rotation). This is
     * the same channel and the same arithmetic `SkeletonPoseFinalizer.resolveHeadTarget` writes — the
     * neck/head local offsets at their authored bone lengths — so a member's cervical geometry is the
     * canonical representation, and it is fully **observable in the published positions** (both bones
     * publish their direction), which is what lets a test separate a retraction from a nod.
     *
     * The member's driver calls this AFTER [buildCervicalChassis] and BEFORE [finalizeCervical], and a
     * member that authors its own cervical geometry declares **no** `headTarget`: the resolver is the
     * sole writer of these offsets and only runs for a pose that declared one, so an authored chain is
     * kept verbatim (stated at the resolver).
     */
    protected fun authorCervicalChain(
        neckLength: Float,
        neckDir: Vector3,
        headLength: Float,
        headDir: Vector3
    ) {
        neck!!.localPosition.set(neckDir.x * neckLength, neckDir.y * neckLength, neckDir.z * neckLength)
        head!!.localPosition.set(headDir.x * headLength, headDir.y * headLength, headDir.z * headLength)
    }

    /** Flattens the authored hierarchy into the published carrier (the family's shared tail). */
    protected fun finalizeCervical(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /** The classic 6-15-10 easing step, shared by the family's ramps (see the package-level helper). */
    protected fun smootherStep(t: Float): Float = cervicalSmootherStep(t)

    /** A hanging arm's direction: a hair forward of the shoulder's vertical, that side outboard. */
    protected fun armRestDirection(sideSign: Float, out: Vector3): Vector3 {
        val f = 0.08f
        val o = 0.03f
        val len = sqrt(f * f + 1f + o * o)
        out.set(f / len, -1f / len, sideSign * o / len)
        return out
    }

    companion object {
        /**
         * The standing height: `226 u`, i.e. `201 u` of leg drop under the hip — the same light athletic
         * stance `HipCirclesPose` publishes (the leg chain's `210 u` span stays comfortably inside its
         * `0.98` extension cap: the authored chord measures `201.05 u`).
         */
        const val STANCE_PELVIS_Y = 226f

        /** The planted ankles: the standing family's floor frame (`HipCirclesPose` authors the same). */
        const val FLOOR_ANKLE_Y = 25f

        /** The head bone's authored length — the engine's own `NECK_END → HEAD_POS` length. */
        const val HEAD_BONE_LENGTH = 18f

        /**
         * The hanging arm's length: `140 u` of the `80 + 66 = 146 u` chain. The hand therefore rests
         * `3.08 u` inside the `0.98` extension cap with the elbow reading `146.8°` interior — a relaxed
         * hanging arm, and reachable by construction (`maxIkClampAmount == 0`).
         */
        const val ARM_REST_RADIUS = 140f

        /** `1.2 × hipWidth` each side of the mid-line (the corpus's narrow standing stance). */
        fun footZ(def: SkeletonDefinition): Float = def.hipWidth * 1.2f

        private val legPoleF = Vector3(1f, 0f, -0.2f)
        private val legPoleB = Vector3(1f, 0f, 0.2f)
        private val armPoleA = Vector3(0f, -1f, -1f)
        private val armPoleP = Vector3(0f, -1f, 1f)
    }
}

/**
 * The classic 6-15-10 easing step (`x³(6x² − 15x + 10)`), the family's shared ramp shape. Package-level
 * so each member's own `companion` (whose readers the tests call statically) can share it without a
 * second copy — a companion object cannot see a member function of the class it belongs to.
 */
internal fun cervicalSmootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}
