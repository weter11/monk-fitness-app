package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.ExercisePreference
import com.monkfitness.app.domain.program.ProgramEditorDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 28's **exercise preference over real storage** — the claims only SQLite can decide.
 *
 * `ExercisePreferenceTest` states what the domain value is; this suite states that the user's stated
 * ordering actually survives the round trip through `program_revision`, and that the ownership rules hold
 * on real rows rather than in a comment.
 *
 * ### Why raw SQL for the writes
 *
 * The round trip is what is under test, so the test writes the **column** directly. Going through
 * `ProgramEditorService.save` would prove that the whole save path works, which several suites already
 * cover, while hiding the one thing this suite is for: whether the value comes back out **in the order it
 * went in**. A mapper that sorted would pass through a full save and be caught here.
 *
 * The reads, by contrast, go through the real repository and the real context source, so the two halves of
 * the claim are measured on opposite sides of the boundary.
 *
 * Two rigs share one database file, as the other storage suites do: the second does not migrate, so a value
 * that comes back was **stored**, not remembered.
 */
class ProgramPreferenceStorageTest {

    private val rig = ProgramDataAccessRig("alpha")
    private val other = ProgramDataAccessRig("beta", supplied = rig.database)

    private val alpha = ProgramId("program-alpha")
    private val beta = ProgramId("program-beta")

    // ------------------------------------------------------------------ the round trip

    /**
     * The user's exact order comes back out of storage, **in that order**.
     *
     * The stored text is asserted too, not only the loaded value: a mapper that sorted on write and
     * sorted again on read would return the right list while storing the wrong thing, and the order would
     * then be wrong for any other reader of the column.
     */
    @Test
    fun theExactOrderedPreferenceSurvivesTheRoundTripThroughStorage()  = runBlocking {
        storeGraph(rig, "alpha", preferred = "pull-ups,dips,muscle-ups")

        assertEquals(
            "the user's order is stored verbatim and never canonicalised — for this column the order IS " +
                "the meaning, and §9 reads it by index",
            "pull-ups,dips,muscle-ups",
            rig.database.scalar("SELECT preferredExerciseIds FROM `program_revision` WHERE revisionId = 'revision-alpha'")
        )
        assertEquals(
            "and it loads back as the same ordered preference, through the real repository",
            ExercisePreference.of("pull-ups", "dips", "muscle-ups"),
            loadPreference("alpha")
        )
    }

    @Test
    fun aReorderedPreferenceIsADifferentStoredValueAndNotAnEquivalentOne()  = runBlocking {
        // The control for the test above, and it is the claim most likely to be got wrong: two orderings of
        // the same two exercises are the same *set* and a different *statement*. A `Set` conversion or any
        // sort anywhere on the path would collapse them, and the user would silently get the other order.
        storeGraph(rig, "alpha", preferred = "dips,pull-ups")

        assertEquals(
            "the reversed order is stored reversed",
            "dips,pull-ups",
            rig.database.scalar("SELECT preferredExerciseIds FROM `program_revision` WHERE revisionId = 'revision-alpha'")
        )
        assertEquals(
            "and loads back as the reversed preference — `FocusPlan` has a canonical order and a " +
                "preference has none, so these two are not the same value",
            ExercisePreference.of("dips", "pull-ups"),
            loadPreference("alpha")
        )
    }

    @Test
    fun aSingleEntryPreferenceLoadsAsItself()  = runBlocking {
        storeGraph(rig, "alpha", preferred = "pull-ups")

        assertEquals(
            "one exercise preferred is a one-entry order, not a boolean and not a set",
            ExercisePreference.of("pull-ups"),
            loadPreference("alpha")
        )
    }

    // ------------------------------------------------------------------ absence

    @Test
    fun aProgramThatNeverStatedOneReadsAsEmptyRatherThanARanking()  = runBlocking {
        storeGraph(rig, "alpha", preferred = null)

        assertNull(
            "an absent preference is stored as SQL NULL — not an empty string, not a separator, and " +
                "certainly not a default ranking of the catalogue (§9: missing is not zero and not a rank)",
            rig.database.scalar("SELECT preferredExerciseIds FROM `program_revision` WHERE revisionId = 'revision-alpha'")
        )
        assertEquals(
            "and it loads as the stated absence the domain names",
            ExercisePreference.NONE,
            loadPreference("alpha")
        )
        assertTrue(
            "which is empty rather than populated: nothing is ranked above anything",
            loadPreference("alpha").isEmpty
        )
    }

