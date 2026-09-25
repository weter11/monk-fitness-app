package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mechanical boundary for Phase 11 target schedule application orchestration. */
class TargetScheduleApplicationServiceArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val serviceFile = File(mainDir, "domain/usecase/TargetScheduleApplicationService.kt")
    private val targetDir = File(mainDir, "domain/program/target")

    @Test
    fun theApplicationBoundaryLivesInDomainUsecase() {
        assertTrue(serviceFile.isFile)
        assertEquals("usecase", serviceFile.parentFile!!.name)
        assertTrue(!serviceFile.parentFile.canonicalPath.contains(targetDir.canonicalPath))
        assertTrue(!File(targetDir, "TargetScheduleApplicationService.kt").exists())
    }

    @Test
    fun onlyTheNamedApplicationAndDomainValuesAreCollaborators() {
        val text = code(serviceFile.readText())
        val forbidden = listOf(
            "androidx.room", "ProgramWorkoutSlotDao", "ProgramWorkoutSlotEntity", "Sqlite", "Room",
            "Clock", "Random", "UUID", "IdGenerator", "ProgramScheduleRepository",
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "TargetPlanner",
            "TargetSchedulePolicy", "TargetScheduleResolver", "TargetOccurrenceComposer",
            "TargetOccurrenceReconciler", "TargetSlotMaterializer", "WorkoutSlot", "SlotId"
        )
        val offenders = codeLines.filter { line -> forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) } }
        assertTrue("application dependencies: $offenders", offenders.isEmpty())
        assertTrue(text.contains("TargetScheduleSlotPersister"))
        assertTrue(text.contains("TargetScheduleDecision"))
        assertTrue(text.contains("TargetProgramDayBinding"))
        assertTrue(text.contains("TargetOccurrencePresentation"))
    }

    @Test
    fun presenterOwnsPresentationAndOnlyCreatedIsForwarded() {
        val code = code(serviceFile.readText())
        assertEquals(1, occurrences(code, "TargetOccurrencePresenter.present("))
        assertTrue(code.contains("targetScheduleDecision.created"))
        assertTrue(code.contains("programDayBindings"))
        assertTrue(codeLines.none { it.contains("TargetOccurrencePresentation(") })
        assertTrue(codeLines.none { it.contains("ProgramDayId(") })
    }

    @Test
    fun persisterOwnsPersistenceAndApplicationHasNoStorageOperations() {
        val code = code(serviceFile.readText())
        assertEquals(1, occurrences(code, "slotPersister.persist("))
        assertTrue(code.contains("slotPersister.persist("))
        assertTrue(codeLines.none { it.contains("slotByTargetOccurrenceKey") || it.contains("addSlots(") })
        assertTrue(codeLines.none { it.contains("ProgramScheduleRepository") })
    }

    @Test
    fun serviceConstructsNoWorkoutSlotAndDuplicatesNoMaterializationMapping() {
        val code = code(serviceFile.readText())
        assertTrue(codeLines.none { it.contains("WorkoutSlot(") || it.contains("SlotId(") })
        assertTrue(codeLines.none { it.contains("TargetSlotMaterializer") })
        assertTrue(codeLines.none { it.contains("SlotStatus") || it.contains("targetOccurrenceKey =") })
    }

    @Test
    fun serviceDoesNotRecomputeTargetSemanticsOrReadAmbientState() {
        val code = code(serviceFile.readText())
        val forbidden = listOf(
            "TargetPlanner", "TargetSchedulePolicy", "TargetScheduleResolver",
            "TargetOccurrenceComposer", "TargetOccurrenceReconciler", "ProgramScheduler",
            "SlotPlanner", "ScheduleCalendar", "Clock", "LocalDate.now", "currentTimeMillis",
            "Random", "UUID", "hashCode"
        )
        val offenders = codeLines.filter { line -> forbidden.any { token -> Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line) } }
        assertTrue("the service consumes an already-computed decision: $offenders", offenders.isEmpty())
    }

    @Test
    fun resultKeepsTheDecisionPresentationsAndPersistenceResultAsValues() {
        assertEquals(
            listOf("decision", "presentations", "persistenceResult"),
            TargetScheduleApplicationResult::class.java.declaredFields
                .filterNot { it.name == "\$stable" }
                .map { it.name }
        )
        assertEquals(
            listOf("apply"),
            TargetScheduleApplicationService::class.java.declaredMethods
                .map { method -> method.name.substringBefore('-') }
                .distinct()
        )
        val apply = TargetScheduleApplicationService::class.java.declaredMethods
            .single { method ->
                method.name.startsWith("apply-") &&
                    method.parameterTypes.toList() == listOf(
                        String::class.java,
                        String::class.java,
                        TargetScheduleDecision::class.java,
                        List::class.java,
                        kotlin.coroutines.Continuation::class.java
                    )
            }
        assertEquals(
            "com.monkfitness.app.domain.usecase.TargetScheduleApplicationResult",
            apply.genericParameterTypes.last().typeName
                .substringAfter("kotlin.coroutines.Continuation<? super ")
                .substringBefore('>')
        )
    }

    @Test
    fun typedFailuresPropagateWithoutCatchOrFallback() {
        val code = code(serviceFile.readText())
        assertTrue(codeLines.none { it.contains("catch") || it.contains("try {") })
        assertEquals(1, occurrences(code, "return TargetScheduleApplicationResult("))
        assertTrue(codeLines.none { it.contains("emptyList()") })
        assertTrue(code.contains("TargetScheduleSlotPersistenceInput("))
    }

    @Test
    fun legacySchedulerContourIsUnwiredAndUnchangedByThisBoundary() {
        val scheduler = File(mainDir, "domain/usecase/ProgramScheduler.kt")
        val planner = File(mainDir, "domain/program/SlotPlanner.kt")
        val calendar = File(mainDir, "domain/program/ScheduleCalendar.kt")
        assertTrue(scheduler.isFile && planner.isFile && calendar.isFile)
        assertTrue(!scheduler.readText().contains("TargetScheduleApplicationService"))
        assertTrue(!planner.readText().contains("TargetScheduleApplicationService"))
        assertTrue(!calendar.readText().contains("TargetScheduleApplicationService"))
    }

    @Test
    fun appContainerWiresTheServiceBesideThePersisterWithoutRewiringLegacyScheduler() {
        val container = File(mainDir, "di/AppContainer.kt").readText()
        assertEquals(1, occurrences(container, "TargetScheduleApplicationService("))
        assertTrue(container.contains("val targetScheduleApplicationService: TargetScheduleApplicationService"))
        assertTrue(container.contains("slotPersister = targetScheduleSlotPersister"))
        val serviceConstruction = container.substringAfter("val targetScheduleApplicationService")
            .substringBefore("val standardProgramBootstrap")
        assertTrue(!serviceConstruction.contains("ProgramScheduleRepository"))
        assertTrue(!serviceConstruction.contains("IdGenerator"))
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
        get() = code(serviceFile.readText()).lines().map { it.trim() }.filter { it.isNotEmpty() }
}
