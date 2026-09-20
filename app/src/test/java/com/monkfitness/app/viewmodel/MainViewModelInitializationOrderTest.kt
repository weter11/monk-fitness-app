package com.monkfitness.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The initialisation order of `MainViewModel`, asserted mechanically.**
 *
 * ### Why this test exists
 *
 * A startup crash escaped the whole JVM suite: the `init` block that starts the nutrition sync sat *above*
 * the nutrition properties it reads, so the first `syncNutritionCycles()` — which `viewModelScope` runs
 * **synchronously** during construction, because it dispatches on `Dispatchers.Main.immediate` and the view
 * model is constructed on the main thread — read an uninitialised `val`. Kotlin compiles that happily: the
 * read happens inside a lambda, so the compiler's flow analysis never sees it, and the field is `null` at
 * runtime.
 *
 * The suite could not catch it because this project has no Robolectric harness: `MainViewModel` is an
 * `AndroidViewModel`, nothing in `src/test` can construct one, and every test in the tree works around it.
 * So the guard has to be a **source-level availability analysis** rather than an instantiation — and to be
 * worth anything it must model the real rule, not the shape of one incident:
 *
 * ```text
 * for every `init` block
 *   for every member it calls (transitively, through the class's own functions)
 *     every *property* that call path reads must be declared ABOVE the init block
 * ```
 *
 * That is exactly the rule Kotlin's top-to-bottom initialisation imposes, so a future `init` placed beside
 * the wiring at the top of the class fails here instead of on a device. It is a weaker guard than
 * constructing the object would be — it cannot see a read the analysis misses — and that weakness is
 * recorded rather than hidden: `docs/PROGRAM_LEGACY_REMOVAL.md` names the missing harness as the reason.
 */
class MainViewModelInitializationOrderTest {

    private val source: String = File("src/main/java/com/monkfitness/app/viewmodel/MainViewModel.kt")
        .let { if (it.isFile) it else File("app/src/main/java/com/monkfitness/app/viewmodel/MainViewModel.kt") }
        .readText()

    /** The whole file without comments or string literals, so prose can never be read as code. */
    private val code: String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex(""""(?:[^"\\]|\\.)*""""), "\"\"")
        .replace(Regex("""^\s*\*.*$""", RegexOption.MULTILINE), "")

    private val lines: List<String> = code.split("\n")

    /** `name -> the 1-based line its declaration starts on`, for every class-level property. */
    private val properties: Map<String, Int> = lines.indices
        .mapNotNull { index ->
            val match = Regex("""^\s+(?:@\w+\s+)*(?:private |internal |protected )?(?:val|var) (\w+)""")
                .find(lines[index])
            match?.let { it.groupValues[1] to index + 1 }
        }
        .toMap()

    /** `name -> the line range of its body`, for every function declared in the class. */
    private val functions: Map<String, IntRange> = lines.indices
        .mapNotNull { index ->
            val match = Regex("""^\s+(?:private |internal |protected )?(?:suspend )?fun (\w+)\s*\(""")
                .find(lines[index])
            match?.let { it.groupValues[1] to index }
        }
        .associate { (name, start) -> name to bodyRange(start) }

    private val initBlocks: List<Int> = lines.indices
        .filter { Regex("""^\s+init\s*\{""").containsMatchIn(lines[it]) }
        .toList()

    /**
     * The range of a block opened on [startLine], by brace depth — so a function's body is measured from
     * its own `{`, not from the next blank line.
     */
    private fun bodyRange(startLine: Int): IntRange {
        var depth = 0
        var opened = false
        for (index in startLine until lines.size) {
            for (character in lines[index]) {
                if (character == '{') {
                    depth++
                    opened = true
                } else if (character == '}') {
                    depth--
                }
            }
            if (opened && depth == 0) return startLine..index
        }
        return startLine..startLine
    }

