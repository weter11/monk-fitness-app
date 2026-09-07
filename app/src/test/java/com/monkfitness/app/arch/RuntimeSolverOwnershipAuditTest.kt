package com.monkfitness.app.arch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 4 (R5) configuration / ownership static audit — P12 §12.7 RETARGETED FORM.
 * (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P4 enforcement, §12.0 state 3, §12.7, §12.8 item 4.)
 *
 * The P4 shape pinned "flag-ON is unreachable" (zero production writes + a stage-only
 * counter). P12 activates the stage, so the contract this audit now enforces is the
 * strengthened ownership contract:
 *
 *  1. `IK_STAGE_ACTIVE` remains a CONFIGURATION SURFACE: its only production write is the
 *     declaration itself, whose default WP-I flips once (production-valid). No runtime code
 *     path may WRITE the flag — flips belong to the declared configuration surface (and to
 *     tests under flag-scoped restore), never to execution logic.
 *  2. Reads are confined to the realization-decision sites: the engine stage gate, the three
 *     registered authoring bakes' realization gates (§12.7a), and the pipeline's window check.
 *     No other subsystem may branch on it.
 *  3. `limbSolverExecutions` is incremented at BOTH registered realization sites — the bake
 *     realization branch (member/package/validation paths, `BasePose.kt` + `BaseValidationPose.kt`)
 *     and the engine stage window (`IkStage.kt`) — so the counter can OBSERVE the historical
 *     violation mode (authoring solver blind to the instrument). P4's "exactly one increment
 *     site" was the vacuity this phase closes (§12.7b). Checked/reset only by
 *     `SkeletonPipeline.runStages`; absent from `copyFrom` (P3 suppression pattern).
 *
 * Pre-P12 this audit was RED against P12's machinery BY DESIGN (that was the WP-A contract's
 * B-property); this file records the post-transition contract.
 */
class RuntimeSolverOwnershipAuditTest {

    private val sources: Map<String, List<String>> by lazy { productionSources() }

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
    fun solverWindowCounterIsIncrementedAtEveryRegisteredRealizationSite() {
        val increment = Regex("""\blimbSolverExecutions\+\+""")
        val perFile = mutableMapOf<String, Int>()
        val sites = mutableListOf<String>()
        for ((path, lines) in sources) {
            for ((i, raw) in lines.withIndex()) {
                val line = stripComment(raw)
                if (increment.containsMatchIn(line)) {
                    sites.add("${path.substringAfterLast('/')}:${i + 1}")
                }
                if (line.contains("limbSolverExecutions")) {
                    perFile[path.substringAfterLast('/')] = (perFile[path.substringAfterLast('/')] ?: 0) + 1
                }
            }
        }
        // Both registered realization windows count: the engine stage (1) + the three bake
        // realization branches (member / package-level / validation). A bake site that
        // realized WITHOUT incrementing would re-open the P4 blindness — the count above
        // pins every realization branch to an increment.
        assertEquals(
            "increment sites must be exactly the stage window + the 3 bake realization " +
                "branches (§12.7b)",
            4, sites.size
        )
        assertEquals(
            setOf("IkStage.kt", "BasePose.kt", "BaseValidationPose.kt"),
            sites.map { it.substringBefore(':') }.toSet()
        )
        assertEquals("the stage window increments exactly once", 1, sites.count { it.startsWith("IkStage.kt") })
        assertEquals("the member bake + the package-level bake increment once each",
            2, sites.count { it.startsWith("BasePose.kt") })
        // Declared+documented in PoseDefinition, incremented at the realization sites above,
        // checked+reset in the pipeline. No other production file may touch the counter.
        assertEquals(
            "unexpected files reference the solver-window counter",
            setOf("PoseDefinition.kt", "IkStage.kt", "BasePose.kt", "BaseValidationPose.kt", "SkeletonPipeline.kt"),
            perFile.keys
        )
    }

    @Test
    fun windowCounterIsExcludedFromCarrierCopy() {
        val poseDef = sources.entries.first { it.key.endsWith("/PoseDefinition.kt") }.value
        val copyFromStart = poseDef.indexOfFirst { it.trim() == "fun copyFrom(other: SkeletonPose) {" }
        assertTrue("copyFrom not found — the audit anchor moved", copyFromStart >= 0)
        var end = copyFromStart
        var depth = 0
        while (end < poseDef.size) {
            depth += poseDef[end].count { it == '{' } - poseDef[end].count { it == '}' }
            if (end > copyFromStart && depth <= 0) break
            end++
        }
        val body = poseDef.subList(copyFromStart, end + 1)
        assertTrue(
            "limbSolverExecutions must never appear in copyFrom (Published Pose State cannot " +
                "inherit instrumentation; P3 suppression pattern)",
            body.none { stripComment(it).contains("limbSolverExecutions") }
        )
    }

    // ------------------------------------------------------------------

    private fun stripComment(line: String): String {
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("*/")) return ""
        return line.substringBefore("//")
    }

    private fun productionSources(): Map<String, List<String>> {
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
        val srcDir = File(root, "src/main/java")
        return srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate {
                it.relativeTo(srcDir).path.replace(File.separatorChar, '/') to it.readLines()
            }
    }
}
