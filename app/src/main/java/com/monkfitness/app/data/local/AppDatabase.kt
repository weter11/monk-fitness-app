package com.monkfitness.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monkfitness.app.data.model.AdaptiveAdjustmentEntity
import com.monkfitness.app.data.model.AdaptiveDecisionRecord
import com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity
import com.monkfitness.app.data.model.AppStateEntity
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.data.model.FamilyProgressionStateEntity
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.ProgramDayEntity
import com.monkfitness.app.data.model.ProgramDayState
import com.monkfitness.app.data.model.ProgramEntity
import com.monkfitness.app.data.model.ProgramExerciseEntity
import com.monkfitness.app.data.model.ProgramPauseEntity
import com.monkfitness.app.data.model.ProgramRevisionEntity
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.model.SessionExerciseEntity
import com.monkfitness.app.data.model.SessionSnapshotEntity
import com.monkfitness.app.data.model.SessionSnapshotExerciseEntity
import com.monkfitness.app.data.model.SetLog
import com.monkfitness.app.data.model.SetLogEntity
import com.monkfitness.app.data.model.ShoppingItemEntity
import com.monkfitness.app.data.model.UserProgress
import com.monkfitness.app.data.model.WorkoutSessionEntity

@Database(
    entities = [
        UserProgress::class,
        PostureSessionProgress::class,
        SetLog::class,
        BodyWeightEntry::class,
        ProgramDayState::class,
        MealCycle::class,
        MealEntity::class,
        ShoppingItemEntity::class,
        FamilyProgressionState::class,
        AdaptiveDecisionRecord::class,
        // Program System target schema (§23). The ten entities above are the shipped ones and are
        // unchanged by this stage: the target tables are added beside them, never in place of them.
        ProgramEntity::class,
        AppStateEntity::class,
        ProgramRevisionEntity::class,
        ProgramDayEntity::class,
        ProgramExerciseEntity::class,
        ProgramWorkoutSlotEntity::class,
        WorkoutSessionEntity::class,
        SessionSnapshotEntity::class,
        SessionSnapshotExerciseEntity::class,
        SessionExerciseEntity::class,
        SetLogEntity::class,
        ProgramPauseEntity::class,
        FamilyProgressionStateEntity::class,
        AdaptiveDecisionRecordEntity::class,
        AdaptiveAdjustmentEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(AdaptiveTypeConverters::class, ProgramTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao

    abstract fun familyProgressionStateDao(): FamilyProgressionStateDao

    abstract fun adaptiveDecisionHistoryDao(): AdaptiveDecisionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `posture_session_progress` (
                        `day` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `completionDate` INTEGER NOT NULL,
                        `focusArea` TEXT NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `set_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `exerciseId` TEXT NOT NULL,
                        `repsCompleted` INTEGER NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `sessionDate` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `body_weight_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `weightKg` REAL NOT NULL,
                        `date` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_body_weight_log_date`
                    ON `body_weight_log` (`date`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_day_state` (
                        `programDay` INTEGER NOT NULL,
                        `isWorkoutDay` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `isMissed` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`programDay`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `meal_cycles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startDate` TEXT NOT NULL,
                        `durationDays` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `autoGenerated` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `meals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `cycleId` INTEGER NOT NULL,
                        `dayNumber` INTEGER NOT NULL,
                        `programDay` INTEGER NOT NULL,
                        `week` INTEGER NOT NULL,
                        `dayType` TEXT NOT NULL,
                        `mealTypeKey` TEXT NOT NULL,
                        `mealProfile` TEXT NOT NULL,
                        `templateId` TEXT NOT NULL,
                        `ingredientData` TEXT NOT NULL,
                        `calories` INTEGER NOT NULL,
                        `proteinGrams` INTEGER NOT NULL,
                        `optional` INTEGER NOT NULL,
                        FOREIGN KEY(`cycleId`) REFERENCES `meal_cycles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_meals_cycleId` ON `meals` (`cycleId`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_meals_cycleId_programDay_mealTypeKey` ON `meals` (`cycleId`, `programDay`, `mealTypeKey`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shopping_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `cycleId` INTEGER NOT NULL,
                        `ingredientKey` TEXT NOT NULL,
                        `totalAmount` INTEGER NOT NULL,
                        FOREIGN KEY(`cycleId`) REFERENCES `meal_cycles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_shopping_items_cycleId` ON `shopping_items` (`cycleId`)"
                )
            }
        }

        /**
         * Cycle-aware foundation (C1/C2): progress tables gain a cycleNumber column and a
         * composite (cycleNumber, day) primary key so each 56-day program cycle has its own
         * history. Non-destructive: legacy rows are preserved verbatim as cycle 1.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // user_progress → cycle 1
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_progress_new` (
                        `cycleNumber` INTEGER NOT NULL,
                        `day` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `completionDate` INTEGER NOT NULL,
                        `workoutType` TEXT NOT NULL,
                        PRIMARY KEY(`cycleNumber`, `day`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `user_progress_new` (`cycleNumber`, `day`, `isCompleted`, `completionDate`, `workoutType`)
                    SELECT 1, `day`, `isCompleted`, `completionDate`, `workoutType` FROM `user_progress`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `user_progress`")
                database.execSQL("ALTER TABLE `user_progress_new` RENAME TO `user_progress`")

                // posture_session_progress → cycle 1
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `posture_session_progress_new` (
                        `cycleNumber` INTEGER NOT NULL,
                        `day` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `completionDate` INTEGER NOT NULL,
                        `focusArea` TEXT NOT NULL,
                        PRIMARY KEY(`cycleNumber`, `day`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `posture_session_progress_new` (`cycleNumber`, `day`, `isCompleted`, `completionDate`, `focusArea`)
                    SELECT 1, `day`, `isCompleted`, `completionDate`, `focusArea` FROM `posture_session_progress`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `posture_session_progress`")
                database.execSQL("ALTER TABLE `posture_session_progress_new` RENAME TO `posture_session_progress`")

                // program_day_state → cycle 1 (backfilled from the live snapshot)
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_day_state_new` (
                        `cycleNumber` INTEGER NOT NULL,
                        `programDay` INTEGER NOT NULL,
                        `isWorkoutDay` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `isMissed` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`cycleNumber`, `programDay`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `program_day_state_new` (`cycleNumber`, `programDay`, `isWorkoutDay`, `isCompleted`, `isMissed`, `completedAt`)
                    SELECT 1, `programDay`, `isWorkoutDay`, `isCompleted`, `isMissed`, `completedAt` FROM `program_day_state`
                    """.trimIndent()
                )
                database.execSQL(
                    "INSERT INTO `program_day_state_new` (`cycleNumber`, `programDay`, `isWorkoutDay`, `isCompleted`, `isMissed`, `completedAt`) " +
                        "SELECT MAX(`cycleNumber`) + 1, `programDay`, `isWorkoutDay`, 0, 0, NULL FROM `program_day_state_new` GROUP BY `programDay`"
                )
                database.execSQL("DROP TABLE `program_day_state`")
                database.execSQL("ALTER TABLE `program_day_state_new` RENAME TO `program_day_state`")
            }
        }

        /**
         * Adaptive program Stage 1: adds the two adaptive tables and touches nothing else.
         *
         * Purely additive — two new tables, no row of any existing table read, rewritten or dropped,
         * so a device upgrading from version 6 keeps every progress, posture, set-log, body-weight,
         * calendar and nutrition row exactly as it was. Both tables are created empty: adaptive state
         * starts at the domain's own baseline (a family with no stored state is evaluated as
         * `notYetTracked`), so there is nothing to backfill and nothing to invent.
         *
         * The DDL is byte-equivalent to what Room generates for the two entities — column order,
         * types, nullability and keys included — because Room validates the migrated schema against
         * its own expectations on open: a hand-written table that differs only in a NOT NULL flag
         * would crash the upgrade rather than fail silently.
         *
         * Visible to the unit tests on purpose: the migration's DDL is the deployable proof that the
         * tables coexist with the existing ones, and `AdaptivePersistenceSchemaTest` executes it.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `family_progression_state` (
                        `familyId` TEXT NOT NULL,
                        `progressionLevel` INTEGER NOT NULL,
                        `currentExerciseId` TEXT,
                        `adaptationState` TEXT NOT NULL,
                        `precedingProgressQualifyingWindows` INTEGER NOT NULL,
                        `precedingRegressQualifyingWindows` INTEGER NOT NULL,
                        `precedingHighRiskWindows` INTEGER NOT NULL,
                        `recoveryQualifyingSessions` INTEGER NOT NULL,
                        `eligibleSessionsSinceLastProgressionChange` INTEGER,
                        `programRevision` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `policyVersion` INTEGER NOT NULL,
                        PRIMARY KEY(`programRevision`, `familyId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `adaptive_decision_record` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `familyId` TEXT NOT NULL,
                        `programRevision` INTEGER NOT NULL,
                        `cycleNumber` INTEGER NOT NULL,
                        `programDay` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `previousState` TEXT NOT NULL,
                        `newState` TEXT NOT NULL,
                        `actions` TEXT NOT NULL,
                        `reasonCode` TEXT NOT NULL,
                        `policyVersion` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Program System step 2 (§30): the target schema, added beside the shipped one.
         *
         * Purely additive. Fifteen tables are created and nothing else is executed: no row of any
         * existing table is read, rewritten, reinterpreted or dropped, no index is changed, and Room's
         * own `room_master_table` is left to Room (it rewrites the identity hash after a successful
         * upgrade). A device at version 7 therefore keeps every progress, posture, set-log, body-weight,
         * nutrition and Stage-1 adaptive row exactly as it was, and gains an empty Program System.
         *
         * There is deliberately **no legacy program/history migration** here. §23 states that the new
         * architecture has no dependency on `UserProgress`, `ProgramDayState`, `cycleNumber` or
         * `sessionDate` as ownership, and §30 gives the old persistence its own retirement step; so no
         * legacy row is converted into a Program, a Revision or a Session, and no placeholder is
         * invented. The two persistence models coexist until that step, which is why the target tables
         * that collide with shipped names are named explicitly: the legacy `set_log` stays `set_log` and
         * the target set log is `program_set_log`; the Stage-1 `family_progression_state` and
         * `adaptive_decision_record` stay in place, and the target pair is
         * `program_family_progression_state` and `program_adaptive_decision_record`.
         *
         * Every foreign key is explicit and every delete action follows the ownership graph of §29:
         * `ON DELETE CASCADE` for everything a Program owns, `SET NULL` for the blueprint's one
         * nullable global reference (`app_state.nextProgramId`), and `NO ACTION` for
         * `app_state.selectedProgramId` so that deleting the selected Program is refused until the
         * lifecycle has chosen the fallback rather than being silently nulled.
         *
         * The DDL is byte-equivalent to what Room generates for the fifteen entities — column order,
         * types, nullability, keys, indices and delete actions included — because Room validates the
         * migrated schema against its own expectations when it opens the database: a hand-written table
         * that differs only in a `NOT NULL` flag would crash the upgrade rather than fail silently.
         *
         * Visible to the unit tests on purpose: the migration's statements are the deployable proof of
         * the schema, and the schema suites execute them on a real SQLite engine as well as compare
         * them token for token.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program` (
                        `programId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `lifecycleStatus` TEXT NOT NULL,
                        `currentRevisionId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `plannedStartDate` TEXT,
                        `actualStartDate` INTEGER,
                        `archivedAt` INTEGER,
                        PRIMARY KEY(`programId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_state` (
                        `id` INTEGER NOT NULL,
                        `selectedProgramId` TEXT,
                        `nextProgramId` TEXT,
                        `nextProgramAutoStart` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`selectedProgramId`) REFERENCES `program`(`programId`) ON UPDATE NO ACTION ON DELETE NO ACTION ,
                        FOREIGN KEY(`nextProgramId`) REFERENCES `program`(`programId`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_app_state_selectedProgramId` ON `app_state` (`selectedProgramId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_app_state_nextProgramId` ON `app_state` (`nextProgramId`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_revision` (
                        `revisionId` TEXT NOT NULL,
                        `programId` TEXT NOT NULL,
                        `revisionNumber` INTEGER NOT NULL,
                        `mode` TEXT NOT NULL,
                        `durationType` TEXT NOT NULL,
                        `durationDays` INTEGER,
                        `scheduleType` TEXT NOT NULL,
                        `scheduleWeekdays` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`revisionId`),
                        FOREIGN KEY(`programId`) REFERENCES `program`(`programId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_program_revision_programId_revisionNumber` ON `program_revision` (`programId`, `revisionNumber`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_day` (
                        `programDayId` TEXT NOT NULL,
                        `revisionId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `name` TEXT,
                        PRIMARY KEY(`programDayId`),
                        FOREIGN KEY(`revisionId`) REFERENCES `program_revision`(`revisionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_program_day_revisionId_position` ON `program_day` (`revisionId`, `position`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_exercise` (
                        `programExerciseId` TEXT NOT NULL,
                        `programDayId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `exerciseId` TEXT NOT NULL,
                        `prescriptionDimension` TEXT NOT NULL,
                        `perSetTargets` TEXT NOT NULL,
                        `origin` TEXT NOT NULL,
                        `isPinned` INTEGER NOT NULL,
                        PRIMARY KEY(`programExerciseId`),
                        FOREIGN KEY(`programDayId`) REFERENCES `program_day`(`programDayId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_program_exercise_programDayId_position` ON `program_exercise` (`programDayId`, `position`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_workout_slot` (
                        `slotId` TEXT NOT NULL,
                        `programId` TEXT NOT NULL,
                        `revisionId` TEXT NOT NULL,
                        `programDayId` TEXT NOT NULL,
                        `plannedFor` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`slotId`),
                        FOREIGN KEY(`programId`) REFERENCES `program`(`programId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`revisionId`) REFERENCES `program_revision`(`revisionId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`programDayId`) REFERENCES `program_day`(`programDayId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_workout_slot_programId` ON `program_workout_slot` (`programId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_workout_slot_programDayId` ON `program_workout_slot` (`programDayId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_workout_slot_revisionId_plannedFor` ON `program_workout_slot` (`revisionId`, `plannedFor`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workout_session` (
                        `sessionId` TEXT NOT NULL,
                        `slotId` TEXT NOT NULL,
                        `programId` TEXT NOT NULL,
                        `revisionId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `finishedAt` INTEGER,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`slotId`) REFERENCES `program_workout_slot`(`slotId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`programId`) REFERENCES `program`(`programId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`revisionId`) REFERENCES `program_revision`(`revisionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_session_slotId` ON `workout_session` (`slotId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_session_programId_startedAt` ON `workout_session` (`programId`, `startedAt`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_session_revisionId` ON `workout_session` (`revisionId`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_snapshot` (
                        `sessionId` TEXT NOT NULL,
                        `capturedAt` INTEGER NOT NULL,
                        `plannedFor` TEXT NOT NULL,
                        `computedAt` INTEGER NOT NULL,
                        `appliedAdjustmentIds` TEXT NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `workout_session`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_snapshot_exercise` (
                        `sessionId` TEXT NOT NULL,
                        `programExerciseId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `exerciseId` TEXT NOT NULL,
                        `prescriptionDimension` TEXT NOT NULL,
                        `perSetTargets` TEXT NOT NULL,
                        PRIMARY KEY(`sessionId`, `programExerciseId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `session_snapshot`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_session_snapshot_exercise_sessionId_position` ON `session_snapshot_exercise` (`sessionId`, `position`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_exercise` (
                        `sessionExerciseId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `programExerciseId` TEXT NOT NULL,
                        `exerciseId` TEXT NOT NULL,
                        `prescriptionDimension` TEXT NOT NULL,
                        `perSetTargets` TEXT NOT NULL,
                        `skipped` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionExerciseId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `workout_session`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_session_exercise_sessionId_position` ON `session_exercise` (`sessionId`, `position`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_set_log` (
                        `setLogId` TEXT NOT NULL,
                        `sessionExerciseId` TEXT NOT NULL,
                        `setIndex` INTEGER NOT NULL,
                        `completedReps` INTEGER NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `performedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`setLogId`),
                        FOREIGN KEY(`sessionExerciseId`) REFERENCES `session_exercise`(`sessionExerciseId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_program_set_log_sessionExerciseId_setIndex` ON `program_set_log` (`sessionExerciseId`, `setIndex`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_pause` (
                        `pauseId` TEXT NOT NULL,
                        `programId` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER,
                        PRIMARY KEY(`pauseId`),
                        FOREIGN KEY(`programId`) REFERENCES `program`(`programId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_pause_programId` ON `program_pause` (`programId`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_family_progression_state` (
                        `revisionId` TEXT NOT NULL,
                        `familyId` TEXT NOT NULL,
                        `progressionLevel` INTEGER NOT NULL,
                        `adaptationState` TEXT NOT NULL,
                        `currentExerciseId` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`revisionId`, `familyId`),
                        FOREIGN KEY(`revisionId`) REFERENCES `program_revision`(`revisionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_adaptive_decision_record` (
                        `decisionId` TEXT NOT NULL,
                        `programId` TEXT NOT NULL,
                        `revisionId` TEXT NOT NULL,
                        `slotId` TEXT NOT NULL,
                        `targetScope` TEXT NOT NULL,
                        `targetId` TEXT,
                        `action` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `evidence` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `recovery` TEXT NOT NULL,
                        `decidedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`decisionId`),
                        FOREIGN KEY(`programId`) REFERENCES `program`(`programId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`revisionId`) REFERENCES `program_revision`(`revisionId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`slotId`) REFERENCES `program_workout_slot`(`slotId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_adaptive_decision_record_programId` ON `program_adaptive_decision_record` (`programId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_adaptive_decision_record_revisionId` ON `program_adaptive_decision_record` (`revisionId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_adaptive_decision_record_slotId` ON `program_adaptive_decision_record` (`slotId`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `adaptive_adjustment` (
                        `adjustmentId` TEXT NOT NULL,
                        `decisionId` TEXT NOT NULL,
                        `programId` TEXT NOT NULL,
                        `revisionId` TEXT NOT NULL,
                        `slotId` TEXT NOT NULL,
                        `programExerciseId` TEXT NOT NULL,
                        `beforeExerciseId` TEXT NOT NULL,
                        `beforePrescriptionDimension` TEXT NOT NULL,
                        `beforePerSetTargets` TEXT NOT NULL,
                        `afterExerciseId` TEXT NOT NULL,
                        `afterPrescriptionDimension` TEXT NOT NULL,
                        `afterPerSetTargets` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `supersedesAdjustmentId` TEXT,
                        PRIMARY KEY(`adjustmentId`),
                        FOREIGN KEY(`decisionId`) REFERENCES `program_adaptive_decision_record`(`decisionId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`programId`) REFERENCES `program`(`programId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`revisionId`) REFERENCES `program_revision`(`revisionId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`slotId`) REFERENCES `program_workout_slot`(`slotId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_adaptive_adjustment_decisionId` ON `adaptive_adjustment` (`decisionId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_adaptive_adjustment_programId` ON `adaptive_adjustment` (`programId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_adaptive_adjustment_revisionId` ON `adaptive_adjustment` (`revisionId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_adaptive_adjustment_slotId` ON `adaptive_adjustment` (`slotId`)"
                )
            }
        }

        /**
         * The schedule-frequency correction (Program System, PR 2.1): one additive column.
         *
         * A `FLEXIBLE_PER_WEEK` revision states a deterministic sessions-per-week frequency (§20) and the
         * domain value it stands for (`ProgramSchedule.FlexiblePerWeek`) requires that number, but the
         * version-8 table stored only the schedule *form*. A revision therefore could not be written or
         * read without losing the frequency or inventing one.
         *
         * The correction is one `ALTER TABLE ... ADD COLUMN`, purely additive: it adds nothing but a
         * nullable column, reads, rewrites, reinterprets and drops no row, touches no other table and no
         * index, and invents no default — the frequency is required for one schedule form and must stay
         * absent for the other, which the entity's own discriminator guard enforces. A device at version
         * 8 keeps every progress, posture, set-log, body-weight, nutrition, Stage-1 adaptive and Program
         * System row exactly as it was, and gains the ability to say how many sessions a week a flexible
         * schedule holds.
         *
         * Version 7 devices are not affected differently: they run the version-7 → version-8 migration
         * first and then this one, which is the chain `ProgramMigrationPreservationTest` executes.
         *
         * Visible to the unit tests on purpose: the statements are the deployable proof of the correction,
         * and the schema suites execute them on a real SQLite engine as well as compare them token for
         * token.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `program_revision` ADD COLUMN `scheduleSessionsPerWeek` INTEGER"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "monk_fitness_db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
