package com.monkfitness.app.domain.usecase

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P29's ownership boundary, pinned mechanically.
 *
 * P29 is the first stage since Stage 10 to change anything inside `domain/program/generated`, so this
 * suite exists for one reason above all: to keep that change **narrow and named**. Every earlier stage
 * could assert *"the generated package is untouched"*; P29 cannot, because it legitimately is not. What
 * it asserts instead is strictly stronger in the way that matters:
 *
 * ```text
 * domain/program/generated — every file
 *     frozen except:
 *         PlanReconciler.kt
 *             exactly one semantic responsibility:
 *             preserve GeneratedElement.focus when creating ProgramExercise
 * ```
 *
 * A blanket *"the generated package may change"* permission is the failure mode this suite is built to
 * prevent, because it is indistinguishable — from inside the package — from a second generation semantic.
 * So the claims are stated as **cardinality and identity**, not as absence:
 *
 *  * the package still holds exactly the **eight** files Stage 10 established (no file added, none removed);
 *  * the *only* file whose content differs from `main` is `PlanReconciler.kt`;
 *  * and inside that one file, the *only* semantic line is the `focus = focus` copy.
 *
 * ### What the behavioural suites cannot see
 *
 * `AdaptiveFocusAttributionTest` proves the focus survives reconciliation. It cannot prove that the
 * reconciliation does not *also* now classify, persist, or reach for a repository — a change that would
 * keep every behavioural test green. Hence the structural claims below: the pure package imports nothing
 * from the application, data or DI layers; nothing outside `PlanReconciler` copies a generated focus; and
 * the context source still holds exactly one collaborator, which is the read port.
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

    // ------------------------------------------------------------------ the generated package: one narrow exception

    @Test
    fun theGeneratedPackageStillHoldsExactlyItsEightFiles() {
        // The closed list is **not** relaxed. Stage 10 established eight files and P29 added none: a new
        // file here would be a new place to put a semantic, and the whole value of the exception below is
        // that it names one existing function rather than opening a surface.
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
        // Unchanged from Stage 10 and §30 step 10, and re-asserted because this is the property a focus
        // copy could most plausibly erode: "copy the value" and "store the value" differ by one import.
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
        // The positive half of the exception, stated so it cannot be widened quietly.
        //
        // A `GeneratedElement` is a plan value with no identity, and exactly one thing turns it into a
        // stored element: `PlanReconciler`'s `asGeneratedElement`. Anything else that did so would be a
        // *second* rule for what an element is — and a second rule is how a reconstruction gets in under
        // a name that looks like preservation.
        //
        // The token is `GeneratedElement(` rather than `GeneratedElement.focus`, because the copy itself
        // is written as an unqualified `focus = focus` inside the extension function on that type. A
        // search for the qualified name would find nothing at all — and an assertion that passes because
        // it searches for something no correct implementation writes is worse than no assertion.
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
            // All three are inside the pure package, which is the claim that matters, and only
            // `PlanReconciler` converts one into a stored element.
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
        // This is the gate that makes rows 17 and 18 of the RED suite catchable, and it is the strongest
        // available form of "frozen": the exact bytes of the other seven files, compared against the
        // digests recorded when the package was established.
        //
        // A file-list assertion is not enough. It proves the package still *has* eight files, which a
        // mutation editing the body of `GeneratedPlan.kt` leaves completely untouched — so RED row 18 (a
        // `require` disabled inside `GeneratedSlot`) would have passed every gate the suite had. Content
        // is what has to be pinned, not membership.
        //
        // `PlanReconciler.kt` is deliberately absent from this list: it is the one file P29 changed, and
        // its change is pinned separately and in much more detail by
        // `theReconcilersOnlyNewSemanticLineIsTheFocusCopy` — a line count, not a hash, because a hash
        // cannot express "one semantic line changed and the rest is documentation".
        // The gate is structural rather than digest-based: each of the seven must carry the semantic
        // statements Stage 10 declared. A digest recorded in a test would go stale the first time a
        // legitimate change needed it, and a stale digest is a gate nobody reads.
        //
        // `GeneratedSlot`'s own assertion is the contract that makes a focus mean *anything*: without it,
        // "this element is the PUSH element" is an unchecked claim. It is pinned by its text, which is
        // what catches RED row 18 — a disabled `require` that every other gate in this class survives.
        assertTrue(
            "§8's own contract is still enforced: a slot plans exactly one element per assigned focus, in " +
                "the assignment's order. Disabling this `require` is RED row 18, and it would leave every " +
                "other gate in this class green",
            // "Present" is not enough — RED row 18 wraps it in `if (false)`, so the guard has to assert
            // the assertion is **live**. The `init` block is taken verbatim, so `if (false)` inside it
            // is still visible here and is the whole reason this check is not a plain `contains`.
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
        // The narrowest possible statement of the approved exception: inside the one file that changed,
        // the assignment `focus = focus` appears exactly once, on the `ProgramExercise` construction, and
        // it reads the element's own property rather than anything derived.
        //
        // This is the gate that would catch a well-meaning future edit — "while I'm here, let this also
        // infer the focus for user-authored elements" — because such an edit would either name
        // `GeneratedElement.focus` twice or produce its value from something other than the property.
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
        // The line must be a **bare copy**, not a copy with a fallback. `focus ?: Focus.entries.first()`
        // still contains the token `focus = focus`, so an occurrence count cannot see it — which is RED
        // row 17, and it is the single most plausible way to "helpfully" widen this stage's exception: an
        // elvis operator here looks like defensive null handling rather than like an invented training
        // claim, and it would stamp every generated element that recorded no focus with the first focus in
        // the vocabulary.
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
        // §30 step 10's own units, named individually so a change to any one of them fails here rather
        // than hiding behind "the generated package was allowed to change".
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

    // ------------------------------------------------------------------ the context boundary is unchanged

    @Test
    fun theContextSourceStillHoldsExactlyOneCollaboratorAndItIsTheReadPort() {
        // Unchanged from P27 and re-asserted because the stage's most tempting shortcut was to hand the
        // source the plan repository "just to resolve the focus". It must not: the focus is already in
        // the snapshot the one read returns, and a second collaborator would be a second place a
        // historical fact could come from.
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
        // The two fields P29 deliberately leaves neutral, asserted as **writes** rather than as field
        // names.
        //
        // The behavioural suite asserts the resulting values are neutral, and P27's gate asserted that
        // `GenerationPreferences` still declares six fields — neither of which notices a source that
        // *fills* one of them from something plausible. That is RED row 14: an adaptive preference read
        // from a family ladder, which produces a real, non-empty, entirely fabricated ranking on any
        // history at all.
        //
        // `adaptivePreferredExerciseIds` is the field with a plausible persisted source sitting right
        // next to it (`FamilyProgressionState.currentExerciseId`), so its ban is the load-bearing one.
        // `recovery` has the same shape and is banned by the FAVORABLE/CAUTIOUS token gate above.
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
        // The named prohibition of the brief, in its strongest available form, and **unchanged** by P29:
        // filling two signals must not open the door to `currentExerciseId`, a decision record or a load
        // profile. The behavioural suite asserts the empty list; this asserts that no such type is even
        // named, so a future edit cannot add the read and leave that assertion passing on a fixture that
        // happens to have no adaptive rows.
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
        // P29's central prohibition, and the one most at risk now that the signal is fillable. A
        // reconstruction answers "what does this exercise train?" and names the result as "which focus
        // was this occurrence assigned?" — a different question, with a plausible-looking answer for every
        // occurrence, including the many whose focus was never recorded at all.
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

    // ------------------------------------------------------------------ the service, and the one-read invariant

    @Test
    fun theServiceGainedNoCollaboratorAndReachesNoStorage() {
        // P27's `context` collaborator was the last one added. P29 needed no new dependency — the focus
        // arrives through the context the service already reads — so the count is unchanged, and a sixth
        // collaborator would be one more thing a generation pass could reach.
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
        // The P27 invariant P29 must not erode: one generation operation → one context snapshot → one
        // `GenerationRequest`. Filling two more signals from that snapshot must not tempt a second read.
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

    // ------------------------------------------------------------------ persistence ownership

    @Test
    fun theHistoricalFocusIsStoredInTwoPlacesAndNoOthers() {
        // Exactly two columns carry it, and each for a stated reason: the element that owns the
        // assignment, and the snapshot that freezes it. A third would be a second copy that could
        // disagree with the first.
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
        // programs and elements the generator never assigned. The absence must be stored as absence, and
        // read back as absence, all the way to the generation context.
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
        // §19's one boundary. P29 did not add a second snapshot construction path and did not add
        // post-hoc repair after creation: the focus is captured when the presentation is composed, from
        // the value the plan already carried, and never written afterwards.
        //
        // The mapper is excluded on purpose, and not as a convenience: it *rebuilds* a snapshot from
        // stored rows, which is the read direction of the same single boundary rather than a second
        // production of one. What must not exist is a second place that **creates** a presentation —
        // that would be a second answer to "what was shown", and §19's whole guarantee rests on there
        // being one.
        //
        // `domain/workout/WorkoutSessionSnapshot.kt` is excluded because it is the type's own
        // declaration and its KDoc names the type — a *declaration* is not a construction site, and
        // counting one would make the gate assert something about prose rather than about behaviour.
        val producers = constructionSites()
            .filterNot { it == "domain/workout/WorkoutSessionSnapshot.kt" }
            .filterNot { it.startsWith("data/mapper") }

        assertEquals(
            "one production construction site: `SessionRuntime.startSession`. A second would be a second " +
                "answer to 'what was presented', and §19's whole guarantee rests on there being one",
            listOf("domain/usecase/SessionRuntime.kt"),
            producers
        )
        // The read direction rebuilds a snapshot from the very rows that one site wrote, and that is the
        // same boundary read backwards rather than a second production of one.
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
     * Whether `GeneratedSlot`'s own `init` still enforces its element-per-focus contract **live**.
     *
     * The claim is *"live"*, deliberately not *"first"*. An earlier version of this predicate required
     * the focus `require` to be the first statement in the block, which was wrong twice over: the block
     * also validates `position`, so requiring a particular order made the gate fail for a harmless reason
     * — and, worse, it would have failed again the next time anyone added a validation before it. An
     * architecture gate has to fail for the breach it is guarding and for nothing else, or it becomes a
     * gate people learn to work around.
     *
     * So this asks the two questions that actually matter about the statement:
     *
     *  1. it is present in the block at all, and
     *  2. it is **executable** — the line is the `require(` itself, with no `if (false)` (or any other
     *     short-circuit) in front of it.
     *
     * (2) is what makes RED row 18 catchable: `if (false) require(...)` keeps every character of the
     * assertion, so a plain `contains` would pass. The block is taken by brace depth rather than by a
     * non-greedy regex, because a non-greedy `init { ... }` stops at the first inner `}` — the closing
     * brace of the *previous* assertion's message — and would inspect a fragment.
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
            // Present but not as a live statement: the only way that happens is something in front of it
            // that does not execute, which is exactly the mutation this gate exists to catch.
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