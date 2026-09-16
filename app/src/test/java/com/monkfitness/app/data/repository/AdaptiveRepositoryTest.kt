package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AdaptiveDecisionHistoryDao
import com.monkfitness.app.data.local.FamilyProgressionStateDao
import com.monkfitness.app.data.model.AdaptiveDecisionRecord
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.AdaptiveAction
import com.monkfitness.app.domain.adaptive.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.FamilyAdaptationState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the adaptive persistence adapter.
 *
 * What is pinned here is the boundary contract of Task 8, not adaptive behaviour: the two tables are
 * separate sources of truth, a family's current row is unique per family and per program revision, a
 * level outside the documented range cannot be stored at all, every value the domain vocabulary can
 * hold survives a round trip, and history only ever grows.
 *
 * ## Why the DAOs are faked
 *
 * This repository has no Robolectric, no androidx.test and no in-memory-Room harness — unit tests are
 * pure JVM and the existing data-layer suites fake their DAO the same way. The fakes below model the
 * two DAO contracts the repository depends on: `INSERT OR REPLACE` on the composite
 * `(programRevision, familyId)` key, `id` assignment on append, and the ordered selects the queries
 * declare. The SQL itself is pinned separately, by executing the real migration DDL in
 * `AdaptivePersistenceSchemaTest`, so a fake that drifts from its query contract cannot pass both.
 *
 * The transaction is supplied by the caller (`AppDatabase.withTransaction` in production, exactly as
 * the C3 maintenance path does it), which is what makes the all-or-nothing guarantee testable here: a
 * committing runner and a rolling-back runner produce observably different databases.
 */
class AdaptiveRepositoryTest {

    // ---- fixtures ------------------------------------------------------------------------------

    private fun familyState(
        familyId: String = "pushups",
        progressionLevel: Int = 0,
        currentExerciseId: String? = null,
        adaptationState: AdaptiveState = AdaptiveState.HOLD,
        precedingProgressQualifyingWindows: Int = 0,
        precedingRegressQualifyingWindows: Int = 0,
        precedingHighRiskWindows: Int = 0,
        recoveryQualifyingSessions: Int = 0,
        eligibleSessionsSinceLastProgressionChange: Int? = null,
        programRevision: Int = 0,
        updatedAt: Long = 1_789_000_000_000L,
        policyVersion: Int = 1
    ) = FamilyProgressionState(
        familyId = familyId,
        progressionLevel = progressionLevel,
        currentExerciseId = currentExerciseId,
        adaptationState = adaptationState,
        precedingProgressQualifyingWindows = precedingProgressQualifyingWindows,
        precedingRegressQualifyingWindows = precedingRegressQualifyingWindows,
        precedingHighRiskWindows = precedingHighRiskWindows,
        recoveryQualifyingSessions = recoveryQualifyingSessions,
        eligibleSessionsSinceLastProgressionChange = eligibleSessionsSinceLastProgressionChange,
        programRevision = programRevision,
        updatedAt = updatedAt,
        policyVersion = policyVersion
    )

    private fun decision(
        state: AdaptiveState = AdaptiveState.HOLD,
        previousState: AdaptiveState = AdaptiveState.HOLD,
        actions: List<AdaptiveAction> = listOf(AdaptiveAction.MAINTAIN_STIMULUS),
        reasonCode: AdaptiveReasonCode = AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
        policyVersion: Int = 1,
        familyId: String? = "pushups"
    ) = AdaptiveDecision(
        state = state,
        previousState = previousState,
        actions = actions,
        reasonCode = reasonCode,
        policyVersion = policyVersion,
        familyId = familyId
    )

    /** In-memory [FamilyProgressionStateDao]: `INSERT OR REPLACE` on `(programRevision, familyId)`. */
    private class FakeStateDao : FamilyProgressionStateDao {
        val rows = mutableListOf<FamilyProgressionState>()
        var failure: String? = null

        override suspend fun getFamilyStates(programRevision: Int): List<FamilyProgressionState> =
            rows.filter { it.programRevision == programRevision }.sortedBy { it.familyId }

        override suspend fun getFamilyState(programRevision: Int, familyId: String): FamilyProgressionState? =
            rows.firstOrNull { it.programRevision == programRevision && it.familyId == familyId }

