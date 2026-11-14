package com.monkfitness.app

import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.skeletonAnimation
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The posture extra workout's exercise-to-animation mapping: every exercise the Home "additional
 * posture workout" can put on the list resolves to something the shared [MediaHero] can show — an
 * engine skeletal animation, the legacy keyframe illustration, or the still-image fallback. The test
 * enumerates the whole track (56 days × every training type) so a gap cannot hide behind one day.
 *
 * The hero's own branch (MediaHero) is: `skeletonAnimation != null` -> ExerciseAnimatedVisual, which
 * renders the engine skeleton only when `PoseRegistry.getPoseConfig(animationId) != null`; otherwise
 * the still image. So "cannot be shown" means neither an engine pose nor a keyframe nor an image.
 */
class PostureHeroMappingTest {

    private val generator = WorkoutGenerator()

    /** Every exercise the posture routine can select across the whole track and every training type. */
    private fun postureRoutineExercises(): List<Exercise> {
        val all = LinkedHashMap<String, Exercise>()
        for (day in 1..56) {
            for (type in FlexibilityTrainingType.entries) {
                generator.generatePostureMobilityWorkout(
                    day = day,
                    flexibilityTrainingType = type
                ).exercises.forEach { all.putIfAbsent(it.id, it) }
            }
        }
        // The library's own posture list is part of what Home can present.
        generator.getPostureExercises().forEach { all.putIfAbsent(it.id, it) }
        return all.values.toList()
    }

    private enum class Rendering { ENGINE, KEYFRAME, STILL_IMAGE, NONE }

    private fun renderingOf(exercise: Exercise): Rendering = when {
        exercise.skeletonAnimation != null && PoseRegistry.getPoseConfig(exercise.animationId) != null -> Rendering.ENGINE
        exercise.skeletonAnimation != null -> Rendering.KEYFRAME
        exercise.imageRes != null -> Rendering.STILL_IMAGE
        else -> Rendering.NONE
    }

    @Test
    fun everyPostureExerciseResolvesToAHeroRendering() {
        val exercises = postureRoutineExercises()
        assertTrue("the posture exercise corpus is empty", exercises.isNotEmpty())
        val gaps = exercises.filter { renderingOf(it) == Rendering.NONE }
        assertTrue(
            "these posture exercises cannot be shown by the hero: " +
                gaps.joinToString { "${it.id}(${it.animationId})" },
            gaps.isEmpty()
        )
        // Most of the posture pool is engine-animated; pin that the shared hero is genuinely reused
        // for real skeletal animation, not only stills.
        val engine = exercises.count { renderingOf(it) == Rendering.ENGINE }
        assertTrue("no posture exercise uses the engine skeleton (count=$engine)", engine > 0)
    }

    @Test
    fun theHeroFallbackCoversAnyExerciseWithoutAnEnginePose() {
        // Any posture exercise that has no engine pose still has a non-empty hero rendering (keyframe
        // illustration or still image) — the same fallback the session screen uses. Empty hero = NONE.
        val exercises = postureRoutineExercises()
        val withoutEngine = exercises.filter { PoseRegistry.getPoseConfig(it.animationId) == null }
        for (exercise in withoutEngine) {
            assertTrue(
                "${exercise.id} (${exercise.animationId}) has neither an engine pose nor a hero fallback",
                exercise.skeletonAnimation != null || exercise.imageRes != null
            )
        }
        // Non-vacuity for the engine path.
        assertTrue(
            "no posture exercise uses the engine skeleton",
            exercises.any { PoseRegistry.getPoseConfig(it.animationId) != null }
        )
    }

    @Test
    fun theNamedPosturePoolMapsToEngineAnimations() {
        val pool = generator.getPostureExercises()
        val byId = pool.associateBy { it.id }
        val expectedAnimationIds = mapOf(
            "hang" to "dead_hang",
            "face_pull" to "face_pull_banded",
            "bird_dog" to "birddog_hold",
            "superman" to "superman_prone",
            "wall_slides" to "wall_slide_standard",
            "deep_squat" to "deep_squat_hold",
            "pike_pushups" to "pike_pushup_standard",
            "horse_stance" to "horse_stance_hold"
        )
        for ((id, animationId) in expectedAnimationIds) {
            val exercise = byId[id]
            assertTrue("the posture pool lost $id", exercise != null)
            assertEquals("$id's animation id moved", animationId, exercise!!.animationId)
            assertTrue(
                "$id ($animationId) has no engine pose config — the hero would take a fallback",
                PoseRegistry.getPoseConfig(animationId) != null
            )
        }
        assertEquals("the base posture pool changed size", expectedAnimationIds.size, pool.size)
    }

    /**
     * Writes the whole-track posture exercise → animation mapping to build/posture-mapping.json for
     * the report: which exercises have an engine pose, a keyframe illustration, or only a still image.
     */
    @Test
    fun writePostureMappingReport() {
        val exercises = postureRoutineExercises()
        val sb = StringBuilder("{\n  \"count\": ${exercises.size},\n  \"exercises\": [\n")
        exercises.forEachIndexed { i, e ->
            val engine = PoseRegistry.getPoseConfig(e.animationId) != null
            val keyframe = e.skeletonAnimation != null
            val image = e.imageRes != null
            val rendering = when {
                engine && keyframe -> "ENGINE"
                keyframe -> "KEYFRAME_ILLUSTRATION"
                image -> "STILL_IMAGE"
                else -> "NONE"
            }
            if (i > 0) sb.append(",\n")
            sb.append("    {\"id\": \"${e.id}\", \"animationId\": \"${e.animationId}\", " +
                "\"engine\": $engine, \"keyframe\": $keyframe, \"stillImage\": $image, " +
                "\"rendering\": \"$rendering\"}")
        }
        sb.append("\n  ],\n  \"withoutAnyAnimation\": [")
        val gaps = exercises.filter { it.skeletonAnimation == null }
        sb.append(gaps.joinToString { "\"${it.id}(${it.animationId})\"" })
        sb.append("],\n  \"withoutEnginePose\": [")
        val noEngine = exercises.filter { PoseRegistry.getPoseConfig(it.animationId) == null }
        sb.append(noEngine.joinToString { "\"${it.id}(${it.animationId})\"" })
        sb.append("]\n}\n")
        val start = java.io.File(System.getProperty("user.dir"))
        var dir = start
        for (k in 0 until 8) {
            if (java.io.File(dir, "app/build").isDirectory) break
            dir = dir.parentFile ?: break
        }
        val out = java.io.File(dir, "app/build/posture-mapping.json")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
        println("POSTURE_MAPPING " + out.absolutePath)
        assertTrue("the mapping report must cover the whole track", exercises.isNotEmpty())
    }
}
