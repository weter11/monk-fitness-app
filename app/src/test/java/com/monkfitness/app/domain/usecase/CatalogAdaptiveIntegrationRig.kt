package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationResult
import com.monkfitness.app.domain.adaptive.integration.AdaptiveWindowRule
import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification
import com.monkfitness.app.bootstrap.BuiltInProgressionCatalogueBootstrap
import com.monkfitness.app.domain.adaptive.integration.ProgressionRelationProvider
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The integration rig rebuilt on the **shipped catalogue's own exercise ids**, wired with the
 * **production** [CatalogExerciseFamilyClassification] and the production stored ladder provider.
 *
 * The older rig classifies `pushup` / `pike_pushup` / `decline_pushup` and declares `push-family`; the
 * shipped catalogue holds `pushups` / `pike_pushups` / `decline_pushups` and declares `pushups`. Those id
 * spaces **do not intersect**, and dozens of suites are built on the older fixture, so "fixing" it would
 * break them all — the old rig is left alone and this one is written on catalogue ids. A pass driven by
 * ids the production classification does not hold would classify nothing and stop one gap earlier than
 * production does.
 */
internal class CatalogAdaptiveIntegrationRig private constructor(
    val programTag: String
) {

    private val data = ProgramDataAccessRig("p30-$programTag", null)

    /** The migrated in-memory database, so a test can count rows in a table the rig does not name. */
    val database: SqliteTestDatabase get() = data.database

    val clock = MovableClock(COMPLETION_ATTEMPT)
    val ids = SequentialIds("p30-$programTag")

    /**
     * The **production** ladder source: the persisted catalogue, read through the real provider over a
     * bootstrapped database — not a fixture ladder, and not a parallel rig. So every adaptive assertion in
     * this package runs against production's own wiring: the shipped classification, the seeded built-in
     * catalogue, the real repository and the real provider.
     *
     * A test that wants the *empty* catalogue states it explicitly with [withoutLadder]; nothing reaches
     * here by accident, because the default is the production path.
     */
    var relations: ProgressionRelationProvider = productionProvider()

    /** The production provider over this rig's own database, after the built-in bootstrap. */
    fun productionProvider(): StoredProgressionRelationProvider =
        StoredProgressionRelationProvider(data.progressionRelationRepository)

    /** Seeds the four authorised built-in ladders into this rig's database. */
    suspend fun seedBuiltInProgressionCatalogue() =
        BuiltInProgressionCatalogueBootstrap(data.progressionRelationRepository).bootstrap()

    /**
     * The **empty** catalogue, for the tests whose claim is that an undeclared family still refuses. An
     * explicit opt-out, so "no ladder" is stated rather than inherited — the difference between a test
     * that measures production and one that happens to pass against it.
     */
    fun withoutLadder(): CatalogAdaptiveIntegrationRig = apply {
        relations = ProgramAdaptiveIntegrationRig.noLadder()
    }

    /** The production source. Replaced by no test that measures production behaviour. */
    var classification: ExerciseFamilyClassification = CatalogExerciseFamilyClassification()

    fun integration(): ProgramAdaptiveIntegration = ProgramAdaptiveIntegration(
        planRepository = data.programPlanRepository,
        scheduleRepository = data.programScheduleRepository,
        sessionRepository = data.workoutSessionRepository,
        adaptiveRepository = data.programAdaptiveRepository,
        relations = relations,
        classification = classification,
        clock = clock,
        idGenerator = ids,
        zone = ZoneOffset.UTC,
        window = AdaptiveWindowRule.V1
    )

    private val runtime: SessionRuntime = SessionRuntime(
        planRepository = data.programPlanRepository,
        scheduleRepository = data.programScheduleRepository,
        sessionRepository = data.workoutSessionRepository,
        adaptiveRepository = data.programAdaptiveRepository,
        clock = clock,
        idGenerator = ids,
        zone = ZoneOffset.UTC,
        inTransaction = data.transaction
    )

    val programId: ProgramId get() = ProgramId(PROGRAM_PREFIX + programTag)
    private val revision = RevisionId("revision-p30-$programTag")
    private val day = ProgramDayId("day-p30-$programTag")
    private val otherDay = ProgramDayId("day-p30-$programTag-2")

    /**
     * The default two-day graph: past opportunities present the push day, future ones the leg day — the
     * graph the *gap distinction* is measured on.
     */
    suspend fun createGraph() {
        // The bootstrap runs where the graph is created, so the provider has the built-in catalogue in
        // place before any adaptive pass reads it — the test-side mirror of `Application.onCreate`.
        seedBuiltInProgressionCatalogue()
        data.programRepository.createProgram(program(), programRevision(), slots())
    }

    /**
     * A one-day graph in which **every** opportunity — past and future — presents the push day, unlike
     * [createGraph] on purpose: the relation gap is only reachable when the target opportunity presents
     * the family the completion exposed. On a graph where it does not, that gap is unreachable and the
     * assertion measures a different refusal.
     */
    suspend fun createSingleFamilyGraph() {
        seedBuiltInProgressionCatalogue()
        data.programRepository.createProgram(program(), singleFamilyRevision(), singleFamilySlots())
    }

    /**
     * A one-day graph presenting an element of **two** families, so one session exposes both and a single
     * completion has two legs. The smallest graph on which "one leg per family" is observable.
     */
    suspend fun createTwoFamilyGraph() {
        seedBuiltInProgressionCatalogue()
        data.programRepository.createProgram(program(), twoFamilyRevision(), singleFamilySlots())
    }

    /**
     * The catalogue repository itself — the one writer production has for ladder content. Exposed so a
     * test can **replace** a stored ladder and observe the provider's answer follow storage: with a static
     * definitions object, rewriting the stored rows would change nothing.
     */
    fun progressionCatalogueForTest() = data.progressionRelationRepository

    /**
     * Runs [qualifyingWindows] **complete** adaptive cycles, so production's own family state records
     * that many progress-qualifying windows and a later pass can reach the engine's `SUSTAINED_POSITIVE`
     * instead of its `AWAITING_CONFIRMATION` hold.
     *
     * The policy requires two progress-qualifying windows, and a window is only counted once a pass has
     * *run and recorded* it — seeding extra completed sessions is not enough. Driving the real
     * `seedHistoryAndTrigger` -> `adaptAfter` cycle is what makes the progression a fact a pass
     * established rather than a family state hand-written to let the engine believe it.
     *
     * The trigger is left **IN_PROGRESS** by [seedHistoryAndTrigger] because only one in-progress session
     * per slot is allowed, so each cycle cancels its own before the next begins on the same slot;
     * reusing a slot without cancelling is refused by production's own rule, not by this rig.
     *
     * @return the trigger session id of the **last** cycle, for the asserted pass to run after.
     */
    suspend fun seedProgressQualifyingHistory(qualifyingWindows: Int = 2): SessionId {
        val windows = qualifyingWindows.coerceAtLeast(1)
        // The **last** cycle's trigger is left IN_PROGRESS: it is the session the asserted pass runs
        // against, and a pass on a cancelled session reports `SESSION_WAS_CANCELLED` instead. Only the
        // earlier cycles are cancelled, so each releases the slot for the next.
        repeat(windows - 1) {
            val trigger = seedHistoryAndTrigger()
            integration().adaptAfter(trigger.sessionId)
            runtime.cancelSession(trigger.sessionId)
        }
        return seedHistoryAndTrigger().sessionId
    }

    fun program(): Program = Program(
        programId = programId,
        name = "P30 program $programTag",
        description = "the P30 rig's program",
        source = ProgramSource.USER,
        lifecycleStatus = LifecycleStatus.RUNNING,
        currentRevisionId = revision,
        createdAt = CREATED,
        updatedAt = CREATED,
        plannedStartDate = LocalDate.parse("2026-09-18"),
        actualStartDate = CREATED
    )

    fun programRevision(): ProgramRevision = ProgramRevision(
        revisionId = revision,
        programId = programId,
        revisionNumber = 1,
        mode = ProgramMode.GENERATED,
        duration = ProgramDuration.FixedDays(30),
        schedule = ProgramSchedule.FixedWeekdays(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        ),
        days = listOf(pushDay(), otherFamilyDay()),
        createdAt = CREATED
    )

    /**
     * Three **real catalogue exercises of the catalogue's `pushups` family**, so a pass over this day has
     * a classified element that also presents an exposed family — the state in which the integration must
     * refuse for want of a *relation* rather than a *classification*. Getting that state wrong reports a
     * gap production would never report.
     */
    fun pushDay(): ProgramDay = ProgramDay(
        programDayId = day,
        position = 1,
        type = ProgramDayType.TRAINING,
        name = "Push day",
        exercises = DAY_EXERCISES.mapIndexed { index, exerciseId ->
            ProgramExercise(
                programExerciseId = ProgramExerciseId("plan-ex-p30-$programTag-1-${index + 1}"),
                exerciseId = exerciseId,
                prescription = RepPrescription(listOf(8, 8)),
                origin = ProgramExerciseOrigin.GENERATED
            )
        }
    )

    private fun singleFamilyRevision(): ProgramRevision =
        programRevision().copy(days = listOf(pushDay()))

    private fun twoFamilyRevision(): ProgramRevision =
        programRevision().copy(days = listOf(twoFamilyDay()))

    /**
     * The push day plus one `squats` element: both families are classified and both have a stored ladder,
     * so a completion over this day has two families to decide rather than one to pick.
     */
    fun twoFamilyDay(): ProgramDay = ProgramDay(
        programDayId = day,
        position = 1,
        type = ProgramDayType.TRAINING,
        name = "Push and squat day",
        exercises = listOf(DAY_EXERCISES.first(), OTHER_FAMILY_EXERCISES.first())
            .mapIndexed { index, exerciseId ->
                ProgramExercise(
                    programExerciseId = ProgramExerciseId("plan-ex-p30-$programTag-1-${index + 1}"),
                    exerciseId = exerciseId,
                    prescription = RepPrescription(listOf(8, 8)),
                    origin = ProgramExerciseOrigin.GENERATED
                )
            }
    )

    private fun singleFamilySlots(): List<WorkoutSlot> = SLOT_DATES.mapIndexed { index, date ->
        WorkoutSlot(
            slotId = slot(index + 1),
            programId = programId,
            revisionId = revision,
            programDayId = day,
            plannedFor = date,
            status = SlotStatus.PLANNED
        )
    }

    /**
     * A second day presenting a family the push day never presents, so the two gaps can be told apart on
     * the **same** plan: the completion trains `pushups` and the next opportunity presents only `squats`,
     * which is `NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT`. Both families in one day would not do it — the
     * completion would have exposed the very family the target presents.
     */
    fun otherFamilyDay(): ProgramDay = ProgramDay(
        programDayId = otherDay,
        position = 2,
        type = ProgramDayType.TRAINING,
        name = "Leg day",
        exercises = OTHER_FAMILY_EXERCISES.mapIndexed { index, exerciseId ->
            ProgramExercise(
                // A `programExerciseId` is unique within a revision, not within a day, so the two days
                // must not share ids — a collision reads as a broken insert rather than a broken fixture.
                programExerciseId = ProgramExerciseId("plan-ex-p30-$programTag-2-${index + 1}"),
                exerciseId = exerciseId,
                prescription = RepPrescription(listOf(8, 8)),
                origin = ProgramExerciseOrigin.GENERATED
            )
        }
    )

    /**
     * Six opportunities: the four **past** ones present the push day (so the history and the trigger are
     * about `pushups`), the two **future** ones the leg day (so the target presents a family the
     * completion did not expose).
     *
     * The split makes the central assertion falsifiable: if every slot presented the push day, the pass
     * would report `NO_DECLARED_PROGRESSION_RELATION` whether or not the classification worked, and the
     * assertion would pass for the wrong reason.
     */
    fun slots(): List<WorkoutSlot> = SLOT_DATES.mapIndexed { index, date ->
        WorkoutSlot(
            slotId = SlotId("${slotPrefix()}${index + 1}"),
            programId = programId,
            revisionId = revision,
            programDayId = if (index < futureFrom()) day else otherDay,
            plannedFor = date,
            status = SlotStatus.PLANNED
        )
    }

    /** The 0-based index from which the opportunities present the leg day rather than the push day. */
    private fun futureFrom(): Int = 4

    fun slotPrefix(): String = "slot-p30-$programTag-"

    fun slot(position: Int): SlotId = SlotId("${slotPrefix()}$position")

    /** Two attempts at the past, then the trigger attempt the adaptive pass is taken after. */
    suspend fun seedHistoryAndTrigger(): WorkoutSession {
        attempt(slot(2), HISTORY_ONE, setsPerOccurrence = 1, occurrences = 1)
        data.database.exec(
            "UPDATE `program_workout_slot` SET `status` = 'MISSED' WHERE `slotId` = '${slot(3).value}'"
        )
        attempt(slot(4), HISTORY_TWO, setsPerOccurrence = 2)
        return attempt(
            slot = slot(2),
            at = COMPLETION_ATTEMPT,
            setsPerOccurrence = 2,
            end = SessionEnd.IN_PROGRESS
        ).also { clock.instant = COMPLETION_MOMENT }
    }

    suspend fun attempt(
        slot: SlotId,
        at: Instant,
        setsPerOccurrence: Int,
        occurrences: Int = 3,
        end: SessionEnd = SessionEnd.CANCELLED
    ): WorkoutSession {
        clock.instant = at
        val session = value(runtime.startSession(slot))
        session.exercises.take(occurrences).forEach { occurrence ->
            repeat(setsPerOccurrence) { index ->
                value(
                    runtime.confirmSet(
                        sessionId = session.sessionId,
                        sessionExerciseId = occurrence.sessionExerciseId,
                        completedReps = occurrence.prescription.perSetTargets[index]
                    )
                )
            }
        }
        return when (end) {
            SessionEnd.CANCELLED -> value(runtime.cancelSession(session.sessionId))
            SessionEnd.COMPLETED -> {
                value(runtime.finishSession(session.sessionId))
                // `finishSession` carries the completion, not the Session, so the stored rows are
                // re-read through a fresh repository.
                data.freshSessionRepository().sessionById(session.sessionId)
                    ?: throw AssertionError("session '${session.sessionId.value}' was just completed")
            }
            SessionEnd.IN_PROGRESS -> session
        }
    }

    enum class SessionEnd { CANCELLED, COMPLETED, IN_PROGRESS }

    suspend fun adapt(sessionId: SessionId): AdaptiveIntegrationResult = integration().adaptAfter(sessionId)

    fun outcomeOf(result: AdaptiveIntegrationResult): AdaptiveIntegrationOutcome = outcomesOf(result).single()

    /** Every outcome a pass produced, one per family the completion exposed. */
    fun outcomesOf(result: AdaptiveIntegrationResult): List<AdaptiveIntegrationOutcome> = when (result) {
        is AdaptiveIntegrationResult.Success -> result.outcomes
        is AdaptiveIntegrationResult.InvalidData ->
            throw AssertionError("expected an outcome, got invalid data: ${result.cause.message}")

        is AdaptiveIntegrationResult.Failure ->
            throw AssertionError("expected an outcome, got a failure: ${result.cause}")
    }

    fun gapOf(result: AdaptiveIntegrationResult): AdaptiveInputGap {
        val outcome = outcomeOf(result)
        if (outcome !is AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest) {
            throw AssertionError("expected a gap, got $outcome")
        }
        return outcome.gap
    }

    /** The stored family of a program exercise, read back from the rows rather than from the fixture. */
    suspend fun storedFamilyStateOf(familyId: String): com.monkfitness.app.domain.adaptive.FamilyProgressionState? =
        data.programAdaptiveRepository.familyState(revision, familyId)

    fun counts(): Map<String, Int> = TABLES.associateWith { data.database.count(it) }

    fun close() = data.close()

    companion object {

        const val PROGRAM_PREFIX = "program-p30-"

        /** The catalogue's `pushups` family, by the catalogue's own ids. */
        val DAY_EXERCISES = listOf("pushups", "pushups_wide", "decline_pushups")

        /** The catalogue's `squats` family — a family the push day never presents. */
        val OTHER_FAMILY_EXERCISES = listOf("squats", "squats_sumo", "squats_jump")

        val CREATED: Instant = Instant.parse("2026-09-01T08:00:00Z")
        val HISTORY_ONE: Instant = Instant.parse("2026-09-18T07:30:00Z")
        val HISTORY_TWO: Instant = Instant.parse("2026-09-20T07:30:00Z")
        val COMPLETION_ATTEMPT: Instant = Instant.parse("2026-09-21T07:30:00Z")
        val COMPLETION_MOMENT: Instant = Instant.parse("2026-09-21T08:20:00Z")

        val SLOT_DATES: List<LocalDate> = listOf(
            LocalDate.parse("2026-09-16"),
            LocalDate.parse("2026-09-18"),
            LocalDate.parse("2026-09-19"),
            LocalDate.parse("2026-09-20"),
            LocalDate.parse("2026-09-22"),
            LocalDate.parse("2026-09-23")
        )

        val TABLES: List<String> = listOf(
            "workout_session",
            "session_snapshot",
            "session_snapshot_exercise",
            "session_exercise",
            "program_set_log",
            "program_workout_slot",
            "program_adaptive_decision_record",
            "adaptive_adjustment",
            "program_family_progression_state",
            "program_revision",
            "program_day",
            "program_exercise",
            "program"
        )

        /** The rig over Program A — whose push day presents the family its completion exposed. */
        fun of(): CatalogAdaptiveIntegrationRig = CatalogAdaptiveIntegrationRig("a")

        /** A second, independent Program with its own database, id space and history. */
        fun secondProgram(): CatalogAdaptiveIntegrationRig = CatalogAdaptiveIntegrationRig("b")
    }
}