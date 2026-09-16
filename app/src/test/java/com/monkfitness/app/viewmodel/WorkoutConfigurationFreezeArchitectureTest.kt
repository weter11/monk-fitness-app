package com.monkfitness.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fences around the session-configuration boundary, checked against the sources themselves.
 *
 * `MainViewModel` is an `AndroidViewModel` and this project has no Robolectric harness, so the app's
 * start transition cannot be driven in a JVM test — `WorkoutConfigurationSnapshotTest` drives the
 * session state holder instead. What this class adds is the other half of that evidence: that the
 * bridge in `MainViewModel` really is the wiring the holder's tests assume. Each rule below is a way
 * the freeze could be lost while every unit test still passed:
 *
 *  * the capture moved out of the start transition — into composition, into the editor, into a
 *    background flow — so that opening the Custom Program screen freezes something it must not;
 *  * the session subscribing to the live configuration flow, so a mid-session edit silently re-points
 *    the running workout (`configurationFlow` is the repository's "re-emitted whenever it changes"
 *    surface, and a session must never hold it);
 *  * a second read of the repository on the session path, which is how "capture once" degrades into
 *    "capture whenever someone asks";
 *  * the completion path clearing or re-capturing the snapshot, which would reconstruct a finished
 *    workout against a newer configuration;
 *  * the session state falling back to a configuration assembled anywhere else, or a second holder
 *    appearing next to the app's own;
 *  * persistence or progression logic creeping into this task: the snapshot is neither stored nor
 *    resolved, and Task 13's adaptive integration is deliberately absent.
 */
class WorkoutConfigurationFreezeArchitectureTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private fun source(relativePath: String): File {
        val candidate = File(mainSourceRoot, relativePath)
        assertTrue("expected $relativePath at ${candidate.absolutePath}", candidate.isFile)
        return candidate
    }

    private val viewModelText: String by lazy { source("viewmodel/MainViewModel.kt").readText() }
    private val holderText: String by lazy { source("viewmodel/ActiveWorkoutConfiguration.kt").readText() }
    private val snapshotText: String by lazy { source("domain/adaptive/WorkoutConfigurationSnapshot.kt").readText() }
    private val sessionStateText: String by lazy { source("viewmodel/UiState.kt").readText() }

    /** The text of one class-level member: from its signature to the next class-level member. */
    private fun memberBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("expected '$signature' in the source", start >= 0)
        val next = Regex("\n    (fun|val|var|private|internal|override|companion) ")
            .find(source, start + signature.length)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    /** The text of a data-class declaration, from `data class` to its closing parenthesis. */
    private fun classDeclaration(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("expected '$signature' in the source", start >= 0)
        val end = source.indexOf(")\n", start)
        assertTrue("expected '$signature' to be closed", end >= 0)
        return source.substring(start, end + 1)
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ---- capture happens at the start transition, and nowhere else -------------------------------

    @Test
    fun theStartTransitionIsWhatCapturesTheSessionsConfiguration() {
        val startWorkoutSession = memberBody(viewModelText, "fun startWorkoutSession(")
        assertTrue(
            "the start transition must capture the session's configuration: " +
                "startWorkoutSession does not begin the session configuration",
            "beginWorkoutSessionConfiguration(" in startWorkoutSession
        )

        val capture = memberBody(viewModelText, "fun beginWorkoutSessionConfiguration(")
        assertTrue("the capture must read the persisted configuration", "programConfigurationRepository.load()" in capture)
        assertTrue("the capture must go through the session state holder", "beginSession(" in capture)
        assertTrue(
            "the capture must carry the app's session identity",
            "WorkoutSessionIdentity(" in capture && "SessionMode.POSTURE_MOBILITY" in capture
        )
        assertTrue(
            "the capture must be bound to the session's own coroutine scope",
            "viewModelScope.launch" in capture
        )
    }

    @Test
    fun theSessionConfigurationIsCapturedByTheStartTransitionAlone() {
        assertEquals(
            "exactly one place in the view model may start a session's configuration",
            1,
            occurrences(viewModelText, "activeWorkoutConfiguration.beginSession(")
        )
        assertEquals(
            "the session configuration state is held once",
            1,
            occurrences(viewModelText, "ActiveWorkoutConfiguration()")
        )
    }

    @Test
    fun theSessionNeverSubscribesToTheLiveConfiguration() {
        assertTrue(
            "a running session must not observe the repository's re-emitted configuration flow",
            "configurationFlow" !in viewModelText
        )
        assertEquals(
            "the persisted configuration is read once per session start, nowhere else on the session path",
            1,
            occurrences(viewModelText, "programConfigurationRepository.load()")
        )
    }

    @Test
    fun theCompletionPathLeavesTheSessionsConfigurationAlone() {
        val completionMembers = listOf(
            "fun completeWorkout(",
            "fun completeRecoveryDay(",
            "fun completePostureWorkout(",
            "fun completeCurrentSession("
        )
        completionMembers.forEach { signature ->
            val body = memberBody(viewModelText, signature)
            assertTrue(
                "$signature must not re-capture or clear the session's configuration",
                "activeWorkoutConfiguration" !in body && "beginSession(" !in body
            )
        }
    }

    // ---- the session state presents the captured configuration ------------------------------------

    @Test
    fun theSessionStateCarriesTheCapturedConfiguration() {
        assertTrue(
            "the workout session state must present the configuration the session runs on",
            "effectiveConfiguration: WorkoutConfigurationSnapshot?" in sessionStateText
        )
        assertTrue(
            "the view model must expose the session's own configuration",
            "val sessionConfiguration" in viewModelText
        )
        val sessionState = memberBody(viewModelText, "val workoutSessionUiState = combine(")
        assertTrue(
            "the session state must be built from the captured configuration, not from the repository",
            "sessionConfiguration" in sessionState
        )
    }

    // ---- the holder stays a state holder ---------------------------------------------------------

    @Test
    fun theSessionHolderHoldsNoPersistenceAndNoAndroid() {
        listOf(
            "import android.", "import androidx.", "import com.monkfitness.app.data.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel.",
            "DataStore", "Repository", "SettingsManager", "Room", "Dao", "@Entity",
            "preferencesDataStore", "System.currentTimeMillis", "LocalDate"
        ).forEach { token ->
            assertTrue("the session holder is plain state, found: $token", token !in holderText)
        }
        assertTrue("the holder may only speak to the domain in flows", "import kotlinx.coroutines.flow" in holderText)
    }

    @Test
    fun theSnapshotIsNeitherStoredNorResolved() {
        listOf(
            "Room", "Dao", "@Entity", "DataStore", "Repository", "SettingsManager",
            "ProgressionResolver", "AdaptiveProgramEngine", "AdaptivePolicy", "AdaptiveDecision",
            "FamilyProgressionState", "AdaptiveRepository", "AdaptiveSignals",
            "generateWorkout", "getWorkoutForDay", "WorkoutGenerator", "applyDifficultyAdjustment",
            ".filter(", ".sortedBy"
        ).forEach { token ->
            assertTrue(
                "the snapshot is the captured configuration and nothing more, found: $token",
                token !in snapshotText && token !in holderText
            )
        }
    }

    // ---- the freeze is not reachable from the editor ---------------------------------------------

    @Test
    fun theCustomProgramEditorCannotFreezeOrReachASession() {
        val editorSources = File(mainSourceRoot, "ui/customprogram")
            .listFiles { file -> file.isFile && file.extension == "kt" }
            ?.sortedBy { it.name }
            ?: emptyList()

        assertTrue("the Custom Program editor must exist", editorSources.isNotEmpty())
        editorSources.forEach { file ->
            val text = file.readText()
            listOf("ActiveWorkoutConfiguration", "WorkoutConfigurationSnapshot", "beginSession(", "effectiveConfiguration")
                .forEach { token ->
                    assertTrue(
                        "${file.name} must not touch a running session: opening the editor freezes nothing",
                        token !in text
                    )
                }
        }
    }

    @Test
    fun noSessionConfigurationIsHeldOutsideTheStartTransition() {
        // The session's configuration is one value owned by one holder: a second place that remembers
        // a configuration (a cached field, a snapshot in another state class) is the failure this
        // rule exists to catch.
        val sessionState = classDeclaration(sessionStateText, "data class WorkoutSessionUiState(")
        assertEquals(
            "the session state carries the configuration once",
            1,
            occurrences(sessionState, "WorkoutConfigurationSnapshot")
        )
    }
}
