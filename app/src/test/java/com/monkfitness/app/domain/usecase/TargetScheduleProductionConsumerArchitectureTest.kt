package com.monkfitness.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The mechanical boundary of §30 step 20 — the first production consumer of the target contour.
 *
 * A consumer's obligations are almost entirely about what it must **not** do, and a prohibition that
 * lives only in a KDoc erodes the first time a convenient import appears. So each is asserted here as a
 * token or a shape in real code, with comments stripped first — which is what lets this stage's own
 * prose say "never reads the legacy schedule" without tripping the rule about reading it.
 *
 * ```text
 *  1. the consumer is an application boundary, and the pure target package gained no file
 *  2. its collaborator set is exactly the seven the read/assembly/run boundary needs
 *  3. it names no legacy scheduling vocabulary, so it cannot manufacture a rule from one
 *  4. it reimplements no target stage: the adapter converts and the orchestrator runs
 *  5. it decides no execution state: no occurrence value, no slot status, no aggregation
 *  6. it holds no clock, no identity, no ambient state, and translates no failure
 *  7. the absent source is a return, and no branch manufactures an empty one
 *  8. the five caller-owned values are forwarded, not defaulted, sorted or filtered
 *  9. the result vocabulary is exactly the six outcomes the stage names
 * 10. the composition root wires it once, and nothing above the root calls it
 * 11. the legacy planning path is unchanged and uncalled by the consumer
 * ```
 */
class TargetScheduleProductionConsumerArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val usecaseDir = File(mainDir, "domain/usecase")
    private val targetDir = File(mainDir, "domain/program/target")
    private val consumerFile = File(usecaseDir, "TargetScheduleProductionConsumer.kt")
    private val containerFile = File(mainDir, "di/AppContainer.kt")

    // ---- 1. placement -------------------------------------------------------------------------------

    @Test
    fun theConsumerIsAnApplicationBoundaryAndThePureTargetPackageGainedNoFile() {
        assertTrue(consumerFile.isFile)
        assertEquals("usecase", consumerFile.parentFile.name)
        assertFalse(consumerFile.parentFile.canonicalPath.contains(targetDir.canonicalPath))
        assertFalse(File(targetDir, "TargetScheduleProductionConsumer.kt").exists())

        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().map { it.name }.sorted()

        assertEquals(
            "the pure target package is unchanged by this stage: the consumer is an application " +
                "boundary, so putting it there would give the package a caller-shaped concern. The " +
                "list stays closed — a twelfth file still fails here.",
            listOf(
                "TargetExistingOccurrence.kt",
                "TargetOccurrenceComposer.kt",
                "TargetOccurrenceExecutionPolicy.kt",
                "TargetOccurrenceExecutionRead.kt",
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

    // ---- 2. collaborators ---------------------------------------------------------------------------

    @Test
    fun theCollaboratorSetIsExactlyTheReadAssemblyAndRunBoundary() {
        val declared = Regex("private val (\\w+): (\\w+)").findAll(code(consumerFile.readText()))
            .map { it.groupValues[1] to it.groupValues[2] }.toList()

        assertEquals(
            "the consumer holds the two revision-reading repositories, the source bridge, the " +
                "occurrence repository, the execution bridge, the input adapter and the orchestrator — " +
                "and nothing else. A collaborator it has no use for is one it must not be able to consult.",
            listOf(
                "programRepository" to "ProgramRepository",
                "planRepository" to "ProgramPlanRepository",
                "sourceBridge" to "TargetScheduleSourceBridge",
                "occurrenceRepository" to "TargetScheduleOccurrenceRepository",
                "existingOccurrenceReader" to "TargetExistingOccurrenceReader",
                "inputAdapter" to "TargetScheduleInputAdapter",
                "orchestrator" to "TargetScheduleOrchestrator"
            ),
            declared
        )
        assertEquals(
            7,
            TargetScheduleProductionConsumer::class.java.declaredConstructors.single().parameterCount
        )
    }

    @Test
    fun theConsumerNamesNoStorageTypeAtAllAndTheRuleFiresOnTheLineACarelessCallerWouldWrite() {
        // A whole-token ban on "Dao" cannot see a qualified `…SourceDao`, because the token is glued
        // to a longer identifier. The claim is therefore stated as a *path*: no source of this file
        // may name the storage package or the database class at all, which is a rule a careless
        // caller breaks in exactly one way and cannot hide behind a name.
        val storageReferences = listOf("com.monkfitness.app.data.local", "androidx.room", "AppDatabase")
        val offenders = codeLines(consumerFile).filter { line ->
            storageReferences.any { reference -> line.contains(reference) }
        }
        assertTrue("the consumer reaches storage only through the repositories it was handed: $offenders", offenders.isEmpty())

        // The positivity of the ban, so it is a rule rather than a statement about today's tree.
        val careless = "    val dao = com.monkfitness.app.data.local.ProgramTargetScheduleSourceDao"
        assertTrue(
            "the rule fires on the line a careless caller would write",
            storageReferences.any { reference -> careless.contains(reference) }
        )
        assertFalse(
            "and does not fire on the consumer's own collaborator",
            storageReferences.any { reference -> "TargetScheduleOccurrenceRepository".contains(reference) }
        )
    }

    @Test
    fun theConsumerHoldsNoIdentityGeneratorClockStorageOrUiState() {
        val forbidden = listOf(
            "IdGenerator", "Clock", "ZoneId", "androidx.room", "AppDatabase", "Dao", "Entity",
            "Sqlite", "Room", "MutableState", "mutableStateOf", "lateinit", "Random", "UUID",
            "WorkoutGenerator", "StandardProgramBootstrap", "ProgramScheduler", "SlotPlanner",
            "ScheduleCalendar", "ScheduleWindow", "PLANNING_HORIZON_DAYS", "ProgramPause",
            "plannedStartDate", "actualStartDate", "Companion"
        )
        val offenders = codeLines(consumerFile).filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }

        assertTrue(
            "the consumer is handed no identity, no clock, no storage handle and no UI state: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- 3. no legacy inference ---------------------------------------------------------------------

    @Test
    fun theConsumerNamesNoLegacySchedulingVocabulary() {
        // Each of these is a way a mapper could appear without anyone adding a file: a `ProgramSchedule`
        // branch, a revision's schedule columns, a day position or name used as a rule or workout
        // identity, a plan date used as an anchor. The consumer is where all of those would live, so
        // the ban belongs here and not only on the adapter.
        //
        // Whole-token with `_` a word character, because `ProgramScheduler` *contains* `ProgramSchedule`
        // — and a substring scan would refuse the very collaborator list above.
        val forbiddenIdentifiers = listOf(
            "ProgramSchedule", "scheduleType", "scheduleWeekdays", "scheduleSessionsPerWeek",
            "ProgramDuration", "ProgramDay", "ProgramDayId", "LegacySchedule", "LegacyScheduleMapper",
            "ScheduleRule", "position", "DayOfWeek", "revisionNumber", "plannedFor"
        )
        val forbiddenPatterns = listOf(
            "\\.name", "\\.position", "ProgramId\\.value", "RevisionId\\.value", "when \\(.*\\.cadence"
        )
        val offenders = codeLines(consumerFile).filter { line ->
            forbiddenIdentifiers.any { token ->
                Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(line)
            } || forbiddenPatterns.any { pattern -> Regex(pattern).containsMatchIn(line) }
        }

        assertTrue(
            "a target run is planned from the stored explicit source and never from legacy scheduling: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noProductionSourceMapsALegacyScheduleOntoATargetSchedule() {
        // Stated over the whole production tree rather than over the one new file, because a mapper is
        // exactly the kind of thing that arrives in whichever file looks convenient.
        val offenders = productionSources().filter { (_, text) ->
            listOf("ProgramSchedule", "LegacySchedule", "LegacyScheduleMapper").any { text.contains(it) } &&
                listOf("TargetSchedule(", "TargetSchedule.daily", "TargetSchedule.everyNDays",
                    "TargetSchedule.fixedWeekdays", "TargetSchedule.sessionsPerWeek",
                    "TargetSchedule.derivedExcluding").any { text.contains(it) }
        }.map { (path, _) -> path }

        assertTrue("no implicit legacy-schedule -> target-rule mapping may exist: $offenders", offenders.isEmpty())
    }

    // ---- 4. no second target policy -----------------------------------------------------------------

    @Test
    fun theConsumerReimplementsNoTargetStage() {
        // The adapter converts and the orchestrator runs. A consumer that reached past them would be a
        // second composition of the pass — a second place where the same rule could be decided.
        val forbidden = listOf(
            "TargetPlanner", "TargetSchedulePolicy", "TargetScheduleResolver", "TargetOccurrenceComposer",
            "TargetOccurrenceReconciler", "TargetOccurrencePresenter", "TargetSlotMaterializer",
            "TargetScheduleApplicationService", "TargetScheduleSlotPersister", "TargetScheduleSlotPersistenceInput",
            "TargetScheduleOrchestrationRequest", "TargetPlan",
            "TargetScheduleDecision", "TargetScheduleApplicationResult", "TargetSupersededOccurrence",
            "TargetOccurrenceSupersessionReason", "ScheduleCadence", "OccurrenceComponent", "WorkoutSlot",
            "SlotId", "PersistedTargetOccurrence"
        )
        val offenders = codeLines(consumerFile).filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }

        assertTrue(
            "the consumer assembles an input and hands it to two owners; it resolves, composes, " +
                "reconciles, classifies, presents and persists nothing itself: $offenders",
            offenders.isEmpty()
        )
        assertEquals(
            "the input is converted by the existing adapter, exactly once",
            1,
            occurrences(code(consumerFile.readText()), "inputAdapter.adapt(")
        )
        assertEquals(
            "and the pass is run by the existing orchestrator, exactly once",
            1,
            occurrences(code(consumerFile.readText()), "orchestrator.apply(")
        )
    }

    // ---- 5. no second execution policy --------------------------------------------------------------

    @Test
    fun theConsumerDecidesNoExecutionStateAndAggregatesNothing() {
        val forbidden = listOf(
            // The value itself, not just its construction: a caller that rebuilt the scheduling input
            // by hand would have to name it, and the reader is the only thing allowed to produce one.
            "TargetExistingOccurrence", "ActualResult", "PerformedWork", "ExecutionEvidence",
            "SlotStatus", "SessionStatus", "OccurrenceExecution", "slot.status", "sumOf", ".sum(",
            "tally", "average", "totalVolume", "totalLoad"
        )
        val offenders = codeLines(consumerFile).filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }

        assertTrue(
            "an occurrence's execution is the stored fact the existing bridge returns and the existing " +
                "policy decides: a consumer that rebuilt it from a slot's status, or aggregated a " +
                "session's performed work, would be a second precedence: $offenders",
            offenders.isEmpty()
        )
        assertEquals(
            "and the stored occurrences are read through the one repository, unfiltered",
            1,
            occurrences(code(consumerFile.readText()), "occurrenceRepository.occurrencesOfProgram(programId)")
        )
        assertTrue(
            "no date filter, horizon or limit is applied before the target stages see them",
            codeLines(consumerFile).none { line ->
                listOf("filter", "sorted", "distinct", "groupBy", "take(", "limit").any { line.contains(it) }
            }
        )
    }

    // ---- 6. no ambient input, no translation --------------------------------------------------------

    @Test
    fun theConsumerReadsNoAmbientStateAndTranslatesNoFailure() {
        val ambient = listOf(
            "LocalDate.now", "Instant.now", "System.currentTimeMillis", "System.nanoTime", "clock",
            "Clock", "Random", "UUID", "hashCode()", "identityHashCode", "runBlocking"
        )
        val translation = listOf("catch", "try {", "runCatching", "throw ", "error(")
        val offenders = codeLines(consumerFile).filter { line ->
            ambient.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) } ||
                translation.any { token -> line.contains(token) }
        }

        assertTrue(
            "a target run's outcome is decided by facts the caller stated, and a stage's own typed " +
                "refusal propagates rather than being translated a second time here: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theSourceIsReadOnceAndItIsReadAgainstTheCurrentRevision() {
        val run = code(consumerFile.readText()).substringAfter("suspend fun run(")
        assertEquals(
            "the stored source is read exactly once, and it is the one the current-revision read named",
            1,
            occurrences(run, "sourceBridge.definitionsAndBindingsOf(revisionId)")
        )
        assertEquals(
            "and the revision it is read against is the one the current-revision read produced, " +
                "never one derived from a slot, a date, a revision number or a source row",
            1,
            occurrences(run, "val revisionId = currentRevision.revisionId")
        )
    }

    // ---- 7. the absent source is a return ------------------------------------------------------------

    @Test
    fun aMissingSourceIsAReturnAndNoBranchManufacturesAnEmptyOne() {
        val source = code(consumerFile.readText())
        val run = source.substringAfter("suspend fun run(")
        val missingArm = run.substringAfter("is TargetScheduleSourceRead.Missing ->")
            .substringBefore("is TargetScheduleSourceRead.Malformed ->")

        assertEquals(
            "the missing case is a return of the typed absence, so no pass runs and no row is written",
            "return TargetScheduleRunResult.SourceMissing(read.revisionId)",
            missingArm.trim()
        )
        assertFalse(
            "and no branch of the run constructs a source out of an empty rule list",
            Regex("TargetScheduleSource\\([^)]*emptyList").containsMatchIn(source)
        )
        assertEquals(
            "the malformed case is reported with the reason the read gave, not defaulted",
            1,
            occurrences(run, "TargetScheduleRunResult.SourceMalformed(read.revisionId, read.reason)")
        )
    }

    // ---- 8. the caller keeps its five values ---------------------------------------------------------

    @Test
    fun theRunContextDeclaresTheFiveCallerOwnedValuesWithNoDefaults() {
        assertEquals(
            "the five values storage does not state are named, in this order, and none has a default: " +
                "a default would be a second decision about a window, a composition, a source, a date " +
                "or a pause",
            listOf("window", "selection", "sources", "asOf", "pauses"),
            TargetScheduleRunContext::class.java.declaredFields
                .filterNot { it.name == "\$stable" }
                .map { it.name }
        )
        val parameters = Regex("class TargetScheduleRunContext\\(([^)]*)\\)")
            .find(code(consumerFile.readText()))!!.groupValues[1]
        val declarations = Regex("val (\\w+):").findAll(parameters).map { it.groupValues[1] }.toList()
        assertEquals(
            "and the value declares them in the same order the input carries them",
            listOf("window", "selection", "sources", "asOf", "pauses"),
            declarations
        )
        assertFalse(
            "no defaulted constructor argument anywhere in the run context",
            Regex("""class TargetScheduleRunContext\([^)]*=""").containsMatchIn(consumerFile.readText())
        )
    }

    @Test
    fun eachCallerOwnedValueIsForwardedUnchangedAndTheOtherFiveComeFromStorage() {
        val run = code(consumerFile.readText()).substringAfter("val input = TargetScheduleInput(")
            .substringBefore("return TargetScheduleRunResult.Scheduled(")
        val assignments = run.lines().map { it.trim() }.filter { it.contains("=") }
            .associate { line ->
                line.substringBefore("=").trim() to line.substringAfter("=").trim().removeSuffix(",")
            }

        assertEquals(
            "the input is assembled field for field: three facts from storage, five from the caller, " +
                "and the two identities the reads established",
            listOf(
                "programId" to "programId",
                "revisionId" to "revisionId",
                "scheduleDefinitions" to "source.rules",
                "window" to "context.window",
                "selection" to "context.selection",
                "existingOccurrences" to "existingOccurrenceReader.existingOccurrencesOf(",
                "sources" to "context.sources",
                "asOf" to "context.asOf",
                "pauses" to "context.pauses",
                "programDayBindings" to "source.programDayBindings"
            ),
            assignments.toList()
        )
        // A forwarded value is forwarded: the consumer decides none of the five, and the persisted
        // three are read whole rather than re-derived from a slot, a date or a name.
        for (field in listOf("window", "selection", "sources", "asOf", "pauses")) {
            assertEquals(
                "the caller's `$field` reaches the input unchanged",
                "context.$field",
                assignments[field]
            )
        }
    }

    // ---- 9. the result vocabulary -------------------------------------------------------------------

    @Test
    fun theResultCarriesExactlyTheOutcomesTheStageNames() {
        assertEquals(
            listOf(
                "CurrentRevisionMissing",
                "ProgramNotFound",
                "Scheduled",
                "SourceMalformed",
                "SourceMissing"
            ),
            TargetScheduleRunResult::class.java.declaredClasses.map { it.simpleName }.sorted()
        )
        assertTrue(
            "each refusal carries the fact that produced it",
            setOf("programId", "revisionId", "reason").all { field ->
                TargetScheduleRunResult::class.java.declaredClasses.any { cls ->
                    cls.declaredFields.any { it.name == field }
                }
            }
        )
    }

    // ---- 10. the wiring ------------------------------------------------------------------------------

    @Test
    fun theCompositionRootWiresTheConsumerOnceAndExactlyOneApplicationBoundaryCallsIt() {
        val container = code(containerFile.readText())

        assertTrue(
            "the consumer is a graph node, as a property like every other node",
            container.contains("val targetScheduleProductionConsumer: TargetScheduleProductionConsumer =")
        )
        assertEquals(
            "and it is constructed exactly once",
            1,
            constructionSites(container, "TargetScheduleProductionConsumer")
        )
        val wiring = container.substringAfter("val targetScheduleProductionConsumer")
            .substringBefore("val standardProgramBootstrap")
        listOf(
            "programRepository = programRepository",
            "planRepository = programPlanRepository",
            "sourceBridge = targetScheduleSourceBridge",
            "occurrenceRepository = targetScheduleOccurrenceRepository",
            "existingOccurrenceReader = targetExistingOccurrenceReader",
            "inputAdapter = targetScheduleInputAdapter",
            "orchestrator = targetScheduleOrchestrator"
        ).forEach { collaborator ->
            assertTrue("the wiring states `$collaborator`", wiring.contains(collaborator))
        }
        val wiringForbidden = listOf("IdGenerator", "clock", "zone", "ProgramScheduler", "SlotPlanner", "ScheduleCalendar")
        val wiringOffenders = wiring.lines().filter { line ->
            wiringForbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }
        assertTrue(
            "the consumer's wiring hands it no identity, no clock and no legacy planning boundary: $wiringOffenders",
            wiringOffenders.isEmpty()
        )

        // The caller half, revised rather than relaxed by §30 step 21: the node used to have no
        // caller at all, and it now has exactly one — the composed Start. The claim is therefore a
        // **closed one-element list** rather than an absence, because an absence would be false by
        // construction the moment production reaches this class, and a second caller has to keep
        // failing here. What the deferral was actually about is kept whole below: no screen, no
        // view model, the save boundary, the import boundary, the bootstrap and the session runtime
        // are all still off the target path, and the legacy planner still owns every slot the
        // application trains from.
        val callers = productionSources()
            .filterNot { (path, _) -> path == "di/AppContainer.kt" }
            .filter { (path, _) -> !path.contains("TargetScheduleProductionConsumer.kt") }
            // Keyed on the *type*, not on the container's property name: a caller receives the
            // consumer as a constructor parameter and calls it, so a scan for `targetSchedule…`
            // would have read empty on a tree where the composed Start demonstrably holds it —
            // the §8b failure, one generation on. The consumer's own file and the composition root
            // are excluded by path, so what remains is the caller list and only the caller list.
            .filter { (_, text) -> text.contains("TargetScheduleProductionConsumer") }
            .map { (path, _) -> path }
        assertEquals(
            "the consumer has exactly one production caller — §30 step 21's composed Start, which " +
                "owns the lifecycle transition and the one controlled invocation. A screen, the save " +
                "boundary, the import boundary, the bootstrap and the session runtime all stay off it.",
            listOf("domain/usecase/ProgramStartService.kt"),
            callers
        )
    }

    @Test
    fun noScreenControllerOrViewModelReachesTheTargetContour() {
        val uiAndViewModel = productionSources().filter { (path, _) ->
            path.startsWith("ui/") || path.startsWith("viewmodel/")
        }
        assertTrue(
            "the sweep must actually cover the UI tree, or it proves nothing",
            uiAndViewModel.size >= 20
        )
        val offenders = uiAndViewModel.filter { (_, text) ->
            listOf(
                "TargetScheduleProductionConsumer", "TargetScheduleInputAdapter", "TargetScheduleOrchestrator",
                "TargetScheduleSourceBridge", "TargetExistingOccurrenceReader"
            ).any { text.contains(it) }
        }.map { (path, _) -> path }

        assertTrue("no screen and no view model runs a target pass: $offenders", offenders.isEmpty())
    }

    // ---- 11. legacy isolation -----------------------------------------------------------------------

    @Test
    fun theLegacyPlannerIsStillProductionsSchedulerAndTheConsumerNeverNamesIt() {
        listOf(
            File(usecaseDir, "ProgramScheduler.kt"),
            File(mainDir, "domain/program/SlotPlanner.kt"),
            File(mainDir, "domain/program/ScheduleCalendar.kt")
        ).forEach { legacy ->
            assertTrue("${legacy.name} must exist", legacy.isFile)
            listOf(
                "TargetScheduleProductionConsumer", "TargetScheduleOrchestrator", "TargetScheduleInputAdapter",
                "TargetScheduleSourceBridge", "TargetScheduleWindow"
            ).forEach { token ->
                assertTrue(
                    "the legacy contour must not reach the target path (${legacy.name}: $token)",
                    !legacy.readText().contains(token)
                )
            }
        }
        assertFalse(
            "the consumer itself names no legacy planning boundary, in code or in prose",
            consumerFile.readText().contains("ProgramScheduler") ||
                consumerFile.readText().contains("SlotPlanner") ||
                consumerFile.readText().contains("ScheduleCalendar")
        )
        assertEquals(
            "the legacy scheduler is still constructed in exactly one place",
            1,
            constructionSites(code(containerFile.readText()), "ProgramScheduler")
        )
        assertEquals(
            "and is still the only property the composition root exposes for it",
            1,
            Regex("""val programScheduler: ProgramScheduler = ProgramScheduler\(""")
                .findAll(containerFile.readText()).count()
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------

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

    private fun constructionSites(text: String, name: String): Int {
        var count = 0
        var index = text.indexOf("$name(")
        while (index >= 0) {
            val before = text.substring(maxOf(0, index - 8), index)
            val insideALongerName = before.isNotEmpty() &&
                (before.last().isLetterOrDigit() || before.last() == '_')
            if (!insideALongerName && !before.contains("class ")) count++
            index = text.indexOf("$name(", index + 1)
        }
        return count
    }

    private fun code(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun codeLines(file: File): List<String> = code(file.readText())
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
}
