package com.monkfitness.app.domain.progress

import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.usecase.ProgramProgressService
import java.io.File
import java.lang.reflect.Modifier
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 9's boundaries, pinned mechanically instead of by convention.
 *
 * The Progress layer is where the app finally *describes* what the user did, and every temptation it has
 * is one import away: the DAO "just to count rows", the Room entity "because the query already returns it",
 * `LocalDate.now()` "because a streak needs today", `ProgramDayType` "because a distribution needs
 * categories", the legacy family tables "because a family distribution needs families", a scalar volume
 * "because a screen wants one number", and the UI "because the shape is finally here". §21, §12, §17, §24,
 * §25 and §30 forbid every one of them. So the rules are asserted against the sources, the compiled shape
 * and the composition root:
 *
 *  * the progress package is pure Kotlin over `java.time` and the domain: no Room, no Android, no data
 *    layer, no UI, no clock;
 *  * the use case runs above the repositories: it reaches no DAO, no entity, no mapper and no Android type,
 *    and it **writes nothing at all** — no insert, no update, no transaction, no id generator;
 *  * no source here names the legacy generation's vocabulary or a later stage's (families, focuses,
 *    `ProgramDayType`), and the reason strings that explain their absence are not mistaken for their use;
 *  * the aggregate is structurally not an entity: it has no identity to carry;
 *  * the calendar derives its counts, so it cannot contradict the history it describes;
 *  * the §21 measure list is pinned field by field, so a "focus distribution" cannot appear silently;
 *  * and nothing above the composition root reaches the layer yet — §30 step 9 lands the contract, and
 *    wiring a screen is a later step.
 */
class ProgressArchitectureTest {

    private val appRoot: File = listOf(
        File("src/main/java/com/monkfitness/app"),
        File("app/src/main/java/com/monkfitness/app")
    ).first { it.isDirectory }

    private val progressSources: List<String> = File(appRoot, "domain/progress")
        .listFiles { file -> file.isFile && file.extension == "kt" }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()

    private val serviceSource = "domain/usecase/ProgramProgressService.kt"

    private val allSources: List<String> = progressSources.map { "domain/progress/$it" } + serviceSource

    // ------------------------------------------------------------------ the scan sees the layer

    @Test
    fun theScanSeesEveryProgressSource() {
        assertTrue(
            "expected the progress package at ${appRoot.absolutePath}/domain/progress, found " +
                "$progressSources",
            progressSources.size >= 12
        )
        listOf(
            "CalendarProgress.kt", "ContextVolume.kt", "DeferredMeasure.kt", "ExercisePerformanceSeries.kt",
            "Frequency.kt", "HistoryItem.kt", "ProgramStreak.kt", "ProgressCalculator.kt", "ProgressFacts.kt",
            "ProgressScope.kt", "ProgressWindow.kt", "SessionDuration.kt", "TrainingProgress.kt"
        ).forEach { name ->
            assertTrue("domain/progress/$name must exist", progressSources.contains(name))
        }
        assertTrue("and the use case must exist", file(serviceSource).isFile)
    }

    // ------------------------------------------------------------------ layering

