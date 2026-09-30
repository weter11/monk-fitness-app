package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Stage 19 — the boundaries of the explicit target schedule authoring path, asserted against the
 * sources themselves.
 *
 * The stage's claim is a set of prohibitions, and a prohibition that lives only in a KDoc erodes the
 * first time a convenient import appears. So each is checked here as a **token or a shape in real
 * code**, with comments stripped first — which is what lets these files' own explanations say "never
 * reads `ProgramSchedule`" without tripping the rule about reading it.
 *
 * ```text
 * 1. no ProgramSchedule -> TargetSchedule inference, anywhere on the authoring path
 * 2. the authoring reads no Scheduler state
 * 3. the authoring reads no resolved occurrence, slot, session or performance state
 * 4. the stored target source stays caller-owned configuration
 * 5. the editor and the save layer hold no second target-scheduling policy
 * 6. production consumers still do not run a target pass
 * ```
 */
class TargetScheduleAuthoringArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val usecaseDir = File(mainDir, "domain/usecase")

    private val authoringFile = File(usecaseDir, "TargetScheduleAuthoring.kt")
    private val saveServiceFile = File(usecaseDir, "ProgramSaveService.kt")
    private val editorFile = File(usecaseDir, "ProgramEditorService.kt")
    private val sourceValueFile = File(usecaseDir, "TargetScheduleSource.kt")
    private val sourceRepositoryFile = File(mainDir, "data/repository/TargetScheduleSourceRepository.kt")
    private val targetDaoFile = File(mainDir, "data/local/ProgramTargetScheduleSourceDao.kt")

    /** Every file this stage adds or changes on the authoring path. */
    private val authoringPath: List<File> = listOf(
        authoringFile,
        saveServiceFile,
        editorFile,
        sourceValueFile,
        sourceRepositoryFile
    )

    private fun code(source: File): String = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    private fun codeLines(source: File): List<String> = code(source)
        .lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }

    private fun offenders(sources: List<File>, tokens: List<String>): List<String> =
        sources.flatMap { source ->
            codeLines(source).filter { line -> tokens.any { token -> line.contains(token) } }
                .map { line -> "${source.name}: $line" }
        }

    // ---------------------------------------------------------------- the files exist

    @Test
    fun theAuthoringPathAddsExactlyTheFilesItClaims() {
        assertTrue(
            "the explicit authoring value, the save boundary that applies it, and Stage 18's value " +
                "and repository it applies through, all exist",
            listOf(authoringFile, saveServiceFile, sourceValueFile, sourceRepositoryFile).all { it.isFile }
        )
        assertTrue("the Stage 18 target-source DAO is still there and is still insert-and-read only",
            targetDaoFile.isFile)
    }

    // ---------------------------------------------------------------- 1. no legacy -> target inference

    @Test
    fun theAuthoringPathNamesNoLegacyScheduleVocabularyAtAll() {
        // Not only the type: the *columns* a legacy schedule is stored in, because a mapper could read
        // `scheduleType` without ever naming `ProgramSchedule`.
        val forbidden = listOf(
            "ProgramSchedule",
            "scheduleType",
            "scheduleWeekdays",
            "scheduleSessionsPerWeek",
            "ProgramDuration"
        )
        val found = offenders(listOf(authoringFile), forbidden)

        assertTrue(
            "a target source is stated, never mapped out of the legacy schedule vocabulary — and the " +
                "authoring value is the one place a mapper would have to live: $found",
            found.isEmpty()
        )
    }

    @Test
    fun theSaveBoundaryNamesNoLegacyScheduleVocabularyEither() {
        val saveService = code(saveServiceFile)
        // Whole-token, with `_` a word character, because `ProgramScheduler` *contains*
        // `ProgramSchedule` — and the save layer legitimately holds §20's Scheduler, which decides the
        // initial opportunities. A substring scan would refuse the collaborator this stage must keep.
        for (token in listOf("ProgramSchedule", "scheduleType", "scheduleWeekdays", "scheduleSessionsPerWeek")) {
            val pattern = Regex("(?<![A-Za-z0-9_])$token(?![A-Za-z0-9_])")
            assertFalse(
                "the boundary that applies a stated source reads no legacy schedule vocabulary ($token), " +
                    "so it cannot manufacture a cadence or an anchor from one",
                pattern.containsMatchIn(saveService)
            )
        }
    }

    @Test
    fun noProductionSourceMapsALegacyScheduleOntoATargetSchedule() {
        // The same absence claim Stage 18 states over the whole production tree, restated because this
        // stage adds the second place such a mapping could have grown: the authoring path.
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { source ->
                val text = code(source)
                val namesLegacy = listOf("ProgramSchedule", "LegacySchedule", "LegacyScheduleMapper")
                    .any { text.contains(it) }
                val buildsTarget = listOf(
                    "TargetSchedule(", "TargetSchedule.daily", "TargetSchedule.everyNDays",
                    "TargetSchedule.fixedWeekdays", "TargetSchedule.sessionsPerWeek",
                    "TargetSchedule.derivedExcluding", "TargetScheduleDefinition("
                ).any { text.contains(it) }
                if (namesLegacy && buildsTarget) source.name else null
            }
            .toList()

        assertTrue("no implicit ProgramSchedule -> TargetSchedule mapping may exist: $offenders", offenders.isEmpty())
    }

    @Test
    fun theAuthoringIsNotCarriedOnTheDraftAndTheLegacyScheduleIsNotExtended() {
        // The claim is checked on the *compiled* shape rather than on an import, because a field typed
        // `TargetScheduleAuthoring` is exactly what a `ProgramEditorDraft` field would be, and the
        // difference is a name the compiler erases — so the fields themselves are read.
        val draftFields = ProgramEditorDraft::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertFalse(
            "target semantics are not draft content: the draft is Program structure, and the structural " +
                "comparison is what decides whether a save warrants a revision at all (§6). Draft " +
                "fields: $draftFields",
            draftFields.any { it.contains("target", ignoreCase = true) }
        )

        // …and the legacy schedule's own vocabulary is unchanged: it gained no target field.
        assertFalse(
            "the legacy schedule is not extended with derived target data either",
            ProgramSchedule::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .any { field -> field.name.contains("target", ignoreCase = true) }
        )
    }

    // ---------------------------------------------------------------- 2./3. no state is read

    @Test
    fun theAuthoringReadsNoSchedulerState() {
        val forbidden = listOf(
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "initialSlotsFor",
            "programScheduleRepository", "ProgramScheduleRepository"
        )
        val found = offenders(listOf(authoringFile), forbidden)

        assertTrue(
            "a target rule states *when* a workout recurs; it is not derived from the opportunities the " +
                "Scheduler already planned, which are a result and not the configuration that produced " +
                "them: $found",
            found.isEmpty()
        )
    }

    @Test
    fun theAuthoringReadsNoResolvedOccurrenceSlotSessionOrPerformanceState() {
        val forbidden = listOf(
            "ExistingOccurrence", "TargetExistingOccurrence", "ActualResult", "PerformedWork",
            "WorkoutSlot", "WorkoutSession", "SessionRuntime", "SessionExercise", "SetResult",
            "OccurrenceExecution", "TargetPlanner", "TargetScheduleResolver", "TargetOccurrenceComposer",
            "TargetSchedulePolicy", "program_workout_slot", "workout_session", "program_set_log",
            "ProgramProgressRepository", "ProgramAdaptiveRepository"
        )
        val found = offenders(listOf(authoringFile), forbidden)

        assertTrue(
            "a stored target source is configuration; what happened afterwards lives in the slot, the " +
                "session graph and the adaptive tables, and reading any of it here would be inventing a " +
                "rule the caller must state: $found",
            found.isEmpty()
        )
    }

    @Test
    fun theAuthoringHoldsNoCollaboratorAndNoAmbientState() {
        val value = code(authoringFile)
        for (forbidden in listOf(
            "Repository", "Dao", "Entity", "@Entity", "androidx", "android.", "Clock", "IdGenerator",
            "LocalDate.now", "Instant.now", "System.currentTimeMillis", "Random", "runBlocking",
            "import kotlinx"
        )) {
            assertFalse(
                "the authoring is caller-owned data, not a collaborator or an environment: it names $forbidden",
                value.contains(forbidden)
            )
        }
        // Read as a banned-*package* sweep over the held types, which is the form that survives
        // `RevisionId`/`ProgramDayId` erasing to `java.lang.String` through reflection.
        val heldTypes = TargetScheduleAuthoring::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.genericType.typeName } +
            TargetScheduleAuthoringBinding::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.genericType.typeName }
        assertEquals(
            "and every type it holds is a domain or JDK type — never a repository, DAO, entity, " +
                "generator, clock, platform or UI collaborator",
            emptyList<String>(),
            heldTypes.filter { type ->
                listOf(
                    "com.monkfitness.app.data.",
                    "com.monkfitness.app.ui.",
                    "com.monkfitness.app.viewmodel.",
                    "com.monkfitness.app.di.",
                    "androidx.",
                    "android."
                ).any { type.startsWith(it) }
            }
        )
        assertEquals(
            "the value holds exactly the two caller-stated lists",
            listOf("rules", "programDayBindings"),
            TargetScheduleAuthoring::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
        )
        assertTrue(
            "and both are copied at construction, so a caller mutating its own list cannot rewrite them",
            value.contains("rules.toList()") && value.contains("programDayBindings.toList()")
        )
    }

    // ---------------------------------------------------------------- 4. caller-owned configuration

    @Test
    fun theSourceIsCallerOwnedConfigurationAndNothingElse() {
        // The only *transformation* the authoring performs is a value substitution plus the re-pointing
        // of one identity the editor itself minted. A default anchor, a chosen cadence, a normalized
        // form or a substituted rule id would each be a fact invented at this boundary.
        val authoring = code(authoringFile)
        assertTrue(
            "the rules are forwarded as the caller's own list, not rebuilt field by field",
            authoring.contains("rules = rules")
        )
        assertTrue(
            "and the source is built from exactly the revision identity, the rules and the bindings",
            authoring.contains(
                "TargetScheduleSource(revisionId = revisionId, rules = rules, programDayBindings = bindings)"
            )
        )
        for (invented in listOf(
            "\"daily\"", "\"legacy\"", "\"unknown\"", "\"placeholder\"", "\"default\"", "TODO",
            "auto-generated", "LocalDate.now", "plannedStartDate"
        )) {
            assertFalse(
                "no substitute anchor, cadence or identity is ever produced here ($invented)",
                authoring.contains(invented)
            )
        }
    }

    @Test
    fun thePlannedStartDateIsNeverUsedAsAnAnchorDate() {
        // The Program's planned start date is the one Program fact that *looks* like an anchor. No
        // documented ownership rule in this repository equates the two, so the boundary that holds both
        // must never pass one for the other.
        val saveService = code(saveServiceFile)
        assertFalse(
            "the save boundary does not turn the creation's planned start date into a rule anchor",
            Regex("anchorDate\\s*=\\s*plannedStartDate").containsMatchIn(saveService) ||
                Regex("anchorDate\\s*=\\s*creation\\.program\\.plannedStartDate")
                    .containsMatchIn(saveService)
        )
        assertTrue(
            "the anchor date reaches the source only through the caller's own stated value, which is why " +
                "the authoring is a parameter of save() rather than something this layer computes",
            saveService.contains("targetSchedule.asSourceOf(")
        )
    }

    @Test
    fun theAbsentCaseIsAReturnAndNeverAnEmptySource() {
        // Stated as a *shape* rather than as a behaviour test, because the behavioural difference is
        // invisible at the row level: an empty source writes no rows, so a save that wrongly constructed
        // one would still leave the revision reading as `Missing`. The rule is therefore pinned where it
        // is actually decided — the branch that answers "the caller stated nothing" — so a future
        // implementation that answers it with a construction is refused before it reaches the database.
        val saveService = code(saveServiceFile)
        assertTrue(
            "the absent case is an early return, so nothing at all is written for a caller that stated " +
                "no target semantics",
            saveService.contains("if (targetSchedule == null) return")
        )
        assertFalse(
            "and no branch in the boundary constructs a source out of an empty rule list",
            Regex("TargetScheduleSource\\([^)]*emptyList").containsMatchIn(saveService)
        )
        assertFalse(
            "nor does the authoring value have a way to express 'no rules' as a storable source",
            Regex("class TargetScheduleAuthoring\\([^)]*default").containsMatchIn(code(authoringFile))
        )
    }

    // ---------------------------------------------------------------- 5. no second policy in the editor/save layer

    @Test
    fun theEditorAndSaveLayerHoldNoTargetSchedulingPolicyOfTheirOwn() {
        // A *second* policy is what a cadence branch, a window, a date computation or a pass would be
        // here: the resolver, the composer, the presenter, the policy, the planner and the orchestrator
        // are the only owners of those, and the save layer forwards rather than restates.
        val forbidden = listOf(
            "TargetScheduleOrchestrator", "TargetPlanner", "TargetSchedulePolicy",
            "TargetOccurrenceComposer", "TargetScheduleResolver", "TargetOccurrencePresenter",
            "TargetScheduleInputAdapter", "TargetScheduleBridge", "TargetScheduleApplicationService",
            "TargetScheduleWindow(", "CompositionSelection(", "ProgramPauseWindow(",
            "TargetScheduleInput", "TargetScheduleInputAdapter(", "ScheduleCadence",
            "asOf =", "when (cadence", "when (rule.cadence"
        )
        val found = offenders(listOf(saveServiceFile, authoringFile), forbidden)

        assertTrue(
            "the save layer applies a stated source and decides nothing about target scheduling: no " +
                "cadence branch, no window, no date, no pass, no adapter: $found",
            found.isEmpty()
        )
    }

    @Test
    fun theEditorReachesNoTargetSchedulingPolicyAndTheOneTargetNameItHoldsIsItsOwnReturnType() {
        // Revised, not relaxed — §30 step 22. The claim used to be *"the editor names no target-stage
        // type at all"*, which was true because target scheduling could not yet change a revision
        // without a structural edit. That made the absence an accident of what existed rather than a
        // rule, and step 22 had to change it: a target-only change mints a revision, and §6 gives the
        // **editor** sole ownership of revision minting, so the editor necessarily names the value it
        // hands back for that mint.
        //
        // What is still forbidden is unchanged and is what the assertion now says: the editor holds no
        // target *scheduling policy* — no source value, no authoring, no repository, no cadence
        // vocabulary, no pass, no adapter. The one target name it is allowed is its own return type,
        // which holds a Program, a revision and a correspondence and nothing about scheduling.
        val forbidden = listOf(
            "TargetScheduleSource", "TargetScheduleAuthoring", "TargetScheduleDefinition",
            "TargetProgramDayBinding", "TargetScheduleRevisionChange", "TargetScheduleSourceRead",
            "TargetScheduleSourceException", "TargetScheduleSourceRepository",
            "TargetScheduleOrchestrator", "TargetPlanner", "TargetSchedulePolicy",
            "TargetOccurrenceComposer", "TargetScheduleResolver", "TargetScheduleInputAdapter",
            "TargetScheduleSourceBridge", "ScheduleCadence", "anchorDate"
        )
        val found = offenders(listOf(editorFile), forbidden)

        assertTrue(
            "the editor names no target scheduling policy, and holds none: the authoring seam sits " +
                "above it, not inside it, and the revision it mints for a target-only change carries " +
                "no scheduling fact of its own. Offenders: $found",
            found.isEmpty()
        )

        // …and the one target-named type it does return is a structural value: a Program, a revision
        // and the correspondence. Checked on the *compiled* shape, because a `TargetScheduleSource`
        // field would be exactly the thing that must not appear and its name would not say so.
        assertEquals(
            "the editor's target-only return value holds a Program, a revision and a day " +
                "correspondence — and no source, no authoring and no binding",
            listOf("program", "revision", "mintedProgramDays"),
            MintedTargetScheduleRevision::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
        )
        val heldTypes = MintedTargetScheduleRevision::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.genericType.typeName }
        assertEquals(
            "…every one of them a domain type: no repository, DAO, entity, clock or generator",
            emptyList<String>(),
            heldTypes.filter { type ->
                listOf(
                    "com.monkfitness.app.data.",
                    "com.monkfitness.app.di.",
                    "androidx.",
                    "android."
                ).any { type.startsWith(it) }
            }
        )
    }

    @Test
    fun theAuthoringIsAppliedOnlyThroughTheStagesEighteenRepository() {
        // One write path, not two: if the save layer had its own insert, there would be a second place
        // where a rule identity, a cadence or an anchor could be decided on the way to storage.
        val saveService = code(saveServiceFile)
        assertTrue(
            "the boundary's single write is Stage 18's repository store",
            saveService.contains("targetSourceRepository.store(")
        )
        assertFalse(
            "and it constructs no DAO and no entity of its own",
            saveService.contains("ProgramTargetScheduleSourceDao") ||
                saveService.contains("ProgramTargetScheduleRuleEntity") ||
                saveService.contains("ProgramTargetProgramDayBindingEntity")
        )
        // Revised, not relaxed — §30 step 22. The claim used to be a count of **one** `store(` call
        // site, which said "a second application path cannot appear beside this one unnoticed". Step
        // 22 added the legs the new contract has — a creation, a structural edit, and a target-only
        // change — and each needs its own call, so counting *sites* no longer states the claim.
        //
        // What the claim actually protects is that every write goes through the same repository and
        // that no leg can reach a DAO. Both are now asserted directly: the number of `store(` sites
        // is pinned at the three legs the contract has (so a *fourth* application path is still
        // refused), and every one of them is a `targetSourceRepository.store(`.
        assertEquals(
            "the target source is written from exactly the three legs this contract has — a " +
                "creation, a structural edit and a target-only change — and no fourth application " +
                "path can appear beside them unnoticed",
            3,
            Regex("targetSourceRepository\\.store\\(").findAll(saveService).count()
        )
        // …and the repository is the only thing that writes: no insert, no update, no delete, no
        // savepoint of a target row anywhere on the boundary.
        for (ownWrite in listOf(
            "insertRules(", "insertBindings(", "updateTarget", "deleteTarget", "deleteRule",
            "clearTarget"
        )) {
            assertFalse(
                "the boundary writes no target row by any route but Stage 18's repository ($ownWrite)",
                saveService.contains(ownWrite)
            )
        }
    }

    // ---------------------------------------------------------------- 6. no cutover

    @Test
    fun theSaveBoundaryStillRunsNoTargetPassAndTheOnlyTargetConsumerIsTheStageTwentyNode() {
        // The authoring path makes a target source *authorable*; it must not make the target contour
        // *reachable* from production scheduling. §30 step 19 deferred the cutover explicitly.
        //
        // §30 step 20 renamed this claim rather than relaxing it. It used to end with "the bridge is
        // consumed by nobody", which was a construction-site absence that Stage 20 made false by
        // construction: the first production consumer holds the bridge, and a scan for `Bridge(` would
        // have kept reading empty while saying so. The claim that is now true is a **closed consumer
        // list** — one entry, the Stage 20 consumer — plus the whole negative half below, which is
        // what the deferral was actually about and which is unchanged: the save boundary, the editor
        // and the legacy contour still run no target pass.
        val saveService = code(saveServiceFile)
        for (pass in listOf("TargetScheduleOrchestrator", "TargetPlanner", "TargetSchedulePolicy",
            "TargetScheduleApplicationService", "TargetScheduleInputAdapter")) {
            assertFalse(
                "the save boundary runs no target pass ($pass)",
                saveService.contains(pass)
            )
        }
        assertFalse(
            "and it does not consume the bridge either: the save boundary never reads a stored source " +
                "into a pass, which is what §30 step 20's separate consumer is for",
            saveService.contains("TargetScheduleSourceBridge")
        )

        val consumers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "AppContainer.kt" }
            .filterNot { it.name == "TargetScheduleSourceBridge.kt" }
            .filterNot { it.name == "TargetScheduleSourceRepository.kt" }
            .filter { it.readText().contains("TargetScheduleSourceBridge") }
            .map { it.name }
            .toList()
        assertEquals(
            "the bridge's only production consumer is the Stage 20 consumer; the cutover is a later stage",
            listOf("TargetScheduleProductionConsumer.kt"),
            consumers
        )
    }

    @Test
    fun theLegacySchedulerIsStillProductionsSchedulingOwner() {
        // The one thing this stage must not have done is give the legacy contour a reason to know about
        // target semantics. §20's ownership is unchanged and the assertion is mechanical.
        for (legacy in listOf(
            File(usecaseDir, "ProgramScheduler.kt"),
            File(mainDir, "domain/program/SlotPlanner.kt"),
            File(mainDir, "domain/program/ScheduleCalendar.kt")
        )) {
            val text = code(legacy)
            for (token in listOf("TargetScheduleSource", "TargetScheduleAuthoring", "TargetScheduleOrchestrator",
                "program_target_schedule_rule", "program_target_program_day_binding")) {
                assertFalse(
                    "${legacy.name} must not reach the target contour: $token",
                    text.contains(token)
                )
            }
        }
        assertTrue("and the legacy Scheduler is still there", File(usecaseDir, "ProgramScheduler.kt").isFile)
    }

    @Test
    fun theImportExportContractIsUntouchedByThisStage() {
        // §30 step 13's transfer model carries no target field and §30 step 19 preserved that decision:
        // the authoring exists, but no *transferable* representation of it was established, so the
        // transfer format is deliberately not widened here. Checked over the whole transfer package, so
        // a new field added to any document type is caught and not only one added to the root.
        val transferDir = File(mainDir, "domain/program/transfer")
        val sources = transferDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the transfer package is still there", sources.isNotEmpty())
        val packageCode = sources.joinToString("\n") { code(it) }
        for (token in listOf("TargetSchedule", "TargetProgramDayBinding", "anchorDate", "TargetScheduleAuthoring")) {
            assertFalse(
                "the transfer format still carries no target authoring data ($token): extending it " +
                    "requires a complete, explicit representation that can be transferred without " +
                    "inference, which this stage did not establish",
                packageCode.contains(token)
            )
        }
    }
}
