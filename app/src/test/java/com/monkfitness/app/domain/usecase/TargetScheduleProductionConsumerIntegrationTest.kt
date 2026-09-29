package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.target.ResolvedScheduleOccurrence
import com.monkfitness.app.domain.program.target.ResolvedScheduleSource
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
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

/**
 * §30 step 20 on a real SQLite engine: the first production consumer of the target contour, driven
 * the way production will drive it, with every collaborator the composition root hands it.
 *
 * ```text
 * a Program saved with an explicit target source
 *         ↓  TargetScheduleProductionConsumer.run(programId, context)
 * current revision → explicit source → stored occurrences → input → adapter → orchestrator
 *         ↓
 * a target occurrence and a target slot, on disk
 * ```
 *
 * Nothing here is mocked. The Program, its revision, its plan, the authored target source, the target
 * occurrence, the target slot, the workout session and its confirmed sets are all written through the
 * production write paths and read back through the production read paths, so a claim such as "the
 * stored source is what the pass planned from" is a statement about rows rather than about a
 * hand-built value handed to a stub.
 *
 * The claims, in the order the stage states them:
 *
 * ```text
 *  1. a complete run: source read → input assembled → adapter → orchestrator → occurrence + slot
 *  2. exact source fidelity: ruleId / workoutId / cadence / anchorDate / binding all crossed
 *  3. existing occurrences arrive through the execution bridge, and its policy stays authoritative
 *  4. a repeated identical request creates nothing the second time
 *  5. a revision with no target source is a typed absence, and writes no target row
 *  6. a malformed stored source is reported, not defaulted
 *  7. the five caller-owned values are forwarded unchanged, and each one visibly reaches the stage
 *     that owns it
 *  8. the run belongs to the *current* revision, and an earlier revision's source is untouched
 *  9. two Programs may hold identical rule identities without reading or writing each other
 * 10. the legacy planning path is untouched and uncalled by the consumer
 * ```
 */
class TargetScheduleProductionConsumerIntegrationTest {

    private val rig = ProgramDataAccessRig(KEY)
    private var minted = 0

    @After
    fun close() {
        rig.close()
    }

    // ---- 1. a complete target run from persisted authoring ----------------------------------------

