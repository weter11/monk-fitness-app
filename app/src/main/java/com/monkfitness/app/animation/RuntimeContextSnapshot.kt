package com.monkfitness.app.animation

/**
 * Captures the freshly injected Frame Context carriers so [SkeletonPipeline.runStages] can verify
 * that no stage mutates them after injection: the pipeline is the sole writer and the context is
 * frozen once injected.
 *
 * Debug-only by construction: a snapshot is allocated only under `BuildConfig.DEBUG` and release
 * builds compile the mechanism out. Unit tests run with `BuildConfig.DEBUG == true`, so the JVM
 * suite does exercise the check.
 */
internal object RuntimeContextSnapshot {

    /** Immutable capture of the injected context of [pose]. */
    fun of(pose: SkeletonPose): Snapshot = Snapshot(pose.environment, pose.supportedPoints.toSet())

    data class Snapshot(
        private val environment: EnvironmentDefinition,
        private val supportedPoints: Set<SupportPoint>
    ) {
        /**
         * Throws [IllegalStateException] if the pose's context no longer
         * matches the injected snapshot. Equality is content-based, so a mid-stage re-derivation
         * producing a *content-identical* environment still passes — such a rewrite is semantically
         * a no-op, and any observable change trips the guard.
         */
        fun assertUnchanged(pose: SkeletonPose, atStage: String) {
            check(environment == pose.environment) {
                "R8 violation: environment mutated $atStage"
            }
            check(supportedPoints == pose.supportedPoints.toSet()) {
                "R8 violation: supportedPoints mutated $atStage"
            }
        }
    }
}
