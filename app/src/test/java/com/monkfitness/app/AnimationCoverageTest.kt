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
 * The animation-coverage metric, kept honest: `animated` means a real skeletal animation exists and is
 * USED by `ExerciseHero` — not that an animated illustration exists.
 *
 * The app carries two animation systems: the engine poses (`PoseRegistry` → `AnimationRegistry` →
 * `PoseBuilder` → `SkeletonPipeline` → `SkeletonRenderer`), keyed by `Exercise.animationId`; and a
 * legacy keyframe illustration map (`exerciseSkeletonAnimation(animationId)`, surfaced as
 * `Exercise.skeletonAnimation`). `ExerciseAnimationProfileTest` only asserts the second, and every
 * catalog exercise has it — so it stayed green while 17 exercises rendered the illustration and never
 * touched the engine. `ExerciseHero` renders the engine skeleton only when BOTH resolve: a
 * `skeletonAnimation` is present AND `PoseRegistry.getPoseConfig(animationId) != null`; otherwise it
 * falls back to the keyframe illustration (`animation.poseAt(progress).toCanvas(size)`).
 *
 * That conjunction is the assertion below, and the coverage count is pinned against the app's own
 * metric (`LibraryStats.animatedExercisesCount`), recomputed from the registry rather than trusted.
 * [REQUIRED_SKELETAL_ANIMATION_IDS] is asserted as a superset so coverage batches stay independently
 * mergeable, while [REQUIRED_COVERAGE_MILESTONE] pins the count actually reached — and the structural
 * assertion makes the total mean *every* catalog exercise, not the pinned list.
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
         * The animation ids converted from the illustration to a real skeletal animation. Must stay in
         * sync with `PoseRegistry.configRegistry` and `AnimationRegistry.registry`.
         */
        val REQUIRED_SKELETAL_ANIMATION_IDS = setOf(
            "horse_stance_hold",
            "wall_sit_hold",
            "ankle_mobility_standard",
            "calf_stretch_hold",
            "row_standard",
            "dip_parallel_bar",
            "band_pull_aparts_standard",
            "yt_raises_standard",
            "hip_circles_hold",
            "leg_swings_hold",
            "ninety_ninety_hips",
            "piriformis_stretch_hold",
            "chin_tuck_standard",
            "neck_circles_hold",
            "shoulder_cars_standard",
            "jumping_jack_standard",
            "child_pose_hold"
        )

        /**
         * The coverage reached so far. Pinned so an id cannot be added to the set above without this
         * count moving with it: the number is the app's own metric
         * (`LibraryStats.animatedExercisesCount`), recomputed from the registry rather than trusted.
         */
        const val REQUIRED_COVERAGE_MILESTONE = 66
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

    /**
     * The completion criterion, stated independently of its own list: no catalog exercise is left on
     * the legacy illustration path. [REQUIRED_COVERAGE_MILESTONE] pinned at the catalog's total says the
     * same thing arithmetically, but this assertion says it structurally — every exercise the app can
     * show resolves to the engine, and the metric's denominator is checked against the catalog rather
     * than assumed.
     */
    @Test
    fun noCatalogExerciseIsLeftOnTheLegacyIllustrationPath() {
        val real = PoseRegistry.getDedicatedAnimationIds()
        val uncovered = catalog().filterNot { it.animationId in real }
        assertTrue(
            "the phase is not complete — these exercises still resolve to the keyframe illustration: " +
                uncovered.joinToString { "${it.id}(${it.animationId})" },
            uncovered.isEmpty()
        )
        val stats = generator.getLibraryStats()
        assertEquals("the catalog's size moved — re-measure the coverage", 66, stats.totalExercises)
        assertTrue(
            "the library reports ${stats.animatedExercisesCount} of ${stats.totalExercises} animated",
            stats.animatedExercisesCount == stats.totalExercises
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
