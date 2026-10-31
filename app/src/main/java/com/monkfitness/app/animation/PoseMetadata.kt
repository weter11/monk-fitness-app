package com.monkfitness.app.animation

enum class LoopMode {
    LOOP,
    HOLD,
    PING_PONG,
    ONCE
}

enum class FacingDirection {
    FRONT,
    LEFT,
    RIGHT
}

/**
 * Per-exercise declaration surface: camera/motion metadata plus the single support declaration
 * channel, [support].
 *
 * The support fact — which pivot the body works about and which body points rest on the
 * environment — has exactly ONE declaration site, [support] (`pivot` + `contacts`), and the engine
 * reads it on every production path. Duplicate channels for the same fact are deliberately absent:
 * a pose could declare support on a field no production reader consults — the runtime then
 * publishes an EMPTY support model and the support-plane derivation is entirely off — while a
 * second pivot copy diverges from `support.pivot`. One channel means a pose cannot compile a
 * declaration the engine does not read.
 */
data class PoseMetadata(
    val camera: CameraDefinition = CameraDefinition.DEFAULT,
    val durationSeconds: Float = 3.0f,
    val loopMode: LoopMode = LoopMode.LOOP,
    val supportsMirroring: Boolean = false,
    val groundHeight: Float = 0f,
    val initialFacing: FacingDirection = FacingDirection.FRONT,
    val environment: EnvironmentDefinition = EnvironmentDefinition(),
    val motionCurve: MotionCurve = MotionCurve.EASE_IN_OUT,
    val breathInFraction: Float = 0.40f,
    val breathHoldFraction: Float = 0.12f,
    val breathOutFraction: Float = 0.40f,
    val support: SupportDefinition = SupportDefinition(PivotType.FEET, emptySet()),
    val name: String = "",
    val exerciseFamily: String = "",
    val defaultGrip: String = "",
    val motionType: String = "",
    val bodyOrientation: String = ""
)
