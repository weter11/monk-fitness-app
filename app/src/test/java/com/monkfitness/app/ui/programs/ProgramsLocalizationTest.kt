package com.monkfitness.app.ui.programs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Localization for the Program UI, checked mechanically instead of by eye (§14).
 *
 * Three rules, each of which an edit can break silently:
 *
 *  * **no hardcoded user-visible copy** — the feature's own Kotlin sources contain no string literal
 *    carrying letters. Every word the user reads comes from `R.string`. (Comments are stripped before the
 *    scan: a sentence about the code is not text the user reads, and this suite's own KDoc quotes the
 *    briefs it implements.)
 *  * **every referenced resource exists in every supported locale** — `values`, `values-ru` and
 *    `values-uk` are the three the app ships, and a key that exists only in English shows English to a
 *    Russian or a Ukrainian user.
 *  * **the translations are translations** — the Russian and Ukrainian values of this feature's own keys
 *    differ from the English ones, so a copied placeholder cannot pass as a translation.
 *  * **every declared key is shown somewhere** — a `programs_` string nothing references is a second
 *    sentence to keep in step for no reader.
 */
class ProgramsLocalizationTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private val resourceRoot = File("src/main/res").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/res")
    }

    /** The feature's own sources: the state holder's package and its five screens. */
    private val featureSources: List<File> = (
        File(mainSourceRoot, "ui/programs")
            .listFiles { file -> file.isFile && file.extension == "kt" }
            ?.sortedBy { file -> file.name }
            ?: emptyList()
        ) + listOf(
        "ui/screens/ProgramsScreen.kt",
        "ui/screens/MyProgramsScreen.kt",
        "ui/screens/ProgramDetailScreen.kt",
        "ui/screens/ProgramEditorScreen.kt",
        "ui/screens/ProgramImportScreen.kt"
    ).map { path -> File(mainSourceRoot, path) }

    /**
     * Where the feature's strings may be shown from: its own sources plus the two places the app hands it
     * over — the graph that navigates to it and the Settings section that offers it. Only [featureSources]
     * are held to the "no literal copy" rule; Settings and `MainActivity` carry older hardcoded labels that
     * are not this stage's to change.
     */
    private val usageSources: List<File> =
        featureSources + listOf(File(mainSourceRoot, "MainActivity.kt"), File(mainSourceRoot, "ui/screens/SettingsScreen.kt"))

    private val locales = mapOf(
        "en" to File(resourceRoot, "values/strings.xml"),
        "ru" to File(resourceRoot, "values-ru/strings.xml"),
        "uk" to File(resourceRoot, "values-uk/strings.xml")
    )

    private fun stringsOf(file: File): Map<String, String> {
        assertTrue("expected the string table at ${file.absolutePath}", file.isFile)
        val pattern = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2].trim() }
    }

    private val stringsByLocale: Map<String, Map<String, String>> =
        locales.mapValues { (_, file) -> stringsOf(file) }

    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun keysIn(files: List<File>): Set<String> {
        val pattern = Regex("""R\.string\.([A-Za-z0-9_]+)""")
        return files
            .flatMap { file -> pattern.findAll(code(file.readText())).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    // ---- the feature's sources --------------------------------------------------------------------

    @Test
    fun theFeatureSourcesExist() {
        assertTrue(
            "expected the feature's Kotlin sources, looked in ${featureSources.map { it.absolutePath }}",
            featureSources.size >= 8
        )
        featureSources.forEach { file -> assertTrue("${file.name} must exist", file.isFile) }
    }

    @Test
    fun theFeatureHasNoHardcodedUserVisibleCopy() {
        val literal = Regex("""["]([^"\\\n]*)["]""")
        val interpolation = Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*""")
        val offenders = featureSources.flatMap { file ->
            literal.findAll(code(file.readText()))
                .map { match ->
                    // A literal's *own* words are what may not be hardcoded: an interpolation reads a value
                    // that came from somewhere else — a resource-resolved label, or the domain — so it is
                    // removed before the check, and `"Hello $name"` is still caught.
                    match.groupValues[1].replace(interpolation, "")
                }
                .filter { text -> text.any { character -> character.isLetter() } }
                .map { text -> "${file.name}: \"$text\"" }
                .toList()
        }

        assertTrue(
            "§14: user-visible copy belongs in a string resource, found literal text: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun everyReferencedStringExistsInEverySupportedLocale() {
        val referenced = keysIn(featureSources)
        assertTrue("expected the sources to reference string resources", referenced.isNotEmpty())

        val missing = locales.flatMap { (locale, _) ->
            val table = stringsByLocale.getValue(locale)
            referenced
                .filter { key -> table[key].isNullOrBlank() }
                .map { key -> "$locale:$key" }
        }

        assertTrue("these referenced strings are missing or blank: ${missing.sorted()}", missing.isEmpty())
    }

    @Test
    fun everyDeclaredStringTheFeatureOwnsIsShownSomewhere() {
        val declared = stringsByLocale.getValue("en").keys.filter { key -> key.startsWith(PREFIX) }
        val used = keysIn(usageSources)

        assertTrue("expected the feature to declare its own strings", declared.size >= 150)
        assertEquals(
            "declared but never shown to the user",
            emptyList<String>(),
            declared.filterNot { key -> key in used }.sorted()
        )
    }

    @Test
    fun theTranslationsAreTranslations() {
        val english = stringsByLocale.getValue("en")
        val declared = english.keys.filter { key -> key.startsWith(PREFIX) }
        assertTrue("expected the feature's own strings", declared.isNotEmpty())

        val untranslated = declared.filter { key ->
            val ru = stringsByLocale.getValue("ru")[key]
            val uk = stringsByLocale.getValue("uk")[key]
            ru == english[key] || uk == english[key]
        }

        assertTrue(
            "Russian and Ukrainian values must not be copies of the English text: ${untranslated.sorted()}",
            untranslated.isEmpty()
        )
        assertTrue(
            "the feature's own strings are localized, so at least one must differ per locale",
            declared.any { key ->
                val ru = stringsByLocale.getValue("ru")[key]
                val uk = stringsByLocale.getValue("uk")[key]
                ru != uk
            }
        )
    }

    @Test
    fun theRequiredVocabularyIsCovered() {
        val keys = stringsByLocale.getValue("en").keys

        val required = listOf(
            "programs_title",                       // the Settings section and the hub's title
            "programs_my_programs",                 // My Programs
            "programs_create",                      // Create Program
            "programs_import",                      // Import Program
            "programs_create_manual",               // §8: Build it myself
            "programs_create_generated",            // §8: Build for me
            "programs_action_select",               // §4's operations
            "programs_action_edit",
            "programs_action_rename",
            "programs_action_archive",
            "programs_action_unarchive",
            "programs_action_delete",
            "programs_action_share",
            "programs_import_start_date",           // §2's date choice
            "programs_import_make_active",          // §5's checkbox
            "programs_import_confirm",
            "programs_detail_next_workout",         // §22's current-state facts
            "programs_detail_revision",
            "programs_detail_lifecycle",
            "programs_detail_progress",
            "programs_refused_standard_edit",       // §4's protections
            "programs_refused_standard_delete",
            "programs_refused_active_session",
            "programs_refused_archive_selection",
            "programs_import_not_a_file",           // §15's four classes
            "programs_import_failed",
            "programs_notice_failed",
            "programs_notice_saved",
            "programs_generation_unavailable"       // the recorded gap, said out loud
        )

        assertEquals("missing feature strings", emptyList<String>(), required.filterNot { it in keys })
    }

    @Test
    fun genericActionsReuseTheAppsExistingStrings() {
        // "OK" and "Cancel" are generic actions the app already ships in three languages; a second
        // resource with the same word would be a second thing to translate and a second thing to keep in
        // step. The screens reference the existing keys, and this stage declares no duplicates.
        val referenced = keysIn(featureSources)
        assertTrue("build OK on the existing string", "ok" in referenced)
        assertTrue("build Cancel on the existing string", "cancel" in referenced)
        assertTrue("build Back on the existing string", "previous" in referenced)
        listOf("programs_ok", "programs_cancel", "programs_back").forEach { key ->
            assertTrue(
                "a duplicate of an existing generic action must not be declared: $key",
                key !in stringsByLocale.getValue("en").keys
            )
        }
    }

    private companion object {
        const val PREFIX = "programs_"
    }
}
