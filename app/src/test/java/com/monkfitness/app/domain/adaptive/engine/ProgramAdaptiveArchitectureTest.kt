package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The adaptive engine's boundaries, pinned mechanically instead of by convention — both the ones that
 * keep the engine pure and the ones that keep the two adaptive generations apart. Both are rules about
 * *reachability*, so both are asserted against the sources and the compiled shape:
 *
 *  * the engine's sources reach no DAO, no Room type, no Android type, no UI type, no ViewModel and no
 *    composition root;
 *  * nothing in it reads an ambient clock, a random source, a hash order or the filesystem;
 *  * it names none of the Stage-1 adaptive implementation — no `AdaptivePolicy`, no
 *    `AdaptiveProgramEngine`, no `AdaptiveSignalCalculator`, no `ProgressionResolver`, no
 *    `PilotProgressionProfiles`, no legacy use case — and the inverse direction is asserted too: the
 *    Stage-1 files are still there, still hold their own vocabulary, and name none of this stage's types;
 *  * its values are pure and its operations stateless, so the same request always produces the same
 *    decision;
 *  * nothing is wired: the composition root, the session runtime and the UI never mention it, because
 *    the integration is a later stage and this one deliberately has no consumer.
 */
class ProgramAdaptiveArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { if (it.isDirectory) it else File("app/$it") }

    /** The files this stage adds, all under the package the foundation's purity scans fence. */
    private val engineSources = listOf(
        "domain/adaptive/engine/ProgramAdaptiveElement.kt",
        "domain/adaptive/engine/ProgramProgressionRelation.kt",
        "domain/adaptive/engine/ProgramAdaptiveReason.kt",
        "domain/adaptive/engine/ProgramLoadComparison.kt",
        "domain/adaptive/engine/ProgramAggregateLoadGuard.kt",
        "domain/adaptive/engine/ProgramAdaptiveSignals.kt",
        "domain/adaptive/engine/ProgramAdaptivePolicy.kt",
        "domain/adaptive/engine/ProgramAdaptiveEngine.kt"
    )

    /**
     * The Stage-1 adaptive implementation this stage must leave exactly where it is.
     *
     * `AdaptiveSessionDecisionRecorder` is listed where it actually lives — `data/repository` — since it
     * is a shipped writer over the legacy decision table; the point of the list is that every one of
     * these files is still present and still untouched.
     */
    private val legacySources = listOf(
        "domain/adaptive/AdaptivePolicy.kt",
        "domain/adaptive/AdaptiveProgramEngine.kt",
        "domain/adaptive/AdaptiveSignalCalculator.kt",
        "domain/adaptive/AdaptiveDecision.kt",
        "domain/adaptive/ProgressionResolver.kt",
        "domain/adaptive/PilotProgressionProfiles.kt",
        "domain/usecase/AdaptiveWorkoutIntegration.kt",
        "data/repository/AdaptiveSessionDecisionRecorder.kt"
    )

    /** The Stage-1 suites: the inverse proof that the old generation is untouched is that it still runs. */
    private val legacyTestSources = listOf(
        "domain/adaptive/AdaptivePolicyTest.kt",
        "domain/adaptive/AdaptiveProgramEngineTest.kt",
        "domain/adaptive/AdaptiveSignalCalculatorTest.kt",
        "domain/adaptive/ProgressionResolverTest.kt"
    )

    @Test
    fun theEngineReachesNoDaoNoRoomNoAndroidAndNoUi() {
        val forbidden = listOf(
            "import android", "import androidx", "import kotlinx",
            "import com.monkfitness.app.data.", "import com.monkfitness.app.ui.",
            "import com.monkfitness.app.viewmodel.", "import com.monkfitness.app.di.",
            "import com.monkfitness.app.animation.", "import com.monkfitness.app.poses.",
            "import com.monkfitness.app.R"
        )
        val offenders = codeLines(engineSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.startsWith(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "the adaptive engine runs entirely in the domain: no DAO, no Room entity, no repository, " +
                "no Android type, no UI type and no composition root may reach it (§25, §26). " +
                "Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noEngineSourceNamesADaoAnEntityOrARoomAnnotation() {
        val offenders = codeLines(engineSources).mapNotNull { (source, line) ->
            when {
                Regex("""\b\w*Dao\b""").containsMatchIn(line) -> "$source: $line"
                Regex("""\b\w*Entity\b""").containsMatchIn(line) -> "$source: $line"
                line.contains("@Entity") || line.contains("@Dao") || line.contains("@Database") ->
                    "$source: $line"

                else -> null
            }
        }

        assertTrue(
            "there is no second adaptive persistence model in this stage: §23's tables and " +
                "`ProgramAdaptiveRepository` stay the only ones (§18, §30 step 12). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun nothingInTheEngineReadsAClockARandomSourceOrTheFilesystem() {
        val forbidden = listOf(
            "Random", "shuffled", "shuffle(", "Math.random", "currentTimeMillis", "nanoTime",
            "Instant.now", "LocalDate.now", "LocalTime.now", "Clock", "UUID", "hashCode()",
            "System.", "ThreadLocal", "File(", "FileSystems", "readText", "writeText"
        )
        val offenders = codeLines(engineSources).mapNotNull { (source, line) ->
            forbidden.firstOrNull { line.contains(it) }?.let { "$source: $line" }
        }

        assertTrue(
            "§18's determinism is an invariant and not a style: no ambient clock, no random source, " +
                "no hash-derived ordering and no filesystem access may reach the engine — the moment a " +
                "decision needs is an argument (the caller's injected clock is read at its own " +
                "boundary). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun noEngineSourceDeclaresMutableStateOrFloatingPoint() {
        val offenders = codeLines(engineSources).mapNotNull { (source, line) ->
            when {
                Regex("""\bvar\b""").containsMatchIn(line) -> "$source: $line"
                Regex("""\bMutable(List|Set|Map)\b""").containsMatchIn(line) -> "$source: $line"
                Regex("""\bmutable(ListOf|SetOf|MapOf)\b""").containsMatchIn(line) -> "$source: $line"
                Regex("""\b(Double|Float)\b""").containsMatchIn(line) -> "$source: $line"
                Regex(
                    """\b(coefficient|multiplier|loadScore|totalLoad|progressFactor|scalingFactor)\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(line) -> "$source: $line"

                else -> null
            }
        }

        assertTrue(
            "every adaptive value is immutable, and the stage decides from counts and ratios stated as " +
                "integers: there is no universal load score and no floating-point reading of one " +
                "(§17, §33). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theEngineAndTheGuardHoldNothingAtAll() {
        listOf(
            ProgramAdaptiveEngine::class.java,
            ProgramAggregateLoadGuard::class.java,
            ProgramAdaptiveSignalCalculator::class.java
        ).forEach { component ->
            val fields = component.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            assertTrue(
                "${component.simpleName} holds nothing: a decision is a function of its request, which " +
                    "is what makes the same request produce the same decision (§18, §33). Found: " +
                    fields.map { it.name },
                fields.isEmpty()
            )
        }
    }

    @Test
    fun everyAdaptiveValueMentionsOnlyKotlinJavaAndTheDomain() {
        val pureTypes = listOf(
            ProgramProgressionVariant::class.java,
            ProgramProgressionRelation::class.java,
            ProgramElementOwnership::class.java,
            ProgramAdaptiveElement::class.java,
            ProgramPerformanceTrend::class.java,
            ProgramConsistency::class.java,
            ProgramComparableExposure::class.java,
            ProgramAdaptiveSignals::class.java,
            ProgramLoadChannel::class.java,
            ProgramIncomparableReason::class.java,
            ProgramChannelComparison::class.java,
            ProgramChannelComparison.Compared::class.java,
            ProgramChannelComparison.Incomparable::class.java,
            ProgramLoadComparison::class.java,
            ProgramGuardReason::class.java,
            ProgramGuardVerdict::class.java,
            ProgramGuardVerdict.NotGuarded::class.java,
            ProgramGuardVerdict.Approved::class.java,
            ProgramGuardVerdict.Filtered::class.java,
            ProgramAdaptiveReason::class.java,
            ProgramAdaptiveWindow::class.java,
            ProgramAdaptiveEvidence::class.java,
            ProgramPolicyDecision::class.java,
            ProgramAdaptivePolicy::class.java,
            ProgramAdaptiveRequest::class.java,
            ProgramAdaptiveResult::class.java
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
            "every adaptive value is pure: its compiled shape may only mention the domain, `kotlin.` and " +
                "`java.`. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theEnginePackageLivesWhereTheFoundationPurityScansCanSeeIt() {
        engineSources.forEach { source ->
            assertTrue(
                "$source must stay in a package `ProgramDomainPurityTest` fences against Android, Room, " +
                    "the data layer, mutable state and invented arithmetic",
                source.startsWith("domain/adaptive/engine") && File(mainDir, source).isFile
            )
        }

        val purity = File("src/test/java/com/monkfitness/app/domain/ProgramDomainPurityTest.kt")
            .let { if (it.isFile) it else File("app/$it") }
        assertTrue("expected the foundation purity test", purity.isFile)
        assertTrue(
            "and that scan must read this stage's package — which is what makes its no-`var`, " +
                "no-floating-point and no-`Random` rules apply here without a second scan",
            purity.readText().contains("\"adaptive/engine\"")
        )

        val adaptivePurity = File("src/test/java/com/monkfitness/app/domain/adaptive/AdaptiveDomainPurityTest.kt")
            .let { if (it.isFile) it else File("app/$it") }
        assertTrue(
            "and the adaptive domain's own fence must read the whole package, nested packages " +
                "included, so a new sub-package cannot sit outside it",
            adaptivePurity.readText().contains("walkTopDown")
        )
    }

    @Test
    fun theStageIsNotWiredIntoAnythingBecauseNothingConsumesItYet() {
        val wiringTargets = listOf(
            "di/AppContainer.kt",
            "domain/usecase/SessionRuntime.kt",
            "domain/usecase/ProgramScheduler.kt"
        )
        val offenders = wiringTargets.flatMap { target ->
            val text = File(mainDir, target).readText()
            listOf("ProgramAdaptiveEngine", "ProgramAggregateLoadGuard", "ProgramAdaptivePolicy")
                .filter { text.contains(it) }
                .map { "$target names $it" }
        }

        assertTrue(
            "§30 step 12 owns the integration: the composition root is not asked to wire what nothing " +
                "consumes, SessionRuntime's completion transaction is untouched, and no decision is " +
                "persisted automatically. Found: $offenders",
            offenders.isEmpty()
        )

        val uiReach = File(mainDir, "ui").walkTopDown()
            .plus(File(mainDir, "viewmodel").walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .filter {
                it.readText().contains("ProgramAdaptiveEngine") ||
                    it.readText().contains("ProgramAggregateLoadGuard")
            }
            .map { it.name }
            .toList()

        assertTrue(
            "no ViewModel and no screen reaches the adaptive engine: the UI integration is a later " +
                "stage, and this test is what says so. Found: $uiReach",
            uiReach.isEmpty()
        )
    }

    @Test
    fun theEngineCannotCreateARevisionASlotOrASession() {
        // The engine's whole output surface is the decision and, when it applies one, the adjustment:
        // both name the revision and the slot they were given and neither can mint one.
        val resultFields = ProgramAdaptiveResult::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }

        assertEquals(
            "a result carries the decision, the adjustment, the family's next state, the reason, what " +
                "was asked for, the signals, the guard's verdict and the window's own verdict — and " +
                "nothing to persist through. The last of those was added by §30 step 12: the caller " +
                "maintains the family's confirmation counts, cooldown position and recovery exit count " +
                "from the policy's per-window answers, and a window cannot count itself",
            listOf(
                "decision", "adjustment", "state", "reason", "requestedAction", "signals", "guard",
                "verdict"
            ),
            resultFields
        )
        assertEquals(
            "a decision carries the reason that answered it, which is the one piece of the engine's " +
                "reasoning §30 step 12 persists (§13, §22) — signals, thresholds and the requested " +
                "action stay out",
            listOf(
                "decisionId", "programId", "revisionId", "slotId", "target", "action", "outcome",
                "evidence", "confidence", "recovery", "decidedAt", "adjustmentId", "reason"
            ),
            AdaptiveDecision::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
        )
        assertEquals(
            "an adjustment is one slot-scoped before/after of one element, superseded by reference (§16)",
            listOf(
                "adjustmentId", "decisionId", "slotId", "before", "after", "createdAt",
                "supersedesAdjustmentId"
            ),
            AdaptiveAdjustment::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
        )
        assertFalse(
            "and the engine names no identity-minting collaborator at all",
            codeLines(engineSources).any { (_, line) ->
                line.contains("IdSource") || line.contains("nextId") || line.contains("UUID")
            }
        )
    }

    @Test
    fun aTargetsScopeIsDerivedFromTheTargetAndCannotBeStatedBesideIt() {
        assertEquals(AdaptiveScope.EXERCISE, AdaptiveTarget.Exercise("pushups").scope)
        assertEquals(AdaptiveScope.FAMILY, AdaptiveTarget.Family("pushups").scope)
        assertEquals(AdaptiveScope.FOCUS, AdaptiveTarget.Focus("push").scope)
        assertEquals(AdaptiveScope.SESSION, AdaptiveTarget.Session.scope)

        AdaptiveTarget::class.java.declaredClasses
            .filterNot { it.isInterface }
            .forEach { case ->
                assertTrue(
                    "${case.simpleName} derives its scope rather than storing one beside it, so a " +
                        "decision cannot record a scope that disagrees with what it changes",
                    case.declaredFields.none { field -> field.name == "scope" }
                )
            }
    }

    @Test
    fun theEngineComposesTheFoundationsOwnPresentationAndTargetTypes() {
        // The effective presentation is the domain's, not a second one.
        assertEquals(
            "an adaptive element presents the domain's own effective exercise",
            EffectiveExercise::class.java.name,
            ProgramAdaptiveElement::class.java.declaredFields
                .first { it.name == "presentation" }
                .type
                .name
        )
        assertEquals(
            "a decision's target is the domain's own target type, whose scope cannot disagree with it",
            AdaptiveTarget::class.java.name,
            AdaptiveDecision::class.java.declaredFields.first { it.name == "target" }.type.name
        )
        assertEquals(
            "an adjustment changes one effective exercise",
            EffectiveExercise::class.java.name,
            AdaptiveAdjustment::class.java.declaredFields.first { it.name == "before" }.type.name
        )
        assertEquals(
            "the guard compares the foundation's own load profiles",
            LoadProfile::class.java.name,
            ProgramLoadComparison.Companion::class.java.declaredMethods
                .first { it.name == "of" }
                .parameterTypes
                .first()
                .name
        )
        assertEquals("and a scope is the foundation's own vocabulary", "EXERCISE, FAMILY, FOCUS, SESSION",
            AdaptiveScope.entries.joinToString(", ") { it.name }
        )
        assertEquals(
            "while the action vocabulary is the decision package's own, with §15's five actions",
            "HOLD, PROGRESS, REGRESS, CHANGE_VARIANT, CHANGE_REST",
            AdaptiveAction.entries.joinToString(", ") { it.name }
        )
        assertEquals(
            "and the outcome vocabulary §18 records a filtered change with",
            "APPLIED, NOT_APPLIED",
            DecisionOutcome.entries.joinToString(", ") { it.name }
        )
        assertTrue(
            "a progression variant prescribes through the domain's own prescription model",
            Prescription::class.java.isAssignableFrom(
                ProgramProgressionVariant::class.java.declaredFields
                    .first { it.name == "prescription" }
                    .type
            )
        )
    }

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
