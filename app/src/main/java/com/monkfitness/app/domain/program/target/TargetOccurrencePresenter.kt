package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.program.PlannedOccurrence

/** Caller-supplied mapping from a target workout identity to one concrete Program plan day. */
data class TargetProgramDayBinding(
    val workoutId: String,
    val programDayId: ProgramDayId
) {
    init {
        require(workoutId.isNotBlank()) { "a target ProgramDay binding needs a workout identity" }
    }
}

/** A schedulable target occurrence and the one Program plan day it presents. */
data class TargetOccurrencePresentation(
    val occurrence: PlannedOccurrence,
    val programDayId: ProgramDayId
)

/** Typed refusals for an invalid or structurally incompatible target presentation. */
sealed class TargetOccurrencePresentationException(message: String) : IllegalArgumentException(message) {
    data class MissingProgramDayBinding(
        val workoutId: String,
        val occurrenceKey: String
    ) : TargetOccurrencePresentationException(
        "target occurrence $occurrenceKey has no ProgramDay binding for workout $workoutId"
    )

    data class ConflictingProgramDayBinding(
        val workoutId: String,
        val programDayIds: Set<ProgramDayId>
    ) : TargetOccurrencePresentationException(
        "workout $workoutId has conflicting ProgramDay bindings: $programDayIds"
    )

    data class DuplicateProgramDayBinding(
        val workoutId: String,
        val programDayId: ProgramDayId
    ) : TargetOccurrencePresentationException(
        "workout $workoutId has duplicate ProgramDay binding $programDayId"
    )

    data class MultiDayProgramOccurrence(
        val occurrenceKey: String,
        val programDayIds: Set<ProgramDayId>
    ) : TargetOccurrencePresentationException(
        "target occurrence $occurrenceKey resolves to multiple ProgramDays: $programDayIds"
    )

    data class InvalidTargetOccurrenceIdentity(
        val identity: String
    ) : TargetOccurrencePresentationException("target occurrence identity is blank: '$identity'")

    data class DuplicateTargetOccurrenceIdentity(
        val occurrenceKey: String
    ) : TargetOccurrencePresentationException("duplicate target occurrence identity: $occurrenceKey")
}

/** Pure target-occurrence-to-ProgramDay presentation; no scheduler or persistence integration. */
object TargetOccurrencePresenter {
    fun present(
        occurrences: List<PlannedOccurrence>,
        bindings: List<TargetProgramDayBinding>
    ): List<TargetOccurrencePresentation> {
        val byWorkout = indexBindings(bindings)
        validateOccurrences(occurrences)

        return occurrences
            .map { occurrence -> presentOne(occurrence, byWorkout) }
            .sortedWith(
                compareBy<TargetOccurrencePresentation> { it.occurrence.plannedFor }
                    .thenBy { it.occurrence.occurrenceKey }
            )
    }

    private fun indexBindings(
        bindings: List<TargetProgramDayBinding>
    ): Map<String, ProgramDayId> {
        val byWorkout = LinkedHashMap<String, ProgramDayId>()
        bindings.forEach { binding ->
            val existing = byWorkout.put(binding.workoutId, binding.programDayId)
            if (existing != null) {
                throw if (existing == binding.programDayId) {
                    TargetOccurrencePresentationException.DuplicateProgramDayBinding(
                        binding.workoutId,
                        binding.programDayId
                    )
                } else {
                    TargetOccurrencePresentationException.ConflictingProgramDayBinding(
                        binding.workoutId,
                        setOf(existing, binding.programDayId)
                    )
                }
            }
        }
        return byWorkout
    }

    private fun validateOccurrences(occurrences: List<PlannedOccurrence>) {
        val keys = HashSet<String>()
        occurrences.forEach { occurrence ->
            if (occurrence.occurrenceKey.isBlank()) {
                throw TargetOccurrencePresentationException.InvalidTargetOccurrenceIdentity(
                    occurrence.occurrenceKey
                )
            }
            if (!keys.add(occurrence.occurrenceKey)) {
                throw TargetOccurrencePresentationException.DuplicateTargetOccurrenceIdentity(
                    occurrence.occurrenceKey
                )
            }
            occurrence.components.forEach { component ->
                if (component.ruleId.isBlank() || component.workoutId.isBlank()) {
                    throw TargetOccurrencePresentationException.InvalidTargetOccurrenceIdentity(
                        component.ruleId.ifBlank { component.workoutId }
                    )
                }
            }
        }
    }

    private fun presentOne(
        occurrence: PlannedOccurrence,
        byWorkout: Map<String, ProgramDayId>
    ): TargetOccurrencePresentation {
        val programDayIds = occurrence.components
            .map { component ->
                byWorkout[component.workoutId]
                    ?: throw TargetOccurrencePresentationException.MissingProgramDayBinding(
                        component.workoutId,
                        occurrence.occurrenceKey
                    )
            }
            .toSet()

        if (programDayIds.size != 1) {
            throw TargetOccurrencePresentationException.MultiDayProgramOccurrence(
                occurrence.occurrenceKey,
                programDayIds
            )
        }
        return TargetOccurrencePresentation(
            occurrence.copy(
                components = occurrence.components.sortedWith(
                    compareBy<com.monkfitness.app.domain.program.OccurrenceComponent> { it.ruleId }
                        .thenBy { it.workoutId }
                )
            ),
            programDayIds.single()
        )
    }
}
