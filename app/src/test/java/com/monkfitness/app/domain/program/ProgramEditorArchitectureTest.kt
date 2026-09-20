package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.usecase.ProgramEditorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * §30 step 6's boundaries, pinned mechanically instead of by convention.
 *
 * The editor is the first layer of the Program System that could reach for *anything*: it runs above
 * the repositories, it writes Programs, and the temptations are all one import away — a DAO "just to
 * check a row", a generator to fill an empty day, a library lookup to validate an exercise, a slot
 * write to keep the schedule in step, a ViewModel to tie it together. §25, §26 and §33 forbid every
 * one of them, and the blueprint's own §27/§30 order assigns each to a later stage, so the rules are
 * asserted against the sources and the compiled shape:
 *
 *  * no DAO, no Room, no Android and no UI type may appear in the editor at all;
 *  * no scheduler, no generator and no exercise-library port may appear as a collaborator — the
 *    absence of the dependency is the guarantee, not a promise not to use it;
 *  * the pure half of the editor may only see `kotlin.`, `java.` and the domain, verified on the
 *    compiled methods rather than on the imports;
 *  * the pure half lives in `domain/program`, which is the package `ProgramDomainPurityTest` already
 *    fences, so the foundation's rules (no `var`, no mutable collection, no invented arithmetic)
 *    apply to it without a second scan;
 *  * and nothing in the UI layer reaches the editor yet: wiring a ViewModel is a later step, and this
 *    test is what says so.
 */
class ProgramEditorArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { if (it.isDirectory) it else File("app/$it") }

    /** The files this stage adds, split by layer. */
    private val domainSources = listOf(
        "domain/program/ProgramStructure.kt",
        "domain/program/ProgramDraftEditor.kt",
        "domain/program/ProgramDraftValidation.kt",
        "domain/program/ProgramDraftReview.kt",
        "domain/program/ProgramEditorResult.kt"
    )

    private val useCaseSource = "domain/usecase/ProgramEditorService.kt"

    private val allSources = domainSources + useCaseSource

    // ------------------------------------------------------------------ layering

    @Test
    fun theEditorReachesNoDaoNoRoomAndNoUi() {
        val forbidden = listOf(
            "import android", "import androidx", "import kotlinx",
            "import com.monkfitness.app.data.local", "import com.monkfitness.app.data.model",
            "import com.monkfitness.app.ui", "import com.monkfitness.app.viewmodel",
            "import com.monkfitness.app.animation", "import com.monkfitness.app.poses",
            "import com.monkfitness.app.R"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.startsWith(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the editor runs above the repositories: no DAO, no Room entity, no Android and no UI " +
                "type may reach it (§25). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noSourceOfTheEditorNamesADaoAnEntityOrARoomAnnotation() {
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            when {
                Regex("""\b\w*Dao\b""").containsMatchIn(line) -> "$source: $line"
                Regex("""\b\w*Entity\b""").containsMatchIn(line) -> "$source: $line"
                line.contains("@Entity") || line.contains("@Dao") || line.contains("@Database") ->
                    "$source: $line"
                else -> null
            }
        }

        assertTrue(
            "there is no second persistence model here and no DAO access (§25, §33): persistence " +
                "goes through the repositories. Found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ what the editor may not have

    @Test
    fun theEditorIsWiredWithoutASchedulerAGeneratorOrALibrary() {
        val collaborators = ProgramEditorService::class.java.declaredConstructors
            .single()
            .parameterTypes
            .map { it.simpleName }

        assertEquals(
            "the editor's collaborators are exactly the persistence that owns a Program's plan, the " +
                "two §26 ports and the transaction runner",
            listOf("ProgramRepository", "ProgramPlanRepository", "Clock", "IdGenerator", "Function2"),
            collaborators
        )

        val forbidden = listOf(
            "ProgramScheduleRepository", "WorkoutSlot", "SlotId", "ProgramProgressRepository",
            "WorkoutSessionRepository", "WorkoutGenerator", "getExerciseLibrary", "PoseRegistry",
            "addSlots", "updateOutcome", "ProgramCalendar", "Random"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "no scheduling, generation, session, progress or library behaviour belongs to the editor " +
                "(§27, §30 steps 7–10): a save moves no date and reconciles no slot. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theEditorNeverReachesForExerciseLibraryMetadata() {
        val libraryType = Regex("""(?<![A-Za-z])Exercise\(""")
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            when {
                // An exercise is an opaque id to this layer (§10): it is never resolved.
                libraryType.containsMatchIn(line) -> "$source: $line"
                line.contains("ExerciseSkeletonData") -> "$source: $line"
                Regex("""\bEquipment\b""").containsMatchIn(line) -> "$source: $line"
                else -> null
            }
        }

        assertTrue(
            "prescriptions and plans are the Program's, never the library's: editing a plan may not " +
                "mutate exercise metadata, and a manual prescription is free of any range (§10). " +
                "Found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ the pure half

    @Test
    fun thePureHalfOfTheEditorSeesNothingButKotlinJavaAndTheDomain() {
        val pureTypes = listOf(
            ProgramStructure::class.java,
            StructuredDay::class.java,
            StructuredExercise::class.java,
            ProgramStructureAspect::class.java,
            ProgramDraftValidation::class.java,
            ProgramDraftEditor::class.java,
            ProgramDraftReview::class.java,
            ProgramDraftSaveKind::class.java,
            ProgramEditorResult::class.java,
            ProgramEditorRejection::class.java,
            ProgramSaveOutcome::class.java
        )

        val offenders = pureTypes.flatMap { type ->
            type.declaredMethods.flatMap { method ->
                (method.parameterTypes.toList() + listOf(method.returnType)).mapNotNull { referenced ->
                    // An array of a domain type — an enum's values — is still a domain type.
                    val referencedName =
                        if (referenced.isArray) referenced.componentType.name else referenced.name
                    val allowed = referencedName.startsWith("kotlin.") ||
                        referencedName.startsWith("java.") ||
                        referencedName.startsWith("com.monkfitness.app.domain.") ||
                        referenced.isPrimitive
                    if (allowed) null else "${type.simpleName}.${method.name} references $referencedName"
                }
            }
        }

        assertTrue(
            "the draft, its validation, its review and the results are pure values: their compiled " +
                "shape may only mention the domain, `kotlin.` and `java.`. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePureHalfLivesWhereTheFoundationPurityScanCanSeeIt() {
        domainSources.forEach { source ->
            assertTrue(
                "$source must stay in `domain/program`, the package ProgramDomainPurityTest fences " +
                    "against Android, Room, the data layer, mutable state and invented arithmetic",
                source.startsWith("domain/program/") && File(mainDir, source).isFile
            )
        }

        val puritySource = File("src/test/java/com/monkfitness/app/domain/ProgramDomainPurityTest.kt")
            .let { if (it.isFile) it else File("app/$it") }
        assertTrue("expected the foundation purity test", puritySource.isFile)
        assertTrue(
            "and that scan must still list `program` among the packages it reads",
            puritySource.readText().contains("\"program\"")
        )
    }

    @Test
    fun theDraftIsAValueAndNotAHandler() {
        val fields = ProgramDraftEditor::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }

        assertEquals(
            "the editor holds the draft and the identity source and nothing else: no repository, no " +
                "clock, no cache of what it is about to save",
            listOf("draft", "ids"),
            fields.map { it.name }
        )
        assertTrue(
            "and both are read-only (§6: a draft is edited by producing the next draft)",
            fields.all { Modifier.isFinal(it.modifiers) }
        )
        assertFalse(
            "so no operation can leave a saved Program half-edited",
            fields.any { it.name.contains("revision", ignoreCase = true) }
        )
    }

    // ------------------------------------------------------------------ the wiring

    /**
     * Revised, not relaxed, by §30 step 14.
     *
     * §30 step 6 landed the editor without an affordance and pinned that by asserting that no UI source
     * named `ProgramEditorService` at all. §30 step 14 is the stage that wires it (§7's *"do not route users
     * into the legacy editor merely because it already exists"*), so the claim the rule always meant is
     * stated instead: the composition root constructs the editor **once**, and no UI source **constructs
     * one** — the screens reach it through the state holder the view model hands them. A second construction
     * is what the original assertion was protecting against, and it is still refused.
     */
    @Test
    fun theCompositionRootWiresTheEditorAndNothingAboveItConstructsOne() {
        val container = File(mainDir, "di/AppContainer.kt")
        assertTrue("the composition root constructs the editor (§26)", container.isFile)
        assertTrue(
            "as a property, like every other graph node",
            container.readText().contains("val programEditorService: ProgramEditorService = ProgramEditorService(")
        )

        val constructions = File(mainDir, "ui").walkTopDown()
            .plus(File(mainDir, "viewmodel").walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ProgramEditorService(") }
            .map { it.name }
            .toList()

        assertTrue(
            "no ViewModel and no screen constructs the editor: §26 says a view model receives what it " +
                "needs rather than building it, and one editor with two owners is one editor too many. " +
                "Found: $constructions",
            constructions.isEmpty()
        )

        val consumers = File(mainDir, "ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ProgramEditorService") }
            .map { it.name }
            .toList()

        assertTrue(
            "and the UI does reach it, so the rule above is not vacuous: §30 step 14 is the affordance " +
                "§7's editor was waiting for. Found: $consumers",
            consumers.isNotEmpty()
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The code lines of each source, with comments removed: the rules above are rules about code. */
    private fun codeLines(sources: List<String>): List<Pair<String, String>> = sources.flatMap { source ->
        val file = File(mainDir, source)
        assertTrue("expected $source", file.isFile)
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .map { source.substringAfterLast("/") to it }
    }
}
