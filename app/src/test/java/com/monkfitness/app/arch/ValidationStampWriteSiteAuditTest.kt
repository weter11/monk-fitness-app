package com.monkfitness.app.arch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whitelist enforcement: a TEST-TIME audit (not runtime enforcement) asserting that raw
 * Validation Stamp writes occur only at explicitly authorized production sites.
 *
 * Model:
 *  - Every assignment to a stamp carrier field in `src/main/java` must either go through
 *    [com.monkfitness.app.animation.ValidationStampMerge] (a strengthening merge) or appear
 *    verbatim in [authorizedRawWrites] below, each with its architectural reason: the
 *    sanctioned build-scoped re-arms, sole-producer overwrite cycles, the provisional secondary
 *    strengthen, and the carrier-internal duplication in `copyFrom`.
 *  - Bidirectional: an unauthorized write fails the audit, and a whitelisted site that
 *    disappears (or duplicates) also fails — the whitelist cannot silently rot.
 *
 * Scanner limitations, by design: single-line assignments only; `//` comments are stripped
 * before matching; property declarations with an initializer (`var x: T = v`) and `==`
 * comparisons do not match. KDoc text is inert because it never contains the exact
 * whitelisted snippets.
 */
class ValidationStampWriteSiteAuditTest {

    private val stampFields = listOf(
        "boneLengthsVerified", "maxIkClampAmount", "rootTranslationDelta",
        "rootRotationDelta", "straightIntentDropped",
        "hipRomStamps", "bilateralSymmetryDelta", "bilateralOppositeBend"
    )

    private val assignmentRegex = Regex(
        """\b(boneLengthsVerified|maxIkClampAmount|rootTranslationDelta|""" +
            """rootRotationDelta|straightIntentDropped|hipRomStamps|bilateralSymmetryDelta|""" +
            """bilateralOppositeBend)\b(?!\s*:)[^=]*=(?!=)"""
    )

    /** file name -> (field, exact trimmed line, why this raw write exists). */
    private val authorizedRawWrites: Map<String, List<Triple<String, String, String>>> = mapOf(
        "BasePose.kt" to listOf(
            Triple(
                "boneLengthsVerified", "jointsBuffer.boneLengthsVerified = true",
                "Sanctioned build-scoped re-arm (F2): opens the fresh write window before any " +
                    "current-build producer has spoken; not a strengthening merge."
            ),
            Triple(
                "boneLengthsVerified", "buffer.boneLengthsVerified = true",
                "Sanctioned build-scoped re-arm (F2) in package-level bakeIkLimb; mirrors the " +
                    "member path."
            ),
            Triple(
                "straightIntentDropped", "jointsBuffer.straightIntentDropped = false",
                "Phase 4 (R5): dropped-flag re-arm in the same sanctioned fresh-window block " +
                    "(F2 pattern); OR identity is false."
            ),
            Triple(
                "straightIntentDropped", "buffer.straightIntentDropped = false",
                "Phase 4 (R5): dropped-flag re-arm in package-level bakeIkLimb; mirrors the " +
                    "member path."
            )
        ),
        "IkStage.kt" to listOf(
            Triple(
                "boneLengthsVerified", "pose.boneLengthsVerified = true",
                "Sanctioned re-arm (F2) in the engine-side limb stage — the ACTIVE realization " +
                    "path in the deployed configuration (state 3, IK_STAGE_ACTIVE=true)."
            )
        ),
        "BaseValidationPose.kt" to listOf(
            Triple(
                "boneLengthsVerified", "jointsBuffer.boneLengthsVerified = true",
                "Sanctioned re-arm (F2) in the diagnostic-instrument bake; mirrors the member path."
            ),
            Triple(
                "straightIntentDropped", "jointsBuffer.straightIntentDropped = false",
                "Phase 4 (R5): dropped-flag re-arm in the same sanctioned fresh-window block " +
                    "(F2 pattern); mirrors the member path."
            )
        ),

        "ConstraintSolver.kt" to listOf(
            Triple(
                "rootTranslationDelta", "pose.rootTranslationDelta = 0f",
                "Sole-producer overwrite cycle (RFC §4.4): settlement preamble reset."
            ),
            Triple(
                "rootRotationDelta", "pose.rootRotationDelta = 0f",
                "Sole-producer overwrite cycle (RFC §4.4): settlement preamble reset."
            ),
            Triple(
                "rootTranslationDelta",
                "pose.rootTranslationDelta = kotlin.math.sqrt(dxp * dxp + dyp * dyp + dzp * dzp)",
                "UNI-6 computation — ConstraintSolver is the sole §4.4 producer."
            ),
            Triple(
                "rootRotationDelta", "pose.rootRotationDelta = kotlin.math.abs(rootDeltaRot.angle)",
                "UNI-6 computation — ConstraintSolver is the sole §4.4 producer."
            )
        ),

        "SkeletonPoseFinalizer.kt" to listOf(
            Triple(
                "rootTranslationDelta",
                "pose.rootTranslationDelta = kotlin.math.max(pose.rootTranslationDelta, maxMove)",
                "Provisional secondary strengthen-only producer (V12, decision F3): monotonic " +
                    "max() complies with R6 mechanics; the §4.4 producer-count discrepancy is " +
                    "recorded as unresolved RFC-owner debt, NOT architecturally closed."
            ),
            Triple(
                "hipRomStamps", "pose.hipRomStamps[hip] = stamp",
                "Sole-producer overwrite (RFC §4.4) in applyValidationStamps."
            ),
            Triple(
                "bilateralSymmetryDelta", "pose.bilateralSymmetryDelta = delta",
                "Sole-producer overwrite (RFC §4.4) in applyValidationStamps."
            ),
            Triple(
                "bilateralOppositeBend", "pose.bilateralOppositeBend = opposite",
                "Sole-producer overwrite (RFC §4.4) in applyValidationStamps."
            )
        ),

        "PoseDefinition.kt" to listOf(
            Triple(
                "maxIkClampAmount", "this.maxIkClampAmount = other.maxIkClampAmount",
                "copyFrom: carrier-internal duplication of already-published values; not a " +
                    "producer."
            ),
            Triple(
                "rootTranslationDelta", "this.rootTranslationDelta = other.rootTranslationDelta",
                "copyFrom: carrier-internal duplication; not a producer."
            ),
            Triple(
                "rootRotationDelta", "this.rootRotationDelta = other.rootRotationDelta",
                "copyFrom: carrier-internal duplication; not a producer."
            ),
            Triple(
                "straightIntentDropped", "this.straightIntentDropped = other.straightIntentDropped",
                "copyFrom: carrier-internal duplication; not a producer."
            ),
            Triple(
                "boneLengthsVerified", "this.boneLengthsVerified = other.boneLengthsVerified",
                "copyFrom: carrier-internal duplication (the Finalizer's outputPose handoff); " +
                    "not a producer."
            )
        )
    )

