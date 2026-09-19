package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationResult
import com.monkfitness.app.domain.adaptive.integration.AdaptiveWindowRule
import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification
import com.monkfitness.app.domain.adaptive.integration.ProgressionRelationProvider
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.LifecycleStatus
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
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.AdaptiveCompletion
import com.monkfitness.app.domain.workout.SessionCompletion
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The §30 step 12 suite's rig: a real migrated SQLite database, the production repositories on their
 * own DAOs, the production [SessionRuntime] and the production [ProgramAdaptiveIntegration] — the
 * whole flow from *"a Session was completed"* to *"the next opportunity presents something else"*,
 * with nothing simulated above the engine.
 *
 * ### The Program it builds
 *
 * The data-access fixture's own graph is a `MANUAL` Program with a pinned element, which §19 refuses
 * to adapt at all — deliberately, and it has its own test. This rig therefore **creates its own**
 * Program, through the production creation path, shaped so that one ladder, one window and one
 * adjustment are all exactly measurable:
 *
 * ```text
 * revision-p12   GENERATED, one training day presenting three elements of ONE family:
 *                plan-ex-p12-1  pushup          2 sets of 8   (the element the ladder resolves)
 *                plan-ex-p12-2  pike_pushup     2 sets of 8
 *                plan-ex-p12-3  decline_pushup  2 sets of 8
 *
 * slots          slot-p12-1  2026-09-16  a past opportunity nothing happened on
 *                slot-p12-2  2026-09-18  the opportunity the trigger Session takes
 *                slot-p12-3  2026-09-19  a past opportunity, `MISSED` and still startable
 *                slot-p12-4  2026-09-20  a past opportunity with history on it
 *                slot-p12-5  2026-09-22  the next *future* opportunity — the adaptive target
 *                slot-p12-6  2026-09-23  a later opportunity, which must not be chosen instead
 * ```
 *
 * The family's ladder is `push-family`: level 1 `pushup`, level 2 `pike_pushup`, level 3
 * `decline_pushup`, every level prescribing two sets of eight unless a test asks for the elevated
 * variants [VariantLevels.EXCESSIVE], which is what makes §18's guard refuse a change.
 *
 * ### The window it can produce
 *
 * [seedHistoryAndTrigger] writes the history a positive progression window needs, and the arithmetic is exact:
 *
 * ```text
 * 2026-09-18  slot 2  a cancelled attempt: ONE set on the first element      (1 exposure, partial)
 * 2026-09-20  slot 4  a cancelled attempt: both sets on all three elements   (3 exposures, full)
 * 2026-09-21  slot 2  the trigger: both sets on all three elements           (3 exposures, full)
 * ```
 *
 * Seven comparable exposures in the window, split 3/4: the older half prescribed 6 sets and completed
 * 5, the newer half prescribed 8 and completed 8 — a measured positive trend with no shortfall on the
 * recent end, which is §7's progression reading. The plan's own prescription over the window's four
 * opportunities (24 sets, 192 repetitions) is *above* what was performed (13 sets, 104 repetitions), so
 * the recent context is not elevated and §14's context is `FAVORABLE`; attendance is 2 of 4
 * opportunities, which is the medium bucket and not the low one.
 *
 * [attempt] takes how many sets to confirm and how many of the occurrences to confirm them on, so a
 * test can add a later completion (a second full attempt, 19 sets performed) without pushing the
 * window's work past what the plan asked for — which §18's recent-context rule would read as
 * *"already carrying more than the plan prescribes"* and refuse the change for.
 *
 * @param excessiveLevels ask the ladder for variants whose prescriptions *add* volume, so §18's guard
 *   refuses the change and the decision is kept as `NOT_APPLIED`.
 */
