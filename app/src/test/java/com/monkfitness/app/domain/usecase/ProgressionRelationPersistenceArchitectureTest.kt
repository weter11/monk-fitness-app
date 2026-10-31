package com.monkfitness.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The persisted progression catalogue is an **app-owned definition**, and everything that could turn it
 * into an inferred ladder is absent.
 *
 * Each rule is a way this could have gone wrong while still compiling and passing its behavioural tests
 * — a second ladder type, a serialized blob, a Program-scoped key, a heuristic level, family *state*
 * read as a hierarchy. The gates scan source with comments stripped, so prose *about* a forbidden thing
 * cannot trip them and a real use cannot hide in a comment.
 */
class ProgressionRelationPersistenceArchitectureTest {

    private val appRoot = File("src/main/java/com/monkfitness/app").absoluteFile

    private fun file(path: String) = File(appRoot, path)

    private val entity = file("data/model/ProgressionRelationVariantEntity.kt")
    private val dao = file("data/local/ProgressionRelationVariantDao.kt")
    private val mapper = file("data/mapper/ProgressionRelationMappers.kt")
    private val repository = file("data/repository/ProgressionRelationRepository.kt")
    private val provider = file("domain/usecase/StoredProgressionRelationProvider.kt")
    private val container = file("di/AppContainer.kt")
    private val adaptiveRepository = file("data/repository/ProgramAdaptiveRepository.kt")

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(source: File): String = source.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    /**
     * The entity's declared `val` constructor fields, in declaration order.
     *
     * `DOT_MATCHES_ALL` is **load-bearing**: the constructor spans several lines, so a `.` without
     * DOTALL finds nothing and the `!!` turns the assertion into a bare `NullPointerException` that
     * reads as a production defect when it is a gate that never ran. The helper asserts its own match,
     * so the same mistake names the entity instead of throwing.
     */
    private fun declaredFields(): List<String> {
        val body = entity.readText()
        val constructor = Regex(
            "data class ProgressionRelationVariantEntity\\((.*?)\\n\\)",
            RegexOption.DOT_MATCHES_ALL
        ).find(body)
        assertTrue(
            "the entity's constructor block was not found — a gate over it would be vacuous, and this " +
                "is the DOT_MATCHES_ALL trap: the parameter list spans several lines",
            constructor != null
        )
        return constructor!!.groupValues[1]
            .lines().map { it.trim() }
            .filter { it.startsWith("val ") }
    }

    /**
     * Whole-token matching, so `ProgramAdaptiveRepository` is not found inside a longer name.
     *
     * The trailing lookahead is also the trap: a ban written on a *prefix* of the real symbol can never
     * match it (`PilotProgression` is not inside `PilotProgressionProfiles`), so such a gate reports
     * "not consulted" while blind to the one name it means to catch. The pilot is therefore banned by
     * [pilotReference], which matches a leading `PilotProgression` in any casing and any suffix.
     */
    private fun names(body: String, token: String): Boolean =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(body)

    /**
     * Any reference to the retired pilot's progression vocabulary: matched as a leading
     * `PilotProgression`, since a whole-token rule would be green on a tree naming
     * `PilotProgressionProfiles` directly.
     */
    private fun pilotReference(body: String): Boolean =
        Regex("(?<![A-Za-z0-9_])[Pp]ilotProgression").containsMatchIn(body)

    /**
     * The persisted row is exactly the five facts of a ladder rung — nothing belonging to a plan, a
     * history or a policy. Asserted on the declared fields, because a column added later would be a
     * claim the schema does not model.
     */
    @Test
    fun thePersistedRowIsExactlyAFamilyLevelExerciseAndPrescription() {
        val declared = declaredFields()

        assertEquals(
            "a ladder rung is a family, a level, an exercise and its full prescription — nothing more, " +
                "because a catalogue is configuration and not adaptive history: $declared",
            listOf(
                "val familyId: String,",
                "val level: Int,",
                "val exerciseId: String,",
                "val prescriptionDimension: String,",
                "val perSetTargets: List<Int>"
            ),
            declared
        )
    }

