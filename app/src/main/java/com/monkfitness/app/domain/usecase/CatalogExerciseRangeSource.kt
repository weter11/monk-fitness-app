package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.domain.adaptive.ExerciseRange
import com.monkfitness.app.domain.adaptive.ExerciseRangeSource
import com.monkfitness.app.domain.adaptive.toExerciseRange

/**
 * P33's **production exercise→range source**: the app's own shipped catalogue, read for the one span
 * each exercise's definition already states.
 *
 * ### Why this is a read of an existing fact, and not a new prescription
 *
 * `ExerciseRangeSource` was introduced with the within-range prescription step as a *caller fact* — the
 * adaptive layer owns no catalogue, so a range arrives as an input. Production supplied nothing, which
 * meant the port's honest default ([ExerciseRangeSource.NONE]) answered `null` for every id and **no
 * range reached the engine in production at all**: the step could only ever be exercised by a caller
 * that stated a range itself.
 *
 * The range is not a new decision. Every catalogue entry already carries `baseMinReps`,
 * `phase4MaxReps`, `baseDurationSeconds`, `phase4DurationSeconds` and `isTimerBased`, and
 * [toConfigurationMetadata] already folds exactly those into the metadata vocabulary the planner reads.
 * This class therefore **passes that projection on** and restates nothing: no bound, no dimension, no
 * set count and no default is chosen here, and if the catalogue's definition of a span changes, this
 * source changes with it because it holds no copy of it.
 *
 * ### What it deliberately does not do
 *
 * - **No inference of any kind.** Nothing here reads `reps`, `minReps`, `maxReps`, `durationSeconds`,
 *   `category` or a name to *derive* a span: those are a single phase's band or a different vocabulary,
 *   and a range read off them would be a range the catalogue never stated. The one projection used is
 *   the mapper that already exists for this purpose.
 * - **No fallback range.** An exercise the catalogue does not hold — and an exercise whose definition
 *   states no span at all — is answered `null`, which the adaptive layer already reads as *"this caller
 *   declares no range"*. A synthesised default would be a prescription the catalogue never made.
 * - **No persistence, no DAO, no repository, no clock, no id source.** The catalogue is a compile-time
 *   fact, so this is a `val` built once at construction rather than a repository (§23 gains no table).
 * - **No generation-domain dependency.** The direction is the app boundary → pure domain: this class
 *   implements the domain's port and never imports `domain.program.generated`.
 *
 * ### Where it is read
 *
 * [com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration] asks it for the range of the element
 * it is about to decide on, and the composition root constructs it — the single construction site. It
 * is a **read-only projection**: it exposes no `Exercise`, no name, no equipment and no animation id,
 * so there is nothing here for an export to copy and nothing here to write back into a plan.
 *
 * **It is not a policy.** The range states where an exercise *may* travel; it says nothing about when to
 * move within it. No threshold, no step size and no gap rule is supplied here, and the engine's own
 * within-range step remains the only thing that reads it.
 */
class CatalogExerciseRangeSource : ExerciseRangeSource {

    /**
     * The catalogue's own exercise id → progression span, taken once at construction.
     *
     * `getExerciseLibrary()` with **no** equipment filter is the catalogue as the app knows it — the same
     * read [CatalogExerciseFamilyClassification] takes, for the same reason: whether the user happens to
     * own a bar cannot decide what an exercise's own definition says its span is. With an empty filter
     * that accessor returns every entry, so this is the whole catalogue and no exercise is silently
     * rangeless because of equipment.
     *
     * An entry whose definition states no span (the mapper answers `null`) is **absent** from the map
     * rather than mapped to an empty range: `null` is the port's stated answer for *"no range"*, and an
     * `ExerciseRange` cannot be constructed without one dimension and at least one value.
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
     * The span [exerciseId]'s own catalogue definition states, or `null` when this catalogue does not
     * hold it or its definition states none.
     *
     * `null` is the port's own answer for *"this caller declares no range"* and is deliberately **not**
     * a fallback: an id the app does not ship has no span here, and the adaptive layer then falls back
     * to its own policy targets exactly as it did before this source existed.
     */
    override fun rangeOf(exerciseId: String): ExerciseRange? = rangeByExerciseId[exerciseId]
}