        override suspend fun upsertFamilyState(state: FamilyProgressionState) {
            failure?.let { throw IllegalStateException(it) }
            // SQLite's INSERT OR REPLACE deletes the conflicting row and inserts a new one.
            rows.removeAll {
                it.programRevision == state.programRevision && it.familyId == state.familyId
            }
            rows += state
        }
    }

    /** In-memory [AdaptiveDecisionHistoryDao]: append-only, `id` assigned on insert, ordered reads. */
    private class FakeHistoryDao : AdaptiveDecisionHistoryDao {
        val rows = mutableListOf<AdaptiveDecisionRecord>()
        var failure: String? = null
        private var nextId = 1L

        override suspend fun appendDecision(record: AdaptiveDecisionRecord): Long {
            failure?.let { throw IllegalStateException(it) }
            val stored = if (record.id == 0L) record.copy(id = nextId++) else record
            rows += stored
            return stored.id
        }

        override suspend fun getDecisionHistory(programRevision: Int): List<AdaptiveDecisionRecord> =
            rows.filter { it.programRevision == programRevision }
                .sortedWith(compareBy({ it.cycleNumber }, { it.programDay }, { it.id }))

        override suspend fun getDecisionHistoryForFamily(familyId: String): List<AdaptiveDecisionRecord> =
            rows.filter { it.familyId == familyId }
                .sortedWith(
                    compareBy(
                        { it.programRevision }, { it.cycleNumber }, { it.programDay }, { it.id }
                    )
                )
    }

    private class Fixture {
        val stateDao = FakeStateDao()
        val historyDao = FakeHistoryDao()
        var transactionCount = 0
            private set

        /** A transaction that commits the whole block, as `AppDatabase.withTransaction` does. */
        val committing: suspend (suspend () -> Unit) -> Unit = { block ->
            transactionCount++
            block()
        }

        /**
         * A transaction that rolls the block back on failure: it snapshots both tables, runs the
         * block, and restores the snapshot if the block throws — the observable outcome of a Room
         * rollback, which is what makes a mid-transaction failure leave nothing behind.
         */
        val rollingBack: suspend (suspend () -> Unit) -> Unit = { block ->
            transactionCount++
            val stateBefore = stateDao.rows.toList()
            val historyBefore = historyDao.rows.toList()
            try {
                block()
            } catch (e: Exception) {
                stateDao.rows.apply { clear(); addAll(stateBefore) }
                historyDao.rows.apply { clear(); addAll(historyBefore) }
                throw e
            }
        }

        fun repository(inTransaction: suspend (suspend () -> Unit) -> Unit = committing) =
            AdaptiveRepository(stateDao, historyDao, inTransaction)
    }

    // ---- current state -------------------------------------------------------------------------

    @Test
    fun oneFamilyStateRoundTripsWithEveryStoredField() = runBlocking {
        val fixture = Fixture()
        val stored = familyState(
            familyId = "pullups",
            progressionLevel = 1,
            currentExerciseId = "pullups_negative",
            adaptationState = AdaptiveState.PROGRESS,
            precedingProgressQualifyingWindows = 2,
            precedingRegressQualifyingWindows = 1,
            precedingHighRiskWindows = 3,
            recoveryQualifyingSessions = 4,
            eligibleSessionsSinceLastProgressionChange = 5,
            updatedAt = 1_789_123_456_789L,
            policyVersion = 1
        )

        fixture.repository().saveFamilyState(stored)

        assertEquals(listOf(stored), fixture.repository().familyStates(0))
    }

    @Test
    fun savingTheSameFamilyAgainReplacesItsRowInsteadOfAddingOne() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(familyState(progressionLevel = 0, adaptationState = AdaptiveState.HOLD))
        repository.saveFamilyState(
            familyState(
                progressionLevel = 1,
                adaptationState = AdaptiveState.PROGRESS,
                eligibleSessionsSinceLastProgressionChange = 0,
                updatedAt = 1_789_000_000_999L
            )
        )

