package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The mechanical boundaries of §30 step 16, checked against the sources themselves.
 *
 * Almost everything this phase must not do is a prohibition: no repository, no DAO, no clock, no
 * planner, no `ProgramDay`, no parsed occurrence key, no `ActualResult` — and, above all, no *second*
 * place that decides an occurrence's execution. A prohibition living only in a KDoc erodes the first
 * time a convenient import appears, so each is asserted here as a **token or a shape in real
 * code**, with comments stripped first, which is what lets the policy explain the absences in prose
 * without tripping the rules about them.
 *
 * The ten gates, in the order the phase states them:
 *
 * ```text
 *  1. the policy is a pure value in the target domain package
 *  2. it has no repository dependency
 *  3. it has no DAO / Room dependency
 *  4. it has no clock, IdGenerator or randomness
 *  5. it depends on no planner / resolver / composer / reconciler / presenter / materializer /
 *     persister / legacy scheduler
 *  6. it does not read ProgramDay or the current ProgramRevision
 *  7. it does not parse the occurrence key
 *  8. it constructs no ActualResult
 *  9. it introduces no execution precedence outside the single policy owner
 * 10. it is pure over the Phase 15 record
 * ```
 */
class TargetOccurrenceExecutionPolicyArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")

    /** The policy and the decision type — the whole of this phase's production code. */
    private val policyFile = File(targetDir, "TargetOccurrenceExecutionPolicy.kt")

    /** The Phase 15 record this phase consumes, unchanged. */
    private val recordFile = File(targetDir, "TargetOccurrenceExecutionRead.kt")

    private fun code(source: File): String = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    private fun codeLines(source: File): List<String> = code(source)
        .lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }

    private fun offenders(source: File, tokens: List<String>): List<String> =
        codeLines(source).filter { line -> tokens.any { token -> line.contains(token) } }
            .map { it }

    /**
     * The same sweep, matching **whole tokens** with `_` counted as a word character.
     *
     * A plain `contains` scan cannot separate two of this phase's own names from a forbidden one:
     * `OccurrenceExecution` occurs inside `TargetOccurrenceExecutionPolicy`, and `Record` inside
     * `TargetOccurrenceExecutionRecord`. A gate that fired on either would have to be weakened to
     * pass, which is how a gate stops being a gate.
     */
    private fun wholeTokenOffenders(source: File, tokens: List<String>): List<String> {
        val pattern = tokens.joinToString(
            prefix = "(?<![A-Za-z0-9_])(?:",
            separator = "|",
            postfix = ")(?![A-Za-z0-9_])"
        )
        val regex = Regex(pattern)
        return codeLines(source).filter { line -> regex.containsMatchIn(line) }
    }

    // ---- 1. it is a pure value in the target domain package ---------------------------------------

    @Test
    fun thePolicyLivesInThePureTargetPackageAndIsThePhasesWholeProductionCode() {
        assertTrue(
            "a renamed or moved policy fails here rather than silently escaping every gate below",
            policyFile.isFile
        )
        assertEquals(
            "and it sits in the target domain package, beside the record it consumes",
            "target",
            policyFile.parentFile!!.name
        )
        assertEquals(
            "the file declares exactly the two things this phase introduces: the decision value " +
                "and the policy that owns the precedence",
            listOf("TargetOccurrenceExecutionDecision", "TargetOccurrenceExecutionPolicy"),
            Regex("(?:data class|object) (\\w+)")
                .findAll(code(policyFile)).map { it.groupValues[1] }.toList()
        )
    }

    // ---- 2. no repository dependency -----------------------------------------------------------------

    @Test
    fun thePolicyHasNoRepositoryDependency() {
        val forbidden = listOf(
            "Repository", "repository", "Dao", "dao", "data.", "AppDatabase", "Room",
            "withTransaction", "inTransaction", "suspend fun", "database"
        )
        val found = offenders(policyFile, forbidden)

        assertTrue(
            "the policy decides over a record that is already in memory; it asks nobody for " +
                "anything, so a repository reference here would mean it is deciding from a source " +
                "other than the stored facts. $found",
            found.isEmpty()
        )
        assertTrue(
            "and the declared collaborator set is empty: a Kotlin `object` policy that took a " +
                "collaborator would have to declare it as a constructor parameter",
            !code(policyFile).contains("private val ") && !code(policyFile).contains("constructor(")
        )
    }

    // ---- 3. no DAO / Room ---------------------------------------------------------------------------

    @Test
    fun thePolicyHasNoStorageDependency() {
        val imports = codeLines(policyFile).filter { it.startsWith("import ") }

        for (imported in imports) {
            assertFalse(
                "the policy reaches storage only through the record that was already read: $imported",
                imported.contains("android") || imported.contains("androidx") ||
                    imported.contains("com.monkfitness.app.data") || imported.contains("com.monkfitness.app.di")
            )
        }
        assertTrue(
            "and every import it does have is a pure domain value",
            imports.all { it.startsWith("import com.monkfitness.app.domain.") }
        )
    }

    // ---- 4. no clock, id generator or randomness ----------------------------------------------------

    @Test
    fun thePolicyAcquiresNoAmbientTimeAndNoRandomness() {
        val forbidden = listOf(
            "LocalDate.now(", "Instant.now(", "System.currentTimeMillis", "System.nanoTime",
            "Clock.system", "clock.now(", "Clock", "Random(", "Random.", "shuffled(",
            "UUID.randomUUID", "nextInt(", "nextLong(", "newId(", "IdGenerator", "currentTimeMillis"
        )
        val found = offenders(policyFile, forbidden)

        assertTrue(
            "the execution of an occurrence is a function of what was stored, and when a workout " +
                "happened is itself a stored column; nothing here reads the device's date, mints an " +
                "identity or reaches for ambient state. $found",
            found.isEmpty()
        )
    }

    // ---- 5. no engine stage -------------------------------------------------------------------------

    @Test
    fun thePolicyDependsOnNoEngineStageAndNoRuntime() {
        val forbidden = listOf(
            "TargetPlanner", "TargetScheduleResolver", "TargetOccurrenceComposer",
            "TargetSchedulePolicy", "TargetOccurrencePresenter", "TargetOccurrenceReconciler",
            "TargetSlotMaterializer", "TargetScheduleSlotPersister", "TargetScheduleOrchestrator",
            "TargetScheduleInputAdapter", "TargetScheduleApplicationService", "TargetScheduleDecision",
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "LegacySchedule",
            "LegacyScheduleMapper", "ProgramProgressService", "SessionRuntime", "ViewModel"
        )
        val found = offenders(policyFile, forbidden)

        assertTrue(
            "a precedence is not a stage's output: the policy reads a record and returns a value, " +
                "so a dependency on any planner, resolver, composer, reconciler, presenter, " +
                "materializer, persister, legacy scheduler or runtime would mean the verdict is " +
                "someone else's. $found",
            found.isEmpty()
        )
    }

    // ---- 6. no ProgramDay, no current revision ------------------------------------------------------

    @Test
    fun thePolicyReadsNoPlanDayAndNoCurrentRevision() {
        val forbidden = listOf(
            "ProgramDayId", "ProgramDay", "programDayId", "currentRevision", "currentProgram",
            "revisionOf", "latestRevision", "RevisionId", "revisionId", "position", ".name"
        )
        val found = offenders(policyFile, forbidden)

        assertTrue(
            "an occurrence's execution is a fact about its attempts; a plan day's identity, a " +
                "revision's currency and a stored revision id are facts about the *plan*, and " +
                "reading any of them here would make the verdict depend on the schedule rather " +
                "than on the workout. $found",
            found.isEmpty()
        )
        assertTrue(
            "and the policy's whole input is the record: it names the record type and nothing else " +
                "as a parameter",
            code(policyFile).contains("fun decide(record: TargetOccurrenceExecutionRecord)")
        )
    }

    // ---- 7. no occurrence-key parsing ----------------------------------------------------------------

    @Test
    fun thePolicyDoesNotParseTheOccurrenceKey() {
        val split = listOf(
            ".split(", ".substring", ".substringBefore", ".substringAfter", ".removePrefix",
            ".removeSuffix", ".replace(", ".indexOf(", ".lastIndexOf(", ".takeWhile", ".dropWhile",
            ".chunked(", ".windowed(", "Regex(", "toRegex(", "Pattern", "StringTokenizer", "trim("
        )
        val found = offenders(policyFile, split)

        assertTrue(
            "the key is an opaque token and the execution of an occurrence is not a function of its " +
                "spelling; a parse here would make an occurrence's state depend on a string format. " +
                "$found",
            found.isEmpty()
        )
        assertTrue(
            "and the policy never reaches for the key at all",
            !code(policyFile).contains("occurrenceKey")
        )
    }

    // ---- 8. no ActualResult -------------------------------------------------------------------------

    @Test
    fun thePolicyConstructsNoActualResultAndInventsNoWorkMapping() {
        for (forbidden in listOf(
            "ActualResult", "PerformedWork", "ExecutedReps", "ExecutedSeconds",
            "CompletionCalculator", "ExistingOccurrence", "SetResult", "SessionExercise"
        )) {
            assertTrue(
                "performed-work mapping is explicitly deferred to a later, separately owned phase; " +
                    "this phase decides an execution state and must not smuggle a work mapping in " +
                    "with it ($forbidden)",
                wholeTokenOffenders(policyFile, listOf(forbidden)).isEmpty()
            )
        }
        assertTrue(
            "and the decision's own surface is the verdict, the opportunity status and a count",
            listOf("val execution: OccurrenceExecution", "val slotStatus: SlotStatus", "val attemptCount: Int")
                .all { code(policyFile).contains(it) }
        )
    }

    // ---- 9. no second precedence ---------------------------------------------------------------------

    @Test
    fun noSecondExecutionPrecedenceExistsAnywhereElse() {
        // The sweep is over the *whole* main source tree, not just this phase: the failure this gate
        // exists for is a caller quietly re-deriving the verdict, which is invisible from inside the
        // policy file itself.
        val mainSources = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        // A file "decides an execution" if it *builds* an `OccurrenceExecution` value out of stored
        // attempt statuses. Reading one off an `ExistingOccurrence` is not deciding, and neither is
        // the enum's own declaration, so both are excluded by name.
        val deciding = mainSources.filterNot { source ->
            source.name in setOf("SemanticExecutionContracts.kt")
        }.filter { source ->
            val text = code(source)
            val buildsAVerdict = Regex("OccurrenceExecution\\.(PLANNED|STARTED|COMPLETED|CANCELLED)")
                .containsMatchIn(text)
            val readsAttemptStatus = text.contains("SessionStatus") || text.contains("WorkoutSession")
            buildsAVerdict && readsAttemptStatus
        }.map { it.name }.sorted()

        assertEquals(
            "the precedence is owned by `TargetOccurrenceExecutionPolicy` and by nothing else: a " +
                "second place that turns stored attempt statuses into an OccurrenceExecution is the " +
                "exact duplication this phase forbids",
            listOf("TargetOccurrenceExecutionPolicy.kt"),
            deciding
        )

        // And within the policy there is exactly one decision point, so "the precedence" is a
        // single readable block rather than a rule assembled in pieces.
        val code = code(policyFile)
        assertEquals(
            "the policy offers one operation, so there is no second entry point that could decide " +
                "differently",
            listOf("decide"),
            Regex("fun (\\w+)\\(").findAll(code).map { it.groupValues[1] }.toList()
        )
        assertEquals(
            "and the precedence itself is one `when` with one branch per rule",
            1,
            Regex("val execution = when \\{").findAll(code).count()
        )
    }

    // ---- 10. pure over the Phase 15 record ------------------------------------------------------------

    @Test
    fun thePolicyIsPureOverThePhaseFifteenRecord() {
        val code = code(policyFile)

        assertTrue(
            "the policy consumes the Phase 15 record, and only its attempt statuses",
            code.contains("record.attemptStatuses")
        )
        assertFalse(
            "it does not filter the attempts it inspects: no status is dropped, sorted or ranked " +
                "before the verdict, because dropping one would change the answer",
            code.contains(".filter") || code.contains(".sorted") || code.contains(".sortedBy") ||
                code.contains(".distinct") || code.contains(".reversed")
        )
        assertFalse(
            "it does not pick one attempt out of several, by position or by status",
            code.contains("firstOrNull") || code.contains("lastOrNull") || code.contains(".first()") ||
                code.contains(".last()") || code.contains("maxBy") || code.contains("maxOf")
        )
        assertFalse(
            "and it reads no execution-unrelated stored column: the slot's status, the finish " +
                "stamp, the planned date and the attempt ids are all facts the verdict does not " +
                "depend on",
            code.contains("finishedAt") || code.contains("completedAt") || code.contains("plannedFor") ||
                code.contains("attemptIds")
        )
        assertTrue(
            "the record is the only declared parameter, and the decision is a plain value over it",
            code.contains("fun decide(record: TargetOccurrenceExecutionRecord)") &&
                code.contains("data class TargetOccurrenceExecutionDecision")
        )
    }

    @Test
    fun thePolicyHoldsNoMutableState() {
        val fields = TargetOccurrenceExecutionPolicy::class.java.declaredFields
            .filterNot { it.isSynthetic }

        assertEquals(
            "an `object` policy holds only its singleton instance: a cache, a counter or a last " +
                "result would make the second call to `decide` depend on the first",
            setOf("INSTANCE", "\$stable"),
            fields.map { it.name }.toSet()
        )
        assertTrue(
            "and every such field is static",
            fields.all { Modifier.isStatic(it.modifiers) }
        )
    }

    @Test
    fun theDecisionTypeUsesOnlyJvmAndPureDomainValues() {
        val types = listOf(
            TargetOccurrenceExecutionDecision::class.java,
            TargetOccurrenceExecutionPolicy::class.java
        )
        val offenders = types.flatMap { type ->
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
            "the verdict and the precedence may use pure domain values and the JVM only: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePolicyAddsNothingToThePhaseFifteenRecord() {
        assertTrue(
            "Phase 15's record is consumed unchanged: it still holds a list of every attempt and " +
                "still exposes no verdict of its own",
            code(recordFile).contains("val attempts: List<WorkoutSession>") &&
                wholeTokenOffenders(recordFile, listOf("OccurrenceExecution")).isEmpty()
        )
    }
}