    @Test
    fun theProgressPackageIsPureKotlinOverTheDomainAndTime() {
        val allowed = listOf("import kotlin", "import java", "import com.monkfitness.app.domain.")
        val offenders = codeLines(progressSources.map { "domain/progress/$it" })
            .filter { (_, line) -> line.startsWith("import ") }
            .filter { (_, line) -> allowed.none { line.startsWith(it) } }

        assertTrue(
            "the pure domain of §25 is JVM Kotlin over java.time: no Room, no Android, no data layer, no " +
                "UI, no clock and no coroutine. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theProgressPackageHoldsNoClockAndNoStorageAndNoPlatformType() {
        val forbidden = listOf(
            "Clock", "Dao", "Entity", "Room.", "androidx", "android.content", "runBlocking", "suspend",
            "Instant.now", "LocalDate.now", "System.currentTimeMillis", "UUID", "Random",
            "ProgramProgressRepository", "WorkoutRepository", "ProgressDao", "UserProgress"
        )
        val offenders = codeLines(progressSources.map { "domain/progress/$it" }).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "a calculation that reads the device's clock or a stored row is not a calculation: the clock " +
                "is read once by the use case and the facts arrive as values (§25, §26). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theServiceRunsAboveTheRepositoriesAndReachesNoDaoNoEntityAndNoAndroidType() {
        val forbidden = listOf(
            "import android", "import androidx", "import com.monkfitness.app.data.local",
            "import com.monkfitness.app.data.model", "import com.monkfitness.app.data.mapper",
            "import com.monkfitness.app.ui", "import com.monkfitness.app.viewmodel",
            "import com.monkfitness.app.animation", "import com.monkfitness.app.poses",
            "import com.monkfitness.app.R"
        )
        val offenders = codeLines(listOf(serviceSource)).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.startsWith(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the use case runs above the repositories: persistence goes through them and never through a " +
                "DAO, an entity or a mapper (§25). Found: $offenders",
            offenders.isEmpty()
        )

        val dataImports = codeLines(listOf(serviceSource))
            .map { (_, line) -> line }
            .filter { it.startsWith("import com.monkfitness.app.data.") }
        assertTrue(
            "and the only data-layer types it names are the repositories that own the facts: $dataImports",
            dataImports.all { it.startsWith("import com.monkfitness.app.data.repository.") }
        )
    }

    // ------------------------------------------------------------------ it reads and never writes

    @Test
    fun noProgressSourceWritesAnythingAtAll() {
        val writes = listOf(
            "insert", "update", "delete", "save", ".add(", "inTransaction", "withTransaction",
            "IdGenerator", "idGenerator", "mutate"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            writes.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "Progress is a view of facts other layers own: there is no write path here at all, which is " +
                "why the service has no transaction runner and no id generator (§21, §24). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theServiceCollaboratorsAreExactlyTheDocumentedOnes() {
        val constructor = instanceConstructor(ProgramProgressService::class.java)

        assertEquals(
            "the service is handed the three repositories that own the facts, the clock that owns " +
                "\"today\", the zone that owns the calendar and the pure calculator — nothing else, so " +
                "nothing else can be reached from here",
            listOf(
                ProgramRepository::class.java.simpleName,
                ProgramScheduleRepository::class.java.simpleName,
                WorkoutSessionRepository::class.java.simpleName,
                Clock::class.java.simpleName,
                ZoneId::class.java.simpleName,
                ProgressCalculator::class.java.simpleName
            ),
            constructor.parameterTypes.map { it.simpleName }
        )

        val queries = ProgramProgressService::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isStatic(it.modifiers) }
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name.substringBefore('-') }
            .filterNot { it.startsWith("access$") || it.contains("$") }
            .sorted()
        assertEquals(
            "and it offers three queries and the window they default to — no fourth operation, no " +
                "state-changing one, and no \"ProgressState\" holding everything at once (§21)",
            listOf("calendarProgress", "currentWindow", "history", "trainingProgress"),
            queries
        )
    }

    // ------------------------------------------------------------------ vocabulary that must not appear

