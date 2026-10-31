package com.monkfitness.app.di

import android.content.Context
import com.monkfitness.app.data.local.AppDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition root's fences, checked against the sources themselves.
 *
 * The rules are the kind a reviewer reads once and a later change erodes: *"AppContainer is composition
 * root, not Service Locator"*, *"ViewModels never create DB/repositories"*, *"Database instance is
 * created once and shared"*. Prose in a KDoc does not keep any of them true, so each is asserted
 * mechanically, on code with its comments stripped — this file's own KDoc has to be able to *name*
 * `AppDatabase.getDatabase` and `Room.databaseBuilder` while explaining why production must not call
 * them twice.
 *
 * Every rule below is a way the wiring can be lost while all of the behavioural suites still pass: a
 * second database acquisition, a second `Room.databaseBuilder` (a genuinely different database over the
 * same file), the container exposed through a `get()` accessor, a repository constructed outside the
 * composition root, a DAO taken somewhere else, a view model or screen reaching into the data layer, a
 * decision creeping into the wiring, or a production call that overrides the transaction runner.
 */
class CompositionRootArchitectureTest {

    private val appRoot: File = listOf(
        File("src/main/java/com/monkfitness/app"),
        File("app/src/main/java/com/monkfitness/app")
    ).first { it.isDirectory }

    private val targetRepositories = listOf(
        "ProgramRepository", "ProgramPlanRepository", "ProgramScheduleRepository",
        "WorkoutSessionRepository", "ProgramProgressRepository", "ProgramAdaptiveRepository",
        "AppStateRepository"
    )

    /** The target tables' accessors, as `AppDatabase` declares them. */
    private val targetAccessors = listOf(
        "programDao", "appStateDao", "programRevisionDao", "programDayDao", "programExerciseDao",
        "programWorkoutSlotDao", "programTargetOccurrenceDao",
        // A revision's explicitly stated target schedule source is taken once, in the composition
        // root, like every other node — which keeps it from becoming a second reader acquired
        // somewhere convenient.
        "programTargetScheduleSourceDao",
        "workoutSessionDao", "sessionSnapshotDao",
        "sessionSnapshotExerciseDao", "sessionExerciseDao", "programSetLogDao", "programPauseDao",
        "programFamilyProgressionStateDao", "programAdaptiveDecisionDao", "adaptiveAdjustmentDao",
        // The progression relation catalogue's DAO joins the graph now that a provider is wired over
        // it. Listed so the "taken once, in the composition root, nowhere else" rule below applies to
        // it exactly as to every other node — the count alone would not catch a second acquisition
        // site.
        "progressionRelationVariantDao"
    )

    private fun relative(file: File): String = file.relativeTo(appRoot).path.replace('\\', '/')

    private fun allSources(): List<File> = appRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun codeOf(relativePath: String): String = code(file(relativePath).readText())

    private fun file(relativePath: String): File {
        val candidate = File(appRoot, relativePath)
        assertTrue("expected $relativePath at ${candidate.absolutePath}", candidate.isFile)
        return candidate
    }

    /** Every main source whose *code* contains [token], as repository-relative paths. */
    private fun filesContaining(token: String): List<String> =
        allSources().filter { code(it.readText()).contains(token) }.map { relative(it) }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    /**
     * Where `name` is *constructed* in [text]: an occurrence of `name(` that is neither the class
     * declaration nor part of a longer identifier. A declaration (`class ProgramRepository(`) is the
     * file that defines the type and a longer name (`ProgramAdaptiveRepository(` for the name
     * `AdaptiveRepository`) is a different class — a construction is a file that depends on this one.
     */
    private fun constructionSites(text: String, name: String): Int {
        var count = 0
        var index = text.indexOf("$name(")
        while (index >= 0) {
            val before = text.substring(maxOf(0, index - 8), index)
            val insideALongerName =
                before.isNotEmpty() && (before.last().isLetterOrDigit() || before.last() == '_')
            if (!insideALongerName && !before.contains("class ")) count++
            index = text.indexOf("$name(", index + 1)
        }
        return count
    }

    private fun filesConstructing(name: String): List<String> =
        allSources().filter { constructionSites(code(it.readText()), name) > 0 }.map { relative(it) }

