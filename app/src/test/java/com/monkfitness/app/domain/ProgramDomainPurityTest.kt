package com.monkfitness.app.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The boundary of the new Program System domain, pinned mechanically instead of by convention.
 *
 * The foundation this change adds is *pure*: it is JVM Kotlin over `java.time`, with no Room, no
 * Android, no `androidx`, no data layer, no UI and no ViewModel — which is what makes it testable
 * without a device and what the whole later roadmap (entities, mappers, DAOs, repositories) was
 * designed around. A comment saying so erodes on the first convenient import, so the rules are
 * asserted against the sources themselves:
 *
 *  * only plain `kotlin.`/`java.`/domain imports may appear;
 *  * no Room annotation and no `androidx` type may appear;
 *  * the models are values: no `var` and no mutable collection type anywhere;
 *  * no floating point and no progression coefficient may appear — there is no load score to be
 *    invented, and no coefficient inside the adaptive foundation to be tuned.
 *
 * The last rule is the one that needs a word of explanation: it is scoped to the files this change
 * adds, because the *previous* adaptive stage (which lives in the same `domain/adaptive` package and
 * is deliberately untouched here) does compute exposure ratios as `Double`. Those ratios belong to
 * that engine and are not part of this foundation; the new files must stay free of them.
 */
class ProgramDomainPurityTest {

    private val domainDir = File("src/main/java/com/monkfitness/app/domain").let { dir ->
        // Unit-test JVM cwd is app/, but fall back to the repo root layout for safety.
        if (dir.isDirectory) dir else File("app/$dir")
    }

    /**
     * The packages this change creates wholesale.
     *
     * `adaptive/engine` was added by §30 step 11: the target adaptive engine is a pure domain component
     * whose rules are exactly the ones this scan pins — no data layer, no platform, no mutable state, no
     * floating point and no random source. Adding it here is what makes those rules apply to it without
     * a second scan.
     */
    private val newPackages = listOf(
        "common", "program", "prescription", "workout", "adaptive/decision", "adaptive/engine"
    )

    /** The files this change adds to the pre-existing `domain/adaptive` package. */
    private val newAdaptiveFiles = listOf(
        "AdaptiveScope.kt",
        "LoadProfile.kt",
        "AdaptiveEvidence.kt",
        "ExposureObservation.kt",
        "AdaptiveInputSnapshot.kt"
    )

    private val forbiddenImports = listOf(
        "import android", "import androidx", "import kotlinx",
        "import com.monkfitness.app.data.", "import com.monkfitness.app.ui.",
        "import com.monkfitness.app.viewmodel.", "import com.monkfitness.app.animation.",
        "import com.monkfitness.app.poses.", "import com.monkfitness.app.R"
    )

    private val forbiddenRoomAnnotations = listOf(
        "@Entity", "@PrimaryKey", "@ColumnInfo", "@Dao", "@Database", "@ForeignKey",
        "@Relation", "@Embedded", "@TypeConverter", "@Ignore"
    )

    // ------------------------------------------------------------------ the scan really finds the files

    @Test
    fun theScanSeesTheWholeFoundation() {
        val sources = foundationSources()

        assertTrue(
            "expected the foundation at ${domainDir.absolutePath}, found nothing",
            sources.size >= 25
        )
        newAdaptiveFiles.forEach { name ->
            assertTrue(
                "$name must exist in domain/adaptive",
                sources.any { it.name == name && it.parentFile.name == "adaptive" }
            )
        }
        newPackages.forEach { pkg ->
            assertTrue(
                "domain/$pkg must hold at least one source",
                sources.any { it.path.replace('\\', '/').contains("/domain/$pkg/") }
            )
        }
    }

    // ------------------------------------------------------------------ purity

    @Test
    fun noFoundationSourceImportsAndroidRoomDataUiOrViewmodel() {
        val offenders = scan(foundationSources()) { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) null
            else forbiddenImports.firstOrNull { trimmed.startsWith(it) }?.let { trimmed }
        }

        assertTrue(
            "the foundation must stay free of Android, Room, the data layer and the UI, found: " +
                "$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theWholeAdaptiveDomainStaysFreeOfTheDataLayerAndThePlatform() {
        // The superset: every source in domain/adaptive, including the pre-existing stage this change
        // deliberately does not touch, so the package the new files live in is fenced as a whole.
        val sources = foundationSources() + (File(domainDir, "adaptive")
            .listFiles { file -> file.isFile && file.extension == "kt" }
            ?.toList()
            ?: emptyList())

        val offenders = scan(sources) { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) null
            else forbiddenImports.firstOrNull { trimmed.startsWith(it) }?.let { trimmed }
        }

        assertTrue("domain/adaptive must not reach for the data layer or the platform: $offenders",
            offenders.isEmpty())
    }

    @Test
    fun noRoomAnnotationAndNoPersistedModelAppearsInTheFoundation() {
        val offenders = scan(foundationSources()) { line ->
            forbiddenRoomAnnotations.firstOrNull { line.contains(it) }?.let { line.trim() }
        }

        assertTrue(
            "the domain has no storage annotations: Room entities are a later, separate layer, " +
                "found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ immutability

    @Test
    fun theFoundationHoldsValuesAndDeclaresNoMutableState() {
        val offenders = scan(foundationSources()) { line ->
            val trimmed = line.trim()
            when {
                Regex("""\bvar\b""").containsMatchIn(trimmed) -> trimmed
                Regex("""\bMutable(List|Set|Map)\b""").containsMatchIn(trimmed) -> trimmed
                Regex("""\bmutable(ListOf|SetOf|MapOf)\b""").containsMatchIn(trimmed) -> trimmed
                else -> null
            }
        }

        assertTrue(
            "every foundation model is an immutable value; found mutable state: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ no invented arithmetic

    @Test
    fun theFoundationDeclaresNoFloatingPointAndNoProgressionCoefficient() {
        val offenders = scan(foundationSources()) { line ->
            when {
                Regex("""\b(Double|Float)\b""").containsMatchIn(line) -> line.trim()
                Regex(
                    """\b(coefficient|multiplier|loadScore|totalLoad|progressFactor|scalingFactor)\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(line) -> line.trim()
                Regex("""\bRandom\b""").containsMatchIn(line) -> line.trim()
                else -> null
            }
        }

        assertTrue(
            "no universal load score and no numeric progression coefficient may appear in the " +
                "foundation, found: $offenders",
            offenders.isEmpty()
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun foundationSources(): List<File> = newPackages.flatMap { pkg ->
        File(domainDir, pkg).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    } + newAdaptiveFiles.map { File(domainDir, "adaptive/$it") }.filter { it.isFile }

    private fun scan(sources: List<File>, rule: (String) -> String?): List<String> =
        sources.flatMap { source ->
            codeLines(source).mapNotNull { line -> rule(line)?.let { "${source.name}: $it" } }
        }

    /**
     * The executable lines of [source], with block comments and line comments removed.
     *
     * The rules above are rules about *code*. The KDoc in this foundation explains, in prose, that
     * there is no coefficient and no load score — and a scan that reads prose would fail on the
     * explanation of the very rule it is checking.
     */
    private fun codeLines(source: File): List<String> =
        source.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .map { it.substringBefore("//") }
}
