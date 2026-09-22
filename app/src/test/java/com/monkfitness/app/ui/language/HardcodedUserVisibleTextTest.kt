package com.monkfitness.app.ui.language

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User-visible copy belongs in a string resource — every locale of it, in every screen (§11).
 *
 * A literal in Kotlin cannot be translated, so it cannot be read by six of the app's seven languages. That
 * is hard to see in review (a sentence in a `Text(...)` of a screen nobody opened this week looks exactly
 * like a sentence in a resource) and impossible to see in a screenshot of the language the author speaks,
 * so it is checked mechanically.
 *
 * ### What counts, and what deliberately does not
 *
 * Only copy handed **directly to a user-visible sink** is a finding: `Text(...)`, `text = …`, a dialog's
 * `title`/`text`, `label = …`, `contentDescription = …`, a toast, a snackbar's message, and a notification's
 * title/text — the places where a literal *is* what the user reads. Everything else that is a string literal
 * by nature stays out of scope, exactly as the brief requires: comments and KDoc (a sentence about the code),
 * log lines, exception messages, engine diagnostics, identifiers, route strings, database SQL, intent extras
 * and the technical scaffolding of tests. A blanket ban on literals would fail on all of them and would
 * therefore be turned off instead of kept.
 *
 * A literal that is only an interpolation (`"$name"`), only punctuation, or only a format specification
 * (`"%02d:%02d"`) is not copy either — the words in it come from elsewhere or from nothing.
 *
 * The engine's own diagnostics (`message = …` inside the animation/validation subsystems, shown only in the
 * hidden engineering-validation tool) are excluded for the same reason: they are developer readings of the
 * engine, not the app's copy, and the stage that grew the app's languages does not translate the engine.
 */
class HardcodedUserVisibleTextTest {

    private val sources = ResourceTables.mainSources()

    private val userVisibleSink = Regex(
        "(?:Text\\(\\s*|text\\s*=\\s*|title\\s*=\\s*|contentDescription\\s*=\\s*|" +
            "label\\s*=\\s*\\{\\s*Text\\(\\s*|Toast\\.makeText\\([^)]*?|setContentTitle\\(\\s*|" +
            "setContentText\\(\\s*|showSnackbar\\(\\s*message\\s*=\\s*)" +
            "[\"]([^\"\\n]*)[\"]"
    )

    private val stringLiteral = Regex("""["]([^"\n]*)["]""")

    @Test
    fun theSourcesAreWhereThisSuiteLooksForThem() {
        assertTrue(
            "expected the app's Kotlin sources, found ${sources.size} files",
            sources.size >= 50
        )
        assertTrue(
            "expected the screens this rule exists for",
            sources.any { it.path.endsWith("ui/screens/SettingsScreen.kt") }
        )
    }

    @Test
    fun noUserVisibleSinkReceivesAStringLiteral() {
        val findings = sources.flatMap { file ->
            val code = ResourceTables.code(file.readText())
            userVisibleSink.findAll(code)
                .map { match -> match.groupValues[1] }
                .filter { text -> ResourceTables.hasWords(text) }
                .map { text -> "${file.name}: \"$text\"" }
                .toList()
        }

        assertTrue(
            "user-visible copy belongs in a string resource, found literal text: $findings",
            findings.isEmpty()
        )
    }

    @Test
    fun theCopyThisStageMovedOutOfKotlinIsReallyOutOfKotlin() {
        // The literals the localization stage replaced. They are the regression this suite exists for: each
        // one was read by a user in a language the app could not translate, and each one is now a resource
        // with seven locales. A phrase may still be *mentioned* in a comment — what may not come back is a
        // literal that the screen hands to the user.
        val movedToResources = listOf(
            "Preview Next Cycle",
            "Accept Cycle",
            "Select Preview Duration:",
            "Cycle Summary",
            "Daily Menu",
            "Required Products Preview",
            "Comparison with Current Cycle",
            "Enable All",
            "Disable All",
            "At least one category must remain enabled",
            "Please enable at least one exercise category",
            "When enabled, the Exercise Library and Search",
            "Show only exercises matching selected categories",
            "Training Styles",
            "Special Programs"
        )

        val offenders = sources.flatMap { file ->
            val literals = stringLiteral.findAll(ResourceTables.code(file.readText()))
                .map { match -> match.groupValues[1] }
                .toList()
            movedToResources
                .filter { phrase -> literals.any { literal -> literal.contains(phrase) } }
                .map { phrase -> "${file.name}: \"$phrase\"" }
        }

        assertTrue(
            "this copy must come from R.string, not from Kotlin: ${offenders.sorted()}",
            offenders.isEmpty()
        )
    }

    @Test
    fun theCategoryNamesTheUserReadsAreResources() {
        // Category names lived in the domain model as a second field beside the key, so the Settings screen
        // showed English to every language. They are resource ids now, and the model must not grow the old
        // shape back.
        val model = ResourceTables.code(ResourceTables.mainSource("data/model/Exercise.kt").readText())

        assertTrue(
            "ExerciseCategoryFilter carries a label resource, not a display name",
            !model.contains("displayName")
        )
        assertTrue(
            "a category group is titled by a resource",
            model.contains("@StringRes val titleRes: Int")
        )
    }
}
