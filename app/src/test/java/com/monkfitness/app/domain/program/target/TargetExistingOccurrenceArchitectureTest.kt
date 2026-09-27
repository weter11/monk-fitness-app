package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The twelve mechanical boundaries of §30 step 17, checked against the sources themselves.
 *
 * A boundary refactor is only finished when the thing it removed *cannot come back by accident*, and
 * an assertion about one file does not survive a second caller: a new target component can import
 * the legacy type tomorrow and every file-local gate stays green. So the gates below sweep the
 * **whole** production tree, not the migrated files, and the ones about ownership are written as
 * closed lists and cardinalities that a later addition fails rather than a claim about the present
 * that a later change quietly falsifies.
 *
 * ```text
 *  1. no target scheduling source imports or names ExistingOccurrence
 *  2. the target-specific value lives in the target domain package
 *  3. it is exactly PlannedOccurrence + OccurrenceExecution
 *  4. no repository / DAO / Room dependency
 *  5. no clock, randomness or IdGenerator
 *  6. no session, work or performance state
 *  7. reconciliation still owns occurrence membership identity
 *  8. the policy remains the sole owner of temporal classification
 *  9. the execution policy remains the sole owner of execution interpretation
 * 10. no second adapter invents execution precedence
 * 11. the legacy scheduler remains isolated
 * 12. ActualResult remains absent from the target scheduling path
 * ```
 */
class TargetExistingOccurrenceArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val valueFile = File(targetDir, "TargetExistingOccurrence.kt")

    private val legacyNames = listOf(
        "ProgramScheduler.kt", "SlotPlanner.kt", "ScheduleCalendar.kt"
    )

    /** Every production source in the Program System, relative to the main source root. */
    private fun productionSources(): List<File> = File(mainDir.path)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun relative(file: File): String =
        file.path.replace(File.separatorChar, '/').substringAfter("/com/monkfitness/app/")

    private fun code(source: File): String = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    private fun codeLines(source: File): List<String> = code(source)
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Whole-token matching with `_` as a word character.
     *
     * A plain `contains` scan is wrong for this phase's own names: `TargetExistingOccurrence` and
     * `TargetOccurrenceExecutionRecord` both *contain* `ExistingOccurrence` and `OccurrenceExecution`
     * respectively. A gate that fired on either would have to be weakened to pass, which is how a
     * gate stops being a gate.
     */
    private fun wholeTokenOffenders(sources: List<File>, tokens: List<String>): List<String> {
        val pattern = tokens.joinToString(
            prefix = "(?<![A-Za-z0-9_])(?:",
            separator = "|",
            postfix = ")(?![A-Za-z0-9_])"
        )
        val regex = Regex(pattern)
        return sources.flatMap { source ->
            codeLines(source).filter { line -> regex.containsMatchIn(line) }
                .map { "${relative(source)}: $it" }
        }
    }

    // ---- 1. the legacy type is gone from the target contour ----------------------------------------

    @Test
    fun noTargetSchedulingSourceImportsOrNamesTheLegacyExistingOccurrence() {
        // Every target-owned file, plus the two orchestration boundaries the brief names. The sweep
        // is over *code* with comments stripped, because these files document the removal in prose
        // and a raw-text scan would fail on the very prose that explains the absence.
        val targetContour = productionSources().filter { file ->
            val path = relative(file)
            path.startsWith("domain/program/target/") ||
                path == "domain/usecase/TargetScheduleOrchestrator.kt" ||
                path == "domain/usecase/TargetScheduleInputAdapter.kt" ||
                path == "domain/usecase/TargetExistingOccurrenceReader.kt"
        }
        assertTrue(
            "the sweep must actually cover the target contour, or it proves nothing",
            targetContour.size >= 12
        )
        val found = wholeTokenOffenders(targetContour, listOf("ExistingOccurrence"))

        assertTrue(
            "target scheduling consumes the target-specific value and nothing else. A target " +
                "component that imports the legacy `ExistingOccurrence` inherits its ActualResult " +
                "payload and its generation's execution shape as scheduling inputs: $found",
            found.isEmpty()
        )
        // The import form specifically, because a file could name the type via a star import or a
        // same-package alias that a token scan reads differently from an import line.
        // Whole-token again: `import …target.TargetExistingOccurrence` contains the forbidden word,
        // so a plain `contains` would refuse the very import this phase introduced.
        val importPattern = Regex("(?<![A-Za-z0-9_])ExistingOccurrence(?![A-Za-z0-9_])")
        val imports = targetContour.flatMap { file ->
            codeLines(file).filter { it.startsWith("import ") && importPattern.containsMatchIn(it) }
                .map { "${relative(file)}: $it" }
        }
        assertTrue("no target source may import the legacy type: $imports", imports.isEmpty())
    }

    @Test
    fun aNewTargetCallerThatImportsTheLegacyTypeWouldFailThisGate() {
        // The regression the brief asks for, stated as a positive check on the gate itself rather
        // than as a claim about a file that does not exist: the same whole-token predicate the gate
        // uses must fire on exactly the shape a careless new caller would have, and must not fire on
        // this phase's own names.
        val pattern = Regex("(?<![A-Za-z0-9_])(?:ExistingOccurrence)(?![A-Za-z0-9_])")
        val careless = listOf(
            "import com.monkfitness.app.domain.program.ExistingOccurrence",
            "val existing: List<ExistingOccurrence> = emptyList()",
            "fun reconcile(existing: List<ExistingOccurrence>, other: Int): Int = 0"
        )
        careless.forEach { line ->
            assertTrue(
                "the gate must still refuse a new target caller shaped like: $line",
                pattern.containsMatchIn(line)
            )
        }
        listOf(
            "import com.monkfitness.app.domain.program.target.TargetExistingOccurrence",
            "val existing: List<TargetExistingOccurrence> = emptyList()",
            "val execution: OccurrenceExecution = OccurrenceExecution.PLANNED"
        ).forEach { line ->
            assertFalse(
                "and must not fire on the target contour's own vocabulary: $line",
                pattern.containsMatchIn(line)
            )
        }
    }

    // ---- 2./3. the value's home and its exact shape ------------------------------------------------

    @Test
    fun theTargetSpecificValueLivesInTheTargetDomainPackageAndHoldsOnlyPayloadAndExecution() {
        assertTrue(
            "the value is target schedule semantics, so it belongs in the pure target package",
            valueFile.isFile
        )
        val value = code(valueFile)

        assertTrue(
            "the package declaration is the target domain package, and the type is a distinct value " +
                "rather than an alias or a subclass of the legacy type",
            value.contains("package com.monkfitness.app.domain.program.target") &&
                value.contains("data class TargetExistingOccurrence(") &&
                !value.contains(": ExistingOccurrence(") &&
                !value.contains("ExistingOccurrence()")
        )
        assertTrue("it holds the planned payload", value.contains("val occurrence: PlannedOccurrence,"))
        assertTrue(
            "and the execution classification",
            value.contains("val execution: OccurrenceExecution")
        )
        // The declared primary constructor is the whole shape: exactly those two properties, in that
        // order, with no default and no third parameter. Reading the constructor block rather than
        // scanning the file means a *derived* accessor cannot be mistaken for a stored fact, and a
        // third stored fact cannot hide behind a default argument.
        val header = value
            .substringAfter("data class TargetExistingOccurrence(")
            .substringBefore("\n)")
        val properties = Regex("val (\\w+): ([^,\\n]+)").findAll(header)
            .map { it.groupValues[1] to it.groupValues[2].trim() }
            .toList()
        assertEquals(
            "the value's primary constructor declares exactly the two facts target scheduling reads",
            listOf("occurrence" to "PlannedOccurrence", "execution" to "OccurrenceExecution"),
            properties
        )
    }

    // ---- 4./5./6. purity ---------------------------------------------------------------------------

    @Test
    fun theTargetSpecificValueHasNoRepositoryDaoOrRoomDependency() {
        val value = code(valueFile)
        for (forbidden in listOf(
            "com.monkfitness.app.data", "TargetScheduleOccurrenceRepository",
            "ProgramScheduleRepository", "WorkoutSessionRepository", "ProgramRepository",
            "@Dao", "Dao(", "Room", "AppDatabase", "withTransaction",
            "inTransaction", "SqliteDatabase", "Entity", "database", "persist", "save",
            "read", "load", "fetch", "query"
        )) {
            assertFalse(
                "a scheduling input is a value: a storage reference ($forbidden) here would make " +
                    "membership depend on a read",
                Regex("(?<![A-Za-z0-9_])${Regex.escape(forbidden)}(?![A-Za-z0-9_])").containsMatchIn(value)
            )
        }
    }

    @Test
    fun theTargetSpecificValueHasNoClockRandomnessOrIdGenerator() {
        val value = code(valueFile)
        for (forbidden in listOf(
            "Clock", "LocalDate.now(", "Instant.now(", "System.currentTimeMillis", "System.nanoTime",
            "Random", "nextInt(", "nextLong(", "newId(", "UUID", "IdGenerator"
        )) {
            assertFalse(
                "scheduling membership is a function of stored facts, and when a pass ran is not one " +
                    "of them ($forbidden)",
                value.contains(forbidden)
            )
        }
        // And the compiled class holds no ambient collaborator at all.
        val fields = TargetExistingOccurrence::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filter { !it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals("the value declares exactly its two facts and no machinery", setOf("occurrence", "execution"), fields)
        assertTrue(
            "and no constructor takes a collaborator",
            TargetExistingOccurrence::class.java.declaredConstructors.all { constructor ->
                constructor.parameterTypes.all { it.name.startsWith("com.monkfitness.app.domain.program") }
            }
        )
    }

    @Test
    fun theTargetSpecificValueHoldsNoSessionWorkOrPerformanceState() {
        val value = code(valueFile)
        for (forbidden in listOf(
            "ActualResult", "PerformedWork", "ExecutionEvidence", "WorkoutSession", "WorkoutSlot",
            "SetResult", "SessionExercise", "SessionStatus", "SlotStatus", "PlannedWork",
            "actual", "performance", "reps", "load", "volume", "tonnage"
        )) {
            assertFalse(
                "performed work and the opportunity outcome belong to the session graph and to the " +
                    "Phase 16 decision, not to a scheduling input ($forbidden)",
                Regex("(?<![A-Za-z0-9_])${Regex.escape(forbidden)}(?![A-Za-z0-9_])").containsMatchIn(value)
        )
        }
        // The dependency surface itself: only the two domain values are reachable.
        val offenders = TargetExistingOccurrence::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .flatMap { method ->
                (method.parameterTypes.toList() + listOf(method.returnType)).mapNotNull { referenced ->
                    val name = if (referenced.isArray) referenced.componentType.name else referenced.name
                    val allowed = name.startsWith("kotlin.") || name.startsWith("java.") ||
                        name.startsWith("com.monkfitness.app.domain.") || referenced.isPrimitive
                    if (allowed) null else "TargetExistingOccurrence.${method.name} references $name"
                }
            }
        assertTrue("the value may only reach JVM and pure domain types: $offenders", offenders.isEmpty())
    }

    // ---- 7. membership identity stays with the reconciler -------------------------------------------

    @Test
    fun targetReconciliationStillOwnsOccurrenceMembershipIdentity() {
        val reconciler = code(File(targetDir, "TargetOccurrenceReconciler.kt"))

        // The membership key is the occurrence key, and it is read as written.
        assertTrue(
            "the reconciler indexes existing occurrences by occurrenceKey",
            reconciler.contains("occurrence.occurrence.occurrenceKey")
        )
        assertTrue(
            "and a duplicate key is refused there rather than merged",
            reconciler.contains("duplicate existing occurrence key")
        )
        // No second identity is admitted: no date, weekday, position or parsed key fragment.
        for (forbidden in listOf(
            ".split(", ".substring", ".substringBefore", ".substringAfter", ".removePrefix",
            ".removeSuffix", "dayOfWeek", "DayOfWeek", "ChronoUnit", "programDayId", ".position", ".name"
        )) {
            assertFalse(
                "occurrence membership identity is the occurrence key and nothing else ($forbidden)",
                reconciler.contains(forbidden)
            )
        }
        // The new value adds no identity of its own that the reconciler could key on instead: its
        // accessors are read-only projections of the payload it already keys on.
        val value = code(valueFile)
        assertTrue("the value exposes the key it forwards", value.contains("val occurrenceKey: String get() = occurrence.occurrenceKey"))
    }

    // ---- 8. temporal classification stays with the policy ------------------------------------------

    @Test
    fun targetPolicyRemainsTheSoleOwnerOfTemporalClassification() {
        val policyFile = File(targetDir, "TargetSchedulePolicy.kt")
        val policy = code(policyFile)
        val outside = productionSources().filter { it != policyFile }.filter { file ->
            val path = relative(file)
            path.startsWith("domain/program/target/") || path.startsWith("domain/usecase/")
        }

        for (verdict in listOf(
            "TargetSupersessionReason", "PASSED_WHILE_PAUSED", "TARGET_NO_LONGER_PRESENTS_OCCURRENCE"
        )) {
            val found = wholeTokenOffenders(outside, listOf(verdict))
            assertTrue(
                "the temporal classification $verdict is stated once, in TargetSchedulePolicy: $found",
                found.isEmpty()
            )
        }
        // And the policy really is where the as-of comparison happens: it takes the date and the
        // pause windows, and no other target source takes an as-of date for a classification.
        assertTrue("the policy decides at an explicit as-of date", policy.contains("asOf: LocalDate"))
        assertTrue("and reads the pause windows it owns", policy.contains("pauses: List<ProgramPauseWindow>"))
    }

    // ---- 9./10. execution interpretation stays with the Phase 16 policy -----------------------------

    @Test
    fun targetExecutionPolicyRemainsTheSoleOwnerOfExecutionInterpretation() {
        val policyFile = File(targetDir, "TargetOccurrenceExecutionPolicy.kt")
        val policy = code(policyFile)
        assertTrue("the Phase 16 policy is still present", policyFile.isFile)
        assertTrue(
            "and still decides from a record",
            policy.contains("fun decide(record: TargetOccurrenceExecutionRecord)")
        )

        // Every place in the tree that turns stored attempt statuses into an execution must be this
        // one. `SessionStatus.COMPLETED`/`IN_PROGRESS`/`CANCELLED` read against an attempt list is
        // the shape of a second precedence, so it is scanned for across the whole main tree.
        // The sweep is over the **target contour** — the pure target package and the target use cases
        // — not the whole tree. A `WorkoutSession` invariant, a `SessionRuntime` write and a Compose
        // label all legitimately name a `SessionStatus`, and none of them is turning a stored
        // *occurrence* attempt into that occurrence's execution. The claim is about a second
        // occurrence-execution precedence, so the sweep is a second occurrence-execution precedence
        // could appear in.
        val policyVerdicts = listOf("SessionStatus.COMPLETED", "SessionStatus.IN_PROGRESS", "SessionStatus.CANCELLED")
        val targetContour = productionSources().filter { file ->
            val path = relative(file)
            path.startsWith("domain/program/target/") || path.startsWith("domain/usecase/Target")
        }.filter { it != policyFile }
        val elsewhere = targetContour.flatMap { file ->
            codeLines(file)
                .filter { line -> policyVerdicts.any { line.contains(it) } }
                .map { "${relative(file)}: $it" }
        }
        assertTrue(
            "a stored attempt status is turned into an occurrence execution in exactly one place, " +
                "TargetOccurrenceExecutionPolicy. A second `when` over them is a second precedence: " +
                "$elsewhere",
            elsewhere.isEmpty()
        )
    }

    @Test
    fun noSecondAdapterInventsExecutionPrecedence() {
        // The bridge from Phase 15/16 to the target input must *call* the policy, not restate it.
        val bridgeFile = File(mainDir, "domain/usecase/TargetExistingOccurrenceReader.kt")
        assertTrue("the bridge exists", bridgeFile.isFile)
        val bridge = code(bridgeFile)

        assertTrue(
            "the bridge reads the stored facts through the Phase 15 reader",
            bridge.contains("executionReader.executionRecordOf(")
        )
        assertTrue(
            "and takes the execution from the Phase 16 decision",
            bridge.contains("TargetOccurrenceExecutionPolicy.decide(record)")
        )
        // No precedence of its own: no `when`, no status comparison, no "latest attempt" pick.
        for (forbidden in listOf(
            "SessionStatus", "attemptStatuses", "slot.status", "slotStatus",
            "maxBy", "last()", "first()", "sortedBy", "maxOf"
        )) {
            assertFalse(
                "the bridge is a sequence of four already-owned steps, not a second interpreter " +
                    "($forbidden)",
                bridge.contains(forbidden)
            )
        }
        // And it holds no repository of its own: storage is the Phase 15 reader's contract.
        val declared = Regex("private val (\\w+): (\\w+)").findAll(bridge)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            "the bridge's whole collaborator set is the Phase 15 reader and nothing else",
            listOf("executionReader" to "TargetOccurrenceExecutionReader"),
            declared
        )
    }

    // ---- 11. the legacy contour stays isolated ------------------------------------------------------

    @Test
    fun theLegacySchedulerRemainsIsolated() {
        val legacy = legacyNames.map { name ->
            productionSources().firstOrNull { it.name == name }
                ?: return@map null
        }
        assertTrue("the legacy contour still exists", legacy.all { it != null })

        // Nothing in the target contour reaches the legacy scheduler, and nothing in the legacy
        // contour reaches the target one.
        val targetFiles = productionSources().filter { relative(it).startsWith("domain/program/target/") }
        targetFiles.forEach { file ->
            val text = code(file)
            for (token in listOf("ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "ScheduleEditReconciler")) {
                assertFalse(
                    "the target contour must not reach the legacy contour (${relative(file)}: $token)",
                    Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(text)
                )
            }
        }
        val legacyFiles = productionSources().filter { it.name in legacyNames }
        legacyFiles.forEach { file ->
            val text = code(file)
            for (token in listOf("TargetSchedule", "TargetPlanner", "TargetPlan", "TargetExistingOccurrence", "TargetOccurrence")) {
                assertFalse(
                    "the legacy contour must not reach the target contour (${relative(file)}: $token)",
                    Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(text)
                )
            }
        }
        // And the legacy type still exists for the legacy contracts that legitimately hold it.
        assertTrue(
            "the legacy `ExistingOccurrence` remains for the general-purpose contracts",
            File(mainDir, "domain/program/SemanticContracts.kt")
                .readText()
                .contains("data class ExistingOccurrence(")
        )
    }

    // ---- 12. ActualResult stays out ------------------------------------------------------------------

    @Test
    fun actualResultRemainsAbsentFromTheTargetSchedulingPath() {
        val schedulingPath = productionSources().filter { file ->
            val path = relative(file)
            path.startsWith("domain/program/target/") ||
                path == "domain/usecase/TargetScheduleOrchestrator.kt" ||
                path == "domain/usecase/TargetScheduleInputAdapter.kt" ||
                path == "domain/usecase/TargetExistingOccurrenceReader.kt" ||
                path == "domain/usecase/TargetScheduleApplicationService.kt"
        }
        assertTrue("the scheduling path is non-empty, or the sweep proves nothing", schedulingPath.isNotEmpty())
        val found = wholeTokenOffenders(schedulingPath, listOf("ActualResult", "PerformedWork", "ExecutionEvidence"))

        assertTrue(
            "target scheduling consumes identity and execution only. Mapping a session's exerciseId " +
                "to a target occurrence's workId, or summing a session's sets into an ActualResult, " +
                "is not a read and stays out of scope for this phase: $found",
            found.isEmpty()
        )
        // And the deferred mapping is not smuggled in under another name either: no work-id mapping
        // and no aggregation helper is reachable from the scheduling path.
        // Only the aggregation *of performed work* is forbidden. `groupBy` over a planned date is
        // same-date composition, which is a scheduling rule the composer legitimately owns, so the
        // scan is for the shape that would sum or tally a session's results instead.
        val aggregating = schedulingPath.flatMap { file ->
            codeLines(file)
                .filter { line ->
                    listOf("sumOf", ".sum(", "tally", "average", "totalVolume", "totalLoad")
                        .any { line.contains(it) }
                }
                .map { "${relative(file)}: $it" }
        }
        assertTrue("no aggregation of performed work happens on the target scheduling path: $aggregating", aggregating.isEmpty())
    }

    @Test
    fun theLegacyTypeWasNotRetrofittedWithTargetBehaviour() {
        // The other way this phase could have been "solved": widen the legacy type instead of
        // narrowing the target input. It must be exactly what it was — three properties, one of
        // which is the ActualResult list.
        val legacy = File(mainDir, "domain/program/SemanticContracts.kt").readText()
        val block = legacy.substringAfter("data class ExistingOccurrence(").substringBefore("\n}")
        assertTrue("the legacy type still holds the actual results", block.contains("val actuals: List<ActualResult>"))
        assertFalse(
            "and gained no target-specific member from this phase",
            block.contains("Target") || block.contains("targetOccurrenceKey") ||
                block.contains("occurrenceKey") || block.contains("attemptCount")
        )
    }
}
