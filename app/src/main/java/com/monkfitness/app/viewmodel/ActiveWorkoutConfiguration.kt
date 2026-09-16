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
 * so "the same session" means exactly what the rest of the app means by it, and a later program
 * revision does not make the running session a different one.
 */
data class WorkoutSessionIdentity(
    val day: Int,
    val isPostureMobilitySession: Boolean
)

/**
 * The session that is running, with everything it froze at its start.
 *
 * @property identity the session these values belong to. It is the key: a re-entry into the same
 *   `(day, mode)` returns this session, whatever the live program state has become since.
 * @property context the program context (revision, calendar, cycle) captured at the session's start, or
 *   `null` while that capture is still in flight — or before it was introduced, which is why the
 *   finalization treats it as absent rather than inventing one.
 * @property configuration the captured configuration, or `null` while the session's one configuration
 *   read is still in flight — the only moment at which a started session has no configuration yet.
 */
data class ActiveWorkoutSession(
    val identity: WorkoutSessionIdentity,
    val context: WorkoutSessionContext?,
    val configuration: WorkoutConfigurationSnapshot?
)

/**
 * The facts one workout session runs on: the program context and the configuration it froze at its start.
 *
 * ## The boundary this type owns
 *
 * The app has exactly one authoritative moment at which a workout becomes started: the session start
 * transition, `MainViewModel.startWorkoutSession(day, mode)` — the pair of state flows the workout
 * session's generation is built from. Before it, the persisted configuration may change as often as
 * the user likes and the session that starts next simply reads the latest value. At it, two things are
 * captured **once**, into immutable values, and they are what the session runs on from then until another
 * session starts:
 *
 *  * the **configuration snapshot** — which exercises the session may use;
 *  * the **program context** ([WorkoutSessionContext]) — the revision, the calendar and the cycle the
 *    session belongs to.
 *
 * After it, an edit changes the persisted configuration and therefore the next session, and a
 * `Start Revised Program` changes the revision and the calendar for every session that starts later.
 * Neither can reach this one: this holder holds no configuration source and no program store, so there is
 * nothing for a later state emission, a recomposition, a navigation event or a lifecycle action to
 * rebuild from — and the completion path finalizes the session under exactly these values.
 *
 * What that means concretely:
 *
 *  * a configuration edited before the start transition is the configuration the started session
 *    reports ([beginSession] reads it as part of the transition);
 *  * a configuration edited after it is not observed by that session, however many edits are made;
 *  * a program revised while the session is running does not re-file it: the session keeps the revision
 *    and the calendar it started under, so a finished workout is never interpreted as a later program's
 *    session;
 *  * both reads happen exactly once per session, so a re-entry — the workout screen's start effect firing
 *    again, a recomposition, navigating back into the session — cannot create a second capture, and a
 *    started session never silently rebuilds itself;
 *  * a session that has reached its completed step keeps what it started with, because nothing outside
 *    this holder's start transition writes it;
 *  * the next session captures what is persisted at *its* start, which is what makes the behaviour
 *    future-only rather than "frozen forever".
 *
 * ## Concurrency
 *
 * The start transition can be entered twice for the same session (the screen's effect and a
 * recomposition, or two rapid navigations), and the reads it performs suspend. The read-and-capture
 * step is therefore serialised: a second entry waits for the first, then finds the session already
 * captured and returns it without reading anything. A session's effective configuration is thus the
 * value observed when *its* start transition began, never a later edit that landed while a duplicate
 * entry was in flight.
 *
 * It holds no persistence and no Android: the caller supplies the reads, so this type can be driven —
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
     * The first call for a session reads [readContext] and [readConfiguration] once each and freezes both
     * results. Every later call for the same session — a recomposition, a navigation back into it, the
     * workout screen's start effect firing again — returns the frozen snapshot and reads nothing, which is
     * what makes a program revision, a calendar restart or a configuration edit that lands while the
     * session is running unable to reach it. A call for a *different* session belongs to that session: it
     * starts its own capture, and the previous session's snapshot and context are left untouched (the
     * session that completed keeps what it ran on).
     *
     * @param readContext reads the program context that is live at this instant. It is invoked at most
     *   once per session, inside the same critical section as the configuration read, so the two frozen
     *   values always describe the one moment the session started at.
     * @param readConfiguration reads the persisted configuration, exactly as the caller's repository
     *   exposes it. It is invoked at most once per session.
     */
    suspend fun beginSession(
        identity: WorkoutSessionIdentity,
        readContext: () -> WorkoutSessionContext,
        readConfiguration: suspend () -> ProgramConfiguration
    ): WorkoutConfigurationSnapshot = capture.withLock {
        val running = state.value
        if (running != null && running.identity == identity) {
            val context = running.context
            val configuration = running.configuration
            if (context != null && configuration != null) return@withLock configuration
        }

        // A different session is starting: it runs on its own capture, whatever the previous session ran
        // on. A re-entry whose capture is still incomplete keeps the half it already has.
        val session = if (running != null && running.identity == identity) {
            running
        } else {
            ActiveWorkoutSession(identity, context = null, configuration = null)
        }

        val started = session.copy(
            context = session.context ?: readContext(),
            configuration = session.configuration
                ?: WorkoutConfigurationSnapshot.capture(readConfiguration())
        )
        state.value = started

        requireNotNull(started.configuration) { "the session start captured no configuration" }
    }
}
