package com.monkfitness.app.domain.usecase

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture gates for the authored content and the activated provider.
 *
 * The failure mode here is not doing too little but authoring content that *looks* authored while being
 * derived: a level computed from a catalogue index, a prescription read out of `Exercise.durationSeconds`,
 * a rung count widened by "one more per family", a seed quietly covering a fifth family. Each produces a
 * ladder that passes a behavioural test and fails the architecture, and these gates read the production
 * **source**, so the derivation is caught whether or not it happens to be right today.
 */
class ProductionProgressionContentArchitectureTest {

    private val appRoot = File("src/main/java/com/monkfitness/app")

    private fun file(path: String) = File(appRoot, path)

    private val definitions = file("domain/product/ProductionProgressionRelationDefinitions.kt")
    private val bootstrap = file("bootstrap/BuiltInProgressionCatalogueBootstrap.kt")
    private val provider = file("domain/usecase/StoredProgressionRelationProvider.kt")
    private val port = file("domain/adaptive/integration/ProgressionRelationProvider.kt")
    private val entity = file("data/model/ProgressionRelationVariantEntity.kt")
    private val repository = file("data/repository/ProgressionRelationRepository.kt")
    private val container = file("di/AppContainer.kt")
    private val application = File("src/main/java/com/monkfitness/app/MonkFitnessApplication.kt")

    /** The file's code with comments removed, so prose about a token cannot satisfy a rule naming it. */
    private fun code(source: File): String = source.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun allProduction(): List<File> = appRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /**
     * Exactly one production source of progression content: two would mean two answers to "what does this
     * family declare", and the provider would read whichever won the wiring. Matched on the type name, so
     * a second source under a different name is still caught.
     */
    @Test
    fun thereIsExactlyOneProductionProgressionContentSource() {
        // An **authoring** site states a ladder as a literal: variants with a quoted exercise id *and* a
        // quoted family id in the same declaration. The mapper and the planner both build domain values
        // and mention prescriptions but never a family id beside a variant, so requiring **both** is what
        // separates authored content from derived or translated content.
        val carriers = allProduction()
            .filter { it != definitions }
            .filter { source ->
                val body = code(source)
                Regex("""ProgramProgressionVariant\(\s*-?\d+\s*,\s*\"[^"]+\"""")
                    .containsMatchIn(body) &&
                    Regex("""familyId\s*=\s*\"""").containsMatchIn(body)
            }
            .map { it.name }

        assertEquals(
            "exactly one production file authors progression content by stating family ids and " +
                "prescriptions as literals — a second source would be a second answer to what a family " +
                "declares: $carriers",
            emptyList<String>(),
            carriers
        )
    }

