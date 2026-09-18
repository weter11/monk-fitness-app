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
 * §26's rules are the kind that a reviewer reads once and a later change erodes: *"AppContainer is
 * composition root, not Service Locator"*, *"ViewModels never create DB/repositories"*, *"Database
 * instance is created once and shared"*. Prose in a KDoc does not keep any of them true, so each one
 * is asserted mechanically, on code with its comments stripped — this file's own KDoc has to be able
 * to *name* `AppDatabase.getDatabase` and `Room.databaseBuilder` while explaining why production must
 * not call them twice.
 *
 * Every rule below is a way the wiring can be lost while all of the behavioural suites still pass:
 *
 *  * the database acquired in a second place (`AppDatabase.getDatabase` from a repository, a view
 *    model, or a new screen's state holder), which is how "created once and shared" becomes "usually
 *    the same instance";
 *  * a second `Room.databaseBuilder` — a genuinely different database, with its own connection, its
 *    own in-memory caches and its own view of the same file;
 *  * the application exposing the container through a `get()` accessor, so every read composes a fresh
 *    graph and re-asks for the database;
 *  * a Program System repository constructed outside the composition root — a service locator by
 *    another name, and a second owner of the object that must have exactly one;
 *  * a DAO taken from the database somewhere else, which is how a second reader of a table appears;
 *  * a view model or a screen reaching into the data layer or into the composition root at all;
 *  * a decision creeping into the wiring: a policy, a scheduler, a generator, a calendar or a clock
 *    read inside the container;
 *  * a production call that overrides the container's transaction runner, or a port implemented
 *    somewhere other than the two files that own the device's time and randomness.
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

    /** The fifteen target tables' accessors, as `AppDatabase` declares them. */
    private val targetAccessors = listOf(
        "programDao", "appStateDao", "programRevisionDao", "programDayDao", "programExerciseDao",
        "programWorkoutSlotDao", "workoutSessionDao", "sessionSnapshotDao",
        "sessionSnapshotExerciseDao", "sessionExerciseDao", "programSetLogDao", "programPauseDao",
        "programFamilyProgressionStateDao", "programAdaptiveDecisionDao", "adaptiveAdjustmentDao"
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

    // ---- one database, acquired in one place ------------------------------------------------------

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

    // ---- one place a repository is constructed -----------------------------------------------------

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
            "the composition root takes every DAO of the Program System graph from the one database — " +
                "one accessor call per target table, and nowhere else in production",
            17,
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
    fun theShippedStageOneConstructionSitesAreUnchangedAndClosed() {
        assertEquals(
            "the shipped Stage-1 adaptive adapter has exactly four construction sites: the composition " +
                "root that wires both generations, and the two session-path factories that predate this " +
                "stage and keep their own instances (§30 step 15 retires them)",
            listOf(
                "data/repository/AdaptiveSessionDecisionRecorder.kt",
                "data/repository/SessionAdaptivePlanReader.kt",
                "di/AppContainer.kt"
            ),
            filesConstructing("AdaptiveRepository")
        )

        val viewModel = codeOf("viewmodel/MainViewModel.kt")
        assertEquals(
            "the view model's shipped constructions are pinned: this stage changes one line of the " +
                "view model and must not grow it",
            1,
            occurrences(viewModel, "WorkoutRepository(")
        )
        assertEquals(1, occurrences(viewModel, "SessionAdaptivePlanReader.of("))
        assertEquals(1, occurrences(viewModel, "AdaptiveSessionDecisionRecorder.of("))
    }

    // ---- nothing above the composition root reaches down --------------------------------------------

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
            "and the one reach that remains is the shipped Stage-1 path taking the shared database " +
                "from the application — pinned to that single line, which is exactly what §30 step 15 " +
                "will delete",
            listOf("viewmodel/MainViewModel.kt"),
            filesContaining("container.database")
        )
        assertEquals(
            "the view model takes the database, not the graph: it never names the composition root's type",
            1,
            occurrences(codeOf("viewmodel/MainViewModel.kt"), "container.database")
        )
    }

    // ---- wiring is not behaviour --------------------------------------------------------------------

    @Test
    fun theCompositionRootDecidesNothing() {
        val text = codeOf("di/AppContainer.kt")

        val forbidden = listOf(
            "WorkoutGenerator", "AdaptivePolicy", "AdaptiveProgramEngine", "ProgressionResolver",
            "FocusPlanner", "ProgramCalendar", "Scheduler", "SettingsManager", "NotificationScheduler",
            "System.currentTimeMillis", "Instant.now", "LocalDate", "Random", "UUID", "runBlocking",
            "lifecycleStatus", "ProgramMaintenance", "ProgressDao", "Room."
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