    @Test
    fun noProgressSourceUsesTheLegacyOrLaterStageVocabulary() {
        val forbidden = listOf(
            // the family generation §30 step 15 retires
            "FamilyProgressionState", "family_progression_state", "familyProgressionStates",
            "FamilyProgression", "familyId", "family",
            // the focus planner §30 step 10 owns
            "FocusPlanner", "Focus", "focus", "ProgramDayType", "secondaryFocus", "primaryFocus",
            // the shipped legacy progress tables
            "ProgressDao", "UserProgress", "ProgramDayState", "cycleNumber", "legacyRevision",
            // the adaptive engine §30 steps 11–12 own
            "AdaptivePolicy", "AdaptiveProgramEngine", "ProgressionResolver", "AdaptiveDecision",
            "adaptive_decision_record", "adaptive_adjustment"
        )
        val offenders = identifiers(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "Progress must stay at the target Program boundary: the legacy family tables are a different " +
                "generation, a ProgramDayType is the kind of day rather than a focus, and the adaptive " +
                "engine's types belong to a later stage — each one is reported as deferred instead of " +
                "being read (§8, §10, §21, §23, §30). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theReasonsTheMeasuresAreDeferredAreNotMistakenForTheirUse() {
        val code = identifiers(allSources).map { (_, line) -> line }

        assertTrue(
            "the rules above scan identifiers and declarations, not the prose and the messages that " +
                "explain what is deliberately not read — those messages are the record of the deferral " +
                "and must be able to name it (a scan that stripped them would be checking nothing)",
            text(serviceSource).contains("no policy, no generator") ||
                text("domain/progress/ProgressCalculator.kt").contains("Focus Planner")
        )
        assertTrue(
            "and the deferred vocabulary really is named in the shipped messages: " +
                ProgressCalculator.DEFERRED_MEASURES.joinToString { it.measure.name },
            code.none { it.contains("FocusPlanner") }
        )
    }

    // ------------------------------------------------------------------ no invented measures

    @Test
    fun noProgressSourceDerivesAUniversalScalarOrACoefficient() {
        val forbidden = listOf(
            "totalVolume", "volumeScore", "loadScore", "totalLoad", "overallScore", "workload",
            "coefficient", "multiplier", "scalingFactor", "oneRepMax", "e1rm", "performanceScore"
        )
        val offenders = identifiers(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "§17: there is no universal scalar volume and no conversion between exercises. Found: $offenders",
            offenders.isEmpty()
        )

        assertEquals(
            "a volume carries one context, one set count and one unit — no third quantity in which two " +
                "contexts could be added, and no `total`",
            listOf("context", "performedSets", "repetitions", "seconds"),
            declaredFields(ContextVolume::class.java)
        )
    }

    @Test
    fun theOnlyFloatingPointInTheLayerIsTwoRatiosOfCountedFacts() {
        val withDouble = progressSources
            .map { "domain/progress/$it" }
            .filter { name -> text(name).contains("Double") }

        assertEquals(
            "two quantities in this layer are mathematically fractional — the mean duration of the " +
                "measured attempts and the workouts-per-week rate — and both are ratios of counted facts, " +
                "not a score or a load. A third one appearing is a review event, so the set is pinned",
            listOf("domain/progress/Frequency.kt", "domain/progress/SessionDuration.kt"),
            withDouble
        )
        assertTrue(
            "and no float, no coefficient and no randomness appears anywhere in the layer",
            progressSources.none { name ->
                val source = text("domain/progress/$name")
                source.contains("Float") || source.contains("Random") ||
                    source.contains("coefficient", ignoreCase = true)
            }
        )
    }

    // ------------------------------------------------------------------ the aggregate is not an entity

    @Test
    fun theAggregateIsStructurallyNotAnEntity() {
        assertEquals(
            "the scope vocabulary is exactly two cases: one Program, or the aggregate",
            listOf("AllPrograms", "OfProgram"),
            ProgressScope::class.java.declaredClasses.map { it.simpleName }.sorted()
        )
        assertEquals(
            "the interface itself carries no identity, so nothing can be addressed by a scope",
            emptyList<String>(),
            ProgressScope::class.java.declaredMethods.map { it.name }
        )

        val aggregate = ProgressScope.AllPrograms::class.java
        assertTrue(
            "the aggregate is a singleton object: it is a value, not a record that could be stored",
            aggregate.declaredFields.any { Modifier.isStatic(it.modifiers) && it.name == "INSTANCE" }
        )
        assertTrue(
            "and it has no `ProgramId` to give, which is what 'an aggregation view, not an entity' means " +
                "in code",
            aggregate.declaredMethods.none { it.name.startsWith("getProgramId") } &&
                aggregate.declaredMethods.none { it.name.startsWith("component") }
        )
        assertTrue(
            "a Program scope, by contrast, does name one Program",
            ProgressScope.OfProgram::class.java.declaredMethods.any { it.name.startsWith("getProgramId") }
        )
    }

    // ------------------------------------------------------------------ the shapes are pinned

    @Test
    fun theCalendarDerivesItsCountsSoItCannotContradictTheHistoryItDescribes() {
        assertEquals(
            "a calendar is a scope and the opportunities: the four counts are derived properties, so " +
                "there is no second place for a number to come from and no way to hold a calendar of " +
                "three completed days and a count that says four (§21)",
            listOf("entries", "scope"),
            declaredFields(CalendarProgress::class.java)
        )
        assertEquals(
            "the same holds of the frequency, whose two counts come from the facts it was computed over",
            listOf("completedSessions", "trainingDays", "window"),
            declaredFields(TrainingFrequency::class.java)
        )
    }

    @Test
    fun theTrainingContractIsPinnedFieldByFieldSoAMeasureCannotAppearSilently() {
        assertEquals(
            "§21's first implementation, named one field at a time: frequency, average session duration, " +
                "comparable performance, per-context volume, streak and the deferred list. A seventh " +
                "field — a focus distribution, a family breakdown, a Program PR, a single score — would " +
                "be an invented measure, so the set is pinned",
            listOf(
                "averageSessionDuration", "deferred", "frequency", "scope", "series", "streaks", "volumes",
                "window"
            ),
            declaredFields(TrainingProgress::class.java)
        )
        assertEquals(
            "and the deferred vocabulary is exactly the three measures the target facts cannot answer",
            listOf("FAMILY_DISTRIBUTION", "FOCUS_DISTRIBUTION", "PROGRAM_PR"),
            ProgressMeasure.entries.map { it.name }.sorted()
        )
        assertEquals(
            "a history entry reports exposure and identity, and no derived score",
            listOf(
                "duration", "exposedExercises", "finishedAt", "performedSets", "plannedFor", "programId",
                "sessionId", "skippedExercises", "slotId", "startedAt", "status"
            ),
            declaredFields(HistoryItem::class.java)
        )
    }

    @Test
    fun theCalculatorIsPureAndItsOnlyCollaboratorIsTheZone() {
        assertEquals(
            "the calculation holds a zone and nothing else: no clock, no repository, no database and no " +
                "state, which is what makes it a function of its facts (§25, §26)",
            listOf(ZoneId::class.java.simpleName),
            instanceConstructor(ProgressCalculator::class.java).parameterTypes.map { it.simpleName }
        )
        assertEquals(
            "and it offers the three aggregations of §21 and nothing else",
            listOf("calendar", "history", "training"),
            ProgressCalculator::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic || it.isBridge }
                .filterNot { it.name.contains("$") }
                .map { it.name.substringBefore('-') }
                .sorted()
        )
    }

