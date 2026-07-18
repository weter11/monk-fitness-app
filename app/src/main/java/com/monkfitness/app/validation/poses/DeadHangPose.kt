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

/**
 * Engineering Validation — Dead Hang.
 *
 * Static, frozen snapshot. Validates: fixed hand contacts, hanging geometry, shoulder
 * girdle, IK, arm proportions, bar alignment, wrist orientation.
 */
class DeadHangPose : BaseValidationPose() {

    private val barY = 500f
    private val gripWidthFactor = 1.5f

    private val verticalPullEnvironment = EnvironmentDefinition(
        // The ground grid is shown for spatial reference; the hanging body (lowest point the
        // ankle at ~y=41) never reaches the level-0 floor, so enabling it is purely visual.
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
        // The body hangs from the floor (y≈41, ankle) up to the bar (y=500), so it is entirely
        // above the world origin, which the renderer places at 70% down the viewport. A tall
        // subject therefore clips at the top unless zoom is low enough that the full ~500u height
        // fits between the origin and the top edge. zoom=0.7 keeps the bar, arms, torso and legs
        // all on-screen for typical viewports (verified at 600/800/1000px canvases: the bar/hands
        // sit ~32-326px from the top, the ankle ~390-670px down). yaw/pitch kept as the standard
        // 3/4 side view.
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

        // Phase 2 (F2): declare the HANGING_UNDER_BAR intent so the solver can derive the pelvis
        // height from the overhead bar contact when it owns posture (it seeds ~ barY - reach -
        // torsoLength, matching the authored value here). The solver then honours the bar contacts.
        declarePosture(PostureIntent.Kind.HANGING_UNDER_BAR)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // B2: route the trunk (pelvis + chest) through the declarative spine curve so the
        // carriers are populated (spineIntent + jointIntents).
        buildSpineCurve(pelvis!!, chest!!, torsoPitch, 0f, axisZ)

        buildGaze(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        // Shoulder girdle: a dead hang depresses the scapulae (shoulders pulled down away from
        // the ears, audit §4.3). The previous pose left the girdle a rigid pass-through.
        buildScapularRotation(scapulaA!!, retraction = 0f, depression = 1f, sideSign = -1f)
        buildScapularRotation(scapulaP!!, retraction = 0f, depression = 1f, sideSign = 1f)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // FIXED hands on the bar (constant world targets; the body hangs from them).
        val targetA = Vector3(0f, barY, -gZ)
        val targetP = Vector3(0f, barY, gZ)
        val armPoleA = Vector3(0f, -1f, 0f)
        val armPoleP = Vector3(0f, -1f, 0f)
        // Hands are fixed contacts on the bar: clamp the IK end onto the bar plane (normal +Y at
        // barY) so the over-clamp can't drag the grip off the bar (PR-03). Target is unchanged.
        // Arms opt into full extension so they render perfectly straight (PR-11).
        val barContact = ContactConstraint(Vector3(0f, 1f, 0f), Vector3(0f, barY, 0f))
        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, armPoleA, armStraightConstraint(def), chest!!.worldRotation, elbowA!!, handA!!, armABuffer, straight = true, contact = barContact)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, armPoleP, armStraightConstraint(def), chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, straight = true, contact = barContact)

        // Overhand grip: fingers wrap up and over the top of the bar. A +90° wrist rotation
        // turns the rigid hand's forward axis (+X) upward (+Y) so the fingertips curl over the
        // bar (audit §4.2 — the previous -90° splayed the hand sideways). The palm thus faces
        // forward away from the body, the correct overhand hang. The engine derives palm/knuckles/
        // fingertips from the forearm + this wrist articulation, cancelling the inherited torso
        // tilt automatically (no hand-authored heel/toe/palm endpoints, no -torsoPitch term).
        // Branch C: the overhand wrist articulation is now the §1.3 intent carried in
        // `extremityArticulations` (single source of truth), composed via buildWristRotation so the
        // 2-DOF vocabulary is explicit. flexion = +90° about the mediolateral Z axis (no deviation).
        buildWristArticulation(Extremity.HAND_A, PI.toFloat() / 2f, 0f, handA!!)
        buildWristArticulation(Extremity.HAND_P, PI.toFloat() / 2f, 0f, handP!!)

        // Legs hang straight down with a slight forward pendulum.
        val ankleX = comX - 18f
        val ankleY = pelvisY - 200f
        val targetF = Vector3(ankleX, ankleY, -def.hipWidth * 0.9f)
        val targetB = Vector3(ankleX, ankleY, def.hipWidth * 0.9f)
        val legPoleF = Vector3(0.15f, 1f, 0f)
        val legPoleB = Vector3(0.15f, 1f, 0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, legStraightConstraint(def), pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, straight = true)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, legStraightConstraint(def), pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, straight = true)

        // The engine derives heel/toe from the shank (knee→ankle) + the neutral ankle articulation,
        // laying the hanging foot out flat — no manual endpoint authoring, no tilt counter-rotation.

        return finalizePose()
    }
}
