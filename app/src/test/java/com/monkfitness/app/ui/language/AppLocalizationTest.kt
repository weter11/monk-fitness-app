package com.monkfitness.app.ui.language

import com.monkfitness.app.language.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.monkfitness.app.ui.language.ResourceTables.allLocales
import com.monkfitness.app.ui.language.ResourceTables.localeDir
import com.monkfitness.app.ui.language.ResourceTables.localeDirsOnDisk
import com.monkfitness.app.ui.language.ResourceTables.localeConfig
import com.monkfitness.app.ui.language.ResourceTables.manifest
import com.monkfitness.app.ui.language.ResourceTables.placeholders
import com.monkfitness.app.ui.language.ResourceTables.referencesIn
import com.monkfitness.app.ui.language.ResourceTables.resourceNameOf
import com.monkfitness.app.ui.language.ResourceTables.stringsOf
import com.monkfitness.app.ui.language.ResourceTables.untranslatableKeys

/**
 * The app's localization, checked mechanically instead of by eye.
 *
 * The stage this suite belongs to replaced a three-button language selector with Android's own
 * application-locale mechanism and grew the shipped set from three languages to seven. Two things can go
 * wrong in that kind of change and neither is visible in a code review:
 *
 *  * **the app says it supports a language it does not.** A `values-xx` directory, a `localeConfig` entry,
 *    a picker row and a supported-language list are four physical statements of one policy; this suite fails
 *    when any of them disagrees with [AppLanguage], which is the single statement the app itself reads.
 *  * **a translation is missing, empty, or a placeholder was dropped.** A key that exists only in English
 *    is not a missing key at runtime — Android silently shows English to a Bulgarian user — so it has to be
 *    a failing test instead.
 *
 * The rules are strict where a defect is invisible (`%1$s` becoming `%s`, a blank value) and deliberately
 * tolerant where languages legitimately agree (a brand name, `1 day`/`1 dia`, a format-only string).
 */
class AppLocalizationTest {

    private val defaultTable = stringsOf(ResourceTables.DEFAULT_LOCALE)

    // ---- the policy -------------------------------------------------------------------------------

    @Test
    fun theSupportedLanguagesAreTheSevenTheAppShips() {
        assertEquals(
            "the app's supported languages, in picker order",
            listOf("en", "ru", "uk", "bg", "pt", "es", "it"),
            AppLanguage.supportedTags
        )
        assertEquals(
            "every entry is a language the app ships, plus the system-default mode",
            AppLanguage.supported.size + 1,
            AppLanguage.entries.size
        )
    }

    @Test
    fun theSystemDefaultIsAModeAndNotALanguage() {
        val systemDefault = AppLanguage.SYSTEM_DEFAULT

        assertTrue("the system-default entry carries no locale", systemDefault.isSystemDefault)
        assertNull("a mode must not present itself as a language tag", systemDefault.tag)
        assertEquals(
            "the mode is offered first, and the languages follow in their own order",
            AppLanguage.SYSTEM_DEFAULT,
            AppLanguage.entries.first()
        )
        assertEquals(
            "the mode's label is a language setting, not a language name",
            "language_system_default",
            stringKeyOf(systemDefault.labelRes)
        )
        assertTrue(
            "the mode's label is declared in the default table so every locale can translate it",
            stringKeyOf(systemDefault.labelRes) in defaultTable
        )
        assertTrue(
            "the language names are proper nouns in their own file; the mode's label is copy in the " +
                "translated table",
            stringKeyOf(systemDefault.labelRes) !in untranslatableKeys(ResourceTables.DEFAULT_LOCALE)
        )
    }

    @Test
    fun everyLanguageOtherThanTheSystemDefaultHasItsOwnResourceDirectory() {
        AppLanguage.supportedTags.forEach { tag ->
            assertTrue(
                "the app claims to ship $tag, so ${localeDir(tag)} must exist",
                localeDir(tag).isDirectory
            )
            assertTrue(
                "${localeDir(tag)} must declare strings",
                stringsOf(tag).isNotEmpty()
            )
        }
        assertEquals(
            "a language resource directory the app does not claim is a language it half-ships",
            AppLanguage.supportedTags.filter { it != ResourceTables.DEFAULT_LOCALE }.sorted(),
            localeDirsOnDisk()
        )
    }

