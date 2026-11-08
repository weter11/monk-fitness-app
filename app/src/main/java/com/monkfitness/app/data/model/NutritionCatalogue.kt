package com.monkfitness.app.data.model

import androidx.annotation.StringRes
import com.monkfitness.app.R
import kotlin.math.roundToInt

/** A food's nutrients per 100 g as the cited export measured them. */
data class NutritionNutrientsPer100g(
    val kcal: Double,
    val proteinGrams: Double,
    val fatGrams: Double,
    val carbohydrateGrams: Double
)

/** One row of a public composition export, citable by the export's own item id. */
data class NutritionSourceRef(
    val source: String,
    val itemId: String,
    val retrievedOn: String
)

/** The second database's value for the same food, kept to show the two do not drift apart. */
data class NutritionSecondSource(
    val source: String,
    val itemId: String,
    val kcalPer100g: Double,
    val basis: String,
    val note: String? = null
)

/** How much a local name can be relied on. */
enum class NutritionNameTrust(val key: String) {
    /** Reviewed by a human against the market's own usage. */
    VERIFIED("verified"),

    /** Taken from an open vocabulary, citable to one concept and one licence. */
    SOURCED("sourced"),

    /** The app's own translation: nobody but the author has looked at it. */
    UNVERIFIED("unverified")
}

/** Where a name came from: a vocabulary concept, citable by id and licence. */
sealed interface NutritionNameSource {

    /** The app wrote this name itself; there is no external row behind it. */
    data object Agent : NutritionNameSource

    data class Vocabulary(
        val database: String,
        val itemId: String,
        val term: String,
        val licence: String
    ) : NutritionNameSource
}

/**
 * One ingredient's name in one language.
 *
 * Names describe the *concept* the ingredient stands for, so they are never read from the
 * composition row the numbers came from: that row may hold a different food.
 *
 * [inflectedFrom] is the vocabulary label a plural was made from, kept so the plural stays checkable
 * against the row it is cited to. [note] says why a word is the word it is where the source is silent.
 */
data class NutritionIngredientName(
    val name: String,
    val trust: NutritionNameTrust,
    val source: NutritionNameSource,
    val inflectedFrom: String? = null,
    val note: String? = null
)

/** The asset's ingredient fields map onto the app's enums by name, so a typo cannot pass silently. */
private val ingredientNameRes: Map<String, Int> = mapOf(
    "nutrition_ingredient_chicken_breast" to R.string.nutrition_ingredient_chicken_breast,
    "nutrition_ingredient_eggs" to R.string.nutrition_ingredient_eggs,
    "nutrition_ingredient_rice" to R.string.nutrition_ingredient_rice,
    "nutrition_ingredient_oats" to R.string.nutrition_ingredient_oats,
    "nutrition_ingredient_potatoes" to R.string.nutrition_ingredient_potatoes,
    "nutrition_ingredient_buckwheat" to R.string.nutrition_ingredient_buckwheat,
    "nutrition_ingredient_cottage_cheese" to R.string.nutrition_ingredient_cottage_cheese,
    "nutrition_ingredient_yogurt" to R.string.nutrition_ingredient_yogurt,
    "nutrition_ingredient_banana" to R.string.nutrition_ingredient_banana,
    "nutrition_ingredient_apple" to R.string.nutrition_ingredient_apple,
    "nutrition_ingredient_nuts" to R.string.nutrition_ingredient_nuts,
    "nutrition_ingredient_tuna" to R.string.nutrition_ingredient_tuna,
    "nutrition_ingredient_carrots" to R.string.nutrition_ingredient_carrots,
    "nutrition_ingredient_tomatoes" to R.string.nutrition_ingredient_tomatoes,
    "nutrition_ingredient_courgette" to R.string.nutrition_ingredient_courgette,
    "nutrition_ingredient_cauliflower" to R.string.nutrition_ingredient_cauliflower,
    "nutrition_ingredient_lentils" to R.string.nutrition_ingredient_lentils,
    "nutrition_ingredient_chickpeas" to R.string.nutrition_ingredient_chickpeas,
    "nutrition_ingredient_salmon" to R.string.nutrition_ingredient_salmon,
    "nutrition_ingredient_cod" to R.string.nutrition_ingredient_cod,
    "nutrition_ingredient_pasta" to R.string.nutrition_ingredient_pasta,
    "nutrition_ingredient_whole_grain_bread" to R.string.nutrition_ingredient_whole_grain_bread,
    "nutrition_ingredient_soy_milk" to R.string.nutrition_ingredient_soy_milk,
    "nutrition_ingredient_tofu" to R.string.nutrition_ingredient_tofu,
    "nutrition_ingredient_ham" to R.string.nutrition_ingredient_ham,
    "nutrition_ingredient_milk" to R.string.nutrition_ingredient_milk
)

