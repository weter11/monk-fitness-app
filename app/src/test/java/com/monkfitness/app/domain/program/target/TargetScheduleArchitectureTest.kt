package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** Mechanical one-way boundary for the pure target schedule package. */
class TargetScheduleArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")

    @Test
    fun targetPackageContainsExactlyTheStageTwoThroughNineProductionFiles() {
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }?.toList().orEmpty()

        assertEquals(
            listOf(
                // The target scheduling input occurrence — the planned payload plus the one execution
                // classification the scheduling rules read. It is a pure value in this package for the
                // same reason as the entries above (schedule semantics, no storage, no clock, no id
                // generator) and it is deliberately narrower than the domain's `ExistingOccurrence`: it
                // carries no ActualResult, no performed work and no session or slot state. The list
                // stays closed: a twelfth file still fails here.
                "TargetExistingOccurrence.kt",
                "TargetOccurrenceComposer.kt",
                // The occurrence-execution *precedence*, and the single owner of it. It belongs in this
                // pure package for the same reason as the two entries below — schedule semantics over an
                // already-read record — and it holds no storage, no clock and no id generator. Unlike
                // the read-back below it does hold a verdict, which is exactly why it is a *named* owner
                // rather than an incidental member of a record or a repository.
                "TargetOccurrenceExecutionPolicy.kt",
                // The target occurrence's stored execution read-back value, its pure aggregation and its
                // typed refusals. It is a pure value in this package for the same reason as the
                // persistence entry below — the same semantic facts, read back — and it holds no
                // storage, no clock, no id generator and, deliberately, no execution verdict.
                "TargetOccurrenceExecutionRead.kt",
                // The target occurrence's persisted semantic value and its typed conflict. It is a pure
                // value in this package because it is schedule *semantics* — the same kind of thing the
                // reconciler already holds — with no storage, no clock and no id generator of its own.
                // The list stays closed: an eleventh file still fails here.
                "TargetOccurrencePersistence.kt",
                "TargetOccurrencePresenter.kt",
                "TargetOccurrenceReconciler.kt",
                "TargetPlanner.kt",
                "TargetSchedulePolicy.kt",
                "TargetScheduleResolver.kt",
                "TargetSlotMaterializer.kt"
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
    fun oldSchedulerSourcesRemainOutsideAndStageTwoHasExactlyFourProductionCallers() {
        // The pin is a *closed* list, not an absence: it grows only when a named component genuinely
        // has to name a target type, and each entry below is such a case. The input adapter and the
        // production consumer forward a caller-owned window and a map of explicitly stated resolved
        // sources unchanged rather than deciding them; the one controlled invocation decides its own
        // bounded window (asOf .. asOf+29). A further consumer still fails here. The resolver itself
        // stays uncalled outside the pure target package: date resolution is the planner's alone, and
        // no adapter or consumer resolves a source to fill the map it forwards.
        val otherProduction = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .toList()
        val outsideReferences = otherProduction.mapNotNull { source ->
            val text = source.readText()
            if (
                text.contains("TargetScheduleResolver") ||
                text.contains("TargetScheduleWindow") ||
                text.contains("ResolvedScheduleOccurrence") ||
                text.contains("ResolvedScheduleSource")
            ) source.path else null
        }
            .map { it.replace(File.separatorChar, '/').substringAfter("/com/monkfitness/app/") }
            .sorted()
            .toList()

        assertEquals(
            listOf(
                // The one controlled production invocation. It decides the window as target-owned
                // policy (asOf .. asOf+29) rather than forwarding somebody else's, which is why it is on
                // this list — and why the closed list still names no second resolver caller: it never
                // resolves a source, so `ResolvedScheduleSource` never has to be named outside the run
                // context it hands the consumer.
                "domain/usecase/ProgramStartService.kt",
                "domain/usecase/TargetScheduleInputAdapter.kt",
                "domain/usecase/TargetScheduleOrchestrator.kt",
                // The first production consumer of the contour. It names the window and the
                // resolved-source map because they are caller-owned runtime context it must forward
                // verbatim — which is why it is on this list and not on the resolver's.
                "domain/usecase/TargetScheduleProductionConsumer.kt"
            ),
            outsideReferences
        )
        assertTrue(File(mainDir, "domain/program/SlotPlanner.kt").isFile)
        assertTrue(File(mainDir, "domain/program/ScheduleCalendar.kt").isFile)
        listOf(
            File(mainDir, "domain/usecase/ProgramScheduler.kt"),
            File(mainDir, "domain/program/SlotPlanner.kt"),
            File(mainDir, "domain/program/ScheduleCalendar.kt")
        ).forEach { legacy ->
            val text = legacy.readText()
            assertTrue("the legacy contour must not reach Stage 2: ${legacy.name}", !text.contains("TargetSchedule"))
        }
    }

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .map { it.substringBefore("//").trim() }
}
