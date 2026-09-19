package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import java.time.Instant

/**
 * One immutable revision of a Program's structure (§6).
 *
 * The Program's identity is stable for its whole life; every *structural* change — mode, duration,
 * frequency, schedule, exercise selection, generated plan, ordering, prescriptions, pinning, a manual
 * workout change — produces a new revision instead of editing this one. Renaming, describing,
 * selecting, starting, pausing, finishing, archiving and setting a planned start date do **not**
 * create one, which is why none of those facts live here: they belong to [Program].
 *
 * Immutability is the contract, so this type is a value: every property is a `val` of an immutable
 * type, there is no setter, and there is no operation that mutates an existing revision. A "change"
 * is a new `ProgramRevision` with a new [revisionId] — the previous one keeps describing exactly
 * what it described before, which is what lets a session started under it stay explained.
 *
 * Three facts are held consistent at construction:
 *
 *  * a saved revision carries a **plan** ([days] is never empty). An editor's work in progress is a
 *    `ProgramEditorDraft`, and that is the only place an incomplete plan is representable;
 *  * the plan days are numbered `1..n` in order, so a day's position is a usable index and never a
 *    silently duplicated or skipped coordinate;
 *  * [revisionNumber] counts from 1 for the user's benefit only — it is human-readable, and identity
 *    is [revisionId] (§23 "Revision has real revisionId").
 *
 * @property revisionId identity of this revision, minted when it is saved.
 * @property programId the Program this revision belongs to.
 * @property revisionNumber the human-readable ordinal, 1-based; never an identity.
 * @property mode manual or generated. Mode is revision content: switching it is a structural change.
 * @property duration how long this revision runs.
 * @property schedule when its slots fall.
 * @property days the revision's plan, ordered by position.
 * @property createdAt when this revision was saved.
 * @property focus the Goals & Focus configuration the plan is built for (§8): the goal, and the
 *   focuses it states — the whole vocabulary for `BALANCED`, the named focuses for `FOCUSED`, the
 *   user's percentages for `CUSTOM`. It defaults to `BALANCED`, which is the configuration that
 *   states nothing: a plan built before Goals & Focus existed was built for every focus with no share
 *   stated, and reading it as anything else would put words in the user's mouth.
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
    val focus: FocusPlan = FocusPlan.DEFAULT
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

        /** The first revision of a Program — every Program is created with one (§27). */
        const val FIRST_REVISION_NUMBER = 1
    }
}