    /**
     * The content source is DATA: each forbidden construct is a way a ladder could be *computed* rather
     * than authored, which is what keeps "this is data" true rather than aspirational.
     */
    @Test
    fun theContentSourceInfersNothing() {
        val body = code(definitions)

        assertFalse(
            "no `when` over a family or category: a ladder's levels are authored, not selected by a rule",
            Regex("""\bwhen\s*\(""").containsMatchIn(body)
        )
        assertFalse(
            "no branch on a category or subcategory",
            Regex("""ExerciseCategory|ExerciseSubCategory""").containsMatchIn(body)
        )
        assertFalse(
            "no read of the shipped exercise catalogue — a rung's level and target are stated here, not " +
                "taken from `Exercise` metadata",
            Regex("""\bExercise\b|SHIPPED_EXERCISE_CATALOGUE|WorkoutGenerator""").containsMatchIn(body)
        )
        assertFalse(
            "no difficulty coefficient, plan phase or catalogue position as a level",
            Regex("""phase|difficulty|coeff|indexOf|ordinal""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        )
        assertFalse(
            "no adaptive history or a family's current exercise as ladder content",
            Regex("""FamilyProgressionState|currentExerciseId|AdaptiveDecision|AdaptiveAdjustment""")
                .containsMatchIn(body)
        )
        assertFalse(
            "no legacy Stage-1 pilot source",
            Regex("""PilotProgression""").containsMatchIn(body)
        )
    }

    /**
     * `RepPrescription`/`TimePrescription` spelled out per rung is what makes the content *declared*; a
     * scalar, a shared constant or a computed value would make a rung's target a derivation.
     */
    @Test
    fun everyRungCarriesAnExplicitDomainPrescription() {
        val body = code(definitions)
        val variants = Regex("""ProgramProgressionVariant\(\s*-?\d+\s*,\s*"[^"]+"\s*,""").findAll(body).count()
        val prescriptions =
            Regex("""(RepPrescription|TimePrescription)\(listOf\([^)]*\)\)""").findAll(body).count()

        assertEquals(
            "the ladder file holds no prose, so this counts declarations only — every variant on one line",
            17,
            variants
        )
        assertEquals(
            "every one of the 17 declared rungs states its prescription as an explicit domain value",
            variants,
            prescriptions
        )
        assertFalse(
            "and no second prescription model is introduced alongside them",
            Regex("""(class|data class)\\s+\\w*Prescription""").containsMatchIn(body)
        )
    }

    /**
     * Counted from the declarations rather than restated, so a fifth relation added here — exactly where
     * one would be added — fails instead of silently widening the seed.
     */
    @Test
    fun exactlyFourFamiliesAreAuthorised() {
        val body = code(definitions)

        assertEquals(
            "exactly four families declare a ladder",
            4,
            Regex("""ProgramProgressionRelation\(\s*""").findAll(body).count()
        )
        listOf("\"pushups\"", "\"squats\"", "\"lunges\"", "\"pullups\"").forEach { familyId ->
            assertTrue(
                "'$familyId' is one of the four",
                body.contains("familyId = $familyId")
            )
        }
    }

    /**
     * `plank` and `glute_bridge` are absent **by decision**, and a one-rung "ladder" would convert that
     * honest absence into a claim that the family declares a hierarchy. Asserted on the content source
     * rather than the seeded rows, because this is a statement about *what was authored*.
     */
    @Test
    fun plankAndGluteBridgeAreNotAuthored() {
        val body = code(definitions)

        assertFalse(
            "`plank` is deliberately undeclared — its intended five-step time progression needs one " +
                "exercise at five levels, which the domain rejects",
            body.contains("familyId = \"plank\"")
        )
        assertFalse(
            "and `glute_bridge` likewise — it has one exercise in its family",
            body.contains("familyId = \"glute_bridge\"")
        )
        assertFalse(
            "and neither is given a one-rung relation to look complete",
            Regex("""familyId\s*=\s*"(plank|glute_bridge)"""").containsMatchIn(body)
        )
    }

    /**
     * Both are real catalogue exercises of an authorised family, so "they are in the catalogue" is not a
     * reason to appear in a ladder — the gate is that they do not.
     */
    @Test
    fun theExcludedExercisesAppearInNoRung() {
        val body = code(definitions)

        assertFalse(
            "`pushups_military` is not placed in the `pushups` ladder",
            Regex("""ProgramProgressionVariant\([^)]*"pushups_military"""").containsMatchIn(body)
        )
        assertFalse(
            "`deep_squat` is not placed in the `squats` ladder — it is a mobility hold, not a variation",
            Regex("""ProgramProgressionVariant\([^)]*"deep_squat"""").containsMatchIn(body)
        )
        assertFalse(
            "`side_plank` is not placed in a `plank` ladder either, and `plank` has none",
            Regex("""ProgramProgressionVariant\([^)]*"side_plank"""").containsMatchIn(body)
        )
    }

    /**
     * Seeded content enters the catalogue at exactly one boundary. Anything else that stores a
     * `ProgramProgressionRelation` in production is a second seed path — the shape from which a ladder
     * reappears without anybody deciding it should.
     */
    @Test
    fun theBootstrapIsTheOnlyPlaceBuiltInContentIsSeeded() {
        // `ProductionProgressionRelationDefinitions` may appear where it is defined, in the bootstrap
        // that reads the ladders and in the container that wires the bootstrap. Any **fourth** production
        // file naming it is a second consumer of the authored ladders, which is what this gate forbids —
        // not the wiring.
        val seeders = allProduction()
            .filter { it != bootstrap && it != container && it != definitions }
            .filter { code(it).contains("ProductionProgressionRelationDefinitions") }
            .map { it.name }

        assertEquals(
            "only the bootstrap reads the authored definitions, so a ladder can enter storage through " +
                "one door: $seeders",
            emptyList<String>(),
            seeders
        )
    }

    /**
     * The provider never seeds; this gate pins the other half of the ordering guarantee — that the
     * bootstrap is actually *invoked* from the application, so "no consumer exists yet" is a fact about
     * the wiring rather than a hope.
     */
    @Test
    fun theBootstrapIsInvokedFromApplicationStart() {
        val body = code(application)

        assertTrue(
            "the application runs the built-in progression bootstrap at start-up",
            Regex("""builtInProgressionCatalogueBootstrap\s*\.\s*bootstrap\(\)""").containsMatchIn(body)
        )
    }

    /**
     * Pinned by its declared fields: a second collaborator is where a clock, a scope, a DAO or an engine
     * would appear, and any of those would let the seed behave differently depending on when or from
     * where it ran.
     */
    @Test
    fun theBootstrapHoldsExactlyTheContentSourceAndTheRepository() {
        val body = code(bootstrap)

        assertEquals(
            "the bootstrap holds exactly one collaborator — the catalogue repository",
            1,
            Regex("""private val \w+""").findAll(body).count()
        )
        assertTrue(
            "and it is the progression relation repository",
            Regex("""private val \w+\s*:\s*ProgressionRelationRepository""").containsMatchIn(body)
        )
        assertTrue(
            "reading the authored definitions",
            body.contains("ProductionProgressionRelationDefinitions")
        )
        assertFalse(
            "and it reaches no database, no DAO and no engine directly — content goes through the " +
                "repository like any other write",
            Regex("""\bDao\b|RoomDatabase|AppDatabase|ProgramAdaptiveEngine""").containsMatchIn(body)
        )
    }

    /** The bootstrap is absent-only: it never overwrites and never widens its own scope. */
    @Test
    fun theBootstrapFillsAbsencesOnlyAndSeedsNothingElse() {
        val body = code(bootstrap)

        assertTrue(
            "it asks the catalogue whether the family is already declared",
            body.contains("relationOf(definition.familyId)")
        )
        assertTrue(
            "and skips a family that is",
            body.contains("return@forEach")
        )
        assertTrue(
            "storing exactly the definitions it iterates — there is no second list to widen",
            body.contains("store(definition)")
        )
        assertFalse(
            "and it never removes or replaces an existing ladder",
            Regex("""\.replace\(|\.remove\(""").containsMatchIn(body)
        )
    }

    /**
     * Asserted on declared fields rather than source text: a collaborator list is where a default ladder,
     * a static content read or a clock would hide, none of which a behavioural test over a populated
     * catalogue could distinguish from correct behaviour.
     */
    @Test
    fun theProviderHoldsExactlyOneCollaboratorAndItIsTheRepository() {
        val fields = provider.readLines()
            .filter { it.contains("private val") }
            .map { it.trim().substringAfter("private val ").substringBefore(":").trim() }

        assertEquals(
            "the provider holds exactly the repository and nothing else",
            listOf("relations"),
            fields
        )
    }

    /**
     * Keeps the persisted catalogue authoritative: a provider that also read the definitions would hold
     * two answers to "what does this family declare", and they could disagree the moment a stored ladder
     * was replaced.
     */
    @Test
    fun theProviderDoesNotReadTheStaticContentSource() {
        val body = code(provider)

        assertFalse(
            "the provider never reads the authored definitions directly — its source is the persisted " +
                "catalogue, so shipped and stored content cannot disagree",
            body.contains("ProductionProgressionRelationDefinitions")
        )
        assertFalse(
            "nor the shipped catalogue, an adaptive state, a decision or a legacy pilot",
            Regex("""WorkoutGenerator|FamilyProgressionState|currentExerciseId|AdaptiveDecision|""" +
                """AdaptiveAdjustment|PilotProgression""").containsMatchIn(body)
        )
        assertTrue(
            "and it delegates its one question to the repository",
            body.contains("relations.relationOf(familyId)")
        )
    }

    /**
     * Scanned on code with comments stripped, because both files *discuss* `runBlocking` in their KDoc
     * and a text scan would fail this gate on its own explanation.
     */
    @Test
    fun theProviderPathHasNoBlockingBridge() {
        val path = listOf(port, provider, file("domain/adaptive/integration/AdaptiveTargetSlot.kt"))

        val offenders = path.filter { code(it).contains("runBlocking") }.map { it.name }

        assertTrue(
            "no file on the progression-relation provider path bridges a suspending read: $offenders",
            offenders.isEmpty()
        )
        assertTrue(
            "the port is suspending, which is what removes the need for a bridge",
            Regex("""suspend fun relationOf""").containsMatchIn(code(port))
        )
    }

    /**
     * The load-bearing half of the ordering guarantee: a provider that seeded on read would make its own
     * answer depend on when it was asked, so a deleted ladder would come back.
     */
    @Test
    fun theProviderPerformsNoWrite() {
        val body = code(provider)

        assertFalse(
            "the provider stores nothing — no seed-on-read fallback, no repair, no replace",
            Regex("""\.(store|replace|remove|insert)\w*\(""").containsMatchIn(body)
        )
        assertEquals(
            "and its whole body is the one delegated read",
            1,
            Regex("""override suspend fun relationOf""").findAll(body).count()
        )
    }

    /**
     * This stage adds content, not schema. The `(familyId, exerciseId)` key is re-asserted here because
     * this is the stage that would be tempted to widen it to author a ladder the domain cannot represent
     * — and it does not need to, since the content was authored to fit.
     */
    @Test
    fun theStorageShapeIsUnchangedAndTheIdentityRuleIsNotWeakened() {
        assertTrue(
            "the entity still keys on (familyId, exerciseId) — one exercise is one position in one " +
                "family's hierarchy",
            code(entity).contains("""primaryKeys = ["familyId", "exerciseId"]""")
        )
        assertFalse(
            "and this stage added no Program or revision ownership to the ladder table",
            Regex("""programId|revisionId""").containsMatchIn(code(entity))
        )
        // Matched on a *declared column*, not the word "ladder": the entity's KDoc discusses ladders at
        // length, and a word match would fail this gate on its explanation of the table.
        assertFalse(
            "no serialized ladder blob and no content column: the five declared columns are the " +
                "authored ones",
            Regex("""(ladder|payload|json|blob|definition)\w*`?""").containsMatchIn(
                Regex("""val \w+:\s*\w+""").findAll(code(entity)).joinToString(" ") { it.value }
            )
        )
    }

    /** The repository still decides nothing and still lets the domain enforce its own rules. */
    @Test
    fun theRepositoryAddsNoSemanticRuleOfItsOwn() {
        val body = code(repository)

        assertTrue(
            "the repository assembles the stored relation through the domain's own constructor",
            body.contains("storedProgressionRelation(familyId, rows)")
        )
        assertFalse(
            "it does not reorder, deduplicate or otherwise repair a family's rows — the domain's " +
                "constructor is the only owner of those rules",
            Regex("""\.sortedBy|\.distinct|\.groupBy""").containsMatchIn(body)
        )
        assertFalse(
            "and it holds no content source, no generator and no state",
            Regex("""ProductionProgressionRelationDefinitions|WorkoutGenerator|FamilyProgressionState""")
                .containsMatchIn(body)
        )
    }

    /**
     * The inverse and strictly stronger: where `NoDeclaredProgression` was once required to
     * be wired, and is now forbidden.
     */
    @Test
    fun productionWiresTheStoredProviderAndNotTheEmptySource() {
        val body = code(container)

        assertTrue(
            "the adaptive integration is wired with the stored provider",
            Regex("""relations\s*=\s*storedProgressionRelationProvider""").containsMatchIn(body)
        )
        assertFalse(
            "and `NoDeclaredProgression` is no longer what production passes — two answers to one wiring " +
                "would mean the catalogue could be served or ignored by wiring order alone",
            Regex("""relations\s*=\s*NoDeclaredProgression""").containsMatchIn(body)
        )
        assertTrue(
            "the provider is constructed by the composition root",
            body.contains("StoredProgressionRelationProvider(")
        )
        assertTrue(
            "and so is the repository it reads, and the bootstrap that fills it",
            body.contains("ProgressionRelationRepository(") &&
                body.contains("BuiltInProgressionCatalogueBootstrap(")
        )
    }

    /**
     * A ladder is a global definition and adaptive history is per-revision state, so folding them
     * together would give one class two unrelated lifetimes.
     */
    @Test
    fun theCatalogueRepositoryRemainsSeparateFromAdaptiveHistory() {
        val body = code(repository)

        assertFalse(
            "the catalogue repository is not a method on `ProgramAdaptiveRepository`",
            body.contains("ProgramAdaptiveRepository")
        )
    }

    /**
     * Read over the content source, the bootstrap and the provider, because such a dependency arrives by
     * being *used*, and only a scan sees a use that no single test happens to exercise.
     */
    @Test
    fun theStageIntroducesNoGenerationUiSchedulerOrImportDependency() {
        val owned = listOf(definitions, bootstrap, provider)
        val forbidden = listOf(
            "WorkoutGenerator", "GenerationPreferences", "ProgramScheduler",
            "ImportExport", "ViewModel", "ProgramProgressRepository", "ProductionFocusClassification",
            "TargetSchedule", "ProgramGenerationService"
        )

        val offenders = owned
            .filter { it.isFile }
            .flatMap { source ->
                forbidden
                    .filter { token -> code(source).contains(token) }
                    .map { "${source.name}: $it" }
            }

        assertTrue(
            "the content source, the bootstrap and the provider reach no generator, preference, " +
                "scheduler, import/export, view model or focus classification: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * Structural rather than behavioural: the whole claim is that this stage supplied *data*, and the
     * only mechanical way to show that is that the two files owning the semantics did not change.
     */
    @Test
    fun theEngineAndThePolicyAreNotModifiedByThisStage() {
        val engine = file("domain/adaptive/engine/ProgramAdaptiveEngine.kt")
        val policy = file("domain/adaptive/engine/ProgramAdaptivePolicy.kt")

        // The only engine-visible change this stage could legitimately force is the `suspend` signature
        // on the port. The engine does not implement the port, so it must not mention it at all.
        assertFalse(
            "the engine does not implement or construct a progression-relation provider",
            code(engine).contains("ProgressionRelationProvider")
        )
        assertTrue(
            "and the policy still owns its own confirmation thresholds, unchanged",
            code(policy).contains("progressConfirmingWindows")
        )
    }

    /**
     * Without this floor, a renamed package would empty every offender list and this class would go green
     * without having read anything.
     */
    @Test
    fun everyFileThisClassRulesOverExists() {
        listOf(
            definitions, bootstrap, provider, port, entity, repository, container, application,
            file("domain/adaptive/engine/ProgramAdaptiveEngine.kt"),
            file("domain/adaptive/engine/ProgramAdaptivePolicy.kt"),
            file("domain/adaptive/integration/AdaptiveTargetSlot.kt")
        ).forEach { source ->
            assertTrue("${source.name} is missing, so every rule over it is vacuous", source.isFile)
        }
    }
}