    /**
     * Every identifier a set of lines mentions, minus the names it *declares* locally.
     *
     * Removing locals is what keeps the analysis honest: `val today = currentDate.value` inside a function
     * must not read as a reference to a class property called `today`.
     */
    private fun identifiersIn(range: IntRange): Set<String> {
        val text = range.joinToString("\n") { lines[it] }
        val locals = Regex("""\b(?:val|var)\s+(\w+)""").findAll(text).map { it.groupValues[1] }.toSet()
        return Regex("""[A-Za-z_]\w*""").findAll(text).map { it.value }.toSet() - locals
    }

    /**
     * [names] closed over the class's own functions: a name that is a function contributes its body's
     * identifiers, transitively. This closure is what makes the analysis follow a call path rather than
     * only the `init` block's own text.
     */
    private fun closedOverCalls(names: Set<String>): Set<String> {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(names)
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!seen.add(name)) continue
            val body = functions[name] ?: continue
            identifiersIn(body).forEach { referenced ->
                if (referenced in functions && referenced !in seen) pending.addLast(referenced)
            }
        }
        return seen
    }

    // ---- the rule ---------------------------------------------------------------------------------

    @Test
    fun everyInitBlockIsDeclaredBelowEveryPropertyItsCallPathReads() {
        assertTrue("expected the class to declare properties", properties.size > 20)
        assertTrue("expected the class to declare at least one init block", initBlocks.isNotEmpty())

        val offenders = initBlocks.flatMap { line ->
            val read = closedOverCalls(identifiersIn(bodyRange(line)))
            read.filter { name -> name in properties }
                .filter { name -> properties.getValue(name) > line }
                .map { name -> "init at line $line reads `$name`, declared at ${properties.getValue(name)}" }
        }

        assertEquals(
            "Kotlin runs property initializers and init blocks top-to-bottom, and viewModelScope's " +
                "`Dispatchers.Main.immediate` runs an init block's first launch SYNCHRONOUSLY on the " +
                "constructing thread — so a property read from an init block's call path must already " +
                "have been initialised. Reading one earlier is a null at runtime that the compiler cannot " +
                "see, because the read sits inside a lambda",
            emptyList<String>(),
            offenders
        )
    }

    // ---- the incident this test was written for ---------------------------------------------------

    @Test
    fun theNutritionStartupTickRunsAfterTheNutritionStateItReads() {
        // The regression itself, named so a future reader can see *which* ordering is load-bearing. The
        // list is the transitive read of `syncNutritionCycles` → `createOrQueueMealCycle`, read off that
        // call path and not guessed: a property added to it belongs here too, and the general rule above
        // is what catches one that is.
        val startupTick = initBlocks.singleOrNull { line ->
            val body = bodyRange(line).joinToString("\n") { lines[it] }
            body.contains("syncNutritionCycles()")
        }
        assertTrue(
            "expected exactly one init block to start the nutrition sync",
            startupTick != null
        )
        // Bound once, non-null: `assertTrue` carries no contract the compiler can use.
        val tickLine: Int = startupTick ?: error("no init block starts the nutrition sync")

        val readByTheTick = listOf(
            "nutritionCycleLength",
            "nutritionAvailableProducts",
            "mealCycles",
            "activeMealCycle",
            "pendingMealCycle",
            "nutritionWeight",
            "nutritionHeight",
            "nutritionExcludedFoods",
            "currentDate"
        )

        for (name in readByTheTick) {
            assertTrue("`$name` is a property of the class", name in properties)
            assertTrue(
                "the nutrition tick reads `$name`, so its declaration (line ${properties.getValue(name)}) " +
                    "must be above the init block that starts it (line $tickLine)",
                properties.getValue(name) < tickLine
            )
        }

        // And the wiring fields the tick's path reaches through the repository, which are initialised in
        // the *other* init block — so that one must come first.
        val wiringInit = initBlocks.single { it != tickLine }
        assertTrue(
            "`nutritionRepository` is assigned by the wiring init block, so that block must run before " +
                "the nutrition tick",
            wiringInit < tickLine
        )
        assertTrue("`trackStartDate` is declared above the wiring block", properties.getValue("trackStartDate") < wiringInit)
        assertTrue("`settingsManager` is declared above the wiring block", properties.getValue("settingsManager") < wiringInit)
    }
}
