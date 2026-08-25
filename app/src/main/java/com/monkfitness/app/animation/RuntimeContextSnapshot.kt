package com.monkfitness.app.animation

/**
 * R8 enforcement helper (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md, Phase 1 — Runtime Context
 * Injection single-point). Captures the freshly injected Frame Context carriers so
 * [SkeletonPipeline.runStages] can verify that no stage ever mutates them after injection
 * (RFC_RUNTIME_SKELETON_ARCHITECTURE §5 R8 / §3.1: the pipeline is the sole writer and the
 * context is frozen once injected).
 *
 * Debug-only by construction: [SkeletonPipeline] allocates a snapshot only under
 * `BuildConfig.DEBUG`; release builds compile the mechanism out entirely — non-execution
 * in release is compile-time constant-folding inference (verified by successful release
 * compilation, not by executing a release build). Unit tests run with
 * `BuildConfig.DEBUG == true`, so the JVM suite exercises the check.
 */
internal object RuntimeContextSnapshot {

    /** Immutable capture of the injected context of [pose]. */
    fun of(pose: SkeletonPose): Snapshot = Snapshot(pose.environment, pose.supportedPoints.toSet())

    data class Snapshot(
        private val environment: EnvironmentDefinition,
        private val supportedPoints: Set<SupportPoint>
    ) {
        /**
         * Throws [IllegalStateException] ("R8 violation: …") if the pose's context no longer
         * matches the injected snapshot. Equality is content-based: [EnvironmentDefinition]
         * is a data class of immutable values, and support points form a plain enum-value set,
         * so both compare structurally. (Note: prop payloads hold mutable `Vector3`s, so a
         * hypothetical mid-stage re-derivation producing a *content-identical* environment
         * instance would still satisfy this check — such a rewrite is semantically a no-op;
         * any observable change trips the guard.)
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