    @Test
    fun onlyTheCompositionRootAcquiresTheAppsDatabase() {
        assertEquals(
            "the app's one database is acquired in exactly one place (§26): every other acquisition " +
                "is a second answer to 'which database does this use'",
            listOf("di/AppContainer.kt"),
            filesContaining("AppDatabase.getDatabase(")
        )
        assertEquals(
            "and it is acquired once per container — twice in the same file is a second instance " +
                "waiting for the singleton to be refactored away",
            1,
            occurrences(codeOf("di/AppContainer.kt"), "AppDatabase.getDatabase(")
        )
        assertEquals(
            "Room's builder appears only in the database class that owns the singleton: a second " +
                "`databaseBuilder` call is a genuinely different database over the same file, with its " +
                "own connection and its own caches",
            listOf("data/local/AppDatabase.kt"),
            filesContaining("Room.databaseBuilder(")
        )
        assertEquals(
            "and the singleton field it caches into belongs to that file too",
            listOf("data/local/AppDatabase.kt"),
            filesContaining("INSTANCE")
        )
    }

    @Test
    fun theDatabaseIsStillCreatedOnceAndSharedByItsOwnSingleton() {
        val text = codeOf("data/local/AppDatabase.kt")

        assertTrue(
            "the database is created once per process: `INSTANCE ?: synchronized(this)` is what makes " +
                "a second call return the first instance instead of opening another database",
            text.contains("INSTANCE ?: synchronized(this)")
        )
        assertTrue("the created instance is stored", text.contains("INSTANCE = instance"))
        assertEquals(
            "exactly one builder — one `Room.databaseBuilder`, registered migrations and all",
            1,
            occurrences(text, "Room.databaseBuilder(")
        )

        val factories = AppDatabase.Companion::class.java.declaredMethods
            .filter { it.returnType == AppDatabase::class.java }
        assertEquals(
            "the companion offers exactly one way to get the database, and nothing else returning it",
            listOf("getDatabase"),
            factories.map { it.name }
        )
        assertEquals(
            "and that factory takes the context, not a name or a configuration: nothing outside can " +
                "ask for a database of its own",
            listOf(Context::class.java),
            factories.single().parameterTypes.toList()
        )
    }

    @Test
    fun theApplicationHoldsExactlyOneCompositionRoot() {
        val text = codeOf("MonkFitnessApplication.kt")

        assertEquals(
            "the container is created in exactly one place — the application",
            1,
            occurrences(text, "AppContainer.create(")
        )
        assertTrue(
            "the container is a stored value: a `get()` accessor would compose a fresh graph, and " +
                "re-ask for the database, on every read",
            text.contains("val container: AppContainer by lazy {") && "get() =" !in text
        )
        assertTrue(
            "and it is the application the manifest names, so the process really has one (§26's " +
                "'Application → AppContainer' is an arrow, not a suggestion)",
            manifest().contains("android:name=\".MonkFitnessApplication\"")
        )
    }

    @Test
    fun onlyTheCompositionRootConstructsAProgramSystemRepository() {
        targetRepositories.forEach { name ->
            assertEquals(
                "$name is constructed in the composition root and nowhere else: a repository built " +
                    "outside it is a second owner of an object that must have exactly one",
                listOf("di/AppContainer.kt"),
                filesConstructing(name)
            )
            assertEquals(
                "and it is constructed once: two constructions of $name in the container would mean " +
                    "two instances of one repository with different collaborators",
                1,
                constructionSites(codeOf("di/AppContainer.kt"), name)
            )
        }
    }

    @Test
    fun onlyTheCompositionRootTakesDaosFromTheDatabase() {
        val accessor = Regex("""database\.\w+Dao\(\)""")

        assertEquals(
            "the composition root takes every DAO of the target graph from the one database — one " +
                "accessor call per target table — plus §16's reset. §30 step 15 changed the count from " +
                "17 to 16 by removing the two Stage-1 handles (`familyProgressionStateDao`, " +
                "`adaptiveDecisionHistoryDao`) and adding the reset's own, §30 step 14 raised it " +
                "again to 17 by adding the target occurrence's own accessor, Stage 18 raised it " +
                "to 18 by adding the explicit target schedule source's own accessor, and P32 raised " +
                "it to 19 by taking the progression relation catalogue's own accessor — which P31 " +
                "created and deliberately left unread until a stage had content to serve through it. " +
                "No accessor's count may fall without its own gate changing: nowhere else " +
                "in production may take one",
            19,
            accessor.findAll(codeOf("di/AppContainer.kt")).count()
        )

        targetAccessors.forEach { accessorName ->
            assertEquals(
                "`$accessorName` is taken from the database in the composition root and nowhere else: a " +
                    "target DAO acquired anywhere else is a second reader nobody wired",
                listOf("di/AppContainer.kt"),
                filesContaining("database.$accessorName()")
            )
            assertEquals(
                "and it is taken once",
                1,
                occurrences(codeOf("di/AppContainer.kt"), "database.$accessorName()")
            )
        }
    }

