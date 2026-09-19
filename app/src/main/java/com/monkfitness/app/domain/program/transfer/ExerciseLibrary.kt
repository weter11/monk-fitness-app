package com.monkfitness.app.domain.program.transfer

/**
 * §5's **exerciseId validation boundary**: the one question an import asks the Exercise Library.
 *
 * ```text
 * Unknown exerciseId is rejected.
 * Import never creates or guesses exercises.
 * ```
 *
 * ### Why this is a port and not a lookup
 *
 * The target domain keeps `exerciseId` **opaque**: `ProgramExercise` holds a library key and never
 * resolves it, which is what keeps the domain free of an exercise catalogue, of equipment rules and of
 * Android types (§10). An import therefore cannot validate the ids it read by itself — and §5 is explicit
 * about what it must *not* do instead:
 *
 * ```text
 * do not put exercise metadata into the export file
 * copy no names, no equipment, no muscles, no animation data
 * add no foreign key from the Program schema to a library
 * mutate no Exercise Library row
 * ```
 *
 * So the question is asked of this port, and the answer is a yes or a no about an id. The importer asks it
 * once per distinct id the document references, and refuses the whole document if any answer is no —
 * exercises are never invented, substituted, auto-created or silently dropped (§5).
 *
 * ### Where the implementation lives, and why it is not in the domain
 *
 * The app's authoritative exercise catalogue is the shipped generator's own
 * (`domain/usecase/WorkoutGenerator.allExercises`), whose entries carry `@StringRes` labels and drawables
 * — an Android-flavoured data model that the pure transfer package may not import. The production
 * implementation is therefore `domain/usecase/ProgramExerciseLibrary`, which sits beside the catalogue it
 * reads and holds **one** narrow read of it; the composition root hands that object to the import service.
 * This is the "smallest explicit validation port" the brief asks for rather than a second Exercise
 * Library architecture, and it is deliberately *not* §24's future `ExerciseLibraryRepository`: that
 * repository will be a persistence-facing read of the target schema, and inventing it here would be a
 * second owner of a fact this stage only needs to ask about.
 *
 * ### What a caller may not do with it
 *
 * Call it for anything other than membership. It answers whether an id exists; it does not return
 * metadata, does not sort a library, does not suggest a replacement and does not tell the importer what to
 * write into the plan. A caller that wanted any of those would be asking the wrong layer (§25).
 */
fun interface ExerciseLibrary {

    /**
     * Whether [exerciseId] names an exercise this app's library holds.
     *
     * The question is asked with the id exactly as the document wrote it — no trimming, no case folding,
     * no normalization — because the id is opaque in both directions (§10): an id that does not match
     * exactly is an id this app does not have, and repairing it would be the silent substitution §5
     * forbids.
     */
    suspend fun knows(exerciseId: String): Boolean
}
