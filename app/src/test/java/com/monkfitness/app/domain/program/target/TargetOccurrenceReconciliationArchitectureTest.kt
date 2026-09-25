package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** Mechanical boundary for pure Stage 4 occurrence reconciliation. */
class TargetOccurrenceReconciliationArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val reconcilerFile = File(targetDir, "TargetOccurrenceReconciler.kt")

    @Test
    fun reconciliationConsumesOnlyJvmAndPureDomainValues() {
        val types = listOf(
            TargetOccurrenceReconciler::class.java,
            TargetOccurrenceReconciliation::class.java,
            com.monkfitness.app.domain.program.ExistingOccurrence::class.java,
            com.monkfitness.app.domain.program.PlannedOccurrence::class.java
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

        assertTrue("reconciliation may use JVM and pure domain values only: $offenders", offenders.isEmpty())
    }

    @Test
    fun reconcilerDoesNotReferenceResolverSchedulerOrLegacyReconciler() {
        val code = reconcilerFile.readText()
        val forbidden = listOf(
            "TargetScheduleResolver", "TargetSchedule", "ScheduleCadence", "ProgramScheduler",
            "SlotPlanner", "ScheduleCalendar", "ScheduleEditReconciler", "OccurrenceComposer",
            "TargetOccurrenceComposer"
        )
        val offenders = forbidden.filter { code.contains(it) }

        assertTrue("Stage 4 must not depend on scheduling or legacy reconciliation: $offenders", offenders.isEmpty())
    }

    @Test
    fun reconcilerCannotReachPlatformStorageRuntimeUiOrAmbientInputs() {
        val forbiddenImports = listOf(
            "import android", "import androidx", "import java.io", "import java.nio.file",
            "import com.monkfitness.app.data.", "import com.monkfitness.app.domain.usecase.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel.",
            "import com.monkfitness.app.animation."
        )
        val forbiddenReferences = listOf(
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "Repository", "UseCase", "ViewModel",
            "SessionRuntime", "AdaptiveRuntime", "Room", "Random", "UUID", "System.currentTimeMillis",
            "System.nanoTime", "LocalDate.now", "Instant.now", "Clock", "plusDays", "minusDays",
            "ChronoUnit", "matches(", "resolve("
        )
        val offenders = codeLines(reconcilerFile).mapNotNull { line ->
            forbiddenImports.firstOrNull { line.startsWith(it) }?.let { it }
                ?: forbiddenReferences.firstOrNull { token ->
                    Regex("\\b${Regex.escape(token.removeSuffix("("))}\\b").containsMatchIn(line)
                }?.let { it }
        }

        assertTrue("Stage 4 must remain a pure value reconciliation: $offenders", offenders.isEmpty())
    }

    @Test
    fun reconcilerObjectHasNoMutableSingletonCacheOrAmbientAccumulator() {
        val fields = TargetOccurrenceReconciler::class.java.declaredFields

        assertEquals(setOf("INSTANCE", "\$stable"), fields.map { it.name }.toSet())
        assertTrue(
            "reconciler must not hold a cache or mutable singleton field",
            fields.all { field -> field.name in setOf("INSTANCE", "\$stable") }
        )
    }

    @Test
    fun targetPackageNowContainsExactlyTheStageTwoThroughNineProductionFiles() {
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .map { it.name }
            .sorted()

        assertEquals(
            listOf(
                "TargetOccurrenceComposer.kt",
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
    fun stageFourIsNotWiredIntoTheExistingSchedulerOrAnyOtherProductionPackage() {
        val outsideReferences = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .mapNotNull { source ->
                if (source.readText().contains("TargetOccurrenceReconciler")) source.path else null
            }
            .toList()

        assertTrue("Stage 4 must not wire the existing Scheduler: $outsideReferences", outsideReferences.isEmpty())
    }

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() }
}
