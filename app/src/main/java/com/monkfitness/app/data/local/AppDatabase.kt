package com.monkfitness.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monkfitness.app.data.model.AdaptiveAdjustmentEntity
import com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity
import com.monkfitness.app.data.model.AppStateEntity
import com.monkfitness.app.data.model.BodyWeightEntry
import com.monkfitness.app.data.model.FamilyProgressionStateEntity
import com.monkfitness.app.data.model.MealCycle
import com.monkfitness.app.data.model.MealEntity
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.data.model.ProgramDayEntity
import com.monkfitness.app.data.model.ProgramEntity
import com.monkfitness.app.data.model.ProgramExerciseEntity
import com.monkfitness.app.data.model.ProgramPauseEntity
import com.monkfitness.app.data.model.ProgramRevisionEntity
import com.monkfitness.app.data.model.ProgramTargetOccurrenceComponentEntity
import com.monkfitness.app.data.model.ProgramTargetOccurrenceEntity
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.model.SessionExerciseEntity
import com.monkfitness.app.data.model.SessionSnapshotEntity
import com.monkfitness.app.data.model.SessionSnapshotExerciseEntity
import com.monkfitness.app.data.model.SetLogEntity
import com.monkfitness.app.data.model.ShoppingItemEntity
import com.monkfitness.app.data.model.WorkoutSessionEntity

