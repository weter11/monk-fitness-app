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
 * Per-exercise declaration surface: camera/motion metadata plus the **single support declaration**
 * channel, [support].
 *
 * ## B-2 — one support declaration channel
 *
 * The support fact (which pivot the body works about, and which body points rest on the
 * environment) has exactly ONE declaration site: [support] (`SupportDefinition`, i.e. `pivot` +
 * `contacts`). The engine reads it on every production path:
 *
 * ```
 * PoseMetadata.support.contacts ──> SkeletonPipeline.buildAndInject ──┐
 *                                └─> ExerciseAnimation (renderer path) ┴─> SkeletonPose.supportedPoints
 *                                                                          └─> SkeletonPoseFinalizer
 *                                                                              (extremity/support derivation)
 * ```
 *
 * `PoseMetadata` previously carried two further copies of the same fact —
 * `supportContacts: Set<SupportContact>` and `pivotType: PivotType` — which no production reader
 * ever consulted (both had writers and zero readers in `app/src/main`; the pipeline and the UI both
 * read `support.contacts`). Two poses (`StaticForearmPlankPose`, `IsometricSidePlankPose`) declared
 * their support ONLY on the unread `supportContacts` channel, so the runtime published an EMPTY
 * support model for them and the Finalizer's support-plane derivation was entirely off; a third
 * `pivotType` copy diverged from `support.pivot` for exactly those two poses. Both duplicate
 * channels were removed (B-2) so the duplication cannot recur — declaring support is now
 * unambiguous, and a pose cannot compile a declaration the engine does not read.
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