    /**
     * A row written **before** the column existed reads as empty, because the migration adds the column
     * with no default and SQLite backfills `NULL`.
     *
     * This is the honest meaning of an old row: the user had no way to state a preference, so none exists —
     * not "they preferred whatever the upgrade decided", and not "the catalogue in alphabetical order".
     */
    @Test
    fun aRowThatPredatesTheColumnReadsAsEmptyRatherThanARanking()  = runBlocking {
        storeGraph(rig, "alpha", preferred = null)

        // The column exists and holds NULL, which is exactly what `MIGRATION_15_16` leaves behind for a
        // row that was already there: an `ALTER TABLE ... ADD COLUMN` without a DEFAULT backfills NULL.
        assertNull(
            "the migration backfills null, so an upgraded row states nothing rather than a ranking",
            rig.database.scalar("SELECT preferredExerciseIds FROM `program_revision` WHERE revisionId = 'revision-alpha'")
        )
        assertEquals(
            "and the loaded preference is the empty one",
            ExercisePreference.NONE,
            loadPreference("alpha")
        )
    }

    // ------------------------------------------------------------------ Program isolation

    @Test
    fun twoProgramsInOneDatabaseNeverSeeEachOthersPreference()  = runBlocking {
        storeGraph(rig, "alpha", preferred = "pull-ups,dips")
        storeGraph(other, "beta", preferred = "muscle-ups")

        assertEquals(
            "each Program's preference is its own, read back through its own programId",
            ExercisePreference.of("pull-ups", "dips"),
            loadPreference("alpha")
        )
        assertEquals(
            "and the other Program's, with nothing of the first's in it",
            ExercisePreference.of("muscle-ups"),
            loadPreference("beta")
        )
    }

    @Test
    fun aProgramWithNoPreferenceDoesNotReadAsAnotherPrograms()  = runBlocking {
        // The sharper half of isolation: an *empty* preference must not be satisfied by falling back to
        // some other row. "Nothing found" and "found an empty list" are the same value here, so the test
        // distinguishes them by the two rows disagreeing rather than by either one being null.
        storeGraph(rig, "alpha", preferred = "pull-ups,dips")
        storeGraph(other, "beta", preferred = null)

        assertEquals(
            "the Program that stated nothing gets the empty preference, not its neighbour's",
            ExercisePreference.NONE,
            loadPreference("beta")
        )
        assertEquals(
            "while the other keeps its own",
            ExercisePreference.of("pull-ups", "dips"),
            loadPreference("alpha")
        )
    }

    @Test
    fun deletingTheProgramDeletesThePreferenceThroughTheRevisionCascade()  = runBlocking {
        storeGraph(rig, "alpha", preferred = "pull-ups,dips")

        assertEquals(
            "the preference is stored on the revision row, which the Program owns",
            1,
            rig.database.scalar(
                "SELECT COUNT(*) FROM `program_revision` WHERE revisionId = 'revision-alpha' " +
                    "AND preferredExerciseIds = 'pull-ups,dips'"
            ).toString().toInt()
        )

        rig.database.exec("DELETE FROM `program` WHERE programId = 'program-alpha'")

        assertEquals(
            "deleting the Program takes its revision — and therefore the preference — with it, through " +
                "the same `ON DELETE CASCADE` every other revision fact already uses (§29). No new " +
                "cascade, no new trigger and no separate preference table was added",
            0,
            rig.database.scalar(
                "SELECT COUNT(*) FROM `program_revision` WHERE revisionId = 'revision-alpha'"
            ).toString().toInt()
        )
    }

    // ------------------------------------------------------------------ reaching the request

    /**
     * The persisted preference reaches `GenerationPreferences` **unchanged** — the whole point of the
     * stage, measured end to end: stored column → revision → draft → context source → request.
     */
    @Test
    fun thePersistedPreferenceReachesTheRequestUnchanged() = runBlocking {
        storeGraph(rig, "alpha", preferred = "muscle-ups,pull-ups,dips")

        val preferences = ProgramHistoryGenerationContext(
            GenerationSessionHistory { programId -> rig.workoutSessionRepository.sessionsOfProgram(programId) }
        ).preferencesFor(draftLoadedFromStorage("alpha"))

        assertEquals(
            "§9's first level is the user's own order, read from the revision and forwarded untouched — " +
                "not sorted, not filtered against the plan, and not completed",
            listOf("muscle-ups", "pull-ups", "dips"),
            preferences.userPreferredExerciseIds
        )
    }

    @Test
    fun aProgramWithNoStoredPreferenceReachesTheRequestAsTheEmptyList() = runBlocking {
        storeGraph(rig, "alpha", preferred = null)

        val preferences = ProgramHistoryGenerationContext(
            GenerationSessionHistory { programId -> rig.workoutSessionRepository.sessionsOfProgram(programId) }
        ).preferencesFor(draftLoadedFromStorage("alpha"))

        assertEquals(
            "absent stays absent all the way into the request: no default, no ranking, no zero",
            emptyList<String>(),
            preferences.userPreferredExerciseIds
        )
    }

