package com.monkfitness.app.viewmodel

import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which workout session a captured configuration belongs to.
 *
 * The two values are the app's own session identity: the pair the workout route carries and the pair
 * `MainViewModel.startWorkoutSession` already uses to decide whether it is entering a session or
 * re-entering the one that is running. Nothing else identifies a session — no timestamp, no counter —
 * so "the same session" means exactly what the rest of the app means by it.
 */
data class WorkoutSessionIdentity(
    val day: Int,
    val isPostureMobilitySession: Boolean
)

/**
 * The session that is running, and the configuration it was started with.
 *
 * @property identity the session these values belong to.
 * @property configuration the captured configuration, or `null` while the session's one configuration
 *   read is still in flight — the only moment at which a started session has no configuration yet.
 */
data class ActiveWorkoutSession(
    val identity: WorkoutSessionIdentity,
    val configuration: WorkoutConfigurationSnapshot?
)

/**
 * The configuration one workout session runs on.
 *
 * ## The boundary this type owns
 *
 * The app has exactly one authoritative moment at which a workout becomes started: the session start
 * transition, `MainViewModel.startWorkoutSession(day, mode)` — the pair of state flows the workout
 * session's generation is built from. Before it, the persisted configuration may change as often as
 * the user likes and the session that starts next simply reads the latest value. At it, the
 * configuration is captured **once**, into an immutable snapshot, and that snapshot is what the
 * session runs on from then until another session starts. After it, an edit changes the persisted
 * configuration and therefore the next session, and cannot reach this one: this holder holds no
 * configuration source, so there is nothing for a later state emission, a recomposition or a
 * navigation event to rebuild from.
 *
 * What that means concretely:
 *
 *  * a configuration edited before the start transition is the configuration the started session
 *    reports ([beginSession] reads it as part of the transition);
 *  * a configuration edited after it is not observed by that session, however many edits are made;
 *  * the repository is read exactly once per session, so a re-entry — the workout screen's start
 *    effect firing again, a recomposition, navigating back into the session — cannot create a second
 *    capture, and a started session never silently rebuilds itself;
 *  * a session that has reached its completed step keeps the snapshot it started with, because
 *    nothing outside this holder's start transition writes it;
 *  * the next session captures the configuration persisted at *its* start, which is what makes the
 *    behaviour future-only rather than "frozen forever".
 *
 * ## Concurrency
 *
 * The start transition can be entered twice for the same session (the screen's effect and a
 * recomposition, or two rapid navigations), and the read it performs suspends. The read-and-capture
 * step is therefore serialised: a second entry waits for the first, then finds the session already
 * captured and returns it without reading anything. A session's effective configuration is thus the
 * value observed when *its* start transition began, never a later edit that landed while a duplicate
 * entry was in flight.
 *
 * It holds no persistence and no Android: the caller supplies the read, so this type can be driven —
 * and its boundary proven — on the JVM.
 */
class ActiveWorkoutConfiguration {

    private val state = MutableStateFlow<ActiveWorkoutSession?>(null)

    /** Serialises the read-and-capture step so one session is captured exactly once. */
    private val capture = Mutex()

    /** The session that is running, or `null` before the first session start. */
    val activeSession: StateFlow<ActiveWorkoutSession?> = state.asStateFlow()

    /**
     * The configuration the running session runs on. A flow rather than a one-off read on purpose: a
     * session state holder is built once, at start, and re-emitted to every consumer — including the
     * workout session's own state — without any of them being able to refresh it from the store.
     */
    val effectiveConfiguration: Flow<WorkoutConfigurationSnapshot?> = state.map { session ->
        session?.configuration
    }

    /**
     * Enters [identity]'s session at the app's start transition and returns the configuration that
     * session runs on.
     *
     * The first call for a session reads [readConfiguration] once and freezes the result. Every later
     * call for the same session — a recomposition, a navigation back into it, the workout screen's
     * start effect firing again — returns the frozen snapshot and does not read anything. A call for a
     * *different* session belongs to that session: it starts a new capture, and the previous session's
     * snapshot is left untouched (the session that completed keeps what it ran on).
     *
     * @param readConfiguration reads the persisted configuration, exactly as the caller's repository
     *   exposes it. It is invoked at most once per session.
     */
    suspend fun beginSession(
        identity: WorkoutSessionIdentity,
        readConfiguration: suspend () -> ProgramConfiguration
    ): WorkoutConfigurationSnapshot = capture.withLock {
        val running = state.value
        if (running != null && running.identity == identity) {
            running.configuration?.let { captured -> return@withLock captured }
        } else {
            // A different session is starting: it runs on its own capture, whatever the previous
            // session ran on.
            state.value = ActiveWorkoutSession(identity, null)
        }

        val session = requireNotNull(state.value) { "the session start was not recorded" }
        val captured = WorkoutConfigurationSnapshot.capture(readConfiguration())
        state.value = session.copy(configuration = captured)

        captured
    }
}
