package com.monkfitness.app.domain.program.transfer

/**
 * The JSON the transfer format is written in — the value model and the strict reader.
 *
 * A transferred document is untrusted input, so the reader is an allowlist that never repairs anything:
 * trailing content after the document's own value, a field name written twice, a raw control character
 * inside text, a lone surrogate escape, a fraction or an exponent are all refused, because none of them
 * has one reading. A parse produces a value or a syntax error with its place, never a partial value —
 * which is what makes "malformed JSON must not become an empty Program" structural rather than
 * remembered.
 *
 * The depth guard is the one defensive mechanism here and it is not speculative: a recursive reader with
 * no bound turns a small user-supplied file into a `StackOverflowError`. Every other size is already
 * bounded by the format's byte limit ([ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES]), so no second
 * count guard appears.
 *
 * No serialization dependency is used: the format's vocabulary is records, arrays, text, whole numbers,
 * booleans and `null`, and `org.json` is an Android platform stub that would put the platform inside a
 * layer that must stay free of it.
 */
sealed interface JsonValue {

    data class JsonString(val value: String) : JsonValue

    /** A whole number: a fraction or an exponent is not a value this format carries. */
    data class JsonNumber(val value: Long) : JsonValue

    data class JsonBoolean(val value: Boolean) : JsonValue

    /**
     * The `null` literal. The format never *writes* it — an absent optional fact is an absent field,
     * because "says nothing" and "says null" are two representations of one fact and the format admits
     * one. It is read anyway so a document carrying it is refused as a wrong type where it appears
     * rather than reported as a syntax error somewhere else.
     */
    data object JsonNull : JsonValue

    /** An array, in document order. */
    data class JsonArray(val elements: List<JsonValue>) : JsonValue

    /** A record: its fields in document order, each name at most once. */
    data class JsonObject(val fields: List<JsonField>) : JsonValue {

        /** The value of [name], or `null` when this record does not carry the field. */
        fun named(name: String): JsonValue? = fields.firstOrNull { field -> field.name == name }?.value

        /** The names this record declares, in document order. */
        val names: List<String>
            get() = fields.map { it.name }
    }
}

data class JsonField(val name: String, val value: JsonValue)

/**
 * A document that is not JSON, with the place the reader stopped. A syntax failure of the text, never a
 * statement about a Program: no value is produced, so nothing downstream sees a partially read plan.
 */
class JsonSyntaxError(
    val at: Int,
    val line: Int,
    val column: Int,
    val reason: String
) : IllegalArgumentException("$reason (line $line, column $column)")

/**
 * The strict JSON reader — a recursive descent over an **immutable** cursor.
 *
 * The cursor is a value (text plus a position) and every step returns the next one, so the reader holds
 * no mutable state: the same text always produces the same value, and the only thing ever appended to is
 * a `StringBuilder` local to one text literal's scan. The three scanning loops (a record's fields, an
 * array's elements, a text literal) are `tailrec`, which keeps a document that is one very long string or
 * one very long array from recursing once per character.
 */
object Json {

    /**
     * How deep a document may nest, counting the outermost value as level 1. The format's own documents
     * nest five levels (document → revision → day → element → prescription), so this is more than eight
     * times what the format can legitimately produce: a deeper document is either not this format or is
     * built to exhaust the reader's stack.
     */
    const val MAXIMUM_DEPTH: Int = 32

    /**
     * The value [text] holds.
     *
     * @throws JsonSyntaxError when [text] is not one JSON value and nothing else.
     */
    fun read(text: String): JsonValue {
        val start = Cursor(text, 0).skippingSpace()
        val (value, after) = valueAt(start, depth = 1)
        val end = after.skippingSpace()
        if (end.position != end.text.length) {
            end.fail("unexpected content after the document's own value")
        }
        return value
    }

    private data class Cursor(val text: String, val position: Int) {

        /** The character at the cursor, or `null` at the end of the text. */
        fun peek(): Char? = if (position >= text.length) null else text[position]

        fun advanced(offset: Int): Cursor = Cursor(text, position + offset)

        /** The cursor past every whitespace character — JSON's four, not the Unicode set. */
        fun skippingSpace(): Cursor = Cursor(
            text,
            (position until text.length).firstOrNull { index -> !isSpace(text[index]) } ?: text.length
        )

        fun fail(reason: String): Nothing = throw syntaxError(text, position, reason)
    }

