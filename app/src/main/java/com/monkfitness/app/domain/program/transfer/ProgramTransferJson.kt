package com.monkfitness.app.domain.program.transfer

/**
 * The transfer format's **writer**: a document, as the exact bytes a share carries (§5, §11, §18).
 *
 * ### Determinism is the contract
 *
 * §2 and the brief require the same Program to produce *byte-equivalent* JSON, and §11's round-trip
 * test is only meaningful if that holds. Every source of variation is therefore closed here:
 *
 * ```text
 * field order        the field lists below are literals, in the schema's own order
 * array order        a day list is the plan's order; a weekday list and a focus list are sorted by the
 *                    domain's canonical order; a per-set target list is the plan's order
 * tokens             enum *names*, never ordinals — a reordered enum can never change a file
 * formatting         two-space indentation, `LF`, one trailing newline; scalar arrays inline and
 *                    arrays of records one per line, by this writer's own rule
 * numbers            decimal, no separators, no sign on a positive value
 * text               the escapes JSON requires and nothing else; every other character raw UTF-8
 * clock              never read: nothing in this file can consult a clock, a locale or a random source
 * ```
 *
 * The last line is the strongest one: this file holds no collaborator at all — it is an `object` whose
 * only input is a document — so "no timestamp is generated merely to change the export" and "the same
 * Program input produces the same bytes" are properties of its shape rather than of its care,
 * and §18's *"do not export source lifecycle timestamps"* has nothing to leak *from*.
 *
 * ### What it cannot write
 *
 * The document it is handed has no identity, no timestamp, no lifecycle, no source and no runtime fact
 * (see [ProgramTransferDocument]), so a leaked `programId`, revision identity, archive stamp or
 * adaptive row is not expressible here: the writer's vocabulary is six leaf types and the schema in
 * [ProgramTransferJson.record]'s literals.
 */
object ProgramTransferJson {

    /**
     * The whole document, as the format's text — ending with exactly one newline.
     *
     * A trailing newline is part of the format rather than a courtesy: it makes the file a text file for
     * every tool that reads one, and it is one more byte that a comparison of two documents either
     * agrees about or does not.
     */
    fun write(document: ProgramTransferDocument): String =
        record(
            0,
            listOf(
                "format" to quoted(ProgramTransferFormat.MARKER),
                "formatVersion" to document.formatVersion.toString(),
                "program" to record(
                    1,
                    listOf(
                        "name" to quoted(document.name),
                        "description" to quoted(document.description)
                    )
                ),
                "revision" to revision(1, document.revision)
            )
        ) + "\n"

    // ------------------------------------------------------------------ the schema's own shapes

    private fun revision(depth: Int, revision: RevisionTransfer): String = record(
        depth,
        listOf(
            "mode" to quoted(revision.mode.name),
            "duration" to duration(depth + 1, revision.duration),
            "schedule" to schedule(depth + 1, revision.schedule),
            "focus" to focus(depth + 1, revision.focus),
            "days" to array(depth + 1, revision.days.map { day -> day(depth + 2, it = day) })
        )
    )

    private fun duration(depth: Int, duration: DurationTransfer): String = when (duration) {
        is DurationTransfer.FixedDays -> record(
            depth,
            listOf(
                "kind" to quoted(TransferTokens.FIXED_DAYS),
                "days" to duration.days.toString()
            )
        )
        DurationTransfer.Indefinite -> record(
            depth,
            listOf("kind" to quoted(TransferTokens.INDEFINITE))
        )
    }

    private fun schedule(depth: Int, schedule: ScheduleTransfer): String = when (schedule) {
        is ScheduleTransfer.FixedWeekdays -> record(
            depth,
            listOf(
                "kind" to quoted(TransferTokens.FIXED_WEEKDAYS),
                "weekdays" to inlineArray(schedule.weekdays.map { day -> quoted(day.name) })
            )
        )
        is ScheduleTransfer.FlexiblePerWeek -> record(
            depth,
            listOf(
                "kind" to quoted(TransferTokens.FLEXIBLE_PER_WEEK),
                "sessionsPerWeek" to schedule.sessionsPerWeek.toString()
            )
        )
    }