    /** **No Program, revision, state, outcome, policy, timestamp, ranking or difficulty column.** */
    @Test
    fun theLadderRowCarriesNoPlanScopeNoHistoryAndNoDifficultySemantics() {
        val body = code(entity)

        for (forbidden in listOf(
            "programId",
            "revisionId",
            "progressionLevel",
            "currentExerciseId",
            "adaptationState",
            "decisionId",
            "adjustmentId",
            "policy",
            "createdAt",
            "updatedAt",
            "timestamp",
            "rank",
            "ranking",
            "difficulty",
            "difficultyScore",
            "inferredDifficulty",
            "enabled",
            "isEnabled"
        )) {
            assertFalse(
                "a ladder row must not carry '$forbidden': a plan scope would make the ladder " +
                    "revision-owned, and the rest are adaptive history or a heuristic",
                names(body, forbidden)
            )
        }
    }

    /** **The table is named exactly, and the key is the family's own pair.** */
    @Test
    fun theTableAndItsKeyAreNamedTheWayTheCatalogueIsScoped() {
        val body = code(entity)

        assertTrue(
            "the table is named for the catalogue, not for a plan",
            body.contains("tableName = \"progression_relation_variant\"")
        )
        assertTrue(
            "and the identity is (familyId, exerciseId) — one exercise is one position per family",
            body.contains("primaryKeys = [\"familyId\", \"exerciseId\"]")
        )
        assertFalse(
            "and explicitly NOT (familyId, level), because two variants at one level are a legitimate " +
                "declaration",
            body.contains("primaryKeys = [\"familyId\", \"level\"]")
        )
    }

    /**
     * One row per progression variant: the ladder is normalized, not a serialized blob.
     *
     * The two halves are different kinds of thing and conflating them would forbid the wrong one — the
     * ladder is a SET of rows, while a rung's prescription is an ordered list of targets in one column
     * via the schema's existing lossless converter. So the assertion is structural: every rung is its
     * own row carrying its own family, level, exercise and targets, which is what makes ordering,
     * contiguity and per-rung identity answerable by the database rather than by parsing a string. A
     * column holding a *whole ladder* as text is what would break that, and only that is refused.
     */
    @Test
    fun theCatalogueIsNormalizedRowsAndNotASerializedBlob() {
        val body = code(entity)
        val fields = declaredFields()

        assertTrue(
            "the row is keyed by the family and the exercise it declares, so each variant is its own " +
                "row rather than an element of a stored list",
            body.contains("primaryKeys = [\"familyId\", \"exerciseId\"]")
        )
        assertEquals(
            "each row carries that rung's own family, position, exercise and targets",
            listOf("familyId", "level", "exerciseId", "prescriptionDimension", "perSetTargets"),
            fields.map { field -> field.removePrefix("val ").substringBefore(":") }
        )

        // The per-set targets stay a LIST, carried by the schema's existing lossless converter. This is
        // the half that must NOT be forbidden: an ordered target list in one TEXT column is the
        // established representation; a single Int here would be the defect.
        assertTrue(
            "the per-set targets are declared as the whole ordered list, which the schema's existing " +
                "converter already stores losslessly — a single Int here would be the defect",
            fields.any { it.trim() == "val perSetTargets: List<Int>" }
        )

        for (forbidden in listOf(
            "ladderJson",
            "variantsJson",
            "relationBlob",
            "serializedLadder",
            "ladderPayload",
            "levelsJson",
            "rungsJson"
        )) {
            assertFalse(
                "a column holding the whole ladder as one serialized value is refused ('$forbidden'): " +
                    "ordering, contiguity and per-rung identity would have to be recovered by parsing",
                names(body, forbidden)
            )
        }
        assertFalse(
            "and no field stores a collection of variants — one row per variant is the whole claim",
            fields.any { it.contains("List<ProgramProgressionVariant>") } ||
                fields.any { it.contains("variants") }
        )
    }

