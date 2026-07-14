package com.monkfitness.app.validation.poses

import com.monkfitness.app.animation.BoxProp
import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.EnvironmentAnchor
import com.monkfitness.app.animation.EnvironmentAnchorType
import com.monkfitness.app.animation.EnvironmentDefinition
import com.monkfitness.app.animation.GroundDefinition
import com.monkfitness.app.animation.PivotType
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
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
        ground = GroundDefinition(visible = false, level = 0f),
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
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.5f),
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
        pelvis!!.localRotation.set(axisZ, torsoPitch)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        chest!!.localRotation.set(axisZ, 0f)

        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // FIXED hands on the bar (constant world targets; the body hangs from them).
        val targetA = Vector3(0f, barY, -gZ)
        val targetP = Vector3(0f, barY, gZ)
        val armPoleA = Vector3(0f, -1f, 0f)
        val armPoleP = Vector3(0f, -1f, 0f)
        val invChestZ = -torsoPitch
        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, invChestZ, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, invChestZ, elbowP!!, handP!!, armPBuffer)

        // Overhand grip: hands rotate so palms face away from the bar.
        val gripAngle = invChestZ - (PI.toFloat() / 2f)
        handA!!.localRotation.set(axisZ, gripAngle); handP!!.localRotation.set(axisZ, gripAngle)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        // Legs hang straight down with a slight forward pendulum.
        val invTorsoZ = -torsoPitch
        val ankleX = comX - 18f
        val ankleY = pelvisY - 200f
        val targetF = Vector3(ankleX, ankleY, -def.hipWidth * 0.9f)
        val targetB = Vector3(ankleX, ankleY, def.hipWidth * 0.9f)
        val legPoleF = Vector3(0.15f, 1f, 0f)
        val legPoleB = Vector3(0.15f, 1f, 0f)
        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, def.legIKConstraint, invTorsoZ, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, def.legIKConstraint, invTorsoZ, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, invTorsoZ); ankleB!!.localRotation.set(axisZ, invTorsoZ)
        heelF!!.localPosition.set(def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(-def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(-def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        return finalizePose()
    }
}
