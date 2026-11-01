package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationResult
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.SessionRuntime
import com.monkfitness.app.domain.workout.AdaptiveCompletion
import com.monkfitness.app.domain.workout.SessionRefusal
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import com.monkfitness.app.domain.workout.WorkoutSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * One occurrence as the session screen renders it: what to ask for, and what has been confirmed.
 *
 * Built from the session's own stored rows — never from the plan — so the numbers a screen shows are the
 * numbers the snapshot captured and the sets that were actually confirmed.
 */
data class SessionExerciseUi(
    /** The occurrence's identity within the session; the handle `confirmSet` takes. */
    val sessionExerciseId: String,
    val exerciseId: String,
    /** The localized display name, or `0` when the catalogue does not know the id. */
    val nameRes: Int,
    val setCount: Int,
    val completedSets: Int,
    /** Whether the occurrence is the one the screen is asking the user to perform. */
    val isCurrent: Boolean,
    val dimension: PrescriptionDimension,
    /** The prescribed target of the next set, or `0` when the occurrence is finished. */
    val nextTarget: Int
) {

    val isFinished: Boolean
        get() = completedSets >= setCount
}

/** Where the session screen is in the attempt's life. */
enum class SessionStage {

    /** The attempt is being started or restored. Nothing is presented yet. */
    LOADING,

    /** An `IN_PROGRESS` session is presented and can be worked on. */
    PRESENTING,

    COMPLETED,

    CANCELLED,

    /** Nothing can be presented, and the reason is said out loud: a refusal is not an absence. */
    UNAVAILABLE
}

/**
 * One occurrence the screen asks the user to perform.
 *
 * A value rather than a pair of loose strings, so *"which occurrence is current"* is the return value of
 * the method that decides it instead of something every caller re-derives from the session.
 */
data class SessionOccurrence(
    val sessionExerciseId: String,
    val exerciseId: String,
    val dimension: PrescriptionDimension
)

/** The whole state of one attempt's screen. */
data class ProgramSessionUiState(
    val stage: SessionStage = SessionStage.LOADING,
    val slotId: String = "",
    val sessionId: String? = null,
    val programName: String? = null,
    /** The date the opportunity was planned for — the snapshot's own fact, not a calendar. */
    val plannedFor: LocalDate? = null,
    val exercises: List<SessionExerciseUi> = emptyList(),
    /** What the user is asked to do next, or `null` when there is nothing left. */
    val currentExercise: SessionExerciseUi? = null,
    /** The rest a new interval starts from, in seconds: the user's current choice. */
    val restSeconds: Int = 0,
    /** One line per family the completion's adaptive half read, in the pass's own order. */
    val outcomes: List<SessionOutcomeUi> = emptyList(),
    /** The sentence explaining a refusal, a failure or a reported non-fatal problem. */
    val notice: ProgramSessionNotice? = null
) {

    val isPresenting: Boolean
        get() = stage == SessionStage.PRESENTING

    val everythingConfirmed: Boolean
        get() = exercises.isNotEmpty() && exercises.all { exercise -> exercise.isFinished }

    val isEnded: Boolean
        get() = stage == SessionStage.COMPLETED || stage == SessionStage.CANCELLED

    val confirmedSetCount: Int
        get() = exercises.sumOf { exercise -> exercise.completedSets }

    val prescribedSetCount: Int
        get() = exercises.sumOf { exercise -> exercise.setCount }
}

/**
 * A sentence the session screen shows about the attempt's own outcomes.
 *
 * Held apart from [ProgramNotice] on purpose: that vocabulary is the *Program* actions', and this one is
 * about one attempt — a refusal to start, a failure to read, a completion whose adaptive half could not be
 * evaluated. Merging them would make one type carry two unrelated sets of sentences.
 */
sealed interface ProgramSessionNotice {

    val messageRes: Int

    /** Something was refused by a rule or by the plan. Nothing was written. */
    data class Refused(override val messageRes: Int) : ProgramSessionNotice

    /** A read or a write failed. Never rendered as "there is nothing here". */
    data class Failed(override val messageRes: Int) : ProgramSessionNotice

