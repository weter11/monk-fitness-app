package com.monkfitness.app.ui.programs

import com.monkfitness.app.language.AppLanguage
import com.monkfitness.app.ui.language.ResourceTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The load and kilogram-volume copy, in every locale the app ships.
 *
 * The keys are listed here rather than discovered, because the point is that **these** sentences exist in
 * all seven tables and are actually translated: a key present only in English shows English to a Russian
 * user, and a copied English sentence is not a translation.
 */
class SetLoadLocalizationTest {

    private val resourceRoot = File("src/main/res").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/res")
    }

    /** Every sentence this change added. */
    private val keys = listOf(
        "programs_session_actual_load",
        "programs_session_load_invalid",
        "programs_session_reps_invalid",
        "programs_session_seconds_invalid",
        "progress_performance_load_row",
        "progress_performance_load_row_with_volume",
        "progress_performance_load_hold_row",
        "progress_volume_kg",
        "progress_volume_reps_and_kg",
        "progress_volume_seconds_and_kg",
        "progress_volume_reps_seconds_and_kg"
    )

    private fun stringsOf(file: File): Map<String, String> {
        assertTrue("expected the string table at ${file.absolutePath}", file.isFile)
        val pattern = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2].trim() }
    }

    private fun tableOf(tag: String): Map<String, String> {
        val dir = if (tag == ResourceTables.DEFAULT_LOCALE) "values" else "values-$tag"
        return stringsOf(File(resourceRoot, "$dir/strings.xml"))
    }

    @Test
    fun everyLoadStringExistsInEveryShippedLocale() {
        val missing = mutableListOf<String>()
        for (tag in AppLanguage.supportedTags) {
            val table = tableOf(tag)
            keys.filterNot { key -> table.containsKey(key) }.forEach { key -> missing += "$tag:$key" }
        }
        assertEquals("the app ships seven locales and each states every load sentence", emptyList<String>(), missing)
        assertEquals(7, AppLanguage.supportedTags.size)
    }

    @Test
    fun theUnitLabelIsTranslatedRatherThanLeftInEnglish() {
        val english = tableOf(ResourceTables.DEFAULT_LOCALE)
        for (tag in listOf("ru", "uk")) {
            val table = tableOf(tag)
            assertEquals(
                "the kilogram label is localized in $tag rather than reusing `kg`",
                "кг",
                table.getValue("progress_volume_kg").substringAfter("%1\$s ")
            )
            for (key in keys) {
                assertNotEquals(
                    "`$key` is a translation in $tag, not the English sentence copied over",
                    english.getValue(key),
                    table.getValue(key)
                )
            }
        }
    }

    @Test
    fun everyPlaceholderSurvivesTranslation() {
        val placeholders = Regex("""%\d+\$[sd]""")
        for (tag in AppLanguage.supportedTags) {
            val table = tableOf(tag)
            for (key in keys) {
                assertEquals(
                    "`$key` in $tag keeps the placeholders its English sentence states",
                    placeholders.findAll(tableOf(ResourceTables.DEFAULT_LOCALE).getValue(key))
                        .map { it.value }.toList(),
                    placeholders.findAll(table.getValue(key)).map { it.value }.toList()
                )
            }
        }
    }
}
