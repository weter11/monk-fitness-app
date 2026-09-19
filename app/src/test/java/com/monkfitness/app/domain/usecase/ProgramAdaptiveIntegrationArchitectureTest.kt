package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationResult
import com.monkfitness.app.domain.adaptive.integration.AdaptiveTargetElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §30 step 12's dependency direction, pinned mechanically instead of by convention.
 *
 * The stage's brief is explicit about which way the arrows may point, and both directions are asserted
 * here against the sources themselves:
 *
 * ```text
 * ProgramAdaptiveIntegration  →  target repositories, the pure engine, Clock, IdGenerator   allowed
 * the engine                  →  any of those collaborators                                  forbidden
 * ProgramAdaptiveIntegration  →  the Stage-1 adaptive implementation                         forbidden
 * the Stage-1 implementation  →  anything this stage adds                                    forbidden
 * ```
 *
 * It also pins what the stage may **not** do at all, because each of those is a rule the architecture
 * states about the adaptive layer and a rule that only exists in prose erodes on the first convenient
 * call: no revision is created or rewritten, no slot is created, moved or superseded, no session is
 * started, and no generator, focus planner or progress layer is consulted.
 */
class ProgramAdaptiveIntegrationArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { if (it.isDirectory) it else File("app/$it") }

    /** §30 step 12's integration layer: the use case and the pure package it is stated in. */
    private val integrationSources = listOf(
        "domain/usecase/ProgramAdaptiveIntegration.kt",
        "domain/adaptive/integration/AdaptiveInputGap.kt",
        "domain/adaptive/integration/AdaptiveIntegrationOutcome.kt",
        "domain/adaptive/integration/AdaptiveIntegrationResult.kt",
        "domain/adaptive/integration/AdaptiveJudgement.kt",
        "domain/adaptive/integration/AdaptiveTargetSlot.kt",
        "domain/adaptive/integration/AdaptiveWindowRule.kt",
        "domain/adaptive/integration/ProgressionRelationProvider.kt"
    )

    /** §30 step 11's engine package: the pure component this stage consumes and must not change. */
    private val engineSources = listOf(
        "domain/adaptive/engine/ProgramAdaptiveElement.kt",
        "domain/adaptive/engine/ProgramProgressionRelation.kt",
        "domain/adaptive/engine/ProgramAdaptiveReason.kt",
        "domain/adaptive/engine/ProgramLoadComparison.kt",
        "domain/adaptive/engine/ProgramAggregateLoadGuard.kt",
        "domain/adaptive/engine/ProgramAdaptiveSignals.kt",
        "domain/adaptive/engine/ProgramAdaptivePolicy.kt",
        "domain/adaptive/engine/ProgramAdaptiveEngine.kt"
    )

    /** The shipped Stage-1 adaptive implementation §30 step 15 retires, untouched by this stage. */
    private val legacySources = listOf(
        "domain/adaptive/AdaptivePolicy.kt",
        "domain/adaptive/AdaptiveProgramEngine.kt",
        "domain/adaptive/AdaptiveSignalCalculator.kt",
        "domain/adaptive/AdaptiveDecision.kt",
        "domain/adaptive/ProgressionResolver.kt",
        "domain/adaptive/PilotProgressionProfiles.kt",
        "domain/usecase/AdaptiveWorkoutIntegration.kt",
        "data/repository/AdaptiveSessionDecisionRecorder.kt",
        "data/repository/AdaptiveRepository.kt"
    )

    // ------------------------------------------------------------------ the engine stays pure

    /**
     * The engine is a *pure domain component* (§11): it reaches no DAO, no Room type, no repository, no
     * Android type, no composition root, no clock and no random source. §30 step 12 consumes it and
     * changes nothing about that.
     */
    @Test
    fun theEngineStillReachesNoCollaboratorAtAll() {
        val forbidden = listOf(
            "import android", "import androidx", "import kotlinx",
            "import com.monkfitness.app.data.", "import com.monkfitness.app.ui.",
            "import com.monkfitness.app.viewmodel.", "import com.monkfitness.app.di.",
            "import com.monkfitness.app.domain.usecase.", "import com.monkfitness.app.domain.adaptive.integration.",
            "import com.monkfitness.app.R", "Clock", "IdGenerator", "Random", "UUID",
            "currentTimeMillis", "Instant.now", "System."
        )
        val offenders = codeLines(engineSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the engine runs entirely in the domain and reads neither a clock nor an id source: the " +
                "moment a decision is stamped with, and the identity it is recorded under, are the " +
                "caller's (§11, §26). Found: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * And the arrow runs one way only: the integration names the engine, and the engine names nothing
     * the integration adds — no gap, no outcome, no judgement, no port.
     */
    @Test
    fun theIntegrationDependsOnTheEngineAndTheEngineKnowsNothingOfTheIntegration() {
        val engineNames = listOf(
            "ProgramAdaptiveEngine", "ProgramAdaptivePolicy", "ProgramAggregateLoadGuard",
            "ProgramAdaptiveRequest", "ProgramAdaptiveResult", "ProgramProgressionRelation",
            "ProgramAdaptiveWindow", "ProgramAdaptiveElement"
        )
        assertTrue(
            "the integration is the caller the engine was built for: it assembles the request and " +
                "hands the result on",
            engineNames.any { name -> File(mainDir, integrationSources.first()).readText().contains(name) }
        )

        val integrationNames = listOf(
            "AdaptiveInputGap", "AdaptiveIntegrationOutcome", "AdaptiveIntegrationResult",
            "AdaptiveJudgement", "AdaptiveTargetSlot", "AdaptiveWindowRule", "PresentedElement",
            "ProgressionRelationProvider", "ExerciseFamilyClassification", "ProgramAdaptiveIntegration"
        )
        val offenders = legacyAndEngineNames(engineSources + legacySources, integrationNames)

        assertTrue(
            "the engine and the Stage-1 generation are both unaware of the integration: an engine that " +
                "knew its caller would be a component with a collaborator. Found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ the integration's own reach

    /** The integration may reach the target repositories, the clock and the id source — and that is all. */
    @Test
    fun theIntegrationReachesExactlyTheRepositoriesTheClockAndTheIdSource() {
        val allowed = listOf(
            "ProgramAdaptiveRepository", "ProgramPlanRepository", "ProgramScheduleRepository",
            "WorkoutSessionRepository", "Clock", "IdGenerator"
        )
        val present = allowed.filter { name ->
            File(mainDir, "domain/usecase/ProgramAdaptiveIntegration.kt").readText().contains(name)
        }

        assertEquals(
            "every collaborator the stage is allowed is one it actually uses, so this list cannot " +
                "quietly grow a slot",
            allowed.sorted(),
            present.sorted()
        )
    }

    /**
     * It writes nothing, creates nothing and moves nothing: no revision is saved, no slot is created,
     * renumbered or superseded, no session is started, and no generator, planner or progress layer is
     * consulted (§6, §20, §25, §26).
     */
    @Test
    fun theIntegrationCreatesNoRevisionNoSlotAndNoSession() {
        val forbidden = listOf(
            "saveNewRevision", "updateProgram", "setCurrentRevision",
            "addSlots", "insertSlots", "recordSlotOutcome", "updateOutcome",
            "startSession", "confirmSet", "cancelSession", "finishSession",
            "GeneratedPlanner", "FocusPlanner", "PlanReconciler", "ProgramScheduler",
            "ProgramProgressRepository", "ProgramProgressService", "ProgramEditorService",
            "WorkoutGenerator", "getExerciseLibrary", "SettingsManager", "ProgramCalendar"
        )
        val offenders = codeLines(integrationSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "an adaptive adjustment changes *presentation*, not opportunity timing, and this stage " +
                "prepares a value rather than writing one: no slot is created, no date moves, no " +
                "revision is saved, no session is started and no planner is called. Found: $offenders",
            offenders.isEmpty()
        )
    }

    /** The moment the decision is stamped with is read once per pass, from the injected clock. */
    @Test
    fun theIntegrationReadsTheClockOnceAndOnlyAtItsOwnBoundary() {
        val lines = codeLines(listOf("domain/usecase/ProgramAdaptiveIntegration.kt"))
            .count { (_, line) -> line.contains("clock.now()") }

        assertEquals(
            "one moment per pass (§26): the window is captured at it, the target opportunity is chosen " +
                "against its own day, and every row the completion writes is stamped with it",
            1,
            lines
        )
    }

    // ------------------------------------------------------------------ the legacy boundary

    /** §30 step 12 does not reach the Stage-1 adaptive generation, in either direction. */
    @Test
    fun neitherDirectionOfTheStageOneBoundaryIsCrossed() {
        val legacyNames = listOf(
            "AdaptivePolicy", "AdaptiveProgramEngine", "AdaptiveSignalCalculator", "AdaptiveSignals",
            "ProgressionResolver", "PilotProgressionProfiles", "AdaptiveWorkoutIntegration",
            "AdaptiveSessionDecisionRecorder", "AdaptiveRepository", "AdaptiveDecisionRecord",
            "FamilyAdaptationState", "AdaptiveProgramInput", "AdaptiveProgressionPlan"
        )
        val offenders = legacyNames.flatMap { name ->
            (integrationSources + engineSources).filter { source ->
                wholeToken(codeText(File(mainDir, source)), name)
            }.map { "$it names $name" }
        }

        assertTrue(
            "the target stage has its own persistence, its own policy and its own ladders: the Stage-1 " +
                "generation is retired in step 15 and is neither consumed nor extended here. Found: " +
                "$offenders",
            offenders.isEmpty()
        )

        // The legacy files are still there, and still name none of this stage's types. A file that has
        // been deleted cannot name anything, so the two assertions are one claim.
        legacySources.forEach { source ->
            assertTrue("$source must still exist: this stage removes nothing", File(mainDir, source).isFile)
        }
        val inverse = legacyAndEngineNames(legacySources, integrationNamesOfThisStage())
        assertTrue(
            "the two generations do not depend on each other in either direction (§30 step 11 keeps " +
                "them separate until step 15 collapses them). Found: $inverse",
            inverse.isEmpty()
        )
    }

    /** The Stage-1 adaptive *tables* are not named by the integration either: the two are disjoint. */
    @Test
    fun theIntegrationNamesNoneOfTheStageOneTables() {
        val offenders = integrationSources.filter { source ->
            val text = File(mainDir, source).readText()
            listOf("`family_progression_state`", "`adaptive_decision_record`", "familyProgressionStateDao", "adaptiveDecisionHistoryDao")
                .any { text.contains(it) }
        }

        assertTrue(
            "the target repository serves the target tables and the Stage-1 adapter serves the legacy " +
                "pair: a write through one is invisible to the other, and neither is named here. " +
                "Found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ no UI, and the shapes

    /**
     * §4's target rule has two halves — the producer *chooses* the opportunity against the decision's own
     * day, and the runtime *checks* that choice against the decision's own moment — and they are the same
     * rule only if they read the same calendar. The composition root owns that one value and hands it to
     * both, which is the mechanical form of *"the producer and the consumer have the same semantics"*.
     */
    @Test
    fun theCompositionRootHandsTheProducerAndTheConsumerOneCalendar() {
        val container = File(mainDir, "di/AppContainer.kt").readText()
        val integration = File(mainDir, "domain/usecase/ProgramAdaptiveIntegration.kt").readText()
        val runtime = File(mainDir, "domain/usecase/SessionRuntime.kt").readText()

        assertTrue(
            "the container owns one calendar value",
            container.contains("val zone: ZoneId = ZoneId.systemDefault()")
        )
        assertEquals(
            "and hands that same value to both sides of the rule — once to the runtime, once to the " +
                "integration — plus, since §30 step 13, to the import, which plans an imported revision's " +
                "opportunities in the same calendar the rest of the graph reads dates in. That third " +
                "hand-over *strengthens* this rule rather than relaxing it: every layer that turns an " +
                "instant into a date is still handed the one value the container owns.",
            3,
            Regex(Regex.escape("zone = zone")).findAll(container).count()
        )
        assertTrue(
            "the producer reads the instant it captured the window at",
            integration.contains("notBefore = LocalDate.ofInstant(capturedAt, zone)")
        )
        assertTrue(
            "the consumer reads the moment the decision was taken on — not a fresh clock reading",
            runtime.contains("LocalDate.ofInstant(decision.decidedAt, zone)")
        )
        assertTrue(
            "and neither side acquires a calendar of its own",
            !integration.contains("ZoneId.systemDefault()") && !runtime.contains("ZoneId.systemDefault()")
        )
    }

    /** No ViewModel and no screen reaches the integration or the engine yet (§23 of the brief). */
    @Test
    fun noViewModelOrScreenReachesTheAdaptiveStage() {
        val names = listOf("ProgramAdaptiveIntegration", "ProgramAdaptiveEngine", "ProgramAdaptivePolicy")
        val offenders = listOf(File(mainDir, "ui"), File(mainDir, "viewmodel"))
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filter { source -> names.any { source.readText().contains(it) } }
            .map { it.name }
            .toList()

        assertTrue(
            "this stage lands a use case and a value vocabulary: the UI integration is a later step and " +
                "this test is what says so. Found: $offenders",
            offenders.isEmpty()
        )
    }

    /** §20's four outcomes and §28's three classes are exactly the shapes the stage reports. */
    @Test
    fun theOutcomeAndResultVocabulariesAreTheOnesTheStageStates() {
        assertEquals(
            "one shape per materially different answer, and none of them is null, an empty list or " +
                "false: a change was applied, a change was refused, nothing was adapted, or no window " +
                "could be built",
            listOf("AdaptiveApplied", "AdaptiveFiltered", "CannotBuildAdaptiveRequest", "NothingToAdapt"),
            shapesOf(AdaptiveIntegrationOutcome::class.java)
        )
        assertEquals(
            "§28's classes: an outcome, invalid stored data, and a failure — CONFLICT is absent because " +
                "nothing in this pass can be in conflict",
            listOf("Failure", "InvalidData", "Success"),
            shapesOf(AdaptiveIntegrationResult::class.java)
        )
        assertEquals(
            "and §10's element choice reports its own gaps apart from its answer",
            listOf("Chosen", "NoDeclaredRelation", "NoExposedFamilyIsPresented", "NoFamilyIsClassified"),
            shapesOf(AdaptiveTargetElement::class.java)
        )
    }

    /**
     * The reason a decision carries is part of the record, and the decision is the only domain value
     * that carries it: a second copy of the answer somewhere else would be a second answer.
     */
    @Test
    fun theReasonTravelsOnTheDecisionAndOnNothingBesideIt() {
        val decisionFields = com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision::class.java
            .declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
            .map { it.name }
        assertTrue(
            "the stored reason is read back on the decision, which is the record it belongs to",
            decisionFields.contains("reason")
        )
        val adjustFields = com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment::class.java
            .declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
            .map { it.name }
        assertTrue(
            "an adjustment is a before/after of one element and states no reason of its own",
            adjustFields.none { it == "reason" }
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun integrationNamesOfThisStage(): List<String> = listOf(
        "AdaptiveInputGap", "AdaptiveIntegrationOutcome", "AdaptiveIntegrationResult",
        "AdaptiveJudgement", "AdaptiveTargetSlot", "AdaptiveWindowRule", "PresentedElement",
        "ProgressionRelationProvider", "ExerciseFamilyClassification", "ProgramAdaptiveIntegration",
        "AdaptiveTargetElement"
    )

    /** The files among [sources] that name one of [names] in whole-token form, comments excluded. */
    private fun legacyAndEngineNames(sources: List<String>, names: List<String>): List<String> =
        sources.flatMap { source ->
            val text = codeText(File(mainDir, source))
            names.filter { wholeToken(text, it) }.map { "$source names $it" }
        }

    /** One source with its comments removed: a KDoc that *names* a legacy type is not a dependency. */
    private fun codeText(file: File): String = file.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    /** The declared shapes of one sealed vocabulary, in name order, without Kotlin's synthetic holders. */
    private fun shapesOf(type: Class<*>): List<String> = type.declaredClasses
        .filterNot { it.isInterface || it.simpleName == "DefaultImpls" }
        .map { it.simpleName }
        .sorted()

    /** Whether [text] contains [token] as a whole identifier, not as part of a longer name. */
    private fun wholeToken(text: String, token: String): Boolean =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])").containsMatchIn(text)

    /** The code lines of each source, with comments removed: the rules above are rules about code. */
    private fun codeLines(sources: List<String>): List<Pair<String, String>> = sources.flatMap { source ->
        val file = File(mainDir, source)
        assertTrue("expected $source", file.isFile)
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .map { source.substringAfterLast("/") to it }
    }
}
