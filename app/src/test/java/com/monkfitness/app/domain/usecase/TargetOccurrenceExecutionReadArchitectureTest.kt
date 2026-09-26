package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.target.PersistedTargetOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionFacts
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionReadException
import com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The mechanical boundaries of §30 step 15, checked against the sources themselves.
 *
 * The phase is a **read-back layer**, and almost everything it must not do is a prohibition: do not
 * reach a DAO, do not derive an occurrence from a plan day or a legacy schedule, do not parse the
 * occurrence key, do not acquire ambient time, do not consult the current revision, and above all do
 * not quietly pick one `OccurrenceExecution` out of several stored attempts. A prohibition that lives
 * only in a KDoc erodes the first time a convenient import appears, so each is asserted here as a
 * **token or a shape in real code**, with comments stripped first — which is what lets these files
 * explain the absences in prose without tripping the rules about them.
 *
 * The ten gates, in the order the phase states them:
 *
 * ```text
 * 1. semantic target data comes from TargetScheduleOccurrenceRepository
 * 2. slot data comes from ProgramScheduleRepository
 * 3. session execution data comes from WorkoutSessionRepository
 * 4. no DAO / Room / AppDatabase access exists in the target execution reader
 * 5. no planner / resolver / composer / policy / presenter / materializer / persister dependency
 * 6. no ProgramSchedule → TargetSchedule inference
 * 7. no ProgramDay identity inference
 * 8. no occurrenceKey parsing
 * 9. no clock, randomness or IdGenerator dependency
 * 10. no ExistingOccurrence execution precedence is encoded
 * ```
 */
class TargetOccurrenceExecutionReadArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val usecaseDir = File(mainDir, "domain/usecase")

    /** The pure value and its typed refusals, plus the pure aggregation boundary. */
    private val valueFile = File(targetDir, "TargetOccurrenceExecutionRead.kt")

    /** The reader: the one class that composes the three repositories. */
    private val readerFile = File(usecaseDir, "TargetOccurrenceExecutionReader.kt")

    /** Both files, for the sweeps whose claim is about the phase as a whole. */
    private val phaseSources: List<File> = listOf(valueFile, readerFile)

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

    /**
     * The same sweep, matching **whole tokens** with `_` counted as a word character.
     *
     * A plain `contains` scan is wrong for two of this phase's own names: `OccurrenceExecution`
     * occurs inside `TargetOccurrenceExecutionRecord`, and `ProgramSchedule` occurs inside
     * `ProgramScheduleRepository` — which this phase is *required* to depend on. A gate that fired on
     * either would have to be weakened to pass, which is how a gate stops being a gate. So the
     * vocabulary that must be absent is matched as a standalone identifier.
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
                .map { "${source.name}: $it" }
        }
    }

    // ---- 1./2./3. the three owners ------------------------------------------------------------------

    @Test
    fun eachLayerComesFromTheRepositoryThatOwnsIt() {
        assertTrue(
            "both phase files exist, and a renamed or moved one fails here rather than silently " +
                "escaping every gate below",
            valueFile.isFile && readerFile.isFile
        )

        val reader = code(readerFile)
        assertTrue(
            "1. the semantic occurrence is asked of the occurrence repository",
            reader.contains("occurrenceRepository: TargetScheduleOccurrenceRepository") &&
                reader.contains("occurrenceRepository.occurrenceOf(")
        )
        assertTrue(
            "2. the slot is asked of the schedule repository, by the same (programId, occurrenceKey) pair",
            reader.contains("scheduleRepository: ProgramScheduleRepository") &&
                reader.contains("scheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)")
        )
        assertTrue(
            "3. the session attempts are asked of the session repository",
            reader.contains("sessionRepository: WorkoutSessionRepository") &&
                reader.contains("sessionRepository.sessionsOfSlot(")
        )

        // The constructor is the whole declared collaborator set, so a fourth dependency cannot
        // appear without this failing. Each is named as a type, not as a field, so a renamed local
        // does not change what the claim means.
        val declared = Regex("private val (\\w+): (\\w+)").findAll(reader)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            "the reader holds exactly the three owning repositories and nothing else",
            listOf(
                "occurrenceRepository" to "TargetScheduleOccurrenceRepository",
                "scheduleRepository" to "ProgramScheduleRepository",
                "sessionRepository" to "WorkoutSessionRepository"
            ),
            declared
        )
    }

    // ---- 4. no storage access below the repositories -----------------------------------------------

    @Test
    fun theTargetExecutionReaderReachesStorageOnlyThroughTheRepositories() {
        val storage = listOf(
            "androidx.room", "@Dao", "@Entity", "AppDatabase", "SqliteDatabase",
            "Dao(", "Entity", "withTransaction", "inTransaction", "Room"
        )
        val found = offenders(phaseSources, storage)

        assertTrue(
            "the reader composes repositories and adds no storage access of its own: a DAO here " +
                "would bypass the contracts that validate the session graph: $found",
            found.isEmpty()
        )
        for (repository in listOf(
            "TargetScheduleOccurrenceRepository", "ProgramScheduleRepository", "WorkoutSessionRepository"
        )) {
            assertFalse(
                "and it does not reach past $repository for its facts",
                code(readerFile).contains("$repository.sessionsOfSlot") &&
                    repository != "WorkoutSessionRepository"
            )
        }
    }

    // ---- 5. no engine stage ------------------------------------------------------------------------

    @Test
    fun theReadBackDependsOnNoEngineStageAndNoOtherUseCase() {
        val forbidden = listOf(
            "TargetPlanner", "TargetScheduleResolver", "TargetOccurrenceComposer",
            "TargetSchedulePolicy", "TargetOccurrencePresenter", "TargetOccurrenceReconciler",
            "TargetSlotMaterializer", "TargetScheduleSlotPersister", "TargetScheduleOrchestrator",
            "TargetScheduleInputAdapter", "TargetScheduleApplicationService", "TargetScheduleDecision",
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "ProgramScheduler",
            "SessionRuntime", "ProgramAdaptiveRepository", "ProgramProgressRepository",
            "ProgramPlanRepository", "ProgramRepository", "IdGenerator", "Clock"
        )
        val found = offenders(phaseSources, forbidden)

        assertTrue(
            "a read-back re-derives nothing and schedules nothing: it asks three repositories and " +
                "returns what they hold. $found",
            found.isEmpty()
        )
    }

    // ---- 6. no ProgramSchedule inference -------------------------------------------------------------

    @Test
    fun thereIsNoProgramScheduleToTargetScheduleInference() {
        val forbidden = listOf(
            "ProgramSchedule", "LegacySchedule", "LegacyScheduleMapper",
            "scheduleWeekdays", "scheduleType", "ScheduleCadence", "FlexiblePerWeek", "FixedWeekdays"
        )
        // Whole-token, so the required `ProgramScheduleRepository` — which is a *repository*, not
        // the legacy `ProgramSchedule` value — is not a hit. The two names are one substring apart
        // and only the token boundary tells them apart.
        val found = wholeTokenOffenders(phaseSources, forbidden)

        assertTrue(
            "the legacy schedule generation is not a source of target occurrence facts, and no " +
                "mapping between the two vocabularies is inferred here: $found",
            found.isEmpty()
        )
        assertTrue(
            "and the schedule dependency this phase *does* hold is the repository, not the value",
            code(readerFile).contains("ProgramScheduleRepository")
        )
    }

    // ---- 7. no ProgramDay identity inference ---------------------------------------------------------

    @Test
    fun thereIsNoProgramDayIdentityInference() {
        val forbidden = listOf(
            "ProgramDayId(", "ProgramDay(", "programDayId", ".position", ".name",
            "dayOfWeek", "DayOfWeek", "ChronoUnit", "plusDays", "minusDays"
        )
        // `programDayId` and `plannedFor` are legitimate *stored fields of the slot*, which this
        // phase returns verbatim — it is the reading of a plan day into an identity that is forbidden,
        // and that is what this narrower token list excludes.
        val found = offenders(phaseSources, forbidden)

        assertTrue(
            "a plan day's identity, position and name are facts about a plan, never a target " +
                "occurrence's identity: $found",
            found.isEmpty()
        )
        assertTrue(
            "and the slot's own stored day and date are passed through, not consumed",
            code(valueFile).contains("val slot: WorkoutSlot")
        )
    }

    // ---- 8. no key parsing ---------------------------------------------------------------------------

    @Test
    fun thereIsNoOccurrenceKeyParsing() {
        val split = listOf(
            ".split(", ".substring", ".substringBefore", ".substringAfter", ".removePrefix",
            ".removeSuffix", ".replace(", ".indexOf(", ".lastIndexOf(", ".takeWhile", ".dropWhile",
            ".chunked(", ".windowed(", "Regex(", "toRegex(", "Pattern", "StringTokenizer", "trim("
        )
        val found = offenders(phaseSources, split)

        assertTrue(
            "the key is an opaque token: it is stored, compared and returned as written, and its " +
                "text is never a source of anything: $found",
            found.isEmpty()
        )
        // Its *only* use in the reader is the lookup parameter, passed through unchanged.
        val reader = code(readerFile)
        assertTrue(
            "the reader passes the key to the occurrence repository and to the slot lookup unchanged",
            reader.contains("occurrenceOf(programId, occurrenceKey)") &&
                reader.contains("slotByTargetOccurrenceKey(programId, occurrenceKey)")
        )
    }

    // ---- 9. no ambient time, no randomness -----------------------------------------------------------

    @Test
    fun theReadBackAcquiresNoAmbientTimeAndNoRandomness() {
        val forbidden = listOf(
            "LocalDate.now(", "Instant.now(", "System.currentTimeMillis", "System.nanoTime",
            "Clock.system", "clock.now(", "Random(", "Random.", "UUID.randomUUID",
            "nextInt(", "nextLong(", "newId("
        )
        val found = offenders(phaseSources, forbidden)

        assertTrue(
            "a stored execution fact is what happened, and when it happened is a stored column: " +
                "nothing here reads the device's date, mints an id, or reaches for ambient state. $found",
            found.isEmpty()
        )
    }

    // ---- 10. no execution precedence -----------------------------------------------------------------

    @Test
    fun noExistingOccurrenceExecutionPrecedenceIsEncoded() {
        val value = code(valueFile)
        val reader = code(readerFile)

        // The verdict vocabulary is absent, not merely unused: the value cannot report an execution
        // state, so nothing downstream can read one off it by accident.
        // Whole-token, because this phase's own names are built from the forbidden word:
        // `TargetOccurrenceExecutionRecord` and `TargetOccurrenceExecutionCounts` both contain
        // `OccurrenceExecution`. What must be absent is the *domain verdict type*, as a standalone
        // identifier — reading a `SlotStatus` as an `OccurrenceExecution` is the defect.
        for (forbidden in listOf(
            "OccurrenceExecution", "ActualResult", "ExistingOccurrence", "PerformedWork",
            "PlannedWork", "CompletionCalculator", "ScheduleReconciliation"
        )) {
            assertTrue(
                "the read-back names no verdict type ($forbidden): with several stored attempts " +
                    "there is no stored fact that says which one decides the occurrence's execution " +
                    "state, so supplying one here would be inventing a precedence rule",
                wholeTokenOffenders(listOf(valueFile, readerFile), listOf(forbidden)).isEmpty()
            )
        }
        // The verdict vocabulary is reachable only through those types, so the four layer accessors
        // are the complete public surface and none of them returns one.
        for (accessor in listOf("slotStatus", "attemptStatuses", "attemptIds", "hasAttempts")) {
            val declaration = Regex("val $accessor[^\n]*").find(value)?.value.orEmpty()
            assertFalse(
                "$accessor returns a verdict rather than a stored fact: $declaration",
                declaration.contains("OccurrenceExecution") || declaration.contains("ActualResult")
            )
        }

        // The attempts are a *list* wherever they appear, and the pure boundary's only operations
        // are counting and filtering — the two things that need no precedence.
        assertTrue(
            "the record holds every attempt as an ordered list, not a single chosen one",
            value.contains("val attempts: List<WorkoutSession>")
        )
        val facts = Regex("fun (\\w+)\\(").findAll(value.substringAfter("object TargetOccurrenceExecutionFacts"))
            .map { it.groupValues[1] }.toList()
        assertEquals(
            "and the pure aggregation offers exactly two operations, neither of which chooses",
            listOf("countsOf", "attemptsWithStatus"),
            facts
        )
        val counts = value.substringAfter("data class TargetOccurrenceExecutionCounts")
            .substringBefore("object TargetOccurrenceExecutionFacts")
        assertFalse(
            "the counts carry no verdict either: a status tally, not an occurrence execution",
            counts.contains("OccurrenceExecution")
        )
    }

    // ---- the record's own shape ----------------------------------------------------------------------

    @Test
    fun theRecordKeepsTheFourLayersExplicitlySeparate() {
        val value = code(valueFile)
        for (declared in listOf(
            "val occurrence: PersistedTargetOccurrence",
            "val slot: WorkoutSlot",
            "val attempts: List<WorkoutSession>"
        )) {
            assertTrue("the record declares `$declared`", value.contains(declared))
        }
        // The four layers are separately addressable, which is what stops a caller conflating them.
        for (accessor in listOf(
            "val slotStatus get() = slot.status",
            "val attemptStatuses: List<SessionStatus>",
            "fun confirmedSets()",
            "fun skippedExercises()"
        )) {
            assertTrue("and separately readable through `$accessor`", value.contains(accessor))
        }
        // Slot status and session status are two accessors over two types, never one.
        assertFalse(
            "the record exposes no accessor that returns a SlotStatus and a SessionStatus as one " +
                "value, and no attempt status is derived from the slot's",
            value.contains("SessionStatus get() = slot")
        )
    }

    @Test
    fun theTypedRefusalsCoverTheStatedCorruptionsAndAreNotUsedForOrdinaryAbsence() {
        val value = code(valueFile)
        for (typed in listOf(
            "MissingTargetOccurrence", "MissingTargetSlot", "SlotBelongsToAnotherProgram",
            "SlotTargetKeyMismatch", "SessionBelongsToAnotherProgram",
            "SessionBelongsToAnotherRevision", "SessionReferencesAnotherSlot"
        )) {
            assertTrue("the refusal `$typed` is declared", value.contains("data class $typed"))
        }

        // Every one of them is thrown, not merely declared: a declared-but-unthrown refusal is a
        // promise, and this suite's behavioural cases are what make the promise load-bearing.
        val reader = code(readerFile)
        for (typed in listOf(
            "MissingTargetOccurrence", "MissingTargetSlot", "SlotBelongsToAnotherProgram",
            "SlotTargetKeyMismatch", "SessionBelongsToAnotherProgram",
            "SessionBelongsToAnotherRevision", "SessionReferencesAnotherSlot"
        )) {
            assertTrue(
                "the reader throws `$typed`",
                reader.contains("throw TargetOccurrenceExecutionReadException.$typed(")
            )
        }

        // No attempts is not a corruption: the read path's only `?:` fallbacks are the two absent
        // records, and neither turns into an empty attempt list.
        assertFalse(
            "the reader never substitutes an empty record for missing stored data",
            reader.contains("emptyList<WorkoutSession>()") ||
                reader.contains("?: TargetOccurrenceExecutionRecord")
        )
    }

    // ---- the record's own invariants ------------------------------------------------------------------

    @Test
    fun theRecordRefusesAMismatchedOccurrenceSlotOrAttempt() {
        val programId = com.monkfitness.app.domain.common.ProgramId("program-1")
        val occurrence = PersistedTargetOccurrence(
            programId,
            com.monkfitness.app.domain.program.PlannedOccurrence(
                occurrenceKey = "rule:2026-10-05",
                plannedFor = java.time.LocalDate.parse("2026-10-05"),
                components = listOf(
                    com.monkfitness.app.domain.program.OccurrenceComponent("rule-a", "workout-a")
                )
            )
        )
        val slot = com.monkfitness.app.domain.program.WorkoutSlot(
            slotId = com.monkfitness.app.domain.common.SlotId("slot-1"),
            programId = programId,
            revisionId = com.monkfitness.app.domain.common.RevisionId("revision-1"),
            programDayId = com.monkfitness.app.domain.common.ProgramDayId("day-1"),
            plannedFor = java.time.LocalDate.parse("2026-10-05"),
            status = com.monkfitness.app.domain.program.SlotStatus.PLANNED,
            targetOccurrenceKey = "rule:2026-10-05"
        )

        assertTrue(
            "a matching pair is a valid record",
            runCatching { TargetOccurrenceExecutionRecord(occurrence, slot) }.isSuccess
        )
        assertTrue(
            "a slot presenting another target key is refused",
            runCatching {
                TargetOccurrenceExecutionRecord(occurrence, slot.copy(targetOccurrenceKey = "other:2026-10-05"))
            }.isFailure
        )
        assertTrue(
            "and a slot of another Program is refused",
            runCatching {
                TargetOccurrenceExecutionRecord(
                    occurrence,
                    slot.copy(programId = com.monkfitness.app.domain.common.ProgramId("program-2"))
                )
            }.isFailure
        )
    }

    @Test
    fun thePureBoundaryIsCollaboratorFreeAndReturnsTheRecordUnchanged() {
        val record = run {
            val programId = com.monkfitness.app.domain.common.ProgramId("program-1")
            val slot = com.monkfitness.app.domain.program.WorkoutSlot(
                slotId = com.monkfitness.app.domain.common.SlotId("slot-1"),
                programId = programId,
                revisionId = com.monkfitness.app.domain.common.RevisionId("revision-1"),
                programDayId = com.monkfitness.app.domain.common.ProgramDayId("day-1"),
                plannedFor = java.time.LocalDate.parse("2026-10-05"),
                status = com.monkfitness.app.domain.program.SlotStatus.PLANNED,
                targetOccurrenceKey = "rule:2026-10-05"
            )
            TargetOccurrenceExecutionRecord(
                occurrence = PersistedTargetOccurrence(
                    programId,
                    com.monkfitness.app.domain.program.PlannedOccurrence(
                        occurrenceKey = "rule:2026-10-05",
                        plannedFor = java.time.LocalDate.parse("2026-10-05"),
                        components = listOf(
                            com.monkfitness.app.domain.program.OccurrenceComponent("rule-a", "workout-a")
                        )
                    )
                ),
                slot = slot
            )
        }

        assertEquals(
            "an occurrence with no attempts counts as zero attempts, not as a verdict",
            0,
            TargetOccurrenceExecutionFacts.countsOf(record).totalAttempts
        )
        assertEquals(
            "and every status tally for it is zero",
            0,
            TargetOccurrenceExecutionFacts.countsOf(record)
                .countOf(com.monkfitness.app.domain.workout.SessionStatus.COMPLETED)
        )
        assertTrue(
            "a status filter over an empty record returns nothing rather than a default",
            TargetOccurrenceExecutionFacts.attemptsWithStatus(
                record,
                com.monkfitness.app.domain.workout.SessionStatus.IN_PROGRESS
            ).isEmpty()
        )
    }
}
