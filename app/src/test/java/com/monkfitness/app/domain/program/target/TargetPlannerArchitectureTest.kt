package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class TargetPlannerArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val plannerFile = File(targetDir, "TargetPlanner.kt")

    @Test
    fun targetPackageContainsExactlyTheStageTwoThroughFiveProductionFiles() {
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .map { it.name }
            .sorted()

        assertEquals(
            listOf(
                "TargetOccurrenceComposer.kt",
                "TargetOccurrenceReconciler.kt",
                "TargetPlanner.kt",
                "TargetScheduleResolver.kt"
            ),
            sources
        )
    }

    @Test
    fun plannerUsesOnlyJvmAndPureDomainValues() {
        val types = listOf(
            TargetPlan::class.java,
            TargetPlanner::class.java
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

        assertTrue("planning orchestration may use JVM and pure domain values only: $offenders", offenders.isEmpty())
    }

    @Test
    fun plannerContainsOnlyTheThreeStageCallsAndNoIndependentPolicy() {
        val code = codeLines(plannerFile)
        val requiredCalls = listOf(
            "TargetScheduleResolver.resolve(schedules, window, sources)",
            "TargetOccurrenceComposer.compose(resolved, selection)",
            "TargetOccurrenceReconciler.reconcile(existing, planned)"
        )
        val forbidden = listOf(
            "ScheduleCadence", "ChronoUnit", "plusDays", "minusDays", "groupBy", "sortedWith",
            "distinctBy", "associateBy", "buildMap", "mutable", "Random", "UUID", "Clock",
            "LocalDate.now", "Instant.now", "System.currentTimeMillis", "System.nanoTime",
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "Repository", "UseCase",
            "ViewModel", "SessionRuntime", "AdaptiveRuntime", "Room"
        )

        val resolverIndex = code.indexOfFirst { it.contains("TargetScheduleResolver.resolve(") }
        val reconcilerIndex = code.indexOfFirst { it.contains("TargetOccurrenceReconciler.reconcile(") }
        val composerIndex = code.indexOfFirst { it.contains("TargetOccurrenceComposer.compose(") }
        assertTrue("planner must invoke all three existing stages", listOf(resolverIndex, reconcilerIndex, composerIndex).none { it < 0 })
        assertTrue(
            "planner must execute resolver, composer, reconciler in order",
            resolverIndex < composerIndex && composerIndex < reconcilerIndex
        )
        val offenders = code.filter { line -> forbidden.any { token -> line.contains(token) } }
        assertTrue("planner must orchestrate only and not duplicate stage policy: $offenders", offenders.isEmpty())
    }

    @Test
    fun plannerHasNoMutableSingletonState() {
        val fields = TargetPlanner::class.java.declaredFields

        assertEquals(setOf("INSTANCE", "\$stable"), fields.map { it.name }.toSet())
        assertTrue(
            "planner must not hold a cache or mutable singleton field",
            fields.all { Modifier.isStatic(it.modifiers) && it.name in setOf("INSTANCE", "\$stable") }
        )
    }

    @Test
    fun stageFiveIsNotWiredIntoTheExistingSchedulerOrAnyOtherProductionPackage() {
        val outsideReferences = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .mapNotNull { source ->
                if (source.readText().contains("TargetPlanner")) source.path else null
            }
            .toList()

        assertTrue("Stage 5 must not wire the existing Scheduler: $outsideReferences", outsideReferences.isEmpty())
        assertTrue(File(mainDir, "domain/usecase/ProgramScheduler.kt").isFile)
        assertTrue(File(mainDir, "domain/program/SlotPlanner.kt").isFile)
        assertTrue(File(mainDir, "domain/program/ScheduleCalendar.kt").isFile)
    }

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() }
}
