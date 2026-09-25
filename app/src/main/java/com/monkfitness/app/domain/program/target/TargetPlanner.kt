package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.PlannedOccurrence

/** Immutable result of the pure target planning pipeline. */
data class TargetPlan(
    val planned: List<PlannedOccurrence>,
    val reconciliation: TargetOccurrenceReconciliation
)

/** Orchestrates target resolution, composition, and reconciliation without adding policy. */
object TargetPlanner {
    fun plan(
        schedules: List<TargetSchedule>,
        window: TargetScheduleWindow,
        selection: CompositionSelection = CompositionSelection(),
        existing: List<ExistingOccurrence> = emptyList(),
        sources: Map<String, ResolvedScheduleSource> = emptyMap()
    ): TargetPlan {
        val resolved = TargetScheduleResolver.resolve(schedules, window, sources)
        val planned = TargetOccurrenceComposer.compose(resolved, selection)
        val reconciliation = TargetOccurrenceReconciler.reconcile(existing, planned)

        return TargetPlan(planned, reconciliation)
    }
}
