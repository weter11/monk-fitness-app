package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.transfer.DayTransfer
import com.monkfitness.app.domain.program.transfer.ExerciseLibrary
import com.monkfitness.app.domain.program.transfer.ExerciseTransfer
import com.monkfitness.app.domain.program.transfer.FocusShare
import com.monkfitness.app.domain.program.transfer.FocusTransfer
import com.monkfitness.app.domain.program.transfer.PrescriptionTransfer
import com.monkfitness.app.domain.program.transfer.ProgramImportDraft
import com.monkfitness.app.domain.program.transfer.ProgramTransferFixture
import com.monkfitness.app.domain.program.transfer.ProgramTransferDocument
import com.monkfitness.app.domain.program.transfer.RevisionTransfer
import com.monkfitness.app.domain.program.transfer.ScheduleTransfer
import com.monkfitness.app.domain.program.transfer.DurationTransfer
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 13's boundaries, pinned mechanically instead of by convention.
 *
 * The transfer boundary is the first layer of the Program System that handles **untrusted text** and the
 * first that speaks to the **platform**, so the ways it can go wrong are new ones: a Room entity creeping
 * into the file format, an Android `Uri` reaching the parser, a `var` appearing in a reader, a slot being
 * built by the importer instead of by the Scheduler, a second Exercise Library, a ViewModel writing a DAO,
 * a Program's identity leaking into a share. Each of those is asserted against the sources and against the
 * compiled shape:
 *
 * ```text
 * the transfer model      pure: no platform, no data layer, no UI, no Room annotation, and no field any
 *                         identity, timestamp, source or lifecycle fact could occupy
 * the JSON codec          pure: the purity scan reads `program/transfer`, so the foundation's rules about
 *                         `var`, mutable collections, floating point and random sources cover it
 * the use cases           collaborators are exactly the owners §8, §9 and §27 name — and the absences
 *                         (no session, adaptive, progress or library *metadata* anywhere near them) are
 *                         what makes the prohibitions structural
 * the platform            the only place an `Intent`, a `Uri` or a `ContentResolver` appears in the app
 * the wiring              two nodes, constructed once, in the composition root, and reached by no screen
 * ```
 */
class ProgramTransferArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { if (it.isDirectory) it else File("app/$it") }

    /** The files this stage adds, by layer. */
    private val transferSources = listOf(
        "domain/program/transfer/Json.kt",
        "domain/program/transfer/ProgramTransferFormat.kt",
        "domain/program/transfer/ProgramTransferDocument.kt",
        "domain/program/transfer/ProgramTransferJson.kt",
        "domain/program/transfer/ProgramTransferReader.kt",
        "domain/program/transfer/ProgramTransferValidation.kt",
        "domain/program/transfer/ProgramTransferMapper.kt",
        "domain/program/transfer/ProgramTransferResult.kt",
        "domain/program/transfer/ProgramTransferIssue.kt",
        "domain/program/transfer/ProgramImportDraft.kt",
        "domain/program/transfer/ExerciseLibrary.kt"
    )

    private val useCaseSources = listOf(
        "domain/usecase/ProgramExportService.kt",
        "domain/usecase/ProgramImportService.kt",
        "domain/usecase/ProgramExerciseLibrary.kt"
    )

    private val platformSources = listOf(
        "platform/ProgramShareSheet.kt",
        "platform/ProgramDocumentImport.kt"
    )

    private val allSources = transferSources + useCaseSources + platformSources

    // ------------------------------------------------------------------ the scan sees the stage

    @Test
    fun theScanSeesEverySourceThisStageAdds() {
        allSources.forEach { source ->
            assertTrue("expected $source at ${mainDir.absolutePath}", File(mainDir, source).isFile)
        }

        val transferPackage = File(mainDir, "domain/program/transfer")
            .listFiles { file -> file.isFile && file.extension == "kt" }
            ?.map { file -> file.name }
            ?.sorted()
            ?: emptyList()

        assertEquals(
            "the transfer package is exactly the model, the codec, the mapper, the validator, the " +
                "issues, the result, the draft and the port — a twelfth file here is a twelfth " +
                "responsibility in the layer that has to stay pure",
            transferSources.map { source -> source.substringAfterLast("/") }.sorted(),
            transferPackage
        )
    }

    // ------------------------------------------------------------------ the transfer layer is pure

    @Test
    fun theTransferLayerReachesNoPlatformNoDataLayerAndNoUi() {
        val forbidden = listOf(
            "import android", "import androidx", "import kotlinx",
            "import com.monkfitness.app.data.", "import com.monkfitness.app.ui.",
            "import com.monkfitness.app.viewmodel.", "import com.monkfitness.app.animation.",
            "import com.monkfitness.app.poses.", "import com.monkfitness.app.R",
            "import com.monkfitness.app.di.", "import com.monkfitness.app.platform."
        )
        val offenders = codeLines(transferSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.startsWith(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the transfer model, the codec and the mapper are pure Kotlin over the domain: a `Uri`, a " +
                "`Context`, a DAO or a Compose state has no way in (§12, §25). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noTransferSourceNamesAPersistedTypeOrADao() {
        val offenders = codeLines(transferSources).mapNotNull { (source, line) ->
            when {
                Regex("""\b\w*Dao\b""").containsMatchIn(line) -> "$source: $line"
                Regex("""\b\w*Entity\b""").containsMatchIn(line) -> "$source: $line"
                line.contains("@Entity") || line.contains("@Dao") || line.contains("@Database") ->
                    "$source: $line"
                else -> null
            }
        }

        assertTrue(
            "a Room entity never leaves the data layer, and the transfer model is not one: the format must " +
                "not depend on column names (§2, §25). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theTransferModelHasNoFieldAnIdentityTimestampSourceOrLifecycleFactCouldOccupy() {
        // Every type of the transfer hierarchy, and every leaf it is built from.
        val types = listOf(
            ProgramTransferDocument::class.java,
            RevisionTransfer::class.java,
            DurationTransfer.FixedDays::class.java,
            DurationTransfer.Indefinite::class.java,
            ScheduleTransfer.FixedWeekdays::class.java,
            ScheduleTransfer.FlexiblePerWeek::class.java,
            FocusTransfer.Balanced::class.java,
            FocusTransfer.Focused::class.java,
            FocusTransfer.Custom::class.java,
            FocusShare::class.java,
            DayTransfer::class.java,
            ExerciseTransfer::class.java,
            PrescriptionTransfer::class.java
        )
        val identityTypes = listOf(
            "ProgramId", "RevisionId", "ProgramDayId", "ProgramExerciseId", "SlotId", "SessionId",
            "SessionExerciseId", "SetLogId", "PauseId", "AdjustmentId", "DecisionId", "Instant",
            "LocalDate", "ProgramSource", "LifecycleStatus", "SlotStatus"
        )
        // The identities §2 names, listed rather than matched by shape — because the allowlist *has*
        // exactly one `…Id`-shaped field and it is not an identity: `exerciseId` is the Exercise Library
        // key, which flows through the domain as an opaque string (§10) and is part of what a transfer
        // carries. A pattern would have swept it up with `programId`, and a rule that cannot tell "which
        // exercise this plans" from "which Program this was" is a rule that would have to be relaxed the
        // first time it mattered.
        val forbiddenNames = listOf(
            "programId", "revisionId", "programDayId", "programExerciseId", "slotId", "sessionId",
            "sessionExerciseId", "setLogId", "pauseId", "adjustmentId", "decisionId",
            "createdAt", "updatedAt", "archivedAt", "plannedStartDate", "actualStartDate",
            "revisionNumber", "source", "lifecycleStatus", "status", "attempts", "adaptive", "progress"
        )

        val offenders = types.flatMap { type ->
            type.declaredFields
                .filterNot { field ->
                    Modifier.isStatic(field.modifiers) || field.isSynthetic || field.name.startsWith("$")
                }
                .mapNotNull { field ->
                    val nameOffence = if (field.name in forbiddenNames) {
                        "${type.simpleName}.${field.name}"
                    } else {
                        null
                    }
                    val typeOffence = identityTypes.firstOrNull { identity ->
                        field.type.simpleName == identity
                    }?.let { identity -> "${type.simpleName}.${field.name}: $identity" }
                    nameOffence ?: typeOffence
                }
        }

        assertTrue(
            "the transfer model is the allowlist: no identity, no timestamp, no source, no lifecycle and " +
                "no runtime fact has a field to travel in (§2). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePurityScanReadsTheTransferPackage() {
        val purity = File("src/test/java/com/monkfitness/app/domain/ProgramDomainPurityTest.kt")
            .let { if (it.isFile) it else File("app/$it") }

        assertTrue("expected the foundation purity scan", purity.isFile)
        assertTrue(
            "and it must list `program/transfer`: that is what makes its no-`var`, no-mutable-collection, " +
                "no-floating-point and no-random rules apply to a package that reads untrusted text",
            purity.readText().contains("\"program/transfer\"")
        )
    }

    // ------------------------------------------------------------------ the use cases' collaborators

    @Test
    fun theExportServiceHoldsTheProgramAggregateAndNothingElse() {
        val collaborators = ProgramExportService::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .parameterTypes
            .map { type -> type.simpleName }

        assertEquals(
            "an export reads a Program and the revision its pointer names — and holds nothing that could " +
                "produce a session, a set, a statistic, a streak, an adaptive state, a decision or an " +
                "adjustment (§15, §16). No clock appears either: nothing in this layer writes a timestamp",
            listOf("ProgramRepository"),
            collaborators
        )

        val forbidden = listOf(
            "WorkoutSessionRepository", "ProgramProgressRepository", "ProgramAdaptiveRepository",
            "AdaptiveRepository", "WorkoutSession", "SetLog", "FamilyProgressionState", "Clock",
            "IdGenerator", "Intent", "Uri", "Context"
        )
        val offenders = codeLines(listOf("domain/usecase/ProgramExportService.kt")).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue("Found: $offenders", offenders.isEmpty())
    }

    @Test
    fun theImportServiceHoldsTheCreationOwnersAndNothingElse() {
        val collaborators = ProgramImportService::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .parameterTypes
            .map { type -> type.simpleName }

        assertEquals(
            "the import's collaborators are the four owners §8, §9 and §27 name (the creation primitive, " +
                "the Scheduler that decides the opportunities, the lifecycle layer that owns selection and " +
                "the exerciseId boundary) plus the two §26 ports, the calendar and the transaction runner",
            listOf(
                "ProgramRepository", "ProgramScheduler", "ProgramLifecycleService", "ExerciseLibrary",
                "Clock", "IdGenerator", "ZoneId", "Function2"
            ),
            collaborators
        )

        val forbidden = listOf(
            "WorkoutSessionRepository", "ProgramProgressRepository", "ProgramAdaptiveRepository",
            "AdaptiveRepository", "ProgramPlanRepository", "AppStateRepository", "WorkoutSession",
            "SetLog", "FamilyProgressionState", "WorkoutGenerator", "getExerciseLibrary", "Dao",
            "Entity", "Intent", "Uri", "Context"
        )
        val offenders = codeLines(listOf("domain/usecase/ProgramImportService.kt")).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "nothing here can read a session, a statistic, an adaptive row or a second revision, and there " +
                "is no DAO: an import reaches storage through the two owners of it and nothing else. " +
                "Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theImporterNeverBuildsAnOpportunityAndTheSchedulerStillDoes() {
        val importer = codeOf("domain/usecase/ProgramImportService.kt")
        val scheduler = codeOf("domain/usecase/ProgramScheduler.kt")

        assertFalse(
            "an import decides what Program is being created; it does not decide what opportunities that " +
                "Program receives (§8, §20). A slot value constructed here would be a second copy of the " +
                "scheduling rule",
            importer.contains("WorkoutSlot(")
        )
        listOf("SlotPlanner", "SlotPlan", "ScheduleRequest", "ScheduleWindow", "SlotIdSource", "plannedFor")
            .forEach { token ->
                assertFalse(
                    "the importer must not name '$token': the scheduling decision is the Scheduler's own",
                    importer.contains(token)
                )
            }
        assertTrue(
            "and the Scheduler is where the decision is, for a Program that is being created as well as " +
                "for one that is stored",
            importer.contains("scheduler.initialSlotsFor(program, revision)")
        )
        assertTrue(
            "which the Scheduler answers through the one implementation of the decision",
            scheduler.contains("private fun decide(") && scheduler.contains("SlotPlanner.plan(")
        )
        assertEquals(
            "and it builds a request in exactly one place, so the window, the anchor and the identity " +
                "source are decided once (§20)",
            1,
            Regex("""SlotPlanner\.plan\(""").findAll(scheduler).count()
        )
    }

    // ------------------------------------------------------------------ the exercise library boundary

    @Test
    fun thereIsOneProductionExerciseLibraryAndItReadsTheCatalogueOnce() {
        val implementations = allMainSources()
            .filter { source ->
                Regex("""\bclass \w+\s*:\s*ExerciseLibrary""").containsMatchIn(code(source.readText()))
            }
            .map { source -> relative(source) }

        assertEquals(
            "§5 asks for one explicit validation port, not a second Exercise Library architecture: one " +
                "production implementation of the port and no second reader of the catalogue",
            listOf("domain/usecase/ProgramExerciseLibrary.kt"),
            implementations
        )
        assertTrue(
            "and the composition root is what hands it to the import",
            codeOf("di/AppContainer.kt").contains("exerciseLibrary = ProgramExerciseLibrary()")
        )

        val library = codeOf("domain/usecase/ProgramExerciseLibrary.kt")
        assertTrue(
            "the implementation takes the ids from the app's own catalogue and keeps nothing else",
            library.contains("WorkoutGenerator().getExerciseLibrary()")
        )
        assertFalse(
            "and it copies no metadata into the program's world: an export carries no library data (§5)",
            library.contains("Equipment") || library.contains("ExerciseSkeletonData")
        )
    }

    @Test
    fun theProductionLibraryKnowsTheExercisesTheTransferFixturesPlanWith() = runBlocking {
        val library = ProgramExerciseLibrary()

        val unknown = ProgramTransferFixture.KNOWN_EXERCISE_IDS.filterNot { exerciseId ->
            library.knows(exerciseId)
        }

        assertEquals(
            "the ids the transfer suites plan with are ids the app really holds — which is what makes " +
                "`ProgramImportServiceTest`'s acceptances meaningful rather than lucky. (This is the check " +
                "that first exposed the older graph fixture's `pushup`/`pike_pushup`, ids the shipped " +
                "catalogue does not carry.)",
            emptyList<String>(),
            unknown
        )
    }

    // ------------------------------------------------------------------ the platform boundary

    @Test
    fun theOnlyPlaceAnIntentAUriOrAContentResolverAppearsIsThePlatformPackage() {
        val platformTokens = listOf("Intent", "Uri", "contentResolver", "FileProvider", "ACTION_SEND")
        val domainReach = allMainSources()
            .filter { source -> relative(source).startsWith("domain/") }
            .filter { source -> platformTokens.any { token -> code(source.readText()).contains(token) } }
            .map { source -> relative(source) }

        assertTrue(
            "§11 and §12 keep the Android boundary at the platform: no domain source may name an Intent, a " +
                "Uri or a ContentResolver — the importer takes bytes and the exporter produces bytes. " +
                "Found: $domainReach",
            domainReach.isEmpty()
        )

        val platformReach = platformSources.map { source -> source to codeOf(source) }
        platformTokens.forEach { token ->
            assertTrue(
                "and the platform package does name '$token', so the rule above is not vacuous",
                platformReach.any { (_, text) -> text.contains(token) }
            )
        }
        val forbidden = listOf("Repository", "Dao", "AppDatabase", "AppContainer")
        val offenders = platformReach.flatMap { (source, text) ->
            forbidden.filter { token -> text.contains(token) }.map { token -> "$source: $token" }
        }

        assertTrue(
            "the platform layer builds a share and reads a document; it owns no persistence and no " +
                "composition (§25: a ViewModel or a screen writes no DAO, and neither does this). Found: " +
                "$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noUiOrViewModelSourceReachesTheTransferStageYet() {
        val names = listOf(
            "ProgramExportService", "ProgramImportService", "ProgramExerciseLibrary", "ProgramShareSheet",
            "ProgramDocumentImport", "ProgramImportDraft"
        )
        val offenders = listOf(File(mainDir, "ui"), File(mainDir, "viewmodel"))
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .flatMap { source ->
                val text = code(source.readText())
                names.filter { name -> text.contains(name) }.map { name -> "${source.name}: $name" }
            }
            .toList()

        assertTrue(
            "§20: this stage lands the transfer mechanism and the platform boundary, not the affordance. " +
                "(The legacy catalogue accessor `getExerciseLibrary` is deliberately not in this list: it " +
                "predates the target architecture and is the vocabulary of the shipped generator.) " +
                "The screen that offers Share and Import — and the ViewModel behind it — is §30 step 14, " +
                "so no UI source names this stage, and §26's 'a ViewModel receives what it needs' is not " +
                "yet being exercised here. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theDraftThePipelineProducesCannotBeAssembledByACaller() {
        val source = codeOf("domain/program/transfer/ProgramImportDraft.kt")

        assertTrue(
            "§7's Import Draft is produced by the pipeline and by nothing else: its constructor is " +
                "internal, so a caller cannot hand a save a draft that skipped validation",
            source.contains("internal constructor(")
        )
    }

    // ------------------------------------------------------------------ the wiring

    @Test
    fun theCompositionRootWiresBothHalvesOnceAndNothingElseDoes() {
        val container = codeOf("di/AppContainer.kt")

        assertTrue(
            "the composition root constructs the export (§26)",
            container.contains("val programExportService: ProgramExportService = ProgramExportService(")
        )
        assertTrue(
            "and the import, with the four owners and the two ports §8, §9 and §27 name",
            container.contains("val programImportService: ProgramImportService = ProgramImportService(")
        )
        listOf("programExportService", "programImportService").forEach { name ->
            assertEquals(
                "$name is a graph node, constructed once, as a property like every other node",
                1,
                Regex("""val $name: \w+ = """).findAll(container).count()
            )
        }

        val constructionSites = allMainSources()
            .filter { source ->
                val text = code(source.readText())
                text.contains("ProgramExportService(") || text.contains("ProgramImportService(")
            }
            .map { source -> relative(source) }

        assertEquals(
            "and nothing else constructs them: a second construction is a second owner of a layer that " +
                "owns the transfer boundary",
            listOf("di/AppContainer.kt"),
            constructionSites.filterNot { it == "domain/usecase/ProgramExportService.kt" }
                .filterNot { it == "domain/usecase/ProgramImportService.kt" }
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun allMainSources(): List<File> = mainDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()

    private fun relative(file: File): String = file.relativeTo(mainDir).path.replace('\\', '/')

    private fun codeOf(relativePath: String): String = code(
        File(mainDir, relativePath).also { source ->
            assertTrue("expected $relativePath", source.isFile)
        }.readText()
    )

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun codeLines(sources: List<String>): List<Pair<String, String>> = sources.flatMap { source ->
        codeOf(source).lines().map { line -> line.trim() }.filter { it.isNotEmpty() }
            .map { line -> source.substringAfterLast("/") to line }
    }
}
