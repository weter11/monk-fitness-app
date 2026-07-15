package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseVerticalPullPose is the single owner of all shared Vertical-Pull-family scaffolding.
 *
 * The Vertical Pull family is a biomechanics-first rewrite (not a modernization of the
 * old rigid-object math). Every member — dead hang, scapular pull-up, and the
 * overhand / wide / chin-up / neutral pull-ups — is a hanging, bar-supported
 * vertical pull. What they genuinely share is engine plumbing and the pull mechanics,
 * not choreography:
 *
 *  - Skeleton hierarchy via [SkeletonFactory.createStandardSkeleton]
 *  - A fixed bar anchor + bar prop (oriented laterally so it passes under the hands)
 *  - Reusable, allocation-free IK buffers and scratch targets/poles
 *  - IK baking via [BasePose.bakeIkLimb] (frame-relative poles, stable in the chest/pelvis frame)
 *  - FIXED hand targets on the bar: the hands NEVER move, so the body is pulled
 *    up *relative to* the fixed contacts. This is what makes the grip read as "attached".
 *  - A scapula-led pull: the arms are IK'd to the fixed bar, so the body height
 *    is derived from a desired shoulder->bar reach. Because the bottom of a pull-up is
 *    the fully-extended (straight-arm) position, the earliest part of every rep can
 *    only be scapular (the body rises a few units while the arms stay straight); only
 *    once the reach shortens do the elbows bend. The scapular depression/retraction
 *    is therefore emergent AND reinforced by an explicit shoulder-girdle offset.
 *  - A breathing / stabilization micro-driver that is zero at the rep endpoints
 *  - Shared camera + ground/bar environment
 *  - Finalization (FK flatten, wrist mirroring, IK-clamp reporting)
 *
 * Everything that differs — grip width, grip mechanics, elbow path, how much the
 * chest leads to the bar — lives in the concrete variant, because it is Vertical
 * Pull biomechanics, not engine knowledge. No speculative helpers are added here.
 */
enum class GripStyle { OVERHAND, UNDERHAND, NEUTRAL }

abstract class BaseVerticalPullPose : BasePose() {

    protected val barY = 500f

