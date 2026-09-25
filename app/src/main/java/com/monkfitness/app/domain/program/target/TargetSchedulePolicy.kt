package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.ProgramPauseWindow
import java.time.LocalDate

enum class TargetSupersessionReason {
    TARGET_NO_LONGER_PRESENTS_OCCURRENCE,
    PASSED_WHILE_PAUSED
}

data class TargetSupersededOccurrence(
    val occurrence: ExistingOccurrence,
    val reason: TargetSupersessionReason
)

/** Pure temporal interpretation of a Stage 5 target plan at an explicit as-of date. */
data class TargetScheduleDecision(
    val targetPlan: TargetPlan,
    val preserved: List<ExistingOccurrence>,
    val retained: List<ExistingOccurrence>,
    val created: List<PlannedOccurrence>,
    val superseded: List<TargetSupersededOccurrence>,
    val missed: List<ExistingOccurrence>
)

/** Applies scheduling-time status without resolving, composing, reconciling, or persisting anything. */
object TargetSchedulePolicy {
    fun decide(
        targetPlan: TargetPlan,
        existing: List<ExistingOccurrence>,
        asOf: LocalDate,
        pauses: List<ProgramPauseWindow>
    ): TargetScheduleDecision {
        val plannedKeys = targetPlan.planned.mapTo(HashSet()) { it.occurrenceKey }
        val plannedExisting = existing.filter { it.execution == OccurrenceExecution.PLANNED }
        val pastPaused = plannedExisting.filter {
            it.occurrence.plannedFor.isBefore(asOf) && isPaused(it.occurrence.plannedFor, pauses)
        }
        val pastUnpaused = plannedExisting.filter {
            it.occurrence.plannedFor.isBefore(asOf) && !isPaused(it.occurrence.plannedFor, pauses)
        }
        val removed = plannedExisting.filter {
            !it.occurrence.plannedFor.isBefore(asOf) && it.occurrence.occurrenceKey !in plannedKeys
        }
        val retained = plannedExisting.filter {
            !it.occurrence.plannedFor.isBefore(asOf) && it.occurrence.occurrenceKey in plannedKeys
        }
        val superseded = (pastPaused.map {
            TargetSupersededOccurrence(it, TargetSupersessionReason.PASSED_WHILE_PAUSED)
        } + removed.map {
            TargetSupersededOccurrence(it, TargetSupersessionReason.TARGET_NO_LONGER_PRESENTS_OCCURRENCE)
        }).sortedWith(
            compareBy<TargetSupersededOccurrence> { it.occurrence.occurrence.plannedFor }
                .thenBy { it.occurrence.occurrence.occurrenceKey }
                .thenBy { it.reason.name }
        )
        val created = targetPlan.reconciliation.added
            .filter { !it.plannedFor.isBefore(asOf) && !isPaused(it.plannedFor, pauses) }
            .canonicalPlannedOrder()

        return TargetScheduleDecision(
            targetPlan = targetPlan,
            preserved = targetPlan.reconciliation.preserved.canonicalExistingOrder(),
            retained = retained.canonicalExistingOrder(),
            created = created,
            superseded = superseded,
            missed = pastUnpaused.canonicalExistingOrder()
        )
    }

    private fun isPaused(date: LocalDate, pauses: List<ProgramPauseWindow>): Boolean =
        pauses.any { it.covers(date) }

    private fun List<ExistingOccurrence>.canonicalExistingOrder(): List<ExistingOccurrence> =
        sortedWith(compareBy<ExistingOccurrence> { it.occurrence.plannedFor }
            .thenBy { it.occurrence.occurrenceKey })

    private fun List<PlannedOccurrence>.canonicalPlannedOrder(): List<PlannedOccurrence> =
        sortedWith(compareBy<PlannedOccurrence> { it.plannedFor }
            .thenBy { it.occurrenceKey })
}
