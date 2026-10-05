package com.monkfitness.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P31's architecture: the persisted progression catalogue is an **app-owned definition**, and
 * everything that could turn it into an inferred ladder is absent.
 *
 * Each rule here corresponds to a way this stage could have gone wrong while still compiling and still
 * passing its own behavioural tests — a second ladder type beside the domain's, a serialized blob, a
 * Program-scoped key, a heuristic level, a read of family *state* as if it were a hierarchy. The gates
 * are source scans with comments stripped, so prose *about* a forbidden thing cannot trip them and a
 * real use of it cannot hide in a comment.
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
     * `RegexOption.DOT_MATCHES_ALL` is **load-bearing** here, and the reason is worth recording: the
     * constructor spans several lines, so a `.` without DOTALL matches only within one line, the regex
     * finds nothing, and a `!!` on the result turns the whole assertion into a `NullPointerException`.
     * That failure names neither the expected value nor the field list, so it reads as a production
     * defect when it is a gate that never ran. The helper asserts its own match, so the same mistake
     * next time names the entity instead of throwing.
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
     * The trailing lookahead is what makes it whole-token — and that is also its one trap. A ban written
     * on a *prefix* of the real symbol can never match it: `PilotProgression` does not match inside
     * `PilotProgressionProfiles`, because the very next character is a word character. A gate written
     * that way reports "the retired pilot is not consulted" while being unable to see the one name it is
     * meant to catch. So the pilot is banned by [pilotReference] instead, which matches a leading
     * `PilotProgression` in any casing and any suffix — the shape the retired generation actually has.
     */
    private fun names(body: String, token: String): Boolean =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(body)

    /**
     * Any reference to the retired Stage-1 pilot's progression vocabulary.
     *
     * Matched as a leading `PilotProgression` rather than as a whole token, because the real type is
     * longer than any prefix worth banning — a whole-token form of this rule would be green on a tree
     * that names `PilotProgressionProfiles` directly.
     */
    private fun pilotReference(body: String): Boolean =
        Regex("(?<![A-Za-z0-9_])[Pp]ilotProgression").containsMatchIn(body)

    // ================================================================ the shape of the persisted row

    /**
     * **The persisted row is exactly the five facts of a ladder rung** — and nothing that belongs to a
     * plan, a history or a policy.
     *
     * Asserted against the entity's declared fields, because a column added later would be a claim the
     * stage document does not make and the schema does not model.
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
     * **One row per progression variant — the ladder is normalized, not a serialized blob.**
     *
     * The two halves of the storage are deliberately *different kinds of thing* and this gate keeps
     * them apart, because conflating them would forbid the wrong one:
     *
     * ```text
     * the ladder          = a SET of rows, one per declared variant
     * the prescription    = an ordered list of per-set targets, in one column,
     *                      via the schema's existing lossless converter
     * ```
     *
     * So what is asserted is structural, not textual: every rung is its own row carrying its own
     * family, level, exercise and targets — which is what makes ordering, contiguity and per-rung
     * identity answerable by the database rather than by parsing a string. A column holding a *whole
     * ladder* as text is what would break that, and only that is refused.
     */
    @Test
    fun theCatalogueIsNormalizedRowsAndNotASerializedBlob() {
        val body = code(entity)
        val fields = declaredFields()

        // Positive: the ladder is a set of rows, one row per variant.
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

        // And the per-set targets stay a LIST type, so the schema's existing lossless converter is
        // what carries them. This is the half that must NOT be forbidden: storing an ordered target
        // list in one TEXT column is the schema's established, already-proven representation.
        assertTrue(
            "the per-set targets are declared as the whole ordered list, which the schema's existing " +
                "converter already stores losslessly — a single Int here would be the defect",
            fields.any { it.trim() == "val perSetTargets: List<Int>" }
        )

        // Negative: nothing on this row holds a *ladder* as one serialized value.
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

    // ================================================================ one repository, one provider

    /** **Exactly one production relation repository exists**, and it is not the adaptive one. */
    @Test
    fun thereIsExactlyOneRelationRepositoryAndItIsNotTheAdaptiveRepository() {
        val repositories = File(appRoot, "data/repository").listFiles()!!.map { it.name }

        // **Sorted**, because the comparison is against a `.sorted()` on the actual side — and the
        // expected list is written in the order `sorted()` produces rather than the order the two
        // concepts are introduced in. An entry in the wrong position fails with a diff that reads like
        // a missing file when both files are present, which is the classic way this gate gets "fixed"
        // by deleting an entry that was never wrong.
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
        // Matched on the *superclass position* — `class X ... : ProgressionRelationProvider` — and
        // not on the bare token. Four files name the port: the port's own declaration, the two call
        // sites that receive it as a constructor parameter, and the one class that implements it. A
        // substring test cannot tell those apart, so it reported the integration and the target-slot
        // port as "a second provider" — which they are not; they are the port's own readers.
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

    // ================================================================ what the provider must not do

    /**
     * **The provider fabricates nothing.** Every forbidden answer below would convert *"this family has
     * no declared ladder"* into a rung nobody authored.
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
     * **The provider reads only the repository.** Its one collaborator, and the absence of every other
     * source a ladder could plausibly be invented from.
     */
    /**
     * **Inverted by P32, deliberately.** P31 asserted this exact import set, including
     * `kotlinx.coroutines.runBlocking`, because the port was synchronous and the provider had to bridge
     * the suspending repository read with a blocking call.
     *
     * P32 made `ProgressionRelationProvider.relationOf` `suspend`, so the bridge is not merely unused —
     * it is the defect the stage removed. The assertion is therefore **replaced, not deleted**: the
     * provider still imports exactly the relation type, the port and the one repository, and the
     * blocking bridge is now *forbidden* by name. Pinning the removal as an exact import set means a
     * re-introduced `runBlocking` fails this gate whether or not it is called, and any *other* added
     * import — a static definitions object, a catalogue, a legacy pilot type — fails it too.
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
     * **No blocking bridge anywhere in the progression-relation provider path.**
     *
     * The import gate above pins the provider's own imports; this one is the behavioural claim the
     * import list cannot make on its own — that no `runBlocking` survives in the port, the provider or
     * the function that reads a relation for the integration. P31 recorded the bridge as a gap it did
     * not own; P32 owns it, and this is the gate that says so.
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
            // Scanned on CODE with the comments stripped, not on raw text: both files *discuss*
            // `runBlocking` in their KDoc — the port because it records what it replaced, the provider
            // because it records the gap it closed — and a text scan would fail this gate on its own
            // explanation of itself.
            .filter { code(it).contains("runBlocking") }
            .map { it.name }

        assertTrue(
            "no production file on the progression-relation provider path bridges a suspending read " +
                "with `runBlocking` — the port is suspending, so a block here can only be a leftover " +
                "or a new bridge somewhere else: $offenders",
            offenders.isEmpty()
        )
    }

    // ================================================================ the storage boundary adds no rules

    /**
     * **The repository validates nothing of its own.** Every rule a ladder has — duplicate exercise,
     * contiguity, canonical order, a non-empty variant list — belongs to the domain's constructor, and a
     * second copy here would be a second copy that can drift.
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
     * **No heuristic prescription.** A column or a mapper that reduced a target list to a scalar would
     * satisfy "the prescription is stored" while losing the load-bearing part of §10.
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
     * **No new converter semantics.** The per-set column goes through the schema's existing
     * `ProgramTypeConverters` pair, which was already lossless for exactly this column type.
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
        // Positively, not by a double negative: the invariant is "the per-set list is converted by
        // exactly the existing pair", so it is measured by counting the converters that handle a
        // `List<Int>` of targets and naming them. An `assertFalse` over a disjunction is satisfied by
        // almost any tree and fails for reasons unrelated to what it claims.
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
     * **The catalogue path reads no ladder-adjacent source at all.**
     *
     * Every token below names something that *looks* like it could stand in for a ladder, and every one
     * of them is a different kind of wrong:
     *
     *  * `FamilyProgressionState` / `currentExerciseId` / `progressionLevel` — a family's **current**
     *    position is ONE rung, so standing it up as a hierarchy fabricates all the others;
     *  * `AdaptiveDecision` / adjustment — what was decided once is not what a family may do next;
     *  * `PilotProgression` — the retired Stage-1 generation, scoped to the legacy program's axis;
     *  * `WorkoutGenerator` / `difficulty` — a generator's difficulty inference is not an authored ladder;
     *  * `programId` / `revisionId` — the scope leak, kept here as well as in the entity rule so the
     *    repository and the provider are covered by it too.
     *
     * The check is a whole-token scan over **comment-stripped** source, so prose *about* the ban cannot
     * trip it and a real use of one of these cannot hide inside a comment.
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

    // ================================================================ scope and dependencies

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
     * **Inverted by P32 — the stage's central deliverable.**
     *
     * P31 asserted the *empty* production state: `NoDeclaredProgression` wired, the provider and the
     * repository not wired at all, because P31 authored no ladder content. P32 authors four ladders,
     * seeds them and serves them, so every one of those assertions is now false **by design** and would
     * forbid the very thing this stage was asked to deliver.
     *
     * It is therefore **replaced, not deleted**, and replaced with strictly stronger claims: the
     * stored provider must be constructed, it must be the value the integration receives, the empty
     * source must be gone from the production wiring, and the repository must be reachable. A test
     * that merely deleted these lines would leave the wiring unpinned in both directions, which is the
     * one outcome a stage gate must never allow.
     *
     * The DAO assertion below is **unchanged**: P31 created the table and P32 does not touch it.
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

    // ================================================================ the sweep really swept

    /**
     * **The scan covered what it claims to cover.** Without a floor, a renamed package would make every
     * offender list empty and this whole class green without having looked at anything.
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