package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.transfer.ExerciseLibrary

/**
 * §5's exerciseId boundary, over the app's own catalogue — the **one** place an import asks the Exercise
 * Library a question.
 *
 * ### What it reads, and why it is here
 *
 * The app's authoritative exercise catalogue is the shipped generator's
 * ([WorkoutGenerator.allExercises], a private list inside that class), and its entries are a data model
 * with `@StringRes` labels, drawables and an equipment vocabulary — Android-flavoured types the pure
 * transfer package must not import (§25). This adapter therefore lives **beside the catalogue it reads**,
 * in the package that already holds it, and exposes nothing but membership: the id in, a yes or a no out.
 *
 * It is deliberately not §24's future `ExerciseLibraryRepository`. That name belongs to a persistence-facing
 * read of the target schema, and building it here would be a second owner of a fact this stage only needs
 * to ask about. What §5 asks for is *"the smallest explicit validation port"*, and this is it: one port
 * ([ExerciseLibrary]) with one production implementation, documented as the boundary rather than as an
 * architecture.
 *
 * ### It cannot mutate the library, and the catalogue cannot be edited through it
 *
 * `getExerciseLibrary()` is a read of a `private val` list the generator builds once. This adapter holds
 * the **ids** of that read and nothing else — no `Exercise` value, no name, no equipment, no animation id —
 * so there is nothing here to copy into an export file (which would be §5's *"do not put exercise metadata
 * into the export"*) and nothing here to write back into a plan. A plan element's `exerciseId` stays opaque
 * in both directions (§10).
 *
 * ### When the catalogue is read
 *
 * Once, when this object is constructed — the catalogue is a compile-time list rather than stored data, so
 * there is nothing to refresh and no state to keep in step. The composition root constructs this once, as
 * it constructs every other graph node.
 */
class ProgramExerciseLibrary : ExerciseLibrary {

    /**
     * Every id the app's library holds.
     *
     * Taken from the generator's own accessor with no equipment filter, which is the catalogue as the app
     * knows it: an import is not a selection step, so equipment availability (a per-user fact) must not
     * decide whether a shared file is readable. A file that plans an exercise the user cannot perform is a
     * plan they can edit, not a file this app refuses.
     */
    private val knownExerciseIds: Set<String> =
        WorkoutGenerator().getExerciseLibrary().map { exercise -> exercise.id }.toSet()

    /** Whether [exerciseId] is one of them. The id is compared exactly: it is opaque in both directions. */
    override suspend fun knows(exerciseId: String): Boolean = exerciseId in knownExerciseIds
}
