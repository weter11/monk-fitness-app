package com.monkfitness.app.di

import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.LegacyV7Schema
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.model.FamilyProgressionState as StoredFamilyState
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.data.repository.failureOf
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.program.AppState
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition root, driven the way the application drives it.
 *
 * `AppContainer` is only wiring, so nearly everything that could go wrong is a question about *what
 * it wired to what* — and every one of those questions has a measurable answer on a real database:
 *
 *  * the whole graph is constructed and nothing else is (§26: the seven repositories of §30 step 3,
 *    plus both adaptive generations), and the container exposes no DAO to anyone;
 *  * every repository in it reads and writes the one database the container was given — measured by a
 *    Program created through one repository being readable through another, by a foreign key that
 *    only resolves if both wrote to the same store, and by a second database of the same schema
 *    staying empty;
 *  * each DAO is taken from the database exactly once and shared, because "how many things in this
 *    graph read `program`" should be a number rather than a reading of the source;
 *  * an atomic operation of the graph really runs in one transaction: a planted failure part-way
 *    leaves nothing, decided by SQLite rather than by this test's bookkeeping;
 *  * the clock and the id generator the container was given are the ones the graph uses — a moved
 *    clock changes the next stamp, and every id in storage is one the test minted;
 *  * the target and the shipped adaptive generations are wired to their own tables on the same
 *    database, and neither can see the other's rows.
 *
 * Nothing here asserts behaviour of its own: no lifecycle, no scheduling, no generation, no policy
 * and no progress calculation, because §30 step 4 has none. Every row below is written and read by
 * the repository §30 step 3 landed, through the collaborators this stage wires.
 */
class AppContainerTest {

    private fun newRig(key: String = "container") = CompositionRootRig(key)

    /**
     * A second database with the same schema, which the container is **not** built over: the
     * counter-example that makes "one database" measurable rather than assumed.
     */
    private fun unattachedDatabase() = SqliteAppDatabase(
        SqliteTestDatabase.inMemory().also { database ->
            database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
            database.migrate(AppDatabase.MIGRATION_7_8)
            database.migrate(AppDatabase.MIGRATION_8_9)
        }
    )

    /** Every table the Program System writes. */
    private val graphTables = listOf(
        "program", "program_revision", "program_day", "program_exercise", "program_workout_slot",
        "workout_session", "session_snapshot", "session_snapshot_exercise", "session_exercise",
        "program_set_log", "app_state", "program_pause", "program_family_progression_state",
        "program_adaptive_decision_record", "adaptive_adjustment"
    )

    // ---- the graph the container constructs -------------------------------------------------------

