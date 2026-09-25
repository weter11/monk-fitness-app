package com.monkfitness.app.domain.program.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** Mechanical boundary for pure Stage 9 target-occurrence materialization. */
class TargetSlotMaterializerArchitectureTest {
    private val mainDir = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }
    private val targetDir = File(mainDir, "domain/program/target")
    private val materializerFile = File(targetDir, "TargetSlotMaterializer.kt")

    @Test
    fun targetPackageContainsExactlyTheStageTwoThroughNineProductionFiles() {
        val sources = targetDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().map { it.name }.sorted()

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
    fun materializerProductionDeclarationExistsOnlyInTheTargetPackage() {
        val declarations = File(mainDir.path).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("object TargetSlotMaterializer") }
            .toList()

        assertEquals(listOf(materializerFile.canonicalPath), declarations.map { it.canonicalPath })
    }

    @Test
    fun materializationValuesUseOnlyJvmAndPureDomainTypes() {
        val types = listOf(
            TargetSlotMaterializationInput::class.java,
            TargetSlotMaterializer::class.java
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

        assertTrue("Stage 9 may use JVM and pure domain values only: $offenders", offenders.isEmpty())
    }

    @Test
    fun materializerHasNoPlatformStorageRuntimeOrAmbientInput() {
        val forbiddenImports = listOf(
            "import android", "import androidx", "import java.io", "import java.nio.file",
            "import com.monkfitness.app.data.", "import com.monkfitness.app.domain.usecase.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel."
        )
        val forbiddenReferences = listOf(
            "ProgramScheduleRepository", "ProgramScheduler", "SlotPlanner", "ScheduleCalendar",
            "Repository", "Dao", "Room", "UseCase", "ViewModel", "SessionRuntime", "AdaptiveRuntime",
            "Clock", "Random", "UUID", "filesystem", "network", "System.currentTimeMillis",
            "System.nanoTime", "LocalDate.now", "Instant.now", "var ", "mutable"
        )
        val offenders = codeLines(materializerFile).mapNotNull { line ->
            forbiddenImports.firstOrNull { line.startsWith(it) }
                ?: forbiddenReferences.firstOrNull { token ->
                    Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}").containsMatchIn(line)
                }
        }

        assertTrue("Stage 9 must remain a pure value transformation: $offenders", offenders.isEmpty())
    }

    @Test
    fun materializerDoesNotCallAnotherTargetStageOrRecomputePolicy() {
        val forbidden = listOf(
            "TargetScheduleResolver", "TargetOccurrenceComposer", "TargetOccurrenceReconciler",
            "TargetPlanner", "TargetSchedulePolicy", "TargetOccurrencePresenter"
        )
        val offenders = codeLines(materializerFile).filter { line ->
            forbidden.any { token -> line.contains(token) }
        }

        assertTrue("materialization consumes an already-complete presentation: $offenders", offenders.isEmpty())
    }

    @Test
    fun materializerHasNoMutableSingletonCacheOrAmbientState() {
        val fields = TargetSlotMaterializer::class.java.declaredFields

        assertEquals(setOf("INSTANCE", "\$stable"), fields.map { it.name }.toSet())
        assertTrue(
            "materializer must not hold a cache or mutable singleton field",
            fields.all { Modifier.isStatic(it.modifiers) && it.name in setOf("INSTANCE", "\$stable") }
        )
    }

    @Test
    fun materializerHasNoIdentityConstructionOrStringBasedInference() {
        val forbidden = listOf(
            "SlotId(", "ProgramId(", "RevisionId(", "ProgramDayId(", "SessionId(",
            "toString()", "hashCode()", "UUID", "Random", "plannedFor +", "+ plannedFor"
        )
        val offenders = codeLines(materializerFile).filter { line ->
            forbidden.any { token -> line.contains(token) }
        }

        assertTrue("materializer must transfer identities, never derive them: $offenders", offenders.isEmpty())
    }

    @Test
    fun materializerCopiesOnlyTheExactDeclaredFieldMapping() {
        val code = codeLines(materializerFile).joinToString("\n")
        val exactMapping = listOf(
            "slotId = input.slotId",
            "programId = input.programId",
            "revisionId = input.revisionId",
            "programDayId = input.presentation.programDayId",
            "plannedFor = occurrence.plannedFor",
            "status = SlotStatus.PLANNED",
            "attempts = emptyList()",
            "completedAt = null",
            "targetOccurrenceKey = occurrence.occurrenceKey"
        )

        assertTrue("the exact mapping is incomplete: $code", exactMapping.all(code::contains))
    }

    @Test
    fun stageNineHasNoPersistenceOrSchedulerWiringOutsideTheIntegrationBoundary() {
        val outsideReferences = File(mainDir, "domain").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile != targetDir }
            .filterNot { it.path.endsWith("domain/usecase/TargetScheduleSlotPersister.kt") }
            .mapNotNull { source ->
                if (source.readText().contains("TargetSlotMaterializer")) source.path else null
            }.toList()

        assertTrue("only the Stage 10 integration boundary may consume the pure materializer: $outsideReferences", outsideReferences.isEmpty())
        assertTrue(File(mainDir, "domain/usecase/ProgramScheduler.kt").isFile)
        assertTrue(File(mainDir, "domain/program/SlotPlanner.kt").isFile)
        assertTrue(File(mainDir, "domain/program/ScheduleCalendar.kt").isFile)
    }

    private fun codeLines(source: File): List<String> = source.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
}
