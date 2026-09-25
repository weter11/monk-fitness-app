package com.monkfitness.app.data.repository

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.target.PersistedTargetOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrencePersistenceException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * `TargetScheduleOccurrenceRepository`: a target occurrence's semantic payload, stored and read back.
 *
 * The claims under test are all about **fidelity and refusal**, and they are measured against a real
 * SQLite engine through the DAOs' own SQL rather than against a mock's bookkeeping:
 *
 *  * what goes in comes back out — key, planned date, every component field, and the component order;
 *  * a repeated identical write writes nothing, and a repeated *different* write is refused rather
 *    than applied;
 *  * membership is `(programId, occurrenceKey)`, so two Programs may share a key and one Program may
 *    not hold one twice;
 *  * the key's own text is never a source of anything.
 */
class TargetScheduleOccurrenceRepositoryTest {

    private val rig = ProgramDataAccessRig("target-occurrence")
    private val programId = ProgramId(ProgramGraphFixture.programId("target-occurrence"))

    @After
    fun close() {
        rig.close()
    }

    @Test
    fun anAbsentIdentityStoresTheWholeOccurrence() = runBlocking {
        rig.createGraph()
        val occurrence = occurrence("strength:2026-10-05", DAY)

        rig.targetScheduleOccurrenceRepository.store(PersistedTargetOccurrence(programId, occurrence))

        assertEquals(
            occurrence,
            rig.targetScheduleOccurrenceRepository.occurrenceOf(programId, occurrence.occurrenceKey)!!.occurrence
        )
    }

    @Test
    fun readBackReproducesAnIdenticalPlannedOccurrence() = runBlocking {
        rig.createGraph()
        val occurrence = occurrence(
            "combined:2026-10-05:rule-b,rule-a",
            DAY,
            OccurrenceComponent("rule-b", "workout-b"),
            OccurrenceComponent("rule-a", "workout-a")
        )

        rig.targetScheduleOccurrenceRepository.store(PersistedTargetOccurrence(programId, occurrence))
        val read = rig.targetScheduleOccurrenceRepository.occurrenceOf(programId, occurrence.occurrenceKey)

        assertEquals(occurrence, read!!.occurrence)
        assertEquals("the Program is part of what was read back", programId, read.programId)
    }

    @Test
    fun multipleComponentsKeepThePresentedOrder() = runBlocking {
        rig.createGraph()
        // Deliberately not alphabetical: an implementation that sorted on write or on read would
        // return a different order, and that is the whole point of storing `position`.
        val occurrence = occurrence(
            "combined:2026-10-05",
            DAY,
            OccurrenceComponent("rule-z", "workout-z"),
            OccurrenceComponent("rule-m", "workout-m"),
            OccurrenceComponent("rule-a", "workout-a")
        )

        rig.targetScheduleOccurrenceRepository.store(PersistedTargetOccurrence(programId, occurrence))
        val read = rig.targetScheduleOccurrenceRepository.occurrenceOf(programId, occurrence.occurrenceKey)

        assertEquals(
            listOf("rule-z", "rule-m", "rule-a"),
            read!!.occurrence.components.map { it.ruleId }
        )
        assertEquals(
            "and each component's workout identity travelled with its own rule, not with its position",
            listOf("workout-z", "workout-m", "workout-a"),
            read.occurrence.components.map { it.workoutId }
        )
    }

    @Test
    fun aRepeatedIdenticalWriteChangesNothing() = runBlocking {
        rig.createGraph()
        val occurrence = occurrence(
            "strength:2026-10-05",
            DAY,
            OccurrenceComponent("rule-a", "workout-a"),
            OccurrenceComponent("rule-b", "workout-b")
        )
        val repository = rig.targetScheduleOccurrenceRepository
        repository.store(PersistedTargetOccurrence(programId, occurrence))

        repository.store(PersistedTargetOccurrence(programId, occurrence))
        repository.store(PersistedTargetOccurrence(programId, occurrence))

        assertEquals(
            "still one parent row and one row per component — an identical repeat wrote nothing",
            1,
            rig.database.count("program_target_occurrence")
        )
        assertEquals(2, rig.database.count("program_target_occurrence_component"))
        assertEquals(occurrence, repository.occurrenceOf(programId, occurrence.occurrenceKey)!!.occurrence)
    }