    /** The attempt completed, and something about its adaptive half is worth saying. */
    data class Done(override val messageRes: Int) : ProgramSessionNotice

    companion object {

        /** The opportunity cannot be presented at all. */
        val SLOT_UNAVAILABLE: ProgramSessionNotice = Refused(R.string.programs_session_slot_unavailable)

        /** The opportunity already holds an attempt, and the refusal could not name it to restore. */
        val SLOT_ALREADY_IN_PROGRESS: ProgramSessionNotice =
            Refused(R.string.programs_session_already_in_progress)

        /** The attempt could not be read or written. */
        val STORAGE_FAILED: ProgramSessionNotice = Failed(R.string.programs_notice_failed)

        /** The completion landed, but the adaptive half could not be evaluated. */
        val COMPLETED_ADAPTIVE_UNAVAILABLE: ProgramSessionNotice =
            Done(R.string.programs_session_completed_adaptive_unavailable)
    }
}

/**
 * The rest a new interval starts from when neither the plan element nor the user states one, in seconds.
 *
 * A documented parameter of the controller rather than a number inside the screen: the screen renders a
 * countdown and owns no default of its own, so the value can be changed in one place and replaced by a
 * test.
 */
private const val DEFAULT_REST_SECONDS = 60

/** Where the session screen's family display names come from: the app's own catalogue, read through a port. */
fun interface FamilyCatalogue {

    /** Every family the catalogue names, keyed by the family id the adaptive stage uses. */
    suspend fun names(): Map<String, Int>
}

/**
 * The rest the user last chose, kept across sessions so a new workout starts from it.
 *
 * A port rather than a `SettingsManager` call, because the controller is plain Kotlin that runs on a JVM
 * and a test states the remembered value without an Android `Context`.
 */
interface SessionRestPreference {

    /** The last chosen rest in seconds, or `null` when the user never chose one. */
    suspend fun read(): Int?

    suspend fun remember(seconds: Int)
}

/**
 * The workout session screen's state holder — the target runtime's UI half.
 *
 * It owns **nothing** about starting, confirming, cancelling or finishing a workout: every one of those is
 * [SessionRuntime]'s, and this object only calls it and renders what comes back. `open` restores when an
 * attempt is already in progress, `confirmSet` takes the position from the stored rows, `finish` hands over
 * the adaptive half from the integration, and `back` calls nothing at all, because leaving the screen is
 * not a fact about the workout.
 *
 * There is no session state machine here, no set counter of its own, no "is this exercise done yet" rule
 * and no completion rule: every one of those is read back from the session the runtime returns, since a
 * second copy would be a second answer to *"what has this attempt confirmed?"*.
 *
 * `startSession` refuses with [SessionRefusal.SlotIsAlreadyBeingWorkedOut] when the opportunity already
 * holds an attempt, and that refusal **names** it — so a screen reopened on a workout the user walked away
 * from restores that attempt rather than refusing to show anything. Every other refusal and every failure
 * is surfaced as a [ProgramSessionNotice], never as an empty screen.
 *
 * `finish` asks [ProgramAdaptiveIntegration] what the completion implies and hands its [AdaptiveCompletion]
 * to the runtime, which stores it in the same transaction. When that pass cannot be read the completion
 * still happens — the workout is a fact the user performed — and the adaptive half is reported as
 * unavailable rather than silently recorded as "nothing was decided": the two are different facts, and only
 * one of them is true.
 */
