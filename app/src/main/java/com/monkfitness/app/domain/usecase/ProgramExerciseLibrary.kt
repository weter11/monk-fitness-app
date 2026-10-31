package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.transfer.ExerciseLibrary

/**
 * The app's catalogue ids, exposed as the transfer layer's membership port.
 *
 * The catalogue lives in [WorkoutGenerator] as Android-flavoured data (`@StringRes` labels, drawables,
 * an equipment vocabulary) the pure transfer package must not import, so this adapter sits beside it
 * and exposes nothing but membership — the id in, a yes or a no out. It holds ids only, so there is
 * nothing to copy into an export and nothing to write back into a plan.
 */
class ProgramExerciseLibrary : ExerciseLibrary {

    /**
     * Read with **no** equipment filter: an import is not a selection step, so equipment availability (a
     * per-user fact) must not decide whether a shared file is readable. A file that plans an exercise the
     * user cannot perform is a plan they can edit, not a file this app refuses.
     */
    private val knownExerciseIds: Set<String> =
        WorkoutGenerator().getExerciseLibrary().map { exercise -> exercise.id }.toSet()

    /** Whether [exerciseId] is one of them. The id is compared exactly: it is opaque in both directions. */
    override suspend fun knows(exerciseId: String): Boolean = exerciseId in knownExerciseIds
}
