package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class WidePushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.9f
    override val poleA = Vector3(0.2f, 0.8f, -2.0f)
    override val poleP = Vector3(0.2f, 0.8f, 2.0f)

    override fun build(context: PoseContext): SkeletonPose {
        val pose = super.build(context)
        // Wide push-up: hands splayed outward (laterally), not forward.
        // Override the BODY_FORWARD heading inherited from BasePushUpPose.
        // LEFT_HAND (HAND_A) points left (+Z in pelvis frame).
        // RIGHT_HAND (HAND_P) points right (-Z in pelvis frame).
        pose.headings[Extremity.HAND_A] = Vector3(0f, 0f, 1f)
        pose.headings[Extremity.HAND_P] = Vector3(0f, 0f, -1f)
        return pose
    }

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_TOES),
                SupportContact(SupportPoint.RIGHT_TOES)
            )
        ),
        pivotType = PivotType.FEET,
        supportContacts = setOf(
            SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND,
            SupportContact.LEFT_TOES, SupportContact.RIGHT_TOES
        ),
        exerciseFamily = "push-up",
        motionType = "Press",
        bodyOrientation = "Prone"
    )
}