    @Test
    fun aPersistedAuthoringBecomesAStoredTargetOccurrenceAndTargetSlot() = runBlocking {
        rig.createGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule()))

        val scheduled = run(ProgramId(programId)).scheduled()
        val result = scheduled.result
        assertEquals("the run belongs to the current revision", revisionId, scheduled.revisionId)
        assertEquals(
            "the request is the stored rule, converted field for field by the existing adapter",
            listOf(strengthRule().toTargetSchedule()),
            result.request.schedules
        )
        assertEquals(
            "three daily occurrences inside the caller's three-day window",
            listOf(ANCHOR, ANCHOR.plusDays(1), ANCHOR.plusDays(2)),
            result.applicationResult.persistenceResult.created.map { it.plannedFor }
        )
        assertEquals(
            "and the target slots carry the occurrence keys the composer built",
            listOf("rule-strength:$ANCHOR", "rule-strength:${ANCHOR.plusDays(1)}", "rule-strength:${ANCHOR.plusDays(2)}"),
            result.applicationResult.persistenceResult.created.map { it.targetOccurrenceKey }
        )
        val stored = rig.targetScheduleOccurrenceRepository.occurrencesOfProgram(ProgramId(programId))
        assertEquals(
            "the semantic occurrence is on disk, not just the slot that presents it",
            3,
            stored.size
        )
        assertEquals(
            "with the stored rule identity and workout identity, and nothing reconstructed",
            listOf("rule-strength", "rule-strength", "rule-strength"),
            stored.map { it.occurrence.components.single().ruleId }
        )
        assertEquals(
            listOf("workout-strength", "workout-strength", "workout-strength"),
            stored.map { it.occurrence.components.single().workoutId }
        )
        // The legacy slots the fixture created are still there and still legacy: the target pass
        // added opportunities beside them rather than rewriting the ones production trains from.
        assertEquals(
            "the fixture's three legacy slots are untouched",
            3,
            rig.programScheduleRepository.slotsOfProgram(ProgramId(programId))
                .count { it.targetOccurrenceKey == null }
        )
    }

    // ---- 2. exact source fidelity -------------------------------------------------------------------

    @Test
    fun everyFieldOfTheRuntimeComesFromTheStoredSourceAndNotFromLegacyProgramData() = runBlocking {
        rig.createGraph()
        val rule = TargetScheduleDefinition(
            ruleId = "rule-mobility",
            workoutId = "workout-mobility",
            cadence = ScheduleCadence.FixedWeekdays(setOf(java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.SATURDAY)),
            anchorDate = ANCHOR
        )
        rig.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = revisionId,
                rules = listOf(rule),
                programDayBindings = listOf(TargetProgramDayBinding("workout-mobility", dayId(3)))
            )
        )

        // A Thursday/Saturday cadence only produces occurrences if the caller's window contains one,
        // so this case states a window that does. The window is still the caller's, not the fixture's.
        val result = run(
            ProgramId(programId),
            defaultContext().copy(window = TargetScheduleWindow(ANCHOR, ANCHOR.plusDays(13)))
        ).scheduled().result

        assertEquals(
            "ruleId, workoutId, cadence form, cadence payload and anchor date all crossed unchanged",
            listOf(rule.toTargetSchedule()),
            result.request.schedules
        )
        assertEquals(
            "and the plan-day binding crossed as the one explicit statement, not a position or a name",
            listOf(TargetProgramDayBinding("workout-mobility", dayId(3))),
            result.request.programDayBindings
        )
        assertEquals(
            "the presenter used the stored plan day, for every occurrence",
            listOf(dayId(3), dayId(3), dayId(3), dayId(3)),
            result.applicationResult.presentations.map { it.programDayId }
        )
        assertEquals(
            "the stored cadence decided the dates: Thursdays and Saturdays only, and the anchor " +
                "excluded nothing because it is the window's first day",
            listOf("2026-10-08", "2026-10-10", "2026-10-15", "2026-10-17"),
            result.applicationResult.persistenceResult.created.map { it.plannedFor.toString() }
        )
        // Now move the legacy schedule under the run's feet. Nothing about the target pass may move
        // with it, because nothing in the pass reads it.
        rig.database.exec(
            "UPDATE `program_revision` SET `scheduleType` = 'FLEXIBLE_PER_WEEK', " +
                "`scheduleSessionsPerWeek` = 2, `scheduleWeekdays` = NULL " +
                "WHERE `revisionId` = '${revisionId.value}'"
        )

        val afterResult = run(
            ProgramId(programId),
            defaultContext().copy(window = TargetScheduleWindow(ANCHOR, ANCHOR.plusDays(13)))
        ).scheduled().result
        assertEquals(
            "the legacy schedule is not an input to the target pass and not an output of it",
            result.request.schedules,
            afterResult.request.schedules
        )
        assertEquals(
            "and the second run created nothing: the first pass already stored these occurrences",
            emptyList<WorkoutSlot>(),
            afterResult.applicationResult.persistenceResult.created
        )
    }

    // ---- 3. existing occurrence execution -----------------------------------------------------------

    @Test
    fun aStoredOccurrenceIsSuppliedByTheExecutionBridgeAndItsStoredHistoryStaysAuthoritative() =
        runBlocking {
            rig.createGraph()
            rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule()))
            val first = run(ProgramId(programId)).scheduled()
            val slot = first.result.applicationResult.persistenceResult.created.first()
            completeSessionOn(slot)

            val result = run(ProgramId(programId)).scheduled().result

            assertEquals(
                "the run was given the three stored occurrences, each under its own key",
                listOf("rule-strength:$ANCHOR", "rule-strength:${ANCHOR.plusDays(1)}", "rule-strength:${ANCHOR.plusDays(2)}"),
                result.request.existing.map { it.occurrenceKey }
            )
            assertEquals(
                "and the one with a completed attempt carries the execution the stored history states",
                listOf(OccurrenceExecution.COMPLETED, OccurrenceExecution.PLANNED, OccurrenceExecution.PLANNED),
                result.request.existing.map { it.execution }
            )
            assertEquals(
                "the completed occurrence is preserved by the reconciliation, not created again",
                listOf("rule-strength:$ANCHOR"),
                result.decision.preserved.map { it.occurrenceKey }
            )
            assertEquals(
                "and the two still-planned ones are retained under their own slots",
                listOf("rule-strength:${ANCHOR.plusDays(1)}", "rule-strength:${ANCHOR.plusDays(2)}"),
                result.decision.retained.map { it.occurrenceKey }
            )
            assertEquals(
                "nothing was re-created, so the completed occurrence's slot was not duplicated",
                emptyList<WorkoutSlot>(),
                result.applicationResult.persistenceResult.created
            )
            assertEquals(
                "and the stored payload is exactly what the occurrence repository holds, so the " +
                    "reconciler compared the stored record rather than the caller against itself",
                rig.targetScheduleOccurrenceRepository.occurrencesOfProgram(ProgramId(programId))
                    .map { it.occurrence },
                result.request.existing.map { it.occurrence }
            )
        }

    // ---- 4. idempotent repeated run -----------------------------------------------------------------

    @Test
    fun runningTheSameRequestTwiceCreatesNoDuplicateOccurrenceOrSlot() = runBlocking {
        rig.createGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule()))

        val first = run(ProgramId(programId)).scheduled()
        val occurrencesAfterFirst = occurrenceRows()
        val slotsAfterFirst = targetSlotRows()

        val secondResult = run(ProgramId(programId)).scheduled().result

        assertEquals(
            "the second pass created no slot: the stored ones satisfied every presentation",
            emptyList<WorkoutSlot>(),
            secondResult.applicationResult.persistenceResult.created
        )
        assertEquals(
            "and presented nothing at all on the rerun, because nothing was created — the stored " +
                "occurrences are still reported through the decision, not as new presentations",
            emptyList<WorkoutSlot>(),
            secondResult.applicationResult.persistenceResult.retained
        )
        assertEquals(
            "the rerun's decision reports the stored occurrences as retained under their own slots",
            listOf("rule-strength:$ANCHOR", "rule-strength:${ANCHOR.plusDays(1)}", "rule-strength:${ANCHOR.plusDays(2)}"),
            secondResult.decision.retained.map { it.occurrenceKey }
        )
        assertEquals(
            "the occurrence table is unchanged",
            occurrencesAfterFirst,
            occurrenceRows()
        )
        assertEquals("the target slot count is unchanged", slotsAfterFirst, targetSlotRows())
        assertEquals(
            "and the first pass really did write three of each, so the case is not vacuous",
            3,
            first.result.applicationResult.persistenceResult.created.size
        )
    }

    // ---- 5./6. the stored-source refusals -----------------------------------------------------------

    @Test
    fun aLegacyCreatedRevisionWithNoTargetSourceIsATypedAbsenceAndWritesNoTargetRow() = runBlocking {
        // `createGraph` is the legacy path: a Program, a revision, a plan and its initial slots, with
        // no target semantics stated anywhere. Nothing in this test writes one.
        rig.createGraph()
        assertEquals(
            "the fixture's revision really does carry a legacy schedule",
            "FIXED_WEEKDAYS",
            rig.database.rows(
                "SELECT `scheduleType` FROM `program_revision` WHERE `revisionId` = '${revisionId.value}'"
            ).single()["scheduleType"]
        )

        val outcome = run(ProgramId(programId))

        assertEquals(
            "a revision that states nothing about target semantics is a typed absence",
            TargetScheduleRunResult.SourceMissing(revisionId),
            outcome
        )
        assertEquals("no target occurrence was written", 0, occurrenceRows())
        assertEquals("no target slot was written", 0, targetSlotRows())
        assertEquals(
            "and the legacy slots are still exactly the three the legacy path created",
            3,
            rig.programScheduleRepository.slotsOfProgram(ProgramId(programId)).size
        )
    }

    @Test
    fun aMalformedStoredSourceIsReportedRatherThanDefaulted() = runBlocking {
        rig.createGraph()
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule()))
        // A cadence token outside the vocabulary. The row is storable precisely because the entity
        // defers unknown tokens to the mapper, so the read is what has to notice.
        rig.database.exec(
            "UPDATE `program_target_schedule_rule` SET `cadenceType` = 'TWICE_A_FORTNIGHT' " +
                "WHERE `revisionId` = '${revisionId.value}'"
        )

        val outcome = run(ProgramId(programId))

        assertTrue(
            "stored rows that contradict the vocabulary are reported, not read as some other schedule: $outcome",
            outcome is TargetScheduleRunResult.SourceMalformed
        )
        assertEquals(0, occurrenceRows())
        assertEquals(0, targetSlotRows())
    }

    // ---- 7. context fidelity -----------------------------------------------------------------------

    @Test
    fun theCallerOwnedContextIsForwardedUnchangedAndEachValueReachesTheStageThatOwnsIt() = runBlocking {
        rig.createGraph()
        val base = TargetScheduleDefinition("rule-strength", "workout-strength", ScheduleCadence.Daily, ANCHOR)
        val derived = TargetScheduleDefinition(
            "rule-extra",
            "workout-extra",
            ScheduleCadence.DerivedExcluding("rule-strength"),
            ANCHOR
        )
        rig.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = revisionId,
                rules = listOf(base, derived),
                programDayBindings = listOf(
                    TargetProgramDayBinding("workout-strength", dayId(1)),
                    TargetProgramDayBinding("workout-extra", dayId(3))
                )
            )
        )
        val context = TargetScheduleRunContext(
            window = TargetScheduleWindow(ANCHOR, ANCHOR.plusDays(2)),
            selection = CompositionSelection.combine("rule-strength"),
            sources = mapOf(
                "rule-strength" to ResolvedScheduleSource(
                    "rule-strength",
                    listOf(
                        ResolvedScheduleOccurrence(
                            "rule-strength",
                            "workout-strength",
                            ANCHOR.plusDays(1),
                            ScheduleCadence.Daily
                        )
                    )
                )
            ),
            asOf = ANCHOR,
            pauses = listOf(ProgramPauseWindow(ANCHOR.plusDays(2), ANCHOR.plusDays(2)))
        )

        val result = run(ProgramId(programId), context).scheduled().result

        assertEquals("the window crossed unchanged", context.window, result.request.window)
        assertEquals("the selection crossed unchanged", context.selection, result.request.selection)
        assertEquals("the resolved sources crossed unchanged", context.sources, result.request.sources)
        assertEquals("the as-of date crossed unchanged", context.asOf, result.request.asOf)
        assertEquals("the pause windows crossed unchanged", context.pauses, result.request.pauses)

        // The three dates, resolved: the daily rule produces one on each; the derived rule produces
        // one on each *except* the date the caller's own source excluded; and the caller's selection
        // composes the daily rule into a single combined occurrence instead of a separate one.
        val planned = result.decision.created
        assertEquals(
            "the selection reached the composer, and the caller's resolved source reached the resolver",
            listOf(
                "combined:$ANCHOR:13:rule-strength",
                "rule-extra:$ANCHOR",
                "combined:${ANCHOR.plusDays(1)}:13:rule-strength"
            ),
            planned.map { it.occurrenceKey }
        )
        assertTrue(
            "the derived rule produced nothing on the date the stated source excluded: " +
                "${planned.map { it.occurrenceKey }}",
            planned.none { it.occurrenceKey == "rule-extra:${ANCHOR.plusDays(1)}" }
        )
        assertTrue(
            "the as-of date reached the temporal stage: nothing before it was created, " +
                "found ${planned.map { it.plannedFor }}",
            planned.none { it.plannedFor.isBefore(context.asOf) }
        )
        assertTrue(
            "the pause window reached the temporal stage: its date created nothing, " +
                "found ${planned.map { it.plannedFor }}",
            planned.none { context.pauses.any { pause -> pause.covers(it.plannedFor) } }
        )
    }

    @Test
    fun aDerivedRuleWithNoStatedSourceIsTheResolversOwnRefusalAndIsNotManufacturedHere() = runBlocking {
        rig.createGraph()
        rig.targetScheduleSourceRepository.store(
            source(
                revisionId,
                TargetScheduleDefinition(
                    "rule-extra",
                    "workout-extra",
                    ScheduleCadence.DerivedExcluding("rule-strength"),
                    ANCHOR
                )
            )
        )
        val failure = try {
            run(ProgramId(programId))
            null
        } catch (thrown: Throwable) {
            thrown
        }

        assertTrue(
            "a derived rule whose source the caller did not state is refused by the resolver, not " +
                "silently given an empty one: $failure",
            failure is IllegalArgumentException && failure.message!!.contains("requires its source")
        )
        assertEquals("and nothing was written", 0, occurrenceRows())
    }

    // ---- 8. revision ownership ----------------------------------------------------------------------

    @Test
    fun theRunBelongsToTheCurrentRevisionAndAnEarlierRevisionsSourceIsUntouched() = runBlocking {
        rig.createGraph()
        val sourceA = TargetScheduleDefinition("rule-alpha", "workout-alpha", ScheduleCadence.Daily, ANCHOR)
        rig.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = revisionId,
                rules = listOf(sourceA),
                programDayBindings = listOf(TargetProgramDayBinding("workout-alpha", dayId(1)))
            )
        )
        val second = ProgramGraphFixture.nextRevision(KEY)
        rig.programPlanRepository.saveNewRevision(second, ProgramGraphFixture.FINISHED)
        val sourceB = TargetScheduleDefinition("rule-beta", "workout-beta", ScheduleCadence.Daily, ANCHOR)
        rig.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = second.revisionId,
                rules = listOf(sourceB),
                programDayBindings = listOf(
                    TargetProgramDayBinding("workout-beta", ProgramDayId("day-$KEY-2-1"))
                )
            )
        )

        val scheduled = run(ProgramId(programId)).scheduled()
        val result = scheduled.result

        assertEquals("the run names the revision the Program points at", second.revisionId, scheduled.revisionId)
        assertEquals(
            "and reads that revision's source, not the earlier one's",
            listOf(sourceB.toTargetSchedule()),
            result.request.schedules
        )
        assertEquals(
            "the earlier revision still states its own source, untouched by the run",
            listOf(sourceA),
            (rig.targetScheduleSourceRepository.sourceOf(revisionId)
                as TargetScheduleSourceRead.Source).source.rules
        )
        assertEquals(
            "and it wrote no occurrence against the earlier revision",
            0,
            rig.targetScheduleOccurrenceRepository
                .occurrencesOfProgram(ProgramId(programId))
                .count { it.occurrence.components.any { component -> component.ruleId == "rule-alpha" } }
        )
    }

    // ---- 9. cross-Program isolation -----------------------------------------------------------------

    @Test
    fun twoProgramsMayHoldIdenticalRuleIdentitiesWithoutReadingOrWritingEachOther() = runBlocking {
        val otherKey = "stage20-other"
        val otherProgramId = ProgramId(ProgramGraphFixture.programId(otherKey))
        val otherRevisionId = RevisionId(ProgramGraphFixture.revisionId(otherKey))
        val otherGraph = ProgramGraphFixture.graph(otherKey)
        rig.createGraph()
        rig.programRepository.createProgram(
            otherGraph.program,
            otherGraph.revision,
            otherGraph.slots
        )
        // Byte-identical authoring, including the rule identity and the workout identity.
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule()))
        rig.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = otherRevisionId,
                rules = listOf(strengthRule()),
                programDayBindings = listOf(
                    TargetProgramDayBinding("workout-strength", ProgramDayId(ProgramGraphFixture.dayId(otherKey, 1)))
                )
            )
        )

        run(ProgramId(programId))

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
            "while the Program that ran holds its own three",
            3,
            rig.targetScheduleOccurrenceRepository.occurrencesOfProgram(ProgramId(programId)).size
        )
        assertEquals(
            "membership stayed (programId, occurrenceKey): the same key under two Programs is two rows",
            0,
            occurrenceRowsFor(otherProgramId)
        )
    }

    // ---- 10. legacy isolation -----------------------------------------------------------------------

    @Test
    fun theConsumerRunsNoLegacySchedulingPassAndLeavesTheLegacySlotsAlone() = runBlocking {
        rig.createGraph()
        val legacySlotsBefore = rig.programScheduleRepository.slotsOfProgram(ProgramId(programId))
        rig.targetScheduleSourceRepository.store(source(revisionId, strengthRule()))

        run(ProgramId(programId))

        val legacySlotsAfter = rig.programScheduleRepository.slotsOfProgram(ProgramId(programId))
            .filter { it.targetOccurrenceKey == null }
        assertEquals(
            "the legacy slots the production UI trains from are byte-identical after a target run",
            legacySlotsBefore.map { it.slotId to it.status },
            legacySlotsAfter.map { it.slotId to it.status }
        )
        assertTrue(
            "and the consumer's own collaborator set contains no legacy scheduling boundary",
            TargetScheduleProductionConsumer::class.java.declaredConstructors
                .single()
                .parameterTypes
                .none { type ->
                    listOf(
                        "ProgramScheduler",
                        "SlotPlanner",
                        "ScheduleCalendar",
                        "ScheduleWindow",
                        "StandardProgramBootstrap"
                    ).any { type.simpleName == it }
                }
        )
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private suspend fun run(programId: ProgramId, context: TargetScheduleRunContext = defaultContext()) =
        consumer().run(programId, context)

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
                    idGenerator = IdGenerator { "s20-slot-${minted++}" },
                    inTransaction = rig.transaction
                )
            )
        )
    )

    private fun defaultContext() = TargetScheduleRunContext(
        window = TargetScheduleWindow(ANCHOR, ANCHOR.plusDays(2)),
        selection = CompositionSelection(),
        sources = emptyMap(),
        asOf = ANCHOR,
        pauses = emptyList()
    )

    private fun strengthRule() = TargetScheduleDefinition(
        "rule-strength",
        "workout-strength",
        ScheduleCadence.Daily,
        ANCHOR
    )

    private fun source(revision: RevisionId, rule: TargetScheduleDefinition) = TargetScheduleSource(
        revisionId = revision,
        rules = listOf(rule),
        programDayBindings = listOf(TargetProgramDayBinding(rule.workoutId, dayId(1)))
    )

    private fun dayId(position: Int) = ProgramDayId(ProgramGraphFixture.dayId(KEY, position))

    private fun occurrenceRows() = occurrenceRowsFor(ProgramId(programId))

    private fun occurrenceRowsFor(programId: ProgramId) =
        rig.database.count("program_target_occurrence") -
            rig.database.scalar(
                "SELECT COUNT(*) FROM `program_target_occurrence` WHERE `programId` <> ?",
                programId.value
            )!!.toInt()

    private fun targetSlotRows() = rig.database.scalar(
        "SELECT COUNT(*) FROM `program_workout_slot` WHERE `targetOccurrenceKey` IS NOT NULL"
    )!!.toInt()

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
        val sessionId = SessionId("session-s20-${slot.slotId.value}")
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
                    appliedAdjustmentIds = listOf(AdjustmentId("adj-s20"))
                )
            ),
            status = SessionStatus.IN_PROGRESS,
            startedAt = STARTED_AT,
            exercises = listOf(
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("se-s20-1"),
                    programExerciseId = first,
                    exerciseId = "knee_pushup",
                    prescription = RepPrescription(listOf(10, 8)),
                    results = listOf(
                        SetResult(
                            SetLogId("set-s20-1"),
                            1,
                            completedReps = 10,
                            durationSeconds = 0,
                            performedAt = STARTED_AT.plus(TEN_MINUTES)
                        )
                    )
                ),
                SessionExercise(
                    sessionExerciseId = SessionExerciseId("se-s20-2"),
                    programExerciseId = second,
                    exerciseId = "pike_pushup",
                    prescription = RepPrescription(listOf(8, 8)),
                    skipped = true
                )
            )
        )
    }

    /**
     * Narrows a run outcome to the success case, so a test that reads `revisionId` off it is reading
     * the real result type rather than a base interface that does not carry it. A refusal therefore
     * fails the assertion with the refusal printed, not with a cast error.
     */
    private fun TargetScheduleRunResult.scheduled(): TargetScheduleRunResult.Scheduled =
        this as? TargetScheduleRunResult.Scheduled
            ?: throw AssertionError("expected a scheduled run, got $this")

    private companion object {
        const val KEY = "stage20-consumer"
        val programId: String = ProgramGraphFixture.programId(KEY)
        val revisionId: RevisionId = RevisionId(ProgramGraphFixture.revisionId(KEY))
        val ANCHOR: LocalDate = LocalDate.parse("2026-10-05")
        val STARTED_AT: Instant = Instant.parse("2026-10-05T08:00:00Z")
        val TEN_MINUTES: Duration = Duration.ofMinutes(10)
        val FORTY_MINUTES: Duration = Duration.ofMinutes(40)
    }
}