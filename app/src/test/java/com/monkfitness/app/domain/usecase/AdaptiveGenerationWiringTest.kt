package com.monkfitness.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fences around Task 13's integration, checked against the sources themselves, in the same
 * spirit as `WorkoutConfigurationFreezeArchitectureTest`: what a unit test cannot drive —
 * `MainViewModel` is an `AndroidViewModel` and this project has no Robolectric harness — is pinned
 * here as the wiring the unit tests assume.
 *
 * Each rule below is a way the integration could look finished while every other test still passed:
 *
 *  * the session's workout generated from the live configuration store instead of the captured
 *    snapshot, or from a configuration assembled on the session path;
 *  * a session presenting a workout before its configuration and its adaptive plan exist, which is
 *    how an unconstrained generation reaches a running session;
 *  * the adaptive plan re-derived per subscriber rather than held once per session, so that
 *    navigating back into a running session could re-shape the workout it is presenting;
 *  * adaptive rules — a threshold, a state transition, a progression step — implemented in the view
 *    model rather than in the adaptive domain;
 *  * the integration reaching into storage, Android or the persistence layer, or building exercises
 *    itself instead of leaving the app's one concrete builder in charge;
 *  * a second workout generator, a second exercise catalogue or a second progression resolver
 *    appearing next to the existing ones.
 */
class AdaptiveGenerationWiringTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private fun source(relativePath: String): String {
        val candidate = File(mainSourceRoot, relativePath)
        assertTrue("expected $relativePath at ${candidate.absolutePath}", candidate.isFile)
        return candidate.readText()
    }

    private val viewModelText: String by lazy { source("viewmodel/MainViewModel.kt") }
    private val integrationText: String by lazy { source("domain/usecase/AdaptiveWorkoutIntegration.kt") }
    private val planText: String by lazy { source("domain/adaptive/AdaptiveProgressionPlan.kt") }
    private val generatorText: String by lazy { source("domain/usecase/WorkoutGenerator.kt") }

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

    // ---- the session generates from its own frozen configuration and its own plan ----------------

    @Test
    fun theSessionPresentsAWorkoutOnlyFromItsCapturedConfigurationAndPlan() {
        val sessionState = memberBody(viewModelText, "val workoutSessionUiState = combine(")

        assertTrue(
            "the session state must carry the captured configuration",
            "effectiveConfiguration" in sessionState
        )
        assertTrue(
            "the session state must carry the session's adaptive plan",
            "adaptivePlan" in sessionState
        )
        assertTrue(
            "a session must not present a workout before its configuration and plan exist",
            "day == null || effectiveConfiguration == null || adaptivePlan == null" in sessionState
        )
        assertTrue(
            "the session's workout must come from the plan-constrained generation path",
            "getWorkoutForDay(day, difficultyAdjustments, trainingType, focusAreas, availableEquipment, disabledFamilies, adaptivePlan)" in sessionState &&
                "getPostureMobilityWorkout(day, difficultyAdjustments, trainingType, focusAreas, availableEquipment, disabledFamilies, adaptivePlan)" in sessionState
        )
    }

    @Test
    fun theSessionsPlanIsDerivedFromTheFrozenSnapshotAndHeldOncePerSession() {
        val plan = memberBody(viewModelText, "private val sessionAdaptivePlan: StateFlow")
        assertTrue(
            "the plan must be derived from the session's snapshot, not from the configuration store",
            "sessionConfiguration" in plan
        )
        assertTrue(
            "the plan must be read once per session and held",
            "stateIn(viewModelScope, SharingStarted.Eagerly, null)" in plan
        )
        assertTrue(
            "the plan must be computed from the configuration the session captured",
            "readSessionAdaptivePlan(captured)" in plan
        )
    }

    @Test
    fun theSessionPathNeverReadsTheConfigurationStoreAgain() {
        // Task 12's rule, restated from Task 13's side: one capture per session, and no second read
        // of the repository anywhere — including from the adaptive inputs.
        assertEquals(
            "the persisted configuration is read once per session start, nowhere else",
            1,
            occurrences(viewModelText, "programConfigurationRepository.load()")
        )
        assertTrue(
            "a running session must not observe the repository's re-emitted configuration flow",
            "configurationFlow" !in viewModelText
        )
        assertTrue(
            "the adaptive plan must not read a configuration of its own",
            "ProgramConfigurationRepository" !in integrationText && "load()" !in integrationText
        )
    }

    // ---- the view model orchestrates and implements no adaptive rule ------------------------------

    @Test
    fun theViewModelImplementsNoAdaptiveRule() {
        listOf(
            "AdaptivePolicy", "AdaptiveSignalCalculator", "AdaptiveSignals", "ProgressionResolver",
            "AdaptiveProgressionPlan.of(", "PilotProgressionProfiles", "AdaptiveProgramEngine",
            "FamilyProgressionState", "AdaptiveRepository", "SessionHistoryAdapter"
        ).forEach { token ->
            assertTrue(
                "the view model orchestrates; $token belongs to the adaptive or persistence layer",
                token !in viewModelText
            )
        }
        assertTrue(
            "the view model must reach the adaptive domain only through the integration",
            "AdaptiveWorkoutIntegration(" in viewModelText
        )
        assertTrue(
            "the session's adaptive inputs must come from the one reader that owns that join",
            "sessionAdaptivePlanReader.read(" in viewModelText &&
                "SessionAdaptiveInputs(" in viewModelText
        )
    }

    // ---- the integration is an adapter, not a second implementation -------------------------------

    @Test
    fun theIntegrationDependsOnNoStorageAndroidOrUi() {
        val forbidden = listOf(
            "import android.", "import androidx.", "import com.monkfitness.app.data.repository.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel.",
            "Repository", "Room", "Dao", "@Entity", "DataStore", "preferencesDataStore",
            "ViewModel", "System.currentTimeMillis", "LocalDate"
        )
        val found = forbidden.filter { token -> token in integrationText }
        assertTrue("the integration must stay a pure adapter, found: $found", found.isEmpty())
    }

    @Test
    fun theWorkoutGeneratorRemainsTheOnlyConcreteBuilder() {
        assertTrue(
            "the integration must build the workout through the existing generator",
            "generator.generateWorkout(" in integrationText &&
                "generator.generatePostureMobilityWorkout(" in integrationText
        )
        assertTrue(
            "the integration must not construct exercises of its own",
            "Exercise(" !in integrationText && "allExercises" !in integrationText
        )
        // Exactly one generator is instantiated across the two production files that build workouts.
        assertEquals(
            "the app has one concrete workout builder",
            1,
            occurrences(integrationText, "WorkoutGenerator(")
        )
        assertTrue(
            "the generator's constraint is one funnel applied to every selection path",
            1 == occurrences(generatorText, "private fun getEligibleExercises(")
        )
    }

    @Test
    fun theAdaptiveReadsCannotMoveAStoredProgressionLevel() {
        // Generation reads adaptive state; it never writes it. A write path here would let a
        // generated workout move a family's level — including a recovery session undoing what the
        // user earned — so the reader has no write call of any kind.
        val readerText = source("data/repository/SessionAdaptivePlanReader.kt")
        listOf(
            "upsertFamilyState", "saveFamilyState", "appendDecision", "persistDecision",
            "@Insert", "@Delete", "@Update", ".edit(", "preferencesDataStore"
        ).forEach { writeToken ->
            assertTrue(
                "the session's adaptive read must not write: found $writeToken",
                writeToken !in readerText
            )
        }
        // It does construct Task 8's adapter — that is the documented access path, and its
        // transaction runner is a constructor argument it never exercises — but every call it makes
        // on it is a read.
        assertEquals(
            "the only adapter call the reader makes is the state read",
            1,
            occurrences(readerText, "adaptiveRepository.")
        )
        assertTrue(
            "the only adapter call the reader makes is the state read",
            "adaptiveRepository.familyStates(" in readerText
        )
        assertTrue(
            "the reader must take its reads as functions so the write path stays absent",
            "readHistory:" in readerText && "readFamilyStates:" in readerText
        )
    }

    @Test
    fun theProgressionPlanAddsNoSecondResolverOrLadder() {
        assertTrue(
            "the plan must resolve through the existing resolver",
            "ProgressionResolver.resolve(" in planText
        )
        assertTrue(
            "the plan must not restate a ladder or a level range of its own",
            "ProgressionStep(" !in planText.replace("ProgressionResolution", "") &&
                "MIN_LEVEL" in planText && "MAX_LEVEL" in planText
        )
        assertTrue(
            "the plan resolves no exercise the caller's configuration did not permit",
            "permittedExerciseIds" in planText
        )
    }
}