    /** **Exactly one production relation repository exists**, and it is not the adaptive one. */
    @Test
    fun thereIsExactlyOneRelationRepositoryAndItIsNotTheAdaptiveRepository() {
        val repositories = File(appRoot, "data/repository").listFiles()!!.map { it.name }

        // **Sorted**, matching the `.sorted()` on the actual side. An entry in the wrong position fails
        // with a diff that reads like a missing file when both files are present, which is how this
        // gate gets "fixed" by deleting an entry that was never wrong.
        assertEquals(
            "P31 adds exactly one repository, and it is named for the catalogue",
            listOf("ProgressionRelationRepository.kt"),
            repositories.filter { it.contains("ProgressionRelation", ignoreCase = true) }
        )
        assertEquals(
            "so the catalogue and the adaptive history stay two separate repositories — the ladder is " +
                "a definition and the other three tables are a per-revision trail",
            listOf("ProgramAdaptiveRepository.kt", "ProgressionRelationRepository.kt"),
            repositories.filter { it.contains("Progression") || it.contains("Adaptive") }.sorted()
        )
    }

    /** **The adaptive repository did not grow a ladder method** — history and catalogue stay apart. */
    @Test
    fun theAdaptiveRepositoryOwnsNoLadderOperation() {
        val body = code(adaptiveRepository)

        assertFalse(
            "a family ladder is a definition and must not be written or read through the repository " +
                "that owns adaptive state, decisions and adjustments",
            names(body, "relationOf") || names(body, "ProgressionRelation") ||
                names(body, "replaceRelation") || names(body, "storedLadder")
        )
        assertFalse(
            "and it must not reach the catalogue's table",
            body.contains("progression_relation_variant")
        )
    }

    /** **Exactly one production provider**, and it is a projection over the one repository. */
    @Test
    fun thereIsExactlyOneProductionRelationProviderOverTheOneRepository() {
        // Matched on the *superclass position*, not the bare token: four files name the port — its
        // declaration, two call sites taking it as a parameter, and the one implementor — and a
        // substring test cannot tell those apart, so it reports readers as "a second provider".
        val providers = File(appRoot, "domain")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { source ->
                Regex(
                    "(?:class|object)\\s+\\w+(?:\\s*\\([^)]*\\))?\\s*:\\s*" +
                        "ProgressionRelationProvider\\b"
                ).containsMatchIn(code(source))
            }
            .map { it.name }
            .toList()

        assertEquals(
            "exactly one class implements the provider port — a second one is a second answer to " +
                "'where does a ladder come from?'",
            listOf("StoredProgressionRelationProvider.kt"),
            providers
        )
        assertTrue(
            "and it holds the catalogue repository rather than constructing a ladder",
            code(provider).contains("ProgressionRelationRepository")
        )
    }

    /**
     * The provider fabricates nothing: every forbidden answer below would convert *"this family has no
     * declared ladder"* into a rung nobody authored.
     */
    @Test
    fun theProviderContainsNoDefaultNoInferenceAndNoFallback() {
        val body = code(provider)

        for (forbidden in listOf(
            // a hard-coded or generated rung
            "ProgramProgressionRelation(",
            "ProgramProgressionVariant(",
            "defaultLadder",
            "fallbackLadder",
            "DEFAULT_LADDER",
            // a family it made up
            "\"pushups\"",
            "\"squats\"",
            "\"pullups\"",
            "\"plank\"",
            "\"lunges\"",
            "\"rows\"",
            // a heuristic level
            "difficulty",
            "rank",
            "indexOf",
            "position +",
            "level +",
            "levelOf",
            "inferLevel",
            // family *state* read as a hierarchy
            "currentExerciseId",
            "FamilyProgressionState",
            "progressionLevel",
            "familyState",
            // adaptive history read as a ladder
            "AdaptiveDecision",
            "adaptiveDecision",
            "adjustment",
            // the retired generation
            "PilotProgression",
            "ProgressionProfile"
        )) {
            assertFalse(
                "the production provider must not contain '$forbidden': every entry on this list is a " +
                    "way of answering with a ladder nobody declared",
                names(body, forbidden)
            )
        }
    }

