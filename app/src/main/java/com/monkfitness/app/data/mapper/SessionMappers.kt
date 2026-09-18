package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.SessionExerciseEntity
import com.monkfitness.app.data.model.SessionSnapshotEntity
import com.monkfitness.app.data.model.SessionSnapshotExerciseEntity
import com.monkfitness.app.data.model.SetLogEntity
import com.monkfitness.app.data.model.WorkoutSessionEntity
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.workout.EffectiveExercise
import com.monkfitness.app.domain.workout.EffectiveWorkout
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import com.monkfitness.app.domain.workout.WorkoutSession
import com.monkfitness.app.domain.workout.WorkoutSessionSnapshot

/**
 * The session rows ⇄ the session aggregate: `workout_session` + `session_snapshot` +
 * `session_snapshot_exercise` + `session_exercise` + `program_set_log`.
 *
 * This is the boundary §19 turns on, so the direction it reads in is the careful one:
 *
 *  * **a session is always loaded from its own snapshot.** The presentation the user was shown is the
 *    stored `session_snapshot` / `session_snapshot_exercise` rows and nothing else. There is no join
 *    to `program_revision`, `program_day`, `program_exercise` or `adaptive_adjustment` anywhere in
 *    this file: a workout that already happened must not be re-explained by a later revision, a later
 *    edit or a superseding adjustment, and an adjustment id captured in the snapshot is returned as
 *    captured rather than replaced by asking which adjustments are current.
 *  * **the session's identity triple is not duplicated.** The slot, Program and revision a
 *    presentation belongs to live on `workout_session`, which is where the snapshot's
 *    `EffectiveWorkout` reads them from.
 *  * **a confirmed set is a row, and no row means no set.** Set results are the stored
 *    `program_set_log` rows in set-index order. A gap or a zero row is refused loudly rather than
 *    filled in: an exercise that was not performed has no row at all (§12), so a placeholder
 *    `0`-repetition set would state work that did not happen.
 *  * **nothing is derived and nothing is invented.** No clock is read, no identity is minted, no
 *    missing snapshot is fabricated to make a session load, and no status is inferred from stamps.
 *
 * The write direction is the same aggregate: a session's rows are derived from the value, with
 * presentation order becoming the stored `position` columns and the snapshot's captured adjustment
 * ids stored in application order.
 */

/**
 * The domain session of one stored session row and every row that belongs to it.
 *
 * @throws IllegalArgumentException when the session has no stored snapshot — a session without the
 *   presentation it was started under is not loadable as a session (§19) — when the schedule of the
 *   snapshot's elements disagrees with the session (a stored element whose day is outside the
 *   revision, a set row whose occurrence is not part of this session, a mismatched session identity),
 *   or when the assembled value violates a domain invariant (sets not accumulating as `1..n`, a
 *   session exercise the snapshot did not present, a status that disagrees with its finish stamp).
 */
internal fun sessionDomain(
    session: WorkoutSessionEntity,
    snapshot: SessionSnapshotEntity?,
    snapshotExercises: List<SessionSnapshotExerciseEntity>,
    sessionExercises: List<SessionExerciseEntity>,
    setLogs: List<SetLogEntity>
): WorkoutSession {
    val captured = requireNotNull(snapshot) {
        "a stored session must keep the presentation it started under: workout_session " +
            "'${session.sessionId}' has no session_snapshot row (§19)"
    }
    val occurrenceIds = sessionExercises.map { it.sessionExerciseId }.toSet()
    val straySet = setLogs.firstOrNull { it.sessionExerciseId !in occurrenceIds }
    require(straySet == null) {
        "every confirmed set of session '${session.sessionId}' must belong to one of its " +
            "occurrences; set '${straySet?.setLogId}' names '${straySet?.sessionExerciseId}'"
    }

    val setsByOccurrence = setLogs.groupBy { it.sessionExerciseId }
    return WorkoutSession(
        sessionId = SessionId(session.sessionId),
        slotId = SlotId(session.slotId),
        programId = ProgramId(session.programId),
        revisionId = RevisionId(session.revisionId),
        snapshot = snapshotDomain(captured, snapshotExercises, session),
        status = storedToken(session.status, SessionStatus.entries, "workout_session.status"),
        startedAt = storedInstant("workout_session.startedAt", session.startedAt),
        finishedAt = session.finishedAt?.let { storedInstant("workout_session.finishedAt", it) },
        exercises = sessionExercises.sortedBy { it.position }.map { row ->
            row.toDomain(setsByOccurrence[row.sessionExerciseId].orEmpty())
        }
    )
}

/**
 * The captured presentation of one session: the snapshot row, its elements and the identity triple
 * the session row holds.
 */
internal fun snapshotDomain(
    snapshot: SessionSnapshotEntity,
    snapshotExercises: List<SessionSnapshotExerciseEntity>,
    session: WorkoutSessionEntity
): WorkoutSessionSnapshot = WorkoutSessionSnapshot(
    sessionId = SessionId(snapshot.sessionId),
    capturedAt = storedInstant("session_snapshot.capturedAt", snapshot.capturedAt),
    workout = EffectiveWorkout(
        slotId = SlotId(session.slotId),
        programId = ProgramId(session.programId),
        revisionId = RevisionId(session.revisionId),
        plannedFor = storedDate("session_snapshot.plannedFor", snapshot.plannedFor),
        computedAt = storedInstant("session_snapshot.computedAt", snapshot.computedAt),
        exercises = snapshotExercises.sortedBy { it.position }.map { element ->
            EffectiveExercise(
                programExerciseId = ProgramExerciseId(element.programExerciseId),
                exerciseId = element.exerciseId,
                prescription = prescriptionOf(
                    element.prescriptionDimension,
                    element.perSetTargets,
                    "session_snapshot_exercise '${element.sessionId}/${element.programExerciseId}'"
                )
            )
        },
        appliedAdjustmentIds = snapshot.appliedAdjustmentIds.map { AdjustmentId(it) }
    )
)

/** One stored occurrence as it ran, with the sets confirmed for it in set order. */
internal fun SessionExerciseEntity.toDomain(setLogs: List<SetLogEntity>): SessionExercise = SessionExercise(
    sessionExerciseId = SessionExerciseId(sessionExerciseId),
    programExerciseId = ProgramExerciseId(programExerciseId),
    exerciseId = exerciseId,
    prescription = prescriptionOf(
        prescriptionDimension,
        perSetTargets,
        "session_exercise '$sessionExerciseId'"
    ),
    results = setLogs.sortedBy { it.setIndex }.map { it.toDomain() },
    skipped = skipped
)

/** One confirmed set. */
internal fun SetLogEntity.toDomain(): SetResult = SetResult(
    setLogId = SetLogId(setLogId),
    setIndex = setIndex,
    completedReps = completedReps,
    durationSeconds = durationSeconds,
    performedAt = storedInstant("program_set_log.performedAt", performedAt)
)

/** One session's row, as the session value stores it. */
internal fun WorkoutSession.toEntity(): WorkoutSessionEntity = WorkoutSessionEntity(
    sessionId = sessionId.value,
    slotId = slotId.value,
    programId = programId.value,
    revisionId = revisionId.value,
    status = status.name,
    startedAt = storedMilliseconds(startedAt),
    finishedAt = finishedAt?.let { storedMilliseconds(it) }
)

/** One session's captured presentation row. */
internal fun WorkoutSession.toSnapshotEntity(): SessionSnapshotEntity = SessionSnapshotEntity(
    sessionId = sessionId.value,
    capturedAt = storedMilliseconds(snapshot.capturedAt),
    plannedFor = storedDateValue(snapshot.workout.plannedFor),
    computedAt = storedMilliseconds(snapshot.workout.computedAt),
    appliedAdjustmentIds = snapshot.workout.appliedAdjustmentIds.map { it.value }
)

/** The elements of one session's captured presentation, in presented order. */
internal fun WorkoutSession.toSnapshotExerciseEntities(): List<SessionSnapshotExerciseEntity> =
    snapshot.workout.exercises.mapIndexed { index, element ->
        SessionSnapshotExerciseEntity(
            sessionId = sessionId.value,
            programExerciseId = element.programExerciseId.value,
            position = index + 1,
            exerciseId = element.exerciseId,
            prescriptionDimension = element.prescription.dimension.name,
            perSetTargets = element.prescription.perSetTargets
        )
    }

/** The occurrences of one session as it ran, in presentation order. */
internal fun WorkoutSession.toSessionExerciseEntities(): List<SessionExerciseEntity> =
    exercises.mapIndexed { index, occurrence ->
        occurrence.toEntity(sessionId, index + 1)
    }

/** One occurrence's row, belonging to [sessionId] and holding place [position] in it. */
internal fun SessionExercise.toEntity(sessionId: SessionId, position: Int): SessionExerciseEntity =
    SessionExerciseEntity(
        sessionExerciseId = sessionExerciseId.value,
        sessionId = sessionId.value,
        position = position,
        programExerciseId = programExerciseId.value,
        exerciseId = exerciseId,
        prescriptionDimension = prescription.dimension.name,
        perSetTargets = prescription.perSetTargets,
        skipped = skipped
    )

/** One confirmed set's row, belonging to the occurrence that performed it. */
internal fun SetResult.toEntity(sessionExerciseId: SessionExerciseId): SetLogEntity = SetLogEntity(
    setLogId = setLogId.value,
    sessionExerciseId = sessionExerciseId.value,
    setIndex = setIndex,
    completedReps = completedReps,
    durationSeconds = durationSeconds,
    performedAt = storedMilliseconds(performedAt)
)
