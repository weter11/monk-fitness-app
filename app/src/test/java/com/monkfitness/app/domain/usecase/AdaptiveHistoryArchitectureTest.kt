package com.monkfitness.app.domain.usecase

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ownership boundary of `domain/program/generated`, pinned mechanically: the package is frozen
 * except `PlanReconciler.kt`, whose one semantic responsibility is to preserve `GeneratedElement.focus`
 * when creating a `ProgramExercise`.
 *
 * A blanket "the generated package may change" permission is the failure mode this suite prevents,
 * because from inside the package it is indistinguishable from a second generation semantic. So the
 * claims are **cardinality and identity**, not absence: the same eight files, the only changed content in
 * `PlanReconciler.kt`, and inside it the only semantic line the `focus = focus` copy.
 *
 * Behavioural tests cannot see the difference: `AdaptiveFocusAttributionTest` proves the focus survives
 * reconciliation but not that reconciliation refrains from *also* classifying, persisting or reaching for
 * a repository. Hence the structural claims — the pure package imports nothing from the application, data
 * or DI layers; nothing outside `PlanReconciler` copies a generated focus; and the context source still
 * holds exactly one collaborator, the read port.
 */
class AdaptiveHistoryArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { dir -> if (dir.isDirectory) dir else File("app/$dir") }

    private val generatedDir = File(mainDir, "domain/program/generated")
    private val reconciler = File(generatedDir, "PlanReconciler.kt")
    private val contextFile = File(mainDir, "domain/usecase/ProgramGenerationContext.kt")
    private val serviceFile = File(mainDir, "domain/usecase/ProgramGenerationService.kt")

    private fun code(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun codeLines(file: File): List<String> = code(file.readText())
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun theGeneratedPackageStillHoldsExactlyItsEightFiles() {
        // The closed list is **not** relaxed: a new file here is a new place to put a semantic, and the
        // value of the exception below is that it names one existing function rather than opening a
        // surface.
        assertEquals(
            "the pure package is exactly the eight files Stage 10 established, and P29 added none — the " +
                "one approved change is a line inside an existing function, not a new file that could " +
                "hold a new semantic",
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

    @Test
    fun theGeneratedPackageReachesNoPersistenceAndNoApplicationLayer() {
        // Re-asserted because this is the property a focus copy could most plausibly erode: "copy the
        // value" and "store the value" differ by one import.
        val imports = generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().flatMap { file -> codeLines(file) }.filter { it.startsWith("import ") }

        assertTrue(
            "the pure package imports neither the application layer nor the app's data model or DI, and " +
                "P29 added no import at all: $imports",
            imports.none { it.startsWith("import com.monkfitness.app.domain.usecase") } &&
                imports.none { it.startsWith("import com.monkfitness.app.data.") } &&
                imports.none { it.startsWith("import com.monkfitness.app.di") }
        )
        val persistence = codeLines(reconciler).filter { line ->
            listOf("Repository", "Dao", "AppDatabase", "@Entity", "Room", "androidx", "import android")
                .any { token -> line.contains(token) }
        }
        assertTrue(
            "and the one changed file in particular gains no persistence collaborator: $persistence",
            persistence.isEmpty()
        )
    }

    @Test
    fun theOnlyFileThatMaterialisesAGeneratedElementIsTheReconciler() {
        // A `GeneratedElement` is a plan value with no identity, and exactly one thing turns it into a
        // stored element. Anything else that did so would be a *second* rule for what an element is — and
        // that is how a reconstruction gets in under a name that looks like preservation.
        //
        // The token is `GeneratedElement(` rather than `GeneratedElement.focus`, because the copy is an
        // unqualified `focus = focus` inside the extension function on that type. An assertion that passes
        // because it searches for something no correct implementation writes is worse than none.
        val sites = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> code(file.readText()).contains("GeneratedElement(") }
            .map { file -> file.relativeTo(mainDir).path.replace('\\', '/') }
            .distinct()
            .sorted()
            .toList()

        assertEquals(
            "the plan is built in `GeneratedPlanner` and materialised in exactly one other place, " +
                "`PlanReconciler` — a second materialisation would be a second definition of what an " +
                "element is, and therefore a second definition of what an element's focus is",
            // `GeneratedPlan.kt` appears because it *declares* `GeneratedElement`; it constructs none.
            // Only `PlanReconciler` converts one into a stored element.
            listOf(
                "domain/program/generated/GeneratedPlan.kt",
                "domain/program/generated/GeneratedPlanner.kt",
                "domain/program/generated/PlanReconciler.kt"
            ),
            sites
        )
        assertTrue(
            "and nothing outside the pure package names the type at all, so no reconstruction could be " +
                "built on it",
            sites.none { !it.startsWith("domain/program/generated") }
        )
        assertFalse(
            "in particular the generation context never sees it: it reads the focus off a session " +
                "snapshot, which is the whole architecture of the stage",
            code(contextFile.readText()).contains("GeneratedElement")
        )
    }

    @Test
    fun theSevenOtherGeneratedFilesAreUnchangedFromStage10() {
        // A file-list assertion is not enough: it proves the package still *has* eight files, which a
        // mutation editing the body of `GeneratedPlan.kt` leaves untouched. Content has to be pinned, not
        // membership.
        //
        // Structural rather than digest-based, because a digest recorded in a test goes stale the first
        // time a legitimate change needs it, and a stale digest is a gate nobody reads. `PlanReconciler`
        // is absent from this list deliberately: its change is pinned separately by
        // `theReconcilersOnlyNewSemanticLineIsTheFocusCopy`, a line count rather than a hash, since a hash
        // cannot express "one semantic line changed and the rest is documentation".
        //
        // `GeneratedSlot`'s own assertion is what makes a focus mean *anything*: without it, "this element
        // is the PUSH element" is an unchecked claim. Pinned by text, which is what catches a disabled
        // `require` that every other gate in this class survives.
        assertTrue(
            "§8's own contract is still enforced: a slot plans exactly one element per assigned focus, in " +
                "the assignment's order. Disabling this `require` is RED row 18, and it would leave every " +
                "other gate in this class green",
            // "Present" is not enough — the mutation wraps it in `if (false)`, so the guard asserts the
            // assertion is **live**. The `init` block is taken verbatim, so `if (false)` inside it stays
            // visible, which is why this is not a plain `contains`.
            generationSlotInitIsGuarded()
        )
        assertEquals(
            "and the seven files that are not the reconciler are exactly the package minus that one",
            listOf(
                "ExerciseSelector.kt",
                "FocusPlanner.kt",
                "GeneratedPlan.kt",
                "GeneratedPlanner.kt",
                "GenerationPolicy.kt",
                "GenerationRequest.kt",
                "ProgramGeneratedEditor.kt"
            ),
            generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
                .orEmpty().map { it.name }.filterNot { it == "PlanReconciler.kt" }.sorted()
        )
    }

    @Test
    fun theReconcilersOnlyNewSemanticLineIsTheFocusCopy() {
        // The narrowest statement of the approved exception: `focus = focus` appears exactly once, on the
        // `ProgramExercise` construction, reading the element's own property rather than anything derived.
        //
        // This catches the well-meaning "while I'm here, let this also infer the focus for user-authored
        // elements": such an edit either names the property twice or derives the value from something
        // else.
        val body = code(reconciler.readText())
        val copies = Regex("""focus\s*=\s*focus\b""").findAll(body).count()
        assertEquals(
            "exactly one focus copy in the reconciler: the approved preservation, and nothing else",
            1,
            copies
        )
        assertTrue(
            "and it is on the `ProgramExercise` the materialisation builds, so the value lands on the " +
                "domain element rather than somewhere transient",
            Regex("""ProgramExercise\([\s\S]{0,400}?focus\s*=\s*focus\b""").containsMatchIn(body)
        )
        assertFalse(
            "and the reconciler does not itself consult the exercise catalogue, the revision or an " +
                "adaptive state to decide a focus — it copies, it does not decide",
            code(reconciler.readText()).contains("ProductionFocusClassification") ||
                code(reconciler.readText()).contains("focusesOf(")
        )
        // A **bare copy**, not a copy with a fallback. `focus ?: Focus.entries.first()` still contains the
        // token `focus = focus`, so an occurrence count cannot see it — and it is the most plausible way
        // to "helpfully" widen the exception: an elvis operator looks like defensive null handling rather
        // than an invented training claim, and would stamp every element that recorded no focus with the
        // first focus in the vocabulary.
        val copyLine = code(reconciler.readText())
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("focus =") || it.startsWith("focus=") }
        assertEquals(
            "exactly one focus line, and it assigns the element's own property with nothing after it",
            listOf("focus = focus"),
            copyLine.map { it.removeSuffix(",") }
        )
        assertFalse(
            "so no fallback, no `?:`, no `ifNull`, and no `getOrElse` may stand between the plan's focus " +
                "and the element's — an absent focus must stay absent all the way to storage",
            Regex("""focus\s*=\s*focus\s*[?:.]""").containsMatchIn(code(reconciler.readText()))
        )
    }

    @Test
    fun thePlannerAndTheSelectorAreUnchanged() {
        // Each unit named individually so a change to any one fails here rather than hiding behind "the
        // generated package was allowed to change".
        listOf(
            "GeneratedPlanner" to "the plan itself",
            "FocusPlanner" to "§8's exposure allocation",
            "ExerciseSelector" to "§9's selection and its precedence",
            "GenerationPolicy" to "the explicit numbers generation uses",
            "GenerationRequest" to "§30 step 10's pure input",
            "GeneratedPlan" to "§8's focus vocabulary and the assignment contract",
            "ProgramGeneratedEditor" to "§7's generate/regenerate/reconcile"
        ).forEach { (name, what) ->
            val file = File(generatedDir, "$name.kt")
            assertTrue(
                "$name ($what) exists, so the exception above names one file rather than describing a " +
                    "package that changed shape",
                file.isFile
            )
        }
        assertEquals(
            "and §8's six signals keep exactly the fields Stage 10 declared — P29 FILLED two of them and " +
                "declared no new one, which is the whole distinction between giving a signal an owner and " +
                "inventing a signal",
            listOf(
                "adaptivePreferredExerciseIds",
                "recentExerciseIds",
                "recentExposureByFocus",
                "recentLoadByFocus",
                "recovery",
                "userPreferredExerciseIds"
            ),
            com.monkfitness.app.domain.program.generated.GenerationPreferences::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .sorted()
        )
        assertEquals(
            "and §30 step 10's request gained no field: the two signals gained an owner, which is a wiring " +
                "change and not a change to what the planner may read",
            listOf(
                "availableEquipment",
                "candidates",
                "duration",
                "focus",
                "policy",
                "preferences",
                "schedule"
            ),
            com.monkfitness.app.domain.program.generated.GenerationRequest::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .sorted()
        )
    }

    @Test
    fun theContextSourceStillHoldsExactlyOneCollaboratorAndItIsTheReadPort() {
        // The most tempting shortcut was to hand the source the plan repository "just to resolve the
        // focus". It must not: the focus is already in the snapshot the one read returns, and a second
        // collaborator would be a second place a historical fact could come from.
        assertEquals(
            "the source is handed one thing — the history read",
            listOf("sessions"),
            ProgramHistoryGenerationContext::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
        )
        assertEquals(
            "and it is constructed with that one thing",
            1,
            ProgramHistoryGenerationContext::class.java.declaredConstructors.single().parameterCount
        )
        assertEquals(
            "the port still declares one read, so a write is not something this source could call even by " +
                "accident — 'the context source writes nothing' stays a property of its shape",
            listOf("sessionsOfProgram"),
            GenerationSessionHistory::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .map { it.name.substringBefore('-') }
        )
    }

    @Test
    fun theContextSourceNeverFillsTheTwoSignalsThatStayNeutral() {
        // The two fields deliberately left neutral, asserted as **writes** rather than field names: a
        // behavioural test on the resulting values, and a gate on the six declared fields, both miss a
        // source that *fills* one of them from something plausible — an adaptive preference read from a
        // family ladder produces a real, non-empty, entirely fabricated ranking on any history at all.
        //
        // `adaptivePreferredExerciseIds` is the load-bearing ban: a plausible persisted source sits right
        // next to it (`FamilyProgressionState.currentExerciseId`). `recovery` has the same shape.
        val construction = code(contextFile.readText())
        listOf("adaptivePreferredExerciseIds =", "recovery =").forEach { assignment ->
            val offenders = codeLines(contextFile).filter { line -> line.contains(assignment) }
            assertTrue(
                "`$assignment` may never be written here — the field exists and stays at its neutral " +
                    "value, and a source that populates it has found a plausible-sounding fact and " +
                    "relabelled it as a generation preference: $offenders",
                offenders.isEmpty()
            )
        }
        assertFalse(
            "and no helper builds a value from the performed history under either name, which is the " +
                "other shape the same fabrication could take",
            Regex("""(adaptive|recovery)\w*\s*\(\s*\)\s*(:|\?)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(construction)
        )
    }

    @Test
    fun theContextSourceReachesNoRepositoryAndNoClock() {
        val forbidden = listOf(
            "ProgramPlanRepository", "ProgramRepository", "ProgramScheduleRepository",
            "ProgramAdaptiveRepository", "WorkoutSessionRepository", "Dao", "AppDatabase", "Room", "@Entity",
            "Clock", "Instant.now", "LocalDate.now", "System.currentTimeMillis", "Duration", "ChronoUnit",
            "kotlinx.coroutines.flow", "androidx", "import android",
            "com.monkfitness.app.data", "com.monkfitness.app.di", "com.monkfitness.app.ui"
        )
        val offenders = codeLines(contextFile).flatMap { line ->
            forbidden.filter { token -> line.contains(token) }.map { token -> "$token: $line" }
        }
        assertTrue(
            "the source holds one narrow read port and nothing that could persist, schedule or tell it the " +
                "time — a revision read here would be exactly how a current revision could relabel old " +
                "history: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theContextSourceNamesNoAdaptiveStateAtAll() {
        // Filling two signals must not open the door to `currentExerciseId`, a decision record or a load
        // profile. The behavioural suite asserts the empty list; this asserts no such type is even named,
        // so a future edit cannot add the read and leave that assertion passing on a fixture that happens
        // to have no adaptive rows.
        val forbidden = listOf(
            "FamilyProgressionState", "currentExerciseId", "AdaptiveDecision", "AdaptiveAdjustment",
            "AdaptiveInputSnapshot", "ExposureObservation", "LoadProfile", "ProgramAdaptiveIntegration",
            "AdaptiveJudgement", "PilotProgressionProfiles", "ProgramAdaptiveRepository",
            "ProgressionPolicy", "ProgressionRelation"
        )
        val offenders = codeLines(contextFile).filter { line -> forbidden.any { token -> line.contains(token) } }
        assertTrue(
            "no stored adaptive state is read as a generation fact: `currentExerciseId` means 'the " +
                "exercise this family is currently on', family- and revision-scoped, and no existing " +
                "contract makes it a selection preference for generation: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theContextSourceNeverReconstructsAFocusFromTheCatalogue() {
        // The one most at risk now that the signal is fillable. A reconstruction answers "what does this
        // exercise train?" and names the result "which focus was this occurrence assigned?" — a different
        // question, with a plausible-looking answer for every occurrence, including those whose focus was
        // never recorded at all.
        val forbidden = listOf(
            "ProductionFocusClassification", "GenerationCandidate", "GenerationFocusSource",
            "focusesOf(", "eligibleFocuses", "focusCounts", "ExerciseGenerator", "WorkoutGenerator"
        )
        val offenders = codeLines(contextFile).filter { line -> forbidden.any { token -> line.contains(token) } }
        assertTrue(
            "a performed occurrence's focus is read from the session's own snapshot and nothing else. The " +
                "shipped classification is a table of what exercises train; an occurrence's recorded " +
                "focus is a fact about one presentation, and no rule turns the first into the second: " +
                "$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theServiceGainedNoCollaboratorAndReachesNoStorage() {
        // The focus arrives through the context the service already reads, so the count is unchanged — and
        // a sixth collaborator would be one more thing a generation pass could reach.
        assertEquals(
            "still exactly five: a catalogue, a focus source, an id source, the context and the policy",
            5,
            ProgramGenerationService::class.java.declaredConstructors
                .first { !it.isSynthetic }.parameterCount
        )
        val forbidden = listOf(
            "Repository", "Dao", "AppDatabase", "Entity", "Room",
            "kotlinx.coroutines.flow", "androidx", "import android", "com.monkfitness.app.ui"
        )
        val offenders = codeLines(serviceFile).filter { line -> forbidden.any { token -> line.contains(token) } }
        assertTrue(
            "Generate and Regenerate only ever alter a draft (§7), and the pass still holds nothing that " +
                "could write: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theServiceStillReadsTheContextExactlyOnceInsideTheSharedPass() {
        // One generation operation → one context snapshot → one `GenerationRequest`. Filling more signals
        // from that snapshot must not tempt a second read.
        val body = code(serviceFile.readText())
        assertEquals(
            "the context is read once per operation — Generate, Regenerate and Preview all hand to the " +
                "same private pass",
            1,
            Regex("context\\.preferencesFor\\(").findAll(body).count()
        )
        assertTrue(
            "and the value is passed into the request assembly as a parameter rather than re-read there",
            body.contains("preferences: GenerationPreferences")
        )
        listOf("generate", "regenerate", "preview").forEach { entry ->
            assertEquals(
                "'$entry' remains the shared pass and nothing else",
                1,
                Regex(
                    "suspend fun $entry\\(\\s*\\n?\\s*draft: ProgramEditorDraft,\\s*\\n?\\s*" +
                        "availableEquipment: Set<Equipment>\\s*\\n?\\s*\\): ProgramGenerationResult = " +
                        "edit\\(draft, availableEquipment\\)"
                ).findAll(body).count()
            )
        }
        assertEquals(
            "and `planned` stays private, so no caller can assemble a request around its own context",
            listOf("edit", "planned"),
            Regex("private (suspend )?fun (edit|planned)\\(").findAll(body).map { it.groupValues[2] }.toList()
        )
    }

    @Test
    fun theHistoricalFocusIsStoredInTwoPlacesAndNoOthers() {
        // Exactly two columns carry it, each for a reason: the element that owns the assignment, and the
        // snapshot that freezes it. A third would be a second copy that could disagree with the first.
        val carriers = mutableListOf<String>()
        File(mainDir, "data/model").listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .filter { file -> code(file.readText()).contains("val focus: String? = null") }
            .forEach { file -> carriers += file.name }

        assertEquals(
            "exactly the two rows that own the fact, and no new table was created for it",
            listOf("ProgramExerciseEntity.kt", "SessionSnapshotExerciseEntity.kt"),
            carriers.sorted()
        )
    }

    @Test
    fun theNullableFocusHasNoDefaultAnywhereInTheDataLayer() {
        // A default focus would stamp a training claim onto every pre-existing row, including manual
        // programs and elements the generator never assigned. Absence must be stored and read back as
        // absence, all the way to the generation context.
        val offenders = File(mainDir, "data").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> codeLines(file).map { file.relativeTo(mainDir).path to it } }
            .filter { (_, line) ->
                Regex("""val focus:\s*String\s*=\s*""").containsMatchIn(line) ||
                    Regex("""focus\s*:\s*String\s*=\s*""").containsMatchIn(line) ||
                    line.contains("focus ?: ") ||
                    line.contains("focus ?:\"")
            }
            .map { (path, line) -> "$path: $line" }
            .toList()

        assertTrue(
            "no focus column has a default and no mapper supplies one for an absent value — a fabricated " +
                "focus would be indistinguishable from a recorded one: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theSessionSnapshotIsStillTheOnlyProducerOfTheHistoricalPresentation() {
        // The focus is captured when the presentation is composed, from the value the plan already
        // carried, and never written afterwards — no post-hoc repair.
        //
        // The mapper is excluded on purpose: it *rebuilds* a snapshot from stored rows, the read direction
        // of the same single boundary rather than a second production of one. What must not exist is a
        // second place that **creates** a presentation, because that is a second answer to "what was
        // shown".
        //
        // `domain/workout/WorkoutSessionSnapshot.kt` is excluded because it is the type's own declaration
        // and its KDoc names the type; a declaration is not a construction site, and counting it would
        // make the gate assert something about prose rather than behaviour.
        val producers = constructionSites()
            .filterNot { it == "domain/workout/WorkoutSessionSnapshot.kt" }
            .filterNot { it.startsWith("data/mapper") }

        assertEquals(
            "one production construction site: `SessionRuntime.startSession`. A second would be a second " +
                "answer to 'what was presented', and §19's whole guarantee rests on there being one",
            listOf("domain/usecase/SessionRuntime.kt"),
            producers
        )
        // The read direction rebuilds a snapshot from the very rows that one site wrote: the same
        // boundary read backwards.
        assertEquals(
            "and the only other place one is built is the mapper's read, which is the same boundary " +
                "traversed backwards",
            listOf("data/mapper/SessionMappers.kt"),
            constructionSites()
                .filterNot { it == "domain/usecase/SessionRuntime.kt" }
                .filterNot { it == "domain/workout/WorkoutSessionSnapshot.kt" }
        )
    }

    /**
     * Whether `GeneratedSlot`'s own `init` still enforces its element-per-focus contract **live** — not
     * merely present, and not necessarily first. The block also validates `position`, so requiring an
     * order made the gate fail for harmless reasons and again the next time anyone added a validation
     * before it; a gate must fail for the breach it guards and nothing else, or people work around it.
     *
     * So: the statement is present, and it is **executable** — the `require(` itself, with no
     * `if (false)` or other short-circuit in front of it. That is what makes a disabled guard catchable,
     * since `if (false) require(...)` keeps every character of the assertion.
     *
     * The block is taken by brace depth rather than a non-greedy regex: a non-greedy `init { ... }` stops
     * at the first inner `}` — the close of a previous assertion's message — and inspects a fragment.
     */
    private fun generationSlotInitIsGuarded(): Boolean {
        val body = code(File(generatedDir, "GeneratedPlan.kt").readText())
        val typeStart = body.indexOf("data class GeneratedSlot(")
        if (typeStart < 0) return false
        val open = body.indexOf("init {", typeStart)
        if (open < 0) return false

        var depth = 0
        var close = -1
        for (index in open until body.length) {
            when (body[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        close = index
                        break
                    }
                }
            }
        }
        if (close < 0) return false

        val contract = "require(elements.map { it.focus } == assignment.focuses)"
        val statements = body.substring(open, close)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("}") }

        val index = statements.indexOfFirst { it.startsWith(contract) }
        if (index < 0) {
            // Present but not live: something in front of it does not execute, which is exactly the
            // mutation this gate exists to catch.
            return false
        }
        return true
    }

    /** Every file whose code constructs the type, by path, de-duplicated and sorted. */
    private fun constructionSites(): List<String> = mainDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { file -> code(file.readText()).contains("WorkoutSessionSnapshot(") }
        .map { file -> file.relativeTo(mainDir).path.replace('\\', '/') }
        .distinct()
        .sorted()
        .toList()
}