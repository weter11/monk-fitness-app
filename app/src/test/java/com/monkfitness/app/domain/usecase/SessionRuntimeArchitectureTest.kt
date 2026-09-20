package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramAdaptiveRepository
import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.workout.SessionCompletion
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 8's boundaries, pinned mechanically instead of by convention.
 *
 * The session runtime is the layer that finally *writes* what a workout was, so every temptation it has
 * is one import away: a DAO "just to read the plan day", the database "for the transaction", the
 * scheduler "to know which slot is next", an adaptive policy "to decide the next set", a
 * `saveNewRevision` "to keep the plan in step with what was performed", a ViewModel "to tell the screen
 * the workout is over". §6, §16, §19, §20, §25, §26, §27 and §33 forbid every one of them, and §27/§30
 * assign each to a later — or an earlier — stage. The rules are therefore asserted against the sources,
 * the compiled shape and the composition root:
 *
 *  * the runtime reaches no DAO, no Room type, no entity and no Android type;
 *  * it acquires no database and holds no mutable state;
 *  * its collaborators are exactly the four repositories of §27's completion, the two §26 ports and the
 *    transaction runner — no scheduler, no generator, no policy, no progress reader, no UI;
 *  * it offers exactly the five operations of §19 and no sixth (no `back`, no `skip`, no `abandon`);
 *  * it never writes a revision, never constructs a schedule and never evaluates a policy;
 *  * it never turns a failure into an empty value, and never swallows one;
 *  * and nothing in the UI layer reaches it yet — wiring a screen is a later step, and this test is what
 *    says so.
 */
class SessionRuntimeArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { if (it.isDirectory) it else File("app/$it") }

    private val runtimeSource = "domain/usecase/SessionRuntime.kt"

    /** The pure domain the runtime and its callers share. */
    private val domainSources = listOf(
        "domain/workout/SessionRuntimeResult.kt",
        "domain/workout/SessionCompletion.kt",
        "domain/adaptive/decision/SlotPresentation.kt"
    )

    private val allSources = domainSources + runtimeSource

    private fun codeLines(sources: List<String>): List<Pair<String, String>> = sources.flatMap { source ->
        val file = File(mainDir, source)
        assertTrue("expected $source", file.isFile)
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .map { source.substringAfterLast("/") to it }
    }

    private fun occurrences(source: String, needle: String): Int {
        var count = 0
        var index = source.indexOf(needle)
        while (index >= 0) {
            count++
            index = source.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun sourceText(source: String): String =
        File(mainDir, source).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")

    // ------------------------------------------------------------------ layering

    @Test
    fun theRuntimeReachesNoDaoNoRoomTypeNoEntityAndNoAndroidType() {
        val forbidden = listOf(
            "import android", "import androidx", "import kotlinx",
            "import com.monkfitness.app.data.local", "import com.monkfitness.app.data.model",
            "import com.monkfitness.app.ui", "import com.monkfitness.app.viewmodel",
            "import com.monkfitness.app.animation", "import com.monkfitness.app.poses",
            "import com.monkfitness.app.R"
        )
        val offenders = codeLines(listOf(runtimeSource)).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.startsWith(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the runtime runs above the repositories: no DAO, no Room entity, no Android and no UI " +
                "type may reach it (§25). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noSourceOfThisStageNamesADaoAnEntityOrARoomAnnotation() {
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            when {
                Regex("""\b\w*Dao\b""").containsMatchIn(line) -> "$source: $line"
                Regex("""\b\w*Entity\b""").containsMatchIn(line) -> "$source: $line"
                line.contains("@Entity") || line.contains("@Dao") || line.contains("@Database") ->
                    "$source: $line"
                else -> null
            }
        }

        assertTrue(
            "there is no second persistence model here and no DAO access (§25, §33): persistence goes " +
                "through the repositories. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun nothingHereAcquiresADatabaseOrReachesForTheCompositionRoot() {
        val forbidden = listOf(
            "AppDatabase", "Room.databaseBuilder", "getDatabase(", "INSTANCE", "AppContainer",
            "SQLiteDatabase", "withTransaction"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the database and the transaction are constructor arguments handed in by the composition " +
                "root, never something this layer finds for itself (§26). Found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ what the runtime may not have

    @Test
    fun theRuntimeIsWiredWithExactlyTheCollaboratorsItsTransactionNeeds() {
        // `declaredConstructors` holds the primary constructor plus any synthetic one Kotlin generates
        // for defaulted parameters, so the synthetic members are filtered out first.
        val collaborators = SessionRuntime::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .parameterTypes
            .map { it.simpleName }

        assertEquals(
            "the runtime's collaborators are exactly the revision it reads, the opportunities it reads " +
                "and writes one outcome through, the session graph it persists, the adaptive rows a " +
                "completion records, the two §26 ports, the one calendar an adaptive decision's day is " +
                "read in — the same value the integration receives, and a required argument so that no " +
                "caller can leave the two sides of §4's rule disagreeing — and the transaction runner. " +
                "The absence of a scheduler, a generator, a policy, a progress reader and a UI type is " +
                "what makes §30 steps 9–12 impossible here rather than forbidden",
            listOf(
                "ProgramPlanRepository", "ProgramScheduleRepository", "WorkoutSessionRepository",
                "ProgramAdaptiveRepository", "Clock", "IdGenerator", "ZoneId", "Function2"
            ),
            collaborators
        )

        val fields = SessionRuntime::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(
            "every collaborator is a constructor-injected value held immutably: no `var`, no mutable " +
                "collection and no state the runtime accumulates between operations",
            fields.all { Modifier.isFinal(it.modifiers) }
        )
        assertEquals(
            "and there is no field that is not one of those collaborators",
            8,
            fields.size
        )
    }

    @Test
    fun theRuntimeOffersExactlyTheFiveOperationsOfASession() {
        val operations = SessionRuntime::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .filterNot { it.name.startsWith("get") && it.parameterCount == 0 }
            // A suspend function whose parameters are inline value classes compiles to a mangled name
            // (`startSession-p1oqjkA`), so the operation is matched by its stem rather than its full
            // compiled name.
            .map { it.name.substringBefore('-') }
            .sorted()

        assertEquals(
            "one attempt at one opportunity has five operations — start it, read it back, confirm a " +
                "set, cancel it and finish it (§19). `Back` is deliberately not among them: leaving the " +
                "screen is not a fact about the workout, so nothing here can write for it, and a sixth " +
                "name appearing here is a lifecycle decision this stage does not own",
            listOf("cancelSession", "confirmSet", "finishSession", "restoreSession", "startSession"),
            operations
        )
    }

    @Test
    fun theSessionsPresentationIsNeverReadFromTheLivePlan() {
        val runtime = sourceText(runtimeSource)

        assertEquals(
            "the plan is read once, for the revision the opportunity names — nothing else in this layer " +
                "consults a revision",
            1,
            occurrences(runtime, "planRepository.revisionById(")
        )
        listOf(
            "currentRevision", "saveNewRevision", "setCurrentRevision", "updateRevision",
            "revisionById", "daysOfRevision", "exercisesOfRevision"
        ).forEach { token ->
            val expected = if (token == "revisionById") 1 else 0
            assertEquals(
                "`$token` is a plan read or a plan write this layer does not make: a presentation is " +
                    "captured once, and a loaded session is rebuilt from its own rows (§6, §19)",
                expected,
                occurrences(runtime, token)
            )
        }
        assertTrue(
            "and the session's own read is the only one a restore may use",
            occurrences(runtime, "sessionRepository.sessionById(") >= 1
        )
    }

    /**
     * §30 step 12 revised one entry of this fence, and this test states both halves of the new line:
     *
     * ```text
     * still forbidden   the whole scheduling vocabulary, and any calendar this layer would *acquire*
     *                   (`LocalDate.now`, `ZoneId.systemDefault`, a clock read for a date)
     * allowed, once     the zone it is *given*, used for exactly one comparison: the day an adaptive
     *                   decision was taken on against the day of the opportunity it names
     * ```
     *
     * The zone is not a scheduling decision: a date and an instant are different facts, and §26 puts
     * their conversion in the layer that owns the clock — which is also why the value is a constructor
     * fact and the same one the producer of that decision receives (`AppContainer`). The positive half
     * below is what keeps this from becoming a hole: the runtime may *read* a calendar it was handed,
     * never acquire one.
     */
    @Test
    fun theRuntimeMakesNoSchedulingDecision() {
        val forbidden = listOf(
            "ProgramScheduler", "SlotPlanner", "SlotPlan", "ScheduleRequest", "ScheduleWindow",
            "PLANNING_HORIZON_DAYS", "SlotIdSource", "DayOfWeek", "asOf",
            "plannedFor(", "plusDays", "minusDays",
            "LocalDate.now", "ZoneId.systemDefault", "ZoneOffset.systemDefault"
        )
        val offenders = codeLines(listOf(runtimeSource)).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the Scheduler owns when an opportunity exists, which dates it covers and whether it was " +
                "missed (§20); this layer starts, continues and ends one attempt, computes no date, " +
                "chooses no date, acquires no calendar and opens no window. Found: $offenders",
            offenders.isEmpty()
        )

        val lines = codeLines(listOf(runtimeSource))
        assertEquals(
            "and the one date this layer does read is the one comparison §4 needs: the day the adaptive " +
                "decision was taken on against the day of the opportunity it names",
            1,
            lines.count { (_, line) -> line.contains("LocalDate.ofInstant(") }
        )
        assertEquals(
            "with the zone it was given, never one it looked up",
            1,
            lines.count { (_, line) -> line.contains("decision.decidedAt") }
        )
    }

    @Test
    fun theRuntimeContainsNoAdaptiveEnginePolicyOrGenerator() {
        // §30 step 12 revised one entry: the runtime now composes §27's *adaptive state* leg, so
        // `FamilyProgressionState` — §23's own stored state value — crosses it as a *value* it writes
        // inside the completion's transaction. Nothing else about the fence moved, and it is tightened
        // where the new stage could leak into this layer: no integration, no window, no ladder, no
        // classification and no reason vocabulary may be named here at all. The positive half of the
        // rule is asserted beside this test.
        val forbidden = listOf(
            "AdaptivePolicy", "AdaptiveProgramEngine", "AdaptiveSignalCalculator", "ProgressionResolver",
            "AdaptiveState", "ExposureObservation", "AdaptiveInputSnapshot",
            "AdaptiveSignal", "WorkoutGenerator", "getExerciseLibrary", "FocusPlanner",
            "ProgramProgressRepository", "ProgramConfiguration", "ProgramEditorService", "SettingsManager",
            "ProgramMaintenance", "ProgramCalendar", "Random", "shuffled", "Math.random",
            "AdaptiveJudgement", "ProgramAdaptiveIntegration", "ProgramAdaptiveWindow",
            "ProgramProgressionRelation", "ProgressionRelationProvider", "ExerciseFamilyClassification",
            "ProgramAdaptiveReason", "AdaptiveInputGap", "AdaptiveIntegrationOutcome",
            "ProgramProgressionVariant", "AdaptivePolicyV"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "no adaptive policy, engine, signal, progression profile, generator, library read, progress " +
                "read or configuration belongs to this stage (§30 steps 9–12). The only adaptive values " +
                "that cross it are the stored adjustments of one opportunity — read, applied and " +
                "captured — and the decision a completion is handed, which is stored and never produced. " +
                "Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theRuntimeOnlyEverCarriesTheAdaptiveStateLegAndNeverProducesOne() {
        // The positive half of the fence above: the one adaptive value that crosses this layer is
        // §23's stored family state, and the runtime may only *carry* it into the transaction. Nothing
        // here reads a family state, computes one, advances a counter, resolves a ladder or builds an
        // integration request — §30 step 12's own use case does that, and the composition root hands
        // its outcome over.
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            when {
                line.contains("familyState.copy(") -> "$source: $line"
                line.contains("precedingProgressQualifyingWindows =") -> "$source: $line"
                line.contains("programAdaptiveIntegration") -> "$source: $line"
                line.contains("ProgressionRelation") -> "$source: $line"
                line.contains("relationOf(") -> "$source: $line"
                line.contains("familyOf(") -> "$source: $line"

                else -> null
            }
        }

        assertTrue(
            "the runtime composes an already-produced adaptive completion: it never advances a " +
                "family's counters, never resolves a ladder, never classifies an exercise and never " +
                "asks the integration for anything. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theRuntimeNeverWritesARevisionAndNeverTouchesAProgramsLifecycle() {
        val forbidden = listOf(
            "programRepository", "lifecycle", "archive", "pause", "currentRevision",
            "saveRevised", "updateProgram", "setCurrentRevision"
        )
        val offenders = codeLines(listOf(runtimeSource)).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it, ignoreCase = true) }?.let { "$source: $line" }
        }

        assertTrue(
            "a revision is immutable (§6), a lifecycle transition is §3's and archive is §29's: this " +
                "layer is handed the repositories of one attempt and has no Program repository at all, " +
                "so starting a workout cannot move a Program. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theRuntimeIsCalledFromExactlyOneStateHolderAndConstructedOnlyByTheCompositionRoot() {
        // §30 step 15 inverted this claim. Until then *nothing* in the UI reached the runtime: the
        // target runtime was DI-wired and unused, and the app still ran the shipped 56-day program. The
        // cutover makes this screen the production path, so the claim is no longer an absence — it is a
        // **cardinality**, which is the part that can regress:
        //
        //   1. the runtime's operations are called from exactly one place, and it is the state holder
        //      that the session screen renders;
        //   2. no ViewModel and no screen ever *constructs* one — §26: it receives the graph the
        //      composition root built.
        val operations = listOf(
            ".startSession(", ".restoreSession(", ".confirmSet(", ".cancelSession(", ".finishSession("
        )
        // Comments are stripped first: the session screen *documents* the operations it delegates
        // (`controller.confirmSet(…)`), and a rule that could not tell prose from a call would push the
        // documentation out of the sources to keep itself green — which is the wrong trade.
        fun withoutComments(text: String): String = text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        // A caller of the runtime is a source that *names* the runtime and invokes one of its
        // operations. Membership by name is what makes the scan able to tell the two apart: a screen
        // calling `controller.confirmSet(...)` reaches the state holder, not the runtime, and the
        // controller is the only file that holds one.
        val callers = listOf(File(mainDir, "ui"), File(mainDir, "viewmodel"))
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                    .filter { source ->
                        val text = withoutComments(source.readText())
                        text.contains("SessionRuntime") && operations.any { text.contains(it) }
                    }
                    .map { source -> "${root.name}/${source.name}" }
                    .toList()
            }
            .sorted()

        assertEquals(
            "the runtime's operations have exactly one caller above it: the state holder the session " +
                "screen renders. A screen calling the runtime directly, or a second state holder doing " +
                "so, would be a second workout runtime",
            listOf("ui/ProgramSessionController.kt"),
            callers
        )

        // The other half of the arrow: the screen reaches the runtime only through that state holder,
        // so it may invoke its methods but must not name the runtime itself.
        // Belt and braces on the stripping: a KDoc line that survived a partially-matched block still
        // starts with `*`, so a prose line can never be read as a call site.
        val screen = withoutComments(File(mainDir, "ui/screens/ProgramSessionScreen.kt").readText())
            .replace(Regex("""^\s*\*.*$""", RegexOption.MULTILINE), "")
        assertFalse(
            "the session screen holds no runtime: it is handed the state holder (§13, §26)",
            screen.contains("SessionRuntime")
        )

        // An initializer, not a mention: `class SessionRuntime(` is a declaration, and the file that
        // declares the class is the last thing that should count as a second construction site.
        val construction = Regex("""=\s*SessionRuntime\(""")
        val constructing = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { source -> construction.containsMatchIn(withoutComments(source.readText())) }
            .map { source -> source.relativeTo(mainDir).path.replace('\\', '/') }
            .sorted()
            .toList()

        assertEquals(
            "and exactly one source constructs one: the composition root (§26)",
            listOf("di/AppContainer.kt"),
            constructing
        )
    }

    // ------------------------------------------------------------------ errors are not swallowed

    @Test
    fun theRuntimeTurnsNoFailureIntoAnEmptyValue() {
        val runtime = sourceText(runtimeSource)

        assertEquals(
            "there are exactly two places this layer handles a thrown value: the occupancy rule the " +
                "storage layer raises (mapped to its refusal, naming the attempt that holds the " +
                "opportunity) and the outer boundary (mapped to a Failure). A third is a new decision " +
                "about errors, and a bare `catch` that returns nothing is the §33 prohibition",
            2,
            occurrences(runtime, "catch (")
        )
        assertTrue(
            "the occupancy refusal is mapped where it happens",
            runtime.contains("catch (alreadyInProgress: SessionAlreadyInProgress)")
        )
        assertTrue(
            "and everything else is surfaced as a failure rather than absorbed",
            runtime.contains("catch (failure: Throwable)") &&
                runtime.contains("SessionRuntimeResult.Failure(failure)")
        )

        val swallowing = listOf(
            "runCatching", "?: emptyList()", "?: emptySet()", "?: null", "?: false", "?: 0",
            "catch (_:", "catch (ignored", "error(\"\")"
        )
        val offenders = swallowing.filter { runtime.contains(it) }
        assertTrue(
            "nothing here swallows a failure, invents an empty result or defaults a missing value away: " +
                "a session that cannot be read is a refusal about its identity, and a row that cannot be " +
                "written is a failure the caller learns about (§28, §33). Found: $offenders",
            offenders.isEmpty()
        )

        val domain = codeLines(domainSources + runtimeSource)
        assertTrue(
            "and no source of this stage tries a value and ignores the attempt",
            domain.none { (_, line) -> line.contains("try {") && line.contains("catch") }
        )
    }

    // ------------------------------------------------------------------ the pure values stay pure

    @Test
    fun theNewDomainValuesSeeNothingButKotlinJavaAndTheDomain() {
        val pureTypes = listOf(
            com.monkfitness.app.domain.workout.SessionRuntimeResult::class.java,
            com.monkfitness.app.domain.workout.SessionRuntimeResult.Success::class.java,
            com.monkfitness.app.domain.workout.SessionRuntimeResult.Refused::class.java,
            com.monkfitness.app.domain.workout.SessionRuntimeResult.Failure::class.java,
            com.monkfitness.app.domain.workout.SessionRefusal::class.java,
            SessionCompletion::class.java,
            com.monkfitness.app.domain.workout.AdaptiveCompletion::class.java,
            com.monkfitness.app.domain.workout.AdaptiveOutcome::class.java
        )

        val offenders = pureTypes.flatMap { type ->
            type.declaredMethods.flatMap { method ->
                (method.parameterTypes.toList() + listOf(method.returnType)).mapNotNull { referenced ->
                    val name = if (referenced.isArray) referenced.componentType.name else referenced.name
                    val allowed = name.startsWith("kotlin.") || name.startsWith("java.") ||
                        name.startsWith("com.monkfitness.app.domain.") || referenced.isPrimitive
                    if (allowed) null else "${type.simpleName}.${method.name} references $name"
                }
            }
        }

        assertTrue(
            "the result envelope, the refusals, the completion and the adaptive seam are pure values: " +
                "their compiled shape may only mention the domain, `kotlin.` and `java.`. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePureHalfLivesWhereTheFoundationPurityScanCanSeeIt() {
        domainSources.forEach { source ->
            assertTrue(
                "$source must stay in a package `ProgramDomainPurityTest` fences against Android, Room, " +
                    "the data layer, mutable state and invented arithmetic",
                File(mainDir, source).isFile
            )
        }
        val puritySource = File("src/test/java/com/monkfitness/app/domain/ProgramDomainPurityTest.kt")
            .let { if (it.isFile) it else File("app/$it") }
        assertTrue("expected the foundation purity test", puritySource.isFile)
        assertTrue(
            "and the packages this stage adds to must be among the ones it reads",
            listOf("\"workout\"", "\"adaptive/decision\"").all { puritySource.readText().contains(it) }
        )
    }

    // ------------------------------------------------------------------ the wiring

    @Test
    fun theCompositionRootWiresTheRuntimeAndNothingInTheUiDoes() {
        val container = File(mainDir, "di/AppContainer.kt")
        assertTrue("the composition root constructs the session runtime (§26)", container.isFile)
        val text = sourceText("di/AppContainer.kt")
        assertEquals(
            "as one graph node, constructed once",
            1,
            occurrences(text, "val sessionRuntime: SessionRuntime = SessionRuntime(")
        )
        assertTrue(
            "and with exactly the collaborators §30 steps 8 and 12 documented: the plan it reads, the " +
                "opportunities, the session graph, the adaptive rows, the two §26 ports, the one calendar " +
                "the adaptive decision's day is read in — the same value the integration receives — and " +
                "the transaction runner. No Program repository, no scheduler, no generator and no " +
                "library is handed to it here",
            text.contains(
                "val sessionRuntime: SessionRuntime = SessionRuntime(\n" +
                    "        planRepository = programPlanRepository,\n" +
                    "        scheduleRepository = programScheduleRepository,\n" +
                    "        sessionRepository = workoutSessionRepository,\n" +
                    "        adaptiveRepository = programAdaptiveRepository,\n" +
                    "        clock = clock,\n" +
                    "        idGenerator = idGenerator,\n" +
                    "        zone = zone,\n" +
                    "        inTransaction = inTransaction\n" +
                    "    )"
            )
        )
    }

    @Test
    fun theRuntimeIsConstructedOverTheRepositoriesOfTheGraphAndNothingElse() {
        val collaborators = listOf(
            ProgramPlanRepository::class.java,
            ProgramScheduleRepository::class.java,
            WorkoutSessionRepository::class.java,
            ProgramAdaptiveRepository::class.java,
            Clock::class.java,
            IdGenerator::class.java
        )
        val parameterTypes = SessionRuntime::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .parameterTypes
            .toList()

        collaborators.forEach { collaborator ->
            assertTrue(
                "${collaborator.simpleName} is constructor-injected: the runtime must not acquire a " +
                    "repository any more than it acquires a database (§26)",
                parameterTypes.contains(collaborator)
            )
        }
    }
}
