package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepository
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.target.PersistedTargetOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrencePresentation
import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import com.monkfitness.app.domain.program.target.TargetSlotMaterializationInput
import com.monkfitness.app.domain.program.target.TargetSlotMaterializer

/** Caller-owned target identities and the already-presented values ready to persist. */
data class TargetScheduleSlotPersistenceInput(
    val programId: ProgramId,
    val revisionId: RevisionId,
    val decision: TargetScheduleDecision,
    val presentations: List<TargetOccurrencePresentation>
) {
    init {
        val expectedKeys = decision.created.map { it.occurrenceKey }
        val presentationKeys = presentations.map { it.occurrence.occurrenceKey }
        require(presentationKeys == expectedKeys) {
            "target presentations must match the target schedule decision's created occurrences"
        }
        require(presentationKeys.size == presentationKeys.toSet().size) {
            "target presentations must not contain duplicate occurrence keys"
        }
    }
}

/** The result of one target persistence pass; retained rows are returned exactly as stored. */
data class TargetScheduleSlotPersistenceResult(
    val created: List<WorkoutSlot>,
    val retained: List<WorkoutSlot>
)

/** Typed refusal for a stored target key whose semantic payload no longer matches the decision. */
class TargetSlotPersistenceException(
    val targetOccurrenceKey: String,
    val expectedProgramId: ProgramId,
    val actualProgramId: ProgramId,
    val expectedRevisionId: RevisionId,
    val actualRevisionId: RevisionId,
    val expectedProgramDayId: ProgramDayId,
    val actualProgramDayId: ProgramDayId,
    val expectedPlannedFor: java.time.LocalDate,
    val actualPlannedFor: java.time.LocalDate,
    val actualTargetOccurrenceKey: String?
) : IllegalArgumentException(
    "target occurrence $targetOccurrenceKey already exists with a different semantic payload"
)

/**
 * Orchestrates target lookup, Stage 9 materialization, and repository insertion.
 *
 * ### The two writes are one unit
 *
 * A target occurrence is now two stored things: the `WorkoutSlot` a user trains against, and the
 * [PersistedTargetOccurrence] that records what the occurrence actually *was* — its planned date and
 * its ordered components, which a slot row has no column for. Both are written inside one
 * `inTransaction` block, so the engine commits them together or rolls both back. A pass that failed
 * between them would otherwise leave a slot pointing at a target identity whose components do not
 * exist anywhere, and a read-back that had to reconstruct those components from the key or the slot
 * would be inventing them.
 *
 * The transaction is a **port** ([inTransaction]), not a database: this class still reaches storage
 * only through the two repositories, exactly as §30 step 10's boundary was written. Production passes
 * the database's own `withTransaction`; the unit tests pass the SQLite engine's real
 * `BEGIN`/`COMMIT`/`ROLLBACK`, so "atomic" here is decided by the engine rather than by this code's
 * bookkeeping.
 *
 * ### Every presented occurrence is stored, created or retained
 *
 * The semantic record is written for **all** presentations, not only the newly created ones, and
 * that is what makes the store idempotent-or-refusing rather than create-only. A repeated pass whose
 * payload is identical writes nothing; a repeated pass whose payload differs — a moved date, a
 * changed rule or workout identity, a reordered component list — is refused by the occurrence
 * repository with a typed [com.monkfitness.app.domain.program.target.TargetOccurrencePersistenceException]
 * and the stored record is left exactly as it was. A later revision or reconciliation pass therefore
 * cannot quietly rewrite a stored occurrence just because a slot with the same target identity is
 * already there: the slot's existence is not evidence that the occurrence's payload is unchanged.
 *
 * The read phase above it is unchanged and still authoritative for the **slot** row: membership stays
 * exactly `(programId, targetOccurrenceKey)` with no date, plan day, revision or position fallback,
 * and [requireSemanticMatch] still refuses a stored slot whose own fields disagree with the decision.
 */
class TargetScheduleSlotPersister(
    private val scheduleRepository: ProgramScheduleRepository,
    private val occurrenceRepository: TargetScheduleOccurrenceRepository,
    private val idGenerator: IdGenerator,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {
    suspend fun persist(
        input: TargetScheduleSlotPersistenceInput
    ): TargetScheduleSlotPersistenceResult {
        val retained = mutableListOf<WorkoutSlot>()
        val created = mutableListOf<WorkoutSlot>()

        input.presentations.forEach { presentation ->
            val key = presentation.occurrence.occurrenceKey
            val existing = scheduleRepository.slotByTargetOccurrenceKey(input.programId, key)
            if (existing == null) {
                created += TargetSlotMaterializer.materialize(
                    TargetSlotMaterializationInput(
                        slotId = SlotId(idGenerator.newId()),
                        programId = input.programId,
                        revisionId = input.revisionId,
                        presentation = presentation
                    )
                )
            } else {
                requireSemanticMatch(input, presentation, existing)
                retained += existing
            }
        }

        inTransaction {
            scheduleRepository.addSlots(created)
            input.presentations.forEach { presentation ->
                occurrenceRepository.store(
                    PersistedTargetOccurrence(
                        programId = input.programId,
                        occurrence = presentation.occurrence
                    )
                )
            }
        }
        return TargetScheduleSlotPersistenceResult(created, retained)
    }

    private fun requireSemanticMatch(
        input: TargetScheduleSlotPersistenceInput,
        presentation: TargetOccurrencePresentation,
        existing: WorkoutSlot
    ) {
        val key = presentation.occurrence.occurrenceKey
        val matches = existing.programId == input.programId &&
            existing.revisionId == input.revisionId &&
            existing.programDayId == presentation.programDayId &&
            existing.plannedFor == presentation.occurrence.plannedFor &&
            existing.targetOccurrenceKey == key
        if (!matches) {
            throw TargetSlotPersistenceException(
                targetOccurrenceKey = key,
                expectedProgramId = input.programId,
                actualProgramId = existing.programId,
                expectedRevisionId = input.revisionId,
                actualRevisionId = existing.revisionId,
                expectedProgramDayId = presentation.programDayId,
                actualProgramDayId = existing.programDayId,
                expectedPlannedFor = presentation.occurrence.plannedFor,
                actualPlannedFor = existing.plannedFor,
                actualTargetOccurrenceKey = existing.targetOccurrenceKey
            )
        }
    }
}