    @Test
    fun theShippedStageOneGenerationHasNoConstructionSiteLeft() {
        // The class itself is gone, so no source can construct a Stage-1 adapter: the closed list is
        // the empty one and the claim is stronger than "no new sites".
        assertEquals(
            "nothing constructs `AdaptiveRepository` any more: the Stage-1 generation is retired, not " +
                "merely unwired",
            emptyList<String>(),
            filesConstructing("AdaptiveRepository")
        )
        assertEquals(
            "and the class does not exist to be constructed",
            emptyList<String>(),
            filesContaining("class AdaptiveRepository(")
        )

        // The view model's shipped constructions are gone with the session they belonged to, so the pin
        // is that they cannot come back — a `WorkoutRepository(db)` line in the view model was the
        // violation this whole stage closes.
        val viewModel = codeOf("viewmodel/MainViewModel.kt")
        for (retired in listOf(
            "WorkoutRepository(", "SessionAdaptivePlanReader.of(", "AdaptiveSessionDecisionRecorder.of(",
            "SessionAdaptivePlanReader(", "AdaptiveSessionDecisionRecorder(", "AdaptiveWorkoutIntegration("
        )) {
            assertEquals(
                "the view model must not construct `$retired` (§26: it receives what the composition " +
                    "root built)",
                0,
                occurrences(viewModel, retired)
            )
        }
    }

    @Test
    fun noViewModelOrUiSourceReachesTheDatabaseOrTheProgramSystemGraph() {
        val forbidden = listOf(
            "AppDatabase", "Room.databaseBuilder", "getDatabase(", "INSTANCE", "AppContainer"
        ) + targetRepositories + listOf("ProgramDao(", "AdaptiveAdjustmentDao(")

        val offenders = listOf(File(appRoot, "viewmodel"), File(appRoot, "ui"))
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { source ->
                    val text = code(source.readText())
                    forbidden.filter { text.contains(it) }
                        .map { "${relative(source)}: $it" }
                }.toList()
            }

        assertTrue(
            "a view model or a screen constructs no database, no DAO and no Program System repository " +
                "(§26, §33 'never let a ViewModel write a DAO directly'): $offenders",
            offenders.isEmpty()
        )