internal class ParsedNutritionCatalogue(
    val schemaVersion: Int,
    val ingredients: List<NutritionIngredient>,
    internal val templates: List<NutritionMealTemplate>
) {
    val ingredientsByKey: Map<String, NutritionIngredient> = ingredients.associateBy { it.key }
    internal val templatesById: Map<String, NutritionMealTemplate> = templates.associateBy { it.id }
}

/**
 * The catalogue is the app's only food data: it is parsed once from the asset and then read
 * everywhere through [NutritionCatalogue]. Android has no way to reach an asset from a top-level
 * initialiser, so the process loads it at start-up.
 */
object NutritionCatalogue {

    @Volatile
    private var snapshot: ParsedNutritionCatalogue? = null

    val ingredients: List<NutritionIngredient>
        get() = parsed().ingredients

    val ingredientsByKey: Map<String, NutritionIngredient>
        get() = parsed().ingredientsByKey

    internal val templates: List<NutritionMealTemplate>
        get() = parsed().templates

    internal val templatesById: Map<String, NutritionMealTemplate>
        get() = parsed().templatesById

    /** How many foods the catalogue knows, for the statistics screen the planner preps for. */
    val ingredientCount: Int
        get() = parsed().ingredients.size

    /** How many dishes a cycle can draw from, counted the same way. */
    val dishCount: Int
        get() = parsed().templates.size

    /** Loads [json] once; a later call is ignored, so the first parse is the catalogue. */
    fun load(json: String) {
        if (snapshot == null) snapshot = parseCatalogue(json)
    }

    private fun parsed(): ParsedNutritionCatalogue =
        snapshot ?: error("the nutrition catalogue asset has not been loaded")
}

