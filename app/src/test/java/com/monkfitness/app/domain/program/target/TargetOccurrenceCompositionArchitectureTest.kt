package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** Mechanical one-way boundary for pure Stage 3 occurrence composition. */
class TargetOccurrenceCompositionArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val composerFile = File(targetDir, "TargetOccurrenceComposer.kt")

    @Test
    fun compositionConsumesOnlyPureDomainValues() {
        val types = listOf(
            TargetOccurrenceComposer::class.java,
            ResolvedScheduleOccurrence::class.java,
            com.monkfitness.app.domain.program.PlannedOccurrence::class.java,
            com.monkfitness.app.domain.program.OccurrenceComponent::class.java,
            com.monkfitness.app.domain.program.CompositionSelection::class.java
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

        assertTrue("composition may use JVM and pure domain values only: $offenders", offenders.isEmpty())
    }

    @Test
    fun composerDoesNotCallTheStageTwoResolverOrAnyDateProducingDependency() {
        val code = codeLines(composerFile)
        val forbidden = listOf(
            "TargetScheduleResolver", "TargetSchedule(", "TargetScheduleWindow", "ScheduleCadence", "ChronoUnit",
            "plusDays", "minusDays", "matches(", "resolve("
        )
        val offenders = code.filter { line -> forbidden.any { token -> line.contains(token) } }

        assertTrue("composition must not resolve or discover dates: $offenders", offenders.isEmpty())
    }

    @Test
    fun composerCannotReachPlatformStorageRuntimeUiOrTheShippedScheduler() {
        val forbiddenImports = listOf(
            "import android", "import androidx", "import java.io", "import java.nio.file",
            "import com.monkfitness.app.data.", "import com.monkfitness.app.domain.usecase.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel.",
            "import com.monkfitness.app.animation."
        )
        val forbiddenReferences = listOf(
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "Repository", "UseCase", "ViewModel",
            "SessionRuntime", "AdaptiveRuntime", "Room", "Random", "UUID", "System.currentTimeMillis",
            "System.nanoTime", "LocalDate.now", "Instant.now", "Clock"
        )
        val offenders = codeLines(composerFile).mapNotNull { line ->
            forbiddenImports.firstOrNull { line.startsWith(it) }?.let { it }
                ?: forbiddenReferences.firstOrNull { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(line) }
                    ?.let { it }
        }

        assertTrue("Stage 3 must stay a pure value transformation: $offenders", offenders.isEmpty())
    }

    @Test
    fun composerObjectHasNoMutableSingletonCacheOrAmbientAccumulator() {
        val fields = TargetOccurrenceComposer::class.java.declaredFields

        assertEquals(setOf("INSTANCE", "\$stable"), fields.map { it.name }.toSet())
        assertTrue(
            "composer must not hold a lazy cache or mutable singleton field",
            fields.all { field -> field.name in setOf("INSTANCE", "\$stable") }
        )
    }

    @Test
    fun programSchedulerSlotPlannerAndCalendarRemainOutsideTheTargetCompositionPackage() {
        assertTrue(File(mainDir, "domain/usecase/ProgramScheduler.kt").isFile)
        assertTrue(File(mainDir, "domain/program/SlotPlanner.kt").isFile)
        assertTrue(File(mainDir, "domain/program/ScheduleCalendar.kt").isFile)
        assertTrue(!File(targetDir, "ProgramScheduler.kt").exists())
        assertTrue(!File(targetDir, "SlotPlanner.kt").exists())
        assertTrue(!File(targetDir, "ScheduleCalendar.kt").exists())
    }

    @Test
    fun stageThreeIsNotWiredIntoTheExistingSchedulerOrAnyOtherProductionPackage() {
        val outsideReferences = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .mapNotNull { source ->
                if (source.readText().contains("TargetOccurrenceComposer")) source.path else null
            }
            .toList()

        assertTrue("Stage 3 must not wire the existing Scheduler: $outsideReferences", outsideReferences.isEmpty())
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

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() }
}
