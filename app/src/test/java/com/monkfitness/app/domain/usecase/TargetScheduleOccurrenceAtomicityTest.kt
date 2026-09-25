package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrencePersistenceException
import com.monkfitness.app.domain.program.target.TargetOccurrencePresentation
import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import com.monkfitness.app.domain.program.target.TargetSlotMaterializationInput
import com.monkfitness.app.domain.program.target.TargetSlotMaterializer
import com.monkfitness.app.di.IdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * §30 step 14's two claims about the **boundary**, measured on a real SQLite engine:
 *
 * ```text
 * 1. the target slot and the target occurrence it presents are one atomic operation, and
 * 2. the occurrence's semantic payload is stored, not reconstructed.
 * ```
 *
 * Claim 1 is measured by planting a failure on the **second** leg of the unit — the component insert,
 * which runs after the slot row and the occurrence's own parent row have both been written. A fault
 * on the first leg would only prove nothing started; a fault on the second proves the rollback
 * reaches back and undoes rows that genuinely landed, which is the only way "all or nothing" is
 * different from "nothing happened".
 *
 * Claim 2 is measured by reading the semantic record back through
 * [com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepository] after a pass, and by the
 * last test here, which is the one a later revision path depends on: a slot's existence is not
 * evidence that the occurrence's payload is unchanged.
 */
class TargetScheduleOccurrenceAtomicityTest {

    private val rig = ProgramDataAccessRig("target-atomicity")
    private val programId = ProgramId(ProgramGraphFixture.programId("target-atomicity"))
    private val revisionId = RevisionId(ProgramGraphFixture.revisionId("target-atomicity"))
    private val day = ProgramDayId(ProgramGraphFixture.dayId("target-atomicity", 1))

    @After
    fun close() {
        rig.close()
    }

    @Test
    fun theFirstPassStoresBothTheSlotAndTheOccurrence() = runBlocking {
        rig.createGraph()

        val result = persister("slot-1").persist(input(presentation("strength:2026-10-05", DAY, day)))

        assertEquals(1, result.created.size)
        assertEquals(
            "the slot exists",
            "slot-1",
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-05")!!.slotId.value
        )
        assertEquals(
            "and so does the occurrence's whole semantic payload",
            presentation("strength:2026-10-05", DAY, day).occurrence,
            rig.targetScheduleOccurrenceRepository
                .occurrenceOf(programId, "strength:2026-10-05")!!.occurrence
        )
    }

    @Test
    fun thePassMaterializesTheSlotExactlyAsStageNineDoes() = runBlocking {
        rig.createGraph()
        val presentation = presentation("strength:2026-10-05", DAY, day)

        val result = persister("slot-1").persist(input(presentation))

        assertEquals(
            TargetSlotMaterializer.materialize(
                TargetSlotMaterializationInput(
                    SlotId("slot-1"),
                    programId,
                    revisionId,
                    presentation
                )
            ),
            result.created.single()
        )
    }

    @Test
    fun aFailureOnTheSecondLegRollsBackBothTheSlotAndTheOccurrence() = runBlocking {
        rig.createGraph()
        val slotsBefore = rig.database.count("program_workout_slot")
        rig.faults.failTargetOccurrenceComponentInsert = true

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { persister("slot-1").persist(input(presentation("strength:2026-10-05", DAY, day))) }
        }