@Database(
    entities = [
        // The retained global tables: the posture / mobility track, the body-weight log and the
        // nutrition plan's own calendar. None of them is keyed by a Program, a cycle or a program day,
        // and all of them survive §30 step 15 unchanged — except `posture_session_progress`, whose own
        // row identity was renamed to `trackCycle` / `trackDay` by `MIGRATION_11_12`.
        PostureSessionProgress::class,
        BodyWeightEntry::class,
        MealCycle::class,
        MealEntity::class,
        ShoppingItemEntity::class,
        // The Program System's schema (§23) — the only Program architecture the app has after §30
        // step 15. The retired tables (`user_progress`, `program_day_state`, `set_log`,
        // `family_progression_state`, `adaptive_decision_record`) are no longer declared here and are
        // dropped by `MIGRATION_11_12`; nothing in this list was added to stand in for them.
        ProgramEntity::class,
        AppStateEntity::class,
        ProgramRevisionEntity::class,
        ProgramDayEntity::class,
        ProgramExerciseEntity::class,
        ProgramWorkoutSlotEntity::class,
        // §30 step 14: the target occurrence's own semantic payload — the planned date and the
        // ordered rule/workout components a slot row has no column for. Two tables because the
        // components are an ordered list, and an order that has to be recovered by parsing a
        // serialized blob is not a stored order.
        ProgramTargetOccurrenceEntity::class,
        ProgramTargetOccurrenceComponentEntity::class,
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
    version = 14,
    exportSchema = false
)
@TypeConverters(AdaptiveTypeConverters::class, ProgramTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    // The retained global accessors. `progressDao` is gone: the mixed DAO was **split** rather than
    // deleted, and the operations that served unrelated global features now live in the DAO that owns
    // them — `nutritionDao` for the meal-cycle calendar, its meals, its shopping list and the
    // body-weight log, and `postureProgressDao` for the posture / mobility track.
    abstract fun nutritionDao(): NutritionDao

    abstract fun postureProgressDao(): PostureProgressDao

    /** Settings → Full reset. Global maintenance, over the tables the reset's contract names. */
    abstract fun maintenanceDao(): MaintenanceDao

    // The Program System's DAOs (§30 step 3): one per target table, and the only Program accessors
    // there are. No target DAO reads a retired table, and no retired table is declared any more.
    abstract fun programDao(): ProgramDao

    abstract fun appStateDao(): AppStateDao

    abstract fun programRevisionDao(): ProgramRevisionDao

    abstract fun programDayDao(): ProgramDayDao

    abstract fun programExerciseDao(): ProgramExerciseDao

    abstract fun programWorkoutSlotDao(): ProgramWorkoutSlotDao

    /**
     * The target occurrence's semantic payload (§30 step 14) — the parent row and its ordered
     * component rows, read and written as one occurrence and never reconstructed from a slot.
     */
    abstract fun programTargetOccurrenceDao(): ProgramTargetOccurrenceDao

    abstract fun workoutSessionDao(): WorkoutSessionDao

    abstract fun sessionSnapshotDao(): SessionSnapshotDao

    abstract fun sessionSnapshotExerciseDao(): SessionSnapshotExerciseDao

    abstract fun sessionExerciseDao(): SessionExerciseDao

    abstract fun programSetLogDao(): ProgramSetLogDao

    abstract fun programPauseDao(): ProgramPauseDao

    abstract fun programFamilyProgressionStateDao(): ProgramFamilyProgressionStateDao

    abstract fun programAdaptiveDecisionDao(): ProgramAdaptiveDecisionDao

    abstract fun adaptiveAdjustmentDao(): AdaptiveAdjustmentDao

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

        /**
         * Version 9 → version 10: the **Goals & Focus** configuration becomes a stored structural fact
         * of a revision (§6, §8).
         *
         * Two columns are appended to `program_revision`, both nullable and both without a default:
         *
         * ```text
         * focusGoal      TEXT   the Goal, as the domain enum member's own name
         * focusTargets   TEXT   the focuses it states, as one converted deterministic value
         * ```
         *
         * Why they exist at all is §6: *goals/focus* is one of the structural changes that creates a
         * revision, so a revision that could not state its goal would not be the record of the plan it
         * describes — and §8's `CUSTOM` percentages would have nowhere to live. Why they are columns
         * and not a table is §23: a focus configuration is a handful of tokens belonging to exactly
         * one immutable revision, and a second row-shaped entity would model a relation that does not
         * exist.
         *
         * Both are nullable because SQLite cannot append a `NOT NULL` column without a default, and a
         * default is exactly what must not be stated here: a revision written before this step chose
         * no goal, and the mapper reads that absence as §8's `BALANCED` — the configuration that
         * states nothing — rather than the schema claiming the user picked something. This migration
         * therefore executes two `ALTER TABLE ... ADD COLUMN` statements and nothing else: no
         * `UPDATE`, no `INSERT`, no row is touched and no value is invented for the rows already in
         * the table.
         *
         * Version 7 and version 8 devices are not affected differently: they run the earlier steps of
         * the chain first, which is what `ProgramMigrationPreservationTest` executes on a real engine.
         *
         * Visible to the unit tests on purpose, like the two corrections above: the statements are the
         * deployable proof of the change and the schema suites compare them token for token.
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `program_revision` ADD COLUMN `focusGoal` TEXT"
                )
                database.execSQL(
                    "ALTER TABLE `program_revision` ADD COLUMN `focusTargets` TEXT"
                )
            }
        }

        /**
         * Version 10 → version 11: the adaptive stage's **window bookkeeping** becomes a stored fact of
         * a family's progression state (§15, §18, §30 step 12).
         *
         * Five columns are appended to `program_family_progression_state`, all nullable and none with a
         * default:
         *
         * ```text
         * precedingProgressQualifyingWindows  INTEGER   consecutive progression-qualifying windows
         * precedingRegressQualifyingWindows   INTEGER   the same, for the regression conditions
         * precedingRecoveryQualifyingWindows  INTEGER   the same, for §14's reduced-absorption pattern
         * qualifyingWindowsSinceLastChange    INTEGER   the cooldown position, absent when never changed
         * recoveryQualifyingWindows           INTEGER   windows completed in recovery: the exit gate
         * ```
         *
         * and one column is appended to `program_adaptive_decision_record`:
         *
         * ```text
         * reason                              TEXT      the rule that answered (ProgramAdaptiveReason)
         * ```
         *
         * The reason is stored rather than reconstructed, and that is a deliberate decision (§13): an
         * `APPLIED` row's reason is the one change token its action names, but a *filtered* one —
         * `NOT_APPLIED`, `HOLD` — has exactly the shape of any of the eighteen holds, so reading it back
         * as `AGGREGATE_LOAD_GUARD` would rest on the write rule rather than on a stored fact, and the
         * mapping from an action to a token is a property of the current vocabulary rather than of the
         * row. The full argument, and what deliberately stays unpersisted (the requested action, the
         * signals, the guard's own verdict), is in `docs/PROGRAM_ADAPTIVE_INTEGRATION.md`.
         *
         * They exist because the engine takes them as **inputs** ([ProgramAdaptiveWindow]) and no
         * window can count itself: §30 step 11 decided that a decision is a pure function of one
         * frozen window plus the caller's maintained facts, so the caller has to carry those facts
         * between windows — and a caller that carried them only in memory would lose a user's
         * confirmation count on every process restart, which is the one thing §30 step 12's brief
         * forbids ("*account for confirmation windows, recovery exit count, cooldown position without
         * silently losing them across process restart*").
         *
         * Why columns here and not a table, and why not a reconstruction from the decision trail, is
         * argued in `docs/PROGRAM_ADAPTIVE_INTEGRATION.md` (§11): the trail records only the decisions
         * the integration *keeps* (an applied change and a guard-filtered one), the per-window verdict
         * that advances a confirmation count is not a fact of any decision row, and a family's state is
         * one current row per family per revision by its own primary key — which is exactly the shape
         * of the fact being stored.
         *
         * Why nullable and defaultless: SQLite cannot append a `NOT NULL` column without a default, and
         * a default is what must not be stated here. A row written before this step records no window
         * bookkeeping, and the mapper reads that absence as the count a family with no preceding window
         * has — the same value a `DEFAULT 0` would produce, without the schema asserting that every
         * upgraded row had counted zero windows. `qualifyingWindowsSinceLastChange` in particular must
         * stay absent rather than become `0`: `null` means *"this family has never had a progression
         * change"* and the cooldown is therefore already served, while `0` would claim a change happened
         * this window.
         *
         * Version 7, 8 and 9 devices are not affected differently: they run the earlier steps of the
         * chain first, which is what `ProgramMigrationPreservationTest` executes on a real engine.
         *
         * Visible to the unit tests on purpose, like the three steps before it: the statements are the
         * deployable proof of the change and the schema suites compare them token for token.
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `program_family_progression_state` ADD COLUMN " +
                        "`precedingProgressQualifyingWindows` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `program_family_progression_state` ADD COLUMN " +
                        "`precedingRegressQualifyingWindows` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `program_family_progression_state` ADD COLUMN " +
                        "`precedingRecoveryQualifyingWindows` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `program_family_progression_state` ADD COLUMN " +
                        "`qualifyingWindowsSinceLastChange` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `program_family_progression_state` ADD COLUMN " +
                        "`recoveryQualifyingWindows` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `program_adaptive_decision_record` ADD COLUMN `reason` TEXT"
                )
            }
        }

        /**
         * §30 step 15 — the legacy schema is retired and the posture/mobility track's identity is renamed.
         *
         * ### What it drops, and why dropping is the right verb
         *
         * Five tables are removed, never converted:
         *
         * ```text
         * user_progress              the shipped 56-day program's day-level completion
         * program_day_state          its 1..56 grid, its cycle number and its missed flags
         * set_log                    its set log — the target writes `program_set_log`
         * family_progression_state   the Stage-1 adaptive generation's per-family progression
         * adaptive_decision_record   the Stage-1 adaptive generation's decision audit trail
         * ```
         *
         * The blueprint is explicit that **no legacy program or history migration is required**, so no row
         * of these becomes a target row: a `UserProgress` row is not a session, a `ProgramDayState` row is
         * not an opportunity, and a Stage-1 family level is not a target family state. Converting them
         * would be inventing a mapping the data does not support, and the resulting target rows would claim
         * a history that never happened. The destruction is deliberate, documented here and measured by
         * `ProgramLegacyRemovalMigrationTest`.
         *
         * ### What it preserves, and how
         *
         * Everything else — the Program System's own tables, `app_state`, the body-weight log, the
         * nutrition plan's calendar, meals and shopping list — is untouched: this migration issues no
         * statement against any of them.
         *
         * `posture_session_progress` is the one table that changes shape, and its **rows are copied, not
         * recreated**: `cycleNumber`/`day` become `trackCycle`/`trackDay`, which is a rename of the row's
         * identity and not a reinterpretation of it (the track keeps its own 56-day calendar and its day 1
         * is the same day). The table is rebuilt and filled from the old one rather than renamed in place,
         * because `ALTER TABLE … RENAME COLUMN` needs SQLite 3.25 and this app's `minSdk` predates it on
         * devices the app still supports; the copy is a plain column-for-column `SELECT`, so no row and no
         * value is lost or invented.
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. The retained track: the same rows under its own two column names.
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `posture_session_progress_new` (
                        `trackCycle` INTEGER NOT NULL,
                        `trackDay` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL,
                        `completionDate` INTEGER NOT NULL,
                        `focusArea` TEXT NOT NULL,
                        PRIMARY KEY(`trackCycle`, `trackDay`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `posture_session_progress_new`
                        (`trackCycle`, `trackDay`, `isCompleted`, `completionDate`, `focusArea`)
                    SELECT `cycleNumber`, `day`, `isCompleted`, `completionDate`, `focusArea`
                    FROM `posture_session_progress`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `posture_session_progress`")
                database.execSQL(
                    "ALTER TABLE `posture_session_progress_new` RENAME TO `posture_session_progress`"
                )

                // 2. The retired tables. `IF EXISTS` because a database that was created from scratch at
                //    an older version may legitimately never have held one of them.
                database.execSQL("DROP TABLE IF EXISTS `user_progress`")
                database.execSQL("DROP TABLE IF EXISTS `program_day_state`")
                database.execSQL("DROP TABLE IF EXISTS `set_log`")
                database.execSQL("DROP TABLE IF EXISTS `family_progression_state`")
                database.execSQL("DROP TABLE IF EXISTS `adaptive_decision_record`")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `program_workout_slot` ADD COLUMN `targetOccurrenceKey` TEXT"
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_program_workout_slot_programId_targetOccurrenceKey`
                    ON `program_workout_slot` (`programId`, `targetOccurrenceKey`)
                    """.trimIndent()
                )
            }
        }

        /**
         * Version 13 → version 14: a target occurrence's **semantic payload** becomes a stored fact
         * (§30 step 14).
         *
         * Until this step a persisted target slot remembered one thing about its occurrence:
         * `program_workout_slot.targetOccurrenceKey`, the occurrence's identity. The occurrence that
         * key names also carries a planned date and an ordered list of components — the rule and
         * workout identities that say *what* the occurrence is — and a slot row has no column for
         * any of them. So the semantics existed only in the value that produced the slot, and any
         * later read had to invent them: parse the key, derive identities from a plan day, or reach
         * back into the legacy scheduler. None of those is a fact about the occurrence; all three
         * are guesses dressed as reads.
         *
         * Two tables are created, and they are two because of one property:
         *
         * ```text
         * program_target_occurrence
         *   programId      TEXT   the Program it belongs to        } the membership identity,
         *   occurrenceKey  TEXT   the occurrence's own key        }  and exactly that
         *   plannedFor     TEXT   the calendar date it was planned for
         *
         * program_target_occurrence_component
         *   programId      TEXT   repeated from the parent
         *   occurrenceKey  TEXT   repeated from the parent
         *   position       INTEGER  the component's place in the presented order, from zero
         *   ruleId         TEXT   the schedule rule's own identity
         *   workoutId      TEXT   the workout's own identity
         * ```
         *
         * ### Why normalized rows and not one serialized column
         *
         * The alternative — a single `TEXT` column holding an encoded component list — was rejected
         * for three reasons, each of which is a way of losing something:
         *
         *  * **Order would stop being stored.** The presenter's order is part of the payload, and it
         *    is part of the primary key here, so it cannot be lost or contradicted. A blob would have
         *    to be *parsed* to recover the order, which means the read-back would depend on an
         *    encoding convention rather than on stored rows.
         *  * **A field would stop being a field.** `ruleId` and `workoutId` are two independent
         *    identities stored in two independent columns. Reading either one back is a column
         *    fetch, not a delimiter split.
         *  * **A component would become fabricable.** A short list, a padded list, a list with a
         *    placeholder token in it — all representable in a blob, none of them representable here.
         *    There is no nullable component row and no default identity, so a record either holds
         *    its real components or does not exist.
         *
         * ### The identity, and what this migration refuses to do
         *
         * `(programId, occurrenceKey)` **is** the primary key of the parent table, which is what makes
         * two Programs able to hold the same key independently and one Program unable to hold it
         * twice — and, because the pair is the key, what makes a stored record impossible to
         * re-point at another Program or another key by a write. This is consistent with Stage 8,
         * where the same pair became the slot's unique target identity.
         *
         * `occurrenceKey` is stored as an opaque token. Nothing in this schema, and nothing that reads
         * it, splits it, trims it, normalizes it or derives a component from its text: the key's own
         * string is not a serialization of anything. `plannedFor` and the two component identities
         * are stored beside it as their own columns precisely so that no one ever has to read the key
         * to recover them.
         *
         * ### What the migration does to existing data: nothing
         *
         * Two `CREATE TABLE` statements and one index, and **no** `UPDATE`, `INSERT`, `DELETE`,
         * `ALTER` or `RENAME`. It creates storage; it writes no row and changes no existing one.
         * There is deliberately **no** backfill of the new tables from `program_workout_slot`: a slot
         * carries no components, so any such row would have to invent them, and a row that invents
         * its own payload is worse than an absent one — a later read would return the invention
         * believing it was stored. A target occurrence that has not been persisted through this
         * stage's path simply has no semantic record yet, and its absence is honest.
         *
         * The `CASCADE` foreign keys are the ownership graph, not a convenience: an occurrence is
         * destroyed with its Program, and its components with the occurrence. That is what keeps a
         * component row from outliving the occurrence whose identity it repeats.
         *
         * Version 7 to 12 devices are not affected differently: they run the earlier steps of the
         * chain first, which is what `ProgramMigrationPreservationTest` executes on a real engine.
         *
         * Visible to the unit tests on purpose, like every step before it: the statements are the
         * deployable proof of the change and the schema suites compare them token for token.
         */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_target_occurrence` (
                        `programId` TEXT NOT NULL,
                        `occurrenceKey` TEXT NOT NULL,
                        `plannedFor` TEXT NOT NULL,
                        PRIMARY KEY(`programId`, `occurrenceKey`),
                        FOREIGN KEY(`programId`) REFERENCES `program`(`programId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_target_occurrence_component` (
                        `programId` TEXT NOT NULL,
                        `occurrenceKey` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `ruleId` TEXT NOT NULL,
                        `workoutId` TEXT NOT NULL,
                        PRIMARY KEY(`programId`, `occurrenceKey`, `position`),
                        FOREIGN KEY(`programId`, `occurrenceKey`)
                            REFERENCES `program_target_occurrence`(`programId`, `occurrenceKey`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_program_target_occurrence_component_programId_occurrenceKey`
                    ON `program_target_occurrence_component` (`programId`, `occurrenceKey`)
                    """.trimIndent()
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
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
