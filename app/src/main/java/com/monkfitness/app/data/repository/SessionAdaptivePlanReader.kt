package com.monkfitness.app.data.repository

import androidx.room.withTransaction
import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.ProgramType
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import com.monkfitness.app.domain.usecase.AdaptiveSessionPlan
import com.monkfitness.app.domain.usecase.AdaptiveSessionRequest
import com.monkfitness.app.domain.usecase.AdaptiveWorkoutIntegration
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The persisted inputs of one workout session, as one value.
 *
 * Every field is something the session start already knows: the calendar position it was started on,
 * the identity of the program revision whose adaptive state applies, the configuration Task 12
 * captured for it, and the equipment the user has.
 *
 * @property programRevision the program revision whose family progression is read. It is carried
 *   separately from [programType] because persistence keys adaptive state by revision — the type is
 *   the decision's context, the revision is which row set is current.
 * @property programStartDate the calendar the stored sessions are interpreted against, exactly as the
 *   live app resolves a date to a cycle and day.
 */
data class SessionAdaptiveInputs(
    val programDay: Int,
    val programCycle: Int,
    val programType: ProgramType,
    val programRevision: Int,
    val configuration: WorkoutConfigurationSnapshot,
    val availableEquipment: Set<Equipment> = emptySet(),
    val programStartDate: LocalDate
) {

    init {
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
        require(programCycle >= 1) { "programCycle must be >= 1, was $programCycle" }
        require(programRevision >= 0) { "programRevision must be >= 0, was $programRevision" }
    }
}

/**
 * Reads one session's persisted adaptive inputs and asks Task 13's integration to plan it.
 *
 * This is the only place that joins the two readers the adaptive pipeline needs — Task 3's normalized
 * session history ([SessionHistoryAdapter]) and Task 8's current family progression
 * ([AdaptiveRepository]) — to the orchestration that turns them into a plan, and it keeps that join
 * out of the view model for the same reason those readers are adapters at all: which stored value is
 * the source of which number is a data-layer fact, not a screen's.
 *
 * It is a **reader**. It writes nothing: no family state, no decision record, no configuration and no
 * calendar. Asking for a session's plan can therefore never move a user's progression, which is what
 * makes "a recovery session does not erase what was earned" true by construction rather than by
 * review.
 *
 * ## Degradation
 *
 * A read that fails yields *no evidence* — an empty history and no family state — which the adaptive
 * engine evaluates as HOLD for every family. A storage problem can withhold progress; it can never
 * invent a progression step, and it never stops the session generating from its own configuration.
 *
 * ## Construction
 *
 * The two reads arrive as functions, exactly as `ActiveWorkoutConfiguration` takes the configuration
 * read it captures: the production wiring is [of], one call over the app's own database, and anything
 * else that can supply the two lists can drive this reader without a database at all.
 *
 * @param readHistory the session's normalized history, oldest first, for the calendar it ran against.
 * @param readFamilyStates the current progression rows of a program revision.
 * @param generator the app's workout builder, used for the family map and the equipment semantics the
 *   integration resolves candidates with.
 */
class SessionAdaptivePlanReader(
    private val readHistory: suspend (LocalDate) -> List<SessionObservation>,
    private val readFamilyStates: suspend (Int) -> List<FamilyProgressionState>,
    private val generator: WorkoutGenerator = WorkoutGenerator()
) {

    private val integration = AdaptiveWorkoutIntegration(generator)

    /** The adaptive plan of the session [inputs] describes. */
    suspend fun read(inputs: SessionAdaptiveInputs): AdaptiveSessionPlan =
        // The join is CPU work over the session's whole stored history — one generator pass per
        // planned opportunity, plus the signal calculation — so it is taken off the caller's thread.
        // The reads dispatch themselves; only the mapping and the evaluation move.
        withContext(Dispatchers.Default) {
            val history = runCatching { readHistory(inputs.programStartDate) }.getOrElse { emptyList() }

            val familyStates = runCatching { readFamilyStates(inputs.programRevision) }
                .getOrElse { emptyList() }

            integration.planSession(
                AdaptiveSessionRequest(
                    programDay = inputs.programDay,
                    programCycle = inputs.programCycle,
                    programType = inputs.programType,
                    configuration = inputs.configuration,
                    availableEquipment = inputs.availableEquipment,
                    recentSessions = history,
                    familyStates = familyStates
                )
            )
        }

    companion object {

        /**
         * The production wiring: the app's database, its two adaptive readers, and Room's own
         * transaction runner for the adapter that owns both adaptive tables.
         */
        fun of(
            database: AppDatabase,
            generator: WorkoutGenerator = WorkoutGenerator()
        ): SessionAdaptivePlanReader {
            val progressDao = database.progressDao()
            val adaptiveRepository = AdaptiveRepository(
                stateDao = database.familyProgressionStateDao(),
                historyDao = database.adaptiveDecisionHistoryDao(),
                inTransaction = { block -> database.withTransaction { block() } }
            )

            return SessionAdaptivePlanReader(
                readHistory = { programStartDate ->
                    SessionHistoryAdapter(
                        progressDao = progressDao,
                        workoutGenerator = generator,
                        programStartDate = programStartDate
                    ).observations()
                },
                readFamilyStates = { programRevision -> adaptiveRepository.familyStates(programRevision) },
                generator = generator
            )
        }
    }
}
