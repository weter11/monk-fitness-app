package com.monkfitness.app.arch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 4 (R5) — configuration / ownership static audit
 * (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P4 enforcement + §12.0 states 1–2).
 *
 * Complements [com.monkfitness.app.IkStageTest] (behavioral) and
 * [ValidationStampWriteSiteAuditTest] (stamp-write) with the source-level contract that the
 * P4 runtime-window counter structurally CANNOT see: authoring-vs-stage exclusivity is today a
 * CONFIGURATION property, and the configuration is pinned by three facts:
 *
 *  1. `IK_STAGE_ACTIVE` has ZERO production writes — it is a rollout flag whose only production
 *     assignment is its `false` declaration; flipping it ON in production is a P12 transition,
 *     not a runtime possibility (RFC R5: "rollout mechanism, not architecture").
 *  2. Its sole production read is the `IkStage.apply` gate — no other subsystem branches on it.
 *  3. `limbSolverExecutions` is incremented at exactly one production site (`IkStage`, the one
 *     engine Phase-1 window), checked/reset only by `SkeletonPipeline.runStages`, and absent
 *     from `copyFrom` (Published Pose State can never inherit it — P3 suppression pattern).
 *
 * If P12 activates the stage, THIS audit is the one the plan retargets (§12.7/§12.8) — failing
 * it outside P12 means someone made flag-ON reachable without the activation contract.
 */
class RuntimeSolverOwnershipAuditTest {

    private val sources: Map<String, List<String>> by lazy { productionSources() }

    @Test
    fun ikStageFlagHasNoProductionWriteAndConfigReadsOnlyAtGateAndCheck() {
        val assignment = Regex("""^\s*IK_STAGE_ACTIVE\s*=(?!=)""")
        val declaration = Regex("""^\s*var IK_STAGE_ACTIVE\s*:\s*Boolean\s*=\s*false""")
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
            "IK_STAGE_ACTIVE must have no production writes before P12 (rollout flag; flips " +
                "belong to tests only)",
            0, writes
        )
        assertEquals("the flag must be declared exactly once, default false", 1, declarations)
        // Two reads are legitimate: the IkStage gate (the decision) and the pipeline's
        // runtime-window check, which reads the SAME config only to express the plan's
        // `count == 0 && stage skipped` disjunct — no new branching logic.
        assertEquals(
            "flag reads must stay at the gate + the R5 window check",
            setOf("IkStage.kt", "SkeletonPipeline.kt"),
            readSites.map { it.substringBefore(':') }.toSet()
        )
        assertTrue("the gate must remain the stage's entry check", readSites.any { it.startsWith("IkStage.kt:") })
    }

    @Test
    fun runtimeWindowCounterHasExactlyOneIncrementSiteCheckedByThePipeline() {
        val increment = Regex("""\bpose\.limbSolverExecutions\+\+""")
        val checkSite = Regex("""limbSolverExecutions""")
        val increments = mutableListOf<String>()
        val perFile = mutableMapOf<String, Int>()
        for ((path, lines) in sources) {
            for ((i, raw) in lines.withIndex()) {
                val line = stripComment(raw)
                if (increment.containsMatchIn(line)) {
                    increments.add("${path.substringAfterLast('/')}:${i + 1}")
                }
                if (checkSite.containsMatchIn(line)) {
                    perFile[path.substringAfterLast('/')] = (perFile[path.substringAfterLast('/')] ?: 0) + 1
                }
            }
        }
        assertEquals("exactly one production increment site (IkStage)", listOf("IkStage.kt:61"), increments)
        // Declared+documented in PoseDefinition, incremented in IkStage, checked+reset in the
        // pipeline. No other production file may touch the counter.
        assertEquals(
            "unexpected files reference the runtime-window counter",
            setOf("PoseDefinition.kt", "IkStage.kt", "SkeletonPipeline.kt"),
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