    private fun focus(depth: Int, focus: FocusTransfer): String = when (focus) {
        FocusTransfer.Balanced -> record(
            depth,
            listOf("goal" to quoted(TransferTokens.BALANCED))
        )
        is FocusTransfer.Focused -> record(
            depth,
            listOf(
                "goal" to quoted(TransferTokens.FOCUSED),
                "focuses" to inlineArray(focus.focuses.map { named -> quoted(named.name) })
            )
        )
        is FocusTransfer.Custom -> record(
            depth,
            listOf(
                "goal" to quoted(TransferTokens.CUSTOM),
                "allocations" to array(
                    depth + 1,
                    focus.allocations.map { allocation ->
                        record(
                            depth + 2,
                            listOf(
                                "focus" to quoted(allocation.focus.name),
                                "percent" to allocation.percent.toString()
                            )
                        )
                    }
                )
            )
        )
    }

    private fun day(depth: Int, it: DayTransfer): String = record(
        depth,
        listOfNotNull(
            "type" to quoted(it.type.name),
            // An unnamed day carries no field at all: "no label" is one value and the format admits one
            // representation of it.
            it.name?.let { name -> "name" to quoted(name) },
            "exercises" to array(
                depth + 1,
                it.exercises.map { element -> element(depth + 2, element) }
            )
        )
    )

    private fun element(depth: Int, element: ExerciseTransfer): String = record(
        depth,
        listOf(
            "exerciseId" to quoted(element.exerciseId),
            "prescription" to record(
                depth + 1,
                listOf(
                    "dimension" to quoted(element.prescription.dimension.name),
                    "perSetTargets" to inlineArray(
                        element.prescription.perSetTargets.map { target -> target.toString() }
                    )
                )
            ),
            "origin" to quoted(element.origin.name),
            "pinned" to element.isPinned.toString()
        )
    )

    // ------------------------------------------------------------------ the mechanism

    /** Two spaces per level — the format's indentation, read and written as a rule. */
    private fun indent(depth: Int): String = "  ".repeat(depth)

    private fun record(depth: Int, fields: List<Pair<String, String>>): String =
        fields.joinToString(
            separator = ",\n",
            prefix = "{\n",
            postfix = "\n" + indent(depth) + "}"
        ) { (name, value) -> indent(depth + 1) + quoted(name) + ": " + value }

    /** An array of records, one per line. */
    private fun array(depth: Int, elements: List<String>): String =
        if (elements.isEmpty()) {
            "[]"
        } else {
            elements.joinToString(
                separator = ",\n",
                prefix = "[\n",
                postfix = "\n" + indent(depth) + "]"
            ) { element -> indent(depth + 1) + element }
        }

    /** An array of scalars, on one line: a weekday set and a target list read better as a row. */
    private fun inlineArray(elements: List<String>): String =
        elements.joinToString(separator = ", ", prefix = "[", postfix = "]")

    /**
     * [value] as a JSON text literal.
     *
     * The escapes are JSON's own and nothing else: `"` and `\`, the five characters with a short escape
     * (`\b \f \n \r \t`), and the remaining control characters as `\u00XX`. Every other character —
     * including every non-ASCII one — is written raw and encoded as UTF-8 by
     * [ProgramTransferFormat.encode], which is what makes the bytes a function of the value alone.
     */
    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\b' -> append("\\b")
                char == '\u000C' -> append("\\f")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char.code < 0x20 ->
                    append("\\u").append(char.code.toString(radix = 16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }
}

/**
 * The format's own tokens: the `kind` of a duration and a schedule, and the `goal` of a focus
 * configuration.
 *
 * One home for the six strings, because the writer emits exactly what the reader accepts — a token
 * spelled twice is a format with two dialects, and the first symptom would be a file this app cannot
 * read back. Every *other* token in the format is a domain enum's own name (`ProgramMode.MANUAL`,
 * `ProgramDayType.REST`, `PrescriptionDimension.REP_BASED`, `Focus.PUSH`), which is the representation
 * the app's converters already persist, so those need no second list here.
 */
internal object TransferTokens {

    const val FIXED_DAYS: String = "FIXED_DAYS"
    const val INDEFINITE: String = "INDEFINITE"

    const val FIXED_WEEKDAYS: String = "FIXED_WEEKDAYS"
    const val FLEXIBLE_PER_WEEK: String = "FLEXIBLE_PER_WEEK"

    const val BALANCED: String = "BALANCED"
    const val FOCUSED: String = "FOCUSED"
    const val CUSTOM: String = "CUSTOM"
}