    protected val verticalPullCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.5f
    )

    protected val verticalPullEnvironment = EnvironmentDefinition(
        ground = GroundDefinition(visible = true, level = 0f),
        props = listOf(
            BoxProp(center = Vector3(0f, barY - 5f, 0f), width = 8f, height = 10f, depth = 240f)
        ),
        anchors = listOf(
            EnvironmentAnchor(
                id = "pullup_bar",
                type = EnvironmentAnchorType.BAR,
                worldPosition = Vector3(0f, barY, 0f)
            )
        )
    )

    protected val verticalPullSupportContacts = setOf(
        SupportContact(point = SupportPoint.LEFT_HAND, anchorId = "pullup_bar"),
        SupportContact(point = SupportPoint.RIGHT_HAND, anchorId = "pullup_bar")
    )

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    protected var clavicleA: SkeletonNode? = null; protected var scapulaA: SkeletonNode? = null; protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var clavicleP: SkeletonNode? = null; protected var scapulaP: SkeletonNode? = null; protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult(); protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult(); protected val armPBuffer = SkeletonMath.IKResult()

    protected val targetA = Vector3(); protected val targetP = Vector3()
    protected val targetF = Vector3(); protected val targetB = Vector3()
    protected val poleA = Vector3(); protected val poleP = Vector3()
    protected val poleF = Vector3(); protected val poleB = Vector3()
    protected val scratchShoulderA = Vector3(); protected val scratchShoulderP = Vector3()

    private val HALF_PI = PI.toFloat() / 2f
    private val neutralAxisA = SkeletonMath.rotAround(Vector3(0f, 0f, 1f), Vector3(1f, 0f, 0f), HALF_PI, Vector3())
    private val neutralAxisP = SkeletonMath.rotAround(Vector3(0f, 0f, 1f), Vector3(1f, 0f, 0f), -HALF_PI, Vector3())

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        clavicleA = nodes.clavicleA; scapulaA = nodes.scapulaA; shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        clavicleP = nodes.clavicleP; scapulaP = nodes.scapulaP; shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    protected abstract val gripStyle: GripStyle
    protected abstract val gripWidthFactor: Float
    protected abstract val elbowPoleA: Vector3
    protected abstract val elbowPoleP: Vector3
    protected abstract val bottomReach: Float
    protected abstract val topReach: Float

    protected open fun torsoPitchAt(rep: Float): Float = SkeletonMath.lerp(-0.05f, -0.02f, rep)
    protected open fun chestFlexAt(rep: Float): Float = SkeletonMath.lerp(0f, 0.05f, rep)
    protected open fun forwardArcAt(rep: Float): Float = 0f
    protected open fun scapularRetractionAt(rep: Float): Float = SkeletonMath.lerp(0f, 4f, rep)
    protected open fun scapularDepressionAt(rep: Float): Float = SkeletonMath.lerp(0f, 1f, rep)

    // Clavicular (proximal girdle) activation. The clavicle sits between the chest and the
    // scapula (CHEST -> CLAVICLE -> SCAPULA -> SHOULDER) and contributes elevation,
    // protraction and axial rotation on top of the scapula, raising the shoulder (glenoid)
    // on overhead reaches (UNI-7). Defaults to 0 so the references keep the girdle near
    // neutral (matching the scapula, which they also leave near neutral); production
    // overhead variants opt in via these overrides.
    protected open fun clavicularElevationAt(rep: Float): Float = 0f
    protected open fun clavicularProtractionAt(rep: Float): Float = 0f
    protected open fun clavicularAxialAt(rep: Float): Float = 0f
    protected open fun breathWave(lift: Float): Float = sin(lift * PI.toFloat())
    protected open val plantarFlexion: Float = 0.6f

    protected fun gripZ(def: SkeletonDefinition): Float = gripWidthFactor * def.shoulderWidth

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val lift = context.progress
        val breath = breathWave(lift)
        val rep = lift

        // --- 1. Pull height derived from a desired shoulder->bar reach ------------
        // The arms are IK'd to the FIXED bar (see section 2), so the only free
        // variable is how far the shoulder sits below the bar. That distance IS the
        // pull: large = dead hang (straight arms), small = contracted (bent arms).
        val reach = SkeletonMath.lerp(bottomReach, topReach, rep)

        // Spine posture (subtle hollow -> neutral; chest leads up at the top).
        val torsoPitch = torsoPitchAt(rep)
        val chestFlex = chestFlexAt(rep)

        // Scapular girdle: shoulders retract (squeeze toward the spine) and depress
        // (drop slightly relative to the rib cage) as the pull progresses.
        val retraction = scapularRetractionAt(rep)
        val depression = scapularDepressionAt(rep)

        // Body arc: the pelvis shifts so the chest reaches toward the bar at the top.
        val forwardArc = forwardArcAt(rep)
        val comX = forwardArc + breath * 2f

        val gZ = gripZ(def)
        val dz = gZ - def.shoulderWidth
        val dx = -comX
        val horiz2 = dx * dx + dz * dz
        val vertReach = sqrt(max(reach * reach - horiz2, 1f))
        val shoulderY = barY - vertReach
        val pelvisY = shoulderY - def.torsoLength

        pelvis!!.localPosition.set(comX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, torsoPitch)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        chest!!.localRotation.set(axisZ, chestFlex)

        // Head follows the thorax by FK; a tiny gaze lift toward the bar at the top.
        val gaze = tempV3.set(0f, 1f, 0f)
        SkeletonMath.rotAround(gaze, axisZ, -chestFlex - breath * 0.03f, gaze)
        gaze.normalize()
        buildHead(neck!!, head!!, def.neckLength, gaze)

        // Shoulder girdle: scapular depression + retraction are REAL scapula rotations
        // (BIOMECHANICS.md §4/§10) — the shoulder (glenoid) position is *derived* from the
        // scapula's local rotation, never translated by hand. `depression`/`retraction`
        // drive the scapula; the IK root (shoulder) inherits the resulting frame.
        SkeletonMath.buildScapularRotation(retraction, depression, -1f, scapulaA!!.localRotation)
        SkeletonMath.buildScapularRotation(retraction, depression, 1f, scapulaP!!.localRotation)
        // Clavicle (UNI-7): proximal girdle node, composed between chest and scapula so the
        // shoulder (glenoid) inherits BOTH girdle joints. Driven by the same rep activation as
        // the scapula; defaults to 0 so references stay near-neutral, production pull-ups opt
        // in via the clavicular*At overrides.
        buildClavicularRotation(
            clavicleA!!,
            clavicularElevationAt(rep),
            clavicularProtractionAt(rep),
            clavicularAxialAt(rep),
            -1f
        )
        buildClavicularRotation(
            clavicleP!!,
            clavicularElevationAt(rep),
            clavicularProtractionAt(rep),
            clavicularAxialAt(rep),
            1f
        )
        // The shoulder rests at its anatomical offset from the (rotated) scapula; its live
        // world position follows the scapula rotation automatically through FK.
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // --- 2. FIXED hands on the bar (constant targets, never translated) ----
        // The hand world targets are authored constants; the body moves relative to them.
        val invChestZ = -(torsoPitch + chestFlex)
        scratchShoulderA.set(shoulderA!!.worldPosition)
        scratchShoulderP.set(shoulderP!!.worldPosition)

        targetA.set(0f, barY, -gZ)
        targetP.set(0f, barY, gZ)
        // Bake the arm in the SHOULDER's world frame: the IK root is the shoulder, whose frame
        // now includes the scapula rotation, so the limb follows the girdle correctly.
        bakeIkLimb(scratchShoulderA, targetA, def.upperArmLength, def.forearmLength, shoulderA!!.worldRotation, elbowPoleA, def.armIKConstraint, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(scratchShoulderP, targetP, def.upperArmLength, def.forearmLength, shoulderP!!.worldRotation, elbowPoleP, def.armIKConstraint, elbowP!!, handP!!, armPBuffer)

        applyGrip(invChestZ)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        // --- 3. Legs: hanging pendulum with their own subtle life ----------
        val invTorsoZ = -torsoPitch
        val ankleX = comX - 18f - breath * 4f + rep * 8f
        val ankleY = pelvisY - 200f + breath * 3f - rep * 10f
        targetF.set(ankleX, ankleY, -def.hipWidth * 0.9f)
        poleF.set(0.15f, 1f, 0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, pelvis!!.worldRotation, poleF, def.legIKConstraint, kneeF!!, ankleF!!, legFBuffer)

        targetB.set(ankleX, ankleY, def.hipWidth * 0.9f)
        poleB.set(0.15f, 1f, 0f)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, pelvis!!.worldRotation, poleB, def.legIKConstraint, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, invTorsoZ - plantarFlexion)
        ankleB!!.localRotation.set(axisZ, invTorsoZ - plantarFlexion)
        heelF!!.localPosition.set(def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(-def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(-def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        return finalizeVerticalPullPose()
    }

    private fun applyGrip(invChestZ: Float) {
        val angle = invChestZ - HALF_PI
        when (gripStyle) {
            GripStyle.OVERHAND -> {
                handA!!.localRotation.set(axisZ, angle)
                handP!!.localRotation.set(axisZ, angle)
            }
            GripStyle.UNDERHAND -> {
                handA!!.localRotation.set(axisZ, invChestZ + HALF_PI)
                handP!!.localRotation.set(axisZ, invChestZ + HALF_PI)
            }
            GripStyle.NEUTRAL -> {
                handA!!.localRotation.set(neutralAxisA, angle)
                handP!!.localRotation.set(neutralAxisP, angle)
            }
        }
    }

    protected fun finalizeVerticalPullPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
