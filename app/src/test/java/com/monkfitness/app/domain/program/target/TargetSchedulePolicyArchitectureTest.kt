package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class TargetSchedulePolicyArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val policyFile = File(targetDir, "TargetSchedulePolicy.kt")

    @Test
    fun targetPackageContainsExactlyTheStageTwoThroughNineProductionFiles() {
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().map { it.name }.sorted()

        assertEquals(
            listOf(
                // §30 step 17: the target scheduling input occurrence — the planned payload plus the
                // one execution classification the scheduling rules read. It is a pure value in this
                // package for the same reason as the entries above (schedule semantics, no storage,
                // no clock, no id generator) and it is deliberately narrower than the domain's
                // `ExistingOccurrence`: it carries no ActualResult, no performed work and no
                // session or slot state. The list stays closed: a twelfth file still fails here.
                "TargetExistingOccurrence.kt",
                "TargetOccurrenceComposer.kt",
                // §30 step 16: the occurrence-execution *precedence*, and the single owner of it. It
                // belongs in this pure package for the same reason as the two entries below — schedule
                // semantics over an already-read record — and it holds no storage, no clock and no id
                // generator. Unlike the read-back below it does hold a verdict, which is exactly why it
                // is a *named* owner rather than an incidental member of a record or a repository.
                "TargetOccurrenceExecutionPolicy.kt",
                // §30 step 15: the target occurrence's stored execution read-back value, its pure
                // aggregation and its typed refusals. It is a pure value in this package for the same
                // reason as the Phase 14 entry below — the same semantic facts, read back — and it
                // holds no storage, no clock, no id generator and, deliberately, no execution verdict.
                "TargetOccurrenceExecutionRead.kt",
                // §30 step 14: the target occurrence's persisted semantic value and its typed
                // conflict. It is a pure value in this package because it is schedule *semantics* —
                // the same kind of thing the reconciler already holds — with no storage, no clock and
                // no id generator of its own. The list stays closed: an eleventh file still fails here.
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
    fun policyUsesOnlyJvmAndPureDomainValues() {
        val types = listOf(
            TargetSchedulePolicy::class.java,
            TargetScheduleDecision::class.java,
            TargetSupersededOccurrence::class.java
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

        assertTrue("Stage 6 may use JVM and pure domain values only: $offenders", offenders.isEmpty())
    }

    @Test
    fun policyContainsNoPlatformSchedulerRuntimeOrPriorStageInvocation() {
        val code = codeLines(policyFile)
        val forbidden = listOf(
            "android", "androidx", "Room", "DAO", "Repository", "UseCase", "ViewModel", "UI",
            "SessionRuntime", "AdaptiveRuntime", "ProgramScheduler", "SlotPlanner", "ScheduleCalendar",
            "TargetScheduleResolver", "TargetOccurrenceComposer", "TargetOccurrenceReconciler",
            "Clock", "Random", "UUID", "System.currentTimeMillis", "System.nanoTime",
        )
        val offenders = code.filter { line -> forbidden.any { token -> line.contains(token, ignoreCase = true) } }
        assertTrue("Stage 6 must remain a pure additive decision: $offenders", offenders.isEmpty())
    }

    @Test
    fun policyHasNoMutableSingletonState() {
        val fields = TargetSchedulePolicy::class.java.declaredFields
        assertEquals(setOf("INSTANCE", "\$stable"), fields.map { it.name }.toSet())
        assertTrue(fields.all { Modifier.isStatic(it.modifiers) && it.name in setOf("INSTANCE", "\$stable") })
    }

    @Test
    fun stageSixHasExactlyOneProductionCallerAndItIsTheStageTwelveOrchestrator() {
        // Stage 12 revised this claim rather than relaxing it. While nothing orchestrated a pass, the
        // pin was an *absence*: no production source outside the pure target package named the
        // policy. The Stage 12 orchestrator is now that one caller, so the closed list is the
        // single-element one and a second consumer appearing later still fails here.
        val outsideReferences = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .mapNotNull { source -> if (source.readText().contains("TargetSchedulePolicy")) source.path else null }
            .map { it.replace(File.separatorChar, '/').substringAfter("/com/monkfitness/app/") }
            .sorted()
            .toList()

        assertEquals(listOf("domain/usecase/TargetScheduleOrchestrator.kt"), outsideReferences)
        assertLegacyContourDoesNotReach("TargetSchedulePolicy")
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
        .lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
}
