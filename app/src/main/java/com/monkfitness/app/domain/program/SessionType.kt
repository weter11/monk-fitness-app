package com.monkfitness.app.domain.program

/**
 * What a "Build for me" plan may draw its exercises from — a **filter on the exercise catalogue**, not a
 * mode, a day type and not a [Focus].
 *
 * A user may select none, one or several types; the members combine as a union, and no selection at all
 * means *Mixed* — every exercise, which is exactly what generation did before this vocabulary existed.
 * A type never overrides the Goals & Focus configuration or any other parameter: it only narrows the pool
 * the planner may choose from.
 *
 * Declaration order is the canonical order the stored form is sorted by, so two selections with the same
 * members are one value however the user reached them, and a re-saved revision is a byte-identical row.
 * Values are persisted by name.
 */
enum class SessionType {
    STRENGTH,
    MOBILITY,
    POSTURE,
    HYPERLORDOSIS;

    companion object {

        /** The selection in canonical order, with duplicates and unknown-to-the-selection order dropped. */
        fun canonical(types: Iterable<SessionType>): List<SessionType> {
            val chosen = types.toSet()
            return entries.filter { type -> type in chosen }
        }
    }
}
