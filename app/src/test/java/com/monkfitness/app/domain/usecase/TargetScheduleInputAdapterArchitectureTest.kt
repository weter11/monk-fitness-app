package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.target.ResolvedScheduleSource
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetSchedule
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mechanical boundary for the Phase 13 target scheduling input adapter.
 *
 * The adapter is the one place where caller-owned Program data becomes a target scheduling request,
 * so the properties worth pinning are the ones a behavioural test cannot see: where the file lives,
 * what it is allowed to name, and which legacy vocabulary it is forbidden to read.
 */
class TargetScheduleInputAdapterArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val adapterFile = File(mainDir, "domain/usecase/TargetScheduleInputAdapter.kt")
    private val targetDir = File(mainDir, "domain/program/target")

    // ------------------------------------------------------------------ placement

    @Test
    fun theAdapterLivesInDomainUsecaseAndNotInTheTargetPackage() {
        assertTrue(adapterFile.isFile)
        assertEquals("usecase", adapterFile.parentFile!!.name)
        assertFalse(adapterFile.parentFile!!.canonicalPath.contains(targetDir.canonicalPath))
        assertFalse(File(targetDir, "TargetScheduleInputAdapter.kt").exists())
    }

    @Test
    fun thePureTargetPackageGainedNoProductionFile() {
        // The adapter is an application boundary: putting it in the pure package would give the
        // package a caller-shaped concern, and Stage 2..9's file census is what keeps that package's
        // contents closed.
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().map { it.name }.sorted()

        assertEquals(
            listOf(
                "TargetOccurrenceComposer.kt",
                // §30 step 15: the target occurrence's stored execution read-back value, its pure
                // aggregation and its typed refusals. It is a pure value in this package for the same
                // reason as the Phase 14 entry below — the same semantic facts, read back — and it
                // holds no storage, no clock, no id generator and, deliberately, no execution verdict.
                "TargetOccurrenceExecutionRead.kt",
                // §30 step 14: the target occurrence's persisted semantic value and its typed
                // conflict. It is a pure value in this package because it is schedule *semantics* —
                // the same kind of thing the reconciler already holds — with no storage, no clock and
                // no id generator of its own. The list stays closed: a tenth file still fails here.
                "TargetOccurrencePersistence.kt",
                "TargetOccurrencePresenter.kt",
                "TargetOccurrenceReconciler.kt",
                "TargetPlanner.kt",
                "TargetSchedulePolicy.kt",
                "TargetScheduleResolver.kt",
                "TargetSlotMaterializer.kt"
            ),
            sources
        )
    }

    // ------------------------------------------------------------------ allowed dependencies

    @Test
    fun everyImportIsAPureDomainValueOrJvm() {
        val allowedPrefixes = listOf(
            "import com.monkfitness.app.domain.common.",
            "import com.monkfitness.app.domain.program.",
            "import java.time."
        )
        val imports = codeLines(adapterFile).filter { it.startsWith("import ") }

        assertTrue("expected the adapter to import its input values: $imports", imports.isNotEmpty())
        val offenders = imports.filterNot { line -> allowedPrefixes.any { line.startsWith(it) } }
        assertTrue("the adapter may reach pure domain values and java.time only: $offenders", offenders.isEmpty())
        assertEquals(
            listOf(
                "import com.monkfitness.app.domain.common.ProgramId",
                "import com.monkfitness.app.domain.common.RevisionId",
                "import com.monkfitness.app.domain.program.CompositionSelection",
                "import com.monkfitness.app.domain.program.ExistingOccurrence",
                "import com.monkfitness.app.domain.program.ProgramPauseWindow",
                "import com.monkfitness.app.domain.program.ScheduleCadence",
                "import com.monkfitness.app.domain.program.target.ResolvedScheduleSource",
                "import com.monkfitness.app.domain.program.target.TargetProgramDayBinding",
                "import com.monkfitness.app.domain.program.target.TargetSchedule",
                "import com.monkfitness.app.domain.program.target.TargetScheduleWindow",
                "import java.time.LocalDate"
            ),
            imports
        )
    }

    @Test
    fun theCompiledValuesOnlyDependOnJvmAndPureDomainTypes() {
        val types = listOf(
            TargetScheduleDefinition::class.java,
            TargetScheduleInput::class.java,
            TargetScheduleInputException::class.java,
            TargetScheduleInputAdapter::class.java
        )
        val offenders = types.flatMap { type ->
            type.declaredMethods.flatMap { method ->
                (method.parameterTypes.toList() + listOf(method.returnType)).mapNotNull { referenced ->
                    val name = if (referenced.isArray) referenced.componentType.name else referenced.name
                    val allowed = name.startsWith("kotlin.") || name.startsWith("java.") ||
                        name.startsWith("com.monkfitness.app.domain.") || referenced.isPrimitive
                    if (allowed) null else "${type.simpleName}.${method.name} references $name"
                }
            }
        }

        assertTrue("the input boundary may use JVM and pure domain values only: $offenders", offenders.isEmpty())
    }

    @Test
    fun theOnlyTargetTypesItNamesAreTheOnesItForwardsOrProduces() {
        val present = listOf(
            "TargetSchedule", "TargetScheduleWindow", "ResolvedScheduleSource", "TargetProgramDayBinding"
        ).filter { token -> code(adapterFile.readText()).contains(token) }

        assertEquals(present, present)
        listOf("TargetSchedule", "TargetScheduleWindow", "ResolvedScheduleSource", "TargetProgramDayBinding")
            .forEach { token -> assertTrue("the adapter must name $token", code(adapterFile.readText()).contains(token)) }
        // The binding is forwarded as a value; the adapter must never mint a ProgramDayId of its own,
        // which is the construction form every string-derived identity would have to take.
        assertTrue(
            "the adapter forwards TargetProgramDayBinding values and constructs none",
            codeLines(adapterFile).none { it.contains("ProgramDayId(") }
        )
    }

    // ------------------------------------------------------------------ forbidden dependencies

    @Test
    fun theAdapterNamesNoPlanningPolicyPresentationOrPersistenceComponent() {
        val forbidden = listOf(
            "TargetPlanner", "TargetSchedulePolicy", "TargetScheduleOrchestrator",
            "TargetScheduleApplicationService", "TargetScheduleSlotPersister", "TargetSlotMaterializer",
            "TargetScheduleResolver", "TargetOccurrenceComposer", "TargetOccurrenceReconciler",
            "TargetOccurrencePresenter", "TargetPlan", "TargetScheduleDecision",
            "TargetScheduleApplicationResult", "TargetScheduleSlotPersistenceInput",
            "PlannedOccurrence", "OccurrenceComponent", "WorkoutSlot", "SlotId", "TargetOccurrencePresentation"
        )
        val offenders = codeLines(adapterFile).filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }

        assertTrue("the adapter builds a request and runs nothing: $offenders", offenders.isEmpty())
    }

    @Test
    fun theAdapterHasNoRepositoryRuntimeStorageOrIdentityCollaborator() {
        val forbidden = listOf(
            "ProgramScheduleRepository", "ProgramPlanRepository", "ProgramRepository", "Dao", "Entity",
            "androidx.room", "AppDatabase", "Room", "Sqlite", "Clock", "IdGenerator", "Random", "UUID",
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "SessionRuntime", "WorkoutGenerator",
            "ProgramAdaptiveIntegration", "runBlocking", "suspend fun", "companion object"
        )
        val offenders = codeLines(adapterFile).filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }

        assertTrue("the input boundary has no collaborators at all: $offenders", offenders.isEmpty())
        assertEquals(
            emptyList<String>(),
            TargetScheduleInputAdapter::class.java.declaredFields
                .filterNot { it.name == "\$stable" }
                .map { it.name }
        )
        assertEquals(
            0,
            TargetScheduleInputAdapter::class.java.declaredConstructors.single().parameterCount
        )
    }

    @Test
    fun theAdapterReadsNoAmbientTimeOrRandomness() {
        val forbidden = listOf(
            "LocalDate.now", "Instant.now", "System.currentTimeMillis", "System.nanoTime", "clock",
            "Clock", "Random", "UUID", "hashCode()", "identityHashCode", "toString()"
        )
        val offenders = codeLines(adapterFile).filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }

        assertTrue("no ambient input: $offenders", offenders.isEmpty())
    }

    // ------------------------------------------------------------------ no legacy inference

    @Test
    fun theAdapterContainsNoLegacySchedulingVocabulary() {
        // The whole point of the stage. Each of these is a way a mapper could appear without anyone
        // adding a file: a `ProgramSchedule` branch, a day's position used as a rule id, a name used
        // as a workout id, an id's own text parsed into one.
        // `\\b`-anchored for the bare identifiers, because two of them are substrings of names this
        // file legitimately uses: `TargetProgramDayBinding` contains `ProgramDay`, and
        // `CompositionSelection` contains `position`. A substring match there would report the very
        // pass-through this boundary exists to guarantee as a violation.
        val forbiddenIdentifiers = listOf(
            "ProgramSchedule", "ProgramDay", "LegacySchedule", "LegacyScheduleMapper", "ScheduleRule",
            "ProgramDayType", "ProgramDayId", "position", "name", "DayOfWeek", "plannedFor",
            "occurrenceKey", "exerciseId"
        )
        val forbiddenPatterns = listOf("\\.name", "ProgramId\\.value", "RevisionId\\.value",
            "programDayId\\.value", "when \\(")
        val offenders = codeLines(adapterFile).filter { line ->
            forbiddenIdentifiers.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) } ||
                forbiddenPatterns.any { pattern -> Regex(pattern).containsMatchIn(line) }
        }

        assertTrue("target semantics must never be inferred from legacy scheduling: $offenders", offenders.isEmpty())
    }

    @Test
    fun noProductionSourceMapsALegacyScheduleOntoATargetSchedule() {
        // Stage 13's absence claim, asserted over the whole production tree rather than over the one
        // new file: a mapper is exactly the kind of thing that arrives in whichever file looks
        // convenient, so the guard cannot be scoped to the adapter.
        val offenders = productionSources().filter { (_, text) ->
            listOf("ProgramSchedule", "LegacySchedule", "LegacyScheduleMapper").any { text.contains(it) } &&
                listOf("TargetSchedule(", "TargetSchedule.daily", "TargetSchedule.everyNDays",
                    "TargetSchedule.fixedWeekdays", "TargetSchedule.sessionsPerWeek",
                    "TargetSchedule.derivedExcluding").any { text.contains(it) }
        }.map { (path, _) -> path }

        assertTrue("no implicit ProgramSchedule -> TargetSchedule mapping may exist: $offenders", offenders.isEmpty())
    }

    @Test
    fun theLegacyContourIsUntouchedAndUnwired() {
        val scheduler = File(mainDir, "domain/usecase/ProgramScheduler.kt")
        val planner = File(mainDir, "domain/program/SlotPlanner.kt")
        val calendar = File(mainDir, "domain/program/ScheduleCalendar.kt")
        val legacySchedule = File(mainDir, "domain/program/ProgramDuration.kt")

        assertTrue(scheduler.isFile && planner.isFile && calendar.isFile && legacySchedule.isFile)
        listOf(scheduler, planner, calendar).forEach { file ->
            assertTrue(
                "${file.name} must not reach the Stage 13 input boundary",
                !file.readText().contains("TargetScheduleInputAdapter") &&
                    !file.readText().contains("TargetScheduleDefinition") &&
                    !file.readText().contains("TargetScheduleInput")
            )
        }
        assertTrue("ProgramSchedule.kt must not exist as its own legacy file", !File(mainDir, "domain/program/ProgramSchedule.kt").exists())
        assertTrue(
            "the legacy declaration itself is untouched and still states its two forms",
            legacySchedule.readText().contains("sealed interface ProgramSchedule") &&
                legacySchedule.readText().contains("data class FixedWeekdays") &&
                legacySchedule.readText().contains("data class FlexiblePerWeek")
        )
        assertTrue(
            "and the Stage 1 compatibility mapping is still exactly that: a legacy mapping, not a target rule",
            File(mainDir, "domain/program/SemanticContracts.kt").readText()
                .contains("object LegacyScheduleMapper")
        )
    }

    // ------------------------------------------------------------------ the conversion itself

    @Test
    fun theRequestIsBuiltInOneExpressionPerFieldWithNoBranch() {
        val code = code(adapterFile.readText())
        val construction = code.substringAfter("return TargetScheduleOrchestrationRequest(")
            .substringBefore(")")
        val lines = construction.lines().map { it.trim() }.filter { it.isNotEmpty() }

        assertEquals(
            listOf(
                "programId = input.programId,",
                "revisionId = input.revisionId,",
                "schedules = schedules,",
                "window = input.window,",
                "selection = input.selection,",
                "existing = input.existingOccurrences,",
                "sources = input.sources,",
                "asOf = input.asOf,",
                "pauses = input.pauses,",
                "programDayBindings = input.programDayBindings"
            ),
            lines
        )
        assertEquals(1, occurrences(code, "TargetScheduleOrchestrationRequest("))
    }

    @Test
    fun theDefinitionConversionIsAFieldForFieldCopy() {
        val code = code(adapterFile.readText())
        val conversion = code.substringAfter("fun toTargetSchedule(): TargetSchedule = TargetSchedule(")
            .substringBefore(")")

        assertEquals(
            listOf(
                "ruleId = ruleId,",
                "workoutId = workoutId,",
                "cadence = cadence,",
                "anchorDate = anchorDate"
            ),
            conversion.lines().map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    @Test
    fun theOnlyValidationIsIdentityAndShape() {
        val code = codeLines(adapterFile)
        val allowedConditions = listOf("isBlank", "it.ruleId == definition.ruleId")
        val conditionLines = code.filter { it.contains("if (") || it.contains("require(") }
        val offenders = conditionLines.filter { line ->
            allowedConditions.none { line.contains(it) }
        }

        assertEquals(3, conditionLines.size)
        assertTrue("validation must stay about identity and shape: $offenders", offenders.isEmpty())
        // No business policy: eligibility, pause coverage, missed/future classification and
        // composition meaning are the downstream stages' questions.
        val policyVocabulary = listOf(
            "covers", "isPaused", "isBefore", "isAfter", "isAfter(", "MISSED", "FUTURE", "PAST",
            "SUPERSEDED", "RETAINED", "combinedRuleIds", "filter", "sorted", "distinct", "groupBy"
        )
        val policyOffenders = code.filter { line ->
            policyVocabulary.any { token -> line.contains(token) }
        }
        assertTrue("no business policy at an input boundary: $policyOffenders", policyOffenders.isEmpty())
    }

    @Test
    fun theTypedRefusalsAreTheOnesTheShapeCanFail() {
        val declared = TargetScheduleInputException::class.java.declaredClasses
            .filterNot { it.isInterface }
            .map { it.simpleName }
            .sorted()

        assertEquals(
            listOf(
                "BlankTargetRuleIdentity",
                "BlankTargetWorkoutIdentity",
                "DuplicateTargetRuleIdentity"
            ),
            declared
        )
        assertTrue(IllegalArgumentException::class.java.isAssignableFrom(TargetScheduleInputException::class.java))
    }

    // ------------------------------------------------------------------ composition root

    @Test
    fun appContainerWiresTheAdapterWithNoCollaborator() {
        val container = code(File(mainDir, "di/AppContainer.kt").readText())

        assertEquals(1, occurrences(container, "TargetScheduleInputAdapter()"))
        assertTrue(container.contains("val targetScheduleInputAdapter: TargetScheduleInputAdapter = TargetScheduleInputAdapter()"))
        val wiring = container.substringAfter("val targetScheduleInputAdapter")
            .substringBefore("val standardProgramBootstrap")
        val forbidden = listOf(
            "ProgramScheduleRepository", "ProgramPlanRepository", "ProgramRepository", "IdGenerator",
            "clock", "zone", "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "SessionRuntime"
        )
        val offenders = wiring.lines().filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }
        assertTrue("the adapter's wiring must carry nothing: $offenders", offenders.isEmpty())
    }

    @Test
    fun theAdapterIsNotWiredIntoTheSchedulerRuntimeUiOrAnyRepository() {
        val consumers = productionSources()
            .filterNot { (path, _) -> path == "di/AppContainer.kt" || path == "domain/usecase/TargetScheduleInputAdapter.kt" }
            .filter { (_, text) -> text.contains("TargetScheduleInputAdapter(") }
            .map { (path, _) -> path }

        assertEquals(
            "the adapter's only construction site is the composition root, and nothing consumes it yet",
            emptyList<String>(),
            consumers
        )
    }

    @Test
    fun theOrchestratedPipelineIsUnchangedByThisStage() {
        // The adapter sits *before* the orchestrator; it does not replace or reorder anything after
        // it. The Stage 12 pass still runs planner -> policy -> application.
        val orchestrator = code(File(mainDir, "domain/usecase/TargetScheduleOrchestrator.kt").readText())

        assertEquals(1, occurrences(orchestrator, "TargetPlanner.plan("))
        assertEquals(1, occurrences(orchestrator, "TargetSchedulePolicy.decide("))
        assertEquals(1, occurrences(orchestrator, "applicationService.apply("))
        assertEquals(
            emptyList<String>(),
            orchestrator.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .filter { it.contains("InputAdapter") || it.contains("TargetScheduleInput") }
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun productionSources(): List<Pair<String, String>> = File(mainDir.path)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { file -> file.relativeTo(mainDir).path.replace('\\', '/') to code(file.readText()) }
        .toList()

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun code(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun codeLines(file: File): List<String> = code(file.readText())
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
}
