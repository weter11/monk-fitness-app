package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
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
 * It is built from the session's own stored rows — never from the plan — so the numbers a screen shows
 * are the numbers the snapshot captured and the sets that were actually confirmed (§19).
 */
data class SessionExerciseUi(
    /** The occurrence's identity within the session; the handle `confirmSet` takes. */
    val sessionExerciseId: String,
    /** The library key, for the exercise-details link. */
    val exerciseId: String,
    /** The localized display name, or `0` when the catalogue does not know the id. */
    val nameRes: Int,
    /** How many sets the prescription composes. */
    val setCount: Int,
    /** How many were confirmed. */
    val completedSets: Int,
    /** Whether the occurrence is the one the screen is asking the user to perform. */
    val isCurrent: Boolean,
    /** The unit of the next set's target. */
    val dimension: PrescriptionDimension,
    /** The prescribed target of the next set, or `0` when the occurrence is finished. */
    val nextTarget: Int
) {

    /** Whether every set of this occurrence was confirmed. */
    val isFinished: Boolean
        get() = completedSets >= setCount
}

/** Where the session screen is in the attempt's life. */
enum class SessionStage {

    /** The attempt is being started or restored. Nothing is presented yet. */
    LOADING,

    /** An `IN_PROGRESS` session is presented and can be worked on. */
    PRESENTING,

    /** The attempt ended as completed. */
    COMPLETED,

    /** The attempt ended as cancelled. */
    CANCELLED,

    /** Nothing can be presented, and the reason is said out loud (§28: a refusal is not an absence). */
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
    val exerciseId: String
)

/** The whole state of one attempt's screen. */
data class ProgramSessionUiState(
    val stage: SessionStage = SessionStage.LOADING,
    /** The opportunity the attempt is on. */
    val slotId: String = "",
    /** The attempt's identity, once one is stored. */
    val sessionId: String? = null,
    /** The Program's name, as the lifecycle layer reports it. */
    val programName: String? = null,
    /** The date the opportunity was planned for — the snapshot's own fact, not a calendar. */
    val plannedFor: LocalDate? = null,
    val exercises: List<SessionExerciseUi> = emptyList(),
    /** What the user is asked to do next, or `null` when there is nothing left. */
    val currentExercise: SessionExerciseUi? = null,
    /** The sentence explaining a refusal, a failure or a reported non-fatal problem (§15, §28). */
    val notice: ProgramSessionNotice? = null
) {

    /** Whether the attempt may be worked on. */
    val isPresenting: Boolean
        get() = stage == SessionStage.PRESENTING

    /** Whether every set of every occurrence was confirmed. */
    val everythingConfirmed: Boolean
        get() = exercises.isNotEmpty() && exercises.all { exercise -> exercise.isFinished }

    /** Whether the attempt ended, either way. */
    val isEnded: Boolean
        get() = stage == SessionStage.COMPLETED || stage == SessionStage.CANCELLED

    /** How many sets the attempt confirmed in total. */
    val confirmedSetCount: Int
        get() = exercises.sumOf { exercise -> exercise.completedSets }

    /** How many sets the attempt prescribed in total. */
    val prescribedSetCount: Int
        get() = exercises.sumOf { exercise -> exercise.setCount }
}

/**
 * A sentence the session screen shows about the attempt's own outcomes.
 *
 * Held apart from [ProgramNotice] on purpose: that vocabulary is the *Program* actions' (select, rename,
 * archive, import), and this one is about one attempt — a refusal to start, a failure to read, a
 * completion whose adaptive half could not be evaluated. Merging them would make one type carry two
 * unrelated sets of sentences.
 */
sealed interface ProgramSessionNotice {

    /** The localized sentence. */
    val messageRes: Int

    /** Something was refused by a rule of §19 or by the plan. Nothing was written. */
    data class Refused(override val messageRes: Int) : ProgramSessionNotice

