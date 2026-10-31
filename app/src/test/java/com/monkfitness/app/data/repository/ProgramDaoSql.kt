package com.monkfitness.app.data.repository

/**
 * Every statement the target DAOs carry as SQL, copied verbatim, plus what the harness executes.
 *
 * The doubles drive the production queries on a real SQLite engine, and each constant here is the DAO's
 * own `@Query` literal; `ProgramDataAccessArchitectureTest` reads the DAO sources and asserts the
 * correspondence in **both** directions — every `@Query` is executed by this harness, and every statement
 * here exists in the DAO it names. A query that changes in a DAO fails that test instead of quietly being
 * exercised in its old form.
 *
 * `ProgramDao.updateProgram`, a Room `@Update`, has no literal to copy: Room generates the `UPDATE` from
 * the entity, so the harness writes it and the architecture test checks its shape (every non-key column,
 * and a `WHERE` on the primary key).
 */

internal object ProgramDaoSql {

    const val PROGRAM_DAO_PROGRAM_BY_ID = "SELECT * FROM `program` WHERE `programId` = :programId LIMIT 1"

    const val PROGRAM_DAO_PROGRAMS = "SELECT * FROM `program` ORDER BY `createdAt` ASC, `programId` ASC"

    const val PROGRAM_DAO_COUNT_PROGRAMS = "SELECT COUNT(*) FROM `program`"

    const val PROGRAM_DAO_SET_CURRENT_REVISION = "UPDATE `program` SET `currentRevisionId` = :revisionId, `updatedAt` = :updatedAt WHERE `programId` = :programId"

    const val PROGRAM_DAO_DELETE_PROGRAM = "DELETE FROM `program` WHERE `programId` = :programId"

    const val APP_STATE_DAO_STATE = "SELECT * FROM `app_state` WHERE `id` = :id LIMIT 1"

    const val PROGRAM_REVISION_DAO_REVISION_BY_ID = "SELECT * FROM `program_revision` WHERE `revisionId` = :revisionId LIMIT 1"

    const val PROGRAM_REVISION_DAO_REVISIONS_OF_PROGRAM = "SELECT * FROM `program_revision` WHERE `programId` = :programId ORDER BY `revisionNumber` ASC"

    const val PROGRAM_REVISION_DAO_COUNT_REVISIONS_OF = "SELECT COUNT(*) FROM `program_revision` WHERE `programId` = :programId"

    const val PROGRAM_DAY_DAO_DAYS_OF_REVISION = "SELECT * FROM `program_day` WHERE `revisionId` = :revisionId ORDER BY `position` ASC"

    const val PROGRAM_EXERCISE_DAO_EXERCISES_OF_REVISION = "SELECT `program_exercise`.* FROM `program_exercise` INNER JOIN `program_day` ON `program_day`.`programDayId` = `program_exercise`.`programDayId` WHERE `program_day`.`revisionId` = :revisionId ORDER BY `program_day`.`position` ASC, `program_exercise`.`position` ASC"

    const val PROGRAM_WORKOUT_SLOT_DAO_SLOT_BY_ID = "SELECT * FROM `program_workout_slot` WHERE `slotId` = :slotId LIMIT 1"

    const val PROGRAM_WORKOUT_SLOT_DAO_SLOT_BY_TARGET_OCCURRENCE_KEY = "SELECT * FROM `program_workout_slot` WHERE `programId` = :programId AND `targetOccurrenceKey` = :targetOccurrenceKey LIMIT 1"

    const val PROGRAM_WORKOUT_SLOT_DAO_SLOTS_OF_PROGRAM = "SELECT * FROM `program_workout_slot` WHERE `programId` = :programId ORDER BY `plannedFor` ASC, `slotId` ASC"

    const val PROGRAM_WORKOUT_SLOT_DAO_SLOTS_OF_REVISION = "SELECT * FROM `program_workout_slot` WHERE `revisionId` = :revisionId ORDER BY `plannedFor` ASC, `slotId` ASC"