    @Test
    fun aChangedPlannedDateIsRefusedAndTheStoredRecordIsUntouched() = runBlocking {
        rig.createGraph()
        val stored = occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-a"))
        val repository = rig.targetScheduleOccurrenceRepository
        repository.store(PersistedTargetOccurrence(programId, stored))

        val failure = assertThrows(TargetOccurrencePersistenceException.ConflictingSemanticPayload::class.java) {
            runBlocking {
                repository.store(
                    PersistedTargetOccurrence(programId, stored.copy(plannedFor = DAY.plusDays(1)))
                )
            }
        }

        assertEquals("strength:2026-10-05", failure.occurrenceKey)
        assertEquals(stored, failure.storedPayload)
        assertEquals(DAY.plusDays(1), failure.requestedPayload.plannedFor)
        assertEquals(
            "and the refusal is not an overwrite: the stored date is still the one that was written",
            DAY,
            repository.occurrenceOf(programId, "strength:2026-10-05")!!.occurrence.plannedFor
        )
    }

    @Test
    fun aChangedComponentIdentityIsRefusedAndTheStoredRecordIsUntouched() = runBlocking {
        rig.createGraph()
        val stored = occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-a"))
        val repository = rig.targetScheduleOccurrenceRepository
        repository.store(PersistedTargetOccurrence(programId, stored))

        assertThrows(TargetOccurrencePersistenceException.ConflictingSemanticPayload::class.java) {
            runBlocking {
                repository.store(
                    PersistedTargetOccurrence(
                        programId,
                        occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-CHANGED"))
                    )
                )
            }
        }

        assertEquals(
            listOf(OccurrenceComponent("rule-a", "workout-a")),
            repository.occurrenceOf(programId, "strength:2026-10-05")!!.occurrence.components
        )
    }

    @Test
    fun aReorderedComponentListIsRefusedBecauseOrderIsPartOfThePayload() = runBlocking {
        rig.createGraph()
        val stored = occurrence(
            "combined:2026-10-05",
            DAY,
            OccurrenceComponent("rule-a", "workout-a"),
            OccurrenceComponent("rule-b", "workout-b")
        )
        val repository = rig.targetScheduleOccurrenceRepository
        repository.store(PersistedTargetOccurrence(programId, stored))

        assertThrows(TargetOccurrencePersistenceException.ConflictingSemanticPayload::class.java) {
            runBlocking {
                repository.store(
                    PersistedTargetOccurrence(
                        programId,
                        occurrence(
                            "combined:2026-10-05",
                            DAY,
                            OccurrenceComponent("rule-b", "workout-b"),
                            OccurrenceComponent("rule-a", "workout-a")
                        )
                    )
                )
            }
        }

        assertEquals(
            "the same two components in the original order, not the refused order",
            listOf("rule-a", "rule-b"),
            repository.occurrenceOf(programId, "combined:2026-10-05")!!.occurrence.components
                .map { it.ruleId }
        )
    }

    @Test
    fun oneProgramCannotHoldTheSameTargetIdentityTwice() = runBlocking {
        rig.createGraph()
        val repository = rig.targetScheduleOccurrenceRepository
        val stored = occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-a"))
        repository.store(PersistedTargetOccurrence(programId, stored))

        assertThrows(TargetOccurrencePersistenceException.ConflictingSemanticPayload::class.java) {
            runBlocking {
                repository.store(
                    PersistedTargetOccurrence(
                        programId,
                        occurrence("strength:2026-10-05", DAY, OccurrenceComponent("other", "other"))
                    )
                )
            }
        }
        assertEquals(1, rig.database.count("program_target_occurrence"))
    }

    @Test
    fun twoProgramsMayUseTheSameOccurrenceKeyIndependently() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("other-target-occurrence")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val otherProgramId = other.program.programId
        val repository = rig.targetScheduleOccurrenceRepository

        repository.store(
            PersistedTargetOccurrence(
                programId,
                occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-a"))
            )
        )
        repository.store(
            PersistedTargetOccurrence(
                otherProgramId,
                occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-b", "workout-b"))
            )
        )