        assertEquals(
            "and the reaches that remain are the retained global stores the view model owns, taken from " +
                "the shared database the application holds. §30 step 15 deleted the legacy " +
                "`WorkoutRepository(db)` line this used to pin and replaced it with the two stores that " +
                "have no Program in them — nutrition and the posture/mobility track",
            listOf("viewmodel/MainViewModel.kt"),
            filesContaining("container.database")
        )
        assertEquals(
            "the view model takes the database, not the graph: it never names the composition root's " +
                "type, and it reaches the database for exactly its two retained global stores. A third " +
                "would be a third store appearing on the path this stage just cleared",
            2,
            occurrences(codeOf("viewmodel/MainViewModel.kt"), "container.database")
        )
        // The other direction of the same rule: none of those reaches may be a Program store.
        val viewModel = codeOf("viewmodel/MainViewModel.kt")
        for (targetDao in listOf(
            "programDao()", "programRevisionDao()", "programDayDao()", "programExerciseDao()",
            "programWorkoutSlotDao()", "workoutSessionDao()", "sessionSnapshotDao()",
            "programSetLogDao()", "programAdaptiveDecisionDao()", "adaptiveAdjustmentDao()",
            "appStateDao()", "maintenanceDao()"
        )) {
            assertEquals(
                "the view model must not reach `$targetDao`: target Program work goes through the " +
                    "services and controllers the container hands it",
                0,
                occurrences(viewModel, targetDao)
            )
        }
    }

    @Test
    fun theCompositionRootDecidesNothing() {
        val text = codeOf("di/AppContainer.kt")

        val forbidden = listOf(
            "WorkoutGenerator", "AdaptivePolicy", "AdaptiveProgramEngine", "ProgressionResolver",
            "FocusPlanner", "ProgramCalendar", "SettingsManager", "NotificationScheduler",
            "System.currentTimeMillis", "Instant.now", "LocalDate", "Random", "UUID", "runBlocking",
            "lifecycleStatus", "ProgramMaintenance", "ProgressDao", "Room.",
            // Forbidding the token "Scheduler" outright no longer works: the Scheduler now exists and
            // *wiring it is not deciding with it*. The token is therefore replaced by the vocabulary of
            // the decision it must not contain — the planner, its request, its plan, its window, its
            // horizon, its pauses, the amount-free facts a slot is made of — which is stricter about
            // what the container may say about scheduling than a name match ever was. What the
            // container *may* do is pinned immediately below, exactly.
            "SlotPlanner", "SlotPlan", "ScheduleRequest", "ScheduleWindow", "PausedInterval",
            "SlotIdSource", "PLANNING_HORIZON_DAYS", "plannedFor", "asOf", "DayOfWeek", "superseded"
        )
        forbidden.forEach { token ->
            assertTrue(
                "wiring a dependency is not deciding one (§33): the composition root must not contain " +
                    "'$token'",
                !text.contains(token)
            )
        }

        assertTrue(
            "and the repositories it wires are the ones §30 step 3 landed, constructed with the " +
                "collaborators that stage documented — nothing is decorated, wrapped or substituted",
            targetRepositories.all { text.contains("$it(") }
        )

        assertEquals(
            "the Scheduler is a graph node and nothing beside it: the container names it in exactly " +
                "six places of code — the property's name, its type, its construction, §30 step 7's " +
                "wiring mentions, and the consumer hand-over lines — so a scheduling decision cannot " +
                "ride along with the wiring unnoticed. Revised (not relaxed) by the creation " +
                "remediation: the sixth place is the Save orchestration's hand-over line " +
                "(`scheduler = programScheduler,`), pinned as the second consumer below.",
            7,
            occurrences(text, "Scheduler")
        )
        assertEquals(
            "and the consumers are stated as one line each: the Scheduler is handed to the transfer " +
                "stage (§30 step 13) and to the Save orchestration (the creation remediation — §27's " +
                "creation unit and reconciliation are composed there, not in a screen) — exactly twice, " +
                "and to nothing else",
            3,
            occurrences(text, "scheduler = programScheduler,")
        )
        assertEquals(
            "it is constructed once, like every other node",
            1,
            constructionSites(text, "ProgramScheduler")
        )
        assertTrue(
            "and with exactly the collaborators §30 step 7 documented: the plan it reads, the schedule " +
                "it writes, the two §26 ports and the transaction runner — no session repository, no " +
                "adaptive port, no generator and no library is handed to it here",
            text.contains(
                "val programScheduler: ProgramScheduler = ProgramScheduler(\n" +
                    "        programRepository = programRepository,\n" +
                    "        planRepository = programPlanRepository,\n" +
                    "        scheduleRepository = programScheduleRepository,\n" +
                    "        clock = clock,\n" +
                    "        idGenerator = idGenerator,\n" +
                    "        zone = zone,\n" +
                    "        inTransaction = inTransaction\n" +
                    "    )"
            )
        )
    }

    @Test
    fun theTransactionRunnerIsTheDatabasesOwnAndOnlyThePortsReadTheDevice() {
        val container = codeOf("di/AppContainer.kt")

        assertTrue(
            "the transaction every repository of the graph runs in is the database's own " +
                "`withTransaction`, which is the wired production behaviour and not a caller's choice",
            container.contains("database.withTransaction { block() }")
        )
        assertTrue(
            "production composes the container over the singleton database, the clock and the " +
                "generator — with no fourth argument, i.e. with the database's own transaction",
            Regex(
                """AppContainer\(\s*AppDatabase\.getDatabase\(context\)\s*,\s*clock\s*,\s*idGenerator\s*\)"""
            ).containsMatchIn(container)
        )

        val scopedSources = allSources().filter { source ->
            val path = relative(source)
            path.startsWith("di/") || path.startsWith("data/mapper/") ||
                path.substringAfterLast('/').startsWith("Program")
        }
        listOf("System.currentTimeMillis", "Instant.now(", "UUID.randomUUID()").forEach { token ->
            val readers = scopedSources.filter { code(it.readText()).contains(token) }.map { relative(it) }
            assertTrue(
                "the device's time and its randomness enter the Program System through the two port " +
                    "files alone (§26: 'Clock and ID generation are injectable'), but '$token' is read " +
                    "in $readers",
                readers.all { it == "di/Clock.kt" || it == "di/IdGenerator.kt" }
            )
        }
        assertEquals(
            "every one of those tokens is read somewhere, so the rule above is not vacuous",
            2,
            scopedSources.count { source ->
                listOf("System.currentTimeMillis", "Instant.now(", "UUID.randomUUID()")
                    .any { code(source.readText()).contains(it) }
            }
        )
    }

    @Test
    fun theCompositionPackageHoldsTheCompositionRootAndItsTwoPorts() {
        val files = File(appRoot, "di").listFiles { candidate -> candidate.extension == "kt" }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        assertEquals(
            "the composition package is the root and the two ports it injects, and nothing else: a " +
                "fourth file here is a fourth responsibility in the layer that is supposed to have one",
            listOf("AppContainer.kt", "Clock.kt", "IdGenerator.kt"),
            files
        )
    }

    private fun manifest(): String {
        val candidate = generateSequence(appRoot) { it.parentFile }
            .map { File(it, "AndroidManifest.xml") }
            .firstOrNull { it.isFile }
        assertTrue("expected AndroidManifest.xml above ${appRoot.absolutePath}", candidate != null)
        return candidate!!.readText()
    }
}
