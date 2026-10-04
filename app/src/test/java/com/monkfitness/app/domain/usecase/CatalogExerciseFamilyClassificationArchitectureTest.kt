package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P30's architecture, pinned mechanically.
 *
 * P30's whole claim is that §9's family membership is **closed by a read of a fact the app already
 * states**, not by a new classification source. That claim is invisible to a behavioural test: a
 * classification that inferred families from `ExerciseCategory` answers the shipped catalogue correctly
 * for every entry, because the catalogue was *authored* with categories and families in agreement. Only a
 * source-level assertion can tell a stored fact from a rule that reproduces it, which is why every test
 * here reads source or compiled shape and none of them asserts an answer.
 *
 * The eight rules below are the ones a future edit could break while leaving every other suite green.
 */
class CatalogExerciseFamilyClassificationArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { dir -> if (dir.isDirectory) dir else File("app/$dir") }

    private val sourceFile = File(mainDir, "domain/usecase/CatalogExerciseFamilyClassification.kt")
    private val containerFile = File(mainDir, "di/AppContainer.kt")
    private val integrationFile = File(mainDir, "domain/usecase/ProgramAdaptiveIntegration.kt")
    private val catalogueFile = File(mainDir, "domain/usecase/WorkoutGenerator.kt")
    private val portFile = File(mainDir, "domain/adaptive/integration/ProgressionRelationProvider.kt")

    private val generatedDir = File(mainDir, "domain/program/generated")

    private fun code(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun codeLines(file: File): List<String> = code(file.readText())
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Whole-token presence, matching the boundary convention the other gates in this package use. */
    private fun names(text: String, token: String): Boolean =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])").containsMatchIn(text)

    /** Every production `.kt` under the app, comment-stripped — the scan root for the "exactly one" rules. */
    private fun productionSources(): List<File> = mainDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    // ------------------------------------------------------------------ 1. exactly one production source

    /**
     * **Exactly one** production implementation of the classification, and it is this file.
     *
     * The failure this forbids is a second opinion: a second class implementing the port, or a second
     * place that reads a catalogue's `familyId` on the adaptive path. Both would let the app disagree
     * with itself about which family an exercise is in, and neither would fail a behavioural test while
     * the two happened to agree.
     */
    @Test
    fun thereIsExactlyOneProductionFamilyClassificationSource() {
        assertTrue("the classification source exists", sourceFile.isFile)
        val type = Class.forName(
            "com.monkfitness.app.domain.usecase.CatalogExerciseFamilyClassification"
        )
        assertTrue(
            "it implements the domain's port",
            ExerciseFamilyClassification::class.java.isAssignableFrom(type)
        )

        // No other production class *implements* the port. Naming the port as a parameter type is not
        // implementing it — `adaptiveTargetElementOf` and `ProgramAdaptiveIntegration` legitimately
        // take one — so what is forbidden is a second *implementation*. `NoExerciseFamilyClassification`
        // is the domain's own *absence* value, declared in the port's own file, and is excluded so that
        // supplying a real classification does not read as deleting the honest empty case.
        //
        // The pattern is anchored on the declaration keyword because a supertype is introduced by a
        // colon that follows the *type's own name* — `class Catalogue : ExerciseFamilyClassification`.
        // A bare `\s*:` alternative is not enough: it also matches
        // `classification: ExerciseFamilyClassification`, which is a **parameter** declaring what a
        // consumer accepts, and would condemn the pure domain for taking the port it defines.
        val implementsThePort = Regex(
            """^\s*(?:internal\s+|private\s+|public\s+|abstract\s+|open\s+|final\s+)*""" +
                """(?:class|object|interface)\s+\w+[^:]*:\s*ExerciseFamilyClassification\b"""
        )
        val others = productionSources()
            .filterNot { it == portFile }
            .filterNot { it == sourceFile }
            .flatMap { file ->
                codeLines(file).filter { line -> implementsThePort.containsMatchIn(line) }
                    .map { line -> "${file.name}: $line" }
            }

        assertTrue(
            "no production file outside the port's own declarations may implement a second " +
                "ExerciseFamilyClassification — a second source is a second opinion about families. " +
                "Found: $others",
            others.isEmpty()
        )
    }

    // ------------------------------------------------------------------ 2. reads the catalogue fact only

    /**
     * The source reads the **catalogue's own stored field** and nothing else about an exercise.
     *
     * The projection is asserted positively — `WorkoutGenerator().getExerciseLibrary()` and
     * `id to familyId` — so the rule is not merely "some forbidden token is absent" but "the one
     * intended read is present", which is what distinguishes a stored fact from an empty file that
     * happens to avoid a forbidden import.
     */
    @Test
    fun theSourceReadsTheCatalogueFactAndNothingElse() {
        val body = code(sourceFile.readText())

        assertTrue(
            "the classification reads the shipped catalogue, and names the accessor",
            names(body, "getExerciseLibrary")
        )
        assertTrue(
            "and projects the stored family field of each entry onto its id",
            names(body, "exercise.id") && names(body, "exercise.familyId")
        )

        // The stored field, read as a field — not a name, not a lookup, not a derived expression.
        assertTrue(
            "the family is read as Exercise.familyId",
            Regex("""exercise\.familyId""").containsMatchIn(body)
        )
        assertFalse(
            "the classification must not build a map of its own from a literal table — a literal " +
                "family string is a new vocabulary",
            Regex("""familyId\s*=\s*""").containsMatchIn(body)
        )
    }

    // ------------------------------------------------------------------ 3. no category / subcategory / training-style inference

    /**
     * **No inference from any classification-shaped field.** This is the rule P23 refused for Focus and
     * P30 refuses again for family: `ExerciseCategory`, `ExerciseSubCategory` and
     * `exerciseToFamiliesMap` are three vocabularies that *look* like family membership and are not —
     * the catalogue was authored with them broadly in agreement, so a source that inferred from them
     * would pass every behavioural test in this repository and be wrong on the first exercise edited.
     */
    @Test
    fun noFamilyIsInferredFromCategorySubcategoryOrTrainingStyle() {
        val body = code(sourceFile.readText())
        listOf(
            "category" to "ExerciseCategory is not a family",
            "subCategory" to "ExerciseSubCategory is not a family",
            "exerciseToFamiliesMap" to "the training-style map is a filter vocabulary, not a family",
            "trainingDomain" to "TrainingDomain is the coarse channel, not a family",
            "bodyRegion" to "BodyRegion is the region axis, not a family",
            "animationId" to "the animation is how the exercise moves, not what family it is in",
            "nameEn" to "a display name is not a family",
            "nameRu" to "a display name is not a family",
            "substringBefore" to "parsing an id into a family is inference, not a stored fact",
            "substringAfter" to "parsing an id into a family is inference, not a stored fact",
            "removePrefix" to "parsing an id into a family is inference, not a stored fact",
            "contains" to "matching an id by text is inference, not a stored fact",
            "startsWith" to "matching an id by prefix is inference, not a stored fact"
        ).forEach { (token, why) ->
            assertFalse(
                "the classification must not read '$token': $why",
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                    .containsMatchIn(body)
            )
        }
    }

    // ------------------------------------------------------------------ 4. no persistence dependency

    /**
     * **No persistence, no clock, no id source** — and the strongest form of the claim is the compiled
     * one: the class declares **no constructor parameter at all** and holds no field but the projection.
     * A source that took a repository could not satisfy that, so this does not enumerate the ways to
     * persist; it removes the possibility.
     */
    @Test
    fun theSourceHasNoPersistenceAndNoCollaboratorOfAnyKind() {
        val type = Class.forName(
            "com.monkfitness.app.domain.usecase.CatalogExerciseFamilyClassification"
        )
        val constructor = type.declaredConstructors.first { !it.isSynthetic }
        assertEquals(
            "the classification is a compile-time projection of the catalogue, so it takes no " +
                "repository, DAO, clock or id source (found ${constructor.parameterCount} parameters)",
            0,
            constructor.parameterCount
        )
        assertEquals(
            "the classification declares no collaborator field",
            listOf("familyByExerciseId"),
            type.declaredFields
                // `$stable` is the Compose compiler's stability marker on a stable class: it is not a
                // collaborator and it is not written by this class.
                .filterNot { field -> field.name == "\$stable" }
                .map { field -> field.name }
        )
        assertTrue(
            "and holds no mutable state",
            type.declaredFields.none { field -> !Modifier.isFinal(field.modifiers) }
        )

        val body = code(sourceFile.readText())
        listOf(
            "Room" to "no persistence",
            "Dao" to "no persistence",
            "AppDatabase" to "no persistence",
            "Repository" to "no persistence",
            "suspend" to "a read of a compile-time list is not a suspending operation",
            "Clock" to "no clock",
            "IdGenerator" to "no id source",
            "Random" to "no randomness — the answer is a stored field",
            "flow" to "no observable stream — the answer is a stored field",
            "Flow" to "no observable stream — the answer is a stored field"
        ).forEach { (token, why) ->
            assertFalse(
                "the classification must not reach '$token': $why",
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                    .containsMatchIn(body)
            )
        }
    }

    // ------------------------------------------------------------------ 5. no generation-domain dependency

    /**
     * **The dependency direction does not invert.** The app boundary implements the pure domain's port;
     * the pure generated domain reaches neither the catalogue nor this source. Both directions are
     * asserted, because either alone is satisfiable by a file that imports the other way round.
     */
    @Test
    fun theSourceDependsOnThePureDomainAndTheGeneratedDomainDependsOnNeither() {
        val imports = codeLines(sourceFile).filter { it.startsWith("import ") }
        assertTrue(
            "the source implements the domain's port, so it imports exactly one adaptive-integration type",
            imports.any { it.endsWith(".ExerciseFamilyClassification") }
        )
        assertTrue(
            "the source imports no generated-domain type: the pure package never names the app boundary",
            imports.none { it.contains("domain.program.generated") }
        )

        val offenders = generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .flatMap { file -> codeLines(file).mapNotNull { line ->
                listOf("CatalogExerciseFamilyClassification", "WorkoutGenerator")
                    .firstOrNull { token ->
                        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                            .containsMatchIn(line)
                    }?.let { "${file.name}: $line" }
            } }
        assertTrue(
            "no generated source reaches the catalogue or this classification: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ 6. AppContainer is the only construction site

    /**
     * **The composition root is the only construction site**, and no ViewModel or UI source builds it.
     *
     * Counted over every production file, so a second `AppContainer` node — or a service building its
     * own classification so a caller can pass a different one per call — fails here rather than in
     * review.
     */
    @Test
    fun appContainerIsTheOnlyConstructionSite() {
        val constructionSites = productionSources().flatMap { file ->
            // The path relative to the app root, so the message names *where* in the tree the site is
            // rather than only which file — two files with the same name would otherwise be
            // indistinguishable in the failure.
            val where_ = file.relativeTo(mainDir).path
            codeLines(file).mapNotNull { line ->
                // The `import` line and the KDoc mention both name the type; only a construction call
                // constructs it. Comments are already stripped by `codeLines`, so this is exact.
                if (Regex("""CatalogExerciseFamilyClassification\s*\(""").containsMatchIn(line) &&
                    !line.startsWith("import ")
                ) "$where_: $line" else null
            }
        }
        assertEquals(
            "AppContainer is the single construction site of the production classification; found " +
                "$constructionSites",
            1,
            constructionSites.size
        )
        assertTrue(
            "and that one site is the composition root: ${constructionSites.single()}",
            constructionSites.single().startsWith("di/AppContainer.kt:")
        )

        val container = code(containerFile.readText())
        assertTrue(
            "the container declares it as a graph node",
            names(container, "CatalogExerciseFamilyClassification")
        )
        assertTrue(
            "and hands that node to the adaptive integration",
            Regex("""classification\s*=\s*catalogExerciseFamilyClassification""")
                .containsMatchIn(container)
        )

        // No ViewModel, screen or controller may reach the source.
        val uiDir = File(mainDir, "ui")
        val uiOffenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> codeLines(file).mapNotNull { line ->
                if (names(line, "CatalogExerciseFamilyClassification") && !line.startsWith("import ")) {
                    "${file.name}: $line"
                } else null
            } }
            .toList()
        assertTrue(
            "no UI or ViewModel source may name the production classification — the port is reached " +
                "through the integration, not by a screen: $uiOffenders",
            uiOffenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ 7. no new family vocabulary

    /**
     * **No new family vocabulary is introduced.** The families the classification can answer are exactly
     * the families the catalogue's own `families` list declares, and exactly the families its exercise
     * entries state — measured against the production catalogue, not a hand-written list.
     *
     * A source that answered `"pushups"` for an unknown id, or that carried its own family constants,
     * would add a vocabulary the rest of the app does not have, and the two halves would drift.
     */
    @Test
    fun noNewFamilyVocabularyIsIntroduced() {
        val classification = CatalogExerciseFamilyClassification()
        val catalogue = WorkoutGenerator().getExerciseLibrary()

        // The catalogue's own declared family list, read off the production source.
        val declared = Regex("""ExerciseFamily\("([^"]+)"""")
            .findAll(code(catalogueFile.readText()))
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("the catalogue declares families", declared.isNotEmpty())

        val answeredByEntries = catalogue.map { exercise -> exercise.familyId }.toSet()
        assertEquals(
            "every family a catalogue entry states is a declared family, so the entry list and the " +
                "family list are one vocabulary",
            declared,
            answeredByEntries
        )

        // The classification's whole answer space IS that vocabulary: every family it can name is one
        // the catalogue declares, and it introduces no family of its own.
        assertEquals(
            "the classification introduces no family beyond the catalogue's declared vocabulary",
            declared,
            catalogue.map { exercise -> classification.familyOf(exercise.id) }.toSet()
        )

        // And the source declares no family literal at all, which is what would add a vocabulary.
        val body = code(sourceFile.readText())
        assertFalse(
            "the source declares no family of its own",
            Regex("""=\s*"[a-z_]+"""")
                .findAll(body)
                .any { it.value.contains("pushups") || it.value.contains("squats") || it.value.contains("plank") }
        )
    }

    // ------------------------------------------------------------------ 8. no legacy Stage-1 adaptive source

    /**
     // **The Stage-1 adaptive source is not consulted** — neither the pilot's progression profiles nor
          * any legacy adaptive repository or adapter. §30 step 11 forbids this generation reaching for them,
          * and P30 adds a *new* consumer of the family concept, which is exactly where a "helpful" reuse
          * would appear: the pilot already has a family vocabulary, and borrowing it would have been faster
          * and wrong.
          *
          * The scan is restricted to **this stage's own sources** — the classification and the container
          * wiring it is constructed in. A repo-wide sweep for `LoadProfile` is wrong rather than strict:
          * `LoadProfile` is the *current* engine's own load value (§12), used by `ProgramAdaptiveEngine`
          * itself, so banning it everywhere would condemn the target generation rather than the legacy one.
          * What is forbidden is the **pilot's** vocabulary reaching the classification path.
          */
         @Test
         fun noLegacyStageOneAdaptiveSourceIsConsulted() {
             // Matched **case-insensitively on a prefix**, because the concrete spelling a later session
             // would use is not knowable in advance: the deleted pilot file was `PilotProgressionProfiles.kt`,
             // a reconstructed stand-in is as likely to be `PILOT_PROGRESSION_PROFILES` or `PilotProfile`, and a
             // case-sensitive whole-token rule catches only the spelling its author happened to choose. The
             // claim is about the *vocabulary*, so the pattern has to be about the vocabulary too — while
             // still anchored on a word boundary so `pilot` does not fire on an unrelated substring.
             val pilotVocabulary = Regex(
                 """(?<![A-Za-z0-9_])(?:pilot\w*|\w*pilot\w*|legacyAdaptive|AdaptiveSessionDecisionRecorder)""",
                 RegexOption.IGNORE_CASE
             )
             val offenders = listOf(sourceFile, containerFile).flatMap { file ->
                 codeLines(file).mapNotNull { line ->
                     if (pilotVocabulary.containsMatchIn(line)) "${file.name}: $line" else null
                 }
             }
             assertTrue(
                 "the classification source must not reach the Stage-1 pilot's own progression vocabulary: " +
                     "$offenders",
                 offenders.isEmpty()
             )
         }

    // ------------------------------------------------------------------ the integration's own wiring

    /**
     * The integration reaches classification **before** it can refuse for want of a relation, and it is
     * still wired with a real classification. Asserted on the wiring, because the ordering itself lives
     * in `adaptiveTargetElementOf` and this pins that the production wiring feeds it a classification.
     *
     * **The second half of this gate inverted in P32, and that is the whole point of it being here.**
     * P30 asserted production wired the *empty* ladder source, because P30 authored no ladder. P32
     * authors four and serves them from the persisted catalogue, so asserting `NoDeclaredProgression`
     * here would now forbid the stage's central deliverable. It is therefore **replaced, not deleted**:
     * the classification assertion is untouched, and the ladder assertion is inverted to require the
     * stored provider and to forbid the empty one — a strictly stronger claim than P30's, because the
     * empty source can no longer pass.
     */
    @Test
    fun theIntegrationIsWiredWithAClassificationAndWithTheStoredProgressionProvider() {
        val container = code(containerFile.readText())
        val wiring = container.substringAfter("val programAdaptiveIntegration")
            .substringBefore("val maintenanceRepository")

        assertTrue(
            "production hands the integration the catalogue classification",
            Regex("""classification\s*=\s*catalogExerciseFamilyClassification""")
                .containsMatchIn(wiring)
        )
        assertTrue(
            "and P32 hands it the stored progression provider, over the persisted catalogue",
            Regex("""relations\s*=\s*storedProgressionRelationProvider""").containsMatchIn(wiring)
        )
        assertFalse(
            "and production no longer wires the empty ladder source at all — the four authored " +
                "ladders are what it serves now, so `NoDeclaredProgression` must not survive here as a " +
                "second answer to the same wiring",
            Regex("""relations\s*=\s*NoDeclaredProgression""").containsMatchIn(wiring)
        )
        assertFalse(
            "and the empty classification value is no longer what production wires",
            Regex("""classification\s*=\s*NoExerciseFamilyClassification""")
                .containsMatchIn(wiring)
        )
    }

    /**
     * The integration's own source did not gain a family rule: it asks the port and it still has no
     * ladder of its own. A classification is a *fact*, not a policy, so no branch that decides a
     * family may appear in the use case.
     */
    @Test
    fun theIntegrationAsksThePortAndDecidesNoFamilyOfItsOwn() {
        val body = code(integrationFile.readText())
        val classifications = codeLines(integrationFile).filter { line ->
            names(line, "classification.familyOf")
        }
        assertTrue(
            "the integration reads the family through the port ($classifications)",
            classifications.isNotEmpty()
        )
        assertFalse(
            "the integration must not hold a fallback family of its own — a `?:` beside a familyOf " +
                "read is an invented default",
            Regex("""familyOf\([^)]*\)\s*\?:""").containsMatchIn(body)
        )
        assertFalse(
            "and must not name a family literally — the families are the app's to state, not the " +
                "integration's to invent",
            Regex("""=\s*"(pushups|squats|pullups|plank|lunges|rows)" """).containsMatchIn(body)
        )
    }
}