class ProgramSessionController(
    private val runtime: SessionRuntime,
    private val adaptive: ProgramAdaptiveIntegration,
    private val lifecycle: ProgramLifecycleService,
    private val catalogue: ExerciseCatalogue,
    private val familyCatalogue: FamilyCatalogue,
    private val restPreference: SessionRestPreference,
    private val defaultRestSeconds: Int = DEFAULT_REST_SECONDS
) {

    private val mutableState = MutableStateFlow(ProgramSessionUiState())

    val state: StateFlow<ProgramSessionUiState> = mutableState.asStateFlow()

    /** The attempt being presented. Held because every operation after `open` is about *this* attempt. */
    private var session: WorkoutSession? = null

    /** Names resolved once from the catalogue, because the catalogue is a compile-time list. */
    private var nameByExerciseId: Map<String, Int>? = null

    /** Family display names, resolved once for the same reason as [nameByExerciseId]. */
    private var nameByFamilyId: Map<String, Int>? = null

    /** The rest the user last chose, read once when the attempt opens. */
    private var rememberedRestSeconds: Int? = null

    /**
     * Opens the attempt for [slotId]: restores the one already in progress, or starts a new one.
     *
     * Both are the runtime's operations, and nothing here decides which is due beyond reading the
     * refusal it comes back with: *"this opportunity is already being worked out, and here is the
     * attempt"* is the runtime's own answer, so the screen needs no second read to discover it.
     */
    suspend fun open(slotId: String) {
        session = null
        rememberedRestSeconds = restPreference.read()
        mutableState.value = ProgramSessionUiState(stage = SessionStage.LOADING, slotId = slotId)
        when (val started = runtime.startSession(SlotId(slotId))) {
            is SessionRuntimeResult.Success -> present(started.value)

            is SessionRuntimeResult.Refused -> when (val reason = started.reason) {
                is SessionRefusal.SlotIsAlreadyBeingWorkedOut -> {
                    val inProgress = reason.sessionId
                    if (inProgress == null) {
                        unavailable(ProgramSessionNotice.SLOT_ALREADY_IN_PROGRESS)
                    } else {
                        restore(inProgress)
                    }
                }

                else -> unavailable(ProgramSessionNotice.SLOT_UNAVAILABLE)
            }

            is SessionRuntimeResult.Failure -> unavailable(ProgramSessionNotice.STORAGE_FAILED)
        }
    }

    private suspend fun restore(sessionId: SessionId) {
        when (val restored = runtime.restoreSession(sessionId)) {
            is SessionRuntimeResult.Success -> present(restored.value)
            is SessionRuntimeResult.Refused -> unavailable(ProgramSessionNotice.SLOT_UNAVAILABLE)
            is SessionRuntimeResult.Failure -> unavailable(ProgramSessionNotice.STORAGE_FAILED)
        }
    }

    suspend fun confirmSet(completedReps: Int = 0, durationSeconds: Int = 0, restSeconds: Int? = null) {
        val stored = confirmSetInternal(completedReps, durationSeconds, restSeconds)
        if (!stored) return
    }

    /**
     * Sets the rest the next interval counts down from, and remembers it as the default of later workouts.
     *
     * The value is a countdown length, not a measurement: the rest a confirmed set stores is the time that
     * actually elapsed, which is this length unless the user skipped the interval early. Zero is legal and
     * means "no rest".
     */
    suspend fun setRestSeconds(seconds: Int) {
        val bounded = seconds.coerceAtLeast(0)
        rememberedRestSeconds = bounded
        mutableState.update { state -> state.copy(restSeconds = bounded) }
        restPreference.remember(bounded)
    }

    /**
     * Parses and confirms the value the user actually performed.
     *
     * Blank, non-integral, zero and negative values return `false` without calling [SessionRuntime].
     * The prescribed target is never used as a fallback: it remains presentation only until the user
     * explicitly confirms a valid actual result.
     */
    suspend fun confirmActualSet(rawValue: String, restSeconds: Int? = null): Boolean {
        val value = rawValue.trim().toIntOrNull() ?: return false
        if (value <= 0) return false
        val current = currentAttempt() ?: return false
        val occurrence = occurrenceToPerform(current) ?: return false
        return confirmSetInternal(
            completedReps = if (occurrence.dimension == PrescriptionDimension.TIME_BASED) 0 else value,
            durationSeconds = if (occurrence.dimension == PrescriptionDimension.TIME_BASED) value else 0,
            restSeconds = restSeconds
        )
    }

    private suspend fun confirmSetInternal(
        completedReps: Int,
        durationSeconds: Int,
        restSeconds: Int?
    ): Boolean {
        val current = currentAttempt() ?: return false
        val occurrence = occurrenceToPerform(current) ?: return false
        return when (
            val confirmed = runtime.confirmSet(
                sessionId = current.sessionId,
                sessionExerciseId = SessionExerciseId(occurrence.sessionExerciseId),
                completedReps = completedReps,
                durationSeconds = durationSeconds,
                restSeconds = restSeconds
            )
        ) {
            is SessionRuntimeResult.Success -> {
                present(confirmed.value)
                true
            }

            is SessionRuntimeResult.Refused -> {
                mutableState.update { state -> state.copy(notice = ProgramSessionNotice.SLOT_UNAVAILABLE) }
                false
            }

            is SessionRuntimeResult.Failure -> {
                mutableState.update { state -> state.copy(notice = ProgramSessionNotice.STORAGE_FAILED) }
                false
            }
        }
    }

    /** Ends the attempt as cancelled. The opportunity is left exactly as it was. */
    suspend fun cancel() {
        val current = currentAttempt() ?: return
        when (val cancelled = runtime.cancelSession(current.sessionId)) {
            is SessionRuntimeResult.Success -> mutableState.update { state ->
                state.copy(stage = SessionStage.CANCELLED)
            }

            is SessionRuntimeResult.Refused -> mutableState.update { state ->
                state.copy(notice = ProgramSessionNotice.SLOT_UNAVAILABLE)
            }

            is SessionRuntimeResult.Failure -> mutableState.update { state ->
                state.copy(notice = ProgramSessionNotice.STORAGE_FAILED)
            }
        }
    }

    /**
     * Completes the attempt: the session, the opportunity and the adaptive half, in one transaction.
     *
     * The adaptive pass is asked **before** the completion and its answer is handed over, because the
     * runtime stores it in the same unit of work. A pass that cannot be read does not stop the completion;
     * it is reported, and nothing adaptive is written.
     */
    suspend fun finish() {
        val current = currentAttempt() ?: return
        val adaptiveHalf = adaptiveHalfFor(current)
        when (val completed = runtime.finishSession(current.sessionId, adaptiveHalf.completion)) {
            is SessionRuntimeResult.Success -> {
                session = completed.value.session
                val notice = if (adaptiveHalf.evaluated) {
                    null
                } else {
                    ProgramSessionNotice.COMPLETED_ADAPTIVE_UNAVAILABLE
                }
                val outcomes = if (adaptiveHalf.evaluated) {
                    SessionOutcomeText.linesOf(adaptiveHalf.outcomes, familyNames())
                } else {
                    emptyList()
                }
                mutableState.update { state ->
                    state.copy(stage = SessionStage.COMPLETED, notice = notice, outcomes = outcomes)
                }
            }

            is SessionRuntimeResult.Refused -> mutableState.update { state ->
                state.copy(notice = ProgramSessionNotice.SLOT_UNAVAILABLE)
            }

            is SessionRuntimeResult.Failure -> mutableState.update { state ->
                state.copy(notice = ProgramSessionNotice.STORAGE_FAILED)
            }
        }
    }

    fun dismissNotice() {
        mutableState.update { state -> state.copy(notice = null) }
    }

    /**
     * Leaving the screen. Deliberately **not** an operation: no call is made, nothing is written, and the
     * attempt stays `IN_PROGRESS`, so the next open restores it.
     */
    fun back() = Unit

    private fun currentAttempt(): WorkoutSession? = session

    /**
     * The occurrence the user is on: the first of the session's own rows that is neither skipped nor
     * complete, in presentation order. `null` when every occurrence is finished.
     */
    private fun occurrenceToPerform(session: WorkoutSession): SessionOccurrence? =
        session.exercises
            .firstOrNull { exercise ->
                !exercise.skipped && exercise.results.size < exercise.prescription.setCount
            }
            ?.let { occurrence ->
                SessionOccurrence(
                    sessionExerciseId = occurrence.sessionExerciseId.value,
                    exerciseId = occurrence.exerciseId,
                    dimension = occurrence.prescription.dimension
                )
            }

    private suspend fun adaptiveHalfFor(session: WorkoutSession): AdaptiveHalf =
        when (val pass = adaptive.adaptAfter(session.sessionId)) {
            is AdaptiveIntegrationResult.Success -> AdaptiveHalf(
                AdaptiveCompletion.of(pass.outcomes.map { outcome -> outcome.completion }),
                evaluated = true,
                outcomes = pass.outcomes
            )
            is AdaptiveIntegrationResult.InvalidData ->
                AdaptiveHalf(AdaptiveCompletion.NothingDecided, evaluated = false)

            is AdaptiveIntegrationResult.Failure ->
                AdaptiveHalf(AdaptiveCompletion.NothingDecided, evaluated = false)
        }

    /**
     * What the completion's adaptive half came to, as the runtime takes it and as the screen reports it.
     *
     * [outcomes] is the pass's per-family result, kept because the completion's own value says what was
     * *written* while a family that only advanced its bookkeeping wrote nothing and still has a line.
     */
    private data class AdaptiveHalf(
        val completion: AdaptiveCompletion,
        val evaluated: Boolean,
        val outcomes: List<AdaptiveIntegrationOutcome> = emptyList()
    )

    private suspend fun present(session: WorkoutSession) {
        this.session = session
        val workout = session.snapshot.workout
        val current = occurrenceToPerform(session)
        val names = names()

        val exercises = session.exercises.map { exercise ->
            val nextSetNumber = exercise.results.size + 1
            SessionExerciseUi(
                sessionExerciseId = exercise.sessionExerciseId.value,
                exerciseId = exercise.exerciseId,
                nameRes = names[exercise.exerciseId] ?: 0,
                setCount = exercise.prescription.setCount,
                completedSets = exercise.results.size,
                isCurrent = exercise.sessionExerciseId.value == current?.sessionExerciseId,
                dimension = exercise.prescription.dimension,
                nextTarget = if (nextSetNumber <= exercise.prescription.setCount) {
                    exercise.prescription.targetForSet(nextSetNumber)
                } else {
                    0
                }
            )
        }

        mutableState.update { state ->
            state.copy(
                stage = SessionStage.PRESENTING,
                sessionId = session.sessionId.value,
                programName = programNameOf(session),
                plannedFor = workout.plannedFor,
                exercises = exercises,
                currentExercise = exercises.firstOrNull { exercise -> exercise.isCurrent },
                // The prescription vocabulary has no rest dimension, so a presented element states no
                // rest and the interval starts from the user's remembered choice or the documented default.
                restSeconds = restSecondsFor(prescribedRestSeconds = null),
                notice = null
            )
        }
    }

    /** The rest a new interval starts from: the element's own rest when it states one, else the user's, else the default. */
    private fun restSecondsFor(prescribedRestSeconds: Int?): Int =
        prescribedRestSeconds ?: rememberedRestSeconds ?: defaultRestSeconds

    /** The Program's name, or `null` when the lifecycle layer cannot read it. A label, never a rule. */
    private suspend fun programNameOf(session: WorkoutSession): String? =
        when (val read = lifecycle.program(session.programId)) {
            is ProgramOperationResult.Success -> read.value.name
            is ProgramOperationResult.Refused -> null
            is ProgramOperationResult.Failure -> null
        }

    private fun unavailable(notice: ProgramSessionNotice) {
        session = null
        mutableState.update { state ->
            state.copy(
                stage = SessionStage.UNAVAILABLE,
                exercises = emptyList(),
                currentExercise = null,
                notice = notice
            )
        }
    }

    /**
     * The catalogue's display names, read once.
     *
     * A failure to read the catalogue is **not** a failure to present the session: the ids are what the
     * plan stores, and a name this app cannot resolve is rendered from its id rather than turning the
     * workout into an unavailable one.
     */
    private suspend fun names(): Map<String, Int> {
        nameByExerciseId?.let { cached -> return cached }
        val loaded = try {
            catalogue.options().associate { option -> option.exerciseId to option.nameRes }
        } catch (failure: Throwable) {
            emptyMap()
        }
        nameByExerciseId = loaded
        return loaded
    }

    /**
     * The catalogue's family display names, read once.
     *
     * A family this build cannot name is still reported: its line is rendered without a name rather than
     * being dropped or shown under its raw id.
     */
    private suspend fun familyNames(): Map<String, Int> {
        nameByFamilyId?.let { cached -> return cached }
        val loaded = try {
            familyCatalogue.names()
        } catch (failure: Throwable) {
            emptyMap()
        }
        nameByFamilyId = loaded
        return loaded
    }
}
