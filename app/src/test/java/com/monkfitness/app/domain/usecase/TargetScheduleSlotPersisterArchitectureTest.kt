package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import com.monkfitness.app.domain.program.target.TargetSlotMaterializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mechanical boundary for Phase 10 target schedule slot persistence. */
class TargetScheduleSlotPersisterArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val persisterFile = File(mainDir, "domain/usecase/TargetScheduleSlotPersister.kt")
    private val targetDir = File(mainDir, "domain/program/target")

    @Test
    fun theIntegrationLivesOutsideThePureTargetPackage() {
        assertTrue(persisterFile.isFile)
        assertTrue(!persisterFile.parentFile.canonicalPath.contains(targetDir.canonicalPath))
        assertTrue(File(targetDir, "TargetSlotMaterializer.kt").isFile)
        assertTrue(!File(targetDir, "TargetScheduleSlotPersister.kt").exists())
    }

    @Test
    fun persistenceDependencyAppearsOnlyAtTheIntegrationBoundary() {
        val targetSources = targetDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val offenders = targetSources.mapNotNull { source ->
            if (source.readText().contains("ProgramScheduleRepository") ||
                source.readText().contains("IdGenerator") ||
                source.readText().contains("addSlots(")
            ) source.path else null
        }

        assertTrue("pure target components must not depend on persistence or id generation: $offenders", offenders.isEmpty())
        assertTrue(persisterFile.readText().contains("ProgramScheduleRepository"))
        assertTrue(persisterFile.readText().contains("IdGenerator"))
    }

    @Test
    fun theIntegrationUsesTheRepositoryPortButNoRoomDaoOrEntity() {
        val code = codeLines(persisterFile)
        val forbidden = listOf(
            "androidx.room", "AppDatabase", "ProgramWorkoutSlotDao", "ProgramWorkoutSlotEntity",
            "Sqlite", "Room", "insertSlots", "updateOutcome", "recordSlotOutcome"
        )
        val offenders = code.filter { line -> forbidden.any { token -> line.contains(token) } }

        assertTrue("the integration reaches storage only through ProgramScheduleRepository: $offenders", offenders.isEmpty())
        assertTrue(code.any { it.contains("scheduleRepository.slotByTargetOccurrenceKey(input.programId, key)") })
        assertTrue(code.any { it.contains("scheduleRepository.addSlots(created)") })
    }

    @Test
    fun targetLookupHasNoDateOrLegacyOccupancyFallback() {
        val code = codeLines(persisterFile)
        val forbidden = listOf(
            "plannedFor in", "occupiedDates", "slotsOfProgram", "slotsFrom", "slotById(",
            "programDayId)", "revisionId)", "list position", "position"
        )
        val offenders = code.filter { line -> forbidden.any { token -> line.contains(token) } }

        assertTrue("target membership is exactly programId + targetOccurrenceKey: $offenders", offenders.isEmpty())
        assertEquals(1, code.count { it.contains("slotByTargetOccurrenceKey(input.programId, key)") })
    }

    @Test
    fun materializerIsTheOnlyMappingPointForNewSlots() {
        val code = codeLines(persisterFile)
        assertEquals(1, code.count { it.contains("TargetSlotMaterializer.materialize(") })
        assertTrue(code.any { it.contains("TargetSlotMaterializationInput(") })
        assertTrue(code.none { it.contains("WorkoutSlot(") })
        assertTrue(persisterFile.readText().contains("idGenerator.newId()"))
        assertTrue(persisterFile.readText().contains("TargetSlotMaterializationInput"))
    }

    @Test
    fun theIntegrationDoesNotRecallTargetPlanningOrPolicyStages() {
        val forbidden = listOf(
            "TargetPlanner", "TargetSchedulePolicy", "TargetScheduleResolver",
            "TargetOccurrenceComposer", "TargetOccurrenceReconciler", "TargetOccurrencePresenter",
            "SlotPlanner", "ScheduleCalendar", "ProgramScheduler"
        )
        val offenders = codeLines(persisterFile).filter { line -> forbidden.any { token -> line.contains(token) } }

        assertTrue("persistence consumes already-calculated target values: $offenders", offenders.isEmpty())
    }

    @Test
    fun theLegacySchedulerRemainsUnwiredAndUnchanged() {
        val scheduler = File(mainDir, "domain/usecase/ProgramScheduler.kt")
        val planner = File(mainDir, "domain/program/SlotPlanner.kt")
        val calendar = File(mainDir, "domain/program/ScheduleCalendar.kt")
        assertTrue(scheduler.isFile)
        assertTrue(planner.isFile)
        assertTrue(calendar.isFile)
        assertTrue(!scheduler.readText().contains("TargetScheduleSlotPersister"))
        assertTrue(!scheduler.readText().contains("TargetSlotMaterializer"))
        assertTrue(!planner.readText().contains("targetOccurrenceKey"))
        assertTrue(!calendar.readText().contains("targetOccurrenceKey"))
    }

    @Test
    fun integrationInputKeepsTheDecisionAndPresentationAsValues() {
        val types = listOf(
            TargetScheduleSlotPersistenceInput::class.java,
            TargetScheduleSlotPersistenceResult::class.java,
            TargetSlotPersistenceException::class.java,
            TargetScheduleSlotPersister::class.java
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

        assertTrue("integration may depend on domain values and JVM coroutine types only: $offenders", offenders.isEmpty())
    }

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
}
