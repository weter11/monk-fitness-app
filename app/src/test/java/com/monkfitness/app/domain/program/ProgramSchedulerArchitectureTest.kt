package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.usecase.ProgramScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * §30 step 7's boundaries, pinned mechanically instead of by convention.
 *
 * The Scheduler is the first layer of the Program System whose whole job is *deciding what happens
 * when*, and every temptation it has is one import away: a session repository "to keep the slot and the
 * workout in step", a generator to fill an opportunity, an adaptive port to make the plan smarter, a
 * lifecycle write to start the program it just planned, a DAO "just to check a row", a ViewModel to show
 * the next workout. §6, §19, §20, §25, §26 and §33 forbid every one of them, and §27/§30 assign each to
 * a later — or an earlier — stage, so the rules are asserted against the sources, the compiled shape and
 * the composition root:
 *
 *  * the Scheduler reaches no DAO, no Room type, no Android type and no UI type;
 *  * its collaborators are exactly the three repositories it reads and writes plus the two §26 ports, the
 *    calendar and the transaction runner — no session repository, no generator, no adaptive port, no
 *    library, no progress reader;
 *  * the scheduling decision itself is pure: its compiled shape may only mention the domain, `kotlin.`
 *    and `java.`, and it may not declare mutable state;
 *  * it carries no session type, no performance value and no amount-like field: a slot is an opportunity
 *    and cannot be made into a measurement;
 *  * and nothing in the UI layer reaches it yet — wiring a ViewModel is a later step, and this test is
 *    what says so.
 */
class ProgramSchedulerArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { if (it.isDirectory) it else File("app/$it") }

    /** The files this stage adds, split by layer. */
    private val domainSources = listOf(
        "domain/program/ScheduleHorizon.kt",
        "domain/program/ScheduleCalendar.kt",
        "domain/program/SlotPlan.kt",
        "domain/program/SlotPlanner.kt",
        "domain/program/ProgramSchedulingResult.kt"
    )

    private val useCaseSource = "domain/usecase/ProgramScheduler.kt"

    private val allSources = domainSources + useCaseSource

    // ------------------------------------------------------------------ layering

    @Test
    fun theSchedulerReachesNoDaoNoRoomAndNoUi() {
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
            "the Scheduler runs above the repositories: no DAO, no Room entity, no Android and no UI " +
                "type may reach it (§25). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noSourceOfTheSchedulerNamesADaoAnEntityOrARoomAnnotation() {
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
            "there is no second persistence model here and no DAO access (§25, §33): persistence goes " +
                "through the repositories. Found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ what the Scheduler may not have

    @Test
    fun theSchedulerIsWiredWithoutASessionARecorderAGeneratorOrALibrary() {
        // `declaredConstructors` holds the primary constructor plus the synthetic one Kotlin generates
        // for the defaulted parameters, so the synthetic members are filtered out first.
        val collaborators = ProgramScheduler::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .parameterTypes
            .map { it.simpleName }

        assertEquals(
            "the Scheduler's collaborators are exactly the plan it reads, the schedule it writes, the " +
                "two §26 ports, the calendar its dates are read in and the transaction runner — the " +
                "absence of a session repository is what makes §33's 'let the Scheduler create Session' " +
                "impossible rather than forbidden",
            listOf(
                "ProgramRepository", "ProgramPlanRepository", "ProgramScheduleRepository",
                "Clock", "IdGenerator", "ZoneId", "Function2"
            ),
            collaborators
        )

        val forbidden = listOf(
            "WorkoutSession", "SessionStatus", "WorkoutSessionRepository", "SessionId",
            "ProgramProgressRepository", "AdaptiveRepository", "ProgramAdaptiveRepository",
            "Adaptive", "WorkoutGenerator", "getExerciseLibrary", "ExerciseSkeletonData",
            "Equipment", "PoseRegistry", "Random", "shuffled", "Math.random",
            "LocalDate.now", "Instant.now", "System.currentTimeMillis"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "no session runtime, no adaptive work, no generation, no library metadata, no progress " +
                "read, no uncontrolled random source and no clock read outside the injected port " +
                "belongs to the Scheduler (§20, §26, §30 steps 8–12). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun thePureHalfOfTheSchedulerSeesNothingButKotlinJavaAndTheDomain() {
        val pureTypes = listOf(
            ScheduleWindow::class.java,
            PausedInterval::class.java,
            SlotPlan::class.java,
            ScheduleRequest::class.java,
            SupersededSlot::class.java,
            SupersessionReason::class.java,
            SlotIdSource::class.java,
            SlotPlanner::class.java,
            ProgramSchedulingResult::class.java,
            ProgramSchedulingRefusal::class.java,
            ScheduleOutcome::class.java
        )

        val offenders = pureTypes.flatMap { type ->
            type.declaredMethods.flatMap { method ->
                (method.parameterTypes.toList() + listOf(method.returnType)).mapNotNull { referenced ->
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
            "the decision, its request, its result and its outcome are pure values: their compiled " +
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
    fun theDecisionIsAFunctionOfItsRequestAndHoldsNoMutableState() {
        val fields = SlotPlanner::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }

        assertTrue(
            "the planner holds nothing: a planning pass is a function of its request, which is what " +
                "makes the same inputs produce the same opportunities (§26, §33)",
            fields.isEmpty()
        )

        val requestFields = ScheduleRequest::class.java.declaredFields
            .filterNot {
                java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic ||
                    it.name.startsWith("$")
            }
            .map { it.name }

        assertEquals(
            "and its request is the whole input: a revision, two dates, the slots, the pauses and the " +
                "identity source — no repository, no clock, no database and no session",
            listOf("revision", "anchor", "asOf", "slots", "pauses", "slotIds"),
            requestFields
        )
    }

    @Test
    fun theSchedulerCannotExpressAnAmountOfWork() {
        val forbidden = listOf(
            "repetition", "repetitions", "durationSeconds", "score", "performance", "volume",
            "completedSets", "loadScore"
        )
        val offenders = codeLines(allSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it, ignoreCase = true) }?.let { "$source: $line" }
        }

        assertTrue(
            "a slot is an opportunity, not an amount of work: nothing in this stage may carry a " +
                "repetition, a duration, a set count or a score, because a missed opportunity cannot " +
                "then be read as a workout that scored zero (§12, §20, §33). Found: $offenders",
            offenders.isEmpty()
        )

        val outcomeFields = ScheduleOutcome::class.java.declaredFields
            .filterNot {
                java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic ||
                    it.name.startsWith("$")
            }
            .map { it.name }

        assertEquals(
            "and the outcome reports the decision and the dates it was made over — nothing measured",
            listOf(
                "programId", "revisionId", "anchor", "asOf", "window", "scheduledDates", "created",
                "superseded", "missed"
            ),
            outcomeFields
        )
    }

    // ------------------------------------------------------------------ the wiring

    /**
     * Revised, not relaxed, by §30 step 14.
     *
     * §30 step 7 landed the Scheduler without an affordance and pinned that by asserting that no UI source
     * named `ProgramScheduler` at all. §30 step 14's Program Detail shows §22's *next workout*, which is the
     * Scheduler's own preview, so the UI must reach it — through the state holder, and without a second
     * owner of §20's timing.
     *
     * The claim the rule always meant: the composition root constructs the Scheduler **once**, no UI source
     * **constructs one**, and the UI does use the one it is handed (so the rule is not vacuous).
     */
    @Test
    fun theCompositionRootWiresTheSchedulerAndNothingAboveItConstructsOne() {
        val container = File(mainDir, "di/AppContainer.kt")
        assertTrue("the composition root constructs the Scheduler (§26)", container.isFile)
        assertTrue(
            "as a property, like every other graph node",
            container.readText().contains("val programScheduler: ProgramScheduler = ProgramScheduler(")
        )

        val constructions = File(mainDir, "ui").walkTopDown()
            .plus(File(mainDir, "viewmodel").walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ProgramScheduler(") }
            .map { it.name }
            .toList()

        assertTrue(
            "no ViewModel and no screen constructs the Scheduler: §26 says a view model receives what it " +
                "needs rather than building it, and a second Scheduler would be a second owner of §20's " +
                "timing. Found: $constructions",
            constructions.isEmpty()
        )

        val consumers = File(mainDir, "ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ProgramScheduler") }
            .map { it.name }
            .toList()

        assertTrue(
            "and the UI does reach it, so the rule above is not vacuous: §22's next workout is the " +
                "Scheduler's own answer rather than a date the detail screen computes. Found: $consumers",
            consumers.isNotEmpty()
        )
    }

    @Test
    fun theSchedulerIsInjectedTheOneCalendarItReadsDatesIn() {
        val parameters = ProgramScheduler::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .parameterTypes
            .toList()

        assertTrue(
            "the zone is a collaborator and not an assumption: a pause is stored as instants and an " +
                "opportunity is planned for a date, so the conversion belongs to the layer that owns " +
                "the clock rather than to the decision (§26)",
            parameters.contains(ZoneId::class.java)
        )
        assertFalse(
            "and the decision itself never sees a zone, an instant or a clock: it compares dates",
            SlotPlanner::class.java.declaredMethods.any { method ->
                (method.parameterTypes.toList() + listOf(method.returnType)).any { type ->
                    type == ZoneId::class.java || type.name.endsWith("Instant") ||
                        type.simpleName == "Clock"
                }
            }
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
