package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** Mechanical pure-domain boundary for Stage 7 target occurrence presentation. */
class TargetOccurrencePresenterArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val presenterFile = File(targetDir, "TargetOccurrencePresenter.kt")

    @Test
    fun targetPackageContainsExactlyTheStageTwoThroughNineProductionFiles() {
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().map { it.name }.sorted()

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
    fun presentationValuesUseOnlyJvmAndPureDomainTypes() {
        val types = listOf(
            TargetProgramDayBinding::class.java,
            TargetOccurrencePresentation::class.java,
            TargetOccurrencePresenter::class.java,
            TargetOccurrencePresentationException::class.java
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

        assertTrue("Stage 7 may use JVM and pure domain values only: $offenders", offenders.isEmpty())
    }

    @Test
    fun presenterHasNoPlatformStorageRuntimeUiOrAmbientInput() {
        val forbiddenImports = listOf(
            "import android", "import androidx", "import java.io", "import java.nio.file",
            "import com.monkfitness.app.data.", "import com.monkfitness.app.domain.usecase.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel."
        )
        val forbiddenReferences = listOf(
            "ProgramScheduler", "SlotPlanner", "ScheduleCalendar", "Repository", "UseCase", "ViewModel",
            "SessionRuntime", "AdaptiveRuntime", "Room", "Clock", "Random", "UUID", "filesystem",
            "network", "System.currentTimeMillis", "System.nanoTime", "LocalDate.now", "Instant.now"
        )
        val offenders = codeLines(presenterFile).mapNotNull { line ->
            forbiddenImports.firstOrNull { line.startsWith(it) }
                ?: forbiddenReferences.firstOrNull { token ->
                    Regex("\\b${Regex.escape(token)}\\b").containsMatchIn(line)
                }
        }

        assertTrue("Stage 7 must remain a pure value transformation: $offenders", offenders.isEmpty())
    }

    @Test
    fun presenterDoesNotCallAnyPriorTargetStageOrInferProgramDayIdentity() {
        val code = codeLines(presenterFile)
        val forbidden = listOf(
            "TargetScheduleResolver", "TargetOccurrenceComposer", "TargetOccurrenceReconciler",
            "TargetPlanner", "TargetSchedulePolicy", "ProgramDayId(", "toString()", "hashCode()"
        )
        val offenders = code.filter { line -> forbidden.any { token -> line.contains(token) } }

        assertTrue("presentation must not re-run earlier stages or infer a typed identity: $offenders", offenders.isEmpty())
    }

    @Test
    fun presenterObjectHasNoMutableSingletonCacheOrAmbientAccumulator() {
        val fields = TargetOccurrencePresenter::class.java.declaredFields

        assertEquals(setOf("INSTANCE", "\$stable"), fields.map { it.name }.toSet())
        assertTrue(
            "presenter must not hold a cache or mutable singleton field",
            fields.all { field -> Modifier.isStatic(field.modifiers) && field.name in setOf("INSTANCE", "\$stable") }
        )
    }

    @Test
    fun stageSevenHasExactlyOneProductionCallerAndItIsTheStageElevenApplicationBoundary() {
        // Stage 11 revised this claim rather than relaxing it. While nothing applied a decision, the
        // pin was an *absence*: no production source outside the pure target package named the
        // presenter. The application boundary is now that one caller, so the closed list is the
        // single-element one — a second consumer appearing later still fails here.
        val outsideReferences = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .mapNotNull { source -> if (source.readText().contains("TargetOccurrencePresenter")) source.path else null }
            .map { it.replace(File.separatorChar, '/').substringAfter("/com/monkfitness/app/").let { p -> "com/monkfitness/app/$p" } }
            .sorted()
            .toList()

        assertEquals(
            listOf("com/monkfitness/app/domain/usecase/TargetScheduleApplicationService.kt"),
            outsideReferences
        )
        assertTrue(File(mainDir, "domain/usecase/ProgramScheduler.kt").isFile)
        assertTrue(File(mainDir, "domain/program/SlotPlanner.kt").isFile)
        assertTrue(File(mainDir, "domain/program/ScheduleCalendar.kt").isFile)
        listOf("ProgramScheduler.kt", "SlotPlanner.kt", "ScheduleCalendar.kt").forEach { name ->
            val source = File(mainDir, "domain").walkTopDown().first { it.name == name }
            assertTrue(
                "the legacy contour must not reach the Stage 7 presenter: $name",
                !source.readText().contains("TargetOccurrencePresenter")
            )
        }
    }

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
}
