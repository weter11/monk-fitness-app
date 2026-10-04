package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.generated.GenerationCandidate
import com.monkfitness.app.domain.program.generated.GenerationRequest
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P23's boundary, pinned mechanically rather than by convention.
 *
 * P23 is the stage where the pure generated domain and the app's real catalogue are connected, which
 * is exactly where the interesting failure modes live — and every one of them is invisible to a
 * behavioural test:
 *
 *  * a **silent default** where an honest refusal belongs. An exercise with no stated focus membership
 *    joined to the whole focus vocabulary, or dropped from the candidate list, or read as "no
 *    equipment needed", all produce a *plan* — a plausible one — built on a fact nobody stated. §6,
 *    §8 and §33 forbid each of them, and none of them fails any assertion in the planner;
 *  * **inferred membership**: classifying by `ExerciseCategory`, `ExerciseSubCategory` or
 *    `exerciseToFamiliesMap`. Two of those three are vocabularies that look like [Focus] and are not,
 *    so the mistake reads as a reasonable simplification in review and as a fabricated training fact
 *    at runtime;
 *  * **inherited legacy semantics**: the boundary reusing `isAccessibleWith`'s "empty means
 *    unconstrained" rule, which silently offers bar exercises to a user who declared no equipment;
 *  * the **dependency direction inverting**: the generated planner reaching for the catalogue, or this
 *    adapter reaching into the planner's decisions, so generation becomes the legacy engine (§30
 *    step 10's own scope fence);
 *  * the boundary acquiring a **collaborator** — a repository, a clock, a random source — when its
 *    whole claim is that it is a function of two arguments.
 *
 * Each is asserted against the sources, the compiled shape, or both.
 */
class ProductionGenerationBoundaryArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { dir -> if (dir.isDirectory) dir else File("app/$dir") }

    private val boundaryFile = File(mainDir, "domain/usecase/ProductionGenerationBoundary.kt")
    private val factsFile = File(mainDir, "domain/usecase/ExerciseGenerationFacts.kt")
    private val boundarySources = listOf(boundaryFile, factsFile)

    /** The pure package this boundary feeds, and must stay separable from. */
    private val generatedDir = File(mainDir, "domain/program/generated")

    private fun codeLines(file: File): List<String> = code(file.readText())
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun code(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    // ------------------------------------------------------------------ placement

    @Test
    fun theBoundaryLivesInTheApplicationLayerAndNotInThePureGeneratedPackage() {
        assertTrue(boundaryFile.isFile)
        assertTrue(factsFile.isFile)
        assertEquals("usecase", boundaryFile.parentFile!!.name)
        assertFalse(boundaryFile.parentFile!!.canonicalPath.contains(generatedDir.canonicalPath))
        assertEquals("usecase", factsFile.parentFile!!.name)
        assertFalse(factsFile.parentFile!!.canonicalPath.contains(generatedDir.canonicalPath))
        // And the pure package gained no file by this stage: the census that pins it is Stage 10's.
        assertEquals(
            listOf(
                "ExerciseSelector.kt",
                "FocusPlanner.kt",
                "GeneratedPlan.kt",
                "GeneratedPlanner.kt",
                "GenerationPolicy.kt",
                "GenerationRequest.kt",
                "PlanReconciler.kt",
                "ProgramGeneratedEditor.kt"
            ),
            generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
                .orEmpty().map { it.name }.sorted()
        )
    }

    // ------------------------------------------------------------------ no silent default

    @Test
    fun noFocusIsEverSubstitutedForAMissingClassification() {
        // The four substitutions a boundary reaches for when the source says nothing. `Focus.entries`
        // is "trains everything"; `FocusPlan.Balanced.eligibleFocuses` is the same claim wearing a
        // different name; `emptySet()` is refused by `GenerationCandidate` itself; and an `else` arm in
        // the `when` is the same substitution with one more hop.
        val forbidden = listOf(
            "Focus.entries", "Focus.values()", "FocusPlan.Balanced", "focuses = emptySet()",
            "focuses = Focus", "?: setOf(", "?: FocusPlan", "else -> setOf(", "else -> Focus"
        )
        val offenders = boundarySources.flatMap { file -> codeLines(file).mapNotNull { line ->
            forbidden.firstOrNull { line.contains(it) }?.let { "${file.name}: $line" }
        } }

        assertTrue(
            "an exercise whose focus membership the source does not state is refused, never admitted " +
                "with an invented one (§6, §8): $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theUnclassifiedExercisesAreCarriedAsAValueAndNotDropped() {
        val code = code(boundaryFile.readText())

        assertTrue(
            "the gap is a field of the result, so a caller can report it: $code",
            code.contains("val unclassifiedExerciseIds: List<String>")
        )
        assertEquals(
            "the unclassified branch appends and the classified branch builds a candidate — and " +
                "neither of them is an `else`",
            1,
            occurrences(code, "null -> unclassified += exercise.id")
        )
        // `filterValues` inside the duplicate-id guard is not this: it names what *failed*, it does
        // not remove anything. What must not appear is a filter that drops candidates, or any
        // re-ordering, before the caller sees the two halves.
        val dropsOrReorders = listOf("filter {", "filterNot", ".sorted(", ".distinct()", ".reversed()")
        val offenders = codeLines(boundaryFile).filter { line ->
            dropsOrReorders.any { token -> line.contains(token) }
        }
        assertTrue(
            "no filtering or re-ordering hides or reshapes either half before the caller sees it: " +
                "$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun anEmptyCatalogueIsRefusedRatherThanPlannedAsNothing() {
        val code = code(boundaryFile.readText())

        assertTrue(
            "generationRequest must refuse a catalogue that classified nothing",
            code.contains("require(catalogue.candidates.isNotEmpty())")
        )
        assertTrue(
            "and the refusal must name the gap, so the caller is not told \"your program has no " +
                "exercises\"",
            code.contains("unclassified")
        )
    }

    // ------------------------------------------------------------------ no inferred membership

    @Test
    fun theBoundaryInfersNoFocusFromTheCataloguesOwnGroupings() {
        // The whole point of this stage's gap record: the catalogue holds three vocabularies that are
        // not the focus vocabulary, two of which are named exactly like focus members.
        val forbidden = listOf(
            "ExerciseCategory", "ExerciseSubCategory", "exerciseToFamiliesMap",
            "ExerciseCategoryFilter", "postureFocusAreas", "stretchFocusAreas",
            "flexibilityFocusAreas", "flexibilitySpecificFocusAreas"
        )
        val offenders = boundarySources.flatMap { file -> codeLines(file).mapNotNull { line ->
            forbidden.firstOrNull { token ->
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                    .containsMatchIn(line)
            }?.let { "${file.name}: $line" }
        } }

        assertTrue(
            "mapping a category, a body region or a training style onto PUSH/PULL/LEGS/CORE would " +
                "invent a training fact the catalogue does not hold: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theCataloguesRealGroupingsAreNotFocusVocabularyAndTheTestReadsThemToProveIt() {
        // The absence claim above is only falsifiable if something states what the catalogue *does*
        // hold. Read it off the real catalogue and compare with the focus vocabulary.
        val exercises = WorkoutGenerator().getExerciseLibrary()

        val categoryNames = exercises.map { it.category.name }.toSet()
        val subCategoryNames = exercises.map { it.subCategory.name }.toSet()
        val styleNames = com.monkfitness.app.data.model.exerciseToFamiliesMap.values
            .flatten().map { it.name }.toSet()
        val focusNames = Focus.entries.map { it.name }.toSet()

        assertTrue("the real catalogue is non-empty", exercises.isNotEmpty())
        assertEquals(
            "the catalogue's categories are its own, not the focus vocabulary",
            setOf("STRENGTH", "MOBILITY", "STRETCHING", "POSTURE"),
            categoryNames
        )
        assertEquals(
            "its body regions are its own too",
            setOf("SHOULDERS", "SPINE", "HIPS", "LEGS", "CORE", "FULL_BODY", "HYPERLORDOSIS"),
            subCategoryNames
        )
        // The vocabulary that *looks* like focus membership and is not — the standing example of why
        // the inference is refused rather than merely avoided. MOBILITY and POSTURE are categories
        // that are also focus names; LEGS and CORE are body regions that are also focus names. So a
        // classifier built on "this is a mobility category" or "this trains the legs" would look
        // reasonable in review and be a fabricated training fact at runtime.
        assertTrue(
            "the catalogue's categories really do include two names the focus vocabulary also uses: " +
                "$categoryNames",
            setOf("MOBILITY", "POSTURE").all { it in categoryNames && it in focusNames }
        )
        assertTrue(
            "and its body regions really do include two more: $subCategoryNames",
            setOf("LEGS", "CORE").all { it in subCategoryNames && it in focusNames }
        )
        assertEquals(
            "its training styles are a third vocabulary of their own, overlapping the focus " +
                "vocabulary in exactly one member and sharing none of its other six",
            setOf("MOBILITY"),
            styleNames.intersect(focusNames)
        )
        assertTrue(
            "so a style name is not a focus name either: $styleNames",
            styleNames.contains("CALISTHENICS") && styleNames.contains("LOWER_BACK")
        )
        // The decisive half: the overlap is not a mapping. Four of the seven focuses are names the
        // catalogue happens to use, and three are not — and the ones that overlap are ambiguous,
        // because MOBILITY as a category covers stretching, mobility *and* posture work, not the
        // single focus a generated plan would allocate to it.
        assertEquals(
            "the focuses the catalogue names somewhere",
            setOf("MOBILITY", "POSTURE", "LEGS", "CORE"),
            focusNames.intersect(categoryNames).union(focusNames.intersect(subCategoryNames))
        )
        assertEquals(
            "and the focuses it never names at all, which no lookup could recover",
            setOf("PUSH", "PULL", "CONDITIONING"),
            focusNames.subtract(categoryNames).subtract(subCategoryNames).subtract(styleNames)
        )
        assertEquals(
            "the focus vocabulary is the seven §8 names, and none of them is a category",
            listOf("PUSH", "PULL", "LEGS", "CORE", "MOBILITY", "POSTURE", "CONDITIONING"),
            focusNames.toList()
        )
    }

    // ------------------------------------------------------------------ the direction of the dependency

    @Test
    fun theGeneratedDomainReachesNeitherTheCatalogueNorThisBoundary() {
        val offenders = generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .flatMap { file -> codeLines(file).mapNotNull { line ->
                listOf("ProductionGenerationBoundary", "ExerciseGenerationFacts", "WorkoutGenerator")
                    .firstOrNull { token ->
                        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                            .containsMatchIn(line)
                    }?.let { "${file.name}: $line" }
            } }

        assertTrue(
            "the dependency is one-way: the adapter knows the catalogue, the planner knows neither " +
                "(§30 step 10's scope fence). Found: $offenders",
            offenders.isEmpty()
        )
        // And the two halves of that claim in the planner's own terms: it must not name the legacy
        // engine, and it must not import this application layer.
        val imports = generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().flatMap { file -> codeLines(file) }.filter { it.startsWith("import ") }

        assertTrue(
            "no generated source imports the application layer: $imports",
            imports.none { it.startsWith("import com.monkfitness.app.domain.usecase") }
        )
        assertTrue(
            "and no generated source imports the data model either: $imports",
            imports.none { it.startsWith("import com.monkfitness.app.data.") }
        )
    }

    @Test
    fun theBoundaryNamesTheLegacyGeneratorAndOnlyTheBoundaryDoes() {
        // The allowance is a *location*, not a list: the legacy catalogue may be read here and
        // nowhere else, so this is asserted as a closed list of readers rather than as an exemption.
        val readers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> code(file.readText()).contains("getExerciseLibrary") }
            .map { it.relativeTo(mainDir).path.replace('\\', '/') }
            .toList()

        assertTrue(
            "the sweep must actually cover the production tree — a renamed package makes the covered " +
                "set empty, the offender list empty too, and the claim green without having looked at " +
                "anything (found ${readers.size})",
            // Raised from 4 by P30 with the reader list below, in the same pass, because the two numbers
            // are the same claim: this many readers are *expected*, so fewer means the sweep is broken.
            readers.size >= 5
        )
        // P30 **extended** this list rather than relaxing it, and the extension is the point. The new
        // reader is `CatalogExerciseFamilyClassification`, and it is allowed here for one stated reason:
        // §9's exercise→family membership is a fact the catalogue *already states per exercise*, so the
        // only honest way to answer it is to read that field — and the alternative would have been a
        // second exercise list, which is a far larger sin than a fifth reader of the first one.
        //
        // It is not a second *owner*: this reader holds a projection of `familyId` and nothing else (no
        // `Exercise`, no name, no equipment, no animation id), which is the same discipline
        // `ProgramExerciseLibrary` holds its ids under, and `ProgramExerciseFamilyClassificationTest`
        // asserts it. A sixth reader would still be a second owner, and this gate would say so.
        //
        // The entry is placed where `sorted()` puts it — `Catalog…` before `Production…` — because these
        // assertions compare against `readers.sorted()`, so an entry appended at the end fails with a diff
        // that reads like a missing file when the file is present.
        assertEquals(
            "the shipped catalogue is read by the legacy engine, the settings read-back, the " +
                "§5 membership adapter, MainViewModel's option list, the P23 generation boundary — " +
                "and, new in P30, by the §9 exercise→family classification. A seventh reader is a " +
                "second owner of the catalogue.",
            listOf(
                "domain/usecase/CatalogExerciseFamilyClassification.kt",
                "domain/usecase/ProductionGenerationBoundary.kt",
                "domain/usecase/ProgramExerciseLibrary.kt",
                "domain/usecase/WorkoutGenerator.kt",
                "viewmodel/MainViewModel.kt"
            ),
            readers.sorted()
        )
    }

    @Test
    fun theBoundaryInvokesNoPlannerDecisionAndOwnsNoBusinessRule() {
        // The planner's decisions — allocation, selection, the equipment constraint, reconciliation —
        // belong to the generated domain. An adapter that restates one of them is a second copy that
        // drifts.
        val forbidden = listOf(
            "FocusPlanner", "ExerciseSelector", "GeneratedPlanner", "PlanReconciler",
            "ProgramGeneratedEditor", "isUsable", "usableCandidatesFor", "plannableFocuses",
            "reasonFocusIsNotPlannable", "slotCount", "cycleWeeks", "sessionsPerWeek"
        )
        val offenders = boundarySources.flatMap { file -> codeLines(file).mapNotNull { line ->
            forbidden.firstOrNull { token ->
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                    .containsMatchIn(line)
            }?.let { "${file.name}: $line" }
        } }

        assertTrue(
            "the boundary converts values and decides nothing: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ no collaborators, no ambient input

    @Test
    fun theBoundaryHoldsNoStateAndAcquiresNoCollaborator() {
        // Two of the three are singletons holding nothing at all; the third is a value, and a value's
        // fields are its facts rather than collaborators.
        listOf(
            ProductionGenerationBoundary::class.java,
            ExerciseGenerationFacts::class.java
        ).forEach { type ->
            assertEquals(
                "${type.simpleName} holds nothing and takes nothing: a pass is a function of its " +
                    "arguments, which is what makes the mapping deterministic",
                emptyList<String>(),
                type.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                    .map { it.name }
            )
            assertEquals("${type.simpleName} is constructed with nothing", 0, type.declaredConstructors.single().parameterCount)
        }
        assertEquals(
            "and the catalogue value's own fields are its two facts — the classified half and the gap",
            listOf("candidates", "unclassifiedExerciseIds"),
            ProductionGenerationBoundary.ProductionGenerationCatalogue::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
                .sorted()
        )

        assertEquals(
            "the boundary's collaborators are exactly the catalogue and the stated facts — no " +
                "repository, no clock, no id generator, no legacy engine",
            emptyList<String>(),
            ProductionGenerationBoundary::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
        )
        assertEquals(
            "and the focus source is the single seam this stage leaves open",
            1,
            ExerciseGenerationFacts.GenerationFocusSource::class.java.declaredMethods.count {
                it.name == "focusesOf"
            }
        )
    }

    @Test
    fun noSourceOfTheBoundaryReadsAnAmbientTimeOrRandomnessOrReachesPersistence() {
        val forbidden = listOf(
            "LocalDate.now", "Instant.now", "System.currentTimeMillis", "System.nanoTime",
            "Random", "shuffled", "shuffle(", "Math.random", "UUID", "hashCode()",
            "import android", "import androidx", "import kotlinx",
            "import com.monkfitness.app.ui", "import com.monkfitness.app.viewmodel",
            "import com.monkfitness.app.R", "Dao", "Entity", "Room", "Repository",
            "Clock", "IdGenerator", "suspend fun", "runBlocking"
        )
        val offenders = boundarySources.flatMap { file -> codeLines(file).mapNotNull { line ->
            forbidden.firstOrNull { line.contains(it) }?.let { "${file.name}: $line" }
        } }

        assertTrue(
            "the mapping is a pure function of two arguments: no clock, no randomness, no storage, " +
                "no platform type and no Android resource. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theBoundaryImportsTheAppModelAndTheGeneratedDomainAndNothingElse() {
        // The imports are the boundary's entire dependency statement, so the claim is exact rather
        // than approximate: two vocabularies, and the legacy engine that owns the catalogue.
        val imports = boundarySources.flatMap { file -> codeLines(file) }.filter { it.startsWith("import ") }
        val prefixes = listOf(
            "import com.monkfitness.app.data.model.",
            "import com.monkfitness.app.domain.program.",
            "import com.monkfitness.app.domain.prescription.",
            "import com.monkfitness.app.domain.usecase."
        )
        val offenders = imports.filterNot { line -> prefixes.any { line.startsWith(it) } }

        assertTrue("expected the boundary to import its inputs: $imports", imports.isNotEmpty())
        assertTrue("the boundary may reach only the app's own values and the pure domain: $offenders", offenders.isEmpty())
        assertTrue(
            "and it must import the generated domain's own request value — the line the two halves meet",
            imports.contains("import com.monkfitness.app.domain.program.generated.GenerationRequest")
        )
    }

    @Test
    fun theCompiledBoundaryValuesOnlyDependOnJvmAndAppTypes() {
        val types = listOf(
            ProductionGenerationBoundary.ProductionGenerationCatalogue::class.java,
            ExerciseGenerationFacts.GenerationFocusSource::class.java,
            ExerciseGenerationFacts::class.java,
            ProductionGenerationBoundary::class.java
        )
        val offenders = types.flatMap { type ->
            (type.declaredMethods.toList() + type.declaredConstructors.toList()).flatMap { member ->
                val parameterTypes = when (member) {
                    is java.lang.reflect.Method -> member.parameterTypes.toList() + listOf(member.returnType)
                    is java.lang.reflect.Constructor<*> -> member.parameterTypes.toList()
                    else -> emptyList()
                }
                parameterTypes.filterNot { it.isPrimitive }.mapNotNull { referenced ->
                    val name = if (referenced.isArray) referenced.componentType.name else referenced.name
                    val allowed = name.startsWith("kotlin.") || name.startsWith("java.") ||
                        name.startsWith("com.monkfitness.app.") || name.startsWith("androidx.")
                    if (allowed) null else "${type.simpleName}.${member.name} references $name"
                }
            }
        }

        assertTrue("no platform or runtime type reaches the boundary's shape: $offenders", offenders.isEmpty())
    }

    // ------------------------------------------------------------------ the wiring P24 added

    @Test
    fun theBoundaryIsWiredExactlyOnceThroughTheGenerationService() {
        // P23 landed the boundary unwired and pinned that as a cardinality of zero, naming P24 as the
        // stage that would invert it. P24 is that stage, so the pin is inverted here rather than
        // deleted: the boundary now has **exactly** the consumers the production flow needs, and a
        // second reader appearing later still fails.
        val consumers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file -> file.relativeTo(mainDir).path.replace('\\', '/') in OWN_FILES }
            .filter { file ->
                code(file.readText()).contains("ProductionGenerationBoundary") ||
                    code(file.readText()).contains("ExerciseGenerationFacts")
            }
            .map { it.relativeTo(mainDir).path.replace('\\', '/') }
            .toList()

        assertEquals(
            "exactly one production file reaches the boundary: the application service that " +
                "assembles a request from it. The composition root builds that service without " +
                "naming the boundary itself (it wires the catalogue port instead), and a second " +
                "caller would be a second generation path.",
            listOf("domain/usecase/ProgramGenerationService.kt"),
            consumers.sorted()
        )

        val container = File(mainDir, "di/AppContainer.kt").readText()
        assertTrue(
            "the composition root wires the generation service explicitly (§26): a graph node " +
                "exists because something needs it",
            container.contains("val programGenerationService: ProgramGenerationService =")
        )
        assertTrue(
            "and it wires the production catalogue and the explicit classification into it",
            container.contains("catalogue = SHIPPED_EXERCISE_CATALOGUE") &&
                container.contains("focusSource = ProductionFocusClassification")
        )
        assertTrue(
            "and the draft identities come from the composition root's own id generator (§26)",
            container.contains("ids = DraftIdSource { idGenerator.newId() }")
        )

        // The legacy half of the P23 claim stays whole: the generator is untouched, and it remains the
        // app's own exercise source — which is what makes the real-catalogue test meaningful.
        assertTrue(
            "the legacy generator is untouched and remains the app's own exercise source",
            File(mainDir, "domain/usecase/WorkoutGenerator.kt").readText()
                .contains("fun getExerciseLibrary(")
        )
        // The controller reaches the service and nothing below it: it must not name the boundary, the
        // classification or the planner, or the orchestration would have a second home in the UI.
        val controller = code(File(mainDir, "ui/programs/ProgramsController.kt").readText())
        listOf(
            "ProductionGenerationBoundary", "ProductionFocusClassification", "GeneratedPlanner",
            "PlanReconciler", "GenerationRequest", "GenerationCandidate", "WorkoutGenerator"
        ).forEach { token ->
            assertFalse(
                "the controller reaches the application service only, never '$token' directly: the " +
                    "orchestration has one home and it is not the UI layer",
                controller.contains(token)
            )
        }
    }

    @Test
    fun theGeneratedDomainStillDeclaresTheCandidateShapeThisBoundaryComposes() {
        // The two halves meet on `GenerationCandidate`'s own contract, so that contract is pinned from
        // the boundary's side too: a candidate states non-empty focuses, and its exercise id and
        // family are its metadata's.
        assertEquals(
            "a candidate is exactly the three facts the boundary supplies",
            listOf("dimension", "focuses", "metadata"),
            GenerationCandidate::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .sorted()
        )
        assertEquals(
            "and a request is the pure input §30 step 10 describes",
            listOf(
                "availableEquipment", "candidates", "duration", "focus", "policy", "preferences",
                "schedule"
            ),
            GenerationRequest::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
                .sorted()
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The files this stage owns, excluded from the consumer sweep below because a file that *declares*
     * a type obviously names it. P24 added the classification beside them, which is why the sweep
     * excludes three files and not two.
     */
    private val OWN_FILES = listOf(
        "domain/usecase/ProductionGenerationBoundary.kt",
        "domain/usecase/ExerciseGenerationFacts.kt",
        "domain/usecase/ProductionFocusClassification.kt"
    )

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }
}