    @Test
    fun theLocaleConfigDeclaresExactlyTheSupportedLanguages() {
        val config = localeConfig()
        assertNotNull(
            "Android 13's per-app language screen only lists languages the app declares, so " +
                "res/xml/locale_config.xml must exist",
            config
        )

        val declared = Regex("""<locale\s+android:name="([^"]+)"""")
            .findAll(config!!)
            .map { match -> match.groupValues[1] }
            .toList()

        assertEquals(
            "locale_config.xml and AppLanguage.supported are two statements of one policy",
            listOf("en", "ru", "uk", "bg", "pt", "es", "it"),
            declared
        )
        assertTrue(
            "the system-default mode is the absence of an application locale, not a locale the " +
                "system could apply, so it must not be declared",
            declared.none { it == AppLanguage.SYSTEM_DEFAULT.tag }
        )
    }

    @Test
    fun theManifestPointsAtTheLocaleConfig() {
        val manifest = manifest()
        assertNotNull("the app manifest must be readable", manifest)
        assertTrue(
            "Android 13 shows an app's language screen only when the manifest declares " +
                "android:localeConfig",
            manifest!!.contains("android:localeConfig=\"@xml/locale_config\"")
        )
    }

    @Test
    fun thePickerOffersExactlyTheLanguagesThePolicyDeclares() {
        val picker = ResourceTables.mainSource("ui/language/LanguagePickerDialog.kt")
        val source = ResourceTables.code(picker.readText())

        assertTrue(
            "the picker must render AppLanguage itself — a hand-written list of languages is a second " +
                "statement of the policy that no other rule in this suite can keep in step",
            source.contains("AppLanguage.entries")
        )
        assertTrue(
            "the picker's rows are the enum's labels",
            source.contains("stringResource(language.labelRes)")
        )
        listOf("en", "ru", "uk", "bg", "pt", "es", "it").forEach { tag ->
            assertTrue(
                "the picker must not name a language by its tag: $tag",
                !source.contains(Regex("""["']$tag["']"""))
            )
        }
    }

    @Test
    fun theSettingsLanguageSectionUsesTheManagerOnly() {
        val settings = ResourceTables.code(ResourceTable_SettingsScreen.readText())
        AppLanguage.supportedTags.forEach { tag ->
            assertTrue(
                "Settings must not select a language by tag — that is what the manager is for: $tag",
                !settings.contains(Regex("""selectAppLanguage\("$tag"\)"""))
            )
        }
        assertTrue(
            "Settings shows the current selection from the manager",
            settings.contains("viewModel.currentAppLanguage()")
        )
        assertTrue(
            "Settings applies a language through the manager",
            settings.contains("viewModel.selectAppLanguage(")
        )
        assertTrue(
            "the system's own per-app language screen is offered next to the picker, not instead of it",
            ResourceTable_SettingsScreen.readText().contains("AppLanguageSettings.intentFor(")
        )
    }

    // ---- completeness -----------------------------------------------------------------------------

    @Test
    fun everyUserVisibleStringExistsInEveryLanguage() {
        val missing = allLocales().drop(1).flatMap { tag ->
            val table = stringsOf(tag)
            defaultTable.keys
                .filterNot { key -> key in untranslatableKeys(ResourceTables.DEFAULT_LOCALE) }
                .filter { key -> table[key].isNullOrBlank() }
                .map { key -> "$tag:$key" }
        }

        assertTrue(
            "these strings exist only in English, so Android would show English to everyone: ${missing.sorted()}",
            missing.isEmpty()
        )
    }

    @Test
    fun noUserVisibleStringIsBlank() {
        val blank = allLocales().flatMap { tag ->
            stringsOf(tag).filterValues { value -> value.isBlank() }.keys.map { key -> "$tag:$key" }
        }
        assertTrue("a blank string is a missing one that passed a review: ${blank.sorted()}", blank.isEmpty())
    }

    @Test
    fun everyLocaleHasTheSameKeysAsTheDefaultOne() {
        val extra = allLocales().drop(1).flatMap { tag ->
            stringsOf(tag).keys
                .filterNot { key -> key in defaultTable.keys || key in untranslatableKeys(tag) }
                .map { key -> "$tag:$key" }
        }
        assertTrue("a translation for a key that no longer exists: ${extra.sorted()}", extra.isEmpty())
    }

    @Test
    fun placeholdersMatchEnglishInEveryLanguage() {
        val violations = allLocales().drop(1).flatMap { tag ->
            val table = stringsOf(tag)
            defaultTable.entries
                .filter { (key, english) -> table[key] != null && placeholders(english) != placeholders(table.getValue(key)) }
                .map { (key, english) ->
                    "$tag:$key — en ${placeholders(english)} vs $tag ${placeholders(table.getValue(key))}"
                }
        }

        assertTrue(
            "a translated string must carry exactly English's placeholders: a dropped %1\$d crashes, " +
                "and a %1\$s turned into %2\$s prints the wrong argument. Violations: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun everyPlaceholderShapeEnglishUsesIsCoveredByThisSuite() {
        // The rule above is only as good as the shapes the app actually uses, so the shapes are stated:
        // %d, %s, %1$d, %1$s, several arguments, and the escaped percent that a literal "%" needs.
        val english = defaultTable.values.joinToString(" ")
        listOf("%d", "%1\$d", "%1\$s", "%1\$02d").forEach { shape ->
            assertTrue("expected the app to use $shape somewhere", english.contains(shape))
        }
        assertTrue("expected a multi-argument string", Regex("""%1\$[a-z].*%2\$[a-z]""").containsMatchIn(english))
        assertTrue("expected an escaped percent", english.contains("%%"))
    }

    @Test
    fun everyLanguageThatShipsTranslationsAlsoTranslatesTheSystemLanguageLabel() {
        val key = stringKeyOf(AppLanguage.SYSTEM_DEFAULT.labelRes)
        assertEquals("the system-default label is declared in the default table", "language_system_default", key)

        val untranslated = allLocales().drop(1).filter { tag ->
            stringsOf(tag)[key].isNullOrBlank() || stringsOf(tag)[key] == defaultTable[key]
        }
        assertTrue(
            "\"System language\" is copy the user reads in Settings, so each language must translate it: $untranslated",
            untranslated.isEmpty()
        )
    }

    @Test
    fun theLanguageNamesAreWrittenInTheirOwnLanguage() {
        val names = stringsOf(ResourceTables.DEFAULT_LOCALE)
        val declared = untranslatableKeys(ResourceTables.DEFAULT_LOCALE)

        val expected = mapOf(
            "language_english" to "English",
            "language_russian" to "Русский",
            "language_ukrainian" to "Українська",
            "language_bulgarian" to "Български",
            "language_portuguese" to "Português",
            "language_spanish" to "Español",
            "language_italian" to "Italiano"
        )

        expected.forEach { (key, native) ->
            assertEquals("a speaker must find their language written in it", native, names[key])
            assertTrue("$key is a proper noun, not copy: translatable=\"false\"", key in declared)
        }
        assertEquals(
            "exactly the supported languages are named, and each name is the label of its entry",
            expected.keys.sorted(),
            AppLanguage.supported.mapNotNull { language -> resourceNameOf(language.labelRes) }.sorted()
        )
        assertEquals(
            "a language name lives in the untranslatable file and nowhere else, so it is never " +
                "half-translated",
            expected.keys.toSet(),
            declared
        )
    }

    // ---- quality ----------------------------------------------------------------------------------

    @Test
    fun noLanguageIsASilentCopyOfEnglish() {
        // Deliberately *not* "every value must differ": a brand ("Monk Fitness"), a unit ("%1$d kcal"),
        // an internationally used exercise name ("Bird Dog") and a format-only string legitimately read the
        // same everywhere. What may not pass is a *block* of English pasted into a locale file, which is what
        // a rushed translation looks like, so the suite bounds both how much and how consecutively a locale
        // may agree with English.
        val allowedFraction = 0.05
        val allowedRun = 8

        allLocales().drop(1).forEach { tag ->
            val table = stringsOf(tag)
            val worded = defaultTable.filterValues { english -> ResourceTables.hasWords(english) }
            val identical = worded.filter { (key, english) -> table[key] == english }

            assertTrue(
                "$tag: ${identical.size} of ${worded.size} strings are still English " +
                    "(allowed: ${(worded.size * allowedFraction).toInt()}). Copied blocks: ${identical.keys.take(20)}",
                identical.size <= (worded.size * allowedFraction).toInt()
            )

            var run = 0
            var longest = 0
            worded.keys.forEach { key ->
                run = if (table[key] == defaultTable[key]) run + 1 else 0
                longest = maxOf(longest, run)
            }
            assertTrue(
                "$tag: $longest consecutive strings are identical to English — a translated file does not " +
                    "agree with English for a run that long",
                longest <= allowedRun
            )
        }
    }

    @Test
    fun theDefaultResourceLocaleIsEnglish() {
        // The system-fallback scenario the brief calls out — a device whose language the app does not ship —
        // ends in `values/`, so the locale of that table is a fact worth asserting rather than assuming.
        listOf("settings" to "Settings", "language" to "Language", "reps" to "Reps").forEach { (key, english) ->
            assertEquals(
                "values/ is the app's ultimate fallback and must be the English table",
                english,
                defaultTable[key]
            )
            allLocales().drop(1).forEach { tag ->
                assertTrue("$tag must not keep the English value of $key", stringsOf(tag)[key] != english)
            }
        }
    }

    @Test
    fun theStringsThisStageAddedAreActuallyShown() {
        // The stage added the language setting and moved Settings' and the nutrition preview's literal copy
        // into resources. A resource nothing reads is a second sentence to keep in step for no reader.
        val referenced = referencesIn(ResourceTables.mainSources())
        val required = listOf(
            "language_system_default",
            "language_system_settings",
            "settings_category_required_title",
            "settings_category_required_text",
            "settings_enable_all",
            "settings_disable_all",
            "settings_filter_library_title",
            "settings_filter_library_hint",
            "settings_exercises_title",
            "category_group_training_styles",
            "category_group_special_programs",
            "category_filter_rehabilitation",
            "nutrition_preview_next_cycle",
            "nutrition_preview_accept",
            "nutrition_preview_summary_title",
            "nutrition_preview_calories_comparison",
            "nutrition_preview_product_missing"
        )
        val unused = required.filterNot { key -> key in referenced && key in defaultTable }
        assertTrue("declared but never shown, or never declared: $unused", unused.isEmpty())

        AppLanguage.supported.forEach { language ->
            val name = resourceNameOf(language.labelRes)
            assertNotNull("every language entry needs a label resource, ${language.name} has none", name)
            assertTrue("$name must be declared", name!! in defaultTable)
        }
    }

    private fun stringKeyOf(id: Int): String =
        resourceNameOf(id) ?: throw AssertionError("no R.string entry has the id $id")

    private val ResourceTable_SettingsScreen = ResourceTables.mainSource("ui/screens/SettingsScreen.kt")
}
