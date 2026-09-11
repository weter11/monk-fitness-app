package com.monkfitness.app.arch

import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.poses.DynamicWorldsGreatestStretchPose
import com.monkfitness.app.poses.QuadrupedThoracicRotationsPose
import com.monkfitness.app.poses.SquatPose
import com.monkfitness.app.poses.StandardPushUpPose
import com.monkfitness.app.poses.ThoracicExtensionPose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P12 WP-I — **the §12.10 activation gate**: the single authoritative, executable activation
 * contract.
 *
 * §12.10 requires that activation happen only when ALL SIX of its conditions hold, and the frozen
 * plan explicitly rejects "flip it after the byte-identity check is green" as under-conditioned.
 * This class is that contract. It is deliberately NOT a check of `IK_STAGE_ACTIVE == true`: a flag
 * value proves nothing about ownership. Each criterion below evaluates an ARCHITECTURAL FACT —
 * call-site inventories, declared-intent completeness, execution evidence, the §12.9 equivalence
 * result, validation ownership — and CRITERION tests delegate to the authoritative suites that
 * already own each fact, so a regression anywhere fails the gate with the failing evidence named
 * instead of silently diverging from a duplicated copy of the same check.
 *
 * ```text
 *  Criterion 1  B-1 consumers        -> hip-flexor family does not consume bake results;
 *                                       the one sanctioned planning solve is composition-only
 *  Criterion 2  direct bypass family -> solveIK inventory == the accepted classification;
 *                                       no isArm heuristic; no legacy reconstruction consumer
 *  Criterion 3  lossless authored data -> declared lengths/constraint decode; realized set ==
 *                                       declared set; the stage body recovers nothing from the
 *                                       definition (no length coincidence)
 *  Criterion 4  strengthened enforcement -> double realization rejected on EXECUTION evidence,
 *                                       even when both executions produce a byte-identical frame
 *  Criterion 5  §12.9 equivalence    -> the complete cross-configuration corpus (not a smoke test)
 *  Criterion 6  validation re-cert   -> the validation probes/readings are owned by the ACTIVE
 *                                       implementation and the validator writes no geometry
 * ```
 *
 * `productionConfigurationIsStateThree` is the DEPLOYED-STATE check (the §12.0 state 2 → state 3
 * transition) and the only deliberately RED item before the flip: it is what the flag flip turns
 * green, and it is the single place the deployed default is asserted at runtime (the declaration's
 * default is asserted statically by
 * `RuntimeSolverOwnershipAuditTest.ikStageFlagIsDeclarationOnlyAndReadOnlyAtRealizationDecisionSites`).
 *
 * The §14 post-activation failure checks (`activatedProductionRejectsASecondRealizationOfTheSameLimb`,
 * `activatedProductionAcceptsAnOrdinaryActiveFrame`, `f1SnapshotFamilyCannotHideASecondRealization`)
 * drive the PRODUCTION path in the deployed configuration — injection goes through the registered
 * intent carrier and rejection happens at the production enforcement point, never by fabricating an
 * evidence value.
 */
class ActivationGateTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalFlag = IK_STAGE_ACTIVE

    @After
    fun restoreFlag() {
        IK_STAGE_ACTIVE = originalFlag
    }

    // =============================================================================================
    // Criterion 1 — B-1 authoring consumers of solved results
    // =============================================================================================

    @Test
    fun criterionOne_b1AuthoringConsumersAreResolved() {
        // (a) Every authoring-time consumer of a solved result is eliminated or routed through the
        // sanctioned §12.4b planning solve. The three owners of these facts are the WP-B contract
        // test, the planning-solve inventory lock and the WP-H composition-only audit.
        delegate("Criterion 1 — hip-flexor authoring consumers (WP-B)") {
            LimbSolverOwnershipActivationContractTest().hipFlexorFamilyDoesNotConsumeSolveResultsForAuthoring()
        }
        delegate("Criterion 1 — sanctioned planning-solve inventory (§12.4b)") {
            PlanningSolveInventoryTest().planningSolveCallSitesAreConfinedToTheSanctionedFamily()
        }
        delegate("Criterion 1 — planning solve composes intent only") {
            RuntimeSolverOwnershipAuditTest().planningSolveComposesIntentOnlyAndIsNeverRealizationEvidence()
        }

        // (b) …and the family still produces a frame through the production path: the remedy moved
        // WHERE the composition inputs come from; it did not stop the family from being realizable.
        for (name in listOf("CouchStretch", "HalfKneelingStretch")) {
            val published = runCatching { produceByName(name) }.getOrElse {
                error("Criterion 1: the $name family must produce a frame: ${it.message}")
            }
            assertTrue(
                "Criterion 1: $name must produce a published frame with a realized limb chain",
                published.roots.isNotEmpty()
            )
            assertTrue("Criterion 1: $name frame must be finite", published.getJoint(Joint.KNEE_F).y.isFinite())
        }
    }

    /** Builds and produces one frame for the named hip-flexor family member. */
    private fun produceByName(name: String): SkeletonPose {
        val ctx = PoseContext(0.5f, Side.LEFT, def)
        val builder: PoseBuilder = when (name) {
            "CouchStretch" -> com.monkfitness.app.poses.CouchStretchPose()
            "HalfKneelingStretch" -> com.monkfitness.app.poses.HalfKneelingStretchPose()
            else -> error("unknown family member $name")
        }
        return SkeletonPipeline(def).produceFrame(builder, ctx).pose
    }

    // =============================================================================================
    // Criterion 2 — the production direct-`solveIK` bypass family
    // =============================================================================================

    @Test
    fun criterionTwo_directBypassFamilyIsResolved() {
        // Remaining production solver calls must be exactly the accepted classifications: canonical
        // engine-side (IkStage), canonical authoring implementation (the two bakes), sanctioned
        // planning solve, R3 settlement re-solve (ConstraintSolver), and the solver math itself.
        // These three audits own the inventory, the heuristic absence and the legacy-reconstruction
        // consumer count, each with per-file counts pinned.
        delegate("Criterion 2 — direct solveIK inventory (per-file counts)") {
            LimbSolverOwnershipActivationContractTest().noUnauthorizedDirectSolveInProductionPoses()
        }
        delegate("Criterion 2 — no isArm heuristic / no unregistered limb-solve path") {
            RuntimeSolverOwnershipAuditTest().noJointNameHeuristicOrUnregisteredLimbSolvePathRemains()
        }
        delegate("Criterion 2 — no legacy reconstruction consumer, no second evidence writer") {
            RuntimeSolverOwnershipAuditTest()
                .equivalenceHarnessIntroducesNoSolverPathNoEvidenceWriterAndNoLegacyReconstruction()
        }
    }

    // =============================================================================================
    // Criterion 3 — lossless authored limb-data recovery
    // =============================================================================================

    @Test
    fun criterionThree_declaredLimbContextReachesTheEngineSolver() {
        // (a) The declared context decodes losslessly (both the constraint and the lengths), and an
        // undeclared target fails fast instead of recovering definition defaults.
        delegate("Criterion 3 — declared constraint decode + undeclared fail-fast") {
            LimbSolverOwnershipActivationContractTest().losslessStraightConstraintDecode()
        }
        delegate("Criterion 3 — declared bone-length decode") {
            LimbSolverOwnershipActivationContractTest().losslessBoneLengthDecode()
        }
        // (b) The engine realizes EXACTLY the declared limb set on every registered realization path
        // (member bake, planning-solve family, package bake, validation bake).
        delegate("Criterion 3 — realized set == declared set on all registered paths") {
            RuntimeSolverOwnershipAuditTest().activeConfigurationExecutesExactlyOneEngineRealization()
        }

        // (c) No hidden recovery remains in the stage: the body of `IkStage.apply` may not read any
        // solve input from the definition (that was the `isArm` + definition-length recovery of B-3)
        // — every input comes from the Limb Target.
        val applyBody = bodyOf("IkStage.kt") { it.contains("fun apply(pose: SkeletonPose") }
        val definitionReads = applyBody.withIndex().filter { (_, raw) ->
            val line = stripComment(raw)
            line.contains("definition.") || line.contains("isArm")
        }.map { (i, raw) -> "IkStage.kt:${i + 1} ${stripComment(raw).trim()}" }
        assertEquals(
            "Criterion 3 (B-3): the engine stage must recover no solve input from the definition " +
                "(no length/constraint coincidence, no joint-name heuristic):\n" +
                definitionReads.joinToString("\n"),
            emptyList<String>(), definitionReads
        )
        assertTrue("the stage must still take the declared target as its solve input", applyBody.any {
            stripComment(it).contains("target.length1")
        } && applyBody.any { stripComment(it).contains("target.length2") } &&
            applyBody.any { stripComment(it).contains("target.constraint") })

        // (d) Complete declared context over a representative production set (the same carriers the
        // activated configuration realises): every declared Limb Target carries lengths + constraint.
        for ((label, factory) in listOf<Pair<String, () -> PoseBuilder>>(
            "SquatPose (member bake)" to { SquatPose() },
            "StandardPushUpPose (contact posture family)" to { StandardPushUpPose() },
            "QuadrupedThoracicRotationsPose (package bake, snapshot carrier)" to { QuadrupedThoracicRotationsPose() }
        )) {
            val built = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("Criterion 3: $label must declare limbs (anti-vacuity)", built.limbTargets.isNotEmpty())
            for (target in built.limbTargets) {
                assertFalse(
                    "Criterion 3: $label:${target.joint} must declare length1 (NaN = undeclared)",
                    target.length1.isNaN()
                )
                assertFalse(
                    "Criterion 3: $label:${target.joint} must declare length2 (NaN = undeclared)",
                    target.length2.isNaN()
                )
                assertNotNull(
                    "Criterion 3: $label:${target.joint} must declare its constraint (null = undeclared)",
                    target.constraint
                )
            }
        }
    }

    // =============================================================================================
    // Criterion 4 — strengthened single-active-solver enforcement
    // =============================================================================================

    @Test
    fun criterionFour_strengthenedEnforcementRejectsASecondRealization() {
        // (a) A second realization of one limb in one build cycle is rejected on EXECUTION evidence
        // even when both executions produce a byte-identical frame (WP-G counterfactual).
        delegate("Criterion 4 — double realization rejected despite identical output") {
            SingleActiveSolverEnforcementTest().doubleRealizationFailsEvenWithIdenticalPose()
        }
        delegate("Criterion 4 — counterfactual double execution rejected in both configurations") {
            RuntimeSolverOwnershipAuditTest().counterfactualDoubleExecutionIsRejectedDeterministically()
        }

        // (b) …and the rejection is execution-evidence based: the pipeline's enforcement block is a
        // `check(...)` contract on the registered realization counters, never a comparison of
        // produced geometry.
        val pipeline = sourcesOf("SkeletonPipeline.kt")
        val windowCheck = pipeline.indexOfFirst { stripComment(it).contains("limbSolverExecutions == 1") }
        assertTrue(
            "Criterion 4: the pipeline must enforce exactly one realization WINDOW per frame",
            windowCheck >= 0
        )
        assertTrue(
            "Criterion 4: the window enforcement must be a `check(...)` contract",
            pipeline.subList(maxOf(0, windowCheck - 2), windowCheck + 1).any { stripComment(it).contains("check(") }
        )
        val duplicateCheck = pipeline.indexOfFirst {
            val line = stripComment(it)
            line.contains("check(") && line.contains("limbDuplicateRealizations == 0")
        }
        assertTrue(
            "Criterion 4: the pipeline must reject on the per-execution duplicate evidence (a " +
                "`check(...)` contract on the registered counter, not a rewritten message)",
            duplicateCheck >= 0
        )
        val evidenceBlock = pipeline.subList(duplicateCheck, (duplicateCheck + 10).coerceAtMost(pipeline.size))
        assertTrue(
            "Criterion 4: the rejection must report the duplicate realization events",
            evidenceBlock.any { it.contains("duplicate realization events") }
        )
        assertTrue(
            "Criterion 4: the enforcement must remain execution-evidence based (the rejection " +
                "reports the registered window count)",
            evidenceBlock.any { it.contains("limbSolverExecutions") }
        )
        assertTrue(
            "Criterion 4: the enforcement must remain execution-evidence based (the rejection " +
                "reports the realized-limb mask)",
            evidenceBlock.any { it.contains("limbRealizedLimbs") }
        )
        assertFalse(
            "Criterion 4: enforcement must not be decided on produced geometry — no float/raw-bit " +
                "comparison may appear in the evidence block",
            evidenceBlock.any { it.contains("toRawBits") || it.contains("abs(") }
        )
    }

    // =============================================================================================
    // Criterion 5 — the §12.9 equivalence harness
    // =============================================================================================

    @Test
    fun criterionFive_section129EquivalenceCorpusIsGreen() {
        // The accepted WP-H result, composed rather than replaced by a smaller smoke test: the FULL
        // corpus (39 representative entries × progress sweep, both configurations, raw-bit exact at
        // the published boundary) is executed here through its own harness, and its report is parsed
        // so the gate fails if the corpus shrank or an adjudication/relaxation appeared.
        val report = File(System.getProperty("java.io.tmpdir"), "p12-wph-equivalence-report.txt")
        delegate("Criterion 5 — §12.9 cross-configuration equivalence corpus") {
            ActivationEquivalenceTest().corpusIsEquivalentAcrossOwnershipConfigurations()
        }
        assertTrue("Criterion 5: the corpus must have produced its report at ${report.path}", report.isFile)
        val text = report.readText()
        val total = text.lineSequence().first { it.startsWith("TOTAL ") }
        val cases = Regex("""cases=(\d+)""").find(total)!!.groupValues[1].toInt()
        val frames = Regex("""frames=(\d+)""").find(total)!!.groupValues[1].toInt()
        val deltas = Regex("""deltas=(\d+)""").find(total)!!.groupValues[1].toInt()
        val premiseFailures = Regex("""premiseFailures=(\d+)""").find(total)!!.groupValues[1].toInt()
        assertEquals("Criterion 5: the corpus must report zero deltas ($total)", 0, deltas)
        assertEquals("Criterion 5: every per-configuration ownership premise must hold ($total)", 0, premiseFailures)
        assertTrue(
            "Criterion 5: the corpus must not shrink below the accepted WP-H coverage ($total) — " +
                "an activation smoke test is explicitly not accepted as §12.9 evidence",
            cases >= 131 && frames >= 143
        )
        assertFalse(
            "Criterion 5: no relaxation/adjudication may be introduced by the activation step",
            text.contains("ADJUDICATED ")
        )
    }

    // =============================================================================================
    // Criterion 6 — validation re-certification
    // =============================================================================================

    @Test
    fun criterionSix_validationProbesAreReCertified() {
        // The validation probes execute, observe and pass in BOTH configurations, and the reading
        // they publish is produced by the ACTIVE implementation (the engine stage in state 3).
        delegate("Criterion 6 — probe reading produced by the active implementation") {
            ActivationEquivalenceTest().validationProbesAreReCertifiedInBothOwnershipConfigurations()
        }
        delegate("Criterion 6 — the drop reading comes from the active solver window") {
            ValidationOwnershipReCertificationTest().straightProbeReadingComesFromActiveImplementation()
        }
        delegate("Criterion 6 — the validator never solves or writes geometry (R9)") {
            ValidationOwnershipReCertificationTest().validatorNeverSolvesOrWritesGeometry()
        }
        delegate("Criterion 6 — fresh build window carries no previous realization (WP-F)") {
            ValidationOwnershipReCertificationTest().freshBuildWindowCarriesNoPreviousRealization()
        }
    }

    // =============================================================================================
    // The deployed state — §12.0 state 2 → state 3
    // =============================================================================================

    @Test
    fun productionConfigurationIsStateThree() {
        // (1) The deployed runtime value. This is the ONE place the deployed default is asserted at
        // runtime; the declaration's default is asserted statically by the §12.7 configuration
        // surface audit, so the two together cover "one declaration, state-3 default, no writer".
        assertTrue(
            "§12.10/§12.0: the production configuration must be state 3 (IK_STAGE_ACTIVE=true)",
            IK_STAGE_ACTIVE
        )

        // (2) Configuration surface: exactly one declaration, zero production writes, no hidden
        // channel. `IK_STAGE_ACTIVE=true` in the runtime is only meaningful if nothing else can move
        // it — an environment variable or a system property that silently changed ownership would
        // make every criterion above unreachable in production.
        val declaration = Regex("""^\s*var IK_STAGE_ACTIVE\s*:\s*Boolean\s*=""")
        val assignment = Regex("""^\s*IK_STAGE_ACTIVE\s*=(?!=)""")
        val flagMention = Regex("""(?<![A-Za-z0-9_])IK_STAGE_ACTIVE\b""")
        var declarations = 0
        var writes = 0
        val readers = mutableSetOf<String>()
        for ((path, lines) in productionSources()) {
            for (raw in lines) {
                val line = stripComment(raw)
                if (line.isEmpty()) continue
                when {
                    declaration.containsMatchIn(line) -> declarations++
                    assignment.containsMatchIn(line) -> writes++
                    flagMention.containsMatchIn(line) -> readers.add(path.substringAfterLast('/'))
                }
            }
        }
        assertEquals("exactly one declaration may exist", 1, declarations)
        assertEquals("no production execution path may write the flag", 0, writes)
        assertEquals(
            "reads must stay at the realization-decision sites (stage gate, three bake gates, " +
                "the pipeline's enforcement block)",
            setOf("IkStage.kt", "BasePose.kt", "BaseValidationPose.kt", "SkeletonPipeline.kt"),
            readers
        )
        // No alternate activation switch: production sources carry no environment/system-property
        // channel that could select the limb-solver implementation.
        val envChannels = productionSources().filterValues { lines ->
            lines.any {
                val line = stripComment(it)
                line.contains("System.getenv") || line.contains("System.getProperty") ||
                    line.contains("BuildConfig.IK_STAGE")
            }
        }.keys.filter { it.endsWith("IkStage.kt") || it.endsWith("SkeletonPipeline.kt") }
        assertEquals(
            "no environment/system-property/alternate-flag channel may select the limb-solver " +
                "implementation: $envChannels",
            emptyList<String>(), envChannels
        )

        // (3) In the deployed configuration the enforcement formula is exactly `count == 1`: the
        // stage counts its window at entry, above every no-work early return, so a frame that
        // reaches the pipeline always reports one window and the legacy
        // `count == 0 && !IK_STAGE_ACTIVE` disjunct is unreachable (zero-limb frames included).
        val applyBody = bodyOf("IkStage.kt") { it.contains("fun apply(pose: SkeletonPose") }
        val gate = applyBody.indexOfFirst { stripComment(it).trim() == "if (!IK_STAGE_ACTIVE) return" }
        val increment = applyBody.indexOfFirst { stripComment(it).contains("limbSolverExecutions++") }
        val noWorkReturns = applyBody.withIndex().filter { (_, raw) ->
            val line = stripComment(raw).trim()
            line.endsWith("return") && line.startsWith("if (") && !line.startsWith("if (!IK_STAGE_ACTIVE")
        }.map { (i, _) -> i }
        assertTrue("the stage gate must exist", gate >= 0)
        assertTrue("the window instantiation must sit below the gate", gate < increment)
        assertTrue("the activated stage must have no-work early returns", noWorkReturns.isNotEmpty())
        assertTrue(
            "the window count must be instantiated ABOVE every no-work early return " +
                "(increment=$increment, no-work returns=$noWorkReturns) — otherwise an activated " +
                "frame could report zero windows and the retired-disjunct reading would return",
            noWorkReturns.all { it > increment }
        )
    }

    // =============================================================================================
    // §14 — deliberate post-activation failure checks (production path, deployed configuration)
    // =============================================================================================

    @Test
    fun activatedProductionRejectsASecondRealizationOfTheSameLimb() {
        // The production state must deterministically reject a REAL second realization of one limb
        // in one build cycle. The injection is a duplicated declared Limb Target on the carrier — the
        // stage then executes the same limb's solve twice inside one window — and the rejection comes
        // from the production enforcement path, never from a fabricated evidence value.
        IK_STAGE_ACTIVE = true
        val built = SquatPose().build(PoseContext(0f, Side.LEFT, def))
        val declared = built.limbTargets.firstOrNull { it.joint == Joint.ANKLE_F }
            ?: built.limbTargets.first()
        assertFalse("anti-vacuity: the injected target must carry a declared context", declared.length1.isNaN())
        built.limbTargets.add(declared.copy())
        assertEquals("the carrier must now declare the same limb twice", declared, built.limbTargets.last())

        val violation = runCatching {
            SkeletonPipeline(def).produceFrame(built)
        }.exceptionOrNull()
        assertTrue(
            "the activated production configuration must reject two realizations of one limb in one " +
                "cycle. Observed: " +
                (violation?.let { "${it::class.simpleName}: ${it.message}" } ?: "no violation raised"),
            violation is IllegalStateException &&
                violation.message.orEmpty().contains("R5 violation") &&
                violation.message.orEmpty().contains("duplicate realization events")
        )
    }

    @Test
    fun activatedProductionAcceptsAnOrdinaryActiveFrame() {
        // The positive half: an ordinary active frame (one engine realization, no duplicates) must
        // pass. Acceptance itself is the enforcement evidence — `runStages` admits a frame only when
        // the carrier it inspects reported exactly one realization window and zero duplicate events
        // (in the deployed configuration that formula is exactly `count == 1`,
        // see [productionConfigurationIsStateThree]).
        IK_STAGE_ACTIVE = true
        val built = SquatPose().build(PoseContext(0.5f, Side.LEFT, def))
        assertEquals(
            "premise: the gated authoring bake realizes nothing while the stage owns realization",
            0, built.limbSolverExecutions
        )
        val declared = built.limbTargets.map { it.joint }.toSet()
        assertTrue("premise: the fixture must declare limbs (anti-vacuity)", declared.isNotEmpty())

        val frame = runCatching { SkeletonPipeline(def).produceFrame(built) }.getOrElse {
            error("an ordinary active frame must be accepted in the deployed configuration: ${it.message}")
        }
        assertTrue("the published frame must be finite", frame.pose.getJoint(Joint.KNEE_F).y.isFinite())
        assertTrue("the published frame must be transforms-updated", frame.pose.isTransformsUpdated)
    }

    /**
     * FINDING F-1 — the snapshot-returning build template (`BaseThoracicPose.finalizeThoracicPose`)
     * carries no authoring realization evidence on its returned carrier. WP-I codified that boundary
     * as intentional (it is the P3 suppression pattern + evidence locality), and this test proves the
     * boundary cannot hide a second realization in the deployed state: the same family that the
     * flag-OFF disjunct admits IS rejected under the deployed configuration when it realizes a limb
     * twice.
     */
    @Test
    fun f1SnapshotFamilyCannotHideASecondRealization() {
        IK_STAGE_ACTIVE = true
        val exporters = listOf<Pair<String, () -> PoseBuilder>>(
            "ThoracicExtension" to { ThoracicExtensionPose() },
            "QuadrupedThoracicRotations" to { QuadrupedThoracicRotationsPose() },
            "DynamicWorldsGreatestStretch" to { DynamicWorldsGreatestStretchPose() }
        )
        for ((name, factory) in exporters) {
            // (a) the F-1 fact itself, and the anti-vacuity premise that the template declares limbs
            val built = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("F-1 premise: $name must declare limbs through a registered bake", built.limbTargets.isNotEmpty())
            assertEquals(
                "F-1: the snapshot-returning template carries no authoring realization evidence on " +
                    "the returned carrier (the bake registered on the builder's own carrier)",
                0, built.limbSolverExecutions
            )

            // (b) the deployed configuration is NOT admitted by the flag-OFF disjunct: an ordinary
            // frame is accepted, which the pipeline can only do through `count == 1` here.
            val accepted = runCatching { SkeletonPipeline(def).produceFrame(factory(), PoseContext(0.5f, Side.LEFT, def)) }
                .exceptionOrNull()
            assertTrue(
                "F-1: the activated configuration must register exactly one engine realization " +
                    "window on the carrier the pipeline inspects ($name): " +
                    (accepted?.let { "${it::class.simpleName}: ${it.message}" } ?: "accepted"),
                accepted == null
            )

            // (c) …and a second realization of one of its limbs is rejected, so the boundary cannot
            // mask a second solver for this family either.
            val duplicated = factory().build(PoseContext(0.5f, Side.LEFT, def))
            duplicated.limbTargets.add(duplicated.limbTargets.first().copy())
            val violation = runCatching { SkeletonPipeline(def).produceFrame(duplicated) }.exceptionOrNull()
            assertTrue(
                "F-1: $name must reject a duplicated realization under the deployed configuration. " +
                    "Observed: " + (violation?.let { "${it::class.simpleName}: ${it.message}" } ?: "no violation"),
                violation is IllegalStateException &&
                    violation.message.orEmpty().contains("duplicate realization events")
            )
        }
    }

    // =============================================================================================
    // helpers
    // =============================================================================================

    /**
     * Runs a delegated authoritative check. Composition, not duplication: if the owning suite
     * regresses, the gate fails with the responsible criterion named.
     */
    private fun delegate(label: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            throw AssertionError(
                "$label: the delegated activation evidence FAILED — ${failure.message}", failure
            )
        }
    }

    private val productionSourceCache: Map<String, List<String>> by lazy {
        val root = File(productionJavaRoot())
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to it.readLines() }
    }

    private fun productionSources(): Map<String, List<String>> = productionSourceCache

    private fun sourcesOf(fileName: String): List<String> =
        productionSources().entries.first { it.key.endsWith("/$fileName") }.value

    /** Brace-matched body of the block starting at the first line matching [predicate]. */
    private fun bodyOf(fileName: String, predicate: (String) -> Boolean): List<String> {
        val lines = sourcesOf(fileName)
        return functionBody(lines, lines.indexOfFirst(predicate))
    }

    private fun functionBody(lines: List<String>, start: Int): List<String> {
        assertTrue("block anchor not found", start >= 0)
        var end = start
        var depth = 0
        var opened = false
        while (end < lines.size) {
            val line = lines[end]
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && end > start && depth <= 0) break
            end++
        }
        return lines.subList(start, end + 1)
    }

    private fun stripComment(line: String): String {
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("*/")) return ""
        return line.substringBefore("//")
    }

    private fun productionJavaRoot(): String {
        var dir = File(System.getProperty("user.dir"))
        for (attempt in 0 until 8) {
            val candidate = File(dir, "src/main/java/com/monkfitness/app")
            if (candidate.isDirectory) return candidate.absolutePath
            dir = dir.parentFile ?: break
        }
        error("Could not locate the app module root from ${System.getProperty("user.dir")}")
    }
}
