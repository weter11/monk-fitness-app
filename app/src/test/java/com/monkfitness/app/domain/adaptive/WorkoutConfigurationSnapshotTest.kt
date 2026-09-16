package com.monkfitness.app.domain.adaptive

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.monkfitness.app.data.repository.ProgramConfigurationRepository
import com.monkfitness.app.viewmodel.ActiveWorkoutConfiguration
import com.monkfitness.app.viewmodel.WorkoutSessionContext
import com.monkfitness.app.viewmodel.WorkoutSessionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The lifecycle boundary Task 12 owns: the configuration a workout runs on is the one that was
 * persisted the instant that workout started, and it is frozen from then on.
 *
 * `ProgramConfiguration` is the user's **future** configuration — mutable, persisted, and advanced by
 * every real edit. A session therefore cannot read it while it is running: an edit made mid-session
 * belongs to the next workout, and a finished workout must never be reconstructed against a newer
 * selection. What this suite pins, in that order:
 *
 *  * the representation ([WorkoutConfigurationSnapshot]) copies the version and the selection and
 *    nothing else — no source, no library, no version arithmetic, no repair;
 *  * an edit made **before** the start transition is the configuration the session runs on (A);
 *  * the capture happens **at** the start transition, so an edit made afterwards is never observed by
 *    the running session, while the store itself does move on (B);
 *  * repeated later edits, and every re-entry the app can perform (recomposition, navigation back
 *    into the session, the screen's start effect firing again), cannot mutate or replace it (C, F);
 *  * the session state that a completed workout is presented from keeps the very snapshot it started
 *    with, rather than rebuilding one from the live configuration (D);
 *  * the snapshot reports the version captured at start, even after the repository has advanced (E);
 *  * **future-only**: the next session starts from the configuration persisted at *its* start (G).
 *
 * ## What is real in this rig
 *
 * The app's own `ProgramConfigurationRepository` over a real preferences DataStore on a real file
 * (the JVM variant the Android artifact resolves to), the app's own session state holder, and the
 * app's own exercise library. The start transition is driven exactly as `MainViewModel` drives it:
 * `beginSession(identity) { repository.load() }`. A fake store or a stubbed capture would prove only
 * that the test can reproduce the implementation, so neither is used.
 */
class WorkoutConfigurationSnapshotTest {

    // ---- fixtures: real library ids ---------------------------------------------------------------

    private val library: Set<String> = ProgramConfigurationRepository.authoritativeDefaultExerciseIds()

    /** The selection that was persisted before the edit the session must observe. */
    private val firstSelection = setOf("pushups", "plank", "squats")

    /** The selection persisted when the session starts: the one it must keep. */
    private val sessionSelection = setOf("cat_cow", "dead_bug", "bird_dog")

    /** A later edit. */
    private val nextSelection = setOf("pullups", "rows", "hang", "face_pull")

    /** A still later edit. */
    private val thirdSelection = setOf("hip_circles", "leg_swings", "superman", "child_pose")

    private val selections = listOf(firstSelection, sessionSelection, nextSelection, thirdSelection)

    /** The app's session identity: the pair its navigation route and its start guard both use. */
    private val identity = WorkoutSessionIdentity(day = 3, isPostureMobilitySession = false)
    private val otherIdentity = WorkoutSessionIdentity(day = 4, isPostureMobilitySession = false)

    /**
     * The program context the sessions in this suite start under — the calendar and revision that are
     * live *at the start transition*, the half of the session freeze a revised program must not reach.
     */
    private val contextA = WorkoutSessionContext(
        programCycle = 1,
        programRevision = 0,
        programStartDate = LocalDate.of(2026, 8, 31)
    )

    /** What the same live flows report after a `Start Revised Program`: a different revision, a new calendar. */
    private val contextB = WorkoutSessionContext(
        programCycle = 1,
        programRevision = 1,
        programStartDate = LocalDate.of(2026, 10, 30)
    )

    // ---- a real DataStore over a real file --------------------------------------------------------

    private inner class Sessions {
        private val file = File(
            File(System.getProperty("java.io.tmpdir"), "task12-workout-configuration-${System.nanoTime()}")
                .apply { mkdirs() },
            "program_configuration.preferences_pb"
        )
        private var scope: CoroutineScope? = null
        private var store: DataStore<Preferences>? = null

        suspend fun open(): ProgramConfigurationRepository {
            close()
            val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val dataStore = PreferenceDataStoreFactory.create(scope = newScope) { file }
            scope = newScope
            store = dataStore
            return ProgramConfigurationRepository(dataStore, library)
        }

        suspend fun close() {
            val current = scope ?: return
            val job = current.coroutineContext[Job]
            current.cancel()
            job?.join()
            scope = null
            store = null
        }
    }

    private fun sessionTest(block: suspend CoroutineScope.(ProgramConfigurationRepository) -> Unit) {
        val sessions = Sessions()
        try {
            runBlocking { block(sessions.open()) }
        } finally {
            runBlocking { sessions.close() }
        }
    }

    /** Counts the configuration reads a session performs: the proof of "captured exactly once". */
    private class Reads(private val repository: ProgramConfigurationRepository) {
        var count = 0

        suspend fun read(): ProgramConfiguration {
            count++
            return repository.load()
        }
    }

    private fun mainSource(relativePath: String): File {
        val candidate = File("src/main/java/com/monkfitness/app/$relativePath")
        return if (candidate.isFile) candidate else File("app/src/main/java/com/monkfitness/app/$relativePath")
    }

    // ---- the representation -----------------------------------------------------------------------

    @Test
    fun theSnapshotCarriesTheVersionAndTheSelectionItCaptured() = sessionTest { repository ->
        val stored = repository.apply(sessionSelection)

        val snapshot = WorkoutConfigurationSnapshot.capture(stored)

        assertEquals(stored.configurationVersion, snapshot.configurationVersion)
        assertEquals(stored.enabledExerciseIds, snapshot.enabledExerciseIds)
        assertEquals(sessionSelection.sorted(), snapshot.orderedExerciseIds)
        assertTrue("every captured exercise is enabled by the snapshot", sessionSelection.all { snapshot.enables(it) })
        assertFalse("the snapshot enables nothing that was not captured", snapshot.enables("pushups"))
    }

    @Test
    fun theSnapshotCopiesNoOtherFieldOfTheConfiguration() {
        // Reflection on the declared fields on purpose: "it copies the version and the selection"
        // must fail the suite the moment a source, a timestamp or an exercise list is added to it.
        val declared = WorkoutConfigurationSnapshot::class.java.declaredFields
        assertTrue(
            "the companion is part of the declaration and must be excluded by this rule, was " +
                declared.map { field -> field.name },
            declared.any { field -> field.name == "Companion" }
        )
        val fields = declared
            .filterNot { field -> java.lang.reflect.Modifier.isStatic(field.modifiers) }
            .map { field -> field.name }
            .filterNot { name -> name.startsWith("$") }
            .sorted()

        assertEquals(listOf("configurationVersion", "enabledExerciseIds"), fields)
    }

    @Test
    fun theSnapshotOwnsNoConfigurationAndNoVersionArithmetic() {
        val source = mainSource("domain/adaptive/WorkoutConfigurationSnapshot.kt").readText()

        listOf(
            "configurationVersion +", "configurationVersion.inc", "INITIAL_VERSION +",
            ".applying(", "resetToDefault(", "ProgramConfiguration.default(", "ProgramConfiguration.custom("
        ).forEach { token ->
            assertFalse("the version and the source belong to the repository, found: $token", token in source)
        }
    }

    @Test
    fun theSnapshotAddsNoSecondExerciseCatalogue() {
        val source = mainSource("domain/adaptive/WorkoutConfigurationSnapshot.kt").readText()

        listOf("WorkoutGenerator", "exerciseToFamiliesMap", "getExerciseLibrary", "R.string", "Exercise(")
            .forEach { token ->
                assertFalse("the library comes from the app's own catalogue, found: $token", token in source)
            }
    }

    @Test
    fun theSnapshotStaysPureDomainCode() {
        val source = mainSource("domain/adaptive/WorkoutConfigurationSnapshot.kt").readText()

        listOf("import android.", "import androidx.", "import kotlinx.", "import com.monkfitness.app.")
            .forEach { token ->
                assertFalse("a session snapshot is domain data, found: $token", token in source)
            }
    }

    // ---- the session lifecycle --------------------------------------------------------------------

    @Test
    fun aSessionThatHasNotStartedHasNoEffectiveConfiguration() = runBlocking {
        val session = ActiveWorkoutConfiguration()

        assertNull("nothing is frozen before a workout starts", session.effectiveConfiguration.first())
        assertNull(session.activeSession.value)
    }

    /** A: an edit made before the start transition is the configuration the session runs on. */
    @Test
    fun aConfigurationChangedBeforeTheSessionStartsIsTheOneTheSessionRunsOn() = sessionTest { repository ->
        val earlier = repository.apply(firstSelection)
        val atStart = repository.apply(sessionSelection)
        assertNotEquals(earlier.configurationVersion, atStart.configurationVersion)

        val session = ActiveWorkoutConfiguration()
        val effective = session.beginSession(identity, readContext = { contextA }) { repository.load() }

        // The assertion is on what the session runs on and reports — not on the store.
        assertEquals(atStart.enabledExerciseIds, effective.enabledExerciseIds)
        assertEquals(atStart.configurationVersion, effective.configurationVersion)
        assertNotEquals(earlier.enabledExerciseIds, effective.enabledExerciseIds)
        assertSame(effective, session.effectiveConfiguration.first())
    }

    /** B: frozen exactly at the start transition; a later edit is not observed by the session. */
    @Test
    fun aConfigurationChangedAfterTheStartIsNotObservedByTheRunningSession() = sessionTest { repository ->
        val atStart = repository.apply(sessionSelection)
        val session = ActiveWorkoutConfiguration()
        session.beginSession(identity, readContext = { contextA }) { repository.load() }

        val later = repository.apply(nextSelection)
        assertNotEquals(atStart.configurationVersion, later.configurationVersion)

        val effective = requireNotNull(session.effectiveConfiguration.first())
        assertEquals(sessionSelection, effective.enabledExerciseIds)
        assertEquals(atStart.configurationVersion, effective.configurationVersion)
        assertFalse(
            "the running session must not observe ${nextSelection.sorted()}",
            nextSelection.any { effective.enables(it) }
        )
        // The freeze is the session's: the persisted configuration really did move on.
        assertEquals(nextSelection, repository.load().enabledExerciseIds)
    }

    /** C: repeated later edits cannot mutate the running session. */
    @Test
    fun repeatedEditsCannotMutateTheRunningSession() = sessionTest { repository ->
        repository.apply(sessionSelection)
        val session = ActiveWorkoutConfiguration()
        val captured = session.beginSession(identity, readContext = { contextA }) { repository.load() }

        val second = repository.apply(nextSelection)
        val third = repository.apply(thirdSelection)
        assertNotEquals(second.configurationVersion, third.configurationVersion)

        assertSame("the session keeps the very snapshot it started with", captured, session.effectiveConfiguration.first())
        assertEquals(sessionSelection, requireNotNull(session.effectiveConfiguration.first()).enabledExerciseIds)
        assertEquals(thirdSelection, repository.load().enabledExerciseIds)
    }

    /**
     * D: a completed workout keeps the snapshot it started with.
     *
     * The app's completion path writes progress and touches no session-configuration state, so
     * presenting the completed workout (the screen's start effect firing again) must return the same
     * snapshot instance rather than rebuilding one from the edited configuration.
     */
    @Test
    fun aCompletedWorkoutKeepsTheVerySnapshotItStartedWith() = sessionTest { repository ->
        repository.apply(sessionSelection)
        val reads = Reads(repository)
        val session = ActiveWorkoutConfiguration()
        val captured = session.beginSession(identity, readContext = { contextA }) { reads.read() }

        repository.apply(nextSelection)

        val presentedAfterCompletion = session.beginSession(identity, readContext = { contextA }) { reads.read() }

        assertSame(captured, presentedAfterCompletion)
        assertSame(captured, session.effectiveConfiguration.first())
        assertEquals(sessionSelection, presentedAfterCompletion.enabledExerciseIds)
        assertEquals("a completed workout is never reconstructed from a later edit", 1, reads.count)
    }

    /** E: the snapshot reports the version captured at start, after the repository has advanced. */
    @Test
    fun theSnapshotKeepsTheVersionItWasCapturedAt() = sessionTest { repository ->
        val atStart = repository.apply(sessionSelection)
        val session = ActiveWorkoutConfiguration()
        val captured = session.beginSession(identity, readContext = { contextA }) { repository.load() }

        val edited = repository.apply(nextSelection)

        assertEquals(1, edited.configurationVersion - atStart.configurationVersion)
        assertEquals(atStart.configurationVersion, captured.configurationVersion)
        assertEquals(
            atStart.configurationVersion,
            requireNotNull(session.effectiveConfiguration.first()).configurationVersion
        )
        assertNotEquals(edited.configurationVersion, captured.configurationVersion)
        assertEquals(edited.configurationVersion, repository.load().configurationVersion)
    }

    /** F: the session reads the persisted configuration exactly once, and never re-reads it. */
    @Test
    fun theSessionReadsTheConfigurationExactlyOnce() = sessionTest { repository ->
        repository.apply(sessionSelection)
        val reads = Reads(repository)
        val session = ActiveWorkoutConfiguration()

        val captured = session.beginSession(identity, readContext = { contextA }) { reads.read() }
        assertEquals("the start transition reads the persisted configuration once", 1, reads.count)

        repository.apply(nextSelection)
        repository.apply(thirdSelection)

        // Every re-entry the app can perform: recomposition, navigation back into the session, the
        // workout screen's own start effect firing a second time.
        repeat(3) {
            assertSame(captured, session.beginSession(identity, readContext = { contextA }) { reads.read() })
            assertSame(captured, session.effectiveConfiguration.first())
        }

        assertEquals("a started session never reads the configuration again", 1, reads.count)
        assertEquals(sessionSelection, captured.enabledExerciseIds)
    }

    /** F, concurrent: two start entries for the same session still produce exactly one capture. */
    @Test
    fun twoStartEntriesForTheSameSessionProduceOneCapture() = sessionTest { repository ->
        repository.apply(sessionSelection)
        val reads = Reads(repository)
        val session = ActiveWorkoutConfiguration()
        val insideTheRead = CompletableDeferred<Unit>()

        val captures = coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                session.beginSession(identity, readContext = { contextA }) {
                    reads.count++
                    insideTheRead.await()
                    repository.load()
                }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                session.beginSession(identity, readContext = { contextA }) { reads.read() }
            }
            // Only now is the first entry's read allowed to finish: the second entry was attempted
            // while the session's single capture was already in flight.
            insideTheRead.complete(Unit)
            first.await() to second.await()
        }

        val (captured, alsoCaptured) = captures
        assertEquals("two start entries must not read the configuration twice", 1, reads.count)
        assertSame(captured, alsoCaptured)
        assertEquals(sessionSelection, captured.enabledExerciseIds)
        assertEquals(sessionSelection, requireNotNull(session.effectiveConfiguration.first()).enabledExerciseIds)
    }

    /** G: the next session starts from the configuration persisted at its own start. */
    @Test
    fun theNextSessionStartsFromTheConfigurationPersistedAtItsOwnStart() = sessionTest { repository ->
        repository.apply(sessionSelection)
        val session = ActiveWorkoutConfiguration()
        val running = session.beginSession(identity, readContext = { contextA }) { repository.load() }
        assertEquals(sessionSelection, running.enabledExerciseIds)

        repository.apply(nextSelection)
        assertSame(
            "the running session is still frozen while the next configuration is edited",
            running,
            session.beginSession(identity, readContext = { contextA }) { repository.load() }
        )

        val next = session.beginSession(otherIdentity, readContext = { contextB }) { repository.load() }

        assertEquals(nextSelection, next.enabledExerciseIds)
        assertNotEquals(running.configurationVersion, next.configurationVersion)
        assertSame(next, session.effectiveConfiguration.first())
        // Starting the next session does not rewrite the previous session's snapshot.
        assertEquals(sessionSelection, running.enabledExerciseIds)
        assertEquals(otherIdentity, session.activeSession.value?.identity)
        assertEquals(
            "a different session captures the context that is live at ITS start",
            contextB,
            session.activeSession.value?.context
        )
    }

    // ---- the program context is frozen with the session -------------------------------------------

    /**
     * The blocker this section exists for: a session that started under revision 0 on calendar A, and the
     * live flows moving on (a revised program bumps the revision and restarts the calendar) *while that
     * session is still alive*. Every later entry into the running session reports the new live context —
     * and the running session must ignore it, exactly as it ignores a later configuration edit.
     */
    @Test
    fun aProgramRevisedWhileTheSessionRunsDoesNotReachItsFrozenContext() = sessionTest { repository ->
        repository.apply(sessionSelection)
        val session = ActiveWorkoutConfiguration()
        var contextReads = 0

        session.beginSession(
            identity = identity,
            readContext = { contextReads++; contextA }
        ) { repository.load() }

        assertEquals("the start transition reads the program context once", 1, contextReads)
        assertEquals(contextA, session.activeSession.value?.context)

        // Start Revised Program: a new revision, a new calendar, a new live configuration.
        repository.apply(nextSelection)

        val reentered = session.beginSession(
            // What the live flows report now — the re-entry must not adopt it.
            identity = identity,
            readContext = { contextReads++; contextB }
        ) { repository.load() }

        assertEquals("nothing is read again for a session that already started", 1, contextReads)
        assertEquals(
            "the frozen revision and calendar survive the revised program",
            contextA,
            session.activeSession.value?.context
        )
        assertEquals(
            "and the configuration captured at start still wins",
            sessionSelection,
            reentered.enabledExerciseIds
        )
        assertSame(reentered, session.effectiveConfiguration.first())
    }

    /** A session that never captured its context at start has none — it is never invented later. */
    @Test
    fun aSessionThatHasNotStartedHasNoFrozenContext() = runBlocking {
        val session = ActiveWorkoutConfiguration()

        assertNull("nothing is frozen before a workout starts", session.activeSession.value?.context)
    }

    @Test
    fun theSelectionsAreRealLibraryIds() {
        assertTrue(
            "the fixtures must be ids the app's own library contains: " + (selections.flatten() - library),
            library.containsAll(selections.flatten())
        )
    }
}
