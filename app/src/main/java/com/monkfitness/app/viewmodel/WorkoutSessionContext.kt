package com.monkfitness.app.viewmodel

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.repository.SessionFinalizationRequest
import java.time.LocalDate

/**
 * The program context one workout session runs under, frozen with that session at its start.
 *
 * `MainViewModel.startWorkoutSession` is the app's one authoritative "a workout has started" moment, and
 * [ActiveWorkoutConfiguration] captures this value at it — in the same critical section and from the same
 * start transition that captures the session's configuration snapshot — and never rereads it. It is the
 * other half of the session freeze:
 *
 *  * [programRevision] says which revision's family progression the session belongs to. `Start Revised
 *    Program` bumps the persisted revision and starts a new program; a session that began before it
 *    belongs to the revision it began under, and its decisions must be recorded there.
 *  * [programStartDate] is the calendar the session's stored history is interpreted against. A revised
 *    program restarts the calendar, so the same `(cycle, day)` means a different range of dates in each
 *    program — which is exactly why this value travels with the session rather than being read live.
 *  * [programCycle] is the cycle the session was presented as. It is the calendar position of the
 *    session's own day (the app resolves a date to a position with `resolveCycleAndDay`), and it is
 *    carried here for the same reason the calendar is: a later rollover or revision must not re-file a
 *    finished workout under a different cycle.
 *
 * The session's program *day* is not repeated here: it is the identity's own [WorkoutSessionIdentity.day],
 * the value the workout route and the start guard already agree on, so it is frozen by the session key
 * itself.
 *
 * @property programCycle the program cycle this session was presented as (1-based, as the existing
 *   calendar defines it).
 * @property programRevision the program revision whose family progression this session continues
 *   (`0` is the program as first started, matching `SettingsManager.PROGRAM_REVISION`).
 * @property programStartDate the day the session's program started — the calendar its stored history is
 *   interpreted against.
 */
data class WorkoutSessionContext(
    val programCycle: Int,
    val programRevision: Int,
    val programStartDate: LocalDate
) {

    init {
        require(programCycle >= 1) { "programCycle must be >= 1, was $programCycle" }
        require(programRevision >= 0) { "programRevision must be >= 0, was $programRevision" }
    }
}

/**
 * The finalization request of a session, built from the facts that session froze — or `null` when it has
 * not (yet) captured what a decision needs.
 *
 * This is the completion path's whole adaptive context decision, extracted as a pure function so it can
 * be driven on the JVM: which revision, which cycle and day, which calendar and which configuration a
 * finished session is finalized under. Every value comes from the session's own frozen state
 * ([ActiveWorkoutSession.context] and [ActiveWorkoutSession.configuration]); nothing here reads a live
 * flow, a settings store or the clock, so a revised program, a rollover or a configuration edit that
 * happened while the session was running cannot reinterpret it.
 *
 * `null` is the honest answer for a session that never captured its context or its configuration — a
 * session whose configuration read had not landed records no decision rather than an invented one.
 *
 * @param session the app's active session, exactly as its holder exposes it.
 * @param availableEquipment the equipment the session's generation could rely on, in the app's own
 *   semantics. It is the user's equipment, not part of the program context a session freezes: the session
 *   path reads it live for generation too, so a mid-session equipment change is one consistent input
 *   rather than a lifecycle reinterpretation.
 */
internal fun sessionFinalizationRequest(
    session: ActiveWorkoutSession?,
    availableEquipment: Set<Equipment>
): SessionFinalizationRequest? {
    val context = session?.context ?: return null
    val configuration = session.configuration ?: return null

    return SessionFinalizationRequest(
        programCycle = context.programCycle,
        programDay = session.identity.day,
        programRevision = context.programRevision,
        configuration = configuration,
        programStartDate = context.programStartDate,
        availableEquipment = availableEquipment
    )
}
