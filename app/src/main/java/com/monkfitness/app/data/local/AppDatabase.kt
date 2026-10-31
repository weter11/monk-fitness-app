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
import com.monkfitness.app.data.model.ProgramTargetProgramDayBindingEntity
import com.monkfitness.app.data.model.ProgramTargetScheduleRuleEntity
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.model.ProgressionRelationVariantEntity
import com.monkfitness.app.data.model.SessionExerciseEntity
import com.monkfitness.app.data.model.SessionSnapshotEntity
import com.monkfitness.app.data.model.SessionSnapshotExerciseEntity
import com.monkfitness.app.data.model.SetLogEntity
import com.monkfitness.app.data.model.ShoppingItemEntity
import com.monkfitness.app.data.model.WorkoutSessionEntity

@Database(
    entities = [
        // The retained global tables: the posture / mobility track, the body-weight log and the
        // nutrition plan's own calendar. None of them is keyed by a Program, a cycle or a program day;
        // `posture_session_progress`'s row identity was renamed to `trackCycle` / `trackDay` by
        // `MIGRATION_11_12`.
        PostureSessionProgress::class,
        BodyWeightEntry::class,
        MealCycle::class,
        MealEntity::class,
        ShoppingItemEntity::class,
        // The Program System's schema — the only Program architecture the app has. The retired tables
        // (`user_progress`, `program_day_state`, `set_log`, `family_progression_state`,
        // `adaptive_decision_record`) are no longer declared and are dropped by `MIGRATION_11_12`;
        // nothing in this list was added to stand in for them.
        ProgramEntity::class,
        AppStateEntity::class,
        ProgramRevisionEntity::class,
        ProgramDayEntity::class,
        ProgramExerciseEntity::class,
        ProgramWorkoutSlotEntity::class,
        // The target occurrence's own semantic payload — the planned date and the ordered rule/workout
        // components a slot row has no column for. Two tables because the components are an ordered
        // list, and an order that has to be recovered by parsing a serialized blob is not a stored
        // order.
        ProgramTargetOccurrenceEntity::class,
        ProgramTargetOccurrenceComponentEntity::class,
        // The revision-owned **explicit target schedule source**: the rules a revision states (each
        // with its cadence form, that form's own payload and its anchor date) and the explicit
        // `workoutId -> ProgramDayId` bindings beside them. Two tables rather than columns on
        // `program_revision`, because the values are a *list* whose order and membership are the
        // contract.
        ProgramTargetScheduleRuleEntity::class,
        ProgramTargetProgramDayBindingEntity::class,
        // The app-owned **progression relation catalogue** — the persisted definition of each family's
        // declared ladder. Deliberately NOT revision-owned: a relation is keyed by `familyId` alone and
        // its levels are ordinals inside that one family, never comparable across families, so scoping
        // it to a Program or a revision would restate one ladder per plan and invite exactly the
        // cross-family comparison the domain forbids. No index is declared either — the
        // `(familyId, exerciseId)` primary key's leading column is the only column it is read by.
        ProgressionRelationVariantEntity::class,
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
    version = 18,
    exportSchema = false
)
@TypeConverters(AdaptiveTypeConverters::class, ProgramTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nutritionDao(): NutritionDao

    abstract fun postureProgressDao(): PostureProgressDao

    /** Settings → Full reset. Global maintenance, over the tables the reset's contract names. */
    abstract fun maintenanceDao(): MaintenanceDao

    abstract fun programDao(): ProgramDao

    abstract fun appStateDao(): AppStateDao

    abstract fun programRevisionDao(): ProgramRevisionDao

    abstract fun programDayDao(): ProgramDayDao

    abstract fun programExerciseDao(): ProgramExerciseDao

    abstract fun programWorkoutSlotDao(): ProgramWorkoutSlotDao

    /**
     * The target occurrence's semantic payload — the parent row and its ordered component rows, read
     * and written as one occurrence and never reconstructed from a slot.
     */
    abstract fun programTargetOccurrenceDao(): ProgramTargetOccurrenceDao

    /**
     * A revision's explicit target schedule source: its stated target rules and its stated
     * `workoutId -> ProgramDayId` bindings, read and written as one immutable unit.
     */
    abstract fun programTargetScheduleSourceDao(): ProgramTargetScheduleSourceDao

    /**
     * The app-owned progression relation catalogue: the variants each family declares, at the positions
     * it declares them.
     *
     * A separate accessor from the adaptive DAO on purpose — see
     * [com.monkfitness.app.data.repository.ProgressionRelationRepository] for why a ladder is a
     * definition and not adaptive history.
     */
    abstract fun progressionRelationVariantDao(): ProgressionRelationVariantDao

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
         * Cycle-aware foundation: the progress tables gain a cycleNumber column and a composite
         * (cycleNumber, day) primary key so each 56-day cycle has its own history. Non-destructive:
         * legacy rows are preserved verbatim as cycle 1.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
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
         * Adds the two adaptive tables and touches nothing else.
         *
         * Purely additive — two new tables, no row of any existing table read, rewritten or dropped,
         * so a device upgrading from version 6 keeps every existing row exactly as it was. Both tables
         * are created empty: adaptive state starts at the domain's own baseline (a family with no
         * stored state is evaluated as `notYetTracked`), so there is nothing to backfill and nothing to
         * invent.
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
         * Program System step 2: the target schema, added beside the shipped one.
         *
         * Purely additive. Fifteen tables are created and nothing else is executed: no row of any
         * existing table is read, rewritten, reinterpreted or dropped, no index is changed, and Room's
         * own `room_master_table` is left to Room (it rewrites the identity hash after a successful
         * upgrade). A device at version 7 gains an empty Program System and keeps every existing row.
         *
         * There is deliberately **no legacy program/history migration** here: the new architecture has
         * no dependency on `UserProgress`, `ProgramDayState`, `cycleNumber` or `sessionDate` as
         * ownership, and the old persistence is retired in its own step — so no legacy row is converted
         * into a Program, a Revision or a Session, and no placeholder is invented. That is also why the
         * target tables that collide with shipped names are named explicitly: the legacy `set_log` stays
         * `set_log` and the target set log is `program_set_log`; the Stage-1
         * `family_progression_state` and `adaptive_decision_record` stay in place, and the target pair
         * is `program_family_progression_state` and `program_adaptive_decision_record`.
         *
         * Every foreign key is explicit and every delete action follows the ownership graph:
         * `ON DELETE CASCADE` for everything a Program owns, `SET NULL` for the blueprint's one nullable
         * global reference (`app_state.nextProgramId`), and `NO ACTION` for `app_state.selectedProgramId`
         * so that deleting the selected Program is refused until the lifecycle has chosen the fallback
         * rather than being silently nulled.
         *
         * The DDL is byte-equivalent to what Room generates for the fifteen entities — column order,
         * types, nullability, keys, indices and delete actions included — because Room validates the
         * migrated schema against its own expectations when it opens the database: a hand-written table
         * that differs only in a `NOT NULL` flag would crash the upgrade rather than fail silently.
         *
         * Visible to the unit tests on purpose: the migration's statements are the deployable proof of
         * the schema, and the schema suites execute them on a real SQLite engine.
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
         * The schedule-frequency correction: one additive column.
         *
         * A `FLEXIBLE_PER_WEEK` revision states a deterministic sessions-per-week frequency and the
         * domain value it stands for (`ProgramSchedule.FlexiblePerWeek`) requires that number, but the
         * version-8 table stored only the schedule *form*. A revision therefore could not be written or
         * read without losing the frequency or inventing one.
         *
         * The correction is one `ALTER TABLE ... ADD COLUMN`, purely additive: it adds nothing but a
         * nullable column and invents no default — the frequency is required for one schedule form and
         * must stay absent for the other, which the entity's own discriminator guard enforces.
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
         * of a revision.
         *
         * Two columns are appended to `program_revision`: `focusGoal` (the Goal, as the domain enum
         * member's own name) and `focusTargets` (the focuses it states, as one converted deterministic
         * value).
         *
         * They exist because goals/focus is one of the structural changes that creates a revision, so a
         * revision that could not state its goal would not be the record of the plan it describes — and
         * `CUSTOM` percentages would have nowhere to live. Columns and not a table, because a focus
         * configuration is a handful of tokens belonging to exactly one immutable revision, and a second
         * row-shaped entity would model a relation that does not exist.
         *
         * Both are nullable because SQLite cannot append a `NOT NULL` column without a default, and a
         * default is exactly what must not be stated here: a revision written before this step chose no
         * goal, and the mapper reads that absence as `BALANCED` — the configuration that states nothing
         * — rather than the schema claiming the user picked something. This migration therefore executes
         * two `ALTER TABLE ... ADD COLUMN` statements and nothing else: no row is touched and no value is
         * invented for the rows already in the table.
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
         * a family's progression state.
         *
         * Five nullable columns are appended to `program_family_progression_state` — the three
         * preceding-* qualifying window counts, `qualifyingWindowsSinceLastChange` (the cooldown
         * position, absent when never changed) and `recoveryQualifyingWindows` (the recovery exit gate)
         * — and `reason` is appended to `program_adaptive_decision_record`.
         *
         * The reason is stored rather than reconstructed: an `APPLIED` row's reason is the one change
         * token its action names, but a *filtered* one — `NOT_APPLIED`, `HOLD` — has exactly the shape of
         * any of the eighteen holds, so reading it back as `AGGREGATE_LOAD_GUARD` would rest on the write
         * rule rather than on a stored fact, and the mapping from an action to a token is a property of
         * the current vocabulary rather than of the row.
         *
         * The counts exist because the engine takes them as **inputs** ([ProgramAdaptiveWindow]) and no
         * window can count itself: a decision is a pure function of one frozen window plus the caller's
         * maintained facts, so the caller has to carry those facts between windows — and a caller that
         * carried them only in memory would lose a user's confirmation count on every process restart,
         * which the design forbids. They are columns rather than a table, and are not reconstructed from
         * the decision trail, because the trail records only the decisions the integration *keeps* and
         * the per-window verdict that advances a confirmation count is not a fact of any decision row.
         *
         * Why nullable and defaultless: SQLite cannot append a `NOT NULL` column without a default, and a
         * default is what must not be stated here. A row written before this step records no window
         * bookkeeping, and the mapper reads that absence as the count a family with no preceding window
         * has — the same value a `DEFAULT 0` would produce, without the schema asserting that every
         * upgraded row had counted zero windows. `qualifyingWindowsSinceLastChange` in particular must
         * stay absent rather than become `0`: `null` means *"this family has never had a progression
         * change"* and the cooldown is therefore already served, while `0` would claim a change happened
         * this window.
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
         * The legacy schema is retired and the posture/mobility track's identity is renamed.
         *
         * Five tables are dropped, never converted: `user_progress`, `program_day_state`, `set_log`,
         * `family_progression_state` and `adaptive_decision_record`. No legacy program or history
         * migration is required, so no row of these becomes a target row: a `UserProgress` row is not a
         * session, a `ProgramDayState` row is not an opportunity, and a Stage-1 family level is not a
         * target family state. Converting them would be inventing a mapping the data does not support,
         * and the resulting target rows would claim a history that never happened.
         *
         * Everything else — the Program System's own tables, `app_state`, the body-weight log, the
         * nutrition plan's calendar, meals and shopping list — is untouched: this migration issues no
         * statement against any of them.
         *
         * `posture_session_progress` is the one table that changes shape, and its **rows are copied, not
         * recreated**: `cycleNumber`/`day` become `trackCycle`/`trackDay`, which is a rename of the row's
         * identity and not a reinterpretation of it (the track keeps its own 56-day calendar and its day
         * 1 is the same day). The table is rebuilt and filled from the old one rather than renamed in
         * place, because `ALTER TABLE … RENAME COLUMN` needs SQLite 3.25 and this app's `minSdk` predates
         * it on devices the app still supports; the copy is a plain column-for-column `SELECT`, so no row
         * and no value is lost or invented.
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
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

                // `IF EXISTS` because a database that was created from scratch at an older version may
                // legitimately never have held one of them.
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
         * Version 13 → version 14: a target occurrence's **semantic payload** becomes a stored fact.
         *
         * Until this step a persisted target slot remembered only the occurrence's identity
         * (`program_workout_slot.targetOccurrenceKey`). The occurrence that key names also carries a
         * planned date and an ordered list of components — the rule and workout identities that say
         * *what* the occurrence is — and a slot row has no column for any of them, so the semantics
         * existed only in the value that produced the slot and any later read had to invent them.
         *
         * Two tables, because a component list has three properties a single serialized column would
         * lose: the order would have to be *parsed* back out of a blob instead of being part of the
         * primary key; `ruleId` and `workoutId` would stop being independent fields; and a short, padded
         * or placeholder component list would become fabricable. There is no nullable component row and
         * no default identity, so a record either holds its real components or does not exist.
         *
         * `(programId, occurrenceKey)` **is** the primary key of the parent table, which is what lets two
         * Programs hold the same key independently and one Program hold it only once — and, because the
         * pair is the key, what makes a stored record impossible to re-point at another Program or
         * another key by a write. `occurrenceKey` is stored as an opaque token: nothing splits it, trims
         * it, normalizes it or derives a component from its text.
         *
         * It creates storage and writes no row: there is deliberately **no** backfill from
         * `program_workout_slot`, because a slot carries no components and any such row would have to
         * invent them — and a row that invents its own payload is worse than an absent one, since a
         * later read would return the invention believing it was stored.
         *
         * The `CASCADE` foreign keys are the ownership graph, not a convenience: an occurrence is
         * destroyed with its Program, and its components with the occurrence.
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

        /**
         * Version 14 → version 15: a revision's **explicit target schedule source** becomes a stored
         * fact.
         *
         * Until this step a revision stored only its legacy `program_schedule`, and every would-be target
         * scheduling entry point had to *invent* the target rules it needed: a rule identity, a workout
         * identity, an anchor date and — for a derived rule — the rule it derives from. None of those is
         * in the legacy vocabulary, so each was a guess dressed as a read.
         *
         * Two tables, not columns on `program_revision`: a revision states a *list* of rules and a *list*
         * of bindings, and both the order and the membership of those lists are part of what is stored.
         *
         * The cadence is a **discriminator plus one payload column per form** rather than one encoded
         * value, for the three reasons that shape exists: the payload is a field, so reading it back is a
         * column fetch rather than a delimiter split; a form that has no payload cannot be confused with
         * a form whose payload is zero, because absent stays absent; and `SessionsPerWeek` cannot be
         * normalized into a weekday set or an interval on the way through. The entity's own guard holds
         * the discriminator and its payload consistent, exactly as `program_revision`'s guard holds
         * `scheduleType` and `scheduleWeekdays`.
         *
         * `(revisionId, ruleId)` **is** the primary key of the rule table: one revision may state a rule
         * once, two revisions may state the same rule identity independently, and a stored rule can never
         * be re-pointed at another revision by a write. `(revisionId, workoutId)` is the primary key of
         * the binding table for the same reason — one workout presents one plan day per revision, so
         * which day it presents is a fact rather than a preference.
         *
         * The binding's `programDayId` cascades with its plan day, and its `revisionId` cascades with its
         * revision. What a foreign key cannot say is that the day belongs to *this* revision, and the
         * repository refuses that case explicitly rather than storing a binding that would present one
         * revision's schedule against another revision's plan.
         *
         * It creates storage and writes no row: there is deliberately **no** backfill from
         * `program_revision.scheduleType`, `scheduleWeekdays` or `scheduleSessionsPerWeek`, because those
         * columns carry none of the four facts a target rule needs. An upgraded revision therefore states
         * no target source, and a read reports that as a typed absence rather than a plausible-looking
         * default.
         */
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_target_schedule_rule` (
                        `revisionId` TEXT NOT NULL,
                        `ruleId` TEXT NOT NULL,
                        `workoutId` TEXT NOT NULL,
                        `cadenceType` TEXT NOT NULL,
                        `cadenceDays` INTEGER,
                        `cadenceSessionsPerWeek` INTEGER,
                        `cadenceWeekdays` TEXT,
                        `cadenceSourceRuleId` TEXT,
                        `anchorDate` TEXT NOT NULL,
                        PRIMARY KEY(`revisionId`, `ruleId`),
                        FOREIGN KEY(`revisionId`) REFERENCES `program_revision`(`revisionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `program_target_program_day_binding` (
                        `revisionId` TEXT NOT NULL,
                        `workoutId` TEXT NOT NULL,
                        `programDayId` TEXT NOT NULL,
                        PRIMARY KEY(`revisionId`, `workoutId`),
                        FOREIGN KEY(`revisionId`) REFERENCES `program_revision`(`revisionId`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`programDayId`) REFERENCES `program_day`(`programDayId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                // `programDayId` is a foreign key outside the primary key, so it is indexed — the same
                // reason `program_workout_slot` indexes its own three. Without it SQLite full-scans
                // the binding table whenever a `program_day` row is modified, which the cascade does.
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_target_program_day_binding_programDayId` " +
                        "ON `program_target_program_day_binding` (`programDayId`)"
                )
            }
        }

        /**
         * The user's **exercise preference** becomes revision content.
         *
         * One nullable column and nothing else — no table, because a preference is a handful of ids
         * belonging to exactly one immutable revision, which is the same reasoning
         * `focusGoal`/`focusTargets` gave for a focus configuration. There is deliberately **no
         * `DEFAULT`**: a default would write a ranking on every existing row, and no user ever stated
         * one. `null` is what an upgraded row gets, and `null` means exactly what
         * `ExercisePreference.NONE` means — the user named nothing.
         *
         * Nullable rather than an empty string so the absence is stored as itself; a blank string would
         * have to be re-read as an absence anyway, and one representation per value is the format's own
         * determinism rule.
         */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `program_revision` ADD COLUMN `preferredExerciseIds` TEXT"
                )
            }
        }

        /**
         * Version 16 → version 17: a plan element's **historical focus** becomes a stored fact, and the
         * session snapshot carries it.
         *
         * Two nullable columns and nothing else — no table, no repository, no new ownership: a focus
         * assignment belongs to exactly the element that represents it, so it belongs on that element's
         * row.
         *
         *  * `program_exercise.focus` — the focus the Focus Planner assigned this element when it was
         *    generated. Without this column `PlanReconciler` drops `GeneratedElement.focus`, so
         *    `recentExposureByFocus` cannot be filled: the fact would exist transiently and then nowhere.
         *  * `session_snapshot_exercise.focus` — a **copy** of that value, frozen at session start for
         *    the same reason the prescription is copied: a later revision, a regeneration or a
         *    superseding adjustment must not re-explain which focus a workout that already happened was
         *    presented for.
         *
         * There is deliberately **no default focus**. A default would write a claim — that this element
         * trains something — on every pre-existing row, including manual programs and user-authored
         * elements the generator never assigned. `null` is what an upgraded row gets, and `null` means
         * exactly what it means in the domain: *no focus was ever recorded*. That absence is then read
         * as absence by generation context, never as a zero.
         *
         * Both columns are nullable rather than empty strings so the absence is stored as itself; a blank
         * token would have to be re-read as an absence anyway, and one representation per value is the
         * format's own determinism rule.
         */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `program_exercise` ADD COLUMN `focus` TEXT"
                )
                database.execSQL(
                    "ALTER TABLE `session_snapshot_exercise` ADD COLUMN `focus` TEXT"
                )
            }
        }

        /**
         * Version 17 -> version 18: the app-owned **progression relation catalogue**.
         *
         * One table and nothing else — no column is added, altered or dropped, and no existing Program
         * or adaptive table is touched. A ladder is a *definition* this app authors, not a fact derived
         * from rows a device already holds, so there is nothing in an upgraded database to convert:
         * `program_exercise` holds what one revision's plan prescribes, not what a family may progress
         * to (and a plan may legitimately contain a single rung of a family, or none);
         * `program_family_progression_state` holds where a family *currently* is, which is history, not
         * a definition — a current position is one rung, and reading it as a hierarchy would fabricate
         * the other rungs; and `program_adaptive_decision_record` holds what was decided once, which is
         * not a ladder.
         *
         * So this step creates the storage and **seeds nothing**: an upgraded device declares no ladder
         * for any family, and production therefore keeps reporting
         * `NO_DECLARED_PROGRESSION_RELATION` — which is the honest state, not a regression. A
         * `DEFAULT`-laden insert or an `INSERT ... SELECT` backfill here would write a claim about what
         * a family trains that no author ever made.
         *
         * `level` is an `INTEGER` rather than a name because the domain's rule is arithmetic — a family's
         * positions must be contiguous — and contiguity is only checkable over numbers. The
         * `(familyId, exerciseId)` primary key makes "one exercise is one position in its family's
         * ladder" a table constraint rather than a caller convention. No index is declared: `familyId`
         * is the key's leading column and the only column the table is ever read by.
         */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `progression_relation_variant` (
                        `familyId` TEXT NOT NULL,
                        `level` INTEGER NOT NULL,
                        `exerciseId` TEXT NOT NULL,
                        `prescriptionDimension` TEXT NOT NULL,
                        `perSetTargets` TEXT NOT NULL,
                        PRIMARY KEY(`familyId`, `exerciseId`)
                    )
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
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