    private fun isSpace(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\n' || char == '\r'

    private fun syntaxError(text: String, index: Int, reason: String): JsonSyntaxError {
        val before = text.substring(0, minOf(index, text.length))
        val line = before.count { char -> char == '\n' } + 1
        val column = before.length - (before.lastIndexOf('\n') + 1) + 1
        return JsonSyntaxError(at = index, line = line, column = column, reason = reason)
    }

    private fun valueAt(cursor: Cursor, depth: Int): Pair<JsonValue, Cursor> {
        if (depth > MAXIMUM_DEPTH) {
            cursor.fail("the document nests deeper than $MAXIMUM_DEPTH levels (§14)")
        }
        val char = cursor.peek() ?: cursor.fail("a value was expected, but the document ended")
        return when {
            char == '{' -> recordAt(cursor.advanced(1), depth)
            char == '[' -> arrayAt(cursor.advanced(1), depth)
            char == '"' -> textAt(cursor.advanced(1)).let { (text, after) ->
                JsonValue.JsonString(text) to after
            }
            char == 't' -> literalAt(cursor, "true", JsonValue.JsonBoolean(true))
            char == 'f' -> literalAt(cursor, "false", JsonValue.JsonBoolean(false))
            char == 'n' -> literalAt(cursor, "null", JsonValue.JsonNull)
            char == '-' || char.isDigit() -> numberAt(cursor)
            else -> cursor.fail("'$char' does not start a value")
        }
    }

    private tailrec fun recordAt(
        cursor: Cursor,
        depth: Int,
        fields: List<JsonField> = emptyList()
    ): Pair<JsonValue, Cursor> {
        val here = cursor.skippingSpace()
        // An empty record's `}` and a record's closing `}` are the same character, so the shortcut is
        // taken only before the first field: after one, a `}` right after a `,` is a trailing comma, which
        // JSON does not admit and this reader does not guess at.
        if (fields.isEmpty() && here.peek() == '}') {
            return JsonValue.JsonObject(emptyList()) to here.advanced(1)
        }
        if (here.peek() != '"') here.fail("a field name was expected, or a '}'")
        val (name, afterName) = textAt(here.advanced(1))
        if (fields.any { it.name == name }) {
            here.fail("the field \"$name\" is written twice in one record; such a record has no reading")
        }
        val beforeValue = afterName.skippingSpace()
        if (beforeValue.peek() != ':') beforeValue.fail("a ':' was expected after the field \"$name\"")
        val (member, afterValue) = valueAt(beforeValue.advanced(1).skippingSpace(), depth + 1)
        val separator = afterValue.skippingSpace()
        val collected = fields + JsonField(name, member)
        return when (separator.peek()) {
            ',' -> recordAt(separator.advanced(1), depth, collected)
            '}' -> JsonValue.JsonObject(collected) to separator.advanced(1)
            else -> separator.fail("a ',' or a '}' was expected after the field \"$name\"")
        }
    }

    private tailrec fun arrayAt(
        cursor: Cursor,
        depth: Int,
        elements: List<JsonValue> = emptyList()
    ): Pair<JsonValue, Cursor> {
        val here = cursor.skippingSpace()
        // As in a record: the `]` that closes an empty array and the one after a `,` are one character, so
        // the shortcut belongs to the empty case alone and `[1,]` is refused.
        if (elements.isEmpty() && here.peek() == ']') {
            return JsonValue.JsonArray(emptyList()) to here.advanced(1)
        }
        val (element, after) = valueAt(here, depth + 1)
        val separator = after.skippingSpace()
        val collected = elements + element
        return when (separator.peek()) {
            ',' -> arrayAt(separator.advanced(1), depth, collected)
            ']' -> JsonValue.JsonArray(collected) to separator.advanced(1)
            else -> separator.fail("a ',' or a ']' was expected after an element")
        }
    }

    private fun literalAt(cursor: Cursor, literal: String, value: JsonValue): Pair<JsonValue, Cursor> {
        val end = minOf(cursor.text.length, cursor.position + literal.length)
        if (cursor.text.substring(cursor.position, end) != literal) {
            cursor.fail("expected the literal $literal")
        }
        return value to cursor.advanced(literal.length)
    }

    private fun numberAt(cursor: Cursor): Pair<JsonValue, Cursor> {
        val text = cursor.text
        val negative = text[cursor.position] == '-'
        val firstDigit = if (negative) cursor.position + 1 else cursor.position
        val digits = (firstDigit until text.length).takeWhile { index -> text[index].isDigit() }
        if (digits.isEmpty()) cursor.fail("a numeric value needs at least one digit")
        if (digits.size > 1 && text[firstDigit] == '0') {
            cursor.fail("a numeric value may not carry a leading zero")
        }
        if (digits.size > 18) cursor.fail("a numeric value is longer than this format carries")
        val length = (if (negative) 1 else 0) + digits.size
        val next = text.getOrNull(cursor.position + length)
        if (next == '.' || next == 'e' || next == 'E') {
            cursor.fail(
                "the format's numbers are whole: a fraction or an exponent is not a value it carries " +
                    "(§14), and rounding it would be a silent repair"
            )
        }
        val literal = text.substring(cursor.position, cursor.position + length)
        val value = literal.toLongOrNull() ?: cursor.fail("'$literal' is not a whole number")
        return JsonValue.JsonNumber(value) to cursor.advanced(length)
    }

    private fun textAt(cursor: Cursor): Pair<String, Cursor> = textBody(cursor, StringBuilder())

    private tailrec fun textBody(cursor: Cursor, out: StringBuilder): Pair<String, Cursor> {
        val char = cursor.peek() ?: cursor.fail("the document ended inside a text value")
        return when {
            char == '"' -> out.toString() to cursor.advanced(1)
            char == '\\' -> {
                val (escaped, after) = escapeAt(cursor.advanced(1))
                out.append(escaped)
                textBody(after, out)
            }
            char.code < 0x20 -> cursor.fail(
                "a text value may not contain a raw control character; the format escapes them"
            )
            else -> {
                out.append(char)
                textBody(cursor.advanced(1), out)
            }
        }
    }

    private fun escapeAt(cursor: Cursor): Pair<String, Cursor> {
        val char = cursor.peek() ?: cursor.fail("the document ended inside an escape")
        return when (char) {
            '"' -> "\"" to cursor.advanced(1)
            '\\' -> "\\" to cursor.advanced(1)
            '/' -> "/" to cursor.advanced(1)
            'b' -> "\b" to cursor.advanced(1)
            'f' -> "\u000C" to cursor.advanced(1)
            'n' -> "\n" to cursor.advanced(1)
            'r' -> "\r" to cursor.advanced(1)
            't' -> "\t" to cursor.advanced(1)
            'u' -> unicodeAt(cursor)
            else -> cursor.fail("'\\$char' is not an escape this format uses")
        }
    }

    /**
     * One `\uXXXX` escape — and the low half of a surrogate **pair**, which the two escapes of one
     * character are.
     *
     * A lone surrogate is refused rather than carried: it is not a character, it cannot be encoded back
     * to UTF-8, and accepting it would make the reader and the writer disagree about a round-trip.
     */
    private fun unicodeAt(cursor: Cursor): Pair<String, Cursor> {
        val digits = cursor.advanced(1)
        val code = hexAt(digits)
        val after = digits.advanced(4)
        if (code in 0xD800..0xDBFF) {
            val lowEscape = after
            if (lowEscape.peek() != '\\' || lowEscape.text.getOrNull(lowEscape.position + 1) != 'u') {
                cursor.fail("a high surrogate must be followed by its low surrogate")
            }
            val lowDigits = lowEscape.advanced(2)
            val low = hexAt(lowDigits)
            if (low !in 0xDC00..0xDFFF) cursor.fail("a high surrogate must be followed by a low surrogate")
            val codePoint = 0x10000 + (code - 0xD800) * 0x400 + (low - 0xDC00)
            return String(Character.toChars(codePoint)) to lowDigits.advanced(4)
        }
        if (code in 0xDC00..0xDFFF) {
            cursor.fail("a low surrogate may not stand alone: it is half of a character")
        }
        return code.toChar().toString() to after
    }

    private fun hexAt(cursor: Cursor): Int {
        val text = cursor.text
        val end = cursor.position + 4
        if (end > text.length) cursor.fail("'\\u' must be followed by four hexadecimal digits")
        val literal = text.substring(cursor.position, end)
        return literal.toIntOrNull(radix = 16)
            ?: cursor.fail("'\\u$literal' is not four hexadecimal digits")
    }
}
