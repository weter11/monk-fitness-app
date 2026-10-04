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
 * P30's integration rig: [ProgramAdaptiveIntegrationRig] rebuilt on the **shipped catalogue's own
 * exercise ids**, wired with the **production** [CatalogExerciseFamilyClassification] and **no ladder**.
 *
 * ### Why this rig exists rather than a new case in the old one
 *
 * §30 step 12's rig classifies `pushup` / `pike_pushup` / `decline_pushup` and declares the family
 * `push-family`. The shipped catalogue holds `pushups` / `pike_pushups` / `decline_pushups` and declares
 * the family `pushups`. Those id spaces **do not intersect**, and nothing before P30 noticed because
 * nothing before P30 ever asked the catalogue a question about a stored session.
 *
 * That is exactly the trap P23 documented: dozens of suites are built on the older fixture, and "fixing"
 * it would break them all. So the old rig is left alone and this one is written on catalogue ids — which
 * is the only way to prove the claim P30 makes, since a pass driven by ids the production classification
 * does not hold would classify nothing and stop one gap earlier than production does.
 *
 * ### What it wires, and the one thing it deliberately does not
 *
 * ```text
 * classification = CatalogExerciseFamilyClassification()   the production source
 * relations      = NoDeclaredProgression                   the production empty ladder
 * ```
 *
 * That pairing **is** production's wiring. It is what makes the central assertion of this stage
 * measurable: a pass that can now name the family it is about, and still refuses to adapt it because no
 * ladder is declared for that family.
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
     * The **production** ladder source: the persisted catalogue, read through the real provider.
     *
     * P30 left this as `noLadder()` because P30 authored no content. **P32 replaces it with the stored
     * provider over a bootstrapped database** — not a fixture ladder, and not a parallel rig — so every
     * adaptive assertion in this package now runs against production's own wiring: the shipped
     * classification, the seeded built-in catalogue, the real repository and the real provider.
     *
     * A test that wants the *empty* catalogue states it explicitly with [withoutLadder]; nothing reaches
     * here by accident any more, because the default is now the production path.
     */
    var relations: ProgressionRelationProvider = productionProvider()

    /** The production provider over this rig's own database, after the built-in bootstrap. */
    fun productionProvider(): StoredProgressionRelationProvider =
        StoredProgressionRelationProvider(data.progressionRelationRepository)

    /** Seeds the four authorised built-in ladders into this rig's database. */
    suspend fun seedBuiltInProgressionCatalogue() =
        BuiltInProgressionCatalogueBootstrap(data.progressionRelationRepository).bootstrap()

    /**
     * The **empty** catalogue, for the tests whose claim is that an undeclared family still refuses.
     *
     * It is an explicit opt-out rather than the default, because the default is now production's stored
     * provider — and a test that wants "no ladder" must now say so, which is the difference between a
     * test that measures production and one that happens to pass against it.
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
     * The default two-day graph: the past opportunities present the push day, the future ones the leg
     * day. This is the graph the *gap distinction* is measured on.
     */
    suspend fun createGraph() {
        // The bootstrap runs where the graph is created, so the production provider has the built-in
        // catalogue in place before any adaptive pass can read it — the test-side mirror of
        // `Application.onCreate`. Every rig reaches production wiring through this path.
        seedBuiltInProgressionCatalogue()
        data.programRepository.createProgram(program(), programRevision(), slots())
    }

    /**
     * A one-day graph in which **every** opportunity — past and future — presents the push day.
     *
     * This is the graph the central assertion is measured on, and it is a different shape from
     * [createGraph] on purpose. The claim *"with classification and no ladder the pass reports
     * `NO_DECLARED_PROGRESSION_RELATION`"* is only meaningful when the target opportunity actually
     * presents the family the completion exposed; on a graph where it does not, that gap is unreachable
     * and the assertion would be measuring a different refusal.
     */
    suspend fun createSingleFamilyGraph() {
        seedBuiltInProgressionCatalogue()
        data.programRepository.createProgram(program(), singleFamilyRevision(), singleFamilySlots())
    }

    /**
     * The catalogue repository itself — the one writer production has for ladder content.
     *
     * Exposed so a test can **replace** a stored ladder and observe that the provider's answer follows
     * storage. That is the behavioural proof that the provider reads the persisted catalogue rather than
     * a static definitions object: with a static source, rewriting the stored rows would change nothing.
     */
    fun progressionCatalogueForTest() = data.progressionRelationRepository

    /**
     * Seeds enough history for the engine's **existing** confirmation window to be satisfied, so a pass
     * can reach a real progression instead of its `AWAITING_CONFIRMATION` hold.
     *
     * The policy asks for two progress-qualifying windows (`progressConfirmingWindows = 2`), and P32
     * changed nothing about that. This therefore replays the rig's ordinary history shape **twice over**,
     * letting each pass record a qualifying window, and only then takes the pass whose result is
     * Runs [qualifyingWindows] **complete** adaptive cycles, each ending with its trigger session
     * cancelled, so production's own family state records that many progress-qualifying windows.
     *
     * The policy requires two progress-qualifying windows (`progressConfirmingWindows = 2`) and **P32
     * changed nothing about that**. A window is only counted once a pass has *run and recorded* it, so
     * seeding extra completed sessions is not enough: this drives the real `seedHistoryAndTrigger` ->
     * `adaptAfter` cycle once per window, which is what lets a later pass reach the engine's own
     * `SUSTAINED_POSITIVE` instead of its `AWAITING_CONFIRMATION` hold. Reaching a real progression is
     * the stage's central claim, so it is worth driving the real cycle rather than hand-writing a family
     * state that would let the engine believe something no pass ever established.
     *
     * The trigger is left **IN_PROGRESS** by [seedHistoryAndTrigger] because §19 allows only one
     * in-progress session per slot, so each cycle cancels its own before the next begins on the same
     * slot; reusing a slot without cancelling is refused by production's own rule, not by this rig.
     *
     * @return the trigger session id of the **last** cycle, for the asserted pass to run after.
     */
    suspend fun seedProgressQualifyingHistory(qualifyingWindows: Int = 2): SessionId {
        val windows = qualifyingWindows.coerceAtLeast(1)
        // The **last** cycle's trigger is deliberately left IN_PROGRESS, because it is the session an
        // asserted pass runs against: §19 refuses a pass on a cancelled session, so cancelling the final
        // trigger would report `SESSION_WAS_CANCELLED` and measure the wrong thing entirely. Only the
        // earlier cycles are cancelled, so each can release the slot for the next.
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
     * One training day presenting three **real catalogue exercises of the catalogue's `pushups` family**.
     *
     * `pushups`, `pushups_wide` and `decline_pushups` are three distinct ids the shipped catalogue states
     * as members of one family, so a pass over this day has a classified element that also presents an
     * exposed family — which is the state in which the integration must refuse for want of a *relation*
     * rather than for want of a *classification*. Getting that state wrong reports a gap that production
     * would never report.
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
     * A second day presenting a family the push day never presents.
     *
     * It exists so the two gaps can be told apart on the **same** plan: the completion trains
     * `pushups` and the next opportunity presents only `squats`, which is
     * `NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT`. Putting both families in one day would not do it — the
     * completion would then have exposed the very family the target presents, and the pass would reach
     * the *relation* gap for the wrong reason.
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
     * Six opportunities: the four **past** ones present the push day (so the history and the trigger
     * completion are about `pushups`), and the two **future** ones present the leg day (so the adaptive
     * target presents a family the completion did not expose).
     *
     * The split is what makes the central assertion falsifiable. If every slot presented the push day,
     * the pass would find the exposed family presented and report `NO_DECLARED_PROGRESSION_RELATION` —
     * the *expected* result — whether or not the classification worked, so the assertion would pass for
     * the wrong reason.
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
            // `finishSession` carries the completion rather than the Session, so the re-read below is
            // what returns a `WorkoutSession` on this path.
            SessionEnd.COMPLETED -> {
                value(runtime.finishSession(session.sessionId))
                // The completion is not the Session, so the stored rows are re-read through a fresh
                // repository — the same read §30 step 12's own rig uses for this path.
                data.freshSessionRepository().sessionById(session.sessionId)
                    ?: throw AssertionError("session '${session.sessionId.value}' was just completed")
            }
            SessionEnd.IN_PROGRESS -> session
        }
    }

    enum class SessionEnd { CANCELLED, COMPLETED, IN_PROGRESS }

    suspend fun adapt(sessionId: SessionId): AdaptiveIntegrationResult = integration().adaptAfter(sessionId)

    fun outcomeOf(result: AdaptiveIntegrationResult): AdaptiveIntegrationOutcome = when (result) {
        is AdaptiveIntegrationResult.Success -> result.outcome
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

        /** The rig over Program A — the one whose push day presents the family its completion exposed. */
        fun of(): CatalogAdaptiveIntegrationRig = CatalogAdaptiveIntegrationRig("a")

        /** A second, independent Program with its own database, id space and history. */
        fun secondProgram(): CatalogAdaptiveIntegrationRig = CatalogAdaptiveIntegrationRig("b")
    }
}