    const val PROGRAM_WORKOUT_SLOT_DAO_SLOTS_FROM = "SELECT * FROM `program_workout_slot` WHERE `programId` = :programId AND `plannedFor` >= :fromDate AND `status` = :status ORDER BY `plannedFor` ASC, `slotId` ASC"

    const val PROGRAM_WORKOUT_SLOT_DAO_UPDATE_OUTCOME = "UPDATE `program_workout_slot` SET `status` = :status, `completedAt` = :completedAt WHERE `slotId` = :slotId"

    const val PROGRAM_WORKOUT_SLOT_DAO_COUNT_BY_STATUS = "SELECT COUNT(*) FROM `program_workout_slot` WHERE `programId` = :programId AND `status` = :status"

    const val PROGRAM_TARGET_OCCURRENCE_DAO_OCCURRENCE_OF = "SELECT * FROM `program_target_occurrence` WHERE `programId` = :programId AND `occurrenceKey` = :occurrenceKey LIMIT 1"

    /** Ordered by the stored position, nothing else. */
    const val PROGRAM_TARGET_OCCURRENCE_DAO_COMPONENTS_OF = "SELECT * FROM `program_target_occurrence_component` WHERE `programId` = :programId AND `occurrenceKey` = :occurrenceKey ORDER BY `position` ASC"

    const val PROGRAM_TARGET_OCCURRENCE_DAO_OCCURRENCES_OF_PROGRAM = "SELECT * FROM `program_target_occurrence` WHERE `programId` = :programId ORDER BY `plannedFor` ASC, `occurrenceKey` ASC"

    /**
     * `ProgramTargetScheduleSourceDao.rulesOfRevision` — ordered by the stored rule identity, so a read
     * is deterministic without any identity being derived.
     */
    const val PROGRAM_TARGET_SCHEDULE_SOURCE_DAO_RULES_OF_REVISION = "SELECT * FROM `program_target_schedule_rule` WHERE `revisionId` = :revisionId ORDER BY `ruleId` ASC"

    /** Keyed on the revision, ordered the same way. */
    const val PROGRAM_TARGET_SCHEDULE_SOURCE_DAO_BINDINGS_OF_REVISION = "SELECT * FROM `program_target_program_day_binding` WHERE `revisionId` = :revisionId ORDER BY `workoutId` ASC"

    const val WORKOUT_SESSION_DAO_SESSION_BY_ID = "SELECT * FROM `workout_session` WHERE `sessionId` = :sessionId LIMIT 1"

    const val WORKOUT_SESSION_DAO_SESSIONS_OF_SLOT = "SELECT * FROM `workout_session` WHERE `slotId` = :slotId ORDER BY `startedAt` ASC, `sessionId` ASC"

    const val WORKOUT_SESSION_DAO_SESSIONS_OF_PROGRAM = "SELECT * FROM `workout_session` WHERE `programId` = :programId ORDER BY `startedAt` ASC, `sessionId` ASC"

    const val WORKOUT_SESSION_DAO_ATTEMPTS_OF_PROGRAM = "SELECT `slotId`, `sessionId`, `startedAt` FROM `workout_session` WHERE `programId` = :programId ORDER BY `startedAt` ASC, `sessionId` ASC"

    const val WORKOUT_SESSION_DAO_ATTEMPTS_OF_REVISION = "SELECT `slotId`, `sessionId`, `startedAt` FROM `workout_session` WHERE `revisionId` = :revisionId ORDER BY `startedAt` ASC, `sessionId` ASC"

    const val WORKOUT_SESSION_DAO_UPDATE_OUTCOME = "UPDATE `workout_session` SET `status` = :status, `finishedAt` = :finishedAt WHERE `sessionId` = :sessionId"

    const val WORKOUT_SESSION_DAO_COUNT_BY_STATUS = "SELECT COUNT(*) FROM `workout_session` WHERE `programId` = :programId AND `status` = :status"

    /** The write that carries the occupancy rule. */
    const val WORKOUT_SESSION_DAO_INSERT_IF_SLOT_NOT_OCCUPIED = "INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, `status`, `startedAt`, `finishedAt`) SELECT :sessionId, :slotId, :programId, :revisionId, :status, :startedAt, :finishedAt WHERE NOT EXISTS (SELECT 1 FROM `workout_session` WHERE `slotId` = :occupiedSlotId AND `status` = :occupiedStatus)"

