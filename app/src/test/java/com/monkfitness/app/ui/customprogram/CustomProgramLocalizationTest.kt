package com.monkfitness.app.ui.customprogram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Localization coverage for the Custom Program editor, checked mechanically instead of by eye.
 *
 * Three rules, each of which a future edit can break silently:
 *
 *  * **no hardcoded user-visible copy** — the feature's own Kotlin sources contain no string literal
 *    that carries letters. Every word the user reads comes from `R.string`.
 *  * **every referenced resource exists in every supported locale** — `values`, `values-ru` and
 *    `values-uk` are the three the app ships, and a key that only exists in English shows English to a
 *    Russian or Ukrainian user.
 *  * **the translations are translations** — the Russian and Ukrainian values of this feature's own
 *    keys differ from the English ones, so a copied placeholder cannot pass as a translation.
 */
class CustomProgramLocalizationTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private val resourceRoot = File("src/main/res").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/res")
    }

    /** The feature's own sources: the state/presentation/editor package and its screen. */
    private val editorSources: List<File> = listOf(
        File(mainSourceRoot, "ui/customprogram"),
        File(mainSourceRoot, "ui/screens/CustomProgramScreen.kt")
    ).flatMap { file ->
        if (file.isDirectory) {
            file.listFiles { candidate -> candidate.isFile && candidate.extension == "kt" }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            listOf(file)
        }
    }.filter { it.isFile }

    /**
     * Where the feature's strings may be shown from: its own sources plus the settings screen that
     * offers the entry point. Only [editorSources] are held to the "no literal copy" rule — the
     * settings screen has older hardcoded labels that are not this task's to change.
     */
    private val usageSources: List<File> = editorSources + File(mainSourceRoot, "ui/screens/SettingsScreen.kt")

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

    private val stringsByLocale: Map<String, Map<String, String>> = locales.mapValues { (_, file) -> stringsOf(file) }

    private fun referencedKeys(): Set<String> {
        val pattern = Regex("""R\.string\.([A-Za-z0-9_]+)""")
        return editorSources
            .flatMap { source -> pattern.findAll(source.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    private fun keysUsedAnywhere(): Set<String> {
        val pattern = Regex("""R\.string\.([A-Za-z0-9_]+)""")
        return usageSources
            .flatMap { source -> pattern.findAll(source.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    // ---- the feature's sources -----------------------------------------------------------------

    @Test
    fun theEditorSourcesExist() {
        assertTrue(
            "expected the feature's Kotlin sources, looked in ${editorSources.map { it.absolutePath }}",
            editorSources.size >= 4
        )
    }

    @Test
    fun theFeatureHasNoHardcodedUserVisibleCopy() {
        val literal = Regex("""["]([^"\\\n]*)["]""")
        val offenders = editorSources.flatMap { source ->
            literal.findAll(source.readText())
                .map { match -> match.groupValues[1] }
                .filter { text -> text.any { it.isLetter() } }
                .map { text -> "${source.name}: \"$text\"" }
                .toList()
        }

        assertTrue(
            "user-visible copy belongs in a string resource, found literal text: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun everyReferencedStringExistsInEverySupportedLocale() {
        val referenced = referencedKeys()
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
    fun everyCustomProgramStringTheAppDeclaresIsUsed() {
        val declared = stringsByLocale.getValue("en").keys.filter { it.startsWith(CUSTOM_PROGRAM_PREFIX) }
        val used = keysUsedAnywhere()

        assertTrue("expected the feature to declare its own strings", declared.size >= 15)
        assertEquals(
            "declared but never shown to the user",
            emptyList<String>(),
            declared.filterNot { it in used }.sorted()
        )
    }

    @Test
    fun theTranslationsAreTranslations() {
        val english = stringsByLocale.getValue("en")
        val declared = english.keys.filter { it.startsWith(CUSTOM_PROGRAM_PREFIX) }
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
            "custom_program",                       // the screen title / settings entry
            "custom_program_desc",
            "custom_program_search_label",
            "custom_program_search_clear",
            "custom_program_apply",
            "custom_program_reset",                 // Reset to Default
            "custom_program_reset_confirm_title",
            "custom_program_reset_confirm_text",
            "custom_program_error_header",
            "custom_program_error_missing_domain",
            "custom_program_error_unavailable_equipment",
            "custom_program_requires_equipment",
            "custom_program_warning_header",
            "custom_program_warning_region_not_covered",
            "custom_program_warning_region_concentration",
            "custom_program_family_all_enabled",
            "custom_program_family_none_enabled",
            "custom_program_family_partial",
            "custom_program_family_toggle_description",
            "custom_program_family_count",
            "custom_program_domain_strength",
            "custom_program_domain_flexibility",
            "custom_program_default_selection",
            "custom_program_custom_selection"
        )

        assertEquals("missing feature strings", emptyList<String>(), required.filterNot { it in keys })
    }

    @Test
    fun cancelReusesTheAppsExistingString() {
        // "Cancel" is a generic action the app already ships in three languages; a second resource
        // with the same word would be a second thing to translate and a second thing to keep in step.
        assertTrue("build the Cancel action on the existing string", "cancel" in referencedKeys())
        assertFalse("do not declare a second Cancel", "custom_program_cancel" in stringsByLocale.getValue("en").keys)
        locales.forEach { (locale, _) ->
            assertNotEquals("", stringsByLocale.getValue(locale).getValue("cancel"))
        }
    }

    private companion object {
        const val CUSTOM_PROGRAM_PREFIX = "custom_program"
    }
}