internal fun parseCatalogue(json: String): ParsedNutritionCatalogue {
    val root = Json.parse(json) as? Map<*, *> ?: error("catalogue: the asset must be a JSON object")
    val version = root.number("schemaVersion").toInt()
    require(version == SUPPORTED_SCHEMA_VERSION) {
        "catalogue: schemaVersion $version is not supported (expected $SUPPORTED_SCHEMA_VERSION)"
    }

    val ingredients = root.objects("ingredients").map { entry ->
        val unit = entry.enumValue<NutritionQuantityUnit>("unit")
        NutritionIngredient(
            key = entry.string("id"),
            nameRes = ingredientNameRes[entry.string("name")]
                ?: error("catalogue: unknown ingredient name key ${entry.string("name")}"),
            group = entry.enumValue("group"),
            unit = unit,
            preparationStyle = entry.enumValue("preparation"),
            nutrientsPer100g = entry.obj("nutrientsPer100g").let { nutrients ->
                NutritionNutrientsPer100g(
                    kcal = nutrients.number("kcal"),
                    proteinGrams = nutrients.number("proteinG"),
                    fatGrams = nutrients.number("fatG"),
                    carbohydrateGrams = nutrients.number("carbG")
                )
            },
            basis = entry.string("basis"),
            source = entry.sourceRef("source"),
            secondSource = entry.obj("secondSource").let { second ->
                NutritionSecondSource(
                    source = second.string("source"),
                    itemId = second.string("itemId"),
                    kcalPer100g = second.number("kcal"),
                    basis = second.string("basis"),
                    note = second["note"] as? String
                )
            },
            gramsPerPiece = if (unit == NutritionQuantityUnit.PIECES) entry.number("pieceGrams").toInt() else null,
            pieceSource = entry.sourceRefOrNull("pieceSource"),
            names = entry.objOrNull("names")?.let(::namesOf).orEmpty(),
            pork = entry["pork"] as? Boolean ?: false,
            lactose = entry["lactose"] as? Boolean ?: false
        )
    }
    val byKey = ingredients.associateBy { it.key }
    require(byKey.size == ingredients.size) { "catalogue: duplicate ingredient id" }

    val templates = root.objects("templates").map { entry ->
        val amounts = entry.objects("ingredients").map { amount ->
            val key = amount.string("ingredient")
            val ingredient = byKey[key] ?: error("catalogue: template ${entry.string("id")} names unknown ingredient $key")
            NutritionIngredientAmount(ingredient, amount.number("amount").toInt())
        }
        val main = (entry["main"] as? List<*>)?.map { it as? String ?: error("catalogue: a main ingredient must be a string") }
            ?: error("catalogue: template ${entry.string("id")} has no main ingredients")
        require(main.size in 2..3) { "catalogue: template ${entry.string("id")} is named after ${main.size} ingredients" }
        main.forEach { key ->
            require(amounts.any { it.ingredient.key == key }) {
                "catalogue: template ${entry.string("id")} is named after $key, which it does not contain"
            }
        }
        NutritionMealTemplate(
            id = entry.string("id"),
            type = entry.enumValue("slot"),
            mealType = entry.enumValue("profile"),
            ingredients = amounts,
            mainIngredientKeys = main,
            calories = amounts.sumOf { it.ingredient.kcalFor(it.amount) }.roundToInt(),
            proteinGrams = amounts.sumOf { it.ingredient.proteinFor(it.amount) }.roundToInt(),
            optional = entry["optional"] as? Boolean ?: false
        )
    }
    require(templates.map { it.id }.toSet().size == templates.size) { "catalogue: duplicate template id" }

    return ParsedNutritionCatalogue(version, ingredients, templates)
}

internal const val SUPPORTED_SCHEMA_VERSION = 1

private fun NutritionIngredient.gramsFor(amount: Int): Double = when (unit) {
    NutritionQuantityUnit.PIECES -> (amount * (gramsPerPiece ?: error("no piece weight for $key"))).toDouble()
    NutritionQuantityUnit.GRAMS, NutritionQuantityUnit.MILLILITERS -> amount.toDouble()
}

private fun NutritionIngredient.kcalFor(amount: Int): Double =
    gramsFor(amount) * (nutrientsPer100g ?: error("no nutrients for $key")).kcal / 100.0

private fun NutritionIngredient.proteinFor(amount: Int): Double =
    gramsFor(amount) * (nutrientsPer100g ?: error("no nutrients for $key")).proteinGrams / 100.0

private fun Map<*, *>.string(name: String): String =
    this[name] as? String ?: error("catalogue: missing or non-string \"$name\"")

private fun Map<*, *>.number(name: String): Double =
    (this[name] as? Double) ?: error("catalogue: missing or non-numeric \"$name\"")

private fun Map<*, *>.objects(name: String): List<Map<*, *>> =
    (this[name] as? List<*>)?.map { it as? Map<*, *> ?: error("catalogue: \"$name\" must hold objects") }
        ?: error("catalogue: missing array \"$name\"")

private fun Map<*, *>.obj(name: String): Map<*, *> =
    this[name] as? Map<*, *> ?: error("catalogue: missing object \"$name\"")

private fun Map<*, *>.objOrNull(name: String): Map<*, *>? = this[name] as? Map<*, *>

private fun namesOf(entry: Map<*, *>): Map<String, NutritionIngredientName> =
    entry.entries.associate { (language, value) ->
        val tag = language as? String ?: error("catalogue: a name language must be a string")
        tag to nameOf(tag, value)
    }

/**
 * One entry of an ingredient's `names` map.
 *
 * `unverified` unless a vocabulary is cited, and a cited vocabulary entry has to carry the id and
 * licence that make it checkable — a name is only "sourced" when a reader can go and look.
 */
