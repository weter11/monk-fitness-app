package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.PauseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.ProgramPause
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * §30 step 21 on a real SQLite engine: the **first controlled production invocation** of the target
 * scheduling contour, driven the way the application drives it.
 *
 * ```text
 * a NOT_STARTED Program with a persisted explicit target source
 *         ↓  ProgramStartService.start
 * ProgramLifecycleService.startProgram        → the factual actualStartDate
 *         ↓  the five stated values
 * TargetScheduleProductionConsumer.run        → target occurrence + target slot, on disk
 * ```
 *
 * Every collaborator is the production one, composed as `AppContainer` composes it; only the clock,
 * the identity generator and the calendar are values a test must state, because a test cannot ask
 * the device for them. Nothing is mocked, so "the target pass wrote a row" and "the legacy slots are
 * byte-identical" are statements about storage.
 *
 * ```text
 *  1. a start creates the target occurrence and the target slot
 *  2. the legacy slots the application trains from are byte-identical afterwards
 *  3. a second controlled invocation creates nothing the first one created
 *  4. the five values are the real production values, each one reaching the stage that owns it
 *  5. a derived rule with no stated source is the resolver's own refusal
 *  6. a Program with no target authoring is a typed absence and writes no target row
 *  7. a Program the lifecycle refuses to start never reaches the target contour
 *  8. two Programs with identical rule identities do not consume each other's target state
 *  9. a completed occurrence stays completed across another controlled invocation
 * ```
 */
class TargetScheduleControlledInvocationIntegrationTest {

    private val rig = ProgramDataAccessRig(KEY)
    private val clock = MovableClock(STARTED_AT)
    private val ids = SequentialIds("s21")
    private var minted = 0

    @After
    fun close() {
        rig.close()
    }

    // ---- 1. the controlled invocation creates target persistence ------------------------------------

