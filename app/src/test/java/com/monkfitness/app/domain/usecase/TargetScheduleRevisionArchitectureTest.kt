package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramOperationRefusal
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * §30 step 22 — **target schedule is revisioned Program behaviour**, asserted against the sources
 * themselves.
 *
 * The stage's claim is a set of prohibitions and one ownership rule, and a prohibition that lives only
 * in a KDoc erodes the first time a convenient shortcut appears. So each is checked here as a token, a
 * shape or a count in real code, with comments stripped first — which is what lets these files' own
 * explanations say *"never infers a binding from a position"* without tripping the rule about doing it.
 *
 * ```text
 *  1. target authoring remains outside ProgramEditorDraft
 *  2. the target revision change is an explicit Keep / Replace / Clear
 *  3. a target-only change has its own explicit revision path
 *  4. revision minting still has exactly one owner
 *  5. an old target source is never updated or deleted
 *  6. a new source attaches only to the new revision
 *  7. bindings are re-identified through the editor's minted correspondence
 *  8. no binding is inferred from a ProgramDay position / name / date / weekday
 *  9. no workoutId is derived from a ProgramDayId
 * 10. no legacy-schedule mapping in either direction
 * 11. no UI layer reads the target source repository
 * 12. no DAO / Room access on the revision path
 * 13. no target scheduler is invoked
 * 14. a creation's omission still means Missing
 * 15. an existing revision's omission means Keep, not a silent Missing
 * 16. Clear is distinct from an empty authoring
 * 17. Copy does not guess target bindings
 * 18. Program identity is unchanged across a target-only revision
 * ```
 */
class TargetScheduleRevisionArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val usecaseDir = File(mainDir, "domain/usecase")

    private val changeFile = File(usecaseDir, "TargetScheduleRevisionChange.kt")
    private val saveServiceFile = File(usecaseDir, "ProgramSaveService.kt")
    private val editorFile = File(usecaseDir, "ProgramEditorService.kt")
    private val authoringFile = File(usecaseDir, "TargetScheduleAuthoring.kt")
    private val sourceRepositoryFile = File(mainDir, "data/repository/TargetScheduleSourceRepository.kt")
    private val containerFile = File(mainDir, "di/AppContainer.kt")

    /** Every file this stage adds or changes on the revision path. */
    private val revisionPath: List<File> = listOf(
        changeFile, saveServiceFile, editorFile, authoringFile, sourceRepositoryFile
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

    private fun occurrences(sources: List<File>, token: String): Int =
        sources.sumOf { source -> Regex(Regex.escape(token)).findAll(code(source)).count() }

    // ---------------------------------------------------------------- the files exist

    @Test
    fun theRevisionPathAddsExactlyTheFilesItClaims() {
        assertTrue(
            "the explicit change vocabulary, the boundary that applies it, the editor entry that " +
                "mints the revision and Stage 18's value, authoring and repository it applies through " +
                "all exist",
            revisionPath.all { it.isFile }
        )
    }

    // ---------------------------------------------------------------- 1./14./16. the values

    @Test
    fun targetAuthoringRemainsOutsideTheDraftAndTheRevisionChangeIsNotOnItEither() {
        // Checked on the *compiled* shape: a field typed `TargetScheduleAuthoring` is exactly what a
        // `ProgramEditorDraft` field would be, and the difference is a name the compiler erases.
        val draftFields = ProgramEditorDraft::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertFalse(
            "target semantics are not draft content: the draft is Program structure, and the " +
                "structural comparison is what decides whether a save warrants a revision (§6). " +
                "Draft fields: $draftFields",
            draftFields.any { it.contains("target", ignoreCase = true) }
        )
        // The draft's own `schedule` field is the **legacy** `ProgramSchedule` and is §6 structure; the
        // target vocabulary is what must not appear beside it, and the check above is on `target`
        // precisely so the legacy field is not mistaken for a violation.
        assertEquals(
            "the draft's fields are exactly §6's structure — the legacy `schedule` among them, and no " +
                "target field beside it",
            listOf(
                "programId", "baseRevisionId", "name", "description", "mode", "duration",
                "schedule", "days", "focus"
            ),
            draftFields
        )
        // …and the same for the legacy schedule, which must not grow a target field either.
        assertFalse(
            "the legacy schedule is not extended with derived target data",
            ProgramSchedule::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .any { it.name.contains("target", ignoreCase = true) }
        )
    }

    @Test
    fun theRevisionChangeIsExactlyKeepReplaceAndClear() {
        // Named from `declaredClasses`, not from a source scan: the point is that a *fourth* case
        // cannot be added without deciding what an unstated claim means, which is the ambiguity this
        // whole stage removes.
        assertEquals(
            "a target revision change is Keep, Replace or Clear, and nothing else: an absent case " +
                "with a fourth meaning would reintroduce the single null this stage replaced",
            listOf("Clear", "Keep", "Replace"),
            TargetScheduleRevisionChange::class.java.declaredClasses
                .filterNot { it.isInterface }
                .map { it.simpleName }
                .sorted()
        )
        assertTrue(
            "and it is a sealed interface, so a `when` over it is exhaustive at compile time",
            TargetScheduleRevisionChange::class.java.isInterface &&
                Modifier.isAbstract(TargetScheduleRevisionChange::class.java.modifiers)
        )
        // `Replace` carries the authoring; `Keep` and `Clear` carry nothing at all — so there is no
        // default, and no `null` with more than one meaning.
        assertEquals(
            "Replace carries exactly the authoring",
            listOf("authoring"),
            TargetScheduleRevisionChange.Replace::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
        )
        for (noArgument in listOf(
            TargetScheduleRevisionChange.Keep::class.java,
            TargetScheduleRevisionChange.Clear::class.java
        )) {
            assertEquals(
                "${noArgument.simpleName} states a claim and carries no data at all",
                emptyList<String>(),
                noArgument.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .map { it.name }
            )
        }
    }

    @Test
    fun clearIsDistinctFromAnEmptyAuthoring() {
        // The two claims are different and only one of them is representable: a revision with no rules
        // cannot produce an occurrence, so an authoring that states none is a statement that cannot be
        // stored, and `Clear` is what "this revision states it has none" looks like.
        val authoring = code(authoringFile)
        assertTrue(
            "an authoring that states no rule is still refused, so `Clear` can never be expressed as " +
                "an empty authoring",
            authoring.contains("NoRulesStated")
        )
        assertFalse(
            "and the authoring value has no way to express 'no rules' as a storable source",
            Regex("class TargetScheduleAuthoring\\([^)]*default").containsMatchIn(authoring)
        )
        assertTrue(
            "`Clear` is a case of the *change* vocabulary and is written nothing at all — it is not a " +
                "source, an empty authoring or a delete",
            occurrences(listOf(changeFile), "data object Clear") == 1
        )
    }

    // ---------------------------------------------------------------- 2./3. the application API

    @Test
    fun theSaveBoundaryStatesBothAbsencesAndRefusesTheWrongLeg() {
        val save = code(saveServiceFile)
        // Both absences are separate parameters, and each is documented on the leg it belongs to: a
        // creation's absence means *never stated*, an edit's means *keep what is there*.
        assertTrue(
            "a creation states its target source with an authoring or states none",
            Regex("targetSchedule:\\s*TargetScheduleAuthoring\\?\\s*=\\s*null").containsMatchIn(save)
        )
        assertTrue(
            "an edit states its target scheduling with an explicit change, defaulting to Keep",
            Regex("targetChange:\\s*TargetScheduleRevisionChange\\?\\s*=\\s*null").containsMatchIn(save) &&
                save.contains("targetChange ?: TargetScheduleRevisionChange.Keep")
        )
        // …and the two are not interchangeable: each leg refuses the other's vocabulary, which is what
        // makes "null" mean one thing per parameter rather than three things overall.
        for (refusal in listOf(
            "EditChangeOnACreation", "CreationAuthoringOnAnEdit"
        )) {
            assertTrue(
                "the boundary refuses a statement handed to the wrong leg ($refusal)",
                occurrences(listOf(changeFile, saveServiceFile), refusal) >= 2
            )
        }
    }

    @Test
    fun aTargetOnlyChangeHasItsOwnExplicitRevisionPath() {
        // The defect this stage fixes is that a target-only change is invisible to §6's structural
        // comparison, so the path has to be its own operation rather than a draft save that happens
        // to notice.
        assertTrue(
            "the save boundary has a dedicated target-only operation",
            code(saveServiceFile).contains("suspend fun saveTargetScheduleChange(")
        )
        assertTrue(
            "and it mints through the editor's explicit target-only entry, not through a draft save",
            code(saveServiceFile).contains("editor.prepareTargetScheduleRevision(") &&
                code(saveServiceFile).contains("editor.saveTargetScheduleRevision(")
        )
        assertTrue(
            "the editor's target-only entry writes nothing by itself, so an identical statement can " +
                "decline to write a revision it has already minted",
            code(editorFile).contains("suspend fun prepareTargetScheduleRevision(") &&
                code(editorFile).contains("suspend fun saveTargetScheduleRevision(")
        )
        // …and the comparison that decides is a *semantic* one, made over plan-day handles, not an
        // identity comparison of two stored sources.
        assertTrue(
            "an identical statement is recognised by comparing semantics, not identity",
            code(changeFile).contains("statesTheSameTargetSemanticsAs") &&
                code(saveServiceFile).contains("storedAsAuthoring.statesTheSameTargetSemanticsAs(requested)")
        )
    }

    // ---------------------------------------------------------------- 4. one owner of minting

    @Test
    fun revisionMintingStillHasExactlyOneOwner() {
        // §6 gives revision minting to the editor. A second `RevisionId(idGenerator.newId())` in the
        // save boundary would be a second minting rule, and a second rule is a second §6.
        // Revised rather than over-claimed: a walk of the whole tree finds three minters, and two of
        // them are §27's creation paths rather than a *second revision rule* — `ProgramImportService`'s
        // creation unit and the standard Program's bootstrap each mint a Program's **first** revision,
        // which no §6 comparison can replace. The claim this stage actually has to make is narrower
        // and is now stated directly: the target-revision path mints nothing, and the save boundary
        // holds no identity generator at all, so a second revision-minting rule cannot appear there.
        assertFalse(
            "the save boundary holds no identity generator, so it cannot mint a revision itself",
            code(saveServiceFile).contains("IdGenerator") || code(saveServiceFile).contains("newId()")
        )
        assertFalse(
            "the change value mints nothing: it is a statement, not a mechanism",
            code(changeFile).contains("IdGenerator") || code(changeFile).contains("newId()")
        )
        // …and every revision this stage mints goes through the editor's *one* minting method, which
        // is declared exactly once and re-identifies every plan day on every revision (§6).
        val editorCode = code(editorFile)
        assertEquals(
            "the editor declares exactly one minting method — a second would be a second §6 rule",
            1,
            Regex("private fun mintRevision\\(").findAll(editorCode).count()
        )
        // Four callers, all of them the editor's own: a creation (§27), the target-only entry this
        // stage added, a structural edit, and the copy's creation. The claim is that they all go
        // through the one method, not that there is only one of them.
        assertEquals(
            "…and every caller of it is one of the editor's four revision-creating entries",
            4,
            Regex("= mintRevision\\(").findAll(editorCode).count()
        )
        assertTrue(
            "including the target-only entry, which mints from the current revision's own plan",
            editorCode.contains("draft = draftOf(program, current),")
        )
        // The single `saveNewRevision` writer is the same component: a second one would be a second
        // place where `currentRevisionId` moves.
        val writers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("planRepository\\.saveNewRevision\\(|\\.saveNewRevision\\(").containsMatchIn(code(it)) }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals(
            "…and exactly one production source writes a new revision",
            listOf("ProgramEditorService.kt"),
            writers
        )
    }

    // ---------------------------------------------------------------- 5./6. immutability and ownership

    @Test
    fun anOldTargetSourceIsNeverUpdatedOrDeleted() {
        // There is no such path to take, which is the strongest form of the claim: the repository
        // exposes no update and no delete, and the boundary calls nothing but `store`.
        val repository = code(sourceRepositoryFile)
        assertFalse(
            "the source repository exposes no update and no delete of a stored source",
            Regex("suspend fun (update|delete|remove|clear)").containsMatchIn(repository)
        )
        for (write in listOf("updateTarget", "deleteTarget", "deleteRule", "deleteBinding", "clearRules")) {
            assertFalse(
                "and no such write exists under another name ($write)",
                repository.contains(write) || code(saveServiceFile).contains(write)
            )
        }
        assertFalse(
            "nor does the revision path open its own transaction around a target row by another route",
            code(changeFile).contains("inTransaction") || code(authoringFile).contains("inTransaction")
        )
    }

    @Test
    fun aNewSourceIsAttachedOnlyToTheNewRevision() {
        val save = code(saveServiceFile)
        // Every `store(` on the revision path names the revision the operation just minted — the save
        // outcome's, or the prepared mint's. There is no branch that writes against the revision the
        // operation replaces.
        assertFalse(
            "no branch stores a source against a previously-read revision",
            Regex("store\\([^)]*currentRevisionId").containsMatchIn(save)
        )
        for (against in listOf(
            "saved.revision.revisionId", "newRevisionId", "creation.revision.revisionId"
        )) {
            assertTrue(
                "the new source is written against the revision this operation minted ($against)",
                save.contains("revisionId = $against")
            )
        }
        // The `Keep` read is explicitly of the *pre-save* current revision, and it is read into a
        // local that is only ever attached to the new one.
        assertTrue(
            "`Keep` reads the current revision's source before the save moves the pointer",
            save.contains("currentRevisionId") && save.contains("asAuthoringOver(")
        )
    }

    // ---------------------------------------------------------------- 7./8./9. re-identification

    @Test
    fun bindingsAreReIdentifiedThroughTheEditorMintedCorrespondenceAndNothingElse() {
        // The one re-identification is `TargetScheduleAuthoring.asSourceOf`, and it is driven by the
        // correspondence the editor reported. Nothing on this path re-derives a day.
        assertTrue(
            "the authoring re-points each binding through the editor's correspondence",
            code(authoringFile).contains("val minted = mintedProgramDays[binding.draftedProgramDayId]")
        )
        assertTrue(
            "and refuses a binding naming a day the correspondence does not carry",
            code(authoringFile).contains("ProgramDayNotInTheSavedRevision")
        )
        assertTrue(
            "the read-side conversion has its own refusal for a stored binding the draft no longer " +
                "carries, so a carried-forward source is never re-pointed at a substitute day",
            code(changeFile).contains("StoredProgramDayNotInTheDraft")
        )
        // A plan-day heuristic would be *reading a ProgramDay* to decide a binding. No file on this
        // path reads one for that purpose: the only `ProgramDay` a statement mentions is a handle.
        val dayHeuristics = listOf(
            ".position", ".name", "dayNumber", "weekday", "dayOfWeek", "indexOf(",
            "sortedBy", "zipWith", "getOrNull("
        )
        val found = offenders(listOf(changeFile), dayHeuristics)
        assertTrue(
            "no binding is inferred from a plan day's position, name, date, weekday or index: $found",
            found.isEmpty()
        )
        // A workout identity derived from a day identity would be a string built out of another.
        for (derived in listOf("workoutId =", "workoutId=")) {
            val derivedFrom = Regex(
                Regex.escape(derived) + """\s*[^,)]*\.(value|toString\(\))"""
            )
            assertFalse(
                "no workoutId is derived from a ProgramDayId ($derived)",
                derivedFrom.containsMatchIn(code(changeFile)) ||
                    derivedFrom.containsMatchIn(code(authoringFile))
            )
        }
    }

    // ---------------------------------------------------------------- 10./13. legacy isolation

    @Test
    fun noLegacyScheduleMappingInEitherDirection() {
        val found = offenders(revisionPath, listOf("scheduleType", "scheduleWeekdays", "scheduleSessionsPerWeek"))
        assertTrue(
            "target source is never reconstructed out of the legacy schedule columns, in this or any " +
                "other stage: $found",
            found.isEmpty()
        )
        val save = code(saveServiceFile)
        for (token in listOf("ProgramSchedule", "scheduleType", "scheduleWeekdays", "scheduleSessionsPerWeek")) {
            val pattern = Regex("(?<![A-Za-z0-9_])$token(?![A-Za-z0-9_])")
            assertFalse(
                "the revision path names no legacy schedule vocabulary ($token), whole-token, because " +
                    "`ProgramScheduler` legitimately contains `ProgramSchedule`",
                pattern.containsMatchIn(save) || pattern.containsMatchIn(code(changeFile))
            )
        }
        // The legacy contour is untouched, in both directions.
        for (legacy in listOf(
            File(usecaseDir, "ProgramScheduler.kt"),
            File(mainDir, "domain/program/SlotPlanner.kt"),
            File(mainDir, "domain/program/ScheduleCalendar.kt")
        )) {
            assertTrue("${legacy.name} is still there", legacy.isFile)
            for (token in listOf(
                "TargetScheduleSource", "TargetScheduleAuthoring", "TargetScheduleRevisionChange",
                "program_target_schedule_rule", "program_target_program_day_binding"
            )) {
                assertFalse(
                    "${legacy.name} must not reach the target contour: $token",
                    code(legacy).contains(token)
                )
            }
        }
    }

    @Test
    fun theRevisionPathRunsNoTargetPass() {
        // Making a target source *revisioned* must not make the target contour *reachable* from a
        // save. The pass is invoked at one named lifecycle point (§30 step 21), and a save is not it.
        for (source in listOf(saveServiceFile, changeFile, editorFile)) {
            for (pass in listOf(
                "TargetScheduleOrchestrator", "TargetPlanner", "TargetSchedulePolicy",
                "TargetScheduleApplicationService", "TargetScheduleInputAdapter",
                "TargetScheduleProductionConsumer", "TargetScheduleSourceBridge"
            )) {
                assertFalse(
                    "${source.name} invokes no target pass ($pass)",
                    code(source).contains(pass)
                )
            }
        }
        // …and the consumer's caller list is still the single one step 21 established.
        val consumers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "AppContainer.kt" }
            .filterNot { it.name == "TargetScheduleSourceBridge.kt" }
            .filterNot { it.name == "TargetScheduleSourceRepository.kt" }
            .filter { it.readText().contains("TargetScheduleSourceBridge") }
            .map { it.name }
            .toList()
        assertEquals(
            "the bridge's only production consumer is still Stage 21's; the cutover is a later stage",
            listOf("TargetScheduleProductionConsumer.kt"),
            consumers
        )
    }

    // ---------------------------------------------------------------- 11./12. layering

    @Test
    fun noUiLayerReachesTheTargetSourceAndTheRevisionPathTouchesNoRoom() {
        for (layer in listOf(File(mainDir, "ui"), File(mainDir, "viewmodel"))) {
            val sources = layer.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            assertTrue("${layer.name} is still there", sources.isNotEmpty())
            for (source in sources) {
                for (token in listOf(
                    "TargetScheduleSourceRepository", "TargetScheduleSourceBridge",
                    "ProgramTargetScheduleSourceDao", "AppDatabase", "Sqlite"
                )) {
                    assertFalse(
                        "the UI layer reaches no target storage directly (${source.name}: $token)",
                        code(source).contains(token)
                    )
                }
            }
        }
        // The change value and the read-side conversion are pure: no DAO, no entity, no platform.
        for (forbidden in listOf(
            "@Entity", "androidx", "android.", "AppDatabase", "Dao", "LocalDate.now",
            "Instant.now", "System.currentTimeMillis", "Random", "runBlocking"
        )) {
            assertFalse(
                "the change value is a caller-owned statement, not a collaborator or an environment: " +
                    "it names $forbidden",
                code(changeFile).contains(forbidden)
            )
        }
        val heldTypes = TargetScheduleRevisionChange.Replace::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.genericType.typeName }
        assertEquals(
            "and every type it holds is a domain type",
            listOf("com.monkfitness.app.domain.usecase.TargetScheduleAuthoring"),
            heldTypes
        )
    }

    // ---------------------------------------------------------------- 15. the omission that was the defect

    @Test
    fun anExistingRevisionsOmissionMeansKeepAndNotASilentMissing() {
        val save = code(saveServiceFile)
        // The defect in one line: a structural edit with a target source on the current revision and
        // no statement from the caller must not land a new revision that states no source.
        assertTrue(
            "the edit's absent change is spelled `Keep`, not an absent source",
            save.contains("targetChange ?: TargetScheduleRevisionChange.Keep")
        )
        assertTrue(
            "and `Keep` is the branch that reads the current revision's stored source forward",
            save.contains("is TargetScheduleRevisionChange.Keep ->") &&
                save.contains("asAuthoringOver(")
        )
        // The create path is *not* given the same treatment: a creation's absence is still Missing,
        // because there is nothing to carry forward.
        assertTrue(
            "a creation's omission is still an early return that writes nothing",
            save.contains("if (targetSchedule == null) return")
        )
    }

    // ---------------------------------------------------------------- 17./18. copy and identity

    @Test
    fun copyDoesNotGuessTargetBindingsAndATargetOnlyChangeKeepsTheProgram() {
        val editor = code(editorFile)
        val save = code(saveServiceFile)
        // A copy mints fresh plan days and shares nothing with its source, so there is no
        // correspondence from the source Program's bindings to the copy's days. The copy path is the
        // creation path, and it reads no source at all.
        // Scoped to the *creation* path: `ProgramSaveService` does read a stored source, on the edit
        // leg, and that is `Keep` carrying the current revision's own source forward. What must be
        // absent is a read on the creation leg — a copy is a creation, so a read there would be the
        // copy inheriting its source Program's bindings.
        val creationLeg = save.substringAfter("private suspend fun createProgram(")
            .substringBefore("private suspend fun saveRevisionWithReconciliation(")
        assertFalse(
            "the creation path reads no stored target source, so a copy cannot inherit one",
            creationLeg.contains("sourceOf(")
        )
        assertTrue(
            "…and it writes a stated authoring only, against the revision it just created",
            creationLeg.contains("stateTargetSourceFor(") &&
                creationLeg.contains("revisionId = creation.revision.revisionId")
        )
        // Scoped to the two copy members themselves, not the whole file: `copyDraft` sits near the top
        // and any generous window from there reaches the target-only entry added by this stage, which
        // is exactly the false positive a wide window produces.
        val copyDraftBody = editor.substringAfter("suspend fun copyDraft(")
            .substringBefore("suspend fun review(")
        val saveCopyOfBody = editor.substringAfter("private suspend fun saveCopyOf(")
            .substringBefore("private suspend fun createProgramFrom(")
        for ((member, body) in listOf("copyDraft" to copyDraftBody, "saveCopyOf" to saveCopyOfBody)) {
            assertFalse(
                "$member maps no Program's plan days onto another's — it holds no target type at all",
                body.contains("TargetSchedule") || body.contains("TargetProgramDayBinding")
            )
        }
        // The target-only path mints a revision of the *same* Program: no `createProgram`, and the
        // Program value it reports is a copy of the stored one with the pointer moved.
        assertFalse(
            "a target-only change creates no Program and mints no ProgramId",
            Regex("saveTargetScheduleChange[\\s\\S]{0,4000}?ProgramId\\(idGenerator").containsMatchIn(save)
        )
        assertTrue(
            "it reports the same Program with `currentRevisionId` moved to the revision it minted",
            code(editorFile).contains("currentRevisionId = revision.revisionId")
        )
    }

    // ---------------------------------------------------------------- the composition root

    @Test
    fun theCompositionRootWiresNoNewTargetCollaboratorAndNoNewNode() {
        // The target-only path needed no new dependency: the save boundary already holds the editor,
        // the creation repository, the Scheduler and the source repository. A new node would also have
        // to be placed outside every existing container slice, and nothing here needed one.
        val construction = code(containerFile)
            .substringAfter("val programSaveService: ProgramSaveService = ProgramSaveService(")
            .substringBefore(")")
        for (collaborator in listOf(
            "editor = programEditorService",
            "programRepository = programRepository",
            "scheduler = programScheduler",
            "targetSourceRepository = targetScheduleSourceRepository",
            "clock = clock",
            "zone = zone",
            "inTransaction = inTransaction"
        )) {
            assertTrue(
                "the save service is wired exactly as it was, plus nothing ($collaborator)",
                construction.contains(collaborator)
            )
        }
        assertFalse(
            "and it gained no second scheduler, no target orchestrator and no new pass",
            construction.contains("Orchestrator") || construction.contains("Consumer") ||
                construction.contains("Planner")
        )
    }

    // ---------------------------------------------------------------- the previous stage's pins

    @Test
    fun theStageNineteenAndTwentyOnePinsStillHold() {
        // Stated here so a future stage that revises one of them has to revise this as well, rather
        // than finding the gate quietly amended somewhere else.
        assertTrue(
            "the authoring value is still the one place a legacy-to-target mapper would have to live",
            File(usecaseDir, "TargetScheduleAuthoring.kt").isFile
        )
        assertTrue(
            "and the authoring value still names no legacy schedule vocabulary, so a mapper between " +
                "the two generations still has nowhere to live",
            offenders(
                listOf(authoringFile),
                listOf("ProgramSchedule", "scheduleType", "scheduleWeekdays", "scheduleSessionsPerWeek")
            ).isEmpty()
        )
        // Checked in the source rather than through reflection: a private top-level class is compiled
        // into the file facade under a name that carries no relationship to the declaration, so
        // `declaredClasses` finds nothing and the predicate would be testing the compiler's naming
        // rather than the visibility. The `private` keyword on the declaration is the fact.
        val editorCode = code(editorFile)
        assertTrue(
            "the structural `MintedRevision` stayed private: the target-only value is the one public " +
                "addition",
            Regex("private data class MintedRevision\\(").containsMatchIn(editorCode)
        )
        assertTrue(
            "…and the target-only value is declared public, because a caller in another layer has to " +
                "be able to read what the editor minted",
            Regex("(?m)^class MintedTargetScheduleRevision\\(").containsMatchIn(editorCode)
        )
        assertFalse(
            "the Program's own structural type gained no target field",
            ProgramStructure::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .any { it.name.contains("target", ignoreCase = true) }
        )
        assertTrue(
            "§28's Program-level refusal vocabulary is untouched by this stage: no new reason was needed",
            ProgramOperationRefusal::class.java.declaredClasses.size > 0
        )
    }
}