    /** SQLite's `changes()`: the answer about the conditional insert above. */
    const val WORKOUT_SESSION_DAO_CHANGED_ROW_COUNT = "SELECT changes()"

    const val SESSION_SNAPSHOT_DAO_SNAPSHOTS_OF = "SELECT * FROM `session_snapshot` WHERE `sessionId` IN (:sessionIds) ORDER BY `sessionId` ASC"

    const val SESSION_SNAPSHOT_EXERCISE_DAO_SNAPSHOT_EXERCISES_OF_SESSIONS = "SELECT * FROM `session_snapshot_exercise` WHERE `sessionId` IN (:sessionIds) ORDER BY `sessionId` ASC, `position` ASC"

    const val SESSION_EXERCISE_DAO_SESSION_EXERCISES_OF_SESSIONS = "SELECT * FROM `session_exercise` WHERE `sessionId` IN (:sessionIds) ORDER BY `sessionId` ASC, `position` ASC"

    const val SESSION_EXERCISE_DAO_SESSION_EXERCISE_BY_ID = "SELECT * FROM `session_exercise` WHERE `sessionExerciseId` = :sessionExerciseId LIMIT 1"

    const val PROGRAM_SET_LOG_DAO_SETS_OF_SESSION_EXERCISE = "SELECT * FROM `program_set_log` WHERE `sessionExerciseId` = :sessionExerciseId ORDER BY `setIndex` ASC"

    const val PROGRAM_SET_LOG_DAO_SETS_OF_SESSIONS = "SELECT `program_set_log`.* FROM `program_set_log` INNER JOIN `session_exercise` ON `session_exercise`.`sessionExerciseId` = `program_set_log`.`sessionExerciseId` WHERE `session_exercise`.`sessionId` IN (:sessionIds) ORDER BY `session_exercise`.`sessionId` ASC, `session_exercise`.`position` ASC, `program_set_log`.`setIndex` ASC"

    const val PROGRAM_SET_LOG_DAO_COUNT_SETS_OF_PROGRAM = "SELECT COUNT(*) FROM `program_set_log` INNER JOIN `session_exercise` ON `session_exercise`.`sessionExerciseId` = `program_set_log`.`sessionExerciseId` INNER JOIN `workout_session` ON `workout_session`.`sessionId` = `session_exercise`.`sessionId` WHERE `workout_session`.`programId` = :programId"

    const val PROGRAM_PAUSE_DAO_PAUSE_BY_ID = "SELECT * FROM `program_pause` WHERE `pauseId` = :pauseId LIMIT 1"

    const val PROGRAM_PAUSE_DAO_PAUSES_OF_PROGRAM = "SELECT * FROM `program_pause` WHERE `programId` = :programId ORDER BY `startedAt` ASC, `pauseId` ASC"

    const val PROGRAM_PAUSE_DAO_CLOSE_PAUSE = "UPDATE `program_pause` SET `endedAt` = :endedAt WHERE `pauseId` = :pauseId"

    const val PROGRAM_FAMILY_PROGRESSION_STATE_DAO_STATES_OF_REVISION = "SELECT * FROM `program_family_progression_state` WHERE `revisionId` = :revisionId ORDER BY `familyId` ASC"

    const val PROGRAM_FAMILY_PROGRESSION_STATE_DAO_STATE_OF = "SELECT * FROM `program_family_progression_state` WHERE `revisionId` = :revisionId AND `familyId` = :familyId LIMIT 1"


    const val PROGRESSION_RELATION_VARIANT_DAO_VARIANTS_OF_FAMILY = "SELECT * FROM `progression_relation_variant` WHERE `familyId` = :familyId ORDER BY `level` ASC, `exerciseId` ASC"

    const val PROGRESSION_RELATION_VARIANT_DAO_DELETE_VARIANTS_OF_FAMILY = "DELETE FROM `progression_relation_variant` WHERE `familyId` = :familyId"