        val stored = repository.familyStates(0)
        assertEquals("one family holds one current-state row, never two", 1, stored.size)
        assertEquals("the second write is the one that stands", 1, stored.single().progressionLevel)
        assertEquals(AdaptiveState.PROGRESS, stored.single().adaptationState)
        assertEquals(1_789_000_000_999L, stored.single().updatedAt)
    }

    @Test
    fun familiesPersistIndependently() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(
            familyState(familyId = "pushups", progressionLevel = 1, adaptationState = AdaptiveState.HOLD)
        )
        repository.saveFamilyState(
            familyState(familyId = "squats", progressionLevel = 0, adaptationState = AdaptiveState.PROGRESS)
        )
        repository.saveFamilyState(
            familyState(familyId = "lunges", progressionLevel = -1, adaptationState = AdaptiveState.REGRESS)
        )

        val byFamily = repository.familyStates(0).associateBy { it.familyId }
        assertEquals("pushups = +1 / HOLD", 1, byFamily.getValue("pushups").progressionLevel)
        assertEquals(AdaptiveState.HOLD, byFamily.getValue("pushups").adaptationState)
        assertEquals("squats = 0 / PROGRESS", 0, byFamily.getValue("squats").progressionLevel)
        assertEquals(AdaptiveState.PROGRESS, byFamily.getValue("squats").adaptationState)
        assertEquals("lunges = -1 / REGRESS", -1, byFamily.getValue("lunges").progressionLevel)
        assertEquals(AdaptiveState.REGRESS, byFamily.getValue("lunges").adaptationState)
    }

    @Test
    fun aLevelOutsideTheDocumentedRangeCannotBeStored() {
        for (level in listOf(-3, 3, 56, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val thrown = assertThrows(IllegalArgumentException::class.java) {
                familyState(progressionLevel = level)
            }
            assertTrue(
                "the rejection names the level and the range it broke: ${thrown.message}",
                thrown.message!!.contains("progressionLevel") &&
                    thrown.message!!.contains("${FamilyProgressionState.MIN_LEVEL}..${FamilyProgressionState.MAX_LEVEL}")
            )
        }
    }

    @Test
    fun theDocumentedLevelBoundariesAreStorable() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(familyState(familyId = "pushups", progressionLevel = -2))
        repository.saveFamilyState(familyState(familyId = "squats", progressionLevel = 2))

        assertEquals(-2, repository.familyState(0, "pushups")!!.progressionLevel)
        assertEquals(2, repository.familyState(0, "squats")!!.progressionLevel)
    }

    @Test
    fun aFamilyWithoutACurrentExerciseRoundTrips() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(familyState(currentExerciseId = null))

        assertNull(repository.familyState(0, "pushups")!!.currentExerciseId)
    }

    @Test
    fun aFamilyOnAConcreteExerciseRoundTrips() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(familyState(currentExerciseId = "pushups_knee"))

        assertEquals("pushups_knee", repository.familyState(0, "pushups")!!.currentExerciseId)
    }

    @Test
    fun everyAdaptationStateRoundTrips() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        assertEquals(
            "the four-state vocabulary the design fixes",
            listOf(
                AdaptiveState.HOLD,
                AdaptiveState.PROGRESS,
                AdaptiveState.REGRESS,
                AdaptiveState.RECOVERY
            ),
            AdaptiveState.entries.toList()
        )
        for (state in AdaptiveState.entries) {
            repository.saveFamilyState(familyState(familyId = state.name, adaptationState = state))
        }

        for (state in AdaptiveState.entries) {
            assertEquals(state, repository.familyState(0, state.name)!!.adaptationState)
        }
    }

    @Test
    fun thePolicyVersionAndTheWriteStampRoundTrip() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(
            familyState(updatedAt = 1_789_555_444_333L, policyVersion = 1)
        )

        val stored = repository.familyState(0, "pushups")!!
        assertEquals(1_789_555_444_333L, stored.updatedAt)
        assertEquals(1, stored.policyVersion)
    }

    @Test
    fun theStoredStateProjectsIntoTheDomainTypeTheEngineReads() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(
            familyState(
                familyId = "plank",
                progressionLevel = -1,
                currentExerciseId = "plank_short",
                adaptationState = AdaptiveState.RECOVERY,
                precedingProgressQualifyingWindows = 2,
                precedingRegressQualifyingWindows = 3,
                precedingHighRiskWindows = 4,
                recoveryQualifyingSessions = 1,
                eligibleSessionsSinceLastProgressionChange = 7,
                updatedAt = 1_789_000_000_123L
            )
        )

        assertEquals(
            listOf(
                FamilyAdaptationState(
                    familyId = "plank",
                    progressionLevel = -1,
                    adaptationState = AdaptiveState.RECOVERY,
                    precedingProgressQualifyingWindows = 2,
                    precedingRegressQualifyingWindows = 3,
                    precedingHighRiskWindows = 4,
                    recoveryQualifyingSessions = 1,
                    eligibleSessionsSinceLastProgressionChange = 7
                )
            ),
            repository.familyAdaptationStates(0)
        )
    }

    @Test
    fun aProgramRevisionKeepsItsOwnFamilyState() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(
            familyState(familyId = "pushups", programRevision = 0, progressionLevel = 2)
        )
        repository.saveFamilyState(
            familyState(familyId = "pushups", programRevision = 1, progressionLevel = 0)
        )

        assertEquals(
            "a revised program starts from baseline",
            0,
            repository.familyState(1, "pushups")!!.progressionLevel
        )
        assertEquals(
            "the earlier revision's state stays readable",
            2,
            repository.familyState(0, "pushups")!!.progressionLevel
        )
        assertEquals("each revision sees exactly its own row", 1, repository.familyStates(0).size)
        assertEquals(1, repository.familyStates(1).size)
    }

    // ---- decision history ----------------------------------------------------------------------

    @Test
    fun aDecisionRecordRoundTrips() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()
        val record = repository.decisionRecord(
            decision = decision(
                state = AdaptiveState.PROGRESS,
                previousState = AdaptiveState.HOLD,
                actions = listOf(AdaptiveAction.INCREASE_STIMULUS),
                reasonCode = AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
                policyVersion = 1
            ),
            programRevision = 0,
            cycleNumber = 2,
            programDay = 17,
            timestamp = 1_789_111_222_333L
        )

        val id = repository.appendDecision(record)

        val stored = repository.decisionHistory(0).single()
        assertEquals("the database assigned the identity", id, stored.id)
        assertEquals(record.copy(id = id), stored)
        assertEquals(1_789_111_222_333L, stored.timestamp)
    }

    @Test
    fun aDecisionRecordKeepsTheFamilyItAudits() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.appendDecision(
            repository.decisionRecord(
                decision = decision(familyId = "pushups", state = AdaptiveState.PROGRESS),
                programRevision = 0,
                cycleNumber = 1,
                programDay = 3,
                timestamp = 10L
            )
        )
        repository.appendDecision(
            repository.decisionRecord(
                decision = decision(familyId = "squats", state = AdaptiveState.REGRESS),
                programRevision = 0,
                cycleNumber = 1,
                programDay = 3,
                timestamp = 11L
            )
        )

        assertEquals(
            "one family's trail holds its own decisions only",
            listOf("pushups"),
            repository.decisionHistoryForFamily("pushups").map { it.familyId }.distinct()
        )
        assertEquals(
            listOf("squats"),
            repository.decisionHistoryForFamily("squats").map { it.familyId }.distinct()
        )
        assertEquals(2, repository.decisionHistory(0).size)
    }

    @Test
    fun aDecisionRecordKeepsTheTransitionItAudits() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.appendDecision(
            repository.decisionRecord(
                decision = decision(
                    state = AdaptiveState.REGRESS,
                    previousState = AdaptiveState.RECOVERY,
                    actions = listOf(AdaptiveAction.REDUCE_STIMULUS),
                    reasonCode = AdaptiveReasonCode.SUSTAINED_DECLINE
                ),
                programRevision = 0,
                cycleNumber = 1,
                programDay = 9,
                timestamp = 20L
            )
        )

        val stored = repository.decisionHistory(0).single()
        assertEquals(AdaptiveState.RECOVERY, stored.previousState)
        assertEquals(AdaptiveState.REGRESS, stored.newState)
    }

    @Test
    fun everyActionRoundTrips() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        val vocabulary = listOf(
            AdaptiveAction.MAINTAIN_STIMULUS to AdaptiveState.HOLD,
            AdaptiveAction.INCREASE_STIMULUS to AdaptiveState.PROGRESS,
            AdaptiveAction.REDUCE_STIMULUS to AdaptiveState.REGRESS,
            AdaptiveAction.RECOVERY_LOAD to AdaptiveState.RECOVERY
        )
        assertEquals(
            "the Task 4 action vocabulary",
            vocabulary.map { it.first },
            AdaptiveAction.entries.toList()
        )
        for ((action, state) in vocabulary) {
            repository.appendDecision(
                repository.decisionRecord(
                    decision = decision(state = state, actions = listOf(action)),
                    programRevision = 0,
                    cycleNumber = 1,
                    programDay = 1,
                    timestamp = 30L
                )
            )
        }

        assertEquals(
            vocabulary.map { it.first },
            repository.decisionHistory(0).map { it.actions.single() }
        )
    }

    @Test
    fun anOrderedActionListRoundTripsInOrder() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        val actions = listOf(AdaptiveAction.RECOVERY_LOAD, AdaptiveAction.REDUCE_STIMULUS)
        repository.appendDecision(
            repository.decisionRecord(
                decision = decision(state = AdaptiveState.RECOVERY, actions = actions),
                programRevision = 0,
                cycleNumber = 1,
                programDay = 2,
                timestamp = 31L
            )
        )

        assertEquals(actions, repository.decisionHistory(0).single().actions)
    }

    @Test
    fun everyReasonCodeRoundTrips() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        assertEquals(
            "the Task 4 reason vocabulary, including the one reserved for the custom-program stage",
            listOf(
                AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
                AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
                AdaptiveReasonCode.SUSTAINED_DECLINE,
                AdaptiveReasonCode.HIGH_LOAD_DETERIORATION,
                AdaptiveReasonCode.RECOVERY,
                AdaptiveReasonCode.PROGRESSION_COOLDOWN,
                AdaptiveReasonCode.CUSTOM_CONFIGURATION_LIMITATION
            ),
            AdaptiveReasonCode.entries.toList()
        )
        for ((index, reason) in AdaptiveReasonCode.entries.withIndex()) {
            repository.appendDecision(
                repository.decisionRecord(
                    decision = decision(reasonCode = reason),
                    programRevision = 0,
                    cycleNumber = 1,
                    programDay = index + 1,
                    timestamp = 40L + index
                )
            )
        }

        assertEquals(
            AdaptiveReasonCode.entries.toList(),
            repository.decisionHistory(0).map { it.reasonCode }
        )
    }

    @Test
    fun decisionHistoryKeepsThePolicyVersionItWasTakenUnder() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.appendDecision(
            repository.decisionRecord(
                decision = decision(policyVersion = 1),
                programRevision = 0,
                cycleNumber = 1,
                programDay = 1,
                timestamp = 50L
            )
        )

        assertEquals(1, repository.decisionHistory(0).single().policyVersion)
    }

    @Test
    fun appendingNeverOverwritesAnEarlierRecord() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        val stamp = 1_789_000_000_000L
        val first = repository.decisionRecord(
            decision = decision(state = AdaptiveState.HOLD),
            programRevision = 0,
            cycleNumber = 1,
            programDay = 1,
            timestamp = stamp
        )
        val second = repository.decisionRecord(
            decision = decision(state = AdaptiveState.PROGRESS, previousState = AdaptiveState.HOLD),
            programRevision = 0,
            cycleNumber = 1,
            programDay = 1,
            timestamp = stamp
        )

        repository.appendDecision(first)
        repository.appendDecision(second)

        val history = repository.decisionHistory(0)
        assertEquals("the same window may legitimately hold more than one record", 2, history.size)
        assertEquals("the first record is still there, unchanged", first.copy(id = 1L), history[0])
        assertEquals(second.copy(id = 2L), history[1])
    }

    @Test
    fun historyQueriesReturnADeterministicOrder() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        // Appended deliberately out of window order, so insertion order cannot be mistaken for it.
        for ((cycle, day) in listOf(2 to 3, 1 to 4, 1 to 1, 2 to 1)) {
            repository.appendDecision(
                repository.decisionRecord(
                    decision = decision(),
                    programRevision = 0,
                    cycleNumber = cycle,
                    programDay = day,
                    timestamp = 60L
                )
            )
        }

        assertEquals(
            listOf(1 to 1, 1 to 4, 2 to 1, 2 to 3),
            repository.decisionHistory(0).map { it.cycleNumber to it.programDay }
        )
    }

    @Test
    fun aFamilysAuditTrailSpansRevisionsWhileOneRevisionsHistoryDoesNot() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.appendDecision(
            repository.decisionRecord(
                decision = decision(familyId = "pushups"),
                programRevision = 0,
                cycleNumber = 1,
                programDay = 5,
                timestamp = 70L
            )
        )
        repository.appendDecision(
            repository.decisionRecord(
                decision = decision(familyId = "pushups"),
                programRevision = 1,
                cycleNumber = 1,
                programDay = 1,
                timestamp = 71L
            )
        )

        assertEquals(
            "the revised program's trail keeps the earlier revision's decision analysable",
            listOf(0 to 1, 1 to 1),
            repository.decisionHistoryForFamily("pushups").map { it.programRevision to it.cycleNumber }
        )
        assertEquals("a single revision's history is that revision's alone", 1, repository.decisionHistory(1).size)
        assertEquals(
            "and it is the revised revision's own decision, not the earlier one's",
            1,
            repository.decisionHistory(1).single().programRevision
        )
    }

    // ---- the two sources of truth stay separate -------------------------------------------------

    @Test
    fun currentStateIsNotDerivedFromDecisionHistory() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()
        repository.saveFamilyState(familyState(progressionLevel = 0, adaptationState = AdaptiveState.HOLD))

        repository.appendDecision(
            repository.decisionRecord(
                decision = decision(
                    state = AdaptiveState.PROGRESS,
                    previousState = AdaptiveState.HOLD,
                    actions = listOf(AdaptiveAction.INCREASE_STIMULUS),
                    reasonCode = AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE
                ),
                programRevision = 0,
                cycleNumber = 1,
                programDay = 6,
                timestamp = 80L
            )
        )

        val stored = repository.familyState(0, "pushups")!!
        assertEquals(
            "an order for the window is not a state: the family still holds HOLD",
            AdaptiveState.HOLD,
            stored.adaptationState
        )
        assertEquals("and its level is untouched by the history", 0, stored.progressionLevel)
        assertEquals("the history was appended next to it, not into it", 1, repository.decisionHistory(0).size)
    }

    @Test
    fun aStateOnlyWriteAppendsNoHistory() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        repository.saveFamilyState(
            familyState(progressionLevel = 1, adaptationState = AdaptiveState.PROGRESS)
        )

        assertTrue("nothing was audited", repository.decisionHistory(0).isEmpty())
        assertEquals(1, repository.familyState(0, "pushups")!!.progressionLevel)
    }

    // ---- the transactional path ------------------------------------------------------------------

    @Test
    fun persistDecisionWritesTheStateAndItsRecordTogether() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()
        val state = familyState(
            progressionLevel = 1,
            adaptationState = AdaptiveState.PROGRESS,
            eligibleSessionsSinceLastProgressionChange = 0,
            updatedAt = 1_789_000_000_001L
        )
        val record = repository.decisionRecord(
            decision = decision(
                state = AdaptiveState.PROGRESS,
                previousState = AdaptiveState.HOLD,
                actions = listOf(AdaptiveAction.INCREASE_STIMULUS),
                reasonCode = AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE
            ),
            programRevision = 0,
            cycleNumber = 1,
            programDay = 12,
            timestamp = 1_789_000_000_002L
        )

        repository.persistDecision(state, record)

        assertEquals(listOf(state), repository.familyStates(0))
        assertEquals(listOf(record.copy(id = 1L)), repository.decisionHistory(0))
        assertEquals("both writes ran inside one transaction", 1, fixture.transactionCount)
    }

    @Test
    fun persistDecisionRollsTheStateBackWhenTheRecordCannotBeAppended() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository(fixture.rollingBack)
        val before = familyState(progressionLevel = 0, adaptationState = AdaptiveState.HOLD)
        repository.saveFamilyState(before)
        fixture.historyDao.failure = "adaptive_decision_record: disk full"

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.persistDecision(
                    familyState(
                        progressionLevel = 1,
                        adaptationState = AdaptiveState.PROGRESS,
                        eligibleSessionsSinceLastProgressionChange = 0
                    ),
                    repository.decisionRecord(
                        decision = decision(
                            state = AdaptiveState.PROGRESS,
                            previousState = AdaptiveState.HOLD,
                            actions = listOf(AdaptiveAction.INCREASE_STIMULUS),
                            reasonCode = AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE
                        ),
                        programRevision = 0,
                        cycleNumber = 1,
                        programDay = 12,
                        timestamp = 90L
                    )
                )
            }
        }

        assertEquals(
            "the failure is not suppressed — it reaches the caller",
            "adaptive_decision_record: disk full",
            thrown.message
        )
        assertEquals(
            "no state is left claiming a transition whose audit record was never written",
            listOf(before),
            repository.familyStates(0)
        )
        assertTrue("the history is empty, not half written", repository.decisionHistory(0).isEmpty())
    }

    @Test
    fun persistDecisionAppendsNoRecordWhenTheStateCannotBeWritten() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository(fixture.rollingBack)
        fixture.stateDao.failure = "family_progression_state: disk full"

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.persistDecision(
                    familyState(progressionLevel = 1),
                    repository.decisionRecord(
                        decision = decision(state = AdaptiveState.PROGRESS),
                        programRevision = 0,
                        cycleNumber = 1,
                        programDay = 12,
                        timestamp = 91L
                    )
                )
            }
        }

        assertTrue("no orphan audit record", repository.decisionHistory(0).isEmpty())
        assertTrue("and no state either", repository.familyStates(0).isEmpty())
    }

    @Test
    fun persistDecisionRejectsAPairThatDoesNotDescribeTheSameFamily() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.persistDecision(
                    familyState(familyId = "squats"),
                    repository.decisionRecord(
                        decision = decision(familyId = "pushups"),
                        programRevision = 0,
                        cycleNumber = 1,
                        programDay = 1,
                        timestamp = 92L
                    )
                )
            }
        }

        assertTrue(
            "the rejection names both families: ${thrown.message}",
            thrown.message!!.contains("squats") && thrown.message!!.contains("pushups")
        )
        assertEquals("nothing was written", 0, fixture.transactionCount)
    }

    @Test
    fun persistDecisionRejectsAPairThatDoesNotDescribeTheSameRevision() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.persistDecision(
                    familyState(programRevision = 1),
                    repository.decisionRecord(
                        decision = decision(),
                        programRevision = 0,
                        cycleNumber = 1,
                        programDay = 1,
                        timestamp = 93L
                    )
                )
            }
        }

        assertEquals("nothing was written", 0, fixture.transactionCount)
    }

    // ---- the boundary refuses what it cannot audit ------------------------------------------------

    @Test
    fun aDecisionWithoutAFamilyCannotBeAudited() {
        val fixture = Fixture()
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            fixture.repository().decisionRecord(
                decision = decision(familyId = null),
                programRevision = 0,
                cycleNumber = 1,
                programDay = 1,
                timestamp = 94L
            )
        }

        assertTrue(
            "the rejection says the family id is what is missing: ${thrown.message}",
            thrown.message!!.contains("family id")
        )
    }

    @Test
    fun aRecordOutsideTheStoredContractCannotBeBuilt() {
        val invalidRecords = listOf<() -> AdaptiveDecisionRecord>(
            { recordFixture(familyId = "  ") },
            { recordFixture(cycleNumber = 0) },
            { recordFixture(programDay = 0) },
            { recordFixture(actions = emptyList()) },
            { recordFixture(policyVersion = 0) }
        )

        for (build in invalidRecords) {
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    private fun recordFixture(
        familyId: String = "pushups",
        cycleNumber: Int = 1,
        programDay: Int = 1,
        actions: List<AdaptiveAction> = listOf(AdaptiveAction.MAINTAIN_STIMULUS),
        policyVersion: Int = 1
    ) = AdaptiveDecisionRecord(
        familyId = familyId,
        programRevision = 0,
        cycleNumber = cycleNumber,
        programDay = programDay,
        timestamp = 95L,
        previousState = AdaptiveState.HOLD,
        newState = AdaptiveState.HOLD,
        actions = actions,
        reasonCode = AdaptiveReasonCode.INSUFFICIENT_EVIDENCE,
        policyVersion = policyVersion
    )
}