        assertTrue(
            "the failure is the planted one, on the component insert",
            failure.message!!.contains("target occurrence component insert")
        )
        assertEquals(
            "no half of the target state survived: the slot count is exactly what it was",
            slotsBefore,
            rig.database.count("program_workout_slot")
        )
        assertEquals(
            null,
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-05")
        )
        assertEquals(
            "and the occurrence's own parent row rolled back with the component row that failed",
            0,
            rig.database.count("program_target_occurrence")
        )
        assertEquals(0, rig.database.count("program_target_occurrence_component"))
    }

    @Test
    fun onceTheFailureIsClearedTheWholeUnitLandsTogether() = runBlocking {
        rig.createGraph()
        rig.faults.failTargetOccurrenceComponentInsert = true
        runCatching { persister("slot-1").persist(input(presentation("strength:2026-10-05", DAY, day))) }
        rig.faults.failTargetOccurrenceComponentInsert = false

        persister("slot-1").persist(input(presentation("strength:2026-10-05", DAY, day)))

        assertEquals(
            "slot-1",
            rig.programScheduleRepository.slotByTargetOccurrenceKey(programId, "strength:2026-10-05")!!.slotId.value
        )
        assertEquals(
            1,
            rig.database.count("program_target_occurrence")
        )
    }

    @Test
    fun aRepeatedPassIsIdempotentAcrossBothHalves() = runBlocking {
        rig.createGraph()
        val request = input(presentation("strength:2026-10-05", DAY, day))

        val first = persister("slot-1").persist(request)
        val second = persister("slot-must-not-be-used").persist(request)

        assertEquals(first.created, second.retained)
        assertTrue(second.created.isEmpty())
        assertEquals(1, rig.database.count("program_target_occurrence"))
        assertEquals(1, rig.database.count("program_target_occurrence_component"))
    }

    @Test
    fun aRepeatedPassWithAChangedPlannedDateIsRefusedAndChangesNothing() = runBlocking {
        rig.createGraph()
        persister("slot-1").persist(input(presentation("strength:2026-10-05", DAY, day)))

        assertThrows(TargetSlotPersistenceException::class.java) {
            runBlocking {
                persister("slot-must-not-be-used").persist(
                    input(presentation("strength:2026-10-05", DAY.plusDays(1), day))
                )
            }
        }
        assertEquals(
            DAY,
            rig.targetScheduleOccurrenceRepository
                .occurrenceOf(programId, "strength:2026-10-05")!!.occurrence.plannedFor
        )
    }

    @Test
    fun aRepeatedPassWithAChangedComponentIdentityIsRefused() = runBlocking {
        rig.createGraph()
        persister("slot-1").persist(input(presentation("strength:2026-10-05", DAY, day)))

        val failure = assertThrows(TargetOccurrencePersistenceException.ConflictingSemanticPayload::class.java) {
            runBlocking {
                persister("slot-must-not-be-used").persist(
                    input(
                        presentation(
                            "strength:2026-10-05",
                            DAY,
                            day,
                            OccurrenceComponent("rule-changed", "workout-changed")
                        )
                    )
                )
            }
        }

        assertEquals("strength:2026-10-05", failure.occurrenceKey)
        assertEquals(
            listOf(OccurrenceComponent("rule-a", "workout-a")),
            rig.targetScheduleOccurrenceRepository
                .occurrenceOf(programId, "strength:2026-10-05")!!.occurrence.components
        )
    }

    @Test
    fun aRepeatedPassWithAReorderedComponentListIsRefused() = runBlocking {
        rig.createGraph()
        persister("slot-1").persist(
            input(
                presentation(
                    "combined:2026-10-05",
                    DAY,
                    day,
                    OccurrenceComponent("rule-b", "workout-b"),
                    OccurrenceComponent("rule-a", "workout-a")
                )
            )
        )

        assertThrows(TargetOccurrencePersistenceException.ConflictingSemanticPayload::class.java) {
            runBlocking {
                persister("slot-must-not-be-used").persist(
                    input(
                        presentation(
                            "combined:2026-10-05",
                            DAY,
                            day,
                            OccurrenceComponent("rule-a", "workout-a"),
                            OccurrenceComponent("rule-b", "workout-b")
                        )
                    )
                )
            }
        }
        assertEquals(
            listOf("rule-b", "rule-a"),
            rig.targetScheduleOccurrenceRepository
                .occurrenceOf(programId, "combined:2026-10-05")!!.occurrence.components
                .map { it.ruleId }
        )
    }

    @Test
    fun multipleOccurrencesLandAsOneUnitOrNotAtAll() = runBlocking {
        rig.createGraph()
        val slotsBefore = rig.database.count("program_workout_slot")
        rig.faults.failTargetOccurrenceComponentInsert = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                persister("slot-a", "slot-b").persist(
                    input(
                        presentation("strength:2026-10-05", DAY, day),
                        presentation("mobility:2026-10-05", DAY, ProgramDayId(ProgramGraphFixture.dayId("target-atomicity", 3)))
                    )
                )
            }
        }

        assertEquals(
            "neither occurrence of the pass survived, so a partial pass is not a thing that can happen",
            slotsBefore,
            rig.database.count("program_workout_slot")
        )
        assertEquals(0, rig.database.count("program_target_occurrence"))
    }

    @Test
    fun aSlotThatAlreadyExistsDoesNotLicenseRewritingTheStoredOccurrence() = runBlocking {
        rig.createGraph()
        val key = "strength:2026-10-05"
        // A slot with this target identity is planted directly, exactly as a previous build's pass or
        // a reconciliation would have left it — with **no** semantic record beside it.
        rig.programScheduleRepository.addSlots(
            listOf(
                com.monkfitness.app.domain.program.WorkoutSlot(
                    slotId = com.monkfitness.app.domain.common.SlotId("planted-slot"),
                    programId = programId,
                    revisionId = revisionId,
                    programDayId = day,
                    plannedFor = DAY,
                    status = com.monkfitness.app.domain.program.SlotStatus.PLANNED,
                    targetOccurrenceKey = key
                )
            )
        )

        val first = persister("must-not-be-used").persist(
            input(presentation(key, DAY, day, OccurrenceComponent("rule-a", "workout-a")))
        )
        assertTrue("the planted slot was retained, not replaced", first.retained.isNotEmpty())
        assertEquals(
            "and the occurrence was stored for the first time, which is not a rewrite",
            listOf(OccurrenceComponent("rule-a", "workout-a")),
            rig.targetScheduleOccurrenceRepository.occurrenceOf(programId, key)!!.occurrence.components
        )

        // Now the later-revision case that matters: the slot is still there, and the pass offers a
        // different payload for the same target identity. A slot's existence is not evidence that the
        // occurrence is unchanged, so the write is refused and the stored payload stands.
        assertThrows(TargetOccurrencePersistenceException.ConflictingSemanticPayload::class.java) {
            runBlocking {
                persister("must-not-be-used").persist(
                    input(presentation(key, DAY, day, OccurrenceComponent("rule-b", "workout-b")))
                )
            }
        }
        assertEquals(
            "the stored payload is the first one, not the revision's",
            listOf(OccurrenceComponent("rule-a", "workout-a")),
            rig.targetScheduleOccurrenceRepository.occurrenceOf(programId, key)!!.occurrence.components
        )
    }

    @Test
    fun aLegacySlotWithNoTargetOccurrenceKeyIsStillLegalAndUntouched() = runBlocking {
        rig.createGraph()
        val legacySlots = rig.programScheduleRepository.slotsOfProgram(programId)
        assertTrue(
            "the fixture's own slots are the legacy case",
            legacySlots.isNotEmpty() && legacySlots.all { it.targetOccurrenceKey == null }
        )

        persister("slot-new").persist(input(presentation("strength:2026-10-05", DAY, day)))

        assertEquals(
            "the legacy slots are all still there, with their NULL target key",
            legacySlots.map { it.slotId },
            rig.programScheduleRepository.slotsOfProgram(programId)
                .filter { it.targetOccurrenceKey == null }
                .map { it.slotId }
        )
        assertNull(
            "and no semantic record was invented for one",
            rig.targetScheduleOccurrenceRepository.occurrenceOf(programId, "legacy")
        )
    }

    @Test
    fun twoProgramsMayUseTheSameOccurrenceKeyIndependentlyThroughTheBoundary() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("other-target-atomicity")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val otherDay = other.revision.days.first().programDayId

        persister("slot-a").persist(input(presentation("strength:2026-10-05", DAY, day)))
        val otherResult = persister("slot-b").persist(
            TargetScheduleSlotPersistenceInput(
                programId = other.program.programId,
                revisionId = other.revision.revisionId,
                decision = decision(listOf(occurrence("strength:2026-10-05", DAY))),
                presentations = listOf(
                    TargetOccurrencePresentation(occurrence("strength:2026-10-05", DAY), otherDay)
                )
            )
        )

        assertEquals("slot-b", otherResult.created.single().slotId.value)
        assertEquals(
            "slot-a",
            rig.programScheduleRepository
                .slotByTargetOccurrenceKey(programId, "strength:2026-10-05")!!.slotId.value
        )
        assertEquals(
            "both records exist independently",
            2,
            rig.database.count("program_target_occurrence")
        )
    }

    private fun persister(vararg ids: String): TargetScheduleSlotPersister {
        val next = ids.iterator()
        return TargetScheduleSlotPersister(
            scheduleRepository = rig.programScheduleRepository,
            occurrenceRepository = rig.targetScheduleOccurrenceRepository,
            idGenerator = IdGenerator { if (next.hasNext()) next.next() else error("unexpected id generation") },
            inTransaction = rig.transaction
        )
    }

    private fun input(vararg presentations: TargetOccurrencePresentation) =
        TargetScheduleSlotPersistenceInput(
            programId = programId,
            revisionId = revisionId,
            decision = decision(presentations.map { it.occurrence }),
            presentations = presentations.toList()
        )

    private fun decision(occurrences: List<PlannedOccurrence>) = TargetScheduleDecision(
        targetPlan = com.monkfitness.app.domain.program.target.TargetPlan(
            planned = occurrences,
            reconciliation = com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliation(
                preserved = emptyList(),
                superseded = emptyList(),
                added = occurrences
            )
        ),
        preserved = emptyList(),
        retained = emptyList(),
        created = occurrences,
        superseded = emptyList(),
        missed = emptyList()
    )

    private fun presentation(
        key: String,
        date: LocalDate,
        programDayId: ProgramDayId,
        vararg components: OccurrenceComponent
    ) = TargetOccurrencePresentation(
        occurrence(
            key,
            date,
            *components.ifEmpty { arrayOf(OccurrenceComponent("rule-a", "workout-a")) }
        ),
        programDayId
    )

    /**
     * One occurrence carrying real components.
     *
     * `PlannedOccurrence` requires a non-empty component list (§9: an occurrence with no work is not
     * an occurrence), and this phase does not weaken that to make a fixture convenient. The default is
     * therefore a *real* component, not an empty one.
     */
    private fun occurrence(
        key: String,
        date: LocalDate,
        vararg components: OccurrenceComponent
    ) = PlannedOccurrence(
        occurrenceKey = key,
        plannedFor = date,
        components = components.toList().ifEmpty { listOf(OccurrenceComponent("rule-a", "workout-a")) }
    )

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
    }
}
