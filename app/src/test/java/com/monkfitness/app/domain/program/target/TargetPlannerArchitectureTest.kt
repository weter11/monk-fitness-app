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
    fun targetPackageContainsExactlyTheStageTwoThroughNineProductionFiles() {
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .map { it.name }
            .sorted()

        assertEquals(
            listOf(
                "TargetOccurrenceComposer.kt",
                // §30 step 14: the target occurrence's persisted semantic value and its typed
                // conflict. It is a pure value in this package because it is schedule *semantics* —
                // the same kind of thing the reconciler already holds — with no storage, no clock and
                // no id generator of its own. The list stays closed: a ninth file still fails here.
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
    fun stageFiveHasExactlyOneProductionCallerAndItIsTheStageTwelveOrchestrator() {
        // Stage 12 revised this claim rather than relaxing it. While nothing orchestrated a pass, the
        // pin was an *absence*: no production source outside the pure target package named the
        // planner. The Stage 12 orchestrator is now that one caller, so the closed list is the
        // single-element one and a second consumer appearing later still fails here.
        val outsideReferences = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .mapNotNull { source ->
                if (source.readText().contains("TargetPlanner")) source.path else null
            }
            .map { it.replace(File.separatorChar, '/').substringAfter("/com/monkfitness/app/") }
            .sorted()
            .toList()

        assertEquals(listOf("domain/usecase/TargetScheduleOrchestrator.kt"), outsideReferences)
        assertLegacyContourDoesNotReach("TargetPlanner")
    }

    /**
     * The legacy contour must never reach a target stage: it is a separate generation, and this
     * assertion is kept explicit so an inversion above can never quietly remove it.
     */
    private fun assertLegacyContourDoesNotReach(token: String) {
        assertTrue(File(mainDir, "domain/usecase/ProgramScheduler.kt").isFile)
        assertTrue(File(mainDir, "domain/program/SlotPlanner.kt").isFile)
        assertTrue(File(mainDir, "domain/program/ScheduleCalendar.kt").isFile)
        listOf("ProgramScheduler.kt", "SlotPlanner.kt", "ScheduleCalendar.kt").forEach { name ->
            val source = File(mainDir, "domain").walkTopDown().first { it.name == name }
            assertTrue("the legacy contour must not reach $token: $name", !source.readText().contains(token))
        }
    }

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() }
}
