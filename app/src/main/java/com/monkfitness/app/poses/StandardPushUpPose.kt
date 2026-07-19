package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class StandardPushUpPose : BasePushUpPose() {

    override val gripWidthMultiplier = 1.5f

    // Level-4 redesign: the rigid plank pitches as one unit so the chest leads the
    // descent and reaches near the floor at the bottom (BPS §9/§13; MSS §5;
    // BIOMECHANICS §11). sin(0.255)·torsoLength(120) ≈ 30, lowering the chest
    // from pelvisY(40 at bottom) to ~10 — within ~10 cm of the floor — while the
    // pelvis height (60→40) and all contacts are preserved via the existing solver
    // + arm IK. 0.255 rad ≈ 14.6° of trunk pitch at full descent.
    override val trunkDescentBottomPitch = 0.255f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = emptyList()
        ),
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