    @Test
    fun aStartCreatesTheTargetOccurrenceAndTheTargetSlot() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))

        val started = start(ProgramId(programId)).started()

        assertEquals(
            "the Program really started, and the as-of date is the moment it started",
            STARTED_DATE,
            started.targetScheduling.input.asOf
        )
        val created = started.targetScheduling.result.applicationResult.persistenceResult.created
        assertEquals(
            "the stated thirty-day window produced one occurrence per date, starting on the day " +
                "the Program started",
            (0L until 30L).map { STARTED_DATE.plusDays(it) },
            created.map { it.plannedFor }
        )
        assertEquals(
            "30 target occurrences and 30 presenting target slots are on disk",
            30 to 30,
            rig.targetRowCounts()
        )
        assertEquals(
            "and the Program itself is running with the factual start date the pass was planned against",
            LifecycleStatus.RUNNING,
            rig.programRepository.programById(ProgramId(programId))!!.lifecycleStatus
        )
    }

    // ---- 2. legacy isolation -----------------------------------------------------------------------

    @Test
    fun theLegacySlotsTheApplicationTrainsFromAreByteIdenticalAfterTheStart() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))
        val before = rig.programScheduleRepository.slotsOfProgram(ProgramId(programId))

        start(ProgramId(programId))

        val after = rig.programScheduleRepository.slotsOfProgram(ProgramId(programId))
            .filter { it.targetOccurrenceKey == null }
        assertEquals(
            "the legacy planner's own slots are untouched, in identity, status and count",
            before.map { it.slotId to it.status },
            after.map { it.slotId to it.status }
        )
        assertEquals(
            "and the target pass added its opportunities beside them rather than rewriting them",
            3,
            after.size
        )
    }

    // ---- 3. idempotence ---------------------------------------------------------------------------

    @Test
    fun aSecondControlledInvocationCreatesNoDuplicateOccurrenceOrSlot() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))

        val first = start(ProgramId(programId)).started()
        val rowsAfterFirst = rig.targetRowCounts()
        // A second start on a running Program is `AlreadyThere` (§3), which is a success: the
        // operation runs the same controlled invocation again over the same factual start date.
        val second = start(ProgramId(programId)).started()

        assertEquals(
            "the second invocation reached the same window",
            first.targetScheduling.input.window,
            second.targetScheduling.input.window
        )
        assertEquals(
            "and created nothing, because the stored occurrences satisfied every presentation",
            emptyList<WorkoutSlot>(),
            second.targetScheduling.result.applicationResult.persistenceResult.created
        )
        assertEquals("the target tables are unchanged", rowsAfterFirst, rig.targetRowCounts())
    }

    // ---- 4. the five stated values -----------------------------------------------------------------

    @Test
    fun theProductionContextIsTheFiveStatedValuesAndEachReachesTheStageThatOwnsIt() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))
        // One *closed* pause inside the window, so the adapter has real persisted intervals to read.
        rig.programScheduleRepository.addPause(
            ProgramPause(
                pauseId = PauseId("pause-s21-1"),
                programId = ProgramId(programId),
                startedAt = STARTED_AT.plus(Duration.ofDays(2)),
                endedAt = STARTED_AT.plus(Duration.ofDays(4))
            )
        )

        val input = start(ProgramId(programId)).started().targetScheduling.input

        assertEquals(
            "asOf is the started Program's own factual start date, read in the stated calendar",
            STARTED_DATE,
            input.asOf
        )
        assertEquals(
            "the window is target-owned and bounded: asOf .. asOf + 29, exactly thirty dates",
            com.monkfitness.app.domain.program.target.TargetScheduleWindow(
                STARTED_DATE,
                STARTED_DATE.plusDays(29)
            ),
            input.window
        )
        assertEquals("30 dates, counted", 30, input.window.dates().size)
        assertEquals(
            "the composition selection is empty, so no rule is combined with another",
            com.monkfitness.app.domain.program.CompositionSelection(),
            input.selection
        )
        assertEquals("no resolved source is stated", emptyMap<String, Any>(), input.sources)
        assertEquals(
            "the persisted closed pause became a date window in the stated calendar",
            listOf(
                ProgramPauseWindow(
                    STARTED_DATE.plusDays(2),
                    STARTED_DATE.plusDays(4)
                )
            ),
            input.pauses
        )
        val stored = rig.targetScheduleOccurrenceRepository.occurrencesOfProgram(ProgramId(programId))
        val covered = input.window.dates().filter { date -> input.pauses.any { it.covers(date) } }
        assertEquals(
            "the adapter really did cover three dates, so the case is not vacuous",
            3,
            covered.size
        )
        assertTrue(
            "and the pause reached the temporal stage: none of those dates was created, " +
                "found ${stored.map { it.occurrence.plannedFor }}",
            stored.none { storedOccurrence -> input.pauses.any { it.covers(storedOccurrence.occurrence.plannedFor) } }
        )
    }

    @Test
    fun thePauseCalendarIsTheStatedOneAndTheSameStoredInstantReadsAsAnotherDate() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))
        // 22:30 UTC on the second day: still the second day in UTC, already the third in Tokyo.
        val instant = Instant.parse("2026-10-06T22:30:00Z")
        rig.programScheduleRepository.addPause(
            ProgramPause(
                pauseId = PauseId("pause-s21-zone"),
                programId = ProgramId(programId),
                startedAt = instant,
                endedAt = instant
            )
        )

        val utcInput = startIn(ZoneId.of("UTC"), ProgramId(programId)).started().targetScheduling.input
        val tokyoInput = startIn(ZoneId.of("Asia/Tokyo"), ProgramId(programId))
            .started().targetScheduling.input

        assertEquals(
            "in UTC the interval covers the second day",
            listOf(ProgramPauseWindow(STARTED_DATE.plusDays(1), STARTED_DATE.plusDays(1))),
            utcInput.pauses
        )
        assertEquals(
            "the very same stored instants, read in Tokyo, cover the third day",
            listOf(ProgramPauseWindow(STARTED_DATE.plusDays(2), STARTED_DATE.plusDays(2))),
            tokyoInput.pauses
        )
    }

    @Test
    fun anOpenPauseIntervalIsRefusedRatherThanGivenAnInventedEnd() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))
        // A NOT_STARTED Program cannot have an open pause: PAUSE is legal only from RUNNING, and
        // START enters RUNNING only from NOT_STARTED. Planting one is how the adapter's refusal is
        // reached from real data rather than argued from the shape of the guard.
        rig.programScheduleRepository.addPause(
            ProgramPause(
                pauseId = PauseId("pause-s21-open"),
                programId = ProgramId(programId),
                startedAt = STARTED_AT
            )
        )

        val failure = try {
            start(ProgramId(programId))
            null
        } catch (thrown: Throwable) {
            thrown
        }

        assertTrue(
            "an open interval has no honest date window, and inventing one would suppress " +
                "occurrences the Program was never paused for: $failure",
            failure is IllegalArgumentException && failure.message!!.contains("no last date")
        )
        assertEquals("and nothing was written", 0 to 0, rig.targetRowCounts())
    }

    // ---- 5. the derived refusal --------------------------------------------------------------------

    @Test
    fun aDerivedRuleWithNoStatedSourceIsTheResolversOwnRefusalAndWritesNothing() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(
            source(
                revisionId,
                TargetScheduleDefinition(
                    ruleId = "rule-extra",
                    workoutId = "workout-extra",
                    cadence = ScheduleCadence.DerivedExcluding("rule-strength"),
                    anchorDate = STARTED_DATE
                )
            )
        )

        val failure = try {
            start(ProgramId(programId))
            null
        } catch (thrown: Throwable) {
            thrown
        }

        assertTrue(
            "the resolver refuses a derived rule whose resolved source the caller did not state, " +
                "and that refusal is not answered with a fabricated one: $failure",
            failure is IllegalArgumentException && failure.message!!.contains("requires its source")
        )
        assertEquals("no target row was fabricated", 0 to 0, rig.targetRowCounts())
        assertEquals(
            "and the Program did start — the refusal is the target's, not a lifecycle one",
            LifecycleStatus.RUNNING,
            rig.programRepository.programById(ProgramId(programId))!!.lifecycleStatus
        )
    }

    // ---- 6. the typed absence ----------------------------------------------------------------------

    @Test
    fun aProgramWithNoTargetAuthoringIsATypedAbsenceAndWritesNoTargetRow() = runBlocking {
        createNotStartedGraph()
        assertEquals(
            "the fixture's revision really does carry a legacy schedule and no target semantics",
            "FIXED_WEEKDAYS",
            rig.database.rows(
                "SELECT `scheduleType` FROM `program_revision` WHERE `revisionId` = '${revisionId.value}'"
            ).single()["scheduleType"]
        )

        val outcome = start(ProgramId(programId))

        assertTrue(
            "a revision that states nothing about target semantics is a typed absence, and the " +
                "Program still started: $outcome",
            outcome is ProgramStartResult.TargetSchedulingRefused &&
                outcome.targetScheduling == TargetScheduleRunResult.SourceMissing(revisionId)
        )
        assertEquals("no target occurrence was written", 0 to 0, rig.targetRowCounts())
        assertEquals(
            "and the legacy slots are still exactly the three the legacy path created",
            3,
            rig.programScheduleRepository.slotsOfProgram(ProgramId(programId)).size
        )
    }

    // ---- 7. a refused start ------------------------------------------------------------------------

    @Test
    fun aProgramTheLifecycleRefusesToStartNeverReachesTheTargetContour() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))
        // Started once, then completed: §3's terminal Program cannot be started again.
        start(ProgramId(programId))
        lifecycleService.completeProgram(ProgramId(programId))

        val rowsBefore = rig.targetRowCounts()
        val outcome = start(ProgramId(programId))

        assertTrue(
            "the lifecycle refusal is carried, in §28's own vocabulary: $outcome",
            outcome is ProgramStartResult.StartRefused
        )
        val refused = outcome as ProgramStartResult.StartRefused
        assertTrue(
            "and it is the lifecycle's own rule, in §28's vocabulary, not a target one: ${refused.reason}",
            refused.reason.message.contains("§3")
        )
        assertEquals(
            "the target pass was never invoked, so target persistence is unchanged",
            rowsBefore,
            rig.targetRowCounts()
        )
    }

    // ---- 8. cross-Program isolation -----------------------------------------------------------------

    @Test
    fun twoProgramsWithIdenticalRuleIdentitiesDoNotConsumeEachOthersTargetState() = runBlocking {
        val otherKey = "stage21-other"
        val otherProgramId = ProgramId(ProgramGraphFixture.programId(otherKey))
        val otherRevisionId = RevisionId(ProgramGraphFixture.revisionId(otherKey))
        val otherGraph = ProgramGraphFixture.graph(otherKey)
        createNotStartedGraph()
        rig.programRepository.createProgram(otherGraph.program, otherGraph.revision, otherGraph.slots)
        // Byte-identical authoring, rule identity and workout identity included.
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))
        rig.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = otherRevisionId,
                rules = listOf(strengthRule(STARTED_DATE)),
                programDayBindings = listOf(
                    TargetProgramDayBinding(
                        "workout-strength",
                        ProgramDayId(ProgramGraphFixture.dayId(otherKey, 1))
                    )
                )
            )
        )

        start(ProgramId(programId))

        assertEquals(
            "the other Program's target occurrence table is still empty",
            0,
            rig.targetScheduleOccurrenceRepository.occurrencesOfProgram(otherProgramId).size
        )
        assertEquals(
            "and it still has exactly the three legacy slots its own creation wrote",
            3,
            rig.programScheduleRepository.slotsOfProgram(otherProgramId).size
        )
        assertEquals(
            "while the Program that ran holds its own thirty",
            30,
            rig.targetScheduleOccurrenceRepository.occurrencesOfProgram(ProgramId(programId)).size
        )
    }

    // ---- 9. an existing completed occurrence --------------------------------------------------------

    @Test
    fun aCompletedTargetOccurrenceStaysCompletedAcrossAnotherControlledInvocation() = runBlocking {
        createNotStartedGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule(STARTED_DATE)))
        val first = start(ProgramId(programId)).started()
        val firstSlot = first.targetScheduling.result.applicationResult.persistenceResult.created.first()
        completeSessionOn(firstSlot)

        val second = start(ProgramId(programId)).started()

        assertEquals(
            "the completed occurrence was handed to the pass with the execution the stored history " +
                "states — the reader's policy verdict, not a slot status read here",
            OccurrenceExecution.COMPLETED,
            second.targetScheduling.input.existingOccurrences.first().execution
        )
        assertEquals(
            "and it is preserved rather than re-created",
            emptyList<WorkoutSlot>(),
            second.targetScheduling.result.applicationResult.persistenceResult.created
        )
        assertEquals(
            "and the stored history still classifies it as COMPLETED, read back through the " +
                "execution reader rather than from a slot status",
            OccurrenceExecution.COMPLETED,
            TargetExistingOccurrenceReader(executionReader())
                .existingOccurrencesOf(
                    ProgramId(programId),
                    rig.targetScheduleOccurrenceRepository
                        .occurrencesOfProgram(ProgramId(programId))
                        .map { it.occurrence }
                )
                .first { it.occurrence.plannedFor == STARTED_DATE }
                .execution
        )
        assertEquals("and no duplicate was written", 30 to 30, rig.targetRowCounts())
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private suspend fun start(programId: ProgramId) = service(ZONE).start(programId)

    private suspend fun startIn(zone: ZoneId, programId: ProgramId) = service(zone).start(programId)

    /** §3's lifecycle owner, wired as the composition root wires it over the rig's real engine. */
    private val lifecycleService = ProgramLifecycleService(
        programRepository = rig.programRepository,
        scheduleRepository = rig.programScheduleRepository,
        sessionRepository = rig.workoutSessionRepository,
        appStateRepository = rig.appStateRepository,
        planRepository = rig.programPlanRepository,
        clock = clock,
        idGenerator = ids,
        standardProgramId = StandardProgram.programId,
        inTransaction = rig.transaction
    )

    private fun service(zone: ZoneId) = ProgramStartService(
        lifecycle = lifecycleService,
        consumer = consumer(),
        scheduleRepository = rig.programScheduleRepository,
        zone = zone
    )

    private fun executionReader() = TargetOccurrenceExecutionReader(
        occurrenceRepository = rig.targetScheduleOccurrenceRepository,
        scheduleRepository = rig.programScheduleRepository,
        sessionRepository = rig.workoutSessionRepository
    )

    /**
     * The consumer, composed exactly as `AppContainer` composes it: the real repositories, the real
     * bridge, the real execution reader and its policy, the real input adapter and the real
     * orchestrator over the real application service and the real slot persister. Only the identity
     * generator is a sequence, because a test cannot ask the device for ids.
     */
    private fun consumer() = TargetScheduleProductionConsumer(
        programRepository = rig.programRepository,
        planRepository = rig.programPlanRepository,
        sourceBridge = TargetScheduleSourceBridge(rig.targetScheduleSourceRepository),
        occurrenceRepository = rig.targetScheduleOccurrenceRepository,
        existingOccurrenceReader = TargetExistingOccurrenceReader(
            TargetOccurrenceExecutionReader(
                occurrenceRepository = rig.targetScheduleOccurrenceRepository,
                scheduleRepository = rig.programScheduleRepository,
                sessionRepository = rig.workoutSessionRepository
            )
        ),
        inputAdapter = TargetScheduleInputAdapter(),
        orchestrator = TargetScheduleOrchestrator(
            TargetScheduleApplicationService(
                TargetScheduleSlotPersister(
                    scheduleRepository = rig.programScheduleRepository,
                    occurrenceRepository = rig.targetScheduleOccurrenceRepository,
                    idGenerator = IdGenerator { "s21-slot-${minted++}" },
                    inTransaction = rig.transaction
                )
            )
        )
    )

    private fun strengthRule(anchor: LocalDate) = TargetScheduleDefinition(
        ruleId = "rule-strength",
        workoutId = "workout-strength",
        cadence = ScheduleCadence.Daily,
        anchorDate = anchor
    )

    private fun source(revision: RevisionId, rule: TargetScheduleDefinition) = TargetScheduleSource(
        revisionId = revision,
        rules = listOf(rule),
        programDayBindings = listOf(TargetProgramDayBinding(rule.workoutId, dayId(1)))
    )

    private fun dayId(position: Int) = ProgramDayId(ProgramGraphFixture.dayId(KEY, position))

    /**
     * The fixture's whole graph with a **NOT_STARTED** Program.
     *
     * The shared fixture creates a Program that is already `RUNNING`, which would make every case
     * here an `AlreadyThere` no-op rather than the start this stage is about. Clearing the stamp
     * with the status is the only difference, and it is the difference the transition needs.
     */
    private suspend fun createNotStartedGraph() {
        val graph = ProgramGraphFixture.graph(KEY)
        rig.programRepository.createProgram(
            graph.program.copy(
                lifecycleStatus = LifecycleStatus.NOT_STARTED,
                actualStartDate = null
            ),
            graph.revision,
            graph.slots
        )
    }

    private suspend fun completeSessionOn(slot: WorkoutSlot) {
        val started = sessionOn(slot)
        rig.startSessionWithSets(started)
        val finished = started.copy(
            status = SessionStatus.COMPLETED,
            finishedAt = STARTED_AT.plus(FORTY_MINUTES)
        )
        rig.workoutSessionRepository.finishSession(
            finished,
            slot.copy(
                status = SlotStatus.COMPLETED,
                attempts = listOf(finished.sessionId),
                completedAt = finished.finishedAt
            )
        )
    }

    private fun sessionOn(slot: WorkoutSlot): WorkoutSession {
        val first = ProgramExerciseId(ProgramGraphFixture.planExerciseId(KEY, 1))
        val second = ProgramExerciseId(ProgramGraphFixture.planExerciseId(KEY, 2))
        val sessionId = SessionId("session-s21-${slot.slotId.value}")
        return WorkoutSession(
            sessionId = sessionId,
            slotId = slot.slotId,
            programId = slot.programId,
            revisionId = slot.revisionId,
            snapshot = WorkoutSessionSnapshot(
                sessionId = sessionId,
                capturedAt = STARTED_AT,
                workout = EffectiveWorkout(
                    slotId = slot.slotId,
                    programId = slot.programId,
                    revisionId = slot.revisionId,
                    plannedFor = slot.plannedFor,
                    computedAt = ProgramGraphFixture.COMPUTED,
                    exercises = listOf(
                        EffectiveExercise(first, "knee_pushup", RepPrescription(listOf(10, 8))),
                        EffectiveExercise(second, "pike_pushup", RepPrescription(listOf(8, 8)))
                    ),
                    appliedAdjustmentIds = emptyList()
                )
            ),
            status = SessionStatus.IN_PROGRESS,
            startedAt = STARTED_AT,
            exercises = listOf(
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("se-s21-1"),
                    programExerciseId = first,
                    exerciseId = "knee_pushup",
                    prescription = RepPrescription(listOf(10, 8)),
                    results = listOf(
                        SetResult(
                            SetLogId("set-s21-1"),
                            1,
                            completedReps = 10,
                            durationSeconds = 0,
                            performedAt = STARTED_AT.plus(TEN_MINUTES)
                        )
                    )
                ),
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("se-s21-2"),
                    programExerciseId = second,
                    exerciseId = "pike_pushup",
                    prescription = RepPrescription(listOf(8, 8)),
                    skipped = true
                )
            )
        )
    }

    /** Narrows the composed outcome to its success case, so a refusal prints itself. */
    private fun ProgramStartResult.started(): ProgramStartResult.Started =
        this as? ProgramStartResult.Started
            ?: throw AssertionError("expected a started Program, got $this")

    private companion object {
        const val KEY = "stage21-start"
        val programId: String = ProgramGraphFixture.programId(KEY)
        val revisionId: RevisionId = RevisionId(ProgramGraphFixture.revisionId(KEY))
        val STARTED_AT: Instant = Instant.parse("2026-10-05T08:00:00Z")
        val STARTED_DATE: LocalDate = LocalDate.parse("2026-10-05")
        val ZONE: ZoneId = ZoneId.of("UTC")
        val TEN_MINUTES: Duration = Duration.ofMinutes(10)
        val FORTY_MINUTES: Duration = Duration.ofMinutes(40)
    }
}
