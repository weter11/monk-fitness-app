package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BaseVerticalPullPose owns the Vertical-Pull family's shared scaffolding. Every member is a hanging,
 * bar-supported vertical pull; what they share is engine plumbing and the pull mechanic:
 *
 *  - the hierarchy, the fixed bar anchor + bar prop (oriented laterally so it passes under the hands),
 *    IK baking via [BasePose.bakeIkLimb] with frame-relative poles, and the camera/environment;
 *  - FIXED hand targets: the hands NEVER move, so the body is pulled up *relative to* the fixed
 *    contacts, which is what makes the grip read as "attached";
 *  - a scapula-led pull — the arms are IK'd to the bar, so the body height is derived from a desired
 *    shoulder→bar reach. Because the bottom of a pull-up is the fully-extended position, the earliest
 *    part of every rep can only be scapular (the body rises a few units while the arms stay straight);
 *    the elbows bend only once the reach shortens.
 *
 * Grip width, grip mechanics, elbow path and how much the chest leads stay in the concrete variant.
 */
enum class GripStyle { OVERHAND, UNDERHAND, NEUTRAL }

abstract class BaseVerticalPullPose : BasePose() {

    protected val barY = 500f

    // A vertical pull travels a large vertical distance. At zoom 1.5 the subject overflowed the
    // frame and the head clipped off the TOP around ~frame 71+ (HEAD_VIEWPORT); 1.1 frames the full
    // athlete head-to-ankles across the whole rep without muting the validator. Yaw/pitch unchanged.
    protected val verticalPullCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.1f
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

    // Clavicular (proximal girdle) activation. The clavicle sits between the chest and the scapula
    // (CHEST -> CLAVICLE -> SCAPULA -> SHOULDER) and contributes elevation, protraction and axial
    // rotation on top of the scapula (UNI-7). Defaults to 0 so references keep the girdle near neutral;
    // production overhead variants opt in via these overrides.
    protected open fun clavicularElevationAt(rep: Float): Float = 0f
    protected open fun clavicularProtractionAt(rep: Float): Float = 0f
    protected open fun clavicularAxialAt(rep: Float): Float = 0f
    protected open fun breathWave(lift: Float): Float = sin(lift * PI.toFloat())
    protected open val plantarFlexion: Float = 0.6f

    protected fun gripZ(def: SkeletonDefinition): Float = gripWidthFactor * def.shoulderWidth

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // Every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val lift = context.progress
        val breath = breathWave(lift)
        val rep = lift

        // The arms are IK'd to the FIXED bar, so the only free variable is how far the shoulder sits
        // below the bar. That distance IS the pull: large = dead hang, small = contracted.
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

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Single spine-intent call. Lower segment is the
        // PELVIS (hips attach here, so the hanging legs inherit the lean).
        buildSpineCurve(pelvis!!, chest!!, torsoPitch, chestFlex)

        // Head follows the thorax by FK; a tiny gaze lift toward the bar at the top. Declared as a
        // gaze target while the legacy direction path still writes the head.
        val gaze = tempV3.set(0f, 1f, 0f)
        SkeletonMath.rotAround(gaze, axisZ, -chestFlex - breath * 0.03f, gaze)
        gaze.normalize()
        buildGaze(neck!!, head!!, def.neckLength, gaze)

        // Scapular depression + retraction are REAL scapula rotations (BIOMECHANICS.md §4/§10) — the
        // glenoid position is *derived* from the scapula's local rotation, never translated by hand; the
        // IK root (shoulder) inherits the resulting frame.
        SkeletonMath.buildScapularRotation(retraction, depression, -1f, scapulaA!!.localRotation)
        SkeletonMath.buildScapularRotation(retraction, depression, 1f, scapulaP!!.localRotation)
        // Clavicle (UNI-7): composed between chest and scapula so the glenoid inherits BOTH girdle joints,
        // driven by the same rep activation; defaults to 0 so references stay near-neutral.
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

