package com.monkfitness.app.ui.language

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User-visible copy belongs in a string resource — every locale of it, in every screen.
 *
 * A literal in Kotlin cannot be translated, so six of the app's seven languages cannot read it. That is
 * hard to see in review (a sentence in a `Text(...)` looks exactly like one in a resource) and invisible in
 * a screenshot of the author's language, so it is checked mechanically.
 *
 * Only copy handed **directly to a user-visible sink** is a finding: `Text(...)`, `text = …`, a dialog's
 * `title`/`text`, `label = …`, `contentDescription = …`, a toast, a snackbar's message, a notification's
 * title/text. Comments and KDoc, log lines, exception messages, engine diagnostics, identifiers, route
 * strings, SQL, intent extras and test scaffolding stay out of scope: a blanket ban on literals would fail
 * on all of them and would be turned off instead of kept. A literal that is only an interpolation
 * (`"$name"`), only punctuation or only a format specification (`"%02d:%02d"`) is not copy either — the
 * words come from elsewhere or from nothing. The engine's own diagnostics (`message = …` in the
 * animation/validation subsystems, shown only in the hidden engineering tool) are developer readings of the
 * engine, not the app's copy.
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
        // Each phrase was read by a user in a language the app could not translate and is a resource with
        // seven locales now. A phrase may still be *mentioned* in a comment; what may not come back is a
        // literal the screen hands to the user.
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
        // Category names used to sit in the domain model as a display field beside the key, so Settings
        // showed English to every language. They are resource ids now; the model must not grow that back.
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