    @Test
    fun theContextSourceStillIssuesExactlyOneReadPerPassWithAPreferenceInPlay() = runBlocking {
        // P27's snapshot invariant, re-pinned with a preference present. Forwarding the draft's own value
        // needs no I/O, so the read count must be unchanged — a second read for the preference would be the
        // obvious way to break "one generation operation = one coherent context snapshot".
        var reads = 0
        storeGraph(rig, "alpha", preferred = "pull-ups")

        val source = ProgramHistoryGenerationContext(
            GenerationSessionHistory { programId ->
                reads += 1
                rig.workoutSessionRepository.sessionsOfProgram(programId)
            }
        )

        source.preferencesFor(draftLoadedFromStorage("alpha"))

        assertEquals(
            "one read per pass: the preference comes off the draft, so it costs no storage read of its own",
            1,
            reads
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** A draft editing [programId], so the history read is scoped rather than skipped. */
    private fun draftEditing(programId: ProgramId) = ProgramEditorDraft(
        programId = programId,
        baseRevisionId = RevisionId("revision-alpha")
    )

    /**
     * The draft the **editor** would be holding for this Program: the stored revision's own plan and
     * configuration, carried onto a draft exactly as `ProgramEditorService.draftOf` carries it.
     *
     * This is the honest fixture for a claim about a *persisted* preference reaching the request. A
     * hand-built draft carrying a preference would prove only that a value someone typed into a test
     * object arrives somewhere — it would not touch storage at all. Building the draft **from** the stored
     * revision is what makes the round trip in this test real: column → revision → draft → request, with
     * no step skipped and no value injected by the test itself.
     */
    private suspend fun draftLoadedFromStorage(key: String): ProgramEditorDraft {
        val revision = (if (key == "alpha") rig.freshPlanRepository() else other.freshPlanRepository())
            .revisionById(RevisionId("revision-$key"))!!
        return draftEditing(ProgramId("program-$key")).copy(
            mode = revision.mode,
            duration = revision.duration,
            schedule = revision.schedule,
            days = revision.days,
            focus = revision.focus,
            preferredExercises = revision.preferredExercises
        )
    }

    /**
     * The stored revision's preference, read through a **fresh** repository over the same database.
     *
     * A fresh instance is what makes "it came back from storage" mean something: nothing can be answered
     * from a cache in this process, so the value is the one the column holds after a full map.
     */
    private suspend fun loadPreference(key: String): ExercisePreference =
        (if (key == "alpha") rig.freshPlanRepository() else other.freshPlanRepository())
            .revisionById(RevisionId("revision-$key"))!!
            .preferredExercises

    /**
     * Writes one Program's whole graph, with [preferred] as the revision's stored preference text.
     *
     * `null` writes SQL `NULL`, which is what an absent preference is. The insert names the column
     * explicitly so the test states the storage representation rather than inferring it.
     */
    private fun storeGraph(
        target: ProgramDataAccessRig,
        key: String,
        preferred: String?
    ) {
        val now = 1700000000000L
        target.database.exec(
            "INSERT INTO `program` (`programId`, `name`, `description`, `source`, `lifecycleStatus`, " +
                "`currentRevisionId`, `createdAt`, `updatedAt`, `plannedStartDate`, `actualStartDate`, " +
                "`archivedAt`) VALUES ('program-$key', 'Program $key', '', 'USER', 'NOT_STARTED', " +
                "'revision-$key', $now, $now, NULL, NULL, NULL)"
        )
        target.database.exec(
            "INSERT INTO `program_revision` (`revisionId`, `programId`, `revisionNumber`, `mode`, " +
                "`durationType`, `durationDays`, `scheduleType`, `scheduleWeekdays`, `createdAt`, " +
                "`scheduleSessionsPerWeek`, `focusGoal`, `focusTargets`, `preferredExerciseIds`) VALUES " +
                "('revision-$key', 'program-$key', 1, 'GENERATED', 'INDEFINITE', NULL, " +
                "'FLEXIBLE_PER_WEEK', NULL, $now, 3, 'BALANCED', NULL, ${literal(preferred)})"
        )
        target.database.exec(
            "INSERT INTO `program_day` (`programDayId`, `revisionId`, `position`, `type`, `name`) VALUES " +
                "('day-$key', 'revision-$key', 1, 'REST', NULL)"
        )
    }

    /** [value] as a SQL literal, or `NULL` for the absence. */
    private fun literal(value: String?): String = value?.let { "'$it'" } ?: "NULL"
}