    const val PROGRESSION_RELATION_VARIANT_DAO_DECLARED_FAMILY_IDS = "SELECT DISTINCT `familyId` FROM `progression_relation_variant` ORDER BY `familyId` ASC"

    const val PROGRAM_ADAPTIVE_DECISION_DAO_DECISION_BY_ID = "SELECT * FROM `program_adaptive_decision_record` WHERE `decisionId` = :decisionId LIMIT 1"

    const val PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_PROGRAM = "SELECT * FROM `program_adaptive_decision_record` WHERE `programId` = :programId ORDER BY `decidedAt` ASC, `decisionId` ASC"

    const val PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_REVISION = "SELECT * FROM `program_adaptive_decision_record` WHERE `revisionId` = :revisionId ORDER BY `decidedAt` ASC, `decisionId` ASC"

    const val PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_SLOT = "SELECT * FROM `program_adaptive_decision_record` WHERE `slotId` = :slotId ORDER BY `decidedAt` ASC, `decisionId` ASC"

    const val ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENT_BY_ID = "SELECT * FROM `adaptive_adjustment` WHERE `adjustmentId` = :adjustmentId LIMIT 1"

    const val ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_DECISION = "SELECT * FROM `adaptive_adjustment` WHERE `decisionId` = :decisionId ORDER BY `createdAt` ASC, `adjustmentId` ASC"

    const val ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_SLOT = "SELECT * FROM `adaptive_adjustment` WHERE `slotId` = :slotId ORDER BY `createdAt` ASC, `adjustmentId` ASC"

    const val ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_PROGRAM = "SELECT * FROM `adaptive_adjustment` WHERE `programId` = :programId ORDER BY `createdAt` ASC, `adjustmentId` ASC"

    const val ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_REVISION = "SELECT * FROM `adaptive_adjustment` WHERE `revisionId` = :revisionId ORDER BY `createdAt` ASC, `adjustmentId` ASC"

    /**
     * `ProgramDao.updateProgram` is a Room `@Update` with no literal to copy, so it is written here —
     * every non-key column, matched by the primary key — and the architecture test asserts its shape so a
     * hand-written write cannot silently become a table-wide one.
     */
    const val PROGRAM_DAO_UPDATE_PROGRAM = "UPDATE `program` SET `name` = ?, `description` = ?, `source` = ?, `lifecycleStatus` = ?, `currentRevisionId` = ?, `createdAt` = ?, `updatedAt` = ?, `plannedStartDate` = ?, `actualStartDate` = ?, `archivedAt` = ? WHERE `programId` = ?"

    /**
     * Every hand-written write, keyed `<Dao>.<method>`: statements that are not `@Query` reads and not
     * generated by Room from an `@Insert`/`@Update`/`@Delete`.
     *
     * `insertSessionIfSlotIsNotOccupied` is the only one whose guard is its own predicate: a conditional
     * insert has no entity to generate from, so it is declared in the DAO and copied here verbatim — and
     * it is a write, so it is listed here as well as in [ALL]. The architecture test checks both what it
     * names and what it refuses.
     */
    val WRITES: Map<String, String> = mapOf(
        "ProgramDao.updateProgram" to PROGRAM_DAO_UPDATE_PROGRAM,
        "WorkoutSessionDao.insertSessionIfSlotIsNotOccupied" to WORKOUT_SESSION_DAO_INSERT_IF_SLOT_NOT_OCCUPIED,
        // Replacing a family's ladder drops its own rows. Scoped to one family on purpose: a table-wide
        // delete would destroy every other family's declared ladder, which a per-family catalogue must
        // never do.
        "ProgressionRelationVariantDao.deleteVariantsOfFamily" to PROGRESSION_RELATION_VARIANT_DAO_DELETE_VARIANTS_OF_FAMILY,
    )