internal class ProgramAdaptiveIntegrationRig private constructor(
    val excessiveLevels: Boolean
) {

    private val data = ProgramDataAccessRig(KEY, null)

    val database: SqliteTestDatabase get() = data.database

    /** The clock every session operation and every pass reads — a value the test set (§26). */
    val clock = MovableClock(COMPLETION_ATTEMPT)

    /** Deterministic identity, so a minted session, occurrence, set, decision and adjustment read. */
    val ids = SequentialIds("p12")

    /** The fault points a test plants, so §27's unit is measured and not argued. */
    val faults get() = data.faults

    /** The two capability boundaries §30 step 12 wires: replaced per test where that is the subject. */
    var relations: ProgressionRelationProvider = ladder(excessiveLevels)
    var classification: ExerciseFamilyClassification = pushFamilies()
    val window: AdaptiveWindowRule = AdaptiveWindowRule.V1

    /** The policy every threshold is read from — a test may replace it for the audit proof. */
    var policy: ProgramAdaptivePolicy = ProgramAdaptivePolicy.V1

    /** The behaviour under test, wired as the composition root wires it. */
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
        window = window,
        policy = policy
    )

    /**
     * The same graph through **freshly constructed repositories**: a restart, as far as any read can
     * tell, because the integration holds no state of its own and every value it assembles comes from
     * rows.
     */
    fun freshIntegration(): ProgramAdaptiveIntegration = ProgramAdaptiveIntegration(
        planRepository = data.freshPlanRepository(),
        scheduleRepository = data.programScheduleRepository,
        sessionRepository = data.freshSessionRepository(),
        adaptiveRepository = data.freshAdaptiveRepository(),
        relations = relations,
        classification = classification,
        clock = clock,
        idGenerator = ids,
        zone = ZoneOffset.UTC,
        window = window,
        policy = policy
    )

    /** The session runtime, over the same repositories and the same transaction runner. */
    val runtime: SessionRuntime = SessionRuntime(
        planRepository = data.programPlanRepository,
        scheduleRepository = data.programScheduleRepository,
        sessionRepository = data.workoutSessionRepository,
        adaptiveRepository = data.programAdaptiveRepository,
        clock = clock,
        idGenerator = ids,
        // The same zone the integration gets, so the runtime's own temporal check of the decision it
        // produced is a check of the very rule that chose the opportunity.
        zone = ZoneOffset.UTC,
        inTransaction = data.transaction
    )

    // ---------------------------------------------------------------------------------------------
    // The graph
    // ---------------------------------------------------------------------------------------------

    /** Persists the Program, its `GENERATED` revision and the five opportunities, atomically. */
    suspend fun createGraph() = data.programRepository.createProgram(program(), revision(1), slots())

    /** The Program, whose current revision is the one [createGraph] saved. */
    fun program(): Program = Program(
        programId = ProgramId(PROGRAM),
        name = "Adaptive program",
        description = "the §30 step 12 rig's program",
        source = ProgramSource.USER,
        lifecycleStatus = LifecycleStatus.RUNNING,
        currentRevisionId = RevisionId(revisionId(1)),
        createdAt = CREATED,
        updatedAt = CREATED,
        plannedStartDate = LocalDate.parse("2026-09-18"),
        actualStartDate = CREATED
    )

    /**
     * One `GENERATED` revision of the rig's Program: one training day presenting the family's three
     * variants, and nothing else. `GENERATED` is what §19 makes adapt-able, and a `MANUAL` revision is
     * refused by the integration entirely — its own test asserts that.
     */
    fun revision(number: Int): ProgramRevision = ProgramRevision(
        revisionId = RevisionId(revisionId(number)),
        programId = ProgramId(PROGRAM),
        revisionNumber = number,
        mode = ProgramMode.GENERATED,
        duration = ProgramDuration.FixedDays(30),
        schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
        days = listOf(pushDay(number)),
        createdAt = CREATED
    )

    /** The revision's plan: one training day, three elements of one family, in presentation order. */
    fun pushDay(number: Int): ProgramDay = ProgramDay(
        programDayId = ProgramDayId(dayId(number)),
        position = 1,
        type = ProgramDayType.TRAINING,
        name = "Push day",
        exercises = listOf(
            element(number, 1, "pushup"),
            element(number, 2, "pike_pushup"),
            element(number, 3, "decline_pushup")
        )
    )

    private fun element(number: Int, position: Int, exerciseId: String): ProgramExercise = ProgramExercise(
        programExerciseId = ProgramExerciseId(planExerciseId(number, position)),
        exerciseId = exerciseId,
        prescription = RepPrescription(listOf(8, 8)),
        origin = ProgramExerciseOrigin.GENERATED
    )

    /** Five open opportunities: three past ones (09-18..09-20) and two future ones (09-22, 09-23). */
    fun slots(): List<WorkoutSlot> = SLOT_DATES.mapIndexed { index, date ->
        WorkoutSlot(
            slotId = SlotId(slotId(index + 1)),
            programId = ProgramId(PROGRAM),
            revisionId = RevisionId(revisionId(1)),
            programDayId = ProgramDayId(dayId(1)),
            plannedFor = date,
            status = SlotStatus.PLANNED
        )
    }

    /** One opportunity of the rig's revision, by its 1-based position. */
    fun slot(position: Int): SlotId = SlotId(slotId(position))

    fun plannedForOf(position: Int): LocalDate = SLOT_DATES[position - 1]

    /**
     * Saves a **second** revision of the same Program, with its own day, its own opportunities and its
     * own (empty) adaptive state — what §6's Save produces and what "a new revision starts from its own
     * baseline" is measured on.
     */
    suspend fun saveSecondRevision(): ProgramRevision {
        val revision = revision(2)
        data.programPlanRepository.saveNewRevision(revision, CREATED)
        data.programScheduleRepository.addSlots(
            SLOT_DATES.mapIndexed { index, date ->
                WorkoutSlot(
                    slotId = SlotId("$SECOND_REVISION_SLOT_PREFIX${index + 1}"),
                    programId = ProgramId(PROGRAM),
                    revisionId = revision.revisionId,
                    programDayId = ProgramDayId(dayId(2)),
                    plannedFor = date,
                    status = SlotStatus.PLANNED
                )
            }
        )
        return revision
    }

    /** One opportunity of the second revision, by its 1-based position. */
    fun secondRevisionSlot(position: Int): SlotId = SlotId("$SECOND_REVISION_SLOT_PREFIX$position")

    // ---------------------------------------------------------------------------------------------
    // The sessions
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts an attempt at [slot], confirms [setsPerOccurrence] sets of every occurrence it runs, and
     * ends it as [cancel] asks.
     *
     * The attempt's moment is [at] — the clock is moved to it, so the exposure's own `startedAt` and
     * every stamp are values the test stated. [end] is `null` for an attempt left in progress.
     */
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
            SessionEnd.CANCELLED -> {
                value(runtime.cancelSession(session.sessionId))
                requireStored(session.sessionId)
            }

            SessionEnd.COMPLETED -> {
                value(runtime.finishSession(session.sessionId))
                requireStored(session.sessionId)
            }

            SessionEnd.IN_PROGRESS -> session
        }
    }

    /** What a test does with an attempt when it ends it. */
    enum class SessionEnd { CANCELLED, COMPLETED, IN_PROGRESS }

    /**
     * The history a positive progression window needs, and the trigger Session that follows it:
     *
     * ```text
     * 09-18  slot-p12-1  cancelled, one of two sets on each of the three elements
     * 09-19  slot-p12-2  cancelled, both sets on each of the three elements
     * 09-21  slot-p12-1  the trigger: completed, both sets on each element — the completed Session
     * ```
     *
     * The trigger's own opportunity is the first slot, so the adaptive target has to be a *future* one
     * (09-22), and the two past-but-startable opportunities (09-19 `MISSED`, 09-20) must never be
     * chosen.
     */
    suspend fun seedHistoryAndTrigger(): WorkoutSession {
        attempt(slot = slot(2), at = HISTORY_ONE, setsPerOccurrence = 1, occurrences = 1)
        database.exec(
            "UPDATE `program_workout_slot` SET `status` = 'MISSED' WHERE `slotId` = '${slot(3).value}'"
        )
        attempt(slot = slot(4), at = HISTORY_TWO, setsPerOccurrence = 2)
        return attempt(
            slot = slot(2),
            at = COMPLETION_ATTEMPT,
            setsPerOccurrence = 2,
            end = SessionEnd.IN_PROGRESS
        ).also { clock.instant = COMPLETION_MOMENT }
    }

    /**
     * A second completion after [seedHistoryAndTrigger] has run: a full attempt at the opportunity the
     * history is already on, so a test can produce a **second** decision about the same future
     * opportunity — §16's supersession chain — without exceeding what the plan prescribed for the
     * window.
     */
    suspend fun secondCompletion(): WorkoutSession = attempt(
        slot = slot(4),
        at = COMPLETION_ATTEMPT,
        setsPerOccurrence = 2,
        end = SessionEnd.IN_PROGRESS
    ).also { clock.instant = COMPLETION_MOMENT }

    /** The previous window's bookkeeping, as a prior pass would have written it (§11). */
    suspend fun seedFamilyState(
        level: Int = 1,
        precedingProgressQualifyingWindows: Int = 1,
        qualifyingWindowsSinceLastChange: Int? = null,
        revision: RevisionId = RevisionId(revisionId(1))
    ): FamilyProgressionState {
        val state = FamilyProgressionState(
            revisionId = revision,
            familyId = FAMILY,
            progressionLevel = level,
            adaptationState = com.monkfitness.app.domain.adaptive.AdaptiveState.HOLD,
            currentExerciseId = "pushup",
            updatedAt = COMPLETION_MOMENT,
            precedingProgressQualifyingWindows = precedingProgressQualifyingWindows,
            qualifyingWindowsSinceLastChange = qualifyingWindowsSinceLastChange
        )
        data.programAdaptiveRepository.saveFamilyState(state)
        return state
    }

    // ---------------------------------------------------------------------------------------------
    // The pass, and the completion that carries it
    // ---------------------------------------------------------------------------------------------

    /** The adaptive pass alone: what §30 step 12 prepares, before anything is written. */
    suspend fun adapt(sessionId: SessionId): AdaptiveIntegrationResult = integration().adaptAfter(sessionId)

    /** The pass, then §27's completion transaction carrying its outcome. */
    suspend fun completeWithAdaptive(sessionId: SessionId): Pair<AdaptiveIntegrationOutcome, SessionCompletion> {
        val outcome = outcomeOf(adapt(sessionId))
        clock.instant = COMPLETION_MOMENT
        val completion = value(runtime.finishSession(sessionId, outcome.completion))
        return outcome to completion
    }

    // ---------------------------------------------------------------------------------------------
    // What the tests read back
    // ---------------------------------------------------------------------------------------------

    /** The outcome a result carries, failing the test with the reason when it carries none. */
    fun outcomeOf(result: AdaptiveIntegrationResult): AdaptiveIntegrationOutcome = when (result) {
        is AdaptiveIntegrationResult.Success -> result.outcome
        is AdaptiveIntegrationResult.InvalidData ->
            throw AssertionError("expected an outcome, got invalid data: ${result.cause.message}")

        is AdaptiveIntegrationResult.Failure ->
            throw AssertionError("expected an outcome, got a failure: ${result.cause}")
    }

    /** The gap a result reports, failing the test when it reports something else. */
    fun gapOf(result: AdaptiveIntegrationResult): AdaptiveInputGap {
        val outcome = outcomeOf(result)
        if (outcome !is AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest) {
            throw AssertionError("expected a gap, got $outcome")
        }
        return outcome.gap
    }

    /** The session as it is stored, read through a fresh repository. */
    suspend fun requireStored(sessionId: SessionId): WorkoutSession =
        data.freshSessionRepository().sessionById(sessionId)
            ?: error("no session '${sessionId.value}' is stored")

    /** One opportunity as stored, read fresh. */
    suspend fun storedSlot(slot: SlotId): WorkoutSlot =
        data.programScheduleRepository.slotById(slot) ?: error("no slot '${slot.value}' is stored")

    /** The family's state as stored, read through a fresh repository — a restart's own read. */
    suspend fun storedFamilyState(
        revision: RevisionId = RevisionId(revisionId(1))
    ): FamilyProgressionState? = data.freshAdaptiveRepository().familyState(revision, FAMILY)

    /** Every stored decision of the rig's revision, oldest first, read through a fresh repository. */
    suspend fun storedDecisions(revision: RevisionId = RevisionId(revisionId(1))) =
        data.freshAdaptiveRepository().decisionsOf(revision)

    /** Every stored adjustment of one opportunity, oldest first, read through a fresh repository. */
    suspend fun storedAdjustments(slot: SlotId) =
        data.freshAdaptiveRepository().adjustmentsOf(slot)

    /** The row count of every table the flow can write, plus the two Stage-1 adaptive tables. */
    fun counts(): Map<String, Int> = TABLES.associateWith { database.count(it) }

    /** The target slot one adaptive outcome names, or `null` when it names none. */
    fun targetSlotOf(outcome: AdaptiveIntegrationOutcome): SlotId? = when (outcome) {
        is AdaptiveIntegrationOutcome.AdaptiveApplied -> outcome.decision.slotId
        is AdaptiveIntegrationOutcome.AdaptiveFiltered -> outcome.decision.slotId
        else -> null
    }

    fun close() = data.close()

    companion object {

        const val KEY = "p12"
        const val PROGRAM = "program-p12"
        const val FAMILY = "push-family"
        const val SECOND_REVISION_SLOT_PREFIX = "slot-p12-v2-"

        fun revisionId(number: Int): String = "revision-p12-$number"

        fun dayId(number: Int): String = "day-p12-$number"

        fun planExerciseId(number: Int, position: Int): String = "plan-ex-p12-$number-$position"

        fun slotId(position: Int): String = "slot-p12-$position"

        val CREATED: Instant = Instant.parse("2026-09-01T08:00:00Z")

        /** The first history attempt: one set on the first element only — a partial exposure. */
        val HISTORY_ONE: Instant = Instant.parse("2026-09-18T07:30:00Z")

        /** The second history attempt: both sets on each of the three elements — three full exposures. */
        val HISTORY_TWO: Instant = Instant.parse("2026-09-20T07:30:00Z")

        /** When the trigger attempt starts. */
        val COMPLETION_ATTEMPT: Instant = Instant.parse("2026-09-21T07:30:00Z")

        /** When the trigger attempt is completed and the decision is taken. */
        val COMPLETION_MOMENT: Instant = Instant.parse("2026-09-21T08:20:00Z")

        /** The six opportunities' planned dates: four past (09-16..09-20), two future (09-22, 09-23). */
        val SLOT_DATES: List<LocalDate> = listOf(
            LocalDate.parse("2026-09-16"),
            LocalDate.parse("2026-09-18"),
            LocalDate.parse("2026-09-19"),
            LocalDate.parse("2026-09-20"),
            LocalDate.parse("2026-09-22"),
            LocalDate.parse("2026-09-23")
        )

        /** Every table the flow can write, plus the shipped Stage-1 adaptive pair. */
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
            "program",
            "set_log",
            "family_progression_state",
            "adaptive_decision_record",
            "user_progress"
        )

        /** The classification the rig wires: the family's three variants, and nothing else. */
        fun pushFamilies(): ExerciseFamilyClassification = ExerciseFamilyClassification { exerciseId ->
            when (exerciseId) {
                "pushup", "pike_pushup", "decline_pushup" -> FAMILY
                else -> null
            }
        }

        /**
         * The family's ladder: level 1 `pushup`, level 2 `pike_pushup`, level 3 `decline_pushup`.
         *
         * Every level prescribes two sets of eight by default, so a level move changes *which variant
         * the family is on* and adds no volume — which is what §18's guard tolerates (v1 allows no
         * automatic volume increase). [excessive] asks for the other case: level 2 prescribes three
         * sets, so the change adds one and the guard refuses it.
         */
        fun ladder(excessive: Boolean = false): ProgressionRelationProvider {
            val levelTwo = if (excessive) listOf(8, 8, 8) else listOf(8, 8)
            val relation = ProgramProgressionRelation(
                familyId = FAMILY,
                variants = listOf(
                    ProgramProgressionVariant(1, "pushup", RepPrescription(listOf(8, 8))),
                    ProgramProgressionVariant(2, "pike_pushup", RepPrescription(levelTwo)),
                    ProgramProgressionVariant(3, "decline_pushup", RepPrescription(listOf(8, 8)))
                )
            )
            return ProgressionRelationProvider { familyId -> relation.takeIf { it.familyId == familyId } }
        }

        /** A ladder whose second level adds volume, so §18 refuses the change. */
        fun excessiveLadder(): ProgressionRelationProvider = ladder(excessive = true)

        /** A ladder source that declares nothing — the production boundary's own value. */
        fun noLadder(): ProgressionRelationProvider = ProgressionRelationProvider { null }

        /** A classification that knows nothing — the production boundary's own value. */
        fun noClassification(): ExerciseFamilyClassification = ExerciseFamilyClassification { null }

        /** The rig with the ordinary ladder. */
        fun of(): ProgramAdaptiveIntegrationRig = ProgramAdaptiveIntegrationRig(excessiveLevels = false)

        /** The rig whose ladder adds volume, so the guard has something to refuse. */
        fun refusingGuard(): ProgramAdaptiveIntegrationRig =
            ProgramAdaptiveIntegrationRig(excessiveLevels = true)
    }
}

/** The value a runtime result carries, failing the test with the refusal when it carries none. */
internal fun <T> value(result: SessionRuntimeResult<T>): T = when (result) {
    is SessionRuntimeResult.Success -> result.value
    is SessionRuntimeResult.Refused ->
        throw AssertionError("expected a value, got a refusal: ${result.reason.message}")

    is SessionRuntimeResult.Failure ->
        throw AssertionError("expected a value, got a failure: ${result.cause}")
}
