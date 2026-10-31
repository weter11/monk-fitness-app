package com.monkfitness.app.validation.poses

import com.monkfitness.app.animation.BoxProp
import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.EnvironmentAnchor
import com.monkfitness.app.animation.EnvironmentAnchorType
import com.monkfitness.app.animation.EnvironmentDefinition
import com.monkfitness.app.animation.GroundDefinition
import com.monkfitness.app.animation.PivotType
import com.monkfitness.app.animation.PostureIntent
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Extremity
import com.monkfitness.app.animation.SupportContact
import com.monkfitness.app.animation.SupportDefinition
import com.monkfitness.app.animation.SupportPoint
import com.monkfitness.app.animation.Vector3
import kotlin.math.PI

/** Engineering Validation — Dead Hang: fixed hand contacts, hanging geometry, girdle, IK, arm proportions, bar alignment, wrist orientation. */
class DeadHangPose : BaseValidationPose() {

    private val barY = 500f
    private val gripWidthFactor = 1.5f

    private val verticalPullEnvironment = EnvironmentDefinition(
        // The ground grid is spatial reference only: the hanging body never reaches level 0.
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

    override val metadata = staticMetadata(
        // The body spans y≈41 (ankle) to the bar at 500, all above the origin, which the renderer places
        // 70% down the viewport: a tall subject clips at the top unless zoom fits the whole ~500u height,
        // so 0.7 keeps bar, arms, torso and legs on-screen (verified at 600/800/1000px canvases).
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.2f, defaultZoom = 0.7f),
        environment = verticalPullEnvironment,
        support = SupportDefinition(
            pivot = PivotType.HANDS,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND, anchorId = "pullup_bar"),
                SupportContact(SupportPoint.RIGHT_HAND, anchorId = "pullup_bar")
            )
        )
    )

    override fun buildStatic(def: SkeletonDefinition): SkeletonPose {
        ensureHierarchy(def)

        val gZ = gripWidthFactor * def.shoulderWidth
        val reach = 139f

        val torsoPitch = 0f
        val pelvisY = barY - reach - def.torsoLength
        val comX = 0f

        pelvis!!.localPosition.set(comX, pelvisY, 0f)

        // Declaring the intent lets the solver derive the pelvis height from the overhead bar contact
        // (it seeds ~barY - reach - torsoLength, matching the authored value).
        declarePosture(PostureIntent.Kind.HANGING_UNDER_BAR)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildSpineCurve(pelvis!!, chest!!, torsoPitch, 0f, axisZ)

        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        // A dead hang depresses the scapulae — shoulders pulled down away from the ears.
        buildScapularRotation(scapulaA!!, retraction = 0f, depression = 1f, sideSign = -1f)
        buildScapularRotation(scapulaP!!, retraction = 0f, depression = 1f, sideSign = 1f)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        val targetA = Vector3(0f, barY, -gZ)
        val targetP = Vector3(0f, barY, gZ)
        val armPoleA = Vector3(0f, -1f, 0f)
        val armPoleP = Vector3(0f, -1f, 0f)
        // Clamping the IK end onto the bar plane keeps an over-clamp from dragging the grip off the bar;
        // the arms opt into full extension so they render straight. Target is unchanged.
        val barContact = ContactConstraint(Vector3(0f, 1f, 0f), Vector3(0f, barY, 0f))
        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, armPoleA, armStraightConstraint(def), chest!!.worldRotation, elbowA!!, handA!!, armABuffer, straight = true, contact = barContact)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, armPoleP, armStraightConstraint(def), chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, straight = true, contact = barContact)

        // +90° about Z turns the rigid hand's forward axis upward so the fingertips curl over the bar (the
        // previous -90° splayed the hand sideways). The engine derives palm/knuckles/fingertips from the
        // forearm plus this articulation, so no endpoints are hand-authored.
        buildWristArticulation(Extremity.HAND_A, PI.toFloat() / 2f, 0f, handA!!)
        buildWristArticulation(Extremity.HAND_P, PI.toFloat() / 2f, 0f, handP!!)

        val ankleX = comX - 18f
        val ankleY = pelvisY - 200f
        val targetF = Vector3(ankleX, ankleY, -def.hipWidth * 0.9f)
        val targetB = Vector3(ankleX, ankleY, def.hipWidth * 0.9f)
        val legPoleF = Vector3(0.15f, 1f, 0f)
        val legPoleB = Vector3(0.15f, 1f, 0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, legStraightConstraint(def), pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, straight = true)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, legStraightConstraint(def), pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, straight = true)

        // The engine derives heel/toe from the shank and the neutral ankle articulation, so the hanging
        // foot lies flat with no manual endpoints.

        return finalizePose()
    }
}
