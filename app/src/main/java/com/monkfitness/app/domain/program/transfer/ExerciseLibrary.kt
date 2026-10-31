package com.monkfitness.app.domain.program.transfer

/**
 * The one question an import asks the Exercise Library: does this id exist?
 *
 * The domain keeps `exerciseId` opaque — `ProgramExercise` holds a library key and never resolves it —
 * so an import cannot validate the ids it read by itself. It asks this port once per distinct id the
 * document references and refuses the whole document if any answer is no: exercises are never invented,
 * substituted or silently dropped, and no metadata is copied into the format.
 *
 * Membership only. It answers yes or no about an id, never metadata or a replacement. The implementation
 * sits beside the catalogue it reads (`domain/usecase/ProgramExerciseLibrary`) because that catalogue
 * carries `@StringRes` labels and drawables the pure transfer package may not import.
 */
fun interface ExerciseLibrary {

    /**
     * Whether [exerciseId] names an exercise this app's library holds. Asked with the id exactly as the
     * document wrote it — no trimming, no case folding — because repairing an id that does not match
     * exactly would be the silent substitution the format forbids.
     */
    suspend fun knows(exerciseId: String): Boolean
}