private fun nameOf(language: String, value: Any?): NutritionIngredientName {
    val entry = value as? Map<*, *> ?: error("catalogue: name \"$language\" must be an object")
    val trust = NutritionNameTrust.entries.firstOrNull { it.key == entry.string("trust") }
        ?: error("catalogue: name \"$language\" has trust ${entry["trust"]}")
    val source = when (val raw = entry["source"]) {
        is String -> {
            require(raw == "agent") { "catalogue: name \"$language\" cites \"$raw\" as its source" }
            NutritionNameSource.Agent
        }
        is Map<*, *> -> NutritionNameSource.Vocabulary(
            database = raw.string("database"),
            itemId = raw.string("id"),
            term = raw.string("term"),
            licence = raw.string("licence")
        )
        else -> error("catalogue: name \"$language\" has no source")
    }
    require((trust == NutritionNameTrust.UNVERIFIED) == (source is NutritionNameSource.Agent)) {
        "catalogue: name \"$language\" is $trust but cites ${if (source is NutritionNameSource.Agent) "no row" else "a vocabulary"}"
    }
    return NutritionIngredientName(
        name = entry.string("name"),
        trust = trust,
        source = source,
        inflectedFrom = entry["inflectedFrom"] as? String,
        note = entry["note"] as? String
    )
}

private fun Map<*, *>.sourceRef(name: String): NutritionSourceRef =
    sourceRefOrNull(name) ?: error("catalogue: missing source \"$name\"")

private fun Map<*, *>.sourceRefOrNull(name: String): NutritionSourceRef? {
    val source = this[name] as? Map<*, *> ?: return null
    return NutritionSourceRef(
        source = source.string("source"),
        itemId = source.string("itemId"),
        retrievedOn = source.string("retrievedOn")
    )
}

private inline fun <reified T : Enum<T>> Map<*, *>.enumValue(name: String): T =
    enumValues<T>().firstOrNull { it.name == string(name) }
        ?: error("catalogue: \"$name\" has no value ${this[name]}")

/**
 * A JSON reader for the one document the app ships. It exists instead of a serialization library
 * because the catalogue is the only JSON the app parses; unknown object members are skipped so a
 * later schema can add fields without an older reader rejecting the file.
 */
private class Json(private val text: String) {

    private var index = 0

    fun parse(): Any? {
        val value = value()
        skipWhitespace()
        require(index == text.length) { "catalogue: trailing content at $index" }
        return value
    }

    private fun value(): Any? {
        skipWhitespace()
        require(index < text.length) { "catalogue: unexpected end of input" }
        return when (val c = text[index]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) number() else error("catalogue: unexpected '$c' at $index")
        }
    }

    private fun obj(): Map<String, Any?> {
        index++
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return result
        }
        while (true) {
            skipWhitespace()
            val key = string()
            skipWhitespace()
            expect(':')
            result[key] = value()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return result
                }
                else -> error("catalogue: expected ',' or '}' at $index")
            }
        }
    }

    private fun array(): List<Any?> {
        index++
        val result = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return result
        }
        while (true) {
            result += value()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return result
                }
                else -> error("catalogue: expected ',' or ']' at $index")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            require(index < text.length) { "catalogue: unterminated string" }
            when (val c = text[index++]) {
                '"' -> return builder.toString()
                '\\' -> builder.append(escape())
                else -> builder.append(c)
            }
        }
    }

    private fun escape(): Char {
        require(index < text.length) { "catalogue: unterminated escape" }
        return when (val c = text[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = text.substring(index, index + 4)
                index += 4
                hex.toInt(16).toChar()
            }
            else -> error("catalogue: unknown escape \\$c")
        }
    }

    private fun number(): Double {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && text[index].let { it.isDigit() || it == '.' || it == 'e' || it == 'E' || it == '+' || it == '-' }) index++
        return text.substring(start, index).toDoubleOrNull() ?: error("catalogue: bad number at $start")
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, index)) { "catalogue: expected $word at $index" }
        index += word.length
        return value
    }

    private fun expect(c: Char) {
        require(peek() == c) { "catalogue: expected '$c' at $index" }
        index++
    }

    private fun peek(): Char? = text.getOrNull(index)

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    companion object {
        fun parse(source: String): Any? = Json(source).parse()
    }
}
