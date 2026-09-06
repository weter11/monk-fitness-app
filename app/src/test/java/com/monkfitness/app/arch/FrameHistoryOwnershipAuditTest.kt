package com.monkfitness.app.arch

import com.monkfitness.app.animation.ConstraintSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 5 (R10) — ownership audit: the ConstraintSolver must hold NO persistent inter-frame
 * state (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P5 "Enforcement mechanism: compile-level
 * absence of the map"; RFC_RUNTIME_SKELETON_ARCHITECTURE §5 R10, §3 scratch-isolation rule).
 *
 * Two complementary prongs, per the repo's sole-producer/sole-state audit pattern (P3/P4):
 *
 *  1. A SOURCE scan of `ConstraintSolver.kt` (comments stripped, per the P4 pitfall-18 rule)
 *     fails if anyone reintroduces:
 *      - a `WeakHashMap` / `IdentityHashMap` (the deleted V3 cache types),
 *      - any `Map<…SkeletonPose…>`-typed state (identity-keyed pose cache in any form),
 *      - a field named like the deleted `lastSolvedRoot` / a retained "previous/last solved
 *        root" property,
 *      - a `static`-equivalent (object-level) mutable `SkeletonPose`-typed field — holding a
 *        pose reference across calls is the identity-cache pattern wearing a different hat.
 *  2. A REFLECTION scan of the `ConstraintSolver` singleton's actual runtime fields: no field
 *     whose generic signature mentions `SkeletonPose` (no pose retained), no Map-typed field
 *     whatsoever (the solver legitimately keeps only scratch vectors/nodes/arrays), and no
 *     non-final field of `SkeletonPose` type. This is the runtime trip-wire: a source scan
 *     can be evaded by renaming the file's identifiers, but the object's field layout cannot
 *     hide a retained pose/cache from reflection.
 *
 *  3. Plus the call-site prong: the temporal argument to `ConstraintSolver.solve` is supplied
 *     by the Frame History owner — production code outside `SkeletonPipeline.kt` must not call
 *     `solve(` at all (IkStage/BasePose only use `chainForEnd`), keeping the pipeline the sole
 *     supplier of smoothing history. The `solve` signature itself (a third, nullable `Vector3`
 *     history parameter) is pinned by reflection so the explicit-input contract cannot silently
 *     regress into hidden state.
 */
class FrameHistoryOwnershipAuditTest {

    private val sources: Map<String, List<String>> by lazy { productionSources() }

    // --------------------------------------------------------------- source scan (prong 1)

    @Test
    fun constraintSolverSourceHasNoIdentityKeyedOrPreviousRootState() {
        val solver = solverSourceStripped()
        val forbidden = listOf(
            "WeakHashMap" to Regex("""\bWeakHashMap\b"""),
            "IdentityHashMap" to Regex("""\bIdentityHashMap\b"""),
            "Map keyed by SkeletonPose" to Regex("""Map\s*<[^>]*\bSkeletonPose\b"""),
            "MutableMap keyed by SkeletonPose" to Regex("""MutableMap\s*<[^>]*\bSkeletonPose\b"""),
            "deleted V3 cache name" to Regex("""\blastSolvedRoot\b"""),
            "retained previous-root property" to Regex(
                """\b(?:private|internal|public)?\s*var\s+\w*(?:last|prev(?:ious)?)\w*(?:Root|Solved|History)\w*\s*[:=]"""
            )
        )
        for ((label, regex) in forbidden) {
            val hits = solver.filter { regex.containsMatchIn(it) }
            assertTrue(
                "R10 regression: ConstraintSolver.kt reintroduces $label at lines " +
                    hits.map { solver.indexOf(it) + 1 }.distinct(),
                hits.isEmpty()
            )
        }
    }

    @Test
    fun constraintSolverHoldsNoMapFieldsAtAllAndSolveTakesSuppliedHistory() {
        // Prong 2: runtime field layout of the singleton. The solver legitimately holds scratch
        // (Vector3 / JointRotation / arrays / node refs reset per call) — but NEVER a collection
        // (the deleted cache was a Map field) and NEVER a SkeletonPose reference (identity
        // retention). Reflection reads the real object, so a rename cannot evade it.
        val fields = ConstraintSolver::class.java.declaredFields.filter { !it.isSynthetic }
        val maps = fields.filter { java.util.Map::class.java.isAssignableFrom(it.type) }
        assertTrue(
            "R10 regression: ConstraintSolver holds Map field(s) " +
                maps.map { it.name } + " — cross-frame collections are forbidden; history is a solve() argument",
            maps.isEmpty()
        )
        val poseFields = fields.filter {
            it.genericType.toString().contains("SkeletonPose")
        }
        assertTrue(
            "R10 regression: ConstraintSolver retains SkeletonPose-typed field(s) " +
                poseFields.map { it.name } + " — no pose may be held across calls",
            poseFields.isEmpty()
        )

        // Prong 3a: the temporal input is an explicit parameter — solve(pose, definition,
        // previousRootWorld: Vector3?, …). If the parameter were dropped in favor of hidden
        // state, this signature check fails at the API level.
        val solve = ConstraintSolver::class.java.declaredMethods
            .firstOrNull { it.name == "solve" && it.parameterTypes.size >= 3 }
            ?: error("R10 regression: ConstraintSolver.solve lost its explicit history parameter — " +
                "temporal state must be a current-frame input, never hidden memory")
        assertTrue(
            "R10: solve's history parameter must be nullable Vector3 (first frame == null)",
            solve.genericParameterTypes[2].typeName.startsWith("com.monkfitness.app.animation.Vector3")
        )
    }

    @Test
    fun onlyTheFrameHistoryOwnerCallsTheSolverInProduction() {
        // Prong 3b: the smoothing history is supplied by SkeletonPipeline (the RFC §4.5 Frame
        // History owner). Production code outside SkeletonPipeline.kt must not call
        // `ConstraintSolver.solve` — an external caller would either re-hide history or create
        // a second temporal wiring site outside the owner. (chainForEnd/bake registrations are
        // fine and unaffected — only `solve(` is pinned.)
        val callers = mutableListOf<String>()
        for ((path, lines) in sources) {
            val file = path.substringAfterLast('/')
            if (file == "ConstraintSolver.kt") continue
            for ((i, raw) in lines.withIndex()) {
                val line = stripComment(raw)
                if (Regex("""\bConstraintSolver\s*\.\s*solve\s*\(""").containsMatchIn(line)) {
                    callers.add("$file:${i + 1}")
                }
            }
        }
        assertEquals(
            "ConstraintSolver.solve must be called in production ONLY by SkeletonPipeline " +
                "(the Frame History owner supplying the smoothing input) — found: $callers",
            setOf("SkeletonPipeline.kt"),
            callers.map { it.substringBefore(':') }.toSet()
        )
        val pipelineCalls = callers.count { it.startsWith("SkeletonPipeline.kt") }
        assertEquals("the pipeline must invoke solve from its single stage-chain site", 1, pipelineCalls)
    }

    // ------------------------------------------------------------------ shared plumbing

    private fun solverSourceStripped(): List<String> =
        solverSourceRaw().map { stripComment(it) }.filter { it.isNotBlank() }

    private fun solverSourceRaw(): List<String> {
        val entry = sources.entries.firstOrNull { it.key.endsWith("/ConstraintSolver.kt") }
            ?: error("ConstraintSolver.kt not found under src/main/java — the audit anchor moved")
        return entry.value
    }

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
