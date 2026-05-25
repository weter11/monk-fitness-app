package com.monkfitness.app

import com.monkfitness.app.data.model.hasAnimatedVariant
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.ui.components.animation.Joint
import com.monkfitness.app.ui.components.animation.allExerciseSkeletonAnimations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseAnimationProfileTest {

    private val generator = WorkoutGenerator()

    @Test
    fun testAllExercisesExposeAnimatedVariants() {
        val allExercises = buildList {
            addAll(generator.getExerciseLibrary())
            addAll(generator.getWarmupExercises())
            addAll(generator.getPostureExercises())
        }.distinctBy { it.id }

        val missingAnimatedVariants = allExercises
            .filterNot { it.hasAnimatedVariant() }
            .map { it.id }

        assertTrue(
            "Missing animated variants for: $missingAnimatedVariants",
            missingAnimatedVariants.isEmpty()
        )
    }

    @Test
    fun testAllSkeletonAnimationsUseFullRig() {
        allExerciseSkeletonAnimations().forEach { animation ->
            animation.keyframes.forEach { keyframe ->
                assertEquals(Joint.entries.size, keyframe.pose.joints.size)
                assertEquals(Joint.entries.toSet(), keyframe.pose.joints.keys)
            }
        }
    }
}
