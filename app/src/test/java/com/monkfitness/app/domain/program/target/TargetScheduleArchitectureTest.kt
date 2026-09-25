package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** Mechanical one-way boundary for the pure Stage 2 target schedule package. */
class TargetScheduleArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")

    @Test
    fun targetPackageContainsExactlyTheStageTwoThroughSevenProductionFiles() {
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }?.toList().orEmpty()

        assertEquals(
            listOf(
                "TargetOccurrenceComposer.kt",
                "TargetOccurrencePresenter.kt",
                "TargetOccurrenceReconciler.kt",
                "TargetPlanner.kt",
                "TargetSchedulePolicy.kt",
                "TargetScheduleResolver.kt"
            ),
            sources.map { it.name }.sorted()
        )
    }

    @Test
    fun targetCodeCannotReachPlatformStorageRuntimeUiOrTheShippedScheduler() {
        val forbiddenImports = listOf(
            "import android",
            "import androidx",
            "import com.monkfitness.app.data.",
            "import com.monkfitness.app.ui.",
            "import com.monkfitness.app.viewmodel.",
            "import com.monkfitness.app.domain.usecase.ProgramScheduler"
        )
        val forbiddenReferences = listOf(
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar",
            "ProgramScheduleRepository", "WorkoutSessionRepository", "AdaptiveRepository"
        )
        val offenders = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .flatMap { source -> codeLines(source).mapNotNull { line ->
                forbiddenImports.firstOrNull { line.startsWith(it) }?.let { "${source.name}: $it" }
                    ?: forbiddenReferences.firstOrNull { line.contains(it) }?.let { "${source.name}: $it" }
            } }

        assertTrue("Stage 2 must stay above persistence/runtime/UI and beside the legacy scheduler: $offenders", offenders.isEmpty())
    }

    @Test
    fun targetCodeHasNoClockRandomnessMutableStateOrAmbientCache() {
        val forbidden = listOf(
            "Clock", "Random", "shuffled", "System.currentTimeMillis", "LocalDate.now", "Instant.now"
        )
        val offenders = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .flatMap { source -> codeLines(source).mapNotNull { line ->
                forbidden.firstOrNull { line.contains(it) }?.let { "${source.name}: $it" }
            } }
        val resolverFields = TargetScheduleResolver::class.java.declaredFields
            .filterNot { it.isSynthetic }

        assertTrue("the resolver is pure and ambient-state free: $offenders", offenders.isEmpty())
        assertTrue(
            "the resolver object must hold no lazy cache or mutable singleton field",
            resolverFields.all { field ->
                Modifier.isStatic(field.modifiers) && field.name in setOf("INSTANCE", "\$stable")
            }
        )
    }

    @Test
    fun compiledTargetValuesOnlyDependOnJvmAndPureDomainTypes() {
        val types = listOf(
            TargetSchedule::class.java,
            TargetScheduleWindow::class.java,
            ResolvedScheduleOccurrence::class.java,
            ResolvedScheduleSource::class.java
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

        assertTrue("Stage 2 values may only use java.time and pure domain values: $offenders", offenders.isEmpty())
    }

    @Test
    fun oldSchedulerSourcesRemainOutsideAndTheNewLayerIsNotWiredIntoProduction() {
        val otherProduction = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .toList()
        val outsideReferences = otherProduction.mapNotNull { source ->
            val text = source.readText()
            if (
                text.contains("TargetScheduleResolver") ||
                text.contains("TargetScheduleWindow") ||
                text.contains("ResolvedScheduleOccurrence")
            ) source.path else null
        }

        assertTrue(File(mainDir, "domain/program/SlotPlanner.kt").isFile)
        assertTrue(File(mainDir, "domain/program/ScheduleCalendar.kt").isFile)
        assertTrue("Stage 2 must not wire the existing Scheduler: $outsideReferences", outsideReferences.isEmpty())
    }

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .map { it.substringBefore("//").trim() }
}
