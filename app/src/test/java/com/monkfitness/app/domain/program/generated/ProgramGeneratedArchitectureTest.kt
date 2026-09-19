package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * §30 step 10's boundaries, pinned mechanically instead of by convention.
 *
 * The Generated Planner is the first layer of the Program System that *decides what a program
 * contains*, and every temptation it has is one import away: a random source "to make the plans
 * varied", a clock "to know how far into the program we are", a repository "to read what the user
 * did lately", a scheduler "to place the plan on the calendar", the legacy `WorkoutGenerator` "since
 * it already builds workouts", a dose of adaptive policy "because the signals are right there". §25,
 * §26, §33 and §30's own stage order forbid every one of them, and the rules are asserted against the
 * sources and the compiled shape:
 *
 *  * the generated planner's sources reach no DAO, no Room type, no Android type, no UI type and no
 *    ambient time source;
 *  * no uncontrolled random source appears anywhere in it — not `Random`, not `shuffled`, not
 *    `Math.random` — because §9 makes determinism an architectural invariant rather than a style;
 *  * the legacy `WorkoutGenerator`, the app's presentation resources and the exercise catalogue are
 *    absent, so generation cannot quietly become the legacy engine;
 *  * the adaptive layer appears only as the *plain input values* §30 step 10 allows (a recovery
 *    context, a set of caller-supplied counts) and never as a policy, an engine, a resolver or a
 *    repository;
 *  * the decision is pure: its compiled shape may only mention the domain, `kotlin.` and `java.`,
 *    and the planner and the focus planner declare no state at all — a plan is a function of its
 *    request, which is what makes the same inputs produce the same plan;
 *  * the Scheduler is not invoked and its boundary is not crossed: no date, no slot, no session, no
 *    revision and no repository is expressible in the request or the plan.
 */
class ProgramGeneratedArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { if (it.isDirectory) it else File("app/$it") }

    /** The files this stage adds, all under the package the foundation's purity scan already fences. */
    private val generatedSources = listOf(
        "domain/program/generated/GenerationPolicy.kt",
        "domain/program/generated/GenerationRequest.kt",
        "domain/program/generated/GeneratedPlan.kt",
        "domain/program/generated/FocusPlanner.kt",
        "domain/program/generated/ExerciseSelector.kt",
        "domain/program/generated/GeneratedPlanner.kt",
        "domain/program/generated/PlanReconciler.kt",
        "domain/program/generated/ProgramGeneratedEditor.kt"
    )

    /** The vocabulary this stage adds beside the editor's own, in the fenced package. */
    private val vocabularySource = "domain/program/FocusPlan.kt"

    private val allSources = generatedSources + vocabularySource

    // ------------------------------------------------------------------ layering

    @Test
    fun theGeneratedPlannerReachesNoDaoNoRoomAndNoUi() {
        val forbidden = listOf(
            "import android", "import androidx", "import kotlinx",
            "import com.monkfitness.app.data.local", "import com.monkfitness.app.data.model",
            "import com.monkfitness.app.data.repository", "import com.monkfitness.app.ui",
            "import com.monkfitness.app.viewmodel", "import com.monkfitness.app.animation",
            "import com.monkfitness.app.poses", "import com.monkfitness.app.di",
            "import com.monkfitness.app.R"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.startsWith(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the planner runs entirely in the domain: no DAO, no Room entity, no repository, no " +
                "Android type and no UI type may reach it (§25). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noSourceOfTheGeneratedPlannerNamesADaoAnEntityOrARoomAnnotation() {
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            when {
                Regex("""\b\w*Dao\b""").containsMatchIn(line) -> "$source: $line"
                Regex("""\b\w*Entity\b""").containsMatchIn(line) -> "$source: $line"
                line.contains("@Entity") || line.contains("@Dao") || line.contains("@Database") ->
                    "$source: $line"
                else -> null
            }
        }

        assertTrue(
            "there is no second persistence model in the planner and no DAO access (§25, §33): a " +
                "generated plan is a value, and persistence goes through the editor's Save. " +
                "Found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ determinism (§9)

    @Test
    fun noSourceOfTheGeneratedPlannerReadsAnUncontrolledRandomOrAmbientTimeSource() {
        val forbidden = listOf(
            "Random", "shuffled", "shuffle(", "Math.random", "currentTimeMillis", "System.nanoTime",
            "LocalDate.now", "LocalTime.now", "Instant.now", "Clock", "UUID", "hashCode()"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "§9's determinism is an invariant and not a style: no uncontrolled random source, no " +
                "ambient clock and no hash-derived ordering may reach generation. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePlannerCannotReachForTheLegacyGeneratorOrTheExerciseCatalogue() {
        // The prohibitions that are about a *name*: a member, a type or a resource, matched as a whole
        // token so `WorkoutGenerator` is caught and `usesOfExercise()` is not mistaken for something
        // else.
        val forbiddenTokens = listOf(
            "WorkoutGenerator", "getExerciseLibrary", "ExerciseSkeletonData", "PoseRegistry",
            "SettingsManager", "R.string", "R.drawable"
        )
        val tokenOffenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbiddenTokens.firstOrNull { token ->
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                    .containsMatchIn(line)
            }?.let { "$source: $line" }
        }

        // And one prohibition that is about a *call*: the legacy catalogue's own type, constructed
        // directly. `ExerciseMetadata(...)` composes the app's own metadata value — the boundary this
        // stage is allowed to read — so the pattern is a bare constructor call with a token boundary
        // on the left, which is what keeps `usesOfExercise()` out of the finding.
        val catalogueCall = Regex("(?<![A-Za-z0-9_])Exercise\\s*\\(")
        val callOffenders = codeLines(allSources).mapNotNull { (source, line) ->
            if (catalogueCall.containsMatchIn(line)) "$source: $line" else null
        }

        val offenders = tokenOffenders + callOffenders
        assertTrue(
            "generation is a new pure planner and not the legacy engine: the legacy WorkoutGenerator, " +
                "the legacy exercise catalogue and Android presentation resources are absent (§30 " +
                "step 10's scope fence). Found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ the adaptive boundary

    @Test
    fun theAdaptiveLayerAppearsOnlyAsPlainInputValues() {
        val forbidden = listOf(
            "AdaptivePolicy", "AdaptiveProgramEngine", "AdaptiveEngine", "ProgressionResolver",
            "AdaptiveRepository", "ProgramAdaptiveRepository", "FamilyProgressionState",
            "AdaptiveDecision", "AdaptiveAdjustment", "AdaptiveSignal", "PilotProgressionProfiles",
            "AdaptiveState", "LoadGuard", "Evidence"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "§30 step 10 is *before* the Adaptive Engine: the planner takes plain signals and computes " +
                "no evidence, no policy, no recovery decision and no adjustment. Found: $offenders",
            offenders.isEmpty()
        )

        // The one adaptive *value* the planner is allowed to read is §14's own recovery vocabulary,
        // and it is read as the input it is: the type is named once, in the request's preferences.
        val recoveryReferences = codeLines(allSources).filter { (_, line) -> line.contains("RecoveryContext") }
        assertTrue(
            "§14's recovery context is the one adaptive value in the boundary, and it is read as a " +
                "plain input — a field type and two comparisons, not a policy: $recoveryReferences",
            recoveryReferences.size in 1..8
        )
        assertEquals(
            "and it is the vocabulary the domain already owns, not a second one invented here",
            listOf("FAVORABLE", "CAUTIOUS", "UNKNOWN"),
            RecoveryContext.entries.map { it.name }
        )
    }

    // ------------------------------------------------------------------ the scheduler boundary

    @Test
    fun theSchedulerIsNeverInvokedAndItsBoundaryIsNeverCrossed() {
        val forbidden = listOf(
            "SlotPlanner", "SlotPlan", "ScheduleRequest", "ProgramScheduler", "ProgramScheduleRepository",
            "WorkoutSlot", "SlotId", "SlotStatus", "WorkoutSession", "SessionId", "SessionStatus",
            "WorkoutSessionRepository", "ProgramRevision(", "RevisionId", "ProgramId(",
            "LocalDate", "DayOfWeek", "Instant"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "§20 and §30 step 7 keep scheduling the Scheduler's: a plan is a number of days and a " +
                "focus each, and no date, slot, session or revision identity is expressible here. " +
                "Found: $offenders",
            offenders.isEmpty()
        )

        assertEquals(
            "the editor integration holds exactly the draft and the identity source, like the manual " +
                "editor — no repository to save through, no scheduler to plan with",
            listOf("draft", "ids"),
            ProgramGeneratedEditor::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
        )
    }

    @Test
    fun theSchedulerAndTheEditorServiceAreUntouchedByThisStage() {
        val service = File(mainDir, "domain/usecase/ProgramEditorService.kt").readText()
        val scheduler = File(mainDir, "domain/usecase/ProgramScheduler.kt").readText()

        assertTrue(
            "the save path knows nothing about generation: it is the same single persistence " +
                "boundary, and a generated draft reaches it as an ordinary draft (§7: Save is not " +
                "duplicated, and generation is not made the editor's business)",
            !service.contains("GeneratedPlanner") && !service.contains("ProgramGeneratedEditor")
        )
        assertTrue(
            "and the Scheduler is not rewritten to plan around a generated plan",
            !scheduler.contains("GeneratedPlanner") && !scheduler.contains("GeneratedPlan")
        )
    }

    // ------------------------------------------------------------------ the pure half

    @Test
    fun thePureHalfOfThePlannerSeesNothingButKotlinJavaAndTheDomain() {
        val pureTypes = listOf(
            FocusPlan::class.java,
            Goal::class.java,
            Focus::class.java,
            FocusAllocation::class.java,
            GenerationPolicy::class.java,
            GenerationCandidate::class.java,
            GenerationPreferences::class.java,
            GenerationRequest::class.java,
            FocusAssignment::class.java,
            GeneratedElement::class.java,
            GeneratedSlot::class.java,
            GeneratedPlan::class.java,
            FocusUnusableReason::class.java,
            GenerationLimitation::class.java,
            PreservationLevel::class.java,
            ChangeKind::class.java,
            ReconciliationChange::class.java,
            ReconciliationReport::class.java,
            ReconciledPlan::class.java,
            GeneratedDraftEdit::class.java
        )

        val offenders = pureTypes.flatMap { type ->
            type.declaredMethods.flatMap { method ->
                (method.parameterTypes.toList() + listOf(method.returnType)).mapNotNull { referenced ->
                    val referencedName =
                        if (referenced.isArray) referenced.componentType.name else referenced.name
                    val allowed = referencedName.startsWith("kotlin.") ||
                        referencedName.startsWith("java.") ||
                        referencedName.startsWith("com.monkfitness.app.domain.") ||
                        referenced.isPrimitive
                    if (allowed) null else "${type.simpleName}.${method.name} references $referencedName"
                }
            }
        }

        assertTrue(
            "every generated value is pure: its compiled shape may only mention the domain, `kotlin.` " +
                "and `java.`. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePlannersHoldNoStateAndCannotBeToldWhatHappenedBefore() {
        listOf(
            FocusPlanner::class.java,
            ExerciseSelector::class.java,
            GeneratedPlanner::class.java,
            PlanReconciler::class.java
        ).forEach { planner ->
            val fields = planner.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            assertTrue(
                "${planner.simpleName} holds nothing: a pass is a function of its arguments, which is " +
                    "what makes the same request produce the same plan (§26, §33). Found: " +
                    fields.map { it.name },
                fields.isEmpty()
            )
        }

        assertTrue(
            "and the planner is an object, so there is no instance state to configure either",
            Modifier.isFinal(GeneratedPlanner::class.java.modifiers)
        )
    }

    @Test
    fun thePlanCarriesNoClockNoDateAndNoAmountOfWork() {
        val forbidden = listOf("moment", "timestamp", "date", "score", "performance", "volume")
        val offenders = GeneratedPlan::class.java.declaredMethods
            .map { it.name }
            .mapNotNull { name ->
                forbidden.firstOrNull { name.contains(it, ignoreCase = true) }?.let { "GeneratedPlan.$name" }
            }

        assertTrue(
            "a plan is a structure, not a measurement and not a schedule: nothing on it names a date, " +
                "a moment, a score or an amount of work (§12, §17, §20). Found: $offenders",
            offenders.isEmpty()
        )
        assertEquals(
            "the plan's own fields are exactly the configuration, the slots and what could not be " +
                "planned — no identity, no clock, no repository",
            listOf("focus", "slots", "limitations"),
            GeneratedPlan::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
        )
    }

    @Test
    fun thePureHalfLivesWhereTheFoundationPurityScanCanSeeIt() {
        allSources.forEach { source ->
            assertTrue(
                "$source must stay in a package `ProgramDomainPurityTest` fences against Android, " +
                    "Room, the data layer, mutable state and invented arithmetic",
                source.startsWith("domain/program") && File(mainDir, source).isFile
            )
        }

        val puritySource = File("src/test/java/com/monkfitness/app/domain/ProgramDomainPurityTest.kt")
            .let { if (it.isFile) it else File("app/$it") }
        assertTrue("expected the foundation purity test", puritySource.isFile)
        assertTrue(
            "and that scan must still read the whole `program` package, subpackages included — which " +
                "is what makes this stage's no-`var`, no-floating-point and no-`Random` rules apply " +
                "to it without a second scan",
            puritySource.readText().contains("\"program\"") &&
                puritySource.readText().contains("walkTopDown")
        )
    }

    @Test
    fun theCompositionRootIsNotAskedToWireWhatNothingConsumes() {
        val container = File(mainDir, "di/AppContainer.kt").readText()

        assertFalse(
            "the generated planner has no consumer yet — no ViewModel, no screen — so wiring it into " +
                "the composition root would be a dependency nothing uses (§26: a graph node exists " +
                "because something needs it)",
            container.contains("ProgramGeneratedEditor") || container.contains("GeneratedPlanner")
        )
        assertTrue(
            "and the editor the stage's Save path already goes through stays exactly what it was",
            container.contains("val programEditorService: ProgramEditorService = ProgramEditorService(")
        )
    }

    @Test
    fun nothingInTheUiReachesGeneration() {
        val uiReach = File(mainDir, "ui").walkTopDown()
            .plus(File(mainDir, "viewmodel").walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("GeneratedPlanner") || it.readText().contains("ProgramGeneratedEditor") }
            .map { it.name }
            .toList()

        assertTrue(
            "no ViewModel and no screen reaches the generated planner yet: the UI integration is a " +
                "later step, and this test is what says so. Found: $uiReach",
            uiReach.isEmpty()
        )
    }

    // ------------------------------------------------------------------ helpers

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
