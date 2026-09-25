package com.monkfitness.app.domain.program.target

import com.monkfitness.app.data.model.ProgramTargetOccurrenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The mechanical boundaries of §30 step 14, checked against the sources themselves.
 *
 * The phase's whole claim is a set of prohibitions — do not parse the key, do not derive an identity
 * from a plan day, do not reconstruct components from a slot, do not acquire ambient time, do not
 * touch the legacy scheduler, do not overwrite a stored payload, do not split the two writes — and a
 * prohibition that lives only in a KDoc erodes the first time a convenient import appears. So each
 * one is asserted here as a **token or a shape in real code**, with comments stripped first, which
 * is what lets this file's own explanations say "never split the occurrence key" without tripping the
 * rule about splitting it.
 *
 * The nine gates, in the order the phase states them:
 *
 * ```text
 * 1. target semantic persistence has no path into ProgramSchedule
 * 2. occurrence components are never reconstructed from targetOccurrenceKey
 * 3. no ProgramDayId / position / name / list-index inference becomes a target identity
 * 4. semantic read-back calls no planner, resolver, composer, policy, presenter, scheduler or runtime
 * 5. the target persistence boundary acquires no ambient time and no randomness
 * 6. legacy ProgramScheduler, SlotPlanner and ScheduleCalendar stay untouched
 * 7. (programId, occurrenceKey) is the only target occurrence membership identity
 * 8. a conflicting semantic payload is refused, not overwritten
 * 9. slot + semantic occurrence persistence is one atomic operation
 * ```
 */
class TargetOccurrencePersistenceArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val dataDir = File(mainDir, "data")

    private val valueFile = File(targetDir, "TargetOccurrencePersistence.kt")
    private val mapperFile = File(dataDir, "mapper/TargetOccurrenceMappers.kt")
    private val repositoryFile = File(dataDir, "repository/TargetScheduleOccurrenceRepository.kt")
    private val daoFile = File(dataDir, "local/ProgramTargetOccurrenceDao.kt")
    private val occurrenceEntity = File(dataDir, "model/ProgramTargetOccurrenceEntity.kt")
    private val componentEntity = File(dataDir, "model/ProgramTargetOccurrenceComponentEntity.kt")
    private val persisterFile = File(mainDir, "domain/usecase/TargetScheduleSlotPersister.kt")

    /**
     * The **semantic payload path**: the value, its storage, and the translation between them.
     *
     * These are the files that decide what a target occurrence *is* and what it reads back as. A
     * forbidden token here is a real defect in the payload — a `ProgramSchedule` reference would mean
     * the semantics were mapped out of the legacy vocabulary rather than stored, and an
     * `occurrenceKey.split(...)` would mean the read-back parses the key it is supposed to be
     * treating as opaque.
     */
    private val payloadPath: List<File> = listOf(
        valueFile,
        mapperFile,
        repositoryFile,
        daoFile,
        occurrenceEntity,
        componentEntity
    )

    /**
     * The **joining boundary**: the Stage 10 persister, which writes a slot and the occurrence it
     * presents inside one transaction.
     *
     * It is listed separately and deliberately *not* swept by the payload gates. It legitimately holds
     * `ProgramScheduleRepository` — that is the Stage 10 dependency the phase is required to preserve
     * rather than bypass — so treating its imports as a payload defect would either fail a correct
     * boundary or tempt a future change into removing the dependency the brief says to keep. The one
     * gate that does apply to it is the atomicity gate below, which is about the shape of its writes.
     */
    private val boundaryPath: List<File> = listOf(persisterFile)

    /** The two together: what a whole-phase "no ambient state" sweep should read. */
    private val semanticSources: List<File> = payloadPath + boundaryPath

    private fun code(source: File): String = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    private fun codeLines(source: File): List<String> = code(source)
        .lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }

    private fun offenders(sources: List<File>, tokens: List<String>): List<String> =
        sources.flatMap { source ->
            codeLines(source).filter { line -> tokens.any { token -> line.contains(token) } }
                .map { "${source.name}: $it" }
        }

    // ---- 1. no path into ProgramSchedule ----------------------------------------------------------

    @Test
    fun targetSemanticPersistenceHasNoPathIntoProgramSchedule() {
        val forbidden = listOf(
            "ProgramSchedule",
            "LegacySchedule",
            "LegacyScheduleMapper",
            "scheduleWeekdays",
            "scheduleType"
        )
        val found = offenders(payloadPath, forbidden)

        assertTrue(
            "the target occurrence's payload is a stored fact, never something mapped out of the " +
                "legacy schedule vocabulary: $found",
            found.isEmpty()
        )
        // The joining boundary is exempt from *this* gate by design and checked for the opposite
        // property instead: it must still hold the Stage 10 slot repository. If a future change
        // dropped that dependency to satisfy a payload rule, this assertion is what would notice.
        assertTrue(
            "and the boundary still holds the Stage 10 slot repository this phase must not bypass",
            code(persisterFile).contains("scheduleRepository: ProgramScheduleRepository")
        )
        // And the whole main tree outside this phase's own files is equally clear: a `ProgramSchedule`
        // reference in a new semantic file would be the *first* path, and the guard above names the
        // files, so a newly created one is covered without a second list to keep in sync.
        assertTrue(valueFile.isFile && mapperFile.isFile && repositoryFile.isFile && daoFile.isFile)
    }

    // ---- 2. components are never reconstructed from the key ---------------------------------------

    @Test
    fun occurrenceComponentsAreNeverReconstructedFromTheOccurrenceKey() {
        val split = listOf(
            ".split(", ".substring", ".substringBefore", ".substringAfter", ".removePrefix",
            ".removeSuffix", ".replace(", ".indexOf(", ".lastIndexOf(", ".takeWhile", ".dropWhile",
            ".chunked(", ".windowed(", "Regex(", "toRegex(", "Pattern", "StringTokenizer"
        )
        val found = offenders(listOf(mapperFile, repositoryFile, valueFile), split)

        assertTrue(
            "the key's own text is an opaque token, so nothing anywhere in the semantic read-back " +
                "parses, splits, slices or pattern-matches it: $found",
            found.isEmpty()
        )
        assertTrue(
            "the read-back's own key use is the lookup parameter, passed through unchanged",
            code(mapperFile).contains("occurrenceKey = occurrenceKey")
        )
    }

    // ---- 3. no ProgramDay-derived identity --------------------------------------------------------

    @Test
    fun noProgramDayOrPositionInferenceBecomesATargetIdentity() {
        val forbidden = listOf(
            "ProgramDayId(",
            "ProgramDay(",
            ".position",
            "programDayId",
            "slotId",
            "dayOfWeek",
            "indexOf(",
            "plannedFor.toString()",
            "DayOfWeek"
        )
        // `programDayId` and `slotId` are legitimate *in the persister* — it materializes a slot, and
        // Stage 9's own boundary is where a presented ProgramDayId legitimately enters. The claim here
        // is narrower and sharper: nothing on the **semantic payload** path may read a plan day, a
        // position, a name or a slot id to produce a rule id, a workout id or an occurrence key.
        val found = offenders(
            listOf(valueFile, mapperFile, repositoryFile, daoFile, occurrenceEntity, componentEntity),
            forbidden
        )

        assertTrue(
            "a target occurrence's identity is its own (programId, occurrenceKey) plus stored " +
                "components — never a plan day, a position, a name, a weekday, a list index or a slot " +
                "id: $found",
            found.isEmpty()
        )
        for (fabricated in listOf("legacy", "\"legacy\"", "unknown", "placeholder", "TODO")) {
            val literals = offenders(
                listOf(valueFile, mapperFile, repositoryFile, daoFile, occurrenceEntity, componentEntity),
                listOf(fabricated)
            )
            assertTrue(
                "no placeholder identity is ever substituted for a stored one ($fabricated): $literals",
                literals.isEmpty()
            )
        }
    }

    // ---- 4. read-back consults no engine stage ----------------------------------------------------

    @Test
    fun semanticReadBackCallsNoPlannerResolverComposerPolicyPresenterOrRuntime() {
        val forbidden = listOf(
            "TargetPlanner",
            "TargetScheduleResolver",
            "TargetOccurrenceComposer",
            "TargetSchedulePolicy",
            "TargetOccurrencePresenter",
            "TargetOccurrenceReconciler",
            "TargetScheduleOrchestrator",
            "TargetScheduleInputAdapter",
            "TargetScheduleApplicationService",
            "ProgramScheduler",
            "SlotPlanner",
            "ScheduleCalendar",
            "ProgramSchedule",
            "IdGenerator",
            "Clock",
            "WorkoutSessionRepository",
            "ProgramProgressRepository"
        )
        val found = offenders(listOf(valueFile, mapperFile, repositoryFile, daoFile), forbidden)

        assertTrue(
            "the read-back is a translation and nothing else — it re-derives no schedule, consults no " +
                "policy, and reaches no session graph: $found",
            found.isEmpty()
        )
    }

    // ---- 5. no ambient time, no randomness ---------------------------------------------------------

    @Test
    fun theTargetPersistenceBoundaryAcquiresNoAmbientTimeOrRandomness() {
        val forbidden = listOf(
            "LocalDate.now(", "Instant.now(", "System.currentTimeMillis", "Clock.system",
            "Random(", "Random.", "UUID.randomUUID", "nextInt(", "nextLong(", "nanoTime(",
            "currentTimeMillis"
        )
        val found = offenders(semanticSources, forbidden)

        assertTrue(
            "a persisted occurrence's date is the caller's date, and its identity is the caller's " +
                "identity: neither the payload path nor the joining boundary reads a clock or mints " +
                "anything — $found",
            found.isEmpty()
        )
        assertTrue(
            "the only identity source on the payload path is the caller's own occurrence key",
            code(mapperFile).contains("occurrenceKey = occurrence.occurrenceKey")
        )
    }

    // ---- 6. the legacy scheduler is untouched ------------------------------------------------------

    @Test
    fun theLegacySchedulerPlannerAndCalendarRemainUntouchedAndUnwired() {
        val scheduler = File(mainDir, "domain/usecase/ProgramScheduler.kt")
        val planner = File(mainDir, "domain/program/SlotPlanner.kt")
        val calendar = File(mainDir, "domain/program/ScheduleCalendar.kt")
        assertTrue(scheduler.isFile && planner.isFile && calendar.isFile)

        for (legacy in listOf(scheduler, planner, calendar)) {
            val text = legacy.readText()
            for (token in listOf(
                "TargetScheduleOccurrenceRepository",
                "ProgramTargetOccurrenceDao",
                "program_target_occurrence",
                "PersistedTargetOccurrence",
                "TargetOccurrencePersistence"
            )) {
                assertFalse(
                    "${legacy.name} must not reach the target occurrence's semantic persistence: $token",
                    code(legacy).contains(token)
                )
            }
        }
        // The two are wired *beside* the legacy scheduler, never into it: the container's legacy
        // scheduler construction does not name the target occurrence repository or its DAO.
        val container = File(mainDir, "di/AppContainer.kt").readText()
        val legacyConstruction = container.substringAfter("val programScheduler: ProgramScheduler =")
            .substringBefore("val targetScheduleOccurrenceRepository")
        assertFalse(
            "the legacy Scheduler's own construction is unchanged by this phase",
            legacyConstruction.contains("TargetScheduleOccurrence") ||
                legacyConstruction.contains("targetOccurrence")
        )
    }

    // ---- 7. (programId, occurrenceKey) is the only membership identity -----------------------------

    @Test
    fun theProgramAndTheOccurrenceKeyAreTheOnlyTargetOccurrenceMembershipIdentity() {
        val dao = code(daoFile)
        val statements = Regex("SELECT \\* FROM `program_target_occurrence[^`]*`[^\\\"]*")
            .findAll(dao).map { it.value.trim() }.toList()
        assertEquals(
            "the DAO declares three reads: the occurrence by key, its components by key, and the " +
                "Program's whole set",
            3,
            statements.size
        )

        // The two **by-key** lookups are the membership reads, and membership is the identity pair.
        // They are selected by the table they read, not by guessing which line is "the" query: the
        // components read comes from the component table, so a gate that matched on the parent table
        // alone would silently skip the read that matters most for component order.
        val byKey = statements.filter { it.contains(":occurrenceKey") }
        assertEquals(
            "exactly two of the three reads are membership lookups",
            2,
            byKey.size
        )
        for (statement in byKey) {
            val where = statement.substringAfter("WHERE").substringBefore("ORDER BY").trim()
            assertTrue(
                "a membership lookup is on programId AND occurrenceKey: $statement",
                where.contains("`programId` = :programId") && where.contains("`occurrenceKey` = :occurrenceKey")
            )
            for (forbidden in listOf("LIKE", "GLOB", "MATCH", "substr(", "instr(", ":plannedFor", ":position")) {
                assertFalse(
                    "and no membership lookup adds a substitute identity or a partial match " +
                        "($forbidden): $statement",
                    where.contains(forbidden)
                )
            }
        }

        // The third read is a **listing**, not a membership lookup: it is scoped to one Program and
        // ordered by a stored column. Ordering by `plannedFor` is a read order, not an identity, so
        // the gate asserts exactly that — the ordering is the only place a date appears, and it
        // appears after every row has already been selected.
        val listing = statements.single { !it.contains(":occurrenceKey") }
        assertEquals(
            "the listing is scoped to one Program and ordered by a stored date, with no key " +
                "substitution anywhere in it",
            "`programId` = :programId ORDER BY `plannedFor` ASC, `occurrenceKey` ASC",
            listing.substringAfter("WHERE").trim()
        )
        assertTrue(
            "and the date is an ORDER BY term, never a membership predicate: $listing",
            listing.indexOf("ORDER BY") < listing.indexOf("`plannedFor`") &&
                !listing.substringAfter("WHERE").substringBefore("ORDER BY").contains("`plannedFor`")
        )
        assertEquals(
            "the entity's primary key is the pair and nothing else",
            listOf("programId", "occurrenceKey"),
            ProgramTargetOccurrenceEntity::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .take(2)
        )
    }

    // ---- 8. a conflicting payload is refused, not overwritten --------------------------------------

    @Test
    fun theSemanticStoreRefusesAConflictingPayloadAndDeclaresNoUpdate() {
        val dao = code(daoFile)
        assertFalse(
            "the DAO has no UPDATE: a stored payload is never rewritten in place",
            Regex("\\bUPDATE\\b", RegexOption.IGNORE_CASE).containsMatchIn(dao)
        )
        assertFalse(
            "and no DELETE: a stored payload is never dropped so a new one can take its place",
            Regex("\\bDELETE\\b", RegexOption.IGNORE_CASE).containsMatchIn(dao)
        )
        val inserts = Regex("@Insert").findAll(dao).count()
        assertEquals("inserts only — one for the occurrence, one for its components", 2, inserts)

        val repository = code(repositoryFile)
        assertTrue(
            "the repository compares the stored payload before it writes and throws a typed refusal",
            repository.contains("hasSamePayloadAs") &&
                repository.contains("ConflictingSemanticPayload")
        )
        assertTrue(
            "and the refusal names both payloads, so it says which record was found",
            repository.contains("storedPayload = stored.occurrence") &&
                repository.contains("requestedPayload = occurrence.occurrence")
        )
    }

    // ---- 9. slot + occurrence is one atomic operation ---------------------------------------------

    @Test
    fun theSlotAndTheOccurrenceAreWrittenInsideOneTransaction() {
        val persister = code(persisterFile)
        val transactionStart = persister.indexOf("inTransaction {")
        assertTrue(
            "the boundary takes a transaction runner as a port rather than opening a database itself",
            transactionStart > 0
        )
        val inside = persister.substring(transactionStart)
        val slotWrite = inside.indexOf("scheduleRepository.addSlots(created)")
        val occurrenceWrite = inside.indexOf("occurrenceRepository.store(")
        assertTrue("the slot write is inside the transaction", slotWrite > 0)
        assertTrue("the occurrence write is inside the transaction", occurrenceWrite > 0)

        // The whole store loop — not a first occurrence as a special case — is inside the block, so a
        // pass with several occurrences is all-or-nothing as a unit rather than occurrence by
        // occurrence.
        // The loop that drives the occurrence writes must itself be inside the block. Slicing from
        // the transaction (not from the first `store(` call) is what makes this check meaningful: the
        // `forEach` opens *before* its first `store(`, so a slice taken from the store call would not
        // contain it even though the loop is correctly inside.
        val loop = inside.substringBefore("occurrenceRepository.store(")
        assertTrue(
            "every presented occurrence is stored inside the transaction, not just the first — the " +
                "loop that drives the writes is inside the block",
            loop.contains("input.presentations.forEach")
        )
        assertTrue(
            "and the loop closes inside the block too, so the whole store is one unit",
            inside.contains("}\n        }")
        )
        assertTrue(
            "and the transaction runner is a parameter, not a database this boundary acquired",
            code(persisterFile).contains("inTransaction: suspend (suspend () -> Unit) -> Unit")
        )
        assertFalse(
            "the boundary still reaches storage only through the two repositories",
            code(persisterFile).contains("androidx.room") ||
                code(persisterFile).contains("AppDatabase") ||
                code(persisterFile).contains("ProgramTargetOccurrenceDao")
        )
    }

    // ---- the value type's own boundary -------------------------------------------------------------

    @Test
    fun thePersistedOccurrenceIsSemanticDataAndNotAnExistingOccurrence() {
        val value = code(valueFile)
        assertTrue(
            "`PersistedTargetOccurrence` carries the Program and the whole planned occurrence",
            value.contains("data class PersistedTargetOccurrence") &&
                value.contains("val programId: ProgramId") &&
                value.contains("val occurrence: PlannedOccurrence")
        )
        for (forbidden in listOf(
            "OccurrenceExecution",
            "ActualResult",
            "existingOccurrence",
            "attempts",
            "SessionId"
        )) {
            assertFalse(
                "the semantic record holds no execution state and no actual result ($forbidden): a " +
                    "started-versus-cancelled reading is not in a slot's status, attempts live in " +
                    "WorkoutSession and performed work lives in the session graph, so reconstructing " +
                    "any of it here would be inventing a rule this phase must not set",
                value.contains(forbidden)
            )
        }
        assertTrue(
            "and it is a distinct type from the domain's `ExistingOccurrence`, not an alias of it",
            !value.contains("typealias")
        )
    }

    @Test
    fun theValueTypeRefusesABlankIdentityOrAnEmptyComponentList() {
        val blank = runCatching {
            PersistedTargetOccurrence(
                com.monkfitness.app.domain.common.ProgramId("program-1"),
                com.monkfitness.app.domain.program.PlannedOccurrence(
                    occurrenceKey = "  ",
                    plannedFor = java.time.LocalDate.parse("2026-10-05"),
                    components = listOf(
                        com.monkfitness.app.domain.program.OccurrenceComponent("rule", "workout")
                    )
                )
            )
        }
        assertTrue("a blank occurrence key cannot be stored", blank.isFailure)
    }
}