        assertEquals(
            "the same key, two Programs, two independent payloads",
            listOf("rule-a"),
            repository.occurrenceOf(programId, "strength:2026-10-05")!!.occurrence.components
                .map { it.ruleId }
        )
        assertEquals(
            listOf("rule-b"),
            repository.occurrenceOf(otherProgramId, "strength:2026-10-05")!!.occurrence.components
                .map { it.ruleId }
        )
        assertNotEquals(
            repository.occurrenceOf(programId, "strength:2026-10-05"),
            repository.occurrenceOf(otherProgramId, "strength:2026-10-05")
        )
    }

    @Test
    fun theOccurrenceKeyTextIsNeverReadForAValue() = runBlocking {
        rig.createGraph()
        val repository = rig.targetScheduleOccurrenceRepository
        // Every part of this key *could* be mistaken for a rule id, a workout id and a date, and the
        // components deliberately match none of them. If any part of the key were parsed into the
        // payload, the read-back below would not equal what was stored.
        val key = "2026-10-05:rule-appears-in-key:workout-also-in-key"
        val occurrence = occurrence(key, DAY, OccurrenceComponent("rule-stored", "workout-stored"))

        repository.store(PersistedTargetOccurrence(programId, occurrence))
        val read = repository.occurrenceOf(programId, key)!!

        assertEquals(key, read.occurrence.occurrenceKey)
        assertEquals(
            "the components are the stored ones, not anything the key spells",
            listOf(OccurrenceComponent("rule-stored", "workout-stored")),
            read.occurrence.components
        )
        assertEquals(DAY, read.occurrence.plannedFor)
    }

    @Test
    fun aKeyThatLooksLikeSomethingElseIsStoredAndReadBackAsExactlyItself() = runBlocking {
        rig.createGraph()
        val repository = rig.targetScheduleOccurrenceRepository
        val awkward = "  spaced:key/with\\separators  "

        repository.store(
            PersistedTargetOccurrence(
                programId,
                occurrence(awkward, DAY, OccurrenceComponent("rule-a", "workout-a"))
            )
        )

        assertEquals(
            "no trim, no normalization, no case folding: the key is an opaque token",
            awkward,
            repository.occurrenceOf(programId, awkward)!!.occurrence.occurrenceKey
        )
        assertNull(
            "and a normalized variant of it is a different identity, not a second spelling of this one",
            repository.occurrenceOf(programId, "spaced:key/with\\separators")
        )
    }

    @Test
    fun noLegacyOrPlanDayDerivedIdentityIsEverAcceptedInPlaceOfAStoredOne() = runBlocking {
        rig.createGraph()
        val repository = rig.targetScheduleOccurrenceRepository
        val stored = occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-a"))
        repository.store(PersistedTargetOccurrence(programId, stored))

        // Each of these is what a reconstructing read-back would have to invent, offered as a rewrite
        // of the stored payload. Every one is refused, and the stored payload survives all of them.
        val invented = listOf(
            OccurrenceComponent("legacy", "legacy"),
            OccurrenceComponent(ProgramGraphFixture.dayId("target-occurrence", 1), "workout-a"),
            OccurrenceComponent("rule-a", DAY.toString())
        )
        for (fabricated in invented) {
            assertThrows(TargetOccurrencePersistenceException.ConflictingSemanticPayload::class.java) {
                runBlocking {
                    repository.store(
                        PersistedTargetOccurrence(
                            programId,
                            occurrence("strength:2026-10-05", DAY, fabricated)
                        )
                    )
                }
            }
        }

        assertEquals(
            listOf(OccurrenceComponent("rule-a", "workout-a")),
            repository.occurrenceOf(programId, "strength:2026-10-05")!!.occurrence.components
        )
    }

    @Test
    fun aStoredOccurrenceNeverBecomesAttachedToADifferentProgram() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("reparent-target-occurrence")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val repository = rig.targetScheduleOccurrenceRepository
        val stored = occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-a"))
        repository.store(PersistedTargetOccurrence(programId, stored))

        // Writing the same payload under the other Program is a *separate* record, not a move: the
        // pair is the identity, so the first Program's record is untouched and still readable.
        repository.store(PersistedTargetOccurrence(other.program.programId, stored))

        assertEquals(
            "two Programs, two records — a re-parent would have left one",
            2,
            rig.database.count("program_target_occurrence")
        )
        assertEquals(
            stored,
            repository.occurrenceOf(programId, "strength:2026-10-05")!!.occurrence
        )
        assertEquals(
            "and a component cannot be attached to an occurrence that does not exist",
            true,
            run {
                val refusal = rig.database.expectError(
                    "INSERT INTO `program_target_occurrence_component` (`programId`, `occurrenceKey`, " +
                        "`position`, `ruleId`, `workoutId`) VALUES " +
                        "('${other.program.programId.value}', 'no-such-occurrence', 0, 'rule-x', " +
                        "'workout-x')"
                )
                refusal.contains("FOREIGN KEY")
            }
        )
    }

    @Test
    fun aLegacySlotWithNoTargetOccurrenceKeyIsUntouchedAndHasNoSemanticRecord() = runBlocking {
        rig.createGraph()
        val repository = rig.targetScheduleOccurrenceRepository

        // The rig's own graph holds three slots written before this stage, every one of them with a
        // NULL `targetOccurrenceKey`. Reading the target path for one must simply find nothing.
        val legacySlots = rig.programScheduleRepository.slotsOfProgram(programId)
        assertTrue(
            "the fixture graph is the legacy case: slots with no target identity",
            legacySlots.all { it.targetOccurrenceKey == null }
        )
        assertTrue(repository.occurrencesOfProgram(programId).isEmpty())
        assertNull(repository.occurrenceOf(programId, "strength:2026-10-05"))

        // Storing a real occurrence beside them is legal and does not touch a legacy slot.
        val stored = occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-a"))
        repository.store(PersistedTargetOccurrence(programId, stored))

        // The occurrence was written through the *occurrence repository*, which owns no slot: storing
        // an occurrence must not create, replace or retire a slot, and a legacy slot must be none of
        // its business. The count is therefore unchanged — the earlier "+ 1" reading was wrong about
        // what this path does, not a rounding error.
        val after = rig.programScheduleRepository.slotsOfProgram(programId)
        assertEquals(
            "every legacy row is still there, with the same NULL target key it always had",
            legacySlots.map { it.slotId },
            after.map { it.slotId }
        )
        assertEquals(
            "and storing an occurrence wrote no slot at all: the two are separate contracts",
            legacySlots.size,
            after.size
        )
        assertTrue(
            "none of them acquired a target identity in the process",
            after.all { it.targetOccurrenceKey == null }
        )
    }

    @Test
    fun aProgramReadsBackItsOwnOccurrencesInPlannedDateOrder() = runBlocking {
        rig.createGraph()
        val repository = rig.targetScheduleOccurrenceRepository
        repository.store(
            PersistedTargetOccurrence(
                programId,
                occurrence("mobility:2026-10-07", DAY.plusDays(2), OccurrenceComponent("r", "w"))
            )
        )
        repository.store(
            PersistedTargetOccurrence(
                programId,
                occurrence("strength:2026-10-05", DAY, OccurrenceComponent("r", "w"))
            )
        )

        assertEquals(
            listOf("strength:2026-10-05", "mobility:2026-10-07"),
            repository.occurrencesOfProgram(programId).map { it.occurrenceKey }
        )
    }

    @Test
    fun aFailureWhileStoringLeavesNeitherHalfOfTheOccurrencePersisted() = runBlocking {
        rig.createGraph()
        rig.faults.failTargetOccurrenceComponentInsert = true
        val stored = occurrence("strength:2026-10-05", DAY, OccurrenceComponent("rule-a", "workout-a"))

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                rig.transaction {
                    rig.targetScheduleOccurrenceRepository.store(
                        PersistedTargetOccurrence(programId, stored)
                    )
                }
            }
        }

        assertTrue(failure.message!!.contains("planted fault"))
        assertEquals(
            "the parent row rolled back with the component row that failed: an occurrence with no " +
                "components is exactly the half-written state this phase exists to prevent",
            0,
            rig.database.count("program_target_occurrence")
        )
        assertEquals(0, rig.database.count("program_target_occurrence_component"))
    }

    @Test
    fun anOccurrenceIsDestroyedWithItsProgramAndItsComponentsWithTheOccurrence() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.graph("cascade-target-occurrence")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val repository = rig.targetScheduleOccurrenceRepository
        for (id in listOf(programId, other.program.programId)) {
            repository.store(
                PersistedTargetOccurrence(
                    id,
                    occurrence("strength:2026-10-05", DAY, OccurrenceComponent("r", "w"))
                )
            )
        }
        assertEquals(2, rig.database.count("program_target_occurrence"))
        assertEquals(2, rig.database.count("program_target_occurrence_component"))

        rig.database.exec("DELETE FROM `program` WHERE `programId` = '${other.program.programId.value}'")

        assertEquals("the other Program's occurrence survived", 1, rig.database.count("program_target_occurrence"))
        assertEquals("and so did its component", 1, rig.database.count("program_target_occurrence_component"))
    }

    @Test
    fun twoComponentsCannotClaimTheSamePlaceInOneOccurrence() = runBlocking {
        rig.createGraph()
        val stored = occurrence(
            "combined:2026-10-05",
            DAY,
            OccurrenceComponent("rule-a", "workout-a"),
            OccurrenceComponent("rule-b", "workout-b")
        )
        rig.targetScheduleOccurrenceRepository.store(PersistedTargetOccurrence(programId, stored))

        val refusal = rig.database.expectError(
            "INSERT INTO `program_target_occurrence_component` (`programId`, `occurrenceKey`, " +
                "`position`, `ruleId`, `workoutId`) VALUES ('${programId.value}', " +
                "'combined:2026-10-05', 0, 'rule-c', 'workout-c')"
        )

        assertTrue(
            "the order is stored, so two components cannot claim the same place: $refusal",
            refusal.contains("UNIQUE")
        )
    }

    @Test
    fun anAbsentOccurrenceReadsAsAbsentRatherThanAsAnInventedOne() = runBlocking {
        rig.createGraph()

        assertNull(
            "a key nothing was stored under returns null, not a record built from the slot or the key",
            rig.targetScheduleOccurrenceRepository.occurrenceOf(programId, "strength:2026-10-05")
        )
    }

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
