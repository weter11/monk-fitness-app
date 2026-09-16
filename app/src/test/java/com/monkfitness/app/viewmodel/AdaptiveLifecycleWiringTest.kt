package com.monkfitness.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fences around the lifecycle wiring, checked against the sources themselves in the same spirit as
 * `WorkoutConfigurationFreezeArchitectureTest` and `AdaptiveGenerationWiringTest`.
 *
 * `MainViewModel` is an `AndroidViewModel` and this project has no Robolectric harness, so the
 * completion path cannot be driven in a JVM test. What this class adds is the other half of the
 * evidence the data-layer suites provide: that the wiring they assume is really there, and that the
 * lifecycle actions which must NOT touch adaptive data do not.
 *
 * Each rule below is a way the integration could look finished while every other test still passed:
 *
 *  * the decision recorded from a path that is not the completion path — a generation preview, a
 *    navigation event, a C3 action — so a user's progression moved without a finished workout;
 *  * a rest day or an optional posture session recording a decision, neither of which the daily-session
 *    observation model can even describe;
 *  * a lifecycle action (rollover, restart, revised program, full reset) recording or resetting adaptive
 *    state of its own, instead of leaving it to the calendar authority and the domain;
 *  * the recorder reading the live configuration store, so a session's decision would be taken under a
 *    selection the session never ran with;
 *  * a second place constructing the recorder, or the recorder growing a policy, a ladder, a resolver,
 *    a threshold or a table of its own.
 */
class AdaptiveLifecycleWiringTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private fun source(relativePath: String): String {
        val candidate = File(mainSourceRoot, relativePath)
        assertTrue("expected $relativePath at ${candidate.absolutePath}", candidate.isFile)
        return candidate.readText()
    }

    private val viewModelText: String by lazy { source("viewmodel/MainViewModel.kt") }
    private val recorderText: String by lazy { source("data/repository/AdaptiveSessionDecisionRecorder.kt") }
    private val settingsText: String by lazy { source("data/local/SettingsManager.kt") }

    /** The text of one class-level member: from its signature to the next class-level member. */
    private fun memberBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("expected '$signature' in the source", start >= 0)
        val next = Regex("\\n    (fun|val|var|private|internal|override|companion|@)")
            .find(source, start + signature.length)
        return source.substring(start, next?.range?.first ?: source.length)
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

    // ---- the completion path is the only writer --------------------------------------------------

    @Test
    fun aFinalizedDailySessionIsWhatRecordsADecision() {
        val completion = memberBody(viewModelText, "fun completeWorkout(")
        assertTrue(
            "the day-level completion is persisted and then recorded: completeWorkout does not record",
            "recordAdaptiveDecision(" in completion
        )
        assertTrue(
            "the record happens after the completion is persisted, from a finalized observation",
            completion.indexOf("repository.updateProgress(") < completion.indexOf("recordAdaptiveDecision(")
        )
    }

    @Test
    fun theRecorderIsInvokedFromOnePlaceAndOnlyAfterTheCompletionLanded() {
        assertEquals(
            "exactly one place in the view model asks for a session's decisions",
            1,
            occurrences(viewModelText, "recordFinalizedSession(")
        )
        val recorderCall = memberBody(viewModelText, "private suspend fun recordAdaptiveDecision(")
        assertTrue("the one call lives in the recording member", "recordFinalizedSession(" in recorderCall)
        assertTrue(
            "the request carries the session's own frozen configuration",
            "effectiveConfiguration" in recorderCall &&
                "programConfigurationRepository" !in recorderCall &&
                "load()" !in recorderCall
        )
        assertTrue(
            "the request carries the program revision the session ran under",
            "programRevision = revision" in recorderCall && "programRevision.value" in recorderCall
        )
    }

    @Test
    fun arestDayAndAPostureSessionRecordNothing() {
        // A rest day prescribes no planned work and a posture/mobility session is not part of the daily
        // session observation model, so neither may reach the recorder.
        listOf("fun completeRecoveryDay(", "fun completePostureWorkout(").forEach { signature ->
            val body = memberBody(viewModelText, signature)
            assertTrue(
                "$signature must not record an adaptive decision",
                "recordAdaptiveDecision(" !in body && "recordFinalizedSession(" !in body
            )
        }
        assertTrue(
            "the rest-or-posture dispatch is what chooses between them",
            "completeRecoveryDay(day)" in viewModelText && "completePostureWorkout(day)" in viewModelText
        )
    }

    // ---- no lifecycle action touches adaptive data -----------------------------------------------

    @Test
    fun noLifecycleActionRecordsOrResetsAdaptiveState() {
        listOf(
            "fun restartCurrentCycle(",
            "fun startRevisedProgram(",
            "fun fullReset(",
            "fun dismissProgramSummary("
        ).forEach { signature ->
            val body = memberBody(viewModelText, signature)
            listOf("recordAdaptiveDecision(", "recordFinalizedSession(", "adaptiveSessionDecisionRecorder")
                .forEach { token ->
                    assertTrue(
                        "$signature is a lifecycle action and owns no adaptive decision: found $token",
                        token !in body
                    )
                }
        }
    }

    @Test
    fun theRolloverIsTheExistingCalendarOperationAndNothingElse() {
        val rollover = memberBody(viewModelText, "fun dismissProgramSummary(")
        assertTrue(
            "the rollover still runs through the existing C2 gate",
            "shouldOfferCycleCompletion(" in rollover
        )
        assertTrue(
            "and it still seeds the next cycle's grid and stamps the stored cycle",
            "synchronizeProgramStates(" in rollover && "setProgramCycleNumber(" in rollover
        )
    }

    @Test
    fun fullResetAlsoReturnsTheProgramConfigurationToItsDefault() {
        val reset = memberBody(viewModelText, "fun fullReset(")
        assertTrue(
            "the adaptive tables are cleared through the C3 authority",
            "clearAllProgressData()" in reset
        )
        assertTrue(
            "the preferences this app owns are cleared as before",
            "settingsManager.clearAll()" in reset
        )
        assertTrue(
            "the program configuration lives in its own store, so returning it to the authoritative " +
                "default is this path's own step, through the repository's own API",
            "programConfigurationRepository.resetToDefault()" in reset
        )
    }

    @Test
    fun theRevisionIdentityStaysTheAppsOwnMarker() {
        assertTrue(
            "the revised-program action bumps the revision marker",
            "PROGRAM_REVISION" in memberBody(settingsText, "suspend fun startRevisedProgram(")
        )
        assertTrue(
            "and the adaptive request is scoped by that same marker",
            "programRevision = revision" in memberBody(
                viewModelText,
                "private suspend fun recordAdaptiveDecision("
            )
        )
    }

    // ---- the recorder stays an adapter ------------------------------------------------------------

    @Test
    fun theRecorderIsBuiltOnceFromTheAppsOwnDatabase() {
        assertEquals(
            "one recorder in production, wired where the app's database is",
            1,
            occurrences(viewModelText, "AdaptiveSessionDecisionRecorder.of(")
        )
        assertEquals(
            "no second adaptive recorder is constructed on the session path",
            1,
            occurrences(viewModelText, "private val adaptiveDecisionRecorder:")
        )
        assertEquals(
            "and the recording path calls the one recorder",
            occurrences(viewModelText, "adaptiveDecisionRecorder."),
            1
        )
    }

    @Test
    fun theRecorderImplementsNoAdaptiveRule() {
        listOf(
            "AdaptivePolicy", "AdaptiveSignalCalculator", "AdaptiveSignals", "PilotProgressionProfiles",
            "ProgressionResolver", "AdaptiveProgramEngine", "AdaptiveProgressionPlan.of(",
            "ProgressionStep(", "MIN_LEVEL", "MAX_LEVEL", "eligibleSessionWindow",
            "progressConfirmingWindows", "progressionCooldownEligibleSessions"
        ).forEach { token ->
            assertTrue(
                "the recorder records what the domain decided; $token is an adaptive rule",
                token !in recorderText
            )
        }
    }

    @Test
    fun theRecorderOwnsNoStorageAndNoSecondTable() {
        listOf(
            "@Entity", "@Dao", "@Insert", "@Query", "@PrimaryKey", "preferencesDataStore",
            "RoomDatabase", "DataStore", "upsertFamilyState", "appendDecision", "countDecisionsFor"
        ).forEach { token ->
            assertTrue(
                "persistence stays behind the adapter; found $token",
                token !in recorderText
            )
        }
        assertTrue(
            "the record is materialized by the repository's own mapping, not built here",
            "AdaptiveDecisionRecord(" !in recorderText && "decisionRecord(" in recorderText
        )
        assertTrue(
            "and the state and the record land through the adapter's transactional write",
            "persistDecisionsOnce(" in recorderText
        )
        assertTrue(
            "the production wiring is the app's own database and its own transaction runner, exactly " +
                "as the session's read-side adapter is wired",
            "AppDatabase" in recorderText && "database.withTransaction { block() }" in recorderText
        )
    }

    @Test
    fun theRecorderGoesThroughTheExistingIntegrationInsteadOfASecondPipeline() {
        assertTrue(
            "one window, one decision: the recorder asks the integration the session path already uses",
            "AdaptiveWorkoutIntegration(" in recorderText && "planSession(" in recorderText
        )
        assertTrue(
            "and it reads history through a function, exactly as the session's reader does",
            "readHistory" in recorderText
        )
    }
}