    /**
     * The provider reads only the repository — its one collaborator, and no other source a ladder could
     * be invented from.
     *
     * `relationOf` is now `suspend`, so the `runBlocking` bridge is not merely unused but the defect
     * removed. Pinning an exact import set forbids it by name: a re-introduced bridge fails this gate
     * whether or not it is called, and any other added import — a static definitions object, a
     * catalogue, a legacy pilot type — fails it too.
     */
    @Test
    fun theProviderImportsNoLadderSourceOtherThanTheRepository() {
        val imports = provider.readLines()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ") }

        assertEquals(
            "the provider imports the engine's relation type, the port and the one repository — " +
                "and nothing else; P32 made the port suspending, so the coroutine bridge P31 required " +
                "is gone rather than merely unused",
            listOf(
                "com.monkfitness.app.data.repository.ProgressionRelationRepository",
                "com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation",
                "com.monkfitness.app.domain.adaptive.integration.ProgressionRelationProvider"
            ).sorted(),
            imports.sorted()
        )
    }

    /**
     * The behavioural half the import gate cannot make: no `runBlocking` survives in the port, the
     * provider or the function that reads a relation for the integration.
     */
    @Test
    fun theProgressionRelationProviderPathContainsNoBlockingBridge() {
        val path = listOf(
            file("domain/adaptive/integration/ProgressionRelationProvider.kt"),
            file("domain/adaptive/integration/AdaptiveTargetSlot.kt"),
            provider
        )

        val offenders = path
            .filter { it.isFile }
            // Code with comments stripped, not raw text: both files *discuss* `runBlocking` in their
            // KDoc, and a text scan would fail this gate on its own explanation of itself.
            .filter { code(it).contains("runBlocking") }
            .map { it.name }

        assertTrue(
            "no production file on the progression-relation provider path bridges a suspending read " +
                "with `runBlocking` — the port is suspending, so a block here can only be a leftover " +
                "or a new bridge somewhere else: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * The repository validates nothing of its own: every rule a ladder has — duplicate exercise,
     * contiguity, canonical order, non-empty variants — belongs to the domain's constructor, and a
     * second copy here is one that can drift.
     */
    @Test
    fun theRepositoryInventsNoRuleTheDomainAlreadyOwns() {
        val body = code(repository)

        for (forbidden in listOf(
            "require(",
            "check(",
            "sortedWith",
            "sortedBy",
            "distinct()",
            "groupBy",
            "maxOf",
            "minOf",
            "average",
            "sumOf",
            ".sum()"
        )) {
            assertFalse(
                "the repository must not implement '$forbidden': level contiguity, canonical order and " +
                    "the duplicate-exercise rule are the domain constructor's, and recomputing them here " +
                    "would hide a bad write behind a repaired ladder",
                names(body, forbidden)
            )
        }
        assertTrue(
            "and it assembles the domain's own relation type rather than a second ladder model",
            body.contains("ProgramProgressionRelation")
        )
    }

    /** **No heuristic level calculation anywhere in the persistence path.** */
    @Test
    fun noLayerOfThePersistencePathComputesALevelOrADifficulty() {
        for (source in listOf(entity, dao, mapper, repository)) {
            val body = code(source)
            for (forbidden in listOf(
                "difficulty",
                "inferLevel",
                "computeLevel",
                "levelFor",
                "indexOf(",
                "sortedBy { it.level } + 1",
                "maxLevel",
                "levelCount"
            )) {
                assertFalse(
                    "${source.name} must not contain '$forbidden': a level is an authored ordinal inside " +
                        "one family, and computing one is inventing a ladder",
                    names(body, forbidden)
                )
            }
        }
    }

    /**
     * A column or mapper reducing a target list to a scalar would satisfy "the prescription is stored"
     * while losing the load-bearing part.
     */
    @Test
    fun noLayerCollapsesAPerSetPrescriptionToOneNumber() {
        for (source in listOf(entity, mapper, repository)) {
            val body = code(source)
            for (forbidden in listOf("average", "average()", ".mean()", "/ size", "/ perSetTargets.size")) {
                assertFalse(
                    "${source.name} must not contain '$forbidden': [12,10,8,6] and [10,10,10,10] are " +
                        "different prescriptions, and a scalar cannot tell them apart",
                    names(body, forbidden)
                )
            }
            assertFalse(
                "${source.name} must not replace a target list with a single value",
                Regex("perSetTargets\\.(first|last|average|sum|min|max|maxOrNull)").containsMatchIn(body)
            )
        }
    }

    /**
     * No new converter semantics: the per-set column goes through the schema's existing
     * `ProgramTypeConverters` pair, already lossless for exactly this column type.
     */
    @Test
    fun thePerSetColumnUsesTheSchemasExistingConverterAndDeclaresNoNewOne() {
        val body = code(mapper)

        assertTrue(
            "the mapper carries the whole target list, in order",
            body.contains("perSetTargets") && body.contains("RepPrescription(targets)") &&
                body.contains("TimePrescription(targets)")
        )
        val converters = code(file("data/local/ProgramTypeConverters.kt"))

        assertTrue(
            "and the column type is declared as the list the schema's own converter already handles",
            converters.contains("fun toPerSetTargets(targets: List<Int>): String") &&
                converters.contains("fun fromPerSetTargets(value: String): List<Int>")
        )
        // Positively, not by a double negative: count the converters handling a `List<Int>` of targets
        // and name them. An `assertFalse` over a disjunction is satisfied by almost any tree and fails
        // for reasons unrelated to what it claims.
        val targetConverters = listOf("ProgramTypeConverters.kt", "AdaptiveTypeConverters.kt")
            .map { it to file("data/local/$it") }
            .filter { (_, source) ->
                Regex("@TypeConverter\\s+fun\\s+\\w*[Pp]erSetTargets\\w*\\s*\\(")
                    .containsMatchIn(source.readText())
            }
            .map { it.first }

        assertEquals(
            "exactly one converter file handles the per-set target list, and it is the schema's own — " +
                "P31 adds no second converter for a shape that is already lossless",
            listOf("ProgramTypeConverters.kt"),
            targetConverters
        )
    }

    /**
     * Every token below looks like it could stand in for a ladder and is a different kind of wrong:
     * a family's **current** position is one rung, so standing it up as a hierarchy fabricates the
     * others; what was decided once is not what a family may do next; the retired pilot generation is
     * scoped to the legacy program's axis; a generator's difficulty inference is not an authored ladder;
     * and a plan id is the scope leak.
     *
     * A whole-token scan over **comment-stripped** source, so prose *about* the ban cannot trip it and a
     * real use cannot hide inside a comment.
     */
    @Test
    fun theCataloguePathReadsNoLadderAdjacentSource() {
        for (source in listOf(entity, dao, mapper, repository, provider)) {
            val body = code(source)
            for (forbidden in listOf(
                "FamilyProgressionState",
                "currentExerciseId",
                "progressionLevel",
                "familyState",
                "AdaptiveDecision",
                "AdaptiveAdjustment",
                "adjustmentsOf",
                "ProgressionProfile",
                "WorkoutGenerator",
                "difficulty",
                "programId",
                "revisionId"
            )) {
                assertFalse(
                    "${source.name} must not reach '$forbidden': each of these is a plausible stand-in for " +
                        "a ladder that is not one — a current position, a past decision, a retired " +
                        "generation, a generator's inference, or a plan scope",
                    names(body, forbidden)
                )
            }
            assertFalse(
                "${source.name} must not reach the retired Stage-1 pilot's progression vocabulary " +
                    "(`PilotProgressionProfiles` and any name built from it)",
                pilotReference(body)
            )
        }
    }

    /** **No Program or revision dependency anywhere in the catalogue's own path.** */
    @Test
    fun theCataloguePathHasNoProgramOrRevisionDependency() {
        for (source in listOf(entity, dao, mapper, repository, provider)) {
            val imports = source.readLines().map { it.trim() }
                .filter { it.startsWith("import ") }
                .map { it.removePrefix("import ") }

            assertTrue(
                "${source.name} must not import a Program or revision type: a ladder is family-scoped, " +
                    "and a plan identity here would be the scope leak the schema just refused ($imports)",
                imports.none { it.contains("ProgramId") || it.contains("RevisionId") }
            )
            assertFalse(
                "${source.name} must not name a plan column in its body",
                names(code(source), "programId") || names(code(source), "revisionId")
            )
        }
    }

    /** **No UI or ViewModel reaches the catalogue.** */
    @Test
    fun noUiOrViewModelSourceReachesTheProgressionCatalogue() {
        val forbidden = listOf(
            "ProgressionRelationRepository",
            "StoredProgressionRelationProvider",
            "ProgressionRelationVariantEntity",
            "ProgressionRelationVariantDao",
            "ProgressionRelationMappers",
            "progression_relation_variant"
        )

        val offenders = listOf(File(appRoot, "ui"), File(appRoot, "viewmodel"))
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown().filter { it.extension == "kt" }.flatMap { source ->
                    source.readText().lines().map { it.trim() }
                        .filter { line -> line.startsWith("import ") && forbidden.any { line.contains(it) } }
                        .map { "${root.name}/${source.name}: $it" }
                }
            }
            .toList()

        assertTrue("the catalogue is not reachable from the UI or a view model: $offenders", offenders.isEmpty())
    }

    /**
     * Inverted deliberately: asserting the *empty* production state would forbid the very
     * content now delivered. Replaced, not deleted, with strictly stronger claims — the stored provider
     * must be constructed, must be the value the integration receives, the empty source must be gone,
     * and the repository must be reachable. Deleting these lines would leave the wiring unpinned in both
     * directions, which a stage gate must never allow.
     */
    @Test
    fun productionWiresTheStoredProgressionProviderAndNotTheEmptyLadderSource() {
        val body = code(container)

        assertTrue(
            "P32 wires the production stored provider in place of the empty ladder source",
            Regex("""relations\s*=\s*storedProgressionRelationProvider""").containsMatchIn(body)
        )
        assertFalse(
            "and production no longer wires `NoDeclaredProgression` — a second answer to the same " +
                "wiring would let the catalogue be served or ignored by wiring order alone",
            Regex("""relations\s*=\s*NoDeclaredProgression""").containsMatchIn(body)
        )
        assertTrue(
            "and the provider is constructed by the composition root, so it is a real graph node",
            names(body, "StoredProgressionRelationProvider")
        )
        assertTrue(
            "and its one collaborator — the catalogue repository — is wired too, since an unwired " +
                "repository would leave the provider with nothing to read",
            names(body, "ProgressionRelationRepository")
        )
        assertTrue(
            "while the database does declare the DAO the catalogue is persisted through",
            code(file("data/local/AppDatabase.kt"))
                .contains("abstract fun progressionRelationVariantDao(): ProgressionRelationVariantDao")
        )
    }

    /**
     * The scan covered what it claims to cover: without a floor, a renamed package would make every
     * offender list empty and this class green without having looked at anything.
     */
    @Test
    fun everyFileThisClassRulesOverExists() {
        val covered = listOf(entity, dao, mapper, repository, provider, adaptiveRepository, container)

        assertTrue(
            "the gate must cover all seven files it rules over",
            covered.size == 7
        )
        covered.forEach { source ->
            assertTrue("${source.name} is missing, so every rule over it is vacuous", source.isFile)
        }
    }
}