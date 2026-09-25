package com.monkfitness.app.data.local

/**
 * Inserts one complete, consistent Program System graph into a real SQLite database.
 *
 * The target schema has no DAO yet (§30 step 3), so the storage tests write rows with SQL — which is
 * the right instrument anyway: the tables are being tested, not a repository. Each graph is suffixed
 * with a key so two independent programs can coexist in one database, which is what makes "deleting
 * one program removed its own rows and nothing else" measurable rather than asserted.
 *
 * The rows are a legal graph, not arbitrary values: one program, its revision, its day and plan
 * element, a slot for that day, a session started for the slot with its frozen snapshot, the exercise
 * occurrence as it ran, one confirmed set, the pause interval, the revision's family progression
 * state, one applied decision and the adjustment it produced.
 *
 * The revision's schedule is a deterministic frequency, so the graph states its frequency
 * (`scheduleSessionsPerWeek`): the two facts are one schedule, and a flexible-frequency revision without
 * one is not a legal row of the current schema (§20).
 */
internal object ProgramGraphInserts {

    /** One row in every target table, all keyed by [key]. */
    fun insertCompleteProgram(
        database: SqliteTestDatabase,
        key: String,
        targetOccurrenceKey: String? = null
    ) {
        val program = "program-$key"
        val revision = "revision-$key"
        val day = "day-$key"
        val planExercise = "plan-exercise-$key"
        val slot = "slot-$key"
        val session = "session-$key"
        val sessionExercise = "session-exercise-$key"
        val decision = "decision-$key"

        database.exec(
            "INSERT INTO `program` (`programId`, `name`, `description`, `source`, `lifecycleStatus`, " +
                "`currentRevisionId`, `createdAt`, `updatedAt`, `plannedStartDate`, `actualStartDate`, " +
                "`archivedAt`) VALUES ('$program', 'Program $key', 'description $key', 'USER', " +
                "'RUNNING', '$revision', 1700000000000, 1700000000000, NULL, 1700000000000, NULL)"
        )
        database.exec(
            "INSERT INTO `program_revision` (`revisionId`, `programId`, `revisionNumber`, `mode`, " +
                "`durationType`, `durationDays`, `scheduleType`, `scheduleWeekdays`, " +
                "`scheduleSessionsPerWeek`, `createdAt`) VALUES ('$revision', '$program', 1, " +
                "'MANUAL', 'FIXED_DAYS', 30, 'FLEXIBLE_PER_WEEK', NULL, 3, 1700000000000)"
        )
        database.exec(
            "INSERT INTO `program_day` (`programDayId`, `revisionId`, `position`, `type`, `name`) " +
                "VALUES ('$day', '$revision', 1, 'TRAINING', 'Day one')"
        )
        database.exec(
            "INSERT INTO `program_exercise` (`programExerciseId`, `programDayId`, `position`, " +
                "`exerciseId`, `prescriptionDimension`, `perSetTargets`, `origin`, `isPinned`) " +
                "VALUES ('$planExercise', '$day', 1, 'pushup', 'REP_BASED', '12,10,8,6', 'GENERATED', 0)"
        )
        val slotColumns = "`plannedFor`, `status`, `completedAt`${if (targetOccurrenceKey == null) "" else ", `targetOccurrenceKey`"}"
        val slotValues = "'2026-09-18', 'PLANNED', NULL${if (targetOccurrenceKey == null) "" else ", '$targetOccurrenceKey'"}"
        database.exec(
            "INSERT INTO `program_workout_slot` (`slotId`, `programId`, `revisionId`, `programDayId`, " +
                "$slotColumns) VALUES ('$slot', '$program', '$revision', '$day', $slotValues)"
        )
        database.exec(
            "INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, " +
                "`status`, `startedAt`, `finishedAt`) VALUES ('$session', '$slot', '$program', " +
                "'$revision', 'IN_PROGRESS', 1700000000000, NULL)"
        )
        database.exec(
            "INSERT INTO `session_snapshot` (`sessionId`, `capturedAt`, `plannedFor`, `computedAt`, " +
                "`appliedAdjustmentIds`) VALUES ('$session', 1700000000000, '2026-09-18', " +
                "1699999999000, '')"
        )
        database.exec(
            "INSERT INTO `session_snapshot_exercise` (`sessionId`, `position`, `programExerciseId`, " +
                "`exerciseId`, `prescriptionDimension`, `perSetTargets`) VALUES ('$session', 1, " +
                "'$planExercise', 'pushup', 'REP_BASED', '12,10,8,6')"
        )
        database.exec(
            "INSERT INTO `session_exercise` (`sessionExerciseId`, `sessionId`, `position`, " +
                "`programExerciseId`, `exerciseId`, `prescriptionDimension`, `perSetTargets`, " +
                "`skipped`) VALUES ('$sessionExercise', '$session', 1, '$planExercise', 'pushup', " +
                "'REP_BASED', '12,10,8,6', 0)"
        )
        database.exec(
            "INSERT INTO `program_set_log` (`setLogId`, `sessionExerciseId`, `setIndex`, " +
                "`completedReps`, `durationSeconds`, `performedAt`) VALUES " +
                "('set-log-$key', '$sessionExercise', 1, 12, 0, 1700000001000)"
        )
        database.exec(
            "INSERT INTO `program_pause` (`pauseId`, `programId`, `startedAt`, `endedAt`) VALUES " +
                "('pause-$key', '$program', 1690000000000, NULL)"
        )
        database.exec(
            "INSERT INTO `program_family_progression_state` (`revisionId`, `familyId`, " +
                "`progressionLevel`, `adaptationState`, `currentExerciseId`, `updatedAt`) VALUES " +
                "('$revision', 'push-family', 3, 'HOLD', 'pushup', 1700000000000)"
        )
        database.exec(
            "INSERT INTO `program_adaptive_decision_record` (`decisionId`, `programId`, `revisionId`, " +
                "`slotId`, `targetScope`, `targetId`, `action`, `outcome`, `evidence`, `confidence`, " +
                "`recovery`, `decidedAt`) VALUES ('$decision', '$program', '$revision', '$slot', " +
                "'FAMILY', 'push-family', 'PROGRESS', 'APPLIED', 'STRONG', 'HIGH', 'FAVORABLE', " +
                "1700000002000)"
        )
        database.exec(
            "INSERT INTO `adaptive_adjustment` (`adjustmentId`, `decisionId`, `programId`, " +
                "`revisionId`, `slotId`, `programExerciseId`, `beforeExerciseId`, " +
                "`beforePrescriptionDimension`, `beforePerSetTargets`, `afterExerciseId`, " +
                "`afterPrescriptionDimension`, `afterPerSetTargets`, `createdAt`, " +
                "`supersedesAdjustmentId`) VALUES ('adjustment-$key', '$decision', '$program', " +
                "'$revision', '$slot', '$planExercise', 'pushup', 'REP_BASED', '12,10,8,6', " +
                "'knee_pushup', 'REP_BASED', '10,8,8,6', 1700000003000, NULL)"
        )
    }

    /** The single AppState row, pointing at two different programs. */
    fun insertAppState(database: SqliteTestDatabase, selected: String?, next: String?) {
        val selectedValue = selected?.let { "'$it'" } ?: "NULL"
        val nextValue = next?.let { "'$it'" } ?: "NULL"
        database.exec(
            "INSERT INTO `app_state` (`id`, `selectedProgramId`, `nextProgramId`, " +
                "`nextProgramAutoStart`) VALUES (1, $selectedValue, $nextValue, 0)"
        )
    }
}
