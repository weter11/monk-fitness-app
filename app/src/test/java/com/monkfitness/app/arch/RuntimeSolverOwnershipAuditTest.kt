package com.monkfitness.app.arch

import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.IkStage
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.poses.CouchStretchPose
import com.monkfitness.app.poses.HalfKneelingStretchPose
import com.monkfitness.app.poses.PelvicTiltPose
import com.monkfitness.app.poses.SquatPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 4 (R5) configuration / ownership audit — P12 §12.7 RETARGETED FORM, WP-G-strengthened.
 * (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P4 enforcement, §12.0 state 3, §12.7, §12.8 item 4.)
 *
 * The P4 shape pinned "flag-ON is unreachable" (zero production writes + a stage-only counter).
 * P12 activates the stage, so the contract this audit enforces is the strengthened ownership
 * contract, in two halves:
 *
 * **A. Static (source-structure) checks.**
 *  1. `IK_STAGE_ACTIVE` remains a CONFIGURATION SURFACE: its only production write is the
 *     declaration itself, whose default WP-I flipped once (state 3 — the deployed value is asserted
 *     statically here and at runtime by `ActivationGateTest.productionConfigurationIsStateThree`).
 *     Reads are confined to the
 *     realization-decision sites (the engine stage gate, the three registered bake gates, the
 *     pipeline's enforcement block).
 *  2. The realization evidence has exactly ONE registration path —
 *     `SkeletonPose.registerLimbRealization` — called at exactly the four registered realization
 *     points (member bake, package bake, validation bake, engine stage). No realization site
 *     writes the evidence fields directly any more.
 *  3. §12.7d: in every registered implementation the configuration gate precedes BOTH the
 *     registration and the solve, and the authored realization blocks are the only ones that
 *     write limb node locals (file-level confinement, with the two non-solver writers — the
 *     Phase-2 contact re-solve and the Finalizer's FK/derivation pass — classified explicitly).
 *  4. §12.7d: no `isArm`-style joint-name heuristic and no direct `solveIK` call outside the
 *     classified implementation files has appeared after WP-D.
 *
 * **B. Execution-ownership checks (the invariant itself, not a flag or a symbol).** For every
 * registered realization path: the AUTHORING configuration (`IK_STAGE_ACTIVE=false`, R5's
 * non-deployed rollout selection) executes exactly ONE authoring realization and zero engine-side
 * ones; the DEPLOYED activated configuration (`IK_STAGE_ACTIVE=true`, §12.0 state 3) executes
 * exactly ONE engine-side realization and zero authoring ones; and a counterfactual
 * two-realization cycle is rejected deterministically on the registered evidence.
 *
 * LIMITS (stated so the checks are not over-read): these are source-structure and behaviour
 * checks over the current file set, not a proof of the whole codebase. The local-write
 * confinement pins WHICH files may write node locals and verifies the registered blocks are
 * gated; it does not prove that every write inside an allow-listed file is authored layout rather
 * than realization. The behavioural half is what closes that gap: a realization that happened
 * where it should not would register evidence and be rejected.
 */
class RuntimeSolverOwnershipAuditTest {

    private val sources: Map<String, List<String>> by lazy { productionSources() }
    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalFlag = IK_STAGE_ACTIVE

    // P12 WP-I (§12.8 disposition): the former P4 premise ("the deployed configuration is flag-OFF")
    // is retired together with the state-2 default it pinned. Every behavioural case in this audit
    // sets the configuration it is about explicitly, and the deployed-state claim now lives in
    // exactly two authoritative places: the configuration-surface test below (the DECLARATION's
    // default, statically) and `ActivationGateTest.productionConfigurationIsStateThree` (the loaded
    // runtime value).

    @After
    fun restoreFlag() {
        IK_STAGE_ACTIVE = originalFlag
    }

    // ================================================================ A. static contract

    @Test
    fun ikStageFlagIsDeclarationOnlyAndReadOnlyAtRealizationDecisionSites() {
        val assignment = Regex("""^\s*IK_STAGE_ACTIVE\s*=(?!=)""")
        val declaration = Regex("""^\s*var IK_STAGE_ACTIVE\s*:\s*Boolean\s*= """)
        var writes = 0
        var declarations = 0
        val readSites = mutableListOf<String>()
        for ((path, lines) in sources) {
            for ((i, raw) in lines.withIndex()) {
                val line = stripComment(raw)
                if (line.isEmpty()) continue
                if (declaration.containsMatchIn(line)) {
                    declarations++
                } else if (assignment.containsMatchIn(line)) {
                    writes++
                } else if (Regex("""(?<![A-Za-z0-9_])IK_STAGE_ACTIVE\b""").containsMatchIn(line)) {
                    readSites.add("${path.substringAfterLast('/')}:${i + 1}")
                }
            }
        }
        assertEquals(
            "no execution path may WRITE the flag — the only production write is the " +
                "declaration's default (config surface; §12.7 flag lifecycle)",
            0, writes
        )
        assertEquals("the flag must be declared exactly once", 1, declarations)
        // §12.8 disposition of the retired `IkStageTest.flagDefaultsFalse`: the configuration
        // SURFACE owns the deployed default now (this static half) together with the runtime gate
        // (`ActivationGateTest.productionConfigurationIsStateThree`). Asserting the declaration's
        // default here is what makes the flip reviewable: a state-2 default cannot come back
        // unnoticed, and it is the same single declaration the write/read audit above counts.
        val declarationLine = sources.entries.first { it.key.endsWith("/IkStage.kt") }.value
            .map { stripComment(it).trim() }
            .single { it.startsWith("var IK_STAGE_ACTIVE") }
        assertEquals(
            "the deployed production default must be the state-3 value (§12.0 state 3: the engine " +
                "stage is the Active Limb Solver): $declarationLine",
            "true", declarationLine.substringAfter("=").trim()
        )
        // Reads are legal only at the realization-decision sites: the stage gate, the three
        // registered bake realization gates, and the pipeline's R5 window check.
        assertEquals(
            "flag reads must stay at the realization gates + the R5 window check " +
                "(IkStage, the bake paths, SkeletonPipeline)",
            setOf("IkStage.kt", "BasePose.kt", "BaseValidationPose.kt", "SkeletonPipeline.kt"),
            readSites.map { it.substringBefore(':') }.toSet()
        )
        assertTrue("the gate must remain the stage's entry check", readSites.any { it.startsWith("IkStage.kt:") })
    }

    @Test
    fun realizationEvidenceIsRegisteredAtTheCanonicalSitesAndNowhereElse() {
        // WP-G: the evidence registration is ONE function called at exactly the four registered
        // realization points. A fifth call site — or a realization branch that realizes without
        // registering — re-opens the P4 blindness (that was the §12.7b vacuity this closes).
        val callSites = mutableListOf<String>()
        val declarations = mutableListOf<String>()
        for ((path, lines) in sources) {
            for ((i, raw) in lines.withIndex()) {
                val line = stripComment(raw)
                if (line.isEmpty()) continue
                if (!line.contains("registerLimbRealization(")) continue
                val site = "${path.substringAfterLast('/')}:${i + 1}"
                if (line.contains("fun registerLimbRealization(")) declarations.add(site) else callSites.add(site)
            }
        }
        assertEquals("the evidence path must be declared exactly once", 1, declarations.size)
        assertTrue("the declaration lives in the carrier", declarations.single().startsWith("PoseDefinition.kt:"))
        assertEquals(
            "registration sites must be exactly the 4 registered realization points " +
                "(member bake, package bake, validation bake, engine stage): $callSites",
            4, callSites.size
        )
        assertEquals(
            setOf("BasePose.kt", "BaseValidationPose.kt", "IkStage.kt"),
            callSites.map { it.substringBefore(':') }.toSet()
        )
        assertEquals("the member bake + the package-level bake register once each",
            2, callSites.count { it.startsWith("BasePose.kt") })
        assertEquals("the engine stage registers per realized target",
            1, callSites.count { it.startsWith("IkStage.kt") })
        assertEquals("the validation bake registers once",
            1, callSites.count { it.startsWith("BaseValidationPose.kt") })

        // The evidence FIELDS are now touched by the single registration helper (PoseDefinition),
        // the stage's window instantiation, and the pipeline's check/reset — never by a bake site
        // directly. This is the "one authoritative execution-evidence path" contract.
        val evidenceFields = Regex(
            """\blimbSolverExecutions\b|\blimbSolverRealizationToken\b|\blimbRealizedLimbs\b|\blimbDuplicateRealizations\b"""
        )
        val perFile = mutableMapOf<String, Int>()
        for ((path, lines) in sources) {
            for (raw in lines) {
                val line = stripComment(raw)
                if (line.isEmpty()) continue
                if (evidenceFields.containsMatchIn(line)) {
                    perFile[path.substringAfterLast('/')] = (perFile[path.substringAfterLast('/')] ?: 0) + 1
                }
            }
        }
        assertEquals(
            "the realization evidence must be confined to its declaration/registration file, the " +
                "stage window instantiation, and the pipeline's enforcement block: $perFile",
            setOf("PoseDefinition.kt", "IkStage.kt", "SkeletonPipeline.kt"),
            perFile.keys
        )
        // The only remaining direct counter write is the stage's window instantiation (WP-G moved
        // the two authoring sites behind the helper).
        val windowIncrement = sources.values.sumOf { lines ->
            lines.count { stripComment(it).contains("limbSolverExecutions++") }
        }
        assertEquals("only the engine window instantiates the counter directly", 1, windowIncrement)
    }

    @Test
    fun realizationEvidenceIsExcludedFromCarrierCopy() {
        val poseDef = sources.entries.first { it.key.endsWith("/PoseDefinition.kt") }.value
        val copyFromStart = poseDef.indexOfFirst { it.trim() == "fun copyFrom(other: SkeletonPose) {" }
        assertTrue("copyFrom not found — the audit anchor moved", copyFromStart >= 0)
        val body = functionBody(poseDef, copyFromStart)
        val forbidden = listOf(
            "limbSolverExecutions", "limbSolverRealizationToken",
            "limbRealizedLimbs", "limbDuplicateRealizations"
        )
        for (field in forbidden) {
            assertTrue(
                "$field must never appear in copyFrom (Published Pose State cannot inherit " +
                    "instrumentation; P3 suppression pattern)",
                body.none { stripComment(it).contains(field) }
            )
        }
    }

    // -------------------------------------------------------------- §12.7d static audit

    @Test
    fun authoringBakesRealizeOnlyBelowTheirConfigurationGate() {
        // §12.7d: "no authoring realization branch runs while activation is ON". Proven
        // structurally per registered bake: inside the bake's own body, the configuration gate
        // precedes BOTH the evidence registration and the realization (solve + limb writes). This
        // is not a file-wide grep — every assertion is scoped to the function it audits.
        val sites = listOf(
            Triple("BasePose.kt", "protected fun bakeIkLimb(", "member bake"),
            Triple("BasePose.kt", "fun bakeIkLimb(", "package bake"),
            Triple("BaseValidationPose.kt", "protected fun bakeIkLimb(", "validation bake")
        )
        for ((file, signature, label) in sites) {
            val body = bodyOf(file) { it.trim().startsWith(signature) }
            val gate = body.indexOfFirst { stripComment(it).trim().startsWith("if (IK_STAGE_ACTIVE) return") }
            val registration = body.indexOfFirst { stripComment(it).contains("registerLimbRealization(") }
            val solve = body.indexOfFirst {
                val l = stripComment(it)
                l.contains("SkeletonMath.solveIK(") || l.contains("SkeletonMath.solveStraightLimb(")
            }
            val limbWrites = body.withIndex().filter {
                Regex("""toLocalDirection\([^)]*\.localPosition\)""").containsMatchIn(stripComment(it.value))
            }.map { it.index }
            assertTrue("$file/$label: the configuration gate must exist", gate >= 0)
            assertTrue("$file/$label: the realization must register evidence", registration >= 0)
            assertTrue("$file/$label: the realization must solve", solve >= 0)
            assertEquals("$file/$label: the realization writes the middle + end offsets", 2, limbWrites.size)
            assertTrue(
                "$file/$label: the gate ($gate) must precede the evidence registration ($registration) " +
                    "— registration and the fresh-window re-arm are configuration-neutral (§12.5, WP-F)",
                gate < registration
            )
            assertTrue(
                "$file/$label: the gate ($gate) must precede the solve ($solve) — the authoring " +
                    "implementation realizes only while the stage is disabled (§12.7a)",
                gate < solve
            )
            assertTrue(
                "$file/$label: the gate ($gate) must precede every limb write ($limbWrites)",
                limbWrites.all { it > gate }
            )
        }
    }

    @Test
    fun engineStageRealizesOnlyBelowItsConfigurationGate() {
        // §12.7d: "no engine realization branch runs while activation is OFF". The stage's entry
        // gate must precede its window instantiation, its per-target registration and its solve.
        val body = bodyOf("IkStage.kt") { it.contains("fun apply(pose: SkeletonPose") }
        val gate = body.indexOfFirst { stripComment(it).trim() == "if (!IK_STAGE_ACTIVE) return" }
        val window = body.indexOfFirst { stripComment(it).contains("limbSolverExecutions++") }
        val registration = body.indexOfFirst { stripComment(it).contains("registerLimbRealization(") }
        val solve = body.indexOfFirst {
            val l = stripComment(it)
            l.contains("SkeletonMath.solveIK(") || l.contains("SkeletonMath.solveStraightLimb(")
        }
        assertEquals("the stage must open with its configuration gate", 1, gate)
        assertTrue("the window instantiation must sit below the gate", gate < window)
        assertTrue("the per-target registration must sit below the gate", gate < registration)
        assertTrue("the realize call must sit below the gate", gate < solve)
        assertTrue("the registration must immediately precede the solve it evidences", registration < solve)
    }

    @Test
    fun limbRealizationWritesStayInsideTheRegisteredImplementations() {
        // §12.7d: "no middleNode/endNode localPosition writes outside the two registered
        // implementations." The realization write idiom is the solver's framed write into a limb
        // node's local offset. Pinned per file, with the one classified non-solver writer —
        // the Phase-2 Contact Re-Solve — anchored structurally rather than by name.
        val idiom = Regex("""toLocalDirection\([^)]*\.localPosition\)""")
        val perFile = mutableMapOf<String, Int>()
        for ((path, lines) in sources) {
            for (raw in lines) {
                val line = stripComment(raw)
                if (line.isEmpty()) continue
                if (idiom.containsMatchIn(line)) {
                    perFile[path.substringAfterLast('/')] = (perFile[path.substringAfterLast('/')] ?: 0) + 1
                }
            }
        }
        assertEquals(
            "limb realization writes must be exactly {registered implementations + the classified " +
                "writers below}, two offsets (middle + end) per realization site: $perFile",
            mapOf(
                "BasePose.kt" to 4,             // member bake + package bake
                "BaseValidationPose.kt" to 2,   // validation bake
                "IkStage.kt" to 2,              // engine stage
                "ConstraintSolver.kt" to 2,     // CLASSIFIED: contact re-solve (below)
                "BaseBarSupportPose.kt" to 1    // CLASSIFIED: MANUAL_OVERRIDE distal-hand authoring (below)
            ),
            perFile
        )
        // The classified writer, anchored: both ConstraintSolver limb writes are inside the
        // contacts re-solve loop — the settlement pass that re-bakes ALREADY-realized contact
        // limbs to honour their declared ContactSpecs (R3). It registers no limb realization
        // because it is not an Active Limb Solver implementation: its inputs are the registered
        // ContactSpecs, and it adds no limb the frame had not already declared.
        val solverLines = sources.entries.first { it.key.endsWith("/ConstraintSolver.kt") }.value
        val solveCall = Regex("""SkeletonMath\.solve(IK|StraightLimb)\(""")
        val reSolveRanges = solverLines.withIndex()
            .filter { it.value.contains("for (spec in contacts)") }
            .map { (index, _) ->
                val body = functionBody(solverLines, index)
                index until (index + body.size)
            }
            .filter { range -> range.any { solveCall.containsMatchIn(stripComment(solverLines[it])) } }
        assertTrue("the contact re-solve loop must be locatable", reSolveRanges.isNotEmpty())
        val solverWrites = solverLines.withIndex()
            .filter { idiom.containsMatchIn(stripComment(it.value)) }
            .map { it.index }
        assertEquals("the contact re-solve writes middle + end", 2, solverWrites.size)
        assertTrue(
            "the only non-implementation limb writes must be the Phase-2 contact re-solve loop " +
                "(writes at ${solverWrites.map { it + 1 }}, re-solve loops at $reSolveRanges)",
            solverWrites.all { write -> reSolveRanges.any { write in it } }
        )

        // The second classified writer, anchored. The bar-support family's flat-grip authoring writes the
        // DISTAL hand chain (palm → knuckles → fingertips) of a MANUAL_OVERRIDE hand — never the middle/end
        // node of an IK chain, and never a realization. Why a pose must author that geometry at all:
        // `SkeletonPoseFinalizer` completes a hand's distal joints ONLY while the extremity is AUTOMATIC
        // (`if (pose.isExtremityAutomatic(Extremity.HAND_A))` gates `adjustHandOrientation`), so a pose that
        // declares `overrideExtremityOrientation` (W1 MANUAL_OVERRIDE) owns that geometry verbatim and the
        // factory ships those nodes with zero offsets. Measured on the row's own published frames: with the
        // authored offsets the chain is `22 u` long and lies `0.000 u` off the bar's top plane at all five
        // sampled phases; with the authoring helper neutered it collapses to a zero-length chain at the
        // wrist; and under the engine's AUTOMATIC derivation (override dropped, no authored grip) the same
        // chain tilts out of that plane by up to `20.927 u` at the bottom of the rep.
        //
        // NOTE (open, recorded not resolved): whether a pose should be able to state a
        // `MANUAL_OVERRIDE` extremity through this offset idiom at all — versus the wrist-articulation
        // channel `PikePushUpPose` uses — is an ownership question for the architecture owner; the corpus
        // has no other pose-side writer of this idiom, and this guard is where it would be decided.
        val barLines = sources.entries.first { it.key.endsWith("/BaseBarSupportPose.kt") }.value
        val barWrites = barLines.withIndex()
            .filter { idiom.containsMatchIn(stripComment(it.value)) }
            .map { it.index }
        assertEquals("the bar-support family authors one distal-hand write site", 1, barWrites.size)
        val segmentHelper = barLines.indexOfFirst { it.contains("private fun setSegment(") }
        assertTrue("the distal-hand authoring helper must be locatable", segmentHelper >= 0)
        assertTrue(
            "the bar-support limb write must live inside its distal-hand authoring helper " +
                "(write at ${barWrites.map { it + 1 }}, helper at ${segmentHelper + 1})",
            barWrites.all { it >= segmentHelper && it < segmentHelper + functionBody(barLines, segmentHelper).size }
        )
        val segmentCalls = barLines.withIndex()
            .filter { it.value.contains("setSegment(") && !it.value.contains("private fun") }
        assertEquals("the helper authors the three distal hand segments", 3, segmentCalls.size)
        assertTrue(
            "the helper is only ever handed a DISTAL hand node — never an IK middle/end node: " +
                segmentCalls.map { it.index + 1 },
            segmentCalls.all { (_, line) ->
                listOf("palmNode", "knucklesNode", "fingertipsNode").any { line.contains(it) }
            }
        )
        val rowsLines = sources.entries.first { it.key.endsWith("/RowsPose.kt") }.value
        val gripCalls = rowsLines.filter { it.contains("authorFlatBarGrip(") && !it.contains("protected fun") }
        assertEquals("both hands' flat grips are authored", 2, gripCalls.size)
        assertTrue(
            "the flat grip must be authored on each hand's DISTAL chain (palm + knuckles + fingertips), " +
                "never on an IK middle/end node: ${gripCalls.map { it.trim() }}",
            gripCalls.all { line ->
                listOf("palmA!!", "knucklesA!!", "fingertipsA!!", "palmP!!", "knucklesP!!", "fingertipsP!!")
                    .count { line.contains(it) } == 3 && !line.contains("elbow")
            }
        )
    }

    @Test
    fun remainingNodeLocalWritersAreClassifiedAndNoneIsARealization() {
        // Every OTHER direct `localPosition.set(...)` writer must be one of the classified
        // authoring/derivation surfaces: (a) authoring pose files declaring their own layout,
        // (b) the standard-skeleton authoring helpers in BasePose, (c) the validation family's
        // authoring helpers, (d) the Finalizer's head/neck FK derivation, (e) the legacy
        // `fromJointPositions` reconstruction that has zero production consumers (WP-D).
        val pattern = Regex("""\blocalPosition\.set\(""")
        val writers = mutableMapOf<String, Int>()
        for ((path, lines) in sources) {
            for (raw in lines) {
                val line = stripComment(raw)
                if (line.isEmpty()) continue
                if (pattern.containsMatchIn(line)) {
                    writers[path.substringAfterLast('/')] = (writers[path.substringAfterLast('/')] ?: 0) + 1
                }
            }
        }
        // The classified direct-write surfaces: (b) the authoring layout helpers of the two
        // registered authoring bases, (d) the Finalizer's FK derivation, (e) the legacy
        // reconstruction. Everything else that writes node locals directly must be an authoring
        // pose file declaring its own layout.
        val classified = mapOf(
            "BasePose.kt" to 5,
            "BaseValidationPose.kt" to 4,
            "PoseDefinition.kt" to 8,
            "SkeletonPoseFinalizer.kt" to 2
        )
        for ((file, expected) in classified) {
            assertEquals(
                "$file writes node locals directly $expected times — a change here must be reviewed " +
                    "against §12.7d before updating this audit (all writers: $writers)",
                expected, writers[file] ?: 0
            )
        }
        val unclassified = writers.keys - classified.keys
        assertEquals(
            "unclassified production file writes node locals directly — every remaining writer must " +
                "be an authoring pose declaring its own layout",
            emptyList<String>(),
            unclassified.filter { name -> sources.keys.none { it.contains("/poses/") && it.endsWith("/$name") } }
        )
        assertTrue(
            "anti-vacuity: authoring pose files are expected to declare their own layout",
            unclassified.isNotEmpty()
        )

        // (b) receiver-pinned: the BasePose writes are the fixed authoring layout of the standard
        // skeleton helpers, not solver output. (The realization writes use the framed
        // `toLocalDirection(..., node.localPosition)` idiom, checked in the test above.)
        val layoutReceivers = setOf("chest", "hipF", "hipB", "shoulderA", "shoulderP")
        val basePoseDirect = sources.entries.first { it.key.endsWith("/BasePose.kt") }.value
            .withIndex().filter { pattern.containsMatchIn(stripComment(it.value)) }
        assertEquals("BasePose direct node-local writes are authoring layout only", 5, basePoseDirect.size)
        for ((i, raw) in basePoseDirect) {
            val receiver = stripComment(raw).trim().substringBefore(".localPosition")
            assertTrue(
                "BasePose's direct node-local write at line ${i + 1} must be authoring layout, " +
                    "not solver output (receiver: $receiver)",
                receiver in layoutReceivers
            )
        }

        // (d) the Finalizer writes are the FK/derivation pass, receiver-pinned.
        val finalizerWrites = sources.entries.first { it.key.endsWith("/SkeletonPoseFinalizer.kt") }.value
            .filter { pattern.containsMatchIn(stripComment(it)) }
        assertTrue(
            "the Finalizer's node writes must target neck/head (FK derivation), not limb chains: $finalizerWrites",
            finalizerWrites.all {
                val receiver = stripComment(it).trim().substringBefore(".localPosition")
                receiver == "neck" || receiver == "head"
            }
        )
        // (e) the legacy reconstruction writes live inside `fromJointPositions`.
        val poseDef = sources.entries.first { it.key.endsWith("/PoseDefinition.kt") }.value
        val legacy = functionBody(poseDef, poseDef.indexOfFirst { it.contains("fun fromJointPositions(") })
        val legacyRange = poseDef.indexOfFirst { it.contains("fun fromJointPositions(") } until
            (poseDef.indexOfFirst { it.contains("fun fromJointPositions(") } + legacy.size)
        val poseDefWrites = poseDef.withIndex().filter { pattern.containsMatchIn(stripComment(it.value)) }.map { it.index }
        assertEquals("PoseDefinition's node-local writes are the legacy reconstruction", 8, poseDefWrites.size)
        assertTrue("every PoseDefinition node-local write lives in fromJointPositions", poseDefWrites.all { it in legacyRange })
    }

    @Test
    fun noJointNameHeuristicOrUnregisteredLimbSolvePathRemains() {
        // §12.7d: (a) the `isArm` heuristic (B-3) must be absent; (b) no direct limb solve outside
        // the classified implementation files may appear after WP-D — the permit-list is asserted
        // WITH per-file counts, so a new direct solve inside an allow-listed file also surfaces.
        val isArm = sources.filterValues { lines ->
            lines.any { stripComment(it).contains(Regex("""\bisArm\b""")) }
        }.keys.map { it.substringAfterLast('/') }
        assertEquals("the isArm joint-name heuristic must not return (B-3 lossless decode)", emptyList<String>(), isArm)

        val solvePattern = Regex("""SkeletonMath\.solve(IK|StraightLimb)\(""")
        val perFile = mutableMapOf<String, Int>()
        for ((path, lines) in sources) {
            for (raw in lines) {
                val line = stripComment(raw)
                if (line.isEmpty()) continue
                if (solvePattern.containsMatchIn(line)) {
                    perFile[path.substringAfterLast('/')] = (perFile[path.substringAfterLast('/')] ?: 0) + 1
                }
            }
        }
        // Classified on the WP-D basis (LimbSolverOwnershipActivationContractTest sweeps pose files
        // for the bypass family): the three registered implementations and the Phase-2 contact
        // re-solve. Counts are pinned so a NEW direct solve inside an allow-listed file surfaces
        // here instead of hiding behind the allow-list. BasePose's extra calls are the sanctioned
        // §12.4b planning solve + the two bake sites (straight/bent branch each).
        assertEquals(
            "production limb-solve call sites must match the classified implementation set: $perFile",
            mapOf(
                "BasePose.kt" to 6,
                "BaseValidationPose.kt" to 2,
                "IkStage.kt" to 2,
                "ConstraintSolver.kt" to 2
            ),
            perFile
        )
        for (implementation in listOf("BasePose.kt", "BaseValidationPose.kt", "IkStage.kt")) {
            assertTrue(
                "$implementation realizes limbs and must therefore register realization evidence",
                sources.entries.first { it.key.endsWith("/$implementation") }.value
                    .any { stripComment(it).contains("registerLimbRealization(") }
            )
        }
    }

    /**
     * WP-H §8 — the sanctioned §12.4b **planning solve** is composition intent, never realization:
     * it writes no nodes, registers no evidence, produces no stamp readings and counts into no
     * solver window, so it cannot become a second Active Limb Solver by accident. Verified
     * structurally (both helper bodies) and behaviourally (the one admitted family), with the
     * cross-configuration equivalence of those families owned by `ActivationEquivalenceTest`.
     */
    @Test
    fun planningSolveComposesIntentOnlyAndIsNeverRealizationEvidence() {
        // (a) structural: both planning-solve bodies are composition-only.
        for (signature in listOf("protected fun planLimbPlacement(", "fun planLimbPlacement(")) {
            val body = bodyOf("BasePose.kt") { it.trim().startsWith(signature) }
            val code = body.map { stripComment(it) }
            assertEquals(
                "$signature: the planning solve must call the solver exactly once", 1,
                code.count { it.contains("SkeletonMath.solveIK(") }
            )
            for (forbidden in listOf(
                "registerLimbRealization", "limbTargets", "toLocalDirection", "localPosition",
                "ValidationStampMerge", "limbSolverExecutions", "setJoint"
            )) {
                assertTrue(
                    "$signature: the planning solve must not contain `$forbidden` — composition " +
                        "intent only, no realization and no carrier write (§12.4b)",
                    code.none { it.contains(forbidden) }
                )
            }
        }

        // (b) behavioural: the one admitted family (hip-flexor chain) — the planning solve adds no
        // window, no duplicate and no stamp, and the composed targets reach the canonical solver
        // identically in both configurations.
        val hipFlexor = listOf<Pair<String, () -> PoseBuilder>>(
            "CouchStretch" to { CouchStretchPose() },
            "HalfKneelingStretch" to { HalfKneelingStretchPose() }
        )
        for ((name, factory) in hipFlexor) {
            IK_STAGE_ACTIVE = false
            val deployed = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name: limbs must be declared (anti-vacuity)", deployed.limbTargets.isNotEmpty())
            assertEquals(
                "$name (flag-OFF): exactly ONE realization window — the registered bake's. The " +
                    "planning solve must not count as Phase-1 realization",
                1, deployed.limbSolverExecutions
            )
            assertEquals("$name (flag-OFF): no duplicate realization", 0, deployed.limbDuplicateRealizations)

            IK_STAGE_ACTIVE = true
            val activated = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertEquals(
                "$name (flag-ON): the build window registers no realization at all — the planning " +
                    "solve is not evidence",
                0, activated.limbSolverExecutions
            )
            assertEquals(0, activated.limbDuplicateRealizations)
            assertEquals(
                "$name: the same declared intent (composed from the planning solve) must reach the " +
                    "canonical active solver in both configurations",
                deployed.limbTargets.map { it.joint to it.world.x.toRawBits() },
                activated.limbTargets.map { it.joint to it.world.x.toRawBits() }
            )
        }
    }

    // ------------------------------------------------- WP-H §15 audit extension
    @Test
    fun equivalenceHarnessIntroducesNoSolverPathNoEvidenceWriterAndNoLegacyReconstruction() {
        // WP-H §15 — additive audit extension (the WP-G checks above are unchanged and unweakened).
        // Four WP-H-specific structural claims:
        //  (1) the §12.9 equivalence harness observes ONLY through the public pipeline boundary —
        //      no direct solver call, no direct stage call, no evidence registration, and no
        //      geometry reconstruction; the two allow-listed authoring-side fixtures are counted;
        //  (2) no `setJoint(solvedResult…) -> fromJointPositions` path has returned: the legacy
        //      reconstruction has zero production consumers outside its declaration file;
        //  (3) the ConstraintSolver settlement boundary is intact: the Phase-2 contact re-solve
        //      neither registers R5 evidence nor reads the Limb Targets nor references the stage;
        //  (4) no additional evidence mutation path exists: the four evidence fields stay confined
        //      to the files the WP-G audit pins, including in the new test sources.
        val harnessFile = "ActivationEquivalenceTest.kt"
        val harness = testSources().entries.firstOrNull { it.key.endsWith("/$harnessFile") }
            ?: error("the WP-H equivalence harness must exist in the arch test package")
        val forbidden = listOf(
            "SkeletonMath.solveIK(", "SkeletonMath.solveStraightLimb(", "IkStage.apply",
            "registerLimbRealization(", "fromJointPositions(", "setJoint(", "limbTargets.add"
        )
        val offenders = mutableListOf<String>()
        var bakeCalls = 0
        var settlementCalls = 0
        for ((i, raw) in harness.value.withIndex()) {
            val line = stripComment(raw)
            if (line.isEmpty()) continue
            for (pattern in forbidden) if (line.contains(pattern)) offenders += "$harnessFile:${i + 1} $pattern"
            if (line.contains("bakeIkLimb(")) bakeCalls++
            if (line.contains("ConstraintSolver.solve(")) settlementCalls++
        }
        assertEquals(
            "the equivalence harness must observe through the pipeline boundary only:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders
        )
        // The two allow-listed authoring-side calls, pinned with their reasons:
        //  - `bakeIkLimb(` ×2: the REGRESSION-A probe is an authoring fixture (it declares limbs
        //    through the registered member bake exactly like any pose; the flag gates realization),
        //    and no corpus case calls a solver directly;
        //  - `ConstraintSolver.solve(` ×1: the §9 settlement-boundary adjudication invokes the
        //    Phase-2 settlement pass to prove it is NOT an R5 realization path.
        assertEquals("only the test-only authoring probe may call the registered bake", 2, bakeCalls)
        assertEquals("only the §9 boundary test may invoke the settlement pass", 1, settlementCalls)

        // WP-I §14 addition — the §12.10 ACTIVATION GATE is audited by the same rules, with exactly
        // ONE allow-listed intent mutation: the duplicated Limb Target it injects to prove that the
        // PRODUCTION enforcement path rejects a real second realization of one limb (the injection
        // must be registered intent, never a fabricated evidence value). Everything else stays
        // forbidden, so the gate's failure proofs cannot come from a direct solver or stage call.
        val gateFile = "ActivationGateTest.kt"
        val gate = testSources().entries.firstOrNull { it.key.endsWith("/$gateFile") }
            ?: error("the §12.10 activation gate must exist in the arch test package")
        val gateForbidden = forbidden.filterNot { it == "limbTargets.add" }
        val gateOffenders = mutableListOf<String>()
        var gateInjections = 0
        for ((i, raw) in gate.value.withIndex()) {
            val line = stripComment(raw)
            if (line.isEmpty()) continue
            for (pattern in gateForbidden) if (line.contains(pattern)) gateOffenders += "$gateFile:${i + 1} $pattern"
            if (line.contains("limbTargets.add(")) gateInjections++
        }
        assertEquals(
            "the activation gate must drive the PRODUCTION path only (no direct solver/stage call, no " +
                "evidence registration, no geometry reconstruction):\n" + gateOffenders.joinToString("\n"),
            emptyList<String>(), gateOffenders
        )
        assertEquals(
            "the gate's only intent mutation is the duplicated Limb Target used to prove that the " +
                "production enforcement rejects a second realization",
            2, gateInjections
        )

        // (2) no legacy reconstruction consumer has returned.
        val legacyConsumers = sources.filterValues { lines ->
            lines.any { stripComment(it).contains("fromJointPositions(") }
        }.keys.filterNot { it.endsWith("/PoseDefinition.kt") }
        assertEquals(
            "`fromJointPositions` must keep zero production consumers (WP-D eliminated the " +
                "position-reconstruction family): $legacyConsumers",
            emptyList<String>(), legacyConsumers
        )
        assertTrue(
            "anti-vacuity: the legacy helper must still be declared where it is audited",
            sources.entries.first { it.key.endsWith("/PoseDefinition.kt") }.value
                .any { stripComment(it).contains("fun fromJointPositions(") }
        )

        // (3) the ConstraintSolver settlement boundary (static half of the §9 adjudication).
        val solver = sources.entries.first { it.key.endsWith("/ConstraintSolver.kt") }.value
            .map { stripComment(it) }
        assertTrue(
            "the Phase-2 contact re-solve must not register R5 realization evidence — it is R3 " +
                "settlement ownership, not an Active Limb Solver implementation",
            solver.none { it.contains("registerLimbRealization") }
        )
        assertTrue(
            "the Phase-2 contact re-solve must not consume the Limb Targets as a hidden second " +
                "limb realization path",
            solver.none { it.contains("limbTargets") }
        )
        assertTrue(
            "the Phase-2 contact re-solve must not reference the engine limb stage",
            solver.none { it.contains("IkStage") }
        )
        assertTrue(
            "anti-vacuity: the settlement re-solve surface must still exist in this file",
            solver.any { it.contains("for (spec in contacts)") }
        )

        // (4) the evidence fields stay confined to the WP-G-pinned files, test sources included.
        val evidenceFields = Regex(
            """\blimbSolverExecutions\b|\blimbSolverRealizationToken\b|\blimbRealizedLimbs\b|\blimbDuplicateRealizations\b"""
        )
        val testWriters = testSources().filterValues { lines ->
            lines.any { evidenceFields.containsMatchIn(stripComment(it)) }
        }.keys.map { it.substringAfterLast('/') }.toSet()
        assertEquals(
            "realization evidence may only be READ in tests (never written) — a test that writes " +
                "the evidence fields directly would fabricate enforcement proof: $testWriters",
            setOf("ActivationEquivalenceTest.kt", "ActivationGateTest.kt",
                "RuntimeSolverOwnershipAuditTest.kt",
                "SingleActiveSolverEnforcementTest.kt", "DefaultPoleOwnershipTest.kt",
                "StraightIntentFallbackTest.kt", "ValidationOwnershipReCertificationTest.kt",
                "LimbSolverOwnershipActivationContractTest.kt",
                // P12 §12.7 — the lifecycle suite reads the same execution evidence to state the
                // "one realization per declared limb" invariant and the double-realization trap.
                // It is a READER only: the write-count assertion below covers it like every other
                // test source, so extending this set cannot legalise a fabricated evidence value.
                "SingleActiveSolverLifecycleTest.kt"),
            testWriters
        )
        val testWrites = testSources().values.sumOf { lines ->
            lines.count { line ->
                val l = stripComment(line)
                Regex("""\b(limbSolverExecutions|limbSolverRealizationToken|limbRealizedLimbs|limbDuplicateRealizations)\s*=(?!=)""")
                    .containsMatchIn(l)
            }
        }
        assertEquals(
            "no test may WRITE the realization evidence fields (the WP-G rule: never fabricate the " +
                "evidence value — inject through the registered production path instead)",
            0, testWrites
        )
    }

    // ====================================================== B. execution-ownership checks
    /** The four registered realization paths, built under the current configuration. */
    private fun registeredPathMatrix(): List<Pair<String, SkeletonPose>> {
        val ctx = PoseContext(0f, Side.LEFT, def)
        val factories: List<Pair<String, () -> PoseBuilder>> = listOf(
            "member bake (BasePose.bakeIkLimb)" to { SquatPose() },
            "member bake + planning solve (hip-flexor family)" to { CouchStretchPose() },
            "package bake (PoseBuilder)" to { PelvicTiltPose() },
            "validation bake (BaseValidationPose.bakeIkLimb)" to { MiddleSplitPose() }
        )
        return factories.map { (label, factory) -> label to factory().build(ctx) }
    }

    private fun realizedJoints(pose: SkeletonPose): Set<Joint> =
        Joint.entries.filterTo(mutableSetOf()) { pose.limbRealizedLimbs and (1L shl it.index) != 0L }

    @Test
    fun inactiveConfigurationExecutesExactlyOneAuthoringRealization() {
        IK_STAGE_ACTIVE = false
        for ((label, pose) in registeredPathMatrix()) {
            val declared = pose.limbTargets.map { it.joint }.toSet()
            assertTrue("$label: the fixture must declare limbs (anti-vacuity)", declared.isNotEmpty())
            assertEquals("$label: exactly one authoring realization window", 1, pose.limbSolverExecutions)
            assertEquals("$label: no limb realized twice", 0, pose.limbDuplicateRealizations)
            assertEquals(
                "$label: the authoring implementation realized exactly the declared limbs",
                declared, realizedJoints(pose)
            )

            // The engine-side implementation must contribute NOTHING while the configuration
            // disables it: no window, no evidence, no node write.
            val localsBefore = pose.roots.flatMap { nodeLocalBits(it) }
            IkStage.apply(pose, def)
            assertEquals("$label: zero engine-side realization windows", 1, pose.limbSolverExecutions)
            assertEquals("$label: zero engine-side duplicate executions", 0, pose.limbDuplicateRealizations)
            assertEquals("$label: the gated stage writes nothing", localsBefore, pose.roots.flatMap { nodeLocalBits(it) })
        }
    }

    @Test
    fun activeConfigurationExecutesExactlyOneEngineRealization() {
        IK_STAGE_ACTIVE = true
        for ((label, pose) in registeredPathMatrix()) {
            val declared = pose.limbTargets.map { it.joint }.toSet()
            assertTrue("$label: the fixture must declare limbs (anti-vacuity)", declared.isNotEmpty())
            // Registration is configuration-neutral (§12.5) — only realization moved.
            assertEquals("$label: the gated bake realizes nothing", 0, pose.limbSolverExecutions)
            assertEquals("$label: no authoring realization event", 0, pose.limbDuplicateRealizations)
            assertEquals("$label: the bake must have written no limb geometry", emptySet<Joint>(), realizedJoints(pose))

            IkStage.apply(pose, def)
            assertEquals("$label: exactly one engine-side realization window", 1, pose.limbSolverExecutions)
            assertEquals("$label: no limb realized twice", 0, pose.limbDuplicateRealizations)
            assertEquals(
                "$label: the engine realized exactly the declared limbs",
                declared, realizedJoints(pose)
            )
        }
    }

    @Test
    fun counterfactualDoubleExecutionIsRejectedDeterministically() {
        // Two realizations in one cycle must fail on the REGISTERED evidence, in both
        // configurations, without any output difference being involved.
        IK_STAGE_ACTIVE = false
        val authoring = runCatching {
            val pipeline = SkeletonPipeline(def)
            val ctx = PoseContext(0f, Side.LEFT, def)
            // Two build passes over ONE carrier without an intervening frame: each pass realizes
            // its limbs, and the second pass re-realizes them — one cycle, two executions per limb.
            val pose = SquatPose().build(ctx)
            pose.registerLimbRealization(Joint.ANKLE_F, authoringWindow = true)
            pipeline.produceFrame(pose)
        }.exceptionOrNull()
        assertTrue(
            "flag-OFF: a second realization of an already-realized limb must be rejected on execution " +
                "evidence. Observed: " +
                (authoring?.let { "${it::class.simpleName}: ${it.message}" } ?: "no violation raised"),
            authoring is IllegalStateException && authoring.message.orEmpty().contains("R5 violation")
        )

        IK_STAGE_ACTIVE = true
        val engine = runCatching {
            val pipeline = SkeletonPipeline(def)
            val ctx = PoseContext(0f, Side.LEFT, def)
            val pose = SquatPose().build(ctx)
            // The stage realizes the declared limbs (pipeline window), and a leaked second
            // realization of one of them is injected through the SAME registered evidence path.
            IkStage.apply(pose, def)
            pose.registerLimbRealization(Joint.ANKLE_F, authoringWindow = false)
            pipeline.produceFrame(pose)
        }.exceptionOrNull()
        assertTrue(
            "flag-ON: two realizations of one limb in one cycle must be rejected on execution " +
                "evidence. Observed: " +
                (engine?.let { "${it::class.simpleName}: ${it.message}" } ?: "no violation raised"),
            engine is IllegalStateException && engine.message.orEmpty().contains("R5 violation")
        )
    }

    // ====================================================================== helpers

    /** Brace-matched body of the block starting at the first line matching [predicate]. */
    private fun bodyOf(file: String, predicate: (String) -> Boolean): List<String> {
        val lines = sources.entries.first { it.key.endsWith("/$file") }.value
        return functionBody(lines, lines.indexOfFirst(predicate))
    }

    private fun nodeLocalBits(node: com.monkfitness.app.animation.SkeletonNode): List<Int> {
        val out = mutableListOf<Int>()
        fun walk(n: com.monkfitness.app.animation.SkeletonNode) {
            out.add(n.localPosition.x.toRawBits())
            out.add(n.localPosition.y.toRawBits())
            out.add(n.localPosition.z.toRawBits())
            for (child in n.children) walk(child)
        }
        walk(node)
        return out
    }

    /** Brace-matched body of the block starting at [start] (inclusive of the closing brace line). */
    private fun functionBody(lines: List<String>, start: Int): List<String> {
        assertTrue("block anchor not found", start >= 0)
        var end = start
        var depth = 0
        var opened = false
        while (end < lines.size) {
            val line = lines[end]
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            // Multi-line signatures: the opening brace may sit lines below the declaration, so the
            // slice only ends once a block has actually been opened and closed again.
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

    private fun productionSources(): Map<String, List<String>> = sourcesUnder("src/main/java")

    /**
     * WP-H §15 — the test sources, scanned by the same rules as the production sources so the
     * equivalence harness itself is auditable (it must observe only through the pipeline boundary
     * and must never write realization evidence).
     */
    private fun testSources(): Map<String, List<String>> = sourcesUnder("src/test/java")

    private fun sourcesUnder(relative: String): Map<String, List<String>> {
        var dir = File(System.getProperty("user.dir"))
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error(
            "Could not locate app module root from ${System.getProperty("user.dir")}"
        )
        val srcDir = File(root, relative)
        return srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate {
                it.relativeTo(srcDir).path.replace(File.separatorChar, '/') to it.readLines()
            }
    }
}
