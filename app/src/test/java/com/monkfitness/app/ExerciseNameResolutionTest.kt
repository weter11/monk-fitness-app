package com.monkfitness.app

import com.monkfitness.app.domain.usecase.WorkoutGenerator
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every exercise the catalogue ships must have a name a reader can see in every locale the app ships,
 * and an id the catalogue no longer ships must resolve to the neutral fallback rather than to itself.
 *
 * The names are read from the shipped `strings.xml` files — the same files the APK packages — because
 * a resource id is only half a name and a unit test has no `Resources` object to resolve the other
 * half.
 */
class ExerciseNameResolutionTest {

    private companion object {

        /** The seven locales the app ships: the default plus six translations. */
        val LOCALES = listOf("values", "values-bg", "values-es", "values-it", "values-pt", "values-ru", "values-uk")

        /** The resource that stands in for an id the catalogue no longer ships. */
        const val FALLBACK = "progress_unknown_exercise"
    }

    @Test
    fun everyShippedExerciseIdHasANonEmptyNameInEveryLocale() {
        val exercises = WorkoutGenerator().getExerciseLibrary()
        assertTrue("the catalogue is not empty", exercises.isNotEmpty())

        val idToField = R.string::class.java.declaredFields
            .associate { field -> field.getInt(null) to field.name }
        assertTrue("the name resources are generated", idToField.size > 20)

        val strings = LOCALES.associateWith { locale -> stringResources(locale) }

        exercises.forEach { exercise ->
            assertTrue("${exercise.id} names a name resource", exercise.nameRes != 0)
            val field = idToField[exercise.nameRes]
            assertNotNull("${exercise.id} resolves to a shipped string resource", field)
            strings.forEach { (locale, values) ->
                val name = values[field]
                assertNotNull("${exercise.id} ($field) is missing from $locale", name)
                assertTrue("${exercise.id} ($field) is blank in $locale", !name.isNullOrBlank())
                assertFalse(
                    "${exercise.id} shows its raw id in $locale instead of a name",
                    name == exercise.id
                )
            }
        }

        assertEquals("seven locales", 7, LOCALES.size)
    }

    @Test
    fun theFallbackForARemovedExerciseIsShippedInEveryLocale() {
        LOCALES.forEach { locale ->
            val value = stringResources(locale)[FALLBACK]
            assertNotNull("$locale has no $FALLBACK", value)
            assertTrue("$FALLBACK is blank in $locale", !value.isNullOrBlank())
        }
    }

    private fun stringResources(locale: String): Map<String, String> {
        val file = listOf(
            File("src/main/res/$locale/strings.xml"),
            File("../src/main/res/$locale/strings.xml"),
            File("app/src/main/res/$locale/strings.xml")
        ).firstOrNull { candidate -> candidate.isFile }
            ?: error("missing $locale/strings.xml from ${File(".").absolutePath}")

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index)
            val key = element.attributes.getNamedItem("name").nodeValue
            key to element.textContent
        }
    }
}