    @Test
    fun everyRepositoryOfTheProgramSystemIsConstructedByTheContainer() {
        val rig = newRig()
        try {
            val constructed = AppContainer::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && it.parameterCount == 0 }
                .filter { it.name.startsWith("get") }
                .map { method ->
                    method.name.removePrefix("get").replaceFirstChar { it.lowercase() } to
                        method.returnType.simpleName
                }
                .filter { (_, type) -> type.endsWith("Repository") }
                .toMap()

            assertEquals(
                "the container constructs exactly the Program System's repositories and both adaptive " +
                    "generations: a missing one is a wiring gap, an extra one is a second owner",
                mapOf(
                    "programRepository" to "ProgramRepository",
                    "programPlanRepository" to "ProgramPlanRepository",
                    "programScheduleRepository" to "ProgramScheduleRepository",
                    "workoutSessionRepository" to "WorkoutSessionRepository",
                    "programProgressRepository" to "ProgramProgressRepository",
                    "appStateRepository" to "AppStateRepository",
                    "programAdaptiveRepository" to "ProgramAdaptiveRepository",
                    "adaptiveRepository" to "AdaptiveRepository"
                ),
                constructed
            )

            // Constructed means constructed: eight live objects over the database, none of them the
            // same object as another, and none of them a `lateinit` waiting for a consumer.
            val repositories = listOf(
                rig.container.programRepository, rig.container.programPlanRepository,
                rig.container.programScheduleRepository, rig.container.workoutSessionRepository,
                rig.container.programProgressRepository, rig.container.appStateRepository,
                rig.container.programAdaptiveRepository, rig.container.adaptiveRepository
            )
            repositories.forEach { assertNotNull("a repository the container declares is null", it) }
            assertEquals(
                "each repository is its own object: one instance of a class cannot be wired to two " +
                    "different sets of collaborators",
                8,
                repositories.map { System.identityHashCode(it) }.distinct().size
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun theContainerExposesRepositoriesAndTheSharedDatabaseAndNoDao() {
        val rig = newRig()
        try {
            val dataLayerTypes = AppContainer::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .map { it.returnType.name }
                .filter { it.startsWith("com.monkfitness.app.data.local.") }
                .toSet()

            assertEquals(
                "the container hands out the database and its repositories — never a DAO, because a " +
                    "DAO in a consumer's hand is §33's prohibition ('never let a ViewModel write a DAO " +
                    "directly') waiting to be broken",
                setOf("com.monkfitness.app.data.local.AppDatabase"),
                dataLayerTypes
            )

            val operations = AppContainer::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .filterNot { it.name.startsWith("get") }
            assertTrue(
                "the composition root composes and decides nothing: it declares no operation at all, " +
                    "found ${operations.map { it.name }}",
                operations.isEmpty()
            )
        } finally {
            rig.close()
        }
    }

    // ---- one database, shared --------------------------------------------------------------------

    @Test
    fun everyRepositoryOfTheGraphReadsAndWritesTheOneDatabaseTheContainerWasGiven() = runBlocking {
        val rig = newRig("shared")
        val other = unattachedDatabase()
        try {
            assertSame(
                "the container holds the database it was built over, not a copy of it",
                rig.database,
                rig.container.database
            )

            val graph = rig.graph
            val programId = graph.program.programId
            rig.container.programRepository.createProgram(graph.program, graph.revision, graph.slots)

            // Read back through a *different* repository: had the plan repository been wired to another
            // database, this is `null`.
            val revision: ProgramRevision? = rig.container.programPlanRepository.currentRevision(programId)
            assertNotNull(
                "the revision created through ProgramRepository is not readable through " +
                    "ProgramPlanRepository — the two are not wired to one database",
                revision
            )
            assertEquals(graph.revision.revisionId, revision!!.revisionId)
            assertEquals(
                "the plan read back is the plan that was created, day for day",
                graph.revision.days.map { it.programDayId },
                revision.days.map { it.programDayId }
            )

            val slots: List<WorkoutSlot> = rig.container.programScheduleRepository.slotsOfProgram(programId)
            assertEquals("every slot of the created Program is readable", graph.slots.size, slots.size)
            assertEquals(graph.slots.map { it.plannedFor }, slots.map { it.plannedFor })

            // A foreign key only resolves inside one database: `app_state.selectedProgramId` points at
            // the Program row, so this write fails outright unless both repositories see one store.
            rig.container.appStateRepository.save(AppState(selectedProgramId = programId))
            val state = rig.container.appStateRepository.state()
            assertNotNull("the global state row was not readable after being written", state)
            assertEquals(
                "the state row the app-state repository wrote points at the Program another repository " +
                    "created — the foreign key is the engine's proof that both share one database",
                programId,
                state!!.selectedProgramId
            )

            // The session path: start, confirm a set, finish. Three repositories, one store.
            val session: WorkoutSession = ProgramGraphFixture.session("shared", graph.slotFor(1))
            rig.container.workoutSessionRepository.startSession(session)
            rig.container.workoutSessionRepository.appendSet(
                SessionExerciseId("session-ex-shared-1"),
                SetResult(
                    SetLogId("set-shared-extra"),
                    3,
                    completedReps = 6,
                    durationSeconds = 0,
                    performedAt = ProgramGraphFixture.SET_THREE
                )
            )
            rig.container.workoutSessionRepository.finishSession(
                session.copy(status = SessionStatus.COMPLETED, finishedAt = ProgramGraphFixture.FINISHED),
                graph.slotFor(1).copy(
                    status = SlotStatus.COMPLETED,
                    attempts = listOf(SessionId("session-shared")),
                    completedAt = ProgramGraphFixture.FINISHED
                )
            )

            assertEquals(
                "the session started through one repository is read back by another",
                listOf(SessionId("session-shared")),
                rig.container.programProgressRepository.sessionIdsOf(programId)
            )
            assertEquals(
                "a session start writes no set of its own; the one confirmed set is the one counted",
                1,
                rig.container.programProgressRepository.confirmedSetCount(programId)
            )
            assertEquals(
                "the slot outcome written by the session path is what Progress counts",
                mapOf(SlotStatus.PLANNED to 2, SlotStatus.COMPLETED to 1),
                rig.container.programProgressRepository.slotStatusCounts(programId).filterValues { it > 0 }
            )

            // The counter-example: the same schema, never handed to the container, is untouched.
            graphTables.forEach { table ->
                assertEquals(
                    "a repository of the graph wrote to a database the container does not hold: " +
                        "$table has rows in the unattached database",
                    0,
                    other.rowCount(table)
                )
            }
            assertTrue(
                "and the database the container does hold really did receive the graph",
                graphTables.take(5).all { rig.rowCount(it) > 0 }
            )
        } finally {
            other.close()
            rig.close()
        }
    }

    @Test
    fun eachDaoIsTakenFromTheDatabaseOnceAndShared() {
        val rig = newRig()
        try {
            val expected = listOf(
                "programDao", "appStateDao", "programRevisionDao", "programDayDao", "programExerciseDao",
                "programWorkoutSlotDao", "workoutSessionDao", "sessionSnapshotDao",
                "sessionSnapshotExerciseDao", "sessionExerciseDao", "programSetLogDao", "programPauseDao",
                "programFamilyProgressionStateDao", "programAdaptiveDecisionDao", "adaptiveAdjustmentDao",
                "familyProgressionStateDao", "adaptiveDecisionHistoryDao"
            )

            assertEquals(
                "the composition root takes exactly one DAO per table the Program System uses, and " +
                    "nothing else from the database",
                expected.sorted(),
                rig.database.accessorCalls.keys.sorted()
            )
            assertEquals(
                "and it takes each of them once: a DAO asked for twice means two places in the graph " +
                    "hold the same table's access, which is how a second reader appears unnoticed",
                expected.associateWith { 1 },
                rig.database.accessorCalls.toMap()
            )
            assertTrue(
                "the Program System reads no shipped progress table (§23: no new architecture " +
                    "dependency on `UserProgress`)",
                "progressDao" !in rig.database.accessorCalls
            )
        } finally {
            rig.close()
        }
    }

    // ---- the transaction is the database's -------------------------------------------------------

    @Test
    fun anAtomicOperationThroughTheContainerRollsBackAsAWhole() = runBlocking {
        val rig = newRig("rollback")
        try {
            rig.database.faults.failExerciseInsert = true

            val failure = failureOf {
                rig.container.programRepository.createProgram(
                    rig.graph.program, rig.graph.revision, rig.graph.slots
                )
            }
            assertTrue(
                "the planted failure propagates instead of being swallowed (§28: nothing converts a " +
                    "database failure into an empty result), got: $failure",
                failure.message!!.contains("planted fault")
            )

            listOf(
                "program", "program_revision", "program_day", "program_exercise", "program_workout_slot"
            ).forEach { table ->
                assertEquals(
                    "the failed creation left a row in $table: the transaction the container wired is " +
                        "not one unit of work, or it is not the database's own",
                    0,
                    rig.rowCount(table)
                )
            }

            // The same container and the same call with the fault cleared: the repository holds no
            // half-state of its own, so the graph is written exactly as if the failure never happened.
            rig.database.faults.failExerciseInsert = false
            rig.container.programRepository.createProgram(
                rig.graph.program, rig.graph.revision, rig.graph.slots
            )
            assertEquals(1, rig.rowCount("program"))
            assertEquals(5, rig.rowCount("program_exercise"))
            assertEquals(3, rig.rowCount("program_workout_slot"))
        } finally {
            rig.close()
        }
    }

    // ---- the injected collaborators --------------------------------------------------------------

    @Test
    fun theInjectedClockIsWhatTheAdaptiveRepositoryStampsItsRowsWith() = runBlocking {
        val rig = newRig("clock")
        try {
            assertSame(
                "the container exposes the clock it was given, not one it minted of its own",
                rig.clock,
                rig.container.clock
            )
            val revisionId = RevisionId(ProgramGraphFixture.revisionId("clock"))
            rig.container.programRepository.createProgram(
                rig.graph.program, rig.graph.revision, rig.graph.slots
            )

            val first = rig.container.programAdaptiveRepository.saveFamilyState(
                FamilyProgressionState(
                    revisionId = revisionId,
                    familyId = "push-family",
                    progressionLevel = 0,
                    adaptationState = AdaptiveState.HOLD,
                    updatedAt = ProgramGraphFixture.STARTED
                )
            )
            assertEquals(
                "the returned state is stamped with the clock the container was given, not with the " +
                    "value the caller handed in",
                ProgramGraphFixture.CREATED,
                first.updatedAt
            )
            assertEquals(
                "and that stamp is what reached the column",
                ProgramGraphFixture.CREATED.toEpochMilli(),
                rig.engine.scalar(
                    "SELECT updatedAt FROM `program_family_progression_state` WHERE familyId = 'push-family'"
                )!!.toLong()
            )

            // A clock that moves is read when it is asked, not captured once when the graph was built:
            // a later write through the same repository is stamped with the later instant.
            rig.clock.instant = ProgramGraphFixture.UPDATED
            val second = rig.container.programAdaptiveRepository.saveFamilyState(
                FamilyProgressionState(
                    revisionId = revisionId,
                    familyId = "pull-family",
                    progressionLevel = 1,
                    adaptationState = AdaptiveState.PROGRESS,
                    updatedAt = ProgramGraphFixture.STARTED
                )
            )
            assertEquals(ProgramGraphFixture.UPDATED, second.updatedAt)
            assertEquals(
                "each write takes the clock's value at the moment of the write",
                ProgramGraphFixture.UPDATED.toEpochMilli(),
                rig.engine.scalar(
                    "SELECT updatedAt FROM `program_family_progression_state` WHERE familyId = 'pull-family'"
                )!!.toLong()
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun theInjectedIdGeneratorIsTheOnlyIdentityTheGraphMintsThrough() = runBlocking {
        val rig = newRig("ids")
        try {
            assertSame(
                "the container exposes the generator it was given, not one it minted of its own",
                rig.ids,
                rig.container.idGenerator
            )

            // A whole graph whose identity comes from the generator, because that is what the creation
            // path of §30 step 5 will do: it wraps the body in the typed id it is creating.
            val programId = ProgramId(rig.container.idGenerator.newId())
            val revisionId = RevisionId(rig.container.idGenerator.newId())
            val program = ProgramGraphFixture.program("ids").copy(
                programId = programId,
                currentRevisionId = revisionId
            )
            val revision = ProgramGraphFixture.revision("ids").copy(
                revisionId = revisionId,
                programId = programId
            )
            val slots = ProgramGraphFixture.slots("ids").map {
                it.copy(programId = programId, revisionId = revisionId)
            }

            rig.container.programRepository.createProgram(program, revision, slots)

            assertEquals(
                "the ids a caller minted through the container are the ids in storage — a deterministic " +
                    "sequence, not a random value the test cannot name",
                listOf("container-id-001", "container-id-002"),
                listOf(
                    rig.engine.scalar("SELECT programId FROM `program`"),
                    rig.engine.scalar("SELECT revisionId FROM `program_revision`")
                )
            )
            assertEquals(
                "and minting is exactly what the caller asked for: nothing inside the graph minted an " +
                    "id for itself (`ProgramRepository` still takes every id it writes)",
                2,
                rig.ids.count
            )

            val stored = rig.container.programRepository.programById(programId)
            assertNotNull("the Program named by the generator is not readable", stored)
            assertEquals(programId, stored!!.programId)
        } finally {
            rig.close()
        }
    }

    // ---- the two persistence generations ---------------------------------------------------------

    @Test
    fun theTwoAdaptiveGenerationsAreWiredToTheirOwnTables() = runBlocking {
        val rig = newRig("generations")
        try {
            val revisionId = RevisionId(ProgramGraphFixture.revisionId("generations"))
            rig.container.programRepository.createProgram(
                rig.graph.program, rig.graph.revision, rig.graph.slots
            )

            // The shipped generation, written through the container: its tables, and the stamp the
            // caller chose rather than any clock the container holds.
            rig.container.adaptiveRepository.saveFamilyState(
                StoredFamilyState(
                    familyId = "push-family",
                    progressionLevel = 1,
                    currentExerciseId = "pushup",
                    adaptationState = AdaptiveState.PROGRESS,
                    eligibleSessionsSinceLastProgressionChange = 4,
                    programRevision = 0,
                    updatedAt = LEGACY_STAMP
                )
            )
            rig.container.adaptiveRepository.appendDecision(
                rig.container.adaptiveRepository.decisionRecord(
                    decision = LEGACY_DECISION,
                    programRevision = 0,
                    cycleNumber = 1,
                    programDay = 3,
                    timestamp = LEGACY_STAMP
                )
            )

            assertEquals(1, rig.rowCount("family_progression_state"))
            assertEquals(1, rig.rowCount("adaptive_decision_record"))
            assertEquals(
                "a write through the shipped adapter reached no target table",
                0,
                rig.rowCount("program_family_progression_state") +
                    rig.rowCount("program_adaptive_decision_record")
            )
            assertEquals(
                "the shipped adapter stamps nothing — the stored stamp is the caller's, not the " +
                    "container's clock (which reads ${ProgramGraphFixture.CREATED} here)",
                LEGACY_STAMP,
                rig.engine.scalar("SELECT updatedAt FROM `family_progression_state`")!!.toLong()
            )

            val shippedRows =
                rig.rows("family_progression_state") + rig.rows("adaptive_decision_record")

            // The target generation: the same shape of write, on its own tables of the same database.
            rig.container.programAdaptiveRepository.saveFamilyState(
                FamilyProgressionState(
                    revisionId = revisionId,
                    familyId = "push-family",
                    progressionLevel = 2,
                    adaptationState = AdaptiveState.PROGRESS,
                    updatedAt = ProgramGraphFixture.STARTED
                )
            )
            rig.container.programAdaptiveRepository.persistDecision(
                AdaptiveDecision(
                    decisionId = DecisionId("decision-generations"),
                    programId = rig.graph.program.programId,
                    revisionId = revisionId,
                    slotId = rig.graph.slotFor(1).slotId,
                    target = AdaptiveTarget.Family("push-family"),
                    action = AdaptiveAction.PROGRESS,
                    outcome = DecisionOutcome.NOT_APPLIED,
                    evidence = EvidenceLevel.STRONG,
                    confidence = ConfidenceLevel.HIGH,
                    recovery = RecoveryContext.FAVORABLE,
                    decidedAt = ProgramGraphFixture.CREATED
                )
            )

            assertEquals(1, rig.rowCount("program_family_progression_state"))
            assertEquals(1, rig.rowCount("program_adaptive_decision_record"))
            assertEquals(
                "a target write changes nothing in the shipped tables: the two generations share a " +
                    "database and no rows",
                shippedRows,
                rig.rows("family_progression_state") + rig.rows("adaptive_decision_record")
            )
            assertEquals(
                "the target generation stamps with the container's clock while the shipped one keeps " +
                    "the caller's value — the two are separate in both directions",
                ProgramGraphFixture.CREATED.toEpochMilli(),
                rig.engine.scalar("SELECT updatedAt FROM `program_family_progression_state`")!!.toLong()
            )
        } finally {
            rig.close()
        }
    }

    private companion object {

        /** A stamp no clock in the container produces, so "who stamped this row" is decidable. */
        const val LEGACY_STAMP = 1_700_000_000_000L

        /**
         * The shipped Stage-1 adaptive decision, in the pilot's own vocabulary
         * (`domain.adaptive`, not `domain.adaptive.decision`): the two names coexist until §30 step
         * 15, and a test that used the target type here would be testing the wrong generation.
         */
        val LEGACY_DECISION = com.monkfitness.app.domain.adaptive.AdaptiveDecision(
            state = AdaptiveState.PROGRESS,
            previousState = AdaptiveState.HOLD,
            actions = listOf(com.monkfitness.app.domain.adaptive.AdaptiveAction.INCREASE_STIMULUS),
            reasonCode = AdaptiveReasonCode.SUSTAINED_POSITIVE_PERFORMANCE,
            policyVersion = 1,
            familyId = "push-family"
        )
    }
}
