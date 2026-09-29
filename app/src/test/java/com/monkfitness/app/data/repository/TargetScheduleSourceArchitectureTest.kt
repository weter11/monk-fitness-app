package com.monkfitness.app.data.repository

import com.monkfitness.app.data.model.ProgramTargetProgramDayBindingEntity
import com.monkfitness.app.data.model.ProgramTargetScheduleRuleEntity
import com.monkfitness.app.domain.usecase.TargetScheduleSource
import com.monkfitness.app.domain.usecase.TargetScheduleSourceBridge
import com.monkfitness.app.domain.usecase.TargetScheduleSourceRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek

/**
 * The mechanical boundaries of the persisted, revision-owned target schedule source.
 *
 * The phase's whole claim is a set of prohibitions — do not read the legacy schedule, do not derive a
 * target identity from a plan day, a position, a name or an id's text, do not touch execution state,
 * do not mutate a saved revision, do not turn a missing source into an empty one — and a prohibition
 * that lives only in a KDoc erodes the first time a convenient import appears. So each one is asserted
 * here as a **token or a shape in real code**, with comments stripped first, which is what lets this
 * file's own explanations say "never reads ProgramSchedule" without tripping the rule about reading it.
 *
 * The twelve gates, in the order the phase states them:
 *
 * ```text
 * 1.  the source's persistence names no ProgramScheduler and no legacy schedule vocabulary
 * 2.  the cadence round-trips form-first: no payload sniffing, no normalization between forms
 * 3.  no ProgramDay position / name / id-text / date becomes a target identity
 * 4.  the source depends on no ExistingOccurrence and no ActualResult
 * 5.  the source depends on no session, runtime or performance state
 * 6.  the source's domain value holds no repository, DAO, entity, clock or UI state
 * 7.  input construction consumes the explicit persisted source
 * 8.  a missing source is a typed absence, never an empty valid source
 * 9.  a saved revision's source gains no update and no delete path
 * 10. every target identity stays explicit, opaque and stored in its own column
 * 11. the closed production-reference lists stay closed
 * 12. the target contour stays a separately callable contour — no cutover happened here
 * ```
 */
class TargetScheduleSourceArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val dataDir = File(mainDir, "data")
    private val usecaseDir = File(mainDir, "domain/usecase")

    private val valueFile = File(usecaseDir, "TargetScheduleSource.kt")
    private val bridgeFile = File(usecaseDir, "TargetScheduleSourceBridge.kt")
    private val repositoryFile = File(dataDir, "repository/TargetScheduleSourceRepository.kt")
    private val mapperFile = File(dataDir, "mapper/TargetScheduleSourceMappers.kt")
    private val daoFile = File(dataDir, "local/ProgramTargetScheduleSourceDao.kt")
    private val ruleEntity = File(dataDir, "model/ProgramTargetScheduleRuleEntity.kt")
    private val bindingEntity = File(dataDir, "model/ProgramTargetProgramDayBindingEntity.kt")

    /** Every file this phase adds, in the order the layer sits between them. */
    private val phaseSources: List<File> = listOf(
        valueFile,
        bridgeFile,
        ruleEntity,
        bindingEntity,
        daoFile,
        mapperFile,
        repositoryFile
    )

    private fun code(source: File): String = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    private fun codeLines(source: File): List<String> = code(source)
        .lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }

    private fun offenders(sources: List<File>, tokens: List<String>): List<String> =
        sources.flatMap { source ->
            codeLines(source).filter { line -> tokens.any { token -> line.contains(token) } }
                .map { line -> "${source.name}: $line" }
        }

    @Test
    fun thePhaseAddsExactlyTheFilesItClaims() {
        assertTrue(
            "the domain value, the bridge, two entities, the DAO, the mapper and the repository all " +
                "exist",
            phaseSources.all { it.isFile }
        )
    }

    // ---- 1. no legacy scheduling vocabulary ---------------------------------------------------------

    @Test
    fun targetSourcePersistenceNamesNoSchedulerAndNoLegacyScheduleVocabulary() {
        val forbidden = listOf(
            "ProgramScheduler",
            "SlotPlanner",
            "ScheduleCalendar",
            "ProgramScheduleRepository",
            // `ProgramSchedule` itself, and the *columns* a legacy schedule is stored in. A mapper could
            // read the columns without naming the type, so both halves are banned.
            "ProgramSchedule",
            "scheduleType",
            "scheduleWeekdays",
            "scheduleSessionsPerWeek"
        )
        val found = offenders(phaseSources, forbidden)

        assertTrue(
            "the explicit target source is stated, never mapped out of the legacy schedule " +
                "vocabulary — neither the type nor the columns it is stored in: $found",
            found.isEmpty()
        )
    }

    @Test
    fun noProductionSourceMapsALegacyScheduleOntoATargetSchedule() {
        // The same absence claim the Phase 13 adapter gate makes, restated over the whole production
        // tree now that a second place could have grown the mapping: a file that names a legacy
        // schedule *and* constructs a target rule is a `ProgramSchedule -> TargetSchedule` inference.
        val offenders = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { source ->
                val text = code(source)
                val namesLegacy = listOf("ProgramSchedule", "LegacySchedule", "LegacyScheduleMapper")
                    .any { text.contains(it) }
                val buildsTarget = listOf(
                    "TargetSchedule(", "TargetSchedule.daily", "TargetSchedule.everyNDays",
                    "TargetSchedule.fixedWeekdays", "TargetSchedule.sessionsPerWeek",
                    "TargetSchedule.derivedExcluding", "TargetScheduleDefinition("
                ).any { text.contains(it) }
                if (namesLegacy && buildsTarget) source.name else null
            }
            .toList()

        assertTrue("no implicit ProgramSchedule -> TargetSchedule mapping may exist: $offenders", offenders.isEmpty())
    }

    // ---- 2. the cadence round-trips form-first -------------------------------------------------------

    @Test
    fun theCadenceIsReadFormFirstWithNoPayloadSniffingAndNoNormalization() {
        val mapper = code(mapperFile)
        val branches = Regex("ProgramTargetScheduleRuleEntity\\.(\\w+) ->")
            .findAll(mapper).map { it.groupValues[1] }.toList()

        assertEquals(
            "every one of the five cadence forms is read from its own discriminator branch, and each " +
                "branch is exactly the vocabulary the entity declares",
            listOf("DAILY", "EVERY_N_DAYS", "SESSIONS_PER_WEEK", "FIXED_WEEKDAYS", "DERIVED_EXCLUDING"),
            branches
        )
        // The `when` subject must be the stored discriminator and nothing else. A mapper that switched
        // on, say, the payload column would be reading cadence by shape — which is exactly how
        // `SessionsPerWeek(3)` becomes a three-day weekday set on the way through.
        // Two dispatchers, and each switches on its own value: the **read** on the stored
        // discriminator and the **write** on the domain cadence. Neither inspects a payload to decide
        // a form, which is what keeps `SessionsPerWeek(3)` from becoming a three-day weekday set.
        assertEquals(
            "the read dispatches on the stored discriminator and the write on the domain value, and " +
                "nothing else dispatches on this file",
            listOf("cadenceType", "cadence"),
            Regex("when \\((\\w+)\\) \\{").findAll(mapper).map { it.groupValues[1] }.toList()
        )
        assertTrue(
            "the read's dispatcher belongs to the stored entity, so it is reading storage",
            codeLines(mapperFile).any { line ->
                line.startsWith("internal fun ProgramTargetScheduleRuleEntity.storedCadence()")
            }
        )
        for (form in listOf("cadenceDays", "cadenceSessionsPerWeek", "cadenceWeekdays",
            "cadenceSourceRuleId")) {
            assertTrue(
                "the $form payload has its own column and its own branch",
                Regex(form).containsMatchIn(mapper)
            )
        }
        assertFalse(
            "and no cadence form is rewritten into another on the way out",
            Regex("(SessionsPerWeek|EveryNDays).*as (FixedWeekdays|SessionsPerWeek|EveryNDays)")
                .containsMatchIn(mapper)
        )
    }

    @Test
    fun theStoredCadenceVocabularyIsExactlyTheFiveFormsAndNothingElse() {
        assertEquals(
            "the discriminator tokens are the entity's own, so a token cannot be right for the file " +
                "and wrong for the database",
            listOf("DAILY", "EVERY_N_DAYS", "SESSIONS_PER_WEEK", "FIXED_WEEKDAYS", "DERIVED_EXCLUDING"),
            listOf(
                ProgramTargetScheduleRuleEntity.DAILY,
                ProgramTargetScheduleRuleEntity.EVERY_N_DAYS,
                ProgramTargetScheduleRuleEntity.SESSIONS_PER_WEEK,
                ProgramTargetScheduleRuleEntity.FIXED_WEEKDAYS,
                ProgramTargetScheduleRuleEntity.DERIVED_EXCLUDING
            )
        )
        assertTrue(
            "the derived cadence's source rule identity has a column of its own, so it is never " +
                "reconstructed",
            ProgramTargetScheduleRuleEntity::class.java.declaredFields
                .map { it.name }.contains("cadenceSourceRuleId")
        )
    }

    // ---- 3./10. identities are explicit and opaque ---------------------------------------------------

    @Test
    fun noTargetIdentityIsDerivedFromAPlanDayAPositionANameOrAnIdText() {
        // `\b`-anchored where the token is a substring of a name this layer legitimately uses:
        // `TargetProgramDayBinding` contains `ProgramDay`, and `programDayId` is a *stored column* the
        // binding table has to name. The claim is narrower: nothing on this path may *construct* an
        // identity out of a plan day, a position, a name, a date or a string.
        // Half (a): every identity is a **copy of its own stored column**. This is the positive form
        // of the claim, and it is checked on the assignment itself rather than on the absence of a
        // token — a mapper that copied `ruleId` correctly but derived `workoutId` from a plan day
        // would slip past a pure ban list, and would not slip past this.
        val mapper = code(mapperFile)
        assertTrue(
            "the rule identity is copied from the rule identity column",
            mapper.contains("ruleId = ruleId,")
        )
        assertTrue(
            "the workout identity is copied from the workout identity column",
            mapper.contains("workoutId = workoutId,")
        )
        assertTrue(
            "the binding's plan day is the stored column wrapped in the domain value, not a derived " +
                "identity",
            mapper.contains("programDayId = ProgramDayId(programDayId)")
        )
        assertTrue(
            "the anchor date is converted from the stored ISO column, not computed",
            mapper.contains("storedDate(\"program_target_schedule_rule.anchorDate\", anchorDate)")
        )

        // Half (b): nothing on the identity path parses or derives. The claim is scoped to *identity
        // and cadence-form* fields, so the one legitimate tokenizer on this path — the stored weekday
        // payload column, whose split is exactly what makes `FixedWeekdays` round-trip — is stated as
        // the single exception rather than banned wholesale.
        val identityAndForm = listOf(
            "ruleId", "workoutId", "anchorDate", "cadenceType", "cadenceSourceRuleId"
        )
        val parsing = listOf(
            "ProgramDay(", ".position", ".split(", ".substring", ".removePrefix", ".removeSuffix",
            ".indexOf(", ".lastIndexOf(", "Regex(", "toRegex(", "Pattern",
            "anchorDate.toString()", "ruleId.value", "workoutId.value", "dayOfWeek.toString()"
        )
        val found = codeLines(mapperFile)
            .filter { line -> identityAndForm.any { field -> line.contains(field) } }
            .filter { line -> parsing.any { token -> line.contains(token) } }
            .map { line -> "TargetScheduleSourceMappers.kt: $line" }

        assertTrue(
            "a target identity is its own stored column, never a plan day's position, name or id text, " +
                "and never a date. The cadence *payload* column is decoded (that is what a lossless " +
                "round trip requires), but no identity or cadence form is ever parsed out of one: $found",
            found.isEmpty()
        )
        val splits = codeLines(mapperFile).filter { it.contains(".split(") }
        assertEquals(
            "and the one split on this path is the stored weekday payload, read back into a set",
            1,
            splits.size
        )
        val splitLine = splits.single()
        assertTrue(
            "the split reads the stored `cadenceWeekdays` value: $splitLine",
            splitLine.contains("stored" + ".split(")
        )
        assertTrue(
            "and it is the stored-weekday reader, whose result is a set: $splitLine",
            splitLine.contains("fun storedWeekdays(stored: String): Set<DayOfWeek>")
        )
        assertTrue(
            "and the repository constructs no identity at all — it reads stored ones",
            codeLines(repositoryFile).none { it.contains("ProgramDayId(") }
        )
    }

    @Test
    fun everyTargetIdentityIsStoredInItsOwnColumn() {
        assertEquals(
            "each field of a rule is its own column, so reading one back is a column fetch rather than " +
                "a parse, and a form with no payload cannot be confused with one whose payload is zero",
            listOf(
                "revisionId",
                "ruleId",
                "workoutId",
                "cadenceType",
                "cadenceDays",
                "cadenceSessionsPerWeek",
                "cadenceWeekdays",
                "cadenceSourceRuleId",
                "anchorDate"
            ),
            ProgramTargetScheduleRuleEntity::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
        )
        assertEquals(
            "and a binding is two independent identities in two independent columns",
            listOf("revisionId", "workoutId", "programDayId"),
            ProgramTargetProgramDayBindingEntity::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
        )
        // The one index this stage declares, and the reason it is needed: `programDayId` is a foreign
        // key that is *not* part of the primary key, so without it SQLite full-scans the binding table
        // on every `program_day` modification — which the cascade performs. `revisionId` needs none: it
        // is the primary key's leading column on both tables.
        //
        // Read from the **source**, not through reflection: Room's `@Entity` is a Kotlin annotation
        // with default (BINARY) retention, so it is absent from a runtime `getAnnotation` and every
        // attempt to read it there reports a null rather than the index list.
        assertTrue(
            "the binding's non-key foreign key column is indexed, and nothing else is",
            code(bindingEntity).contains("indices = [Index(\"programDayId\")]")
        )
        assertFalse(
            "and the rule table declares no index at all, because its only read column — revisionId — " +
                "is a leading key column",
            code(ruleEntity).contains("indices =")
        )
    }

    @Test
    fun noPlaceholderIdentityIsEverSubstitutedForAStoredOne() {
        for (fabricated in listOf("\"legacy\"", "\"unknown\"", "\"placeholder\"", "\"TODO\"",
            "\"default\"", "auto-generated")) {
            val found = offenders(phaseSources, listOf(fabricated))
            assertTrue(
                "no placeholder identity is ever substituted for a stored one ($fabricated): $found",
                found.isEmpty()
            )
        }
    }

    // ---- 4./5. no execution, session or performance state -------------------------------------------

    @Test
    fun theSourceDependsOnNoExecutionSessionOrPerformanceState() {
        val forbidden = listOf(
            "ExistingOccurrence",
            "ActualResult",
            "PerformedWork",
            "WorkoutSession",
            "WorkoutSlot",
            "SessionRuntime",
            "SessionExercise",
            "SetResult",
            "OccurrenceExecution",
            "program_workout_slot",
            "workout_session",
            "program_set_log",
            "AdaptiveRepository",
            "ProgramAdaptiveRepository",
            "ProgramProgressRepository"
        )
        val found = offenders(phaseSources, forbidden)

        assertTrue(
            "a stored target source is configuration: what happened afterwards lives in the slot, the " +
                "session graph and the adaptive tables, and reconstructing any of it here would be " +
                "inventing a rule this phase must not set: $found",
            found.isEmpty()
        )
    }

    // ---- 6. the domain value's own boundary ----------------------------------------------------------

    @Test
    fun theDomainValueHoldsNoCollaboratorAndNoAmbientState() {
        val value = code(valueFile)
        assertTrue(
            "the value is a revision plus two explicitly stated lists",
            value.contains("class TargetScheduleSource(") &&
                value.contains("val revisionId: RevisionId") &&
                value.contains("val rules: List<TargetScheduleDefinition>") &&
                value.contains("val programDayBindings: List<TargetProgramDayBinding>")
        )
        for (forbidden in listOf(
            "Repository", "Dao", "Entity", "@Entity", "androidx", "android.", "Clock", "IdGenerator",
            "LocalDate.now", "Instant.now", "System.currentTimeMillis", "Random", "runBlocking",
            "suspend fun", "WorkoutSlot", "TargetPlan", "TargetScheduleDecision"
        )) {
            assertFalse(
                "the domain value is data, not a collaborator or an environment: it names $forbidden",
                value.contains(forbidden)
            )
        }
        // The **declared properties** are what a collaborator would have to appear on, and reading
        // them rather than the constructor's parameters is deliberate: `RevisionId` is a value class
        // over `String`, so the constructor erases it to `java.lang.String` and a parameter-based
        // check would either pass a `java.lang.String` through by accident or be forced to allow
        // `java.*` and stop meaning anything.
        assertEquals(
            "the value holds exactly three properties — a revision and two explicitly stated lists",
            listOf("revisionId", "rules", "programDayBindings"),
            TargetScheduleSource::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
        )
        // Read as a **banned-package** check rather than an expected-type check, and that is the
        // whole reason: `RevisionId` is an inline value class, so reflection reports its backing field
        // as a plain `java.lang.String` and its getter under a name-mangled form. Pinning the exact
        // type list would therefore have been pinning an erasure artefact — it would fail on a correct
        // value and pass on a wrong one that happened to erase the same way. The claim being made is
        // "no collaborator", so it is stated that way: nothing this value holds can be a repository, a
        // DAO, a Room entity, an id generator, a clock or anything from the platform or UI layers.
        val collaboratorPackages = listOf(
            "com.monkfitness.app.data.",
            "com.monkfitness.app.domain.usecase.TargetScheduleSourceRepository",
            "com.monkfitness.app.ui.",
            "com.monkfitness.app.viewmodel.",
            "com.monkfitness.app.di.",
            "androidx.",
            "android."
        )
        val heldTypes = TargetScheduleSource::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.genericType.typeName } +
            TargetScheduleSource::class.java.declaredMethods
                .filter { it.parameterCount == 0 && it.name.startsWith("get") }
                .map { it.genericReturnType.typeName }
        assertEquals(
            "and every type it holds is a domain or JDK type — never a repository, DAO, entity, " +
                "generator, clock, platform or UI collaborator",
            emptyList<String>(),
            heldTypes.filter { type ->
                collaboratorPackages.any { type.startsWith(it) }
            }
        )
        assertTrue(
            "and the two collections are the domain's own lists, which is what makes the copy at " +
                "construction meaningful",
            heldTypes.any { type -> type.contains("TargetScheduleDefinition") } &&
                heldTypes.any { type -> type.contains("TargetProgramDayBinding") }
        )
        assertTrue(
            "both lists are copied at construction, so a caller mutating its own list cannot rewrite " +
                "the value",
            value.contains("rules.toList()") && value.contains("programDayBindings.toList()")
        )
    }

    @Test
    fun theThreeReadOutcomesAreKeptApart() {
        val declared = TargetScheduleSourceRead::class.java.declaredClasses
            .filterNot { it.isInterface }
            .map { it.simpleName }
            .sorted()

        assertEquals(
            "a source exists, a source is missing and a source is unreadable are three different facts, " +
                "and collapsing missing into an empty source is the one this phase forbids",
            listOf("Malformed", "Missing", "Source"),
            declared
        )
    }

    // ---- 7./8. the bridge consumes the explicit source, and refuses an absent one ----------------------

    @Test
    fun theBridgeReadsTheExplicitSourceAndDecidesNothing() {
        val bridge = code(bridgeFile)
        assertTrue(
            "the bridge's only input is the source repository's read of one revision",
            bridge.contains("sourceRepository.sourceOf(revisionId)")
        )
        assertTrue(
            "and it forwards that read's own outcome rather than reshaping it, so a missing source stays " +
                "missing on the way to the caller",
            bridge.contains("suspend fun definitionsAndBindingsOf(revisionId: RevisionId): TargetScheduleSourceRead =")
        )
        val forbidden = listOf(
            "ProgramScheduler", "SlotPlanner", "ProgramSchedule", "scheduleType", "scheduleWeekdays",
            "scheduleSessionsPerWeek",
            // It must not decide the caller's other eight input values.
            "TargetScheduleWindow(", "CompositionSelection(", "asOf =", "ProgramPauseWindow(",
            // …and it must not run the pass.
            "TargetPlanner", "TargetSchedulePolicy", "TargetScheduleOrchestrator",
            "TargetScheduleApplicationService", "TargetScheduleSlotPersister", "TargetOccurrencePresenter",
            "TargetScheduleInputAdapter",
            // …nor reach for execution, sessions or performance.
            "ExistingOccurrence", "ActualResult", "WorkoutSession", "ProgramProgressRepository",
            // …nor ambient state.
            "LocalDate.now", "Instant.now", "System.currentTimeMillis", "Clock", "Random", "IdGenerator"
        )
        val found = offenders(listOf(bridgeFile), forbidden)

        assertTrue(
            "the bridge supplies the two lists a caller cannot honestly reconstruct, and decides nothing " +
                "else — no dates, no window, no composition, no pass, no ambient state: $found",
            found.isEmpty()
        )
        assertEquals(
            "and it holds exactly one collaborator: the source repository",
            listOf("com.monkfitness.app.data.repository.TargetScheduleSourceRepository"),
            TargetScheduleSourceBridge::class.java.declaredConstructors
                .single().parameterTypes.map { it.name }
        )
    }

    @Test
    fun aMissingSourceIsNeverConvertedIntoAnEmptyValidSource() {
        val repository = code(repositoryFile)
        assertTrue(
            "the repository's absence case is the typed one",
            repository.contains("TargetScheduleSourceRead.Missing(revisionId)")
        )
        assertFalse(
            "and no branch constructs a source out of an empty read",
            Regex("Source\\(\\s*TargetScheduleSource\\([^)]*emptyList").containsMatchIn(repository)
        )
        // The `when` over the three outcomes must be exhaustive, so a new outcome cannot be added
        // without deciding what an unwritten read means here.
        val outcomes = Regex("TargetScheduleSourceRead\\.(Source|Missing|Malformed)")
            .findAll(repository).map { it.groupValues[1] }.toSet()
        assertEquals(
            "the repository handles all three read outcomes",
            setOf("Source", "Missing", "Malformed"),
            outcomes
        )
    }

    // ---- 9. immutability ----------------------------------------------------------------------------

    @Test
    fun aSavedRevisionsSourceGainsNoUpdateAndNoDeletePath() {
        val dao = code(daoFile)
        assertFalse(
            "the DAO has no UPDATE: a revision's stated semantics are never rewritten in place",
            Regex("\\bUPDATE\\b", RegexOption.IGNORE_CASE).containsMatchIn(dao)
        )
        assertFalse(
            "and no DELETE: a rule or a binding is never dropped so a new one can take its place",
            Regex("\\bDELETE\\b", RegexOption.IGNORE_CASE).containsMatchIn(dao)
        )
        assertEquals(
            "inserts only — one for the rules, one for the bindings",
            2,
            Regex("@Insert").findAll(dao).count()
        )
        assertEquals(
            "and exactly two reads, both keyed on the revision and nothing else",
            2,
            Regex("@Query").findAll(dao).count()
        )
        for (statement in Regex("@Query\\(\"([^\"]*)\"\\)").findAll(dao).map { it.groupValues[1] }) {
            assertTrue(
                "a membership read is on `revisionId` and adds no substitute identity: $statement",
                statement.contains("WHERE `revisionId` = :revisionId")
            )
            for (substitute in listOf("LIKE", "GLOB", "MATCH", "substr(", ":anchorDate",
                ":cadenceType", ":ruleId", ":workoutId")) {
                assertFalse(
                    "and no read falls back to another column as a substitute identity ($substitute): " +
                        "$statement",
                    statement.contains(substitute)
                )
            }
        }

        val repository = code(repositoryFile)
        assertTrue(
            "the repository compares the stored source before it writes, and refuses a different one",
            repository.contains("ConflictingStoredSource") &&
                repository.contains("if (stored.source == source) return")
        )
    }

    @Test
    fun theSourceIsOwnedByTheRevisionAndDiesWithIt() {
        // Each table is named by its own entity, and each states the revision it belongs to. The
        // assertion reads the physical table names rather than a camel-cased approximation of them,
        // because the table name is what the migration and every SQL statement actually use.
        assertTrue(
            "the rule table is declared by the rule entity",
            code(ruleEntity).contains("tableName = \"program_target_schedule_rule\"")
        )
        assertTrue(
            "and the binding table by the binding entity",
            code(bindingEntity).contains("tableName = \"program_target_program_day_binding\"")
        )
        for (entity in listOf(ruleEntity, bindingEntity)) {
            assertTrue(
                "${entity.name} states the revision it belongs to, as a required column",
                code(entity).contains("val revisionId: String")
            )
        }
        val entities = code(ruleEntity) + code(bindingEntity)
        assertTrue(
            "the rule's membership is the pair (revisionId, ruleId) and the binding's is the pair " +
                "(revisionId, workoutId), so one revision states each once",
            code(ruleEntity).contains("primaryKeys = [\"revisionId\", \"ruleId\"]") &&
                code(bindingEntity).contains("primaryKeys = [\"revisionId\", \"workoutId\"]")
        )
        assertTrue(
            "both cascade with the revision that states them",
            entities.contains("ProgramRevisionEntity::class") &&
                Regex("onDelete = ForeignKey.CASCADE").findAll(entities).count() >= 3
        )
    }

    // ---- 11./12. the closed lists stay closed, and no cutover happened -------------------------------

    @Test
    fun theProductionReferenceListsThisPhaseTouchesStayClosedAndUnbroadened() {
        // The data-layer gate's closed lists grew by exactly the DAO, mapper and repository this phase
        // adds — one name each, no wildcard and no second owner.
        val architecture = File("src/test/java/com/monkfitness/app/data/repository")
            .let { dir -> if (dir.isDirectory) dir else File("app/$dir") }
        val source = File(architecture, "ProgramDataAccessArchitectureTest.kt").readText()
        for (added in listOf("ProgramTargetScheduleSourceDao", "TargetScheduleSourceMappers",
            "TargetScheduleSourceRepository")) {
            assertTrue(
                "the new $added is named in the closed list it belongs to",
                source.contains("\"$added\"")
            )
        }
        assertFalse(
            "and no list was turned into a prefix or a wildcard while doing it",
            source.contains("startsWith(\"ProgramTargetSchedule\")") ||
                source.contains("contains(\"TargetScheduleSource\")")
        )
    }

    @Test
    fun theTargetContourStaysASeparatelyCallableContourAndNothingWasCutOver() {
        // The legacy scheduler must not name this phase at all.
        for (legacy in listOf(
            File(usecaseDir, "ProgramScheduler.kt"),
            File(mainDir, "domain/program/SlotPlanner.kt"),
            File(mainDir, "domain/program/ScheduleCalendar.kt")
        )) {
            val text = code(legacy)
            for (token in listOf("TargetScheduleSource", "program_target_schedule_rule",
                "program_target_program_day_binding", "TargetScheduleSourceRepository",
                "ProgramTargetScheduleSourceDao")) {
                assertFalse(
                    "${legacy.name} must not reach the persisted target source: $token",
                    text.contains(token)
                )
            }
        }

        // The container wires the source beside the target contour and into nothing else.
        val container = code(File(mainDir, "di/AppContainer.kt"))
        assertTrue(
            "the composition root constructs the source repository and the bridge",
            container.contains("val targetScheduleSourceRepository: TargetScheduleSourceRepository =") &&
                container.contains("val targetScheduleSourceBridge: TargetScheduleSourceBridge =")
        )
        val legacyConstruction = container.substringAfter("val programScheduler: ProgramScheduler =")
            .substringBefore("val targetScheduleOccurrenceRepository")
        assertFalse(
            "the legacy Scheduler's own construction is unchanged by this phase",
            legacyConstruction.contains("TargetScheduleSource") || legacyConstruction.contains("targetScheduleSource")
        )

        // And exactly one production source consumes the bridge — the Stage 20 consumer — which is
        // what "the target contour is a separately callable production path" now means. The scan is
        // over *naming* the type rather than over constructing it, because Stage 20 revised this
        // claim rather than relaxing it: a construction-site scan would have read empty and gone on
        // claiming the bridge was unconsumed while a production consumer held it. The legacy half is
        // untouched: nothing in the legacy contour names it, so the bridge is still not on the path
        // the UI trains from.
        val consumers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "AppContainer.kt" }
            .filterNot { it.name == "TargetScheduleSourceBridge.kt" }
            .filterNot { it.name == "TargetScheduleSourceRepository.kt" }
            .filter { it.readText().contains("TargetScheduleSourceBridge") }
            .map { it.name }
            .toList()
        assertEquals(
            "the bridge has exactly one production consumer, the Stage 20 consumer; cutover is a later phase",
            listOf("TargetScheduleProductionConsumer.kt"),
            consumers
        )
    }

    @Test
    fun theLegacyContourAndTheTargetContourAreBothStillPresent() {
        // The phase adds storage beside the existing contours; it deletes and replaces nothing.
        for (present in listOf(
            File(usecaseDir, "ProgramScheduler.kt"),
            File(usecaseDir, "TargetScheduleOrchestrator.kt"),
            File(usecaseDir, "TargetScheduleInputAdapter.kt"),
            File(mainDir, "domain/program/SlotPlanner.kt"),
            daoFile,
            valueFile,
            bridgeFile
        )) {
            assertTrue("${present.name} is still there", present.isFile)
        }
    }

    @Test
    fun aFixedWeekdaySetIsStoredCanonicallySoTwoEqualSetsCompareEqual() {
        // A `Set<DayOfWeek>` has no order of its own, so the stored form has to be canonical or two
        // equal cadences would write different bytes and the immutability comparison would be a
        // coin flip. The order is the schema's existing `scheduleWeekdays` convention.
        val mapper = code(mapperFile)
        // Whitespace-collapsed, because the `FixedWeekdays(` call spans two lines and a gate that only
        // matched the single-line spelling would pass on nothing.
        val flattened = mapper.replace(Regex("\\s+"), " ")
        assertTrue(
            "the stored weekday order is ascending ISO, and the round trip rebuilds a set from it",
            flattened.contains("sortedBy { day -> day.value }") &&
                flattened.contains("ScheduleCadence.FixedWeekdays( storedWeekdays(")
        )
        assertEquals(
            "and Monday-first is what ascending ISO means",
            listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
            listOf(DayOfWeek.entries.minBy { it.value }, DayOfWeek.entries.maxBy { it.value })
        )
    }
}
