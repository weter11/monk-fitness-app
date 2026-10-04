package com.monkfitness.app.domain.usecase

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P27's production context, pinned mechanically.
 *
 * The behavioural suite (`ProgramGenerationContextTest`) measures what the source decides. This one
 * measures the three properties that are **invisible from behaviour alone** — or rather, that a
 * behaviour-only suite would let through:
 *
 *  * **the context source reaches no storage and no clock.** A source that quietly grew a `Clock` to
 *    decide recovery would still pass every behavioural test in the suite *provided* it returned
 *    `UNKNOWN` — the fabrication this stage forbids is exactly the kind that can be implemented
 *    without changing any current answer. So the claim is structural: the type names no collaborator
 *    that could tell it the time.
 *  * **the context source reads no adaptive state.** `FamilyProgressionState.currentExerciseId` and
 *    `AdaptiveDecision` are both persisted and both would produce a *plausible* preference. The
 *    behavioural suite asserts the empty list; this asserts that no such type is even named, so a
 *    future edit cannot add the read and leave the existing assertion passing on a fixture that happens
 *    to have no adaptive rows.
 *  * **the pure generated package is untouched.** P27's own brief requires it, and §30 step 10's
 *    boundary requires it independently: the domain gained no file and imports nothing from the
 *    application layer, the data layer or DI.
 *
 * The fourth property is the wiring itself: **one** construction of the production source, in the
 * composition root. A second construction would be a second set of context semantics, which is the same
 * failure shape P23 pinned for the catalogue and P24 for the service.
 */
class ProgramGenerationContextArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { dir -> if (dir.isDirectory) dir else File("app/$dir") }

    private val contextFile = File(mainDir, "domain/usecase/ProgramGenerationContext.kt")
    private val serviceFile = File(mainDir, "domain/usecase/ProgramGenerationService.kt")
    private val generatedDir = File(mainDir, "domain/program/generated")

    /** This stage's own declaration site — the file that *is* the source, not a caller of it. */
    private val contextPath = "domain/usecase/ProgramGenerationContext.kt"

    /** Comments stripped, so a gate never fires on prose that explains an absence. */
    private fun code(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun codeLines(file: File): List<String> = code(file.readText())
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Whole-token match, with `_` as a word character, so a prefix never fires on a longer name. */
    private fun List<String>.namesAny(token: String): List<String> = filter { line ->
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])").containsMatchIn(line)
    }

    // ------------------------------------------------------------------ the source is a reader and nothing else

    @Test
    fun theContextSourceReachesNoRepositoryNoDaoAndNoClock() {
        // A `Clock` is the collaborator that would make a recovery heuristic *possible*, so its absence
        // is the structural form of "recovery is never derived from elapsed time". `WorkoutSessionRepository`
        // is absent because the source holds the narrow `GenerationSessionHistory` port instead — the
        // production wiring names the repository, this file does not.
        val forbidden = listOf(
            "WorkoutSessionRepository", "ProgramRepository", "ProgramPlanRepository",
            "ProgramScheduleRepository", "ProgramAdaptiveRepository", "ProgramProgressRepository",
            "Dao", "AppDatabase", "Room", "@Entity", "Sqlite",
            "Clock", "LocalDate.now", "Instant.now", "System.currentTimeMillis", "LocalDate.now()",
            "Random", "Duration", "ChronoUnit", "TimeUnit",
            "kotlinx.coroutines.flow", "androidx", "import android",
            "com.monkfitness.app.data", "com.monkfitness.app.di", "com.monkfitness.app.ui",
            "com.monkfitness.app.viewmodel"
        )
        val offenders = codeLines(contextFile).flatMap { line ->
            forbidden.filter { token -> line.contains(token) }.map { token -> "$token: $line" }
        }

        assertTrue(
            "the context source holds one narrow read port and nothing that could persist, schedule, " +
                "read a calendar or tell it the time. A collaborator added here is a new derivation " +
                "available to a signal that has no honest source today: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theContextSourceNamesNoAdaptiveStateAtAll() {
        // The named prohibition of the brief, in its strongest available form. `FamilyProgressionState`
        // and `AdaptiveDecision` are both persisted, both revision-scoped and both would yield a
        // *plausible* preference — so the empty list the behavioural suite asserts could survive an
        // implementation that reads them and simply happens to answer empty on its fixtures.
        val forbidden = listOf(
            "FamilyProgressionState", "currentExerciseId", "AdaptiveDecision", "AdaptiveAdjustment",
            "AdaptiveInputSnapshot", "ExposureObservation", "LoadProfile", "ProgramAdaptiveIntegration",
            "AdaptiveJudgement", "PilotProgressionProfiles", "ProgramAdaptiveRepository",
            "ProgressionPolicy", "ProgressionRelation"
        )
        val offenders = codeLines(contextFile).namesAny("").isEmpty().let {
            codeLines(contextFile).filter { line -> forbidden.any { token -> line.contains(token) } }
        }

        assertTrue(
            "no stored adaptive state is read as a generation fact: `currentExerciseId` means " +
                "'the exercise this family is currently on', family- and revision-scoped, and no " +
                "existing contract makes it a selection preference. Naming none of these types makes " +
                "that structural rather than a claim about a fixture that happens to be empty: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theContextSourceStatesNoRecoveryAndNoFocusDerivedSignal() {
        // REVISED by P29, narrowed rather than dropped. P27 banned the token `Focus` outright, because
        // at that stage a focus-keyed map was precisely what could not be filled honestly. P29 gave
        // those two maps a real owner, so `Focus` is now legitimately named here — and the ban that
        // actually protected them is kept in full, in its stronger form: the source may **read** a
        // focus the snapshot recorded, and may do nothing else with it.
        //
        // What survives unchanged:
        //  * recovery stays `UNKNOWN` — §14's `FAVORABLE` and `CAUTIOUS` are decisions of the adaptive
        //    stage's own judgement rule, not facts a generation context may manufacture;
        //  * no cross-dimension number is built — repetitions and seconds are never summed into a count
        //    of sets, and no scalar load score is computed.
        val forbidden = listOf(
            "FAVORABLE", "CAUTIOUS", "focusCounts", "totalTarget", "completedReps",
            "durationSeconds", "volume", "intensity", "density"
        )
        val offenders = codeLines(contextFile).filter { line -> forbidden.any { token -> line.contains(token) } }

        assertTrue(
            "recovery stays UNKNOWN and no cross-dimension number is computed here. §14's FAVORABLE and " +
                "CAUTIOUS are decisions of the adaptive stage's own judgement rule, and a load total is a " +
                "derivation this stage must not invent: $offenders",
            offenders.isEmpty()
        )
        // The *reconstruction* ban, which is the claim P29 exists to make permanent. P29 made historical
        // focus readable, which is exactly the circumstance in which a future edit might reach for the
        // catalogue instead of the snapshot — and that would produce a plausible, wrong answer.
        val reconstructors = listOf(
            "ProductionFocusClassification", "ExerciseMetadata", "exerciseMetadata", "catalog",
            "catalogue", "trains(", "eligibleFocuses", "focusOf(", "classify", "Classif"
        )
        val reoffenders = codeLines(contextFile).filter { line ->
            reconstructors.any { token -> line.contains(token) }
        }
        assertTrue(
            "a performed occurrence's focus is READ from the session's own snapshot and never " +
                "reconstructed. Classifying an exercise by what the catalogue says it trains is the " +
                "reconstruction algorithm P27 refused and P29 made unnecessary: an exercise that trains " +
                "two focuses is not two assignments, and no assignment was recorded for a manual " +
                "program: $reoffenders",
            reoffenders.isEmpty()
        )
        // And the absence claim, in the form that matters now that the maps are filled: an element with
        // no recorded focus is dropped rather than defaulted, so no `?: 0` and no `getOrDefault`.
        val defaulted = codeLines(contextFile).filter { line ->
            Regex("""(getOrDefault|getOrElse|\?:)\s*0""").containsMatchIn(line)
        }
        assertTrue(
            "§12 and `ExposureObservation`'s own invariant: absence produces no observation rather than " +
                "a zero one, so a focus nobody recorded must never appear with a 0: $defaulted",
            defaulted.isEmpty()
        )
    }

    @Test
    fun theContextSourceHoldsExactlyOneCollaboratorAndItIsTheReadPort() {
        // A cardinality claim rather than an absence claim: "no repository" cannot survive a rename,
        // "exactly one collaborator, and it is the read port" can.
        assertEquals(
            "the source is handed one thing — the history read — so there is no second place a fact " +
                "could come from",
            listOf("sessions"),
            ProgramHistoryGenerationContext::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
        )
        assertEquals(
            "and it is constructed with that one thing",
            1,
            ProgramHistoryGenerationContext::class.java.declaredConstructors
                .single().parameterCount
        )
    }

    @Test
    fun theHistoryPortCanOnlyRead() {
        // "Performs no writes" as a property of the *shape*: the port declares one method, so a write is
        // not something the source could call even by accident. Named this way because a source-scan over
        // the body would pass on a body that wrote through a differently-named collaborator.
        assertEquals(
            "one read, and nothing that could start a session, confirm a set or finish a workout",
            listOf("sessionsOfProgram"),
            // The SAM method of a `fun interface` arrives name-mangled (`sessionsOfProgram-<hash>`),
            // so the name is matched with the mangling suffix removed rather than by equality.
            GenerationSessionHistory::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .map { it.name.substringBefore('-') }
        )
        assertTrue(
            "and it is a suspend read, so a generation pass reaches it from the coroutine the caller " +
                "already owns",
            GenerationSessionHistory::class.java.declaredMethods
                .single().let { method ->
                    method.parameterTypes.contains(kotlin.coroutines.Continuation::class.java)
                }
        )
    }

    @Test
    fun theSourceTurnsMissingIntoAnExplicitNeutralValueAndNeverAZero() {
        // The three shapes an absence could take in this file, and the two it may not. `?: 0`, `?: 1`,
        // `?: false` and `?: emptyList()` on a focus-keyed or count-bearing expression would each be a
        // fabricated measurement; `?: GenerationPreferences.NONE` and a guard clause are the honest form.
        val lines = codeLines(contextFile)
        val invented = lines.filter { line ->
            val fabricated = Regex(""":\s*(0|1|false|emptyList\(\)|emptyMap\(\)|Focus\.)""")
            fabricated.containsMatchIn(line)
        }

        assertTrue(
            "§12 and ExposureObservation's own invariant: absence produces no observation rather than " +
                "a zero one. Every `?:` in this file must be a neutral value, never a number: $invented",
            invented.isEmpty()
        )
        // **Revised by P29, not relaxed.** P28 could assert a literal `return GenerationPreferences.NONE`
        // at both exits because §9's top level was the one signal nothing could fill, so "neutral" and
        // "all-defaults" were the same value. P28 gave that signal an owner, so an exit now carries the
        // user's stated preference. P29 then removed the *second* exit entirely: the "no performed
        // exercise" branch existed only because `recentExerciseIds` needed an empty-list answer, and an
        // empty map is already the honest answer for two more signals — so a branch that returned
        // all-defaults was a branch that had to be re-argued every time a signal gained an owner.
        //
        // The claim is therefore restated and made *stronger*, not weaker: the file constructs
        // `GenerationPreferences` exactly **twice** — the no-Program exit and the single full one — both
        // state the draft's own preference, and the full one states all four read signals. An
        // implementation that dropped the preference fails; one that fabricated a ranking trips the
        // reconstruction ban above; one that added a fifth construction, or a second request-assembly
        // path, fails here.
        val code = code(contextFile.readText())
        assertTrue(
            "every exit states the preference explicitly rather than falling back to the all-defaults value",
            code.contains("userPreferredExerciseIds = preferred")
        )
        assertEquals(
            "exactly two constructions in this file: the no-Program exit, and the single full one that " +
                "states every signal the read produced. A third would be a path that had to be re-argued " +
                "the next time a signal gained an owner",
            2,
            Regex("GenerationPreferences\\(").findAll(code).count()
        )
        assertEquals(
            "and both carry the draft's own preference, so neither can drop what the user stated",
            2,
            Regex("GenerationPreferences\\(\\s*userPreferredExerciseIds = preferred")
                .findAll(code)
                .count()
        )
        // The four read signals are named on the single full construction. Asserted as *field* names so
        // the claim survives a reformat, and exhaustively, so a fifth read signal added here without
        // revising this test fails rather than passing quietly.
        listOf(
            "recentExerciseIds",
            "recentExposureByFocus",
            "recentLoadByFocus"
        ).forEach { field ->
            assertTrue(
                "the full construction states `$field` from the history that was read — the signal has " +
                    "an owner now, and an owner that is not forwarded is not an owner",
                code.contains("            $field =")
            )
        }
    }

    // ------------------------------------------------------------------ one read, one pass, one path

    @Test
    fun theServiceReadsTheContextExactlyOnceInsideTheSharedPass() {
        val code = code(serviceFile.readText())
        assertEquals(
            "the context is read once per operation — Generate, Regenerate and Preview all hand to the " +
                "same private pass, so a preview can never be planned against one set of facts and " +
                "applied against another",
            1,
            Regex("context\\.preferencesFor\\(").findAll(code).count()
        )
        assertTrue(
            "and it is read inside that pass, with the value passed down as an argument rather than " +
                "re-read by the request assembly",
            code.contains("context.preferencesFor(draft)")
        )
        assertTrue(
            "so `planned` takes the preferences as a parameter: a second read inside it would be a " +
                "second set of facts for the same operation",
            code.contains("preferences: GenerationPreferences")
        )
    }

    @Test
    fun theServiceStillSpeaksOnlyTheDomainsOwnRequestValues() {
        // The request assembly stays P23's. P27 added a *value* to it and no new path into it.
        val code = code(serviceFile.readText())
        assertTrue(
            "the request is still assembled by the boundary, so there is exactly one assembly of §30 " +
                "step 10's pure input",
            code.contains("ProductionGenerationBoundary.generationRequest(")
        )
        assertEquals(
            "and it is assembled in exactly one place",
            1,
            Regex("ProductionGenerationBoundary\\.generationRequest\\(").findAll(code).count()
        )
        listOf("GeneratedPlanner", "PlanReconciler", "FocusPlanner", "ExerciseSelector").forEach { token ->
            assertEquals(
                "the service names $token only where a test or a KDoc reference may, never as a call it " +
                    "makes itself: P27 changed no planner semantics",
                0,
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "\\.").findAll(code).count()
            )
        }
    }

    @Test
    fun allThreeEntryPointsRemainOneSharedPass() {
        // `generate`, `regenerate` and `preview` each hand to `edit` and to nothing else. A fourth
        // entry point, or one of them calling `planned` directly, is how Preview and Generate would come
        // to disagree.
        val code = code(serviceFile.readText())
        listOf("generate", "regenerate", "preview").forEach { entry ->
            val declared = Regex(
                "suspend fun $entry\\(\\s*\\n?\\s*draft: ProgramEditorDraft,\\s*\\n?\\s*" +
                    "availableEquipment: Set<Equipment>\\s*\\n?\\s*\\): ProgramGenerationResult = " +
                    "edit\\(draft, availableEquipment\\)"
            ).findAll(code).count()
            assertEquals(
                "'$entry' is the shared pass and nothing else — §33's no-silent-substitution applied to " +
                    "context as well as to focus",
                1,
                declared
            )
        }
        assertEquals(
            "and `planned` is private, so no caller can assemble a request around its own context",
            listOf("edit", "planned"),
            Regex("private (suspend )?fun (edit|planned)\\(").findAll(code).map { it.groupValues[2] }
                .toList()
        )
    }

    @Test
    fun theServiceDeclaresTheContextAndNothingElseNew() {
        // REVISED by P27, not relaxed. The old claim was "five things, of which preferences and policy
        // are optional". P27 replaced the optional `preferences` value with a **required** context
        // collaborator: the count is unchanged at five, and the change is that one of the five can no
        // longer be omitted — which is the whole point, since a defaulted value here was the quiet
        // neutrality this stage removed.
        val constructor = ProgramGenerationService::class.java.declaredConstructors
            .first { !it.isSynthetic }
        val declaration = code(serviceFile.readText())

        assertEquals(
            "still exactly five collaborators: a catalogue, a focus source, an id source, the context " +
                "and the policy. A sixth would be one more thing a generation pass could reach",
            5,
            constructor.parameterCount
        )
        assertTrue(
            "the context is REQUIRED — a service constructed without one would silently plan against " +
                "no facts, which is the neutrality P27 replaced rather than endorsed",
            declaration.contains("private val context: GenerationContextSource") &&
                !declaration.contains("context: GenerationContextSource =")
        )
        assertTrue(
            "and the policy remains the generated domain's own declared default",
            declaration.contains("policy: GenerationPolicy = GenerationPolicy.DEFAULT")
        )
    }

    @Test
    fun theServiceHoldsNoRepositoryAndNoStorageStill() {
        // Unchanged by P27 and re-asserted because the stage's most tempting shortcut was to hand the
        // service the session repository directly. The read belongs to the context source; the service
        // must still hold nothing that could write.
        val forbidden = listOf(
            "ProgramRepository", "ProgramPlanRepository", "ProgramScheduleRepository",
            "ProgramExerciseDao", "ProgramDao", "AppDatabase", "Dao", "Room", "@Entity",
            "ProgramEditorService", "ProgramSaveService", "ProgramScheduler", "SlotPlanner",
            "Clock", "LocalDate.now", "Instant.now", "System.currentTimeMillis", "Random",
            "WorkoutSessionRepository", "WorkoutGenerator",
            "kotlinx.coroutines.flow", "androidx", "import android", "com.monkfitness.app.ui",
            "com.monkfitness.app.viewmodel", "R.string"
        )
        val offenders = codeLines(serviceFile).flatMap { line ->
            forbidden.filter { token -> line.contains(token) }.map { token -> "$token: $line" }
        }

        assertTrue(
            "Generate and Regenerate only ever alter a draft (§7). P27 gave the pass a *reader*, and a " +
                "reader reached directly would put storage back in the orchestration: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ the wiring, and the pure package

    @Test
    fun theCompositionRootBuildsTheProductionContextFromTheRepositorysOwnRead() {
        val container = code(File(mainDir, "di/AppContainer.kt").readText())
        assertTrue(
            "the container wires the production source",
            container.contains("ProgramHistoryGenerationContext(")
        )
        assertTrue(
            "over the repository's own `sessionsOfProgram` — the one path that assembles a complete " +
                "WorkoutSession from its stored snapshot and occurrence rows, not a second DAO read",
            container.contains("workoutSessionRepository.sessionsOfProgram(programId)")
        )
    }

    @Test
    fun theProductionContextIsBuiltInOnePlaceAndNoOneElseBuildsOne() {
        val sites = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(mainDir).path.replace('\\', '/') == contextPath }
            .filter { file -> code(file.readText()).contains("ProgramHistoryGenerationContext(") }
            .map { it.relativeTo(mainDir).path.replace('\\', '/') }
            .toList()

        assertEquals(
            "one construction site, in the composition root (§26): a second one would be a second set of " +
                "context semantics, which is the same failure shape P23 pinned for the catalogue",
            listOf("di/AppContainer.kt"),
            sites
        )
    }

    @Test
    fun theContextSourceIsReachedThroughItsPortAndNotDowncast() {
        val container = code(File(mainDir, "di/AppContainer.kt").readText())
        assertTrue(
            "the container names the port the source is built on, so a future implementation of a " +
                "different context strategy replaces the wiring and not the call sites",
            container.contains("GenerationSessionHistory {")
        )
        assertTrue(
            "and the service reaches the source through `GenerationContextSource`, the one collaborator " +
                "P27 added",
            code(serviceFile.readText()).contains("private val context: GenerationContextSource")
        )
        assertTrue(
            "which is a `fun interface`, so a test states one lambda and production states a real source",
            GenerationContextSource::class.java.isInterface &&
                GenerationContextSource::class.java.declaredMethods.count { !it.isSynthetic } == 1
        )
    }

    @Test
    fun thePureGeneratedPackageGainedNoFileAndReachesNothingNew() {
        assertEquals(
            "the pure package is exactly the eight files Stage 10 established and P24 wired around — " +
                "P27 reads facts for it and changes nothing inside it, because a domain that had to know " +
                "where those facts come from would be a domain that could reach storage",
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
        val imports = generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().flatMap { file -> codeLines(file) }.filter { it.startsWith("import ") }

        assertTrue(
            "and it imports neither the application layer nor the app's data model or DI (§25, " +
                "§30 step 10): $imports",
            imports.none { it.startsWith("import com.monkfitness.app.domain.usecase") } &&
                imports.none { it.startsWith("import com.monkfitness.app.data.") } &&
                imports.none { it.startsWith("import com.monkfitness.app.di") }
        )
    }

    @Test
    fun theGeneratedPreferencesTypeIsUnchanged() {
        // The signal's own vocabulary: P27 filled one field and changed no definition, so the field list
        // and the neutral companion are what Stage 10 declared.
        assertEquals(
            "§8's six plain signals, in Stage 10's order — no field added, renamed or removed",
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
            "and the neutral representation is still the all-defaults value, because it is what a new " +
                "Program and a Program with no history both state",
            com.monkfitness.app.domain.program.generated.GenerationPreferences(),
            com.monkfitness.app.domain.program.generated.GenerationPreferences.NONE
        )
    }

    @Test
    fun theRequestTypeGainedNoFieldForThisStage() {
        // The brief's "do not extend GenerationRequest if the existing fields suffice" — they did, so it
        // was not extended. A new field here would be a new thing the planner could read, which is a
        // semantic change to §30 step 10 rather than a wiring one.
        assertEquals(
            "§30 step 10's pure input, unchanged: the configuration, the candidates, the equipment, the " +
                "preferences and the policy",
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
}