    @Test
    fun rawStampWritesOccurOnlyAtAuthorizedSites() {
        val sources = productionSources()
        val rawWrites = mutableListOf<Triple<String, Int, String>>() // file, line, trimmed text

        for ((path, lines) in sources) {
            for (i in lines.indices) {
                val line = stripComment(lines[i])
                val m = assignmentRegex.find(line) ?: continue
                if (m.groupValues[1] !in stampFields) continue
                // The IKResult scratch buffer is a solve-local outcome carrier (Limb Solve
                // Result, never inter-subsystem state), not the pose's Validation Stamp, so its
                // field writes are out of scope. Receiver-based: every carrier write sits on
                // `pose.`/`jointsBuffer.`/`buffer.`/`this.` — never `result.`.
                val lhs = line.substringBefore('=')
                if (Regex("""(?<![A-Za-z0-9_])result\.${m.groupValues[1]}\s*$""").containsMatchIn(lhs)) continue
                val throughHelper = line.contains("ValidationStampMerge.") ||
                    lines.getOrNull(i + 1)?.contains("ValidationStampMerge.") == true
                if (!throughHelper) {
                    rawWrites.add(Triple(path.substringAfterLast('/'), i + 1, line.trim()))
                }
            }
        }

        val allowed = authorizedRawWrites.flatMap { (file, entries) ->
            entries.map { Triple(file, it.first, it.second) }
        }.toSet()

        val unexpected = rawWrites.filter { (file, _, text) ->
            Triple(file, fieldName(text), text) !in allowed
        }
        assertTrue(
            "Unauthorized raw Validation Stamp writes detected:\n" +
                unexpected.joinToString("\n") { "  ${it.first}:${it.second}: ${it.third}" },
            unexpected.isEmpty()
        )

        // Bidirectional check: every whitelisted site must exist exactly once.
        for ((file, entries) in authorizedRawWrites) {
            for ((field, snippet, _) in entries) {
                val hits = rawWrites.filter { it.first == file && it.third == snippet }
                assertEquals(
                    "Whitelisted $field site missing or duplicated in $file: \"$snippet\"",
                    1,
                    hits.size
                )
            }
        }
    }

    /**
     * The sole-producer Hip ROM Stamp overwrite is preceded by exactly one map clear per
     * publication — both halves of the overwrite cycle are pinned.
     */
    @Test
    fun hipRomStampOverwriteCycleIsComplete() {
        val clears = productionSources().entries
            .filter { it.key.endsWith("/SkeletonPoseFinalizer.kt") }
            .flatMap { entry ->
                entry.value.withIndex()
                    .filter { it.value.contains("hipRomStamps.clear()") }
                    .map { entry.key.substringAfterLast('/') to (it.index + 1) }
            }
        assertEquals(1, clears.size)
    }

    private fun fieldName(line: String): String =
        stampFields.first { line.contains(it) }

    private fun stripComment(code: String): String {
        val idx = code.indexOf("//")
        return if (idx >= 0) code.substring(0, idx) else code
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