    // ------------------------------------------------------------------ nothing reaches it yet

    @Test
    fun nothingAboveTheCompositionRootReachesTheProgressLayerYet() {
        val roots = listOf(File(appRoot, "ui"), File(appRoot, "viewmodel")).filter { it.isDirectory }
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { source ->
                val text = text(source.relativeTo(appRoot).path.replace('\\', '/'))
                listOf("ProgramProgressService", "domain.progress", "ProgressCalculator", "ProgressScope")
                    .filter { text.contains(it) }
                    .map { "${source.name}: $it" }
            }.toList()
        }

        assertTrue(
            "§30 step 9 completes the domain/use-case/data contracts; the screens are a later step, and " +
                "wiring one is a decision about presentation rather than about a measure. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theContainerWiresTheLayerAsOneGraphNodeWithTheDocumentedCollaborators() {
        val container = text("di/AppContainer.kt")

        assertEquals(
            "the composition root names the layer in exactly three places — the import, the property's " +
                "type and the construction — so a measure cannot ride along with the wiring unnoticed",
            3,
            occurrences(container, "ProgramProgressService")
        )
        assertEquals("and it is constructed once", 1, occurrences(container, "ProgramProgressService("))
        assertTrue(
            "with exactly the collaborators the layer documents — the three repositories that own the " +
                "facts and the clock that owns \"today\" — and no zone or calculator override: the " +
                "defaults are the layer's own (§26)",
            container.contains(
                "val programProgressService: ProgramProgressService = ProgramProgressService(\n" +
                    "        programRepository = programRepository,\n" +
                    "        scheduleRepository = programScheduleRepository,\n" +
                    "        sessionRepository = workoutSessionRepository,\n" +
                    "        clock = clock\n" +
                    "    )"
            )
        )
        assertEquals(
            "the placeholder repository this stage deliberately does not extend or read is still wired " +
                "and still untouched",
            1,
            occurrences(container, "ProgramProgressRepository(")
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun file(relativePath: String): File = File(appRoot, relativePath)

    /** A source's text with comments removed — the rules below are rules about code and about prose. */
    private fun text(relativePath: String): String = file(relativePath).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    /**
     * A source's **identifiers and declarations**: comments, KDoc, string literals and character literals
     * removed, one entry per non-empty line.
     *
     * The distinction matters here more than in any other guard in this repository: this layer's whole
     * point is that it does *not* read families, focuses or `ProgramDayType`, and it says so in the KDoc and
     * in the messages it ships — a scan that read those would fail on the explanation of the rule it is
     * checking, and a scan that skipped messages would prove nothing. What must never name a legacy or
     * future vocabulary is the *code*.
     */
    private fun identifiers(sources: List<String>): List<Pair<String, String>> = codeLines(sources)
        .map { (source, line) -> source to stripLiterals(line) }
        .filter { (_, line) -> line.isNotEmpty() }

    private fun stripLiterals(line: String): String = line
        .replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\"")
        .replace(Regex("""'(?:\\.|[^'\\])*'"""), "''")

    private fun codeLines(sources: List<String>): List<Pair<String, String>> = sources.flatMap { source ->
        val name = source.substringAfterLast("/")
        text(source).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { name to it }
    }

    private fun declaredFields(type: Class<*>): List<String> = type.declaredFields
        .filterNot { field -> field.name.startsWith("$") || Modifier.isStatic(field.modifiers) }
        .map { it.name }
        .sorted()

    /**
     * The class's own instance constructor: Kotlin adds a synthetic bridge for defaulted parameters (and a
     * no-argument one when every parameter has a default), so the real constructor is the shortest
     * non-synthetic one, and the bridge is asserted not to be what is under test.
     */
    private fun instanceConstructor(type: Class<*>): java.lang.reflect.Constructor<*> {
        val candidates = type.declaredConstructors
            .filterNot { it.isSynthetic }
            .filterNot { own -> own.parameterTypes.any { it.simpleName.contains("DefaultConstructorMarker") } }
        assertTrue(
            "expected ${type.simpleName} to declare one usable constructor, found " +
                "${candidates.map { own -> own.parameterTypes.map { it.simpleName } }}",
            candidates.isNotEmpty()
        )
        return candidates.maxByOrNull { own -> own.parameterCount }!!
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
