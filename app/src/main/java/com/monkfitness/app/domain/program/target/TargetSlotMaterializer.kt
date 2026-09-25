package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot

/**
 * Caller-owned storage identities plus the already-presented target occurrence.
 *
 * No identity is generated or inferred here. A blank occurrence identity is rejected before a
 * [WorkoutSlot] can acquire a different semantic identity.
 */
data class TargetSlotMaterializationInput(
    val slotId: SlotId,
    val programId: ProgramId,
    val revisionId: RevisionId,
    val presentation: TargetOccurrencePresentation
)

/** Pure transfer of caller-supplied identities and an existing presentation into a fresh slot. */
object TargetSlotMaterializer {
    fun materialize(
        input: TargetSlotMaterializationInput
    ): WorkoutSlot {
        val occurrence = input.presentation.occurrence
        require(occurrence.occurrenceKey.isNotBlank()) {
            "a target slot needs a non-blank occurrence identity"
        }
        require(occurrence.components.all { it.ruleId.isNotBlank() && it.workoutId.isNotBlank() }) {
            "a target slot needs a valid occurrence identity"
        }
        return WorkoutSlot(
            slotId = input.slotId,
            programId = input.programId,
            revisionId = input.revisionId,
            programDayId = input.presentation.programDayId,
            plannedFor = occurrence.plannedFor,
            status = SlotStatus.PLANNED,
            attempts = emptyList(),
            completedAt = null,
            targetOccurrenceKey = occurrence.occurrenceKey
        )
    }
}
