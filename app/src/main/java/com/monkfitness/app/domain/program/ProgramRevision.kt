package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import java.time.Instant

/**
 * One immutable revision of a Program's structure.
 *
 * The Program's identity is stable for its whole life; every *structural* change — mode, duration,
 * frequency, schedule, exercise selection, generated plan, ordering, prescriptions, pinning, a manual
 * workout change — produces a new revision instead of editing this one. Renaming, describing, selecting,
 * starting, pausing, finishing, archiving and setting a planned start date do **not** create one, which is
 * why none of those facts live here: they belong to [Program].
 *
 * Immutability is the contract: every property is a `val` of an immutable type, there is no setter, and
 * nothing mutates an existing revision. A "change" is a new `ProgramRevision` with a new [revisionId] — the
 * previous one keeps describing exactly what it described before, which is what lets a session started
 * under it stay explained.
 *
 * Three facts are held consistent at construction:
 *
 *  * a saved revision carries a **plan** ([days] is never empty). An editor's work in progress is a
 *    `ProgramEditorDraft`, the only place an incomplete plan is representable;
 *  * the plan days are numbered `1..n` in order, so a day's position is a usable index and never a
 *    silently duplicated or skipped coordinate;
 *  * [revisionNumber] counts from 1 for the user's benefit only — it is human-readable, and identity is
 *    [revisionId].
 *
 * [focus] defaults to `BALANCED`, which states nothing: a plan built before Goals & Focus existed was
 * built for every focus with no share stated, and reading it as anything else would put words in the user's
 * mouth. [preferredExercises] defaults to `ExercisePreference.NONE` for the same reason — the user named
 * nothing, rather than an ordering of the catalogue no user ever stated.
 */
data class ProgramRevision(
    val revisionId: RevisionId,
    val programId: ProgramId,
    val revisionNumber: Int,
    val mode: ProgramMode,
    val duration: ProgramDuration,
    val schedule: ProgramSchedule,
    val days: List<ProgramDay>,
    val createdAt: Instant,
    val focus: FocusPlan = FocusPlan.DEFAULT,
    val preferredExercises: ExercisePreference = ExercisePreference.NONE
) {

    init {
        require(revisionNumber >= FIRST_REVISION_NUMBER) {
            "revision numbering starts at $FIRST_REVISION_NUMBER, was $revisionNumber"
        }
        require(days.isNotEmpty()) {
            "a saved revision carries a plan; an incomplete plan belongs to the editor draft"
        }
        require(days.map { it.position } == (1..days.size).toList()) {
            "a revision's days are numbered 1..${days.size} in order, got " +
                "${days.map { it.position }}"
        }
        require(days.map { it.programDayId }.toSet().size == days.size) {
            "each plan day of a revision has its own identity"
        }
    }

    /** The plan day at [position] (1-based), or `null` when the revision has no such day. */
    fun dayAt(position: Int): ProgramDay? = days.getOrNull(position - 1)

    companion object {

        /** The first revision of a Program — every Program is created with one. */
        const val FIRST_REVISION_NUMBER = 1
    }
}
