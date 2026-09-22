package com.monkfitness.app.ui.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The other localizable resource types (§14): `<plurals>` and `<string-array>`.
 *
 * A localization suite that *claims* to cover these types but can only ever run against content the app
 * does not have is not covering anything. So the rule has two halves, both of which execute:
 *
 *  * the reader is proven against a document this test writes — a synthetic file, not invented app
 *    content, and no app resource is added for the sake of a test;
 *  * the app's own tables are then asserted to ship none of them, which is the state the brief asks for
 *    ("do not add plural/array resources without need") and is worth failing on if it changes silently:
 *    a count-dependent phrase in this app is written as `%1$d-day …` against a value the app already
 *    localizes, and a new plural would have to exist in all seven locales and carry matching placeholders.
 */
class ResourceTypesTest {

    private val resourceTypes = Regex("""<(plurals|string-array)\s+name="([^"]+)"""")

    @Test
    fun theSuiteReadsPluralAndArrayResources() {
        val file = File(Files.createTempDirectory("resource-types").toFile(), "strings.xml")
        file.writeText(
            """
            <resources>
                <plurals name="demo_plural">
                    <item quantity="one">%1${'$'}d thing</item>
                    <item quantity="other">%1${'$'}d things</item>
                </plurals>
                <string-array name="demo_array">
                    <item>a</item>
                </string-array>
            </resources>
            """.trimIndent()
        )

        assertEquals(
            "the reader must see both types, or the rule that covers them cannot fail",
            mapOf("demo_plural" to "plurals", "demo_array" to "string-array"),
            ResourceTables.collectionsIn(file)
        )
        assertTrue(
            "and it must see nothing in a table that has neither",
            ResourceTables.collectionsIn(File(Files.createTempDirectory("resource-types-empty").toFile(), "strings.xml").apply {
                writeText("<resources><string name=\"only\">text</string></resources>")
            }).isEmpty()
        )
    }

    @Test
    fun theAppShipsNoPluralOrArrayResources() {
        val perLocale = ResourceTables.allLocales().associateWith { tag -> ResourceTables.collectionsOf(tag) }
        val declared = perLocale.getValue(ResourceTables.DEFAULT_LOCALE)

        assertTrue(
            "a new plural/array resource must exist in every locale and carry matching placeholders; " +
                "§14 asks for none without need, so this suite fails when one appears: $declared",
            declared.isEmpty()
        )
        ResourceTables.allLocales().drop(1).forEach { tag ->
            assertEquals(
                "$tag declares a different set of plural/array resources than the default table",
                declared.keys,
                perLocale.getValue(tag).keys
            )
        }
    }

    @Test
    fun theReaderIsTheOneTheAppTablesAreReadWith() {
        // The two halves have to be the same reader: if they drift, the empty-table assertion above could
        // pass while the reader that would catch a real plural looks somewhere else.
        val onDisk = ResourceTables.allLocales().flatMap { tag -> ResourceTables.filesIn(tag) }
        val detected = onDisk.flatMap { file -> ResourceTables.collectionsIn(file).entries.toList() }
        val viaLocale = ResourceTables.allLocales().flatMap { tag -> ResourceTables.collectionsOf(tag).entries }

        assertEquals(detected.size, viaLocale.size)
        onDisk.forEach { file ->
            assertEquals(
                "${file.name}: collection names must not depend on which reader is used",
                resourceTypes.findAll(file.readText()).map { it.groupValues[2] }.toList().sorted(),
                ResourceTables.collectionsIn(file).keys.sorted()
            )
        }
    }
}
