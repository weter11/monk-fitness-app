package com.monkfitness.app.ui.language

import com.monkfitness.app.language.AppLanguage
import java.io.File

/**
 * The localization suite's one reader for the app's resource tables.
 *
 * Every localization rule in this package compares the same three things — the keys the default
 * (`values/`) table declares, the per-locale tables, and the source files that use them — so the reading
 * lives here once and the tests state rules instead of re-implementing parsing.
 *
 * It reads the files on disk rather than resolving resources through a `Context`, because this repository
 * has no Robolectric and no instrumentation source set: a source scan is the only way these invariants can
 * run in the JVM suite.
 */
internal object ResourceTables {

    /** The unqualified resource directory: the app's ultimate fallback, and the table English lives in. */
    const val DEFAULT_LOCALE = "en"

    private val resRoot: File = File("src/main/res").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/res")
    }

    private val mainSourceRoot: File = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    /** The directory holding [tag]'s resources: `values/` for the default locale, `values-<tag>` otherwise. */
    fun localeDir(tag: String): File =
        if (tag == DEFAULT_LOCALE) File(resRoot, "values") else File(resRoot, "values-$tag")

    /**
     * The language-qualified resource directories that exist, as tags.
     *
     * Only two-letter qualifiers are read: `values-night` and friends are configuration variants, not
     * languages, and a language the app ships without saying so is exactly what this list must catch.
     */
    fun localeDirsOnDisk(): List<String> =
        resRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.matches(Regex("values-[a-z]{2}")) }
            .map { it.name.removePrefix("values-") }
            .sorted()

    /** Every XML file a locale directory declares, sorted by name. */
    fun filesIn(tag: String): List<File> =
        localeDir(tag).listFiles { file -> file.isFile && file.extension == "xml" }
            ?.sortedBy { it.name }
            ?: emptyList()

    private val stringElement = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The `<string>` entries of every file in a locale directory, in file order.
     *
     * The raw (still XML-escaped) text is returned: comparing translations must not care how an apostrophe
     * is escaped, but it must care that `%1$d` survived.
     */
    fun stringsOf(tag: String): LinkedHashMap<String, String> {
        val table = LinkedHashMap<String, String>()
        filesIn(tag).forEach { file ->
            stringElement.findAll(file.readText()).forEach { match ->
                table[match.groupValues[1]] = match.groupValues[3].trim()
            }
        }
        return table
    }

    /**
     * The keys a locale directory declares as **not translatable**.
     *
     * `translatable="false"` is the app saying "this is a proper noun, not copy" — the language names the
     * picker shows are exactly that. The exemption is read from this attribute and not from a list in a
     * test, so an untranslated string has to say out loud that it is one; a key cannot quietly join the
     * untranslated set by being added to some test's list.
     */
    fun untranslatableKeys(tag: String): Set<String> =
        filesIn(tag).flatMap { file ->
            stringElement.findAll(file.readText())
                .filter { match -> match.groupValues[2].contains("translatable=\"false\"") }
                .map { match -> match.groupValues[1] }
                .toList()
        }.toSet()

    /** Every `<plurals>`/`<string-array>` name a locale directory declares (§14's other localizable types). */
    fun collectionsOf(tag: String): Map<String, String> =
        filesIn(tag).flatMap { file ->
            Regex("""<(plurals|string-array)\s+name="([^"]+)"""")
                .findAll(file.readText())
                .map { match -> match.groupValues[2] to match.groupValues[1] }
                .toList()
        }.toMap()

    /** The text of `res/xml/locale_config.xml`, or `null` when the app declares no locale config. */
    fun localeConfig(): String? =
        File(resRoot, "xml/locale_config.xml").takeIf { it.isFile }?.readText()

    /** The app manifest, or `null` when it cannot be found. */
    fun manifest(): String? = File("src/main/AndroidManifest.xml").let { direct ->
        when {
            direct.isFile -> direct
            else -> File("app/src/main/AndroidManifest.xml").takeIf { it.isFile }
        }
    }?.readText()

    /** The Kotlin sources under `main/`, as paths relative to the package root. */
    fun mainSources(): List<File> =
        mainSourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sorted().toList()

    /** A source file by its path relative to the package root, e.g. `ui/screens/SettingsScreen.kt`. */
    fun mainSource(path: String): File = File(mainSourceRoot, path)

    /** Strips comments and KDoc: a sentence about the code is not text the user reads. */
    fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    /** The `R.string.<key>` keys a set of sources references. */
    fun referencesIn(sources: List<File>): Set<String> {
        val pattern = Regex("""R\.string\.([A-Za-z0-9_]+)""")
        return sources
            .flatMap { file -> pattern.findAll(code(file.readText())).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    /** The `R.string` *value* an id has, resolved through the generated `R` class. */
    fun resourceNameOf(id: Int): String? =
        Class.forName("com.monkfitness.app.R\$string").declaredFields
            .firstOrNull { field ->
                runCatching { field.isAccessible = true; field.getInt(null) == id }.getOrDefault(false)
            }
            ?.name

    /** All locales this suite checks: the default table plus every language the policy supports. */
    fun allLocales(): List<String> = listOf(DEFAULT_LOCALE) + AppLanguage.supportedTags.filter { it != DEFAULT_LOCALE }

    /** Placeholders an Android string carries, as a sorted multiset (`%1$d`, `%s`, `%2$s`, …). */
    fun placeholders(value: String): List<String> =
        Regex("""%(?:\d+\$)?[a-zA-Z]""").findAll(value).map { it.value }.sorted().toList()

    /**
     * Whether a value carries words at all, once interpolations and format specifications are removed.
     *
     * The format shape is the wide one (`%d`, `%02d`, `%.1f`, `%1$02d`, `%%`): a literal that is only a time
     * pattern such as `"%02d:%02d"` is a format, not copy, and must not be reported as untranslated text.
     */
    fun hasWords(value: String): Boolean = value
        .replace(Regex("""\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*"""), "")
        .replace(Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]"""), "")
        .any { character -> character.isLetter() }
}
