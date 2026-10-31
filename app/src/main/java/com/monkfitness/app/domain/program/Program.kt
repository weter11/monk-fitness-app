package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import java.time.Instant
import java.time.LocalDate

/**
 * A saved Program: one stable identity, one current revision, and the facts that are *not* structure.
 *
 * What belongs here follows from the revision rules, inverted. Renaming, describing, selecting, starting,
 * pausing, resuming, finishing, archiving and planning a start date are all things the user does to *this*
 * value, and none of them creates a revision. What the plan *is* — mode, duration, schedule, days,
 * prescriptions — belongs to the revision, which is why this type carries no such field and no mode of its
 * own: a Program's mode is its current revision's mode, and duplicating it here would create a second
 * source of truth that can silently disagree.
 *
 * Two consequences worth stating, because both are easy to get wrong:
 *
 *  * a Program always has a [currentRevisionId] — create, copy and import produce a Program together with
 *    its first revision and its initial slots in one transaction, so "a program with no plan" is not a
 *    representable state;
 *  * **selection and archiving are different things.** Which program is selected is global runtime state
 *    (`AppState`), not a Program-owned flag, so the two can never contradict each other and export never
 *    carries it. Archiving *is* a Program fact: it retains all history and stops future planning without
 *    creating a revision.
 *
 * `plannedStartDate` is a plan and `actualStartDate` a fact: only starting the Program sets the latter.
 */
data class Program(
    val programId: ProgramId,
    val name: String,
    val description: String,
    val source: ProgramSource,
    val lifecycleStatus: LifecycleStatus,
    val currentRevisionId: RevisionId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val plannedStartDate: LocalDate? = null,
    val actualStartDate: Instant? = null,
    val archivedAt: Instant? = null
) {

    init {
        require(name.isNotBlank()) { "a program has a name" }
        require(updatedAt >= createdAt) {
            "a program cannot be updated before it was created: created=$createdAt updated=$updatedAt"
        }
        require(actualStartDate == null || actualStartDate >= createdAt) {
            "a program cannot have started before it was created: created=$createdAt " +
                "started=$actualStartDate"
        }
        require(lifecycleStatus != LifecycleStatus.NOT_STARTED || actualStartDate == null) {
            "a NOT_STARTED program has no actual start; a planned start date does not start it (§3)"
        }
        require(lifecycleStatus == LifecycleStatus.NOT_STARTED || actualStartDate != null) {
            "a ${lifecycleStatus} program has actually started; set actualStartDate when it does (§3)"
        }
        require(archivedAt == null || archivedAt >= createdAt) {
            "a program cannot be archived before it was created: created=$createdAt " +
                "archived=$archivedAt"
        }
    }

    /** Whether this Program is archived. Archiving retains history and stops future planning. */
    val isArchived: Boolean
        get() = archivedAt != null

    /** Whether the lifecycle has begun — the planned start date is deliberately irrelevant here. */
    val hasStarted: Boolean
        get() = lifecycleStatus != LifecycleStatus.NOT_STARTED
}
