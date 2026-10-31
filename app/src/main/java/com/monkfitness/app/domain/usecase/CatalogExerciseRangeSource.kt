package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.domain.adaptive.ExerciseRange
import com.monkfitness.app.domain.adaptive.ExerciseRangeSource
import com.monkfitness.app.domain.adaptive.toExerciseRange

/**
 * The app's shipped catalogue read as an exercise→range source.
 *
 * The range is not a new prescription: each entry already states `baseMinReps`/`phase4MaxReps` (or the
 * timer durations, or `isTimerBased`), and `toConfigurationMetadata().toExerciseRange()` is the mapper
 * that already folds exactly those into the planner's vocabulary. This class passes that projection on
 * and holds no copy of a bound, dimension or set count, so a catalogue change moves the range with it.
 *
 * Nothing reads `reps`, `minReps`, `maxReps`, `durationSeconds`, `category` or a name to *derive* a
 * span — those are a single phase's band or a different vocabulary. An unknown id, or an entry stating
 * no span, answers `null`: *"this caller declares no range"*, never a synthesised default. It is not a
 * policy either — no threshold, step size or gap rule lives here.
 */
class CatalogExerciseRangeSource : ExerciseRangeSource {

    /**
     * Taken with **no** equipment filter, as [CatalogExerciseFamilyClassification] does: what the user
     * owns cannot decide what an exercise's own definition says its span is.
     *
     * An entry stating no span is **absent** from the map rather than mapped to an empty range: `null`
     * is the port's answer for *"no range"*, and an `ExerciseRange` cannot be built without a dimension
     * and at least one value.
     */
    private val rangeByExerciseId: Map<String, ExerciseRange> =
        WorkoutGenerator().getExerciseLibrary()
            .mapNotNull { exercise ->
                exercise.toConfigurationMetadata().toExerciseRange()?.let { range ->
                    exercise.id to range
                }
            }
            .toMap()

    /**
     * `null` is *"this caller declares no range"*, deliberately not a fallback: the adaptive layer then
     * uses its own policy targets exactly as it did before this source existed.
     */
    override fun rangeOf(exerciseId: String): ExerciseRange? = rangeByExerciseId[exerciseId]
}
