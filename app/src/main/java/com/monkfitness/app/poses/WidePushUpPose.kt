package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class WidePushUpPose : BasePushUpPose() {
    // Architecture v2 conformance: this pose declares only typed intent the engine consumes
    // (grip width, elbow poles, posture CUSTOM via the base, and the support contacts). Dead
    // hand-orientation fields (handDirA/P) were removed — poses must not declare intent the
    // Finalizer ignores (W1 audit, frozen in docs/ARCHITECTURE_FREEZE.md).

    override val gripWidthMultiplier = 1.9f
    override val poleA = Vector3(0.2f, 0.8f, -2.0f)
    override val poleP = Vector3(0.2f, 0.8f, 2.0f)

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
        )
    )
}
