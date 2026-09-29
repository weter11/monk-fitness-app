package com.monkfitness.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The mechanical boundary of §30 step 21 — the **first controlled production invocation** of the
 * target scheduling contour.
 *
 * This stage's subject is a *wiring* claim, and a wiring claim that lives only in a KDoc erodes the
 * first time a convenient import appears. So each is asserted here as a token or a shape in real
 * code, with comments stripped first — which is what lets the production files say "never reads the
 * legacy schedule" and "no default was invented" without tripping the rules about doing either.
 *
 * ```text
 *  1. exactly one production caller of the target consumer, and it is this stage's node
 *  2. that caller is an application boundary with exactly the four collaborators it needs
 *  3. no screen, no view model and no screen-adjacent file builds any target run context
 *  4. the five caller-owned values are stated, not derived: the window is its own policy
 *  5. the as-of date is the started Program's own fact, and no ambient time is read anywhere
 *  6. the composed-source map is empty, and a derived rule is never answered with a fabricated source
 *  7. the pause adapter is target-owned, takes its zone explicitly, and invents no last date
 *  8. no legacy planning vocabulary, no second scheduler and no slot write reaches this boundary
 *  9. lifecycle policy stays in its own file, and target policy never enters it
 * 10. a target refusal is carried, not caught and not converted into a success
 * ```
 */
class TargetScheduleControlledInvocationArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val usecaseDir = File(mainDir, "domain/usecase")
    private val consumerFile = File(usecaseDir, "TargetScheduleProductionConsumer.kt")
    private val startFile = File(usecaseDir, "ProgramStartService.kt")
    private val adapterFile = File(usecaseDir, "TargetSchedulePauseAdapter.kt")
    private val containerFile = File(mainDir, "di/AppContainer.kt")
    private val policyFile = File(mainDir, "domain/program/ProgramLifecyclePolicy.kt")
    private val lifecycleFile = File(usecaseDir, "ProgramLifecycleService.kt")

    // ---- 1/2. the single caller ---------------------------------------------------------------------

    @Test
    fun exactlyOneProductionCallerOfTheTargetConsumerAndItIsThisStagesNode() {
        listOf(startFile, adapterFile).forEach { assertTrue("${it.name} must exist", it.isFile) }

        val callers = productionSources()
            .filterNot { (path, _) -> path == "di/AppContainer.kt" }
            .filterNot { (path, _) -> path.endsWith("TargetScheduleProductionConsumer.kt") }
            .filter { (_, text) -> text.contains("TargetScheduleProductionConsumer") }
            .map { (path, _) -> path }

        assertEquals(
            "the contour is now reachable, and from exactly one place: the composed Start. A screen, " +
                "the save boundary, the import boundary, the bootstrap and the session runtime are all " +
                "still off it, and a second application caller still fails here.",
            listOf("domain/usecase/ProgramStartService.kt"),
            callers
        )

        // And it really is a call, not merely a mention: a file that only *holds* the type would be
        // a different claim, and this one is "one node invokes the consumer".
        assertEquals(
            "the caller invokes the consumer exactly once",
            1,
            occurrences(code(startFile.readText()), "consumer.run(")
        )
        assertEquals(
            "and it declares it as a collaborator",
            1,
            occurrences(code(startFile.readText()), "private val consumer: TargetScheduleProductionConsumer")
        )
    }

    @Test
    fun theCallerIsAnApplicationBoundaryHoldingExactlyWhatTheCompositionNeeds() {
        assertEquals("usecase", startFile.parentFile.name)
        val declared = Regex("private val (\\w+): ([\\w.]+)")
            .findAll(code(startFile.readText()))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        assertEquals(
            "the lifecycle owner it starts through, the consumer it invokes, the repository the " +
                "persisted pauses are read from, and the calendar they are read in — and nothing " +
                "else. A second scheduler, a clock or a UI port here would be a layer reached around.",
            listOf(
                "lifecycle" to "ProgramLifecycleService",
                "consumer" to "TargetScheduleProductionConsumer",
                "scheduleRepository" to "ProgramScheduleRepository",
                "zone" to "ZoneId"
            ),
            declared
        )
        assertEquals(
            4,
            ProgramStartService::class.java.declaredConstructors.single().parameterCount
        )
    }

    // ---- 3. the UI sees one operation ----------------------------------------------------------------

    @Test
    fun noScreenOrViewModelBuildsAnyPartOfATargetRunContext() {
        val uiAndViewModel = productionSources().filter { (path, _) ->
            path.startsWith("ui/") || path.startsWith("viewmodel/")
        }
        assertTrue(
            "the sweep must actually cover the UI tree, or it proves nothing",
            uiAndViewModel.size >= 20
        )
        val contextVocabulary = listOf(
            "TargetScheduleRunContext", "TargetScheduleWindow", "CompositionSelection",
            "ResolvedScheduleSource", "ProgramPauseWindow", "TargetSchedulePauseAdapter",
            "TargetScheduleProductionConsumer"
        )
        val offenders = uiAndViewModel.filter { (_, text) ->
            contextVocabulary.any { token -> text.contains(token) }
        }.map { (path, _) -> path }

        assertTrue(
            "a screen may ask for one application operation and nothing else: it builds no window, " +
                "no selection, no resolved source, no pause window and no run context: $offenders",
            offenders.isEmpty()
        )

        // The positivity of the ban, so it is a rule rather than a statement about today's tree.
        val careless = "    val context = TargetScheduleRunContext(window = TargetScheduleWindow(a, b))"
        assertTrue(
            "the rule fires on the line a careless screen would write",
            contextVocabulary.any { token -> careless.contains(token) }
        )
    }

    // ---- 4/5. the five stated values ----------------------------------------------------------------

    @Test
    fun theRunContextIsStatedFieldForFieldAndTheWindowIsThisStagesOwnPolicy() {
        val body = code(startFile.readText())
        val construction = body.substringAfter("TargetScheduleRunContext(")
            .substringBefore(")\n        return when")

        listOf(
            "window = TargetScheduleWindow(asOf, asOf.plusDays(WINDOW_DAYS - 1))",
            "selection = CompositionSelection()",
            "sources = emptyMap()",
            "asOf = asOf",
            "pauses = TargetSchedulePauseAdapter.windowsOf("
        ).forEach { field ->
            assertTrue("the context states `$field`: $construction", construction.contains(field))
        }
        assertEquals(
            "and the window is an inclusive count of dates, not a horizon borrowed from elsewhere",
            30L,
            ProgramStartService.WINDOW_DAYS
        )
        assertTrue(
            "the count is used as a subtraction, so asOf..asOf+29 is exactly thirty dates rather " +
                "than thirty-one",
            code(startFile.readText()).contains("WINDOW_DAYS - 1")
        )
    }

    @Test
    fun theAsOfDateIsTheStartedProgramsOwnFactAndNoAmbientTimeIsReadAnywhere() {
        val body = code(startFile.readText())
        assertTrue(
            "the as-of date is derived from the Program the lifecycle transition returned",
            body.contains("started.actualStartDate?.atZone(zone)?.toLocalDate()")
        )
        listOf(
            "LocalDate.now" to "the device's date",
            "Instant.now" to "the device's instant",
            "System.currentTimeMillis" to "the system clock",
            "clock.now" to "a second clock read",
            "System.currentTimeMillis()" to "the system clock"
        ).forEach { (token, what) ->
            listOf(startFile to body, adapterFile to code(adapterFile.readText())).forEach { (file, text) ->
                assertFalse(
                    "${file.name} reads $what; the as-of date is the start date the transition " +
                        "already recorded",
                    text.contains(token)
                )
            }
        }
    }

    // ---- 6. the composed sources -------------------------------------------------------------------

    @Test
    fun noResolvedSourceIsEverSynthesisedAndADerivedRuleIsLeftToTheResolver() {
        val body = code(startFile.readText())
        assertFalse(
            "this boundary must not name the resolved-source type at all: stating the map empty is " +
                "the whole policy, and naming the value is the first step towards filling it",
            body.contains("ResolvedScheduleSource")
        )
        assertFalse(
            "and it must not name the derived cadence, because answering one is the resolver's job " +
                "and refusing it is the resolver's vocabulary",
            body.contains("DerivedExcluding")
        )
        assertTrue(
            "the map is stated empty, once, at the field itself",
            body.contains("sources = emptyMap()")
        )
    }

    // ---- 7. the pause adapter ----------------------------------------------------------------------

    @Test
    fun thePauseAdapterIsTargetOwnedTakesItsZoneAndInventsNoLastDate() {
        val body = code(adapterFile.readText())

        assertEquals(
            "the adapter is a pure value conversion with no collaborator at all",
            emptyList<Pair<String, String>>(),
            Regex("private val (\\w+): ([\\w.]+)").findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()
        )
        assertTrue(
            "the calendar is an explicit argument, never read from the device",
            body.contains("zone: ZoneId") && !body.contains("ZoneId.systemDefault")
        )
        assertTrue(
            "both ends of a closed interval are read in that calendar",
            body.contains("pause.startedAt.atZone(zone).toLocalDate()") &&
                body.contains("endedAt.atZone(zone).toLocalDate()")
        )
        assertTrue(
            "an open interval is refused rather than given an invented end",
            body.contains("require(endedAt != null)")
        )
        listOf("LocalDate.MAX", "LocalDate.now", "ChronoUnit", "window.lastDate").forEach { invention ->
            assertFalse(
                "the adapter must not manufacture a last date ($invention)",
                body.contains(invention)
            )
        }
        // The refusal is the only answer, and it is stated where the missing end is.
        assertEquals(
            "exactly one branch answers a missing end, and it refuses",
            1,
            occurrences(body, "require(endedAt != null)")
        )
    }

    // ---- 8. legacy isolation -----------------------------------------------------------------------

    @Test
    fun theBoundaryNamesNoLegacyPlanningVocabularyAndWritesNoSlot() {
        val startCode = code(startFile.readText())
        val adapterCode = code(adapterFile.readText())
        val legacyVocabulary = listOf(
            "ProgramSchedule", "ScheduleWindow", "PLANNING_HORIZON_DAYS", "ScheduleHorizon",
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "WorkoutSlot", "SlotStatus",
            "plannedStartDate", "revision.duration", "pausedInterval"
        )
        listOf(startFile to startCode, adapterFile to adapterCode).forEach { (file, text) ->
            val offenders = legacyVocabulary.filter { token ->
                Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(text)
            }
            assertTrue(
                "${file.name} must not reach the legacy planning contour or its values: $offenders",
                offenders.isEmpty()
            )
        }

        assertEquals(
            "and the schedule repository is asked for one thing: the persisted pause intervals",
            1,
            occurrences(startCode, "scheduleRepository.pausesOfProgram(")
        )
        listOf("insertSlot", "updateSlot", "deleteSlot", "reconcile", "saveSlots").forEach { write ->
            assertFalse(
                "the target invocation writes target-owned persistence only ($write)",
                startCode.contains(write) || adapterCode.contains(write)
            )
        }
        assertEquals(
            "the legacy planner is still constructed in exactly one place",
            1,
            constructionSites(code(containerFile.readText()), "ProgramScheduler")
        )
        listOf("ProgramScheduler.kt", "SlotPlanner.kt", "ScheduleCalendar.kt").forEach { name ->
            val legacy = File(usecaseDir, name).takeIf { it.isFile }
                ?: File(mainDir, "domain/program/$name")
            assertTrue("$name must exist", legacy.isFile)
            assertTrue(
                "$name must not reach the target contour",
                !legacy.readText().contains("TargetSchedule")
            )
        }
    }

    @Test
    fun theBoundaryHoldsNoIdentityGeneratorNoStorageTypeAndNoSecondScheduler() {
        val startCode = code(startFile.readText())
        listOf("IdGenerator", "Random", "nextId", "Clock", "com.monkfitness.app.data.local",
            "androidx.room", "AppDatabase", "Dao", "Entity").forEach { token ->
            assertTrue(
                "the composed Start must not reach for $token",
                !startCode.contains(token)
            )
        }
    }

    // ---- 9. ownership ------------------------------------------------------------------------------

    @Test
    fun lifecyclePolicyStaysInItsOwnFileAndTargetPolicyNeverEntersIt() {
        val policy = code(policyFile.readText())
        listOf("TargetSchedule", "TargetScheduleWindow", "CompositionSelection",
            "ProgramPauseWindow", "ResolvedScheduleSource", "ProgramStartService").forEach { token ->
            assertTrue(
                "the lifecycle transition table must not learn about target scheduling ($token)",
                !policy.contains(token)
            )
        }
        assertTrue(
            "and it is the lifecycle service, not this stage's node, that asks it",
            code(lifecycleFile.readText()).contains("ProgramLifecyclePolicy.decision(")
        )
        val startCode = code(startFile.readText())
        assertFalse(
            "the composed Start asks the lifecycle service for the transition, never the policy " +
                "itself: a second decision table would be a second lifecycle",
            startCode.contains("ProgramLifecyclePolicy") || startCode.contains("ProgramTransition")
        )
    }

    // ---- 10. failure semantics ---------------------------------------------------------------------

    @Test
    fun everyTargetRefusalIsCarriedAndNoneIsCaughtIntoASuccess() {
        val body = code(startFile.readText())

        // The typed absences the consumer can report are named, and every one of them lands in the
        // refusal case — a `when` that omits one does not compile, which is the point: the compiler
        // is the first half of this claim and the text is the second.
        listOf(
            "TargetScheduleRunResult.ProgramNotFound",
            "TargetScheduleRunResult.CurrentRevisionMissing",
            "TargetScheduleRunResult.SourceMissing",
            "TargetScheduleRunResult.SourceMalformed"
        ).forEach { absence ->
            assertTrue("the mapping names $absence: $body", body.contains(absence))
        }
        assertTrue(
            "and they all land in the case that keeps the started Program beside the refusal",
            body.contains("ProgramStartResult.TargetSchedulingRefused(started, run)")
        )
        assertTrue(
            "while a scheduled run lands in the success case, and nowhere else",
            body.contains("ProgramStartResult.Started(started, run)")
        )
        assertEquals(
            "the composed operation carries exactly the four outcomes the stage names",
            listOf(
                "StartFailed", "StartRefused", "Started", "TargetSchedulingRefused"
            ),
            ProgramStartResult::class.java.declaredClasses.map { it.simpleName }.sorted()
        )

        // A catch would be how a target refusal is turned into a success, so the file carries none.
        listOf("catch", "runCatching", "try {").forEach { token ->
            assertFalse(
                "the composed Start translates no failure ($token)",
                body.contains(token) || code(adapterFile.readText()).contains(token)
            )
        }
        // The success case cannot hold a refusal in the first place: its own field is typed as the
        // success arm of the consumer's vocabulary, so a `SourceMissing` does not compile there.
        // That is the structural half of "a refusal is never presented as the start's success".
        val started = ProgramStartResult::class.java.declaredClasses
            .single { it.simpleName == "Started" }
        val refused = ProgramStartResult::class.java.declaredClasses
            .single { it.simpleName == "TargetSchedulingRefused" }
        assertEquals(
            "the start's success carries the consumer's *success* result",
            TargetScheduleRunResult.Scheduled::class.java.name,
            started.declaredFields.single { it.name == "targetScheduling" }.type.name
        )
        assertEquals(
            "while the refusal case carries the whole vocabulary, so which absence it was survives",
            TargetScheduleRunResult::class.java.name,
            refused.declaredFields.single { it.name == "targetScheduling" }.type.name
        )
    }

    @Test
    fun theTargetPassRunsOnlyAfterASuccessfulStart() {
        val body = code(startFile.readText())
        val start = body.indexOf("lifecycle.startProgram(programId)")
        val consumer = body.indexOf("consumer.run(programId, context)")
        val refused = body.indexOf("ProgramStartResult.StartRefused(")
        val failed = body.indexOf("ProgramStartResult.StartFailed(outcome.cause)")

        assertTrue("the lifecycle call is there", start >= 0)
        assertTrue("the consumer call is there", consumer >= 0)
        assertTrue("the start is decided before anything is invoked", start < consumer)
        assertTrue(
            "a refused start returns before the target pass is reached",
            refused in 0 until consumer
        )
        assertTrue(
            "and so does a failed start — a target pass must never run for a Program that did not start",
            failed in 0 until consumer
        )
        // The *first* reach is what matters here, not a later one: a pass composed above the
        // transition would leave every index above still in order while planning for a Program that
        // has not started. So the claim is stated on the first occurrence, and on there being
        // exactly one occurrence at all — a second reach is a second pass, not a re-read.
        assertEquals(
            "and the consumer is reached exactly once, so no pass is composed before the start and " +
                "none is composed twice",
            1,
            occurrences(body, "consumer.run(")
        )
        assertTrue(
            "the first reach of the consumer is strictly after the lifecycle transition",
            body.indexOf("consumer.run(") > start
        )
    }

    // ---- the composition root ----------------------------------------------------------------------

    @Test
    fun theCompositionRootWiresTheNodeOnceAndHandsItTheGraphsOwnZone() {
        val container = code(containerFile.readText())
        assertTrue(
            "the composed Start is a graph node, as a property like every other node",
            container.contains("val programStartService: ProgramStartService =")
        )
        assertEquals(
            "and it is constructed exactly once",
            1,
            constructionSites(container, "ProgramStartService")
        )
        val wiring = container.substringAfter("val programStartService")
            .substringBefore("val programSaveService")
        listOf(
            "lifecycle = programLifecycleService",
            "consumer = targetScheduleProductionConsumer",
            "scheduleRepository = programScheduleRepository",
            "zone = zone"
        ).forEach { collaborator ->
            assertTrue("the wiring states `$collaborator`", wiring.contains(collaborator))
        }
        val wiringOffenders = wiring.lines().filter { line ->
            listOf("ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "IdGenerator", "Clock")
                .any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }
        assertTrue(
            "the composed Start is handed the lifecycle owner, the target consumer, the pause " +
                "repository and the graph's own calendar — and no second scheduler and no clock: " +
                "$wiringOffenders",
            wiringOffenders.isEmpty()
        )
    }

    @Test
    fun theStageAddsNoFileToThePureTargetPackage() {
        val targetDir = File(mainDir, "domain/program/target")
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().map { it.name }.sorted()

        assertEquals(
            "the target scheduling policy belongs to the application boundary, not to the pure " +
                "package: putting the window or the pause adapter there would give a pure component " +
                "a persistence-flavoured concern. The list stays closed — a twelfth file still fails here.",
            listOf(
                "TargetExistingOccurrence.kt",
                "TargetOccurrenceComposer.kt",
                "TargetOccurrenceExecutionPolicy.kt",
                "TargetOccurrenceExecutionRead.kt",
                "TargetOccurrencePersistence.kt",
                "TargetOccurrencePresenter.kt",
                "TargetOccurrenceReconciler.kt",
                "TargetPlanner.kt",
                "TargetSchedulePolicy.kt",
                "TargetScheduleResolver.kt",
                "TargetSlotMaterializer.kt"
            ),
            sources
        )
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun productionSources(): List<Pair<String, String>> = File(mainDir.path)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { file -> file.relativeTo(mainDir).path.replace('\\', '/') to code(file.readText()) }
        .toList()

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun constructionSites(text: String, name: String): Int {
        var count = 0
        var index = text.indexOf("$name(")
        while (index >= 0) {
            val before = text.substring(maxOf(0, index - 8), index)
            val insideALongerName = before.isNotEmpty() &&
                (before.last().isLetterOrDigit() || before.last() == '_')
            if (!insideALongerName && !before.contains("class ")) count++
            index = text.indexOf("$name(", index + 1)
        }
        return count
    }

    private fun code(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")
}