    val ALL: Map<String, String> = mapOf(
        "ProgramDao.programById" to PROGRAM_DAO_PROGRAM_BY_ID,
        "ProgramDao.programs" to PROGRAM_DAO_PROGRAMS,
        "ProgramDao.countPrograms" to PROGRAM_DAO_COUNT_PROGRAMS,
        "ProgramDao.setCurrentRevision" to PROGRAM_DAO_SET_CURRENT_REVISION,
        "ProgramDao.deleteProgram" to PROGRAM_DAO_DELETE_PROGRAM,
        "AppStateDao.state" to APP_STATE_DAO_STATE,
        "ProgramRevisionDao.revisionById" to PROGRAM_REVISION_DAO_REVISION_BY_ID,
        "ProgramRevisionDao.revisionsOfProgram" to PROGRAM_REVISION_DAO_REVISIONS_OF_PROGRAM,
        "ProgramRevisionDao.countRevisionsOf" to PROGRAM_REVISION_DAO_COUNT_REVISIONS_OF,
        "ProgramDayDao.daysOfRevision" to PROGRAM_DAY_DAO_DAYS_OF_REVISION,
        "ProgramExerciseDao.exercisesOfRevision" to PROGRAM_EXERCISE_DAO_EXERCISES_OF_REVISION,
        "ProgramWorkoutSlotDao.slotById" to PROGRAM_WORKOUT_SLOT_DAO_SLOT_BY_ID,
        "ProgramWorkoutSlotDao.slotByTargetOccurrenceKey" to PROGRAM_WORKOUT_SLOT_DAO_SLOT_BY_TARGET_OCCURRENCE_KEY,
        "ProgramWorkoutSlotDao.slotsOfProgram" to PROGRAM_WORKOUT_SLOT_DAO_SLOTS_OF_PROGRAM,
        "ProgramWorkoutSlotDao.slotsOfRevision" to PROGRAM_WORKOUT_SLOT_DAO_SLOTS_OF_REVISION,
        "ProgramWorkoutSlotDao.slotsFrom" to PROGRAM_WORKOUT_SLOT_DAO_SLOTS_FROM,
        "ProgramWorkoutSlotDao.updateOutcome" to PROGRAM_WORKOUT_SLOT_DAO_UPDATE_OUTCOME,
        "ProgramWorkoutSlotDao.countByStatus" to PROGRAM_WORKOUT_SLOT_DAO_COUNT_BY_STATUS,
        "ProgramTargetOccurrenceDao.occurrenceOf" to PROGRAM_TARGET_OCCURRENCE_DAO_OCCURRENCE_OF,
        "ProgramTargetOccurrenceDao.componentsOf" to PROGRAM_TARGET_OCCURRENCE_DAO_COMPONENTS_OF,
        "ProgramTargetOccurrenceDao.occurrencesOfProgram" to PROGRAM_TARGET_OCCURRENCE_DAO_OCCURRENCES_OF_PROGRAM,
        "ProgramTargetScheduleSourceDao.rulesOfRevision" to PROGRAM_TARGET_SCHEDULE_SOURCE_DAO_RULES_OF_REVISION,
        "ProgramTargetScheduleSourceDao.bindingsOfRevision" to PROGRAM_TARGET_SCHEDULE_SOURCE_DAO_BINDINGS_OF_REVISION,
        "WorkoutSessionDao.sessionById" to WORKOUT_SESSION_DAO_SESSION_BY_ID,
        "WorkoutSessionDao.sessionsOfSlot" to WORKOUT_SESSION_DAO_SESSIONS_OF_SLOT,
        "WorkoutSessionDao.sessionsOfProgram" to WORKOUT_SESSION_DAO_SESSIONS_OF_PROGRAM,
        "WorkoutSessionDao.attemptsOfProgram" to WORKOUT_SESSION_DAO_ATTEMPTS_OF_PROGRAM,
        "WorkoutSessionDao.attemptsOfRevision" to WORKOUT_SESSION_DAO_ATTEMPTS_OF_REVISION,
        "WorkoutSessionDao.updateOutcome" to WORKOUT_SESSION_DAO_UPDATE_OUTCOME,
        "WorkoutSessionDao.countByStatus" to WORKOUT_SESSION_DAO_COUNT_BY_STATUS,
        "WorkoutSessionDao.insertSessionIfSlotIsNotOccupied" to WORKOUT_SESSION_DAO_INSERT_IF_SLOT_NOT_OCCUPIED,
        "WorkoutSessionDao.changedRowCount" to WORKOUT_SESSION_DAO_CHANGED_ROW_COUNT,
        "SessionSnapshotDao.snapshotsOf" to SESSION_SNAPSHOT_DAO_SNAPSHOTS_OF,
        "SessionSnapshotExerciseDao.snapshotExercisesOfSessions" to SESSION_SNAPSHOT_EXERCISE_DAO_SNAPSHOT_EXERCISES_OF_SESSIONS,
        "SessionExerciseDao.sessionExercisesOfSessions" to SESSION_EXERCISE_DAO_SESSION_EXERCISES_OF_SESSIONS,
        "SessionExerciseDao.sessionExerciseById" to SESSION_EXERCISE_DAO_SESSION_EXERCISE_BY_ID,
        "ProgramSetLogDao.setsOfSessionExercise" to PROGRAM_SET_LOG_DAO_SETS_OF_SESSION_EXERCISE,
        "ProgramSetLogDao.setsOfSessions" to PROGRAM_SET_LOG_DAO_SETS_OF_SESSIONS,
        "ProgramSetLogDao.countSetsOfProgram" to PROGRAM_SET_LOG_DAO_COUNT_SETS_OF_PROGRAM,
        "ProgramPauseDao.pauseById" to PROGRAM_PAUSE_DAO_PAUSE_BY_ID,
        "ProgramPauseDao.pausesOfProgram" to PROGRAM_PAUSE_DAO_PAUSES_OF_PROGRAM,
        "ProgramPauseDao.closePause" to PROGRAM_PAUSE_DAO_CLOSE_PAUSE,
        "ProgramFamilyProgressionStateDao.statesOfRevision" to PROGRAM_FAMILY_PROGRESSION_STATE_DAO_STATES_OF_REVISION,
        "ProgramFamilyProgressionStateDao.stateOf" to PROGRAM_FAMILY_PROGRESSION_STATE_DAO_STATE_OF,
        "ProgramAdaptiveDecisionDao.decisionById" to PROGRAM_ADAPTIVE_DECISION_DAO_DECISION_BY_ID,
        "ProgramAdaptiveDecisionDao.decisionsOfProgram" to PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_PROGRAM,
        "ProgramAdaptiveDecisionDao.decisionsOfRevision" to PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_REVISION,
        "ProgramAdaptiveDecisionDao.decisionsOfSlot" to PROGRAM_ADAPTIVE_DECISION_DAO_DECISIONS_OF_SLOT,
        "AdaptiveAdjustmentDao.adjustmentById" to ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENT_BY_ID,
        "AdaptiveAdjustmentDao.adjustmentsOfDecision" to ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_DECISION,
        "AdaptiveAdjustmentDao.adjustmentsOfSlot" to ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_SLOT,
        "AdaptiveAdjustmentDao.adjustmentsOfProgram" to ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_PROGRAM,
        "AdaptiveAdjustmentDao.adjustmentsOfRevision" to ADAPTIVE_ADJUSTMENT_DAO_ADJUSTMENTS_OF_REVISION,
        // The ladder catalogue's reads. The delete is a write, so it is listed here and in [WRITES].
        "ProgressionRelationVariantDao.variantsOfFamily" to PROGRESSION_RELATION_VARIANT_DAO_VARIANTS_OF_FAMILY,
        "ProgressionRelationVariantDao.declaredFamilyIds" to PROGRESSION_RELATION_VARIANT_DAO_DECLARED_FAMILY_IDS,
        // The delete is a `@Query`, so the architecture test's "every @Query is executed" rule finds it in
        // the DAO's source — it therefore has to be in [ALL] as well as in [WRITES], as
        // `ProgramDao.deleteProgram` is.
        "ProgressionRelationVariantDao.deleteVariantsOfFamily" to PROGRESSION_RELATION_VARIANT_DAO_DELETE_VARIANTS_OF_FAMILY,
    )
}