        scratchShoulderA.set(shoulderA!!.worldPosition)
        scratchShoulderP.set(shoulderP!!.worldPosition)

        targetA.set(0f, barY, -gZ)
        targetP.set(0f, barY, gZ)
        // Bake in the SHOULDER's world frame: the IK root's frame now includes the scapula rotation, so
        // the limb follows the girdle correctly.
        val armAPoleWorld = SkeletonMath.toWorldDirection(elbowPoleA, elbowA!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(scratchShoulderA, targetA, def.upperArmLength, def.forearmLength, armAPoleWorld, def.armIKConstraint, shoulderA!!.worldRotation, elbowA!!, handA!!, armABuffer)
        val armPPoleWorld = SkeletonMath.toWorldDirection(elbowPoleP, elbowP!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(scratchShoulderP, targetP, def.upperArmLength, def.forearmLength, armPPoleWorld, def.armIKConstraint, shoulderP!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // The intentional grip is authored without the old inherited-tilt cancellation; the
        // engine removes torso/chest tilt relative to the forearm, so pass 0 and keep only the grip
        // style component (see applyGrip).
        applyGrip(0f)
        // The engine now derives palm/knuckles/fingertips from the forearm + this wrist articulation.

        val ankleX = comX - 18f - breath * 4f + rep * 8f
        val ankleY = pelvisY - 200f + breath * 3f - rep * 10f
        targetF.set(ankleX, ankleY, -def.hipWidth * 0.9f)
        // Leg reach authoring: as the body rises the hanging-leg target drifts past the leg's
        // reachable radius (`(thigh+shin)*0.98 = 205.8`), so the solver clamped and honestly reported it
        // (`maxIkClampAmount` up to ~4.45). Project onto the reachable band — reachable-by-construction —
        // so the pendulum legs hang at full-but-valid extension without muting the clamp signal.
        SkeletonMath.clampTargetToReach(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, def.legIKConstraint, targetF)
        poleF.set(0.15f, 1f, 0f)
        val legFPoleWorld = SkeletonMath.toWorldDirection(poleF, kneeF!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legFPoleWorld, def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)

        targetB.set(ankleX, ankleY, def.hipWidth * 0.9f)
        SkeletonMath.clampTargetToReach(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, def.legIKConstraint, targetB)
        poleB.set(0.15f, 1f, 0f)
        val legBPoleWorld = SkeletonMath.toWorldDirection(poleB, kneeB!!.parent!!.worldRotation, tempPoleWorld)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legBPoleWorld, def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // ankle plantar-flexion routes through the §1.3 intent carrier.
        buildAnkleArticulation(Extremity.FOOT_F, -plantarFlexion, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, -plantarFlexion, 0f, ankleB!!)
        // The engine now derives heel/toe (removed tilt counter-rotation; plantar flexion retained).

        return finalizeVerticalPullPose()
    }

    private fun applyGrip(chestTilt: Float) {
        val angle = chestTilt - HALF_PI
        when (gripStyle) {
            GripStyle.OVERHAND -> {
                buildWristArticulation(Extremity.HAND_A, angle, 0f, handA!!)
                buildWristArticulation(Extremity.HAND_P, angle, 0f, handP!!)
            }
            GripStyle.UNDERHAND -> {
                buildWristArticulation(Extremity.HAND_A, chestTilt + HALF_PI, 0f, handA!!)
                buildWristArticulation(Extremity.HAND_P, chestTilt + HALF_PI, 0f, handP!!)
            }
            GripStyle.NEUTRAL -> {
                // neutral wrist articulation (a pure deviation about the Y axis) routes
                // through the §1.3 intent carrier. neutralAxis* is ±Y, so flexion=0 and deviation
                // carries the signed angle (Ry(-angle) for the active hand, Ry(+angle) for passive).
                buildWristArticulation(Extremity.HAND_A, 0f, -angle, handA!!)
                buildWristArticulation(Extremity.HAND_P, 0f, angle, handP!!)
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
