package com.monkfitness.app.arch

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * P12 (§12.4b) — the sanctioned planning solve's inventory lock.
 *
 * `planLimbPlacement` is the ONLY authoring-time limb-solve computation allowed to exist outside
 * the registered Active Limb Solver implementations, and the plan admits it for exactly one
 * purpose: composing later Limb Targets for a family whose choreography depends on an earlier
 * limb's bent placement (the hip-flexor chain). If it grows additional callers it is becoming a
 * shadow solver, which R5 forbids; that expansion requires an explicit plan amendment, so this
 * audit pins the call sites.
 *
 * The primitive body itself (BasePose.kt) is the only place allowed to touch `solveIK` outside
 * the registered implementations — see
 * [LimbSolverOwnershipActivationContractTest.noUnauthorizedDirectSolveInProductionPoses].
 */
class PlanningSolveInventoryTest {

    @Test
    fun planningSolveCallSitesAreConfinedToTheSanctionedFamily() {
        val root = productionJavaRoot()
        val sites = mutableListOf<String>()
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                val code = line.substringBefore("//")
                if (code.contains("planLimbPlacement(") && !code.contains("fun planLimbPlacement")) {
                    sites.add("${f.relativeTo(File(root)).path}:${i + 1}")
                }
            }
        }
        assertEquals(
            "planLimbPlacement may be called ONLY from the hip-flexor family's sanctioned " +
                "composition site (§12.4b); a new call site means a new authoring-side limb " +
                "solve outside the registered implementations — plan amendment required",
            setOf("poses/BaseHipFlexorPose.kt"),
            sites.map { it.substringBeforeLast(':') }.toSet()
        )
        assertEquals("exactly one planning-solve call site is sanctioned", 1, sites.size)
    }

    private fun productionJavaRoot(): String {
        var dir = File(System.getProperty("user.dir"))
        for (attempt in 0 until 8) {
            val candidate = File(dir, "src/main/java/com/monkfitness/app")
            if (candidate.isDirectory) return candidate.absolutePath
            dir = dir.parentFile ?: break
        }
        error("Could not locate app module root from ${System.getProperty("user.dir")}")
    }
}
