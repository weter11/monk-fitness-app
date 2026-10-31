package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification

/**
 * The app's shipped catalogue read as an exercise→family classification.
 *
 * The family is a stored constructor argument on each catalogue entry, never derived here: nothing
 * reads `category`, `subCategory`, `requiredEquipment`, `animationId` or `exerciseToFamiliesMap`. A
 * guessed family is a family the app never stated.
 *
 * An id the catalogue does not hold answers `null`, which the adaptive layer reads as *not classified*
 * rather than as a default bucket — a fallback family here would fabricate a ladder question.
 *
 * It is a read-only projection with one collaborator and no persistence (the catalogue is a
 * compile-time `val`), and it never imports `domain.program.generated`. It supplies no progression
 * ladder, so passes still end in `NO_DECLARED_PROGRESSION_RELATION`.
 */
class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {

    /**
     * Taken with **no** equipment filter, the same read [ProgramExerciseLibrary] takes: a family is a
     * property of the exercise's definition, so what the user happens to own cannot decide whether the
     * exercise belongs to `pullups`. An empty filter returns every entry.
     */
    private val familyByExerciseId: Map<String, String> =
        WorkoutGenerator().getExerciseLibrary()
            .associate { exercise -> exercise.id to exercise.familyId }

    /**
     * `null` is the port's stated *"not classified"*, deliberately not a default bucket; the adaptive
     * layer reports the gap itself.
     */
    override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]
}