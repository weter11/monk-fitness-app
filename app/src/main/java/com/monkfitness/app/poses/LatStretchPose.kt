package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

// P12 (§12.6): the direct solveIK import was removed with the bypass family migration;
// limbs are declared through the package-level bakeIkLimb (registered authoring bake).
class LatStretchPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                WallProp(
                    center = Vector3(45f, 90f, 0f),
                    width = 6f,
                    height = 180f,
                    depth = 40f
                )
            )
        ),
        // M9 — the planted feet on the ONE canonical support channel (`metadata.support`). The WALL
        // contact (the hand/forearm the stretch hangs off) is not declared: the wall's contact
        // plane is the M15 finding's territory, not this pass's.
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        )
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        // M11-a (P11 audit §3 row M11 / §4 "TODO — P1" item 2, "for full carrier coverage"):
        // the pose's own hand-rolled tree (PELVIS -> CHEST, CHEST -> SHOULDER_*) is replaced by the
        // canonical authored hierarchy. The two-segment spine and the shoulder girdle are the nodes
        // this tree simply did not have, so `LUMBAR`, `CLAVICLE_A/P` and `SCAPULA_A/P` were never
        // authored and published at the WORLD ORIGIN (measured `|LUMBAR − PELVIS| = 144.4507`) — the
        // exact legacy-tree signature the M3/M5 pass measured on `ReverseSnowAngelPose`. The
        // factory's added nodes are pass-throughs (coincident, identity rotation) between the
        // existing links, so every transform this pose authors resolves exactly as before; the
        // three-quarter chain (pelvis -> hip, shoulder -> palm/knuckles/fingertips,
        // knee -> ankle -> heel/toe) is the same set of nodes the hand-rolled tree built.
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)

        // 1. Core Hip Hinge Positioning
        // Smooth C2 cosine wave mapping progress 0.0 -> 0.5 (deepest stretch) -> 1.0 (release)
        val u = (1f - cos(context.progress * 2f * PI.toFloat())) * 0.5f

        val pulse = lerp(0f, 6f, u)
        val pelvisY = 135f - pulse
        val pelvisX = -65f
        val leanAngle = 0.95f

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), -leanAngle)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.PELVIS, JointRotation(Vector3(0f, 0f, 1f), -leanAngle))

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f); head!!.localPosition = Vector3(0f, 18f, 0f)
        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Flush Spine FK to get precise Hip and Shoulder origins
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. LEG TARGETS (Completely planted on the floor)
        val targetAnkleF = Vector3(-60f, 10f, -def.hipWidth * 1.5f)
        val targetAnkleB = Vector3(-60f, 10f, def.hipWidth * 1.5f)

        // P12 (§12.6): limbs are declared through the registered authoring bake — registration
        // (Limb Target + stamps) always; node realization only while the engine stage is off.
        // (Was: direct solveIK + raw-offset node writes — the bypass family.)
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The flat
        // foot on the forward-leaning shank is intentionally NOT hand-authored here; if the engine
        // derivation lands the foot imperfectly that is an engine limitation left exposed.

        // 3. ARM TARGETS (Hands placed flat on the wall prop)
        val targetHandA = Vector3(45f, 120f, -def.shoulderWidth * 0.8f)
        val targetHandP = Vector3(45f, 120f, def.shoulderWidth * 0.8f)

        // P12 (§12.6): arms likewise declared through the registered bake (was direct solveIK).
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // M11-b (recorded, deliberately NOT migrated — measured): the audit's "gaze helpers" half of
        // this finding is INAPPLICABLE to this pose, and declaring one would be a regression.
        // `SkeletonPoseFinalizer.resolveHeadTarget` (the sole head writer) derives the gaze DIRECTION
        // from a world delta (`headTarget.world − neck.worldPosition`) and writes it verbatim as the
        // neck/head LOCAL offset, which the neck's parent rotation then re-applies: the resolved
        // WORLD gaze is `neckParentRot · dir`. This pose's trunk is pitched `0.95` rad (`54.4°`), so a
        // world-space target resolves the head `0.9147` rad (`52.4°`) OFF the authored trunk axis —
        // measured by declaring the authored direction as a world target on this tree. That is the
        // B-7/B-8b resolver constraint the M3/M5 pass recorded for the prone family (`SupermanPose`
        // authors its head in the chain's own frame for exactly this reason, and the pose-side
        // conversion that would compensate is prohibited by `MIGRATION_RULES` A8). The sanctioned
        // representation for a pitched body is the one this pose already has: the head authored along
        // the neck's own local +Y (its BPS §4 "cervical spine neutral, following the trunk"), which
        // the published frame realizes exactly (guard: `M11M12LimbRealizationMigrationTest`).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
