package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import java.time.Instant
import java.time.LocalDate

/**
 * A saved Program: one stable identity, one current revision, and the facts that are *not* structure.
 *
 * What belongs here follows from the revision rules (§6), inverted. Renaming, describing, selecting,
 * starting, pausing, resuming, finishing, archiving and planning a start date are all things the user
 * does to *this* value, and none of them creates a revision. What the plan *is* — mode, duration,
 * schedule, days, prescriptions — belongs to the revision, which is why this type carries no such
 * field and no mode of its own: a Program's mode is its current revision's mode, and duplicating it
 * here would create a second source of truth that can silently disagree.
 *
 * Two consequences worth stating, because both are easy to get wrong:
 *
 *  * a Program always has a [currentRevisionId] — create, copy and import produce a Program together
 *    with its first revision and its initial slots in one transaction (§27), so "a program with no
 *    plan" is not a representable state;
 *  * **selection and archiving are different things.** Which program is selected is global runtime
 *    state (`AppState`), not a Program-owned flag — a Program never claims to be selected, so the two
 *    can never contradict each other, and export never carries it (§21, §5). Archiving *is* a
 *    Program fact: it retains all history and stops future planning without creating a revision.
 *
 * @property programId stable identity, for the whole life of the Program.
 * @property name user-facing name. Renaming is not a structural change.
 * @property description user-facing description; may be empty.
 * @property source where the Program came from.
 * @property lifecycleStatus where it is in `NOT_STARTED / RUNNING / PAUSED / COMPLETED`.
 * @property currentRevisionId the revision that currently describes the plan.
 * @property createdAt when the Program was created.
 * @property updatedAt when it was last changed in any way.
 * @property plannedStartDate the date it is planned to start on. A plan, not a fact.
 * @property actualStartDate when it actually started. A fact, set by starting it.
 * @property archivedAt when it was archived, or `null` while it is not archived.
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

    /** Whether this Program is archived. Archiving retains history and stops future planning (§29). */
    val isArchived: Boolean
        get() = archivedAt != null

    /**
     * Whether the lifecycle has begun — the planned start date is deliberately irrelevant here.
     */
    val hasStarted: Boolean
        get() = lifecycleStatus != LifecycleStatus.NOT_STARTED
}
