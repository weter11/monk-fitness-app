package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mechanical boundary for Phase 12 target schedule orchestration. */
class TargetScheduleOrchestratorArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val orchestratorFile = File(mainDir, "domain/usecase/TargetScheduleOrchestrator.kt")
    private val targetDir = File(mainDir, "domain/program/target")

    @Test
    fun theOrchestratorLivesInDomainUsecaseAndNotInTheTargetPackage() {
        assertTrue(orchestratorFile.isFile)
        assertEquals("usecase", orchestratorFile.parentFile!!.name)
        assertTrue(!orchestratorFile.parentFile.canonicalPath.contains(targetDir.canonicalPath))
        assertTrue(!File(targetDir, "TargetScheduleOrchestrator.kt").exists())
    }

    @Test
    fun theOnlyServiceCollaboratorIsTheApplicationBoundary() {
        val code = code(orchestratorFile.readText())
        assertEquals(1, occurrences(code, "applicationService: TargetScheduleApplicationService"))
        assertEquals(1, occurrences(code, "applicationService.apply("))
        val forbidden = listOf(
            "ProgramScheduleRepository", "ProgramPlanRepository", "ProgramRepository", "ProgramScheduler",
            "SlotPlanner", "ScheduleCalendar", "IdGenerator", "TargetScheduleSlotPersister",
            "TargetScheduleResolver", "TargetOccurrenceComposer", "TargetOccurrenceReconciler",
            "TargetOccurrencePresenter", "TargetSlotMaterializer", "androidx.room", "ProgramWorkoutSlotDao",
            "ProgramWorkoutSlotEntity", "Sqlite", "Random", "UUID", "MutableState", "mutableStateOf",
            "companion object", "mutableListOf", "HashMap", "LinkedHashMap"
        )
        val offenders = codeLines.filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }
        assertTrue("orchestrator dependencies: $offenders", offenders.isEmpty())
    }

    @Test
    fun theOrchestratorStopsAtPlannerPolicyAndApplication() {
        val code = code(orchestratorFile.readText())
        assertEquals(1, occurrences(code, "TargetPlanner.plan("))
        assertEquals(1, occurrences(code, "TargetSchedulePolicy.decide("))
        assertEquals(1, occurrences(code, "applicationService.apply("))
        assertTrue(code.indexOf("TargetPlanner.plan(") < code.indexOf("TargetSchedulePolicy.decide("))
        assertTrue(code.indexOf("TargetSchedulePolicy.decide(") < code.indexOf("applicationService.apply("))
    }

    @Test
    fun thePolicyIsTheOnlyTemporalAuthority() {
        val code = code(orchestratorFile.readText())
        assertEquals(1, occurrences(code, "targetScheduleDecision = decision"))
        assertTrue(code.contains("targetPlan = targetPlan"))
        assertTrue(code.contains("existing = request.existing"))
        assertTrue(code.contains("asOf = request.asOf"))
        assertTrue(code.contains("pauses = request.pauses"))
        val dateLogic = listOf(
            "isBefore", "isAfter", "isPaused", "covers", "PAUSE", "MISSED", "SUPERSEDED", "PAST",
            "FUTURE", "RETAINED", "filter", "sortedWith", "sortedBy", "distinctBy", "groupBy", "sorted("
        )
        val offenders = codeLines.filter { line ->
            dateLogic.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }
        assertTrue("no second temporal policy: $offenders", offenders.isEmpty())
    }

    @Test
    fun theOrchestratorPerformsNoManualPresentationAndNoSlotMaterialization() {
        val offenders = codeLines.filter { line ->
            listOf("TargetOccurrencePresentation(", "ProgramDayId(", "WorkoutSlot(", "SlotId(")
                .any { token -> line.contains(token) }
        }
        assertTrue("no manual construction: $offenders", offenders.isEmpty())
        assertTrue(codeLines.none { it.contains("targetOccurrenceKey") })
        assertTrue(codeLines.none { it.contains("SlotStatus") })
    }

    @Test
    fun theOrchestratorOwnsNoIdentityAndNoAmbientState() {
        val ambient = listOf(
            "Clock", "System.currentTimeMillis", "Instant.now", "LocalDate.now", "MutableState",
            "mutableStateOf", "lateinit", "System.identityHashCode", "hashCode()", "runBlocking"
        )
        val offenders = codeLines.filter { line ->
            ambient.any { token -> line.contains(token) }
        }
        assertTrue("no ambient state: $offenders", offenders.isEmpty())
        val requestFields = TargetScheduleOrchestrationRequest::class.java.declaredFields
            .filterNot { it.name == "\$stable" }
            .map { it.name }
        assertEquals(
            listOf(
                "programId", "revisionId", "schedules", "window", "selection", "existing",
                "sources", "asOf", "pauses", "programDayBindings"
            ),
            requestFields
        )
    }

    @Test
    fun theResultKeepsAllThreeSemanticBoundariesSeparate() {
        assertEquals(
            listOf("request", "targetPlan", "decision", "applicationResult"),
            TargetScheduleOrchestrationResult::class.java.declaredFields
                .filterNot { it.name == "\$stable" }
                .map { it.name }
        )
        val apply = TargetScheduleOrchestrator::class.java.declaredMethods
            .single { method -> method.name.substringBefore('-') == "apply" }
        assertEquals(
            listOf(
                TargetScheduleOrchestrationRequest::class.java,
                kotlin.coroutines.Continuation::class.java
            ),
            apply.parameterTypes.toList()
        )
        assertEquals(
            "com.monkfitness.app.domain.usecase.TargetScheduleOrchestrationResult",
            apply.genericParameterTypes.last().typeName
                .substringAfter("kotlin.coroutines.Continuation<? super ")
                .substringBefore('>')
        )
    }

    @Test
    fun typedFailuresPropagateWithoutCatchOrFallback() {
        val code = code(orchestratorFile.readText())
        assertTrue(codeLines.none { it.contains("catch") || it.contains("try {") })
        assertTrue(codeLines.none { it.contains("runCatching") || it.contains("emptyList()") })
        assertEquals(1, occurrences(code, "return TargetScheduleOrchestrationResult("))
    }

    @Test
    fun theLegacySchedulerContourIsUnwiredAndUnchanged() {
        val scheduler = File(mainDir, "domain/usecase/ProgramScheduler.kt")
        val planner = File(mainDir, "domain/program/SlotPlanner.kt")
        val calendar = File(mainDir, "domain/program/ScheduleCalendar.kt")
        assertTrue(scheduler.isFile && planner.isFile && calendar.isFile)
        listOf(scheduler, planner, calendar).forEach { file ->
            assertTrue(
                "${file.name} must not reach the target orchestrator",
                !file.readText().contains("TargetScheduleOrchestrator")
            )
        }
        assertTrue(!orchestratorFile.readText().contains("ProgramScheduler"))
        assertTrue(!orchestratorFile.readText().contains("SlotPlanner"))
        assertTrue(!orchestratorFile.readText().contains("ScheduleCalendar"))
    }

    @Test
    fun appContainerWiresTheOrchestratorBesideThePersisterAndTheApplicationBoundary() {
        val container = code(File(mainDir, "di/AppContainer.kt").readText())
        assertEquals(1, occurrences(container, "TargetScheduleOrchestrator("))
        assertTrue(container.contains("val targetScheduleOrchestrator: TargetScheduleOrchestrator"))
        assertTrue(container.contains("applicationService = targetScheduleApplicationService"))
        val wiring = container.substringAfter("val targetScheduleOrchestrator")
            .substringBefore("val standardProgramBootstrap")
        val forbidden = listOf(
            "ProgramScheduleRepository", "ProgramPlanRepository", "ProgramRepository", "IdGenerator",
            "clock", "ProgramScheduler", "SlotPlanner", "ScheduleCalendar"
        )
        val offenders = wiring.lines().filter { line ->
            forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) }
        }
        assertTrue("orchestrator wiring: $offenders", offenders.isEmpty())
    }

    @Test
    fun theStageElevenBoundaryRemainsTheOnlyProducerOfTheApplicationResult() {
        val code = code(orchestratorFile.readText())
        assertTrue(code.contains("TargetScheduleApplicationResult"))
        assertTrue(codeLines.none { it.contains("TargetScheduleApplicationResult(") })
        assertTrue(TargetScheduleDecision::class.java.declaredFields.isNotEmpty())
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun code(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private val codeLines: List<String>
        get() = code(orchestratorFile.readText()).lines().map { it.trim() }.filter { it.isNotEmpty() }
}