    /** A read or a write failed. Never rendered as "there is nothing here" (§28, §33). */
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
 * The workout session screen's state holder — the target runtime's UI half.
 *
 * ### What it owns, and what it deliberately does not
 *
 * It owns **nothing** about starting, confirming, cancelling or finishing a workout. Every one of those
 * is [SessionRuntime]'s, and this object only calls it and renders what comes back:
 *
 * ```text
 * open(slotId)   startSession(slotId) — or restoreSession(id) when one is already in progress
 * confirmSet()   confirmSet(sessionId, sessionExerciseId, …) — the position comes from the stored rows
 * cancel()       cancelSession(sessionId)
 * finish()       finishSession(sessionId, adaptive) — the adaptive half from the integration
 * back()         nothing at all: leaving the screen is not a fact about the workout (§19)
 * ```
 *
 * There is no session state machine here, no set counter of its own, no "is this exercise done yet"
 * rule and no completion rule: every one of those is read back from the session the runtime returns. A
 * second copy would be a second answer to *"what has this attempt confirmed?"*.
 *
 * ### Why the refusal path is written out rather than hidden
 *
 * `startSession` refuses with [SessionRefusal.SlotIsAlreadyBeingWorkedOut] when the opportunity already
 * holds an attempt — and that refusal **names** it. A screen reopened on a workout the user walked away
 * from therefore restores that attempt rather than refusing to show anything, which is §19's *"the
 * attempt is persisted runtime state"* read from the UI's side. Every other refusal and every failure is
 * surfaced as a [ProgramSessionNotice], never as an empty screen.
 *
 * ### The adaptive half of a completion
 *
 * `finish` asks [ProgramAdaptiveIntegration] what the completion implies and hands its
 * [AdaptiveCompletion] to the runtime, which stores it inside §27's one transaction. When that pass
 * cannot be read, the completion still happens — the workout is a fact the user performed — and the
 * adaptive half is reported as unavailable rather than silently recorded as "nothing was decided": the
 * two are different facts, and only one of them is true (§12, §15).
 *
 * @param runtime §30 step 8's runtime. The only thing that starts, confirms, cancels or finishes.
 * @param adaptive §30 step 12's integration, asked once per completion for the adaptive half.
 * @param lifecycle read for the Program's name, so the screen can say which program is being worked on.
 * @param catalogue the app's own exercise catalogue, read for display names only.
 */
class ProgramSessionController(
    private val runtime: SessionRuntime,
    private val adaptive: ProgramAdaptiveIntegration,
    private val lifecycle: ProgramLifecycleService,
    private val catalogue: ExerciseCatalogue
) {

    private val mutableState = MutableStateFlow(ProgramSessionUiState())

    /** The screen's state. */
    val state: StateFlow<ProgramSessionUiState> = mutableState.asStateFlow()

    /** The attempt being presented. Held because every operation after `open` is about *this* attempt. */
    private var session: WorkoutSession? = null

    /** Names resolved once from the catalogue, because the catalogue is a compile-time list. */
    private var nameByExerciseId: Map<String, Int>? = null

    // ---------------------------------------------------------------- opening

    /**
     * Opens the attempt for [slotId]: restores the one already in progress, or starts a new one.
     *
     * Both are the runtime's operations, and nothing here decides which is due beyond reading the
     * refusal it comes back with: *"this opportunity is already being worked out, and here is the
     * attempt"* is the runtime's own answer, so the screen needs no second read to discover it.
     */
    suspend fun open(slotId: String) {
        session = null
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

    // ---------------------------------------------------------------- the operations

    /**
     * Confirms one set of the current occurrence.
     *
     * @param completedReps repetitions performed, for an occurrence prescribed in repetitions.
     * @param durationSeconds seconds performed, for an occurrence prescribed in time.
     */
    suspend fun confirmSet(completedReps: Int = 0, durationSeconds: Int = 0) {
        val current = currentAttempt() ?: return
        val occurrence = occurrenceToPerform(current) ?: return
        when (
            val confirmed = runtime.confirmSet(
                sessionId = current.sessionId,
                sessionExerciseId = SessionExerciseId(occurrence.sessionExerciseId),
                completedReps = completedReps,
                durationSeconds = durationSeconds
            )
        ) {
            is SessionRuntimeResult.Success -> present(confirmed.value)

            is SessionRuntimeResult.Refused -> mutableState.update { state ->
                state.copy(notice = ProgramSessionNotice.SLOT_UNAVAILABLE)
            }

            is SessionRuntimeResult.Failure -> mutableState.update { state ->
                state.copy(notice = ProgramSessionNotice.STORAGE_FAILED)
            }
        }
    }

    /** Ends the attempt as cancelled. The opportunity is left exactly as it was (§19). */
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
     * runtime stores it in the same unit of work (§27). A pass that cannot be read does not stop the
     * completion; it is reported, and nothing adaptive is written.
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
                mutableState.update { state ->
                    state.copy(stage = SessionStage.COMPLETED, notice = notice)
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

    /** Clears the sentence the screen is showing. */
    fun dismissNotice() {
        mutableState.update { state -> state.copy(notice = null) }
    }

    /**
     * Leaving the screen. It is deliberately **not** an operation: no call is made, nothing is written,
     * and the attempt stays `IN_PROGRESS`, so the next open restores it (§19).
     */
    fun back() = Unit

    // ---------------------------------------------------------------- reading the attempt

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
                    exerciseId = occurrence.exerciseId
                )
            }

    private suspend fun adaptiveHalfFor(session: WorkoutSession): AdaptiveHalf =
        when (val pass = adaptive.adaptAfter(session.sessionId)) {
            is AdaptiveIntegrationResult.Success -> AdaptiveHalf(pass.outcome.completion, evaluated = true)
            is AdaptiveIntegrationResult.InvalidData ->
                AdaptiveHalf(AdaptiveCompletion.NothingDecided, evaluated = false)

            is AdaptiveIntegrationResult.Failure ->
                AdaptiveHalf(AdaptiveCompletion.NothingDecided, evaluated = false)
        }

    private data class AdaptiveHalf(val completion: AdaptiveCompletion, val evaluated: Boolean)

    // ---------------------------------------------------------------- presenting

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
                notice = null
            )
        }
    }

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
     * plan stores (§10), and a name this app cannot resolve is rendered from its id rather than turning
     * the workout into an unavailable one.
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
}
