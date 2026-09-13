package com.monkfitness.app

import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.skeletonAnimation
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.animation.AnimationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The animation-coverage metric, kept honest: **`animated` means a real skeletal animation exists and
 * is USED by `ExerciseHero`** — not that an animated illustration exists.
 *
 * ## Why this test exists
 *
 * The app carries two animation systems:
 *
 *  1. the **engine** poses (`PoseRegistry` → `AnimationRegistry` → `PoseBuilder` → `SkeletonPipeline`
 *     → `SkeletonRenderer`), keyed by `Exercise.animationId`; and
 *  2. a **legacy keyframe illustration** map (`ui/components/animation/ExerciseSkeletonData.kt`,
 *     `exerciseSkeletonAnimation(animationId)`, surfaced as `Exercise.skeletonAnimation`).
 *
 * `ExerciseAnimationProfileTest` only asserts system 2 (`hasAnimatedVariant()`), and every one of the
 * 66 catalog exercises has it — so it stayed green while 17 exercises rendered the illustration and
 * never touched the engine. `ExerciseHero`'s own branch (`ui/components/CommonComponents.kt`,
 * `ExerciseVisualContent`) is:
 *
 * ```kotlin
 * val skeletonAnimation = exercise.skeletonAnimation        // system 2 present?
 * if (skeletonAnimation != null) ExerciseAnimatedVisual(...) // then the engine decides:
 * //   PoseRegistry.getPoseConfig(animationId) != null -> SkeletonRenderer on the real skeleton
 * //   else                                            -> animation.poseAt(progress).toCanvas(size)
 * ```
 *
 * so an exercise is REALLY animated exactly when **both** resolve. That is the assertion below, and
 * the coverage count is pinned against the app's own metric (`LibraryStats.animatedExercisesCount`).
 *
 * ## Coverage (this phase)
 *
 * `49/66` at the phase's start (measured on `origin/main` @ `4a32d84` with `ZzCoverageProbeTest`'s
 * census: `REAL-ANIMATED=49 UNCOVERED=17`) → `53/66` after batch 1 → `57/66` after batch 2 (both
 * re-measured on the branch and recorded in `docs/ANIMATION_COVERAGE_PHASE.md`) → `66/66` at the
 * phase's end. [REQUIRED_SKELETAL_ANIMATION_IDS] grows with each batch of the phase and is asserted
 * as a **superset** so the batches stay independently mergeable, while
 * [REQUIRED_COVERAGE_MILESTONE] pins the count each batch actually reached; the phase's completion PR
 * additionally pins the total.
 */
class AnimationCoverageTest {

    private val generator = WorkoutGenerator()

    /** Every catalog exercise (library + warmups + posture drills), deduplicated by id. */
    private fun catalog(): List<Exercise> = buildList {
        addAll(generator.getExerciseLibrary())
        addAll(generator.getWarmupExercises())
        addAll(generator.getPostureExercises())
    }.distinctBy { it.id }

    companion object {
        /**
         * The animation ids the phase has converted from the illustration to a real skeletal
         * animation. Must stay in sync with `PoseRegistry.configRegistry` and
         * `AnimationRegistry.registry`.
         */
        val REQUIRED_SKELETAL_ANIMATION_IDS = setOf(
            // Batch 1 — the standing lower-body family: foot-planted stance holds and drills.
            "horse_stance_hold",
            "wall_sit_hold",
            "ankle_mobility_standard",
            "calf_stretch_hold",
            // Batch 2 — the upper-body pull / bar-support family.
            "row_standard",
            "dip_parallel_bar",
            "band_pull_aparts_standard",
            "yt_raises_standard"
        )

        /**
         * The coverage the phase has reached so far (`57` of the catalog's `66` after batch 2). Pinned
         * so a batch cannot quietly add an id to the set above without the phase doc's measured
         * before → after being updated with it: the count is the app's own metric
         * (`LibraryStats.animatedExercisesCount`), recomputed from the registry rather than trusted.
         */
        const val REQUIRED_COVERAGE_MILESTONE = 57
    }

    /** The hero renders the engine skeleton for these exercises, not the keyframe illustration. */
    @Test
    fun everyRequiredExerciseIsRenderedByTheEngineSkeleton() {
        val problems = mutableListOf<String>()
        val byAnimId = catalog().associateBy { it.animationId }
        for (id in REQUIRED_SKELETAL_ANIMATION_IDS) {
            val exercise = byAnimId[id]
            if (exercise == null) {
                problems.add("$id is in the phase's coverage set but no catalog exercise uses it")
                continue
            }
            if (exercise.skeletonAnimation == null) {
                problems.add("${exercise.id}: the hero would take the still-image branch (no SkeletonAnimation)")
            }
            if (PoseRegistry.getPoseConfig(id) == null) {
                problems.add("${exercise.id} ($id): no PoseRegistry config — the hero would draw the keyframe illustration")
            }
            if (AnimationRegistry.get(id) == null) {
                problems.add("${exercise.id} ($id): no AnimationRegistry builder")
            }
        }
        assertTrue("the coverage metric is not honest for:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** The app's own coverage count must agree with the registry (no unregistered/duplicated ids). */
    @Test
    fun theLibraryStatsCoverageCountsTheSameExercises() {
        val real = PoseRegistry.getDedicatedAnimationIds()
        val expected = catalog().count { it.animationId in real }
        val stats = generator.getLibraryStats()
        assertEquals(
            "LibraryStats.animatedExercisesCount disagrees with the PoseRegistry-resolved count",
            expected, stats.animatedExercisesCount
        )
        assertEquals("the catalog size moved — re-measure the phase's coverage", 66, stats.totalExercises)
        assertEquals(
            "the phase's coverage milestone moved — re-measure it and record the before → after in " +
                "docs/ANIMATION_COVERAGE_PHASE.md",
            REQUIRED_COVERAGE_MILESTONE, stats.animatedExercisesCount
        )
        assertTrue(
            "the phase's required set ($REQUIRED_SKELETAL_ANIMATION_IDS) is not covered",
            REQUIRED_SKELETAL_ANIMATION_IDS.all { it in real }
        )
    }

    /** Every dedicated id resolves to a builder that is a real engine pose (never a placeholder). */
    @Test
    fun everyDedicatedIdResolvesToAnEnginePose() {
        for (id in REQUIRED_SKELETAL_ANIMATION_IDS) {
            val cfg = PoseRegistry.getPoseConfig(id)
            assertNotNull("$id resolved to no pose config", cfg)
            val builder = cfg!!.builder
            assertTrue(
                "$id's builder ${builder.javaClass.simpleName} is not a PoseBuilder-driven engine pose",
                builder is com.monkfitness.app.animation.PoseBuilder
            )
            assertTrue(
                "$id must not be a legacy illustration key",
                builder.javaClass.name.startsWith("com.monkfitness.app.poses.")
            )
        }
    }
}
