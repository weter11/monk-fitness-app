package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import java.time.DayOfWeek

/**
 * The transfer format's **reader**: JSON text, as the [ProgramTransferDocument] it declares — §5's
 * *parse* and *schema validation* steps.
 *
 * ### The order is the pipeline
 *
 * §5 fixes the order of the steps, and this file walks them in that order because the order is what
 * makes each refusal the *right* one:
 *
 * ```text
 * the byte limit        a document is read into memory, so how large it may be is part of the format
 * parse                 Json.read: a syntax failure produces no value at all
 * the marker            "format": "monkfitness.program"  → not a program file, before anything is read
 * formatVersion         present, a whole number, and supported → before the schema, because a version
 *                       this reader does not know is a document whose schema it does not know either
 * the schema            every field, kind and token → unknown fields refused, not ignored
 * ```
 *
 * ### The reader is an allowlist, field by field
 *
 * Every record's allowed field names are literals here, and a field that is not among them is a finding
 * ([ProgramTransferIssue.UnknownField]) rather than something quietly stepped over. That is what makes
 * §2's *"allowlist design, not a blacklist"* mechanical: the format carries exactly what this file
 * reads, and a document that carries more was written by something else. The allowed set is chosen
 * *per variant* (`FIXED_DAYS` carries `days`, `INDEFINITE` does not), so a record that carries the
 * other variant's field is a finding rather than a second reading of one fact.
 *
 * ### One pass, every finding
 *
 * The reading is a chain of `Read` steps, each of which answers *the value, or nothing, and what was
 * wrong with the way*. Sibling fields are read independently and their findings are collected, so a
 * document with four problems reports four — a user fixing a file by hand gets the whole list rather
 * than one line per attempt. The gate is at the top: **a document is produced only when there is not
 * one finding**, so a value built best-effort from a partly broken document can never be mistaken for a
 * valid one.
 *
 * ### What this file does *not* do
 *
 * It never decides whether the document describes a *valid program*: a fixed duration of `0` days, a
 * blank exercise id, a rest day with elements, an empty weekday list and a custom focus that does not
 * sum to 100% all read perfectly here and are refused one layer up ([ProgramTransferValidation], §6).
 * The separation is §5's own — *schema validation* then *semantic validation* — and keeping it means
 * the schema layer has no business rule to duplicate and the semantic layer has no parsing to redo.
 */
internal object ProgramTransferReader {

    private const val DOCUMENT: String = "document"
    private const val PROGRAM: String = "$DOCUMENT.program"
    private const val REVISION: String = "$DOCUMENT.revision"

    private val DOCUMENT_FIELDS = listOf("format", "formatVersion", "program", "revision")
    private val PROGRAM_FIELDS = listOf("name", "description")
    private val REVISION_FIELDS = listOf("mode", "duration", "schedule", "focus", "days")
    private val DURATION_FIELDS = listOf("kind", "days")
    private val SCHEDULE_FIELDS = listOf("kind", "weekdays", "sessionsPerWeek")
    private val FOCUS_FIELDS = listOf("goal", "focuses", "allocations")
    private val ALLOCATION_FIELDS = listOf("focus", "percent")
    private val DAY_FIELDS = listOf("type", "name", "exercises")
    private val EXERCISE_FIELDS = listOf("exerciseId", "prescription", "origin", "pinned")
    private val PRESCRIPTION_FIELDS = listOf("dimension", "perSetTargets")

    /**
     * The document [text] holds.
     *
     * @throws MalformedDocument when [text] is too large, is not JSON, or is not this format.
     * @throws UnsupportedDocumentVersion when it is this format in a version this reader does not know.
     * @throws SchemaViolation when it is this format, in this version, but not the shape it defines.
     */
    fun read(text: String): ProgramTransferDocument {
        val size = ProgramTransferFormat.encode(text).size
        if (size > ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES) {
            throw MalformedDocument(
                "it is $size bytes, and this app reads documents of at most " +
                    "${ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES} bytes"
            )
        }

        val root = try {
            Json.read(text)
        } catch (failure: JsonSyntaxError) {
            throw MalformedDocument(failure.message ?: "it is not JSON")
        }

        val record = root as? JsonValue.JsonObject
            ?: throw SchemaViolation(
                listOf(ProgramTransferIssue.WrongType(DOCUMENT, "a JSON object", kindOf(root)))
            )

        val structural = unknownFields(record, DOCUMENT, DOCUMENT_FIELDS)
        val marker = markerIssues(record)
        val version = intField(record, DOCUMENT, "formatVersion")
        if (marker.isNotEmpty() || version.value == null) {
            throw SchemaViolation(structural + marker + version.issues)
        }
        if (version.value != ProgramTransferFormat.VERSION) {
            throw UnsupportedDocumentVersion(version.value)
        }

        val program = fieldOf(record, DOCUMENT, "program")
            .then { value -> recordOf(value, PROGRAM) }
            .then { fields -> programFields(fields) }
        val revision = fieldOf(record, DOCUMENT, "revision")
            .then { value -> recordOf(value, REVISION) }
            .then { fields -> revisionFields(fields) }

        val issues = structural + program.issues + revision.issues
        val document = if (issues.isEmpty() && program.value != null && revision.value != null) {
            ProgramTransferDocument(
                formatVersion = ProgramTransferFormat.VERSION,
                name = program.value.first,
                description = program.value.second,
                revision = revision.value
            )
        } else {
            null
        }
        return document ?: throw SchemaViolation(issues)
    }

    // ------------------------------------------------------------------ the document's own shape

    /**
     * The `format` marker, as findings rather than as a refusal: a *missing* marker is a missing required
     * field, while a marker that *is* there and says something else is not this format at all — and that
     * is a sentence about the file rather than about a program.
     */
    private fun markerIssues(record: JsonValue.JsonObject): List<ProgramTransferIssue> =
        when (val marker = record.named("format")) {
            null -> listOf(ProgramTransferIssue.MissingField(DOCUMENT, "format"))
            is JsonValue.JsonString -> if (marker.value == ProgramTransferFormat.MARKER) {
                emptyList()
            } else {
                throw MalformedDocument(
                    "its format marker is \"${marker.value}\" rather than " +
                        "\"${ProgramTransferFormat.MARKER}\""
                )
            }
            else -> listOf(
                ProgramTransferIssue.WrongType("$DOCUMENT.format", "a text value", kindOf(marker))
            )
        }

    private fun programFields(record: JsonValue.JsonObject): Read<Pair<String, String>> {
        val name = requiredText(record, PROGRAM, "name")
        val description = requiredText(record, PROGRAM, "description")
        val issues = unknownFields(record, PROGRAM, PROGRAM_FIELDS) + name.issues +
            description.issues
        val fields = if (name.value != null && description.value != null) {
            name.value to description.value
        } else {
            null
        }
        return Read(fields, issues)
    }

    private fun revisionFields(record: JsonValue.JsonObject): Read<RevisionTransfer> {
        val mode = fieldOf(record, REVISION, "mode").then { value ->
            token(value, "$REVISION.mode", ProgramMode.entries.map { it.name }) { token ->
                ProgramMode.entries.firstOrNull { each -> each.name == token }
            }
        }
        val duration = fieldOf(record, REVISION, "duration")
            .then { value -> recordOf(value, "$REVISION.duration") }
            .then { fields -> durationFields(fields) }
        val schedule = fieldOf(record, REVISION, "schedule")
            .then { value -> recordOf(value, "$REVISION.schedule") }
            .then { fields -> scheduleFields(fields) }
        val focus = fieldOf(record, REVISION, "focus")
            .then { value -> recordOf(value, "$REVISION.focus") }
            .then { fields -> focusFields(fields) }
        val days = fieldOf(record, REVISION, "days")
            .then { value -> arrayOf(value, "$REVISION.days") }
            .then { elements -> dayList(elements) }

        val issues = unknownFields(record, REVISION, REVISION_FIELDS) + mode.issues +
            duration.issues + schedule.issues + focus.issues + days.issues
        val revision = if (
            mode.value != null && duration.value != null && schedule.value != null &&
            focus.value != null && days.value != null
        ) {
            RevisionTransfer(
                mode = mode.value,
                duration = duration.value,
                schedule = schedule.value,
                focus = focus.value,
                days = days.value
            )
        } else {
            null
        }
        return Read(revision, issues)
    }

    private fun durationFields(record: JsonValue.JsonObject): Read<DurationTransfer> {
        val path = "$REVISION.duration"
        val kind = fieldOf(record, path, "kind").then { value ->
            token(
                value,
                "$path.kind",
                listOf(TransferTokens.FIXED_DAYS, TransferTokens.INDEFINITE)
            ) { token -> token }
        }
        val allowed = when (kind.value) {
            TransferTokens.INDEFINITE -> listOf("kind")
            else -> DURATION_FIELDS
        }
        val days = if (kind.value == TransferTokens.FIXED_DAYS) {
            intField(record, path, "days")
        } else {
            Read.of(null)
        }
        val issues = kind.issues + unknownFields(record, path, allowed) + days.issues
        val duration = when (kind.value) {
            null -> null
            TransferTokens.INDEFINITE -> DurationTransfer.Indefinite
            else -> days.value?.let { DurationTransfer.FixedDays(it) }
        }
        return Read(duration, issues)
    }

    private fun scheduleFields(record: JsonValue.JsonObject): Read<ScheduleTransfer> {
        val path = "$REVISION.schedule"
        val kind = fieldOf(record, path, "kind").then { value ->
            token(
                value,
                "$path.kind",
                listOf(TransferTokens.FIXED_WEEKDAYS, TransferTokens.FLEXIBLE_PER_WEEK)
            ) { token -> token }
        }
        val allowed = when (kind.value) {
            TransferTokens.FIXED_WEEKDAYS -> listOf("kind", "weekdays")
            TransferTokens.FLEXIBLE_PER_WEEK -> listOf("kind", "sessionsPerWeek")
            else -> SCHEDULE_FIELDS
        }
        val weekdays = if (kind.value == TransferTokens.FIXED_WEEKDAYS) {
            fieldOf(record, path, "weekdays")
                .then { value -> arrayOf(value, "$path.weekdays") }
                .then { elements -> weekdayList(elements, "$path.weekdays") }
        } else {
            Read.of(null)
        }
        val sessions = if (kind.value == TransferTokens.FLEXIBLE_PER_WEEK) {
            intField(record, path, "sessionsPerWeek")
        } else {
            Read.of(null)
        }
        val issues = kind.issues + unknownFields(record, path, allowed) + weekdays.issues +
            sessions.issues
        val schedule = when (kind.value) {
            null -> null
            TransferTokens.FIXED_WEEKDAYS ->
                weekdays.value?.let { days -> ScheduleTransfer.FixedWeekdays(days) }
            TransferTokens.FLEXIBLE_PER_WEEK ->
                sessions.value?.let { perWeek -> ScheduleTransfer.FlexiblePerWeek(perWeek) }
            else -> null
        }
        return Read(schedule, issues)
    }

    private fun focusFields(record: JsonValue.JsonObject): Read<FocusTransfer> {
        val path = "$REVISION.focus"
        val goal = fieldOf(record, path, "goal").then { value ->
            token(
                value,
                "$path.goal",
                listOf(TransferTokens.BALANCED, TransferTokens.FOCUSED, TransferTokens.CUSTOM)
            ) { token -> token }
        }
        val allowed = when (goal.value) {
            TransferTokens.BALANCED -> listOf("goal")
            TransferTokens.FOCUSED -> listOf("goal", "focuses")
            TransferTokens.CUSTOM -> listOf("goal", "allocations")
            else -> FOCUS_FIELDS
        }
        val focuses = if (goal.value == TransferTokens.FOCUSED) {
            fieldOf(record, path, "focuses")
                .then { value -> arrayOf(value, "$path.focuses") }
                .then { elements -> focusList(elements, "$path.focuses") }
        } else {
            Read.of(null)
        }
        val allocations = if (goal.value == TransferTokens.CUSTOM) {
            fieldOf(record, path, "allocations")
                .then { value -> arrayOf(value, "$path.allocations") }
                .then { elements -> allocationList(elements, "$path.allocations") }
        } else {
            Read.of(null)
        }
        val issues = goal.issues + unknownFields(record, path, allowed) + focuses.issues +
            allocations.issues
        val focus = when (goal.value) {
            null -> null
            TransferTokens.BALANCED -> FocusTransfer.Balanced
            TransferTokens.FOCUSED ->
                focuses.value?.let { named -> FocusTransfer.Focused(named) }
            TransferTokens.CUSTOM ->
                allocations.value?.let { shares -> FocusTransfer.Custom(shares) }
            else -> null
        }
        return Read(focus, issues)
    }

    // ------------------------------------------------------------------ the plan

    private fun dayList(elements: List<JsonValue>): Read<List<DayTransfer>> {
        val days = elements.mapIndexed { index, element -> day(element, index) }
        return collect(days)
    }

    private fun day(value: JsonValue, index: Int): Read<DayTransfer> {
        val path = "$REVISION.days[$index]"
        val record = value as? JsonValue.JsonObject
            ?: return Read.failed(ProgramTransferIssue.WrongType(path, "a JSON object", kindOf(value)))
        val type = fieldOf(record, path, "type").then { field ->
            token(field, "$path.type", ProgramDayType.entries.map { it.name }) { token ->
                ProgramDayType.entries.firstOrNull { each -> each.name == token }
            }
        }
        val name = optionalText(record, path, "name")
        val exercises = fieldOf(record, path, "exercises")
            .then { field -> arrayOf(field, "$path.exercises") }
            .then { values -> exerciseList(values, path) }

        val issues = unknownFields(record, path, DAY_FIELDS) + type.issues + name.issues +
            exercises.issues
        val planDay = if (type.value != null && exercises.value != null) {
            DayTransfer(type = type.value, name = name.value, exercises = exercises.value)
        } else {
            null
        }
        return Read(planDay, issues)
    }

    private fun exerciseList(elements: List<JsonValue>, dayPath: String): Read<List<ExerciseTransfer>> {
        val elementsRead = elements.mapIndexed { index, element -> exercise(element, dayPath, index) }
        return collect(elementsRead)
    }

    private fun exercise(value: JsonValue, dayPath: String, index: Int): Read<ExerciseTransfer> {
        val path = "$dayPath.exercises[$index]"
        val record = value as? JsonValue.JsonObject
            ?: return Read.failed(ProgramTransferIssue.WrongType(path, "a JSON object", kindOf(value)))
        val exerciseId = requiredText(record, path, "exerciseId")
        val prescription = fieldOf(record, path, "prescription")
            .then { field -> recordOf(field, "$path.prescription") }
            .then { fields -> prescriptionFields(fields, "$path.prescription") }
        val origin = fieldOf(record, path, "origin").then { field ->
            token(field, "$path.origin", ProgramExerciseOrigin.entries.map { it.name }) { token ->
                ProgramExerciseOrigin.entries.firstOrNull { each -> each.name == token }
            }
        }
        val pinned = booleanField(record, path, "pinned")

        val issues = unknownFields(record, path, EXERCISE_FIELDS) + exerciseId.issues +
            prescription.issues + origin.issues + pinned.issues
        val element = if (
            exerciseId.value != null && prescription.value != null && origin.value != null &&
            pinned.value != null
        ) {
            ExerciseTransfer(
                exerciseId = exerciseId.value,
                prescription = prescription.value,
                origin = origin.value,
                isPinned = pinned.value
            )
        } else {
            null
        }
        return Read(element, issues)
    }

    private fun prescriptionFields(
        record: JsonValue.JsonObject,
        path: String
    ): Read<PrescriptionTransfer> {
        val dimension = fieldOf(record, path, "dimension").then { field ->
            token(field, "$path.dimension", PrescriptionDimension.entries.map { it.name }) { token ->
                PrescriptionDimension.entries.firstOrNull { each -> each.name == token }
            }
        }
        val targets = fieldOf(record, path, "perSetTargets")
            .then { field -> arrayOf(field, "$path.perSetTargets") }
            .then { values -> targetList(values, "$path.perSetTargets") }

        val issues = unknownFields(record, path, PRESCRIPTION_FIELDS) + dimension.issues +
            targets.issues
        val prescription = if (dimension.value != null && targets.value != null) {
            PrescriptionTransfer(dimension.value, targets.value)
        } else {
            null
        }
        return Read(prescription, issues)
    }

    private fun targetList(elements: List<JsonValue>, path: String): Read<List<Int>> =
        collect(
            elements.mapIndexed { index, element -> intAt(element, "$path[$index]") }
        )

    private fun weekdayList(elements: List<JsonValue>, path: String): Read<List<DayOfWeek>> =
        collect(
            elements.mapIndexed { index, element ->
                token(element, "$path[$index]", enumValues<DayOfWeek>().map { it.name }) { token ->
                    enumValues<DayOfWeek>().firstOrNull { each -> each.name == token }
                }
            }
        )

    private fun focusList(elements: List<JsonValue>, path: String): Read<List<Focus>> =
        collect(
            elements.mapIndexed { index, element ->
                token(element, "$path[$index]", Focus.entries.map { it.name }) { token ->
                    Focus.entries.firstOrNull { each -> each.name == token }
                }
            }
        )

    private fun allocationList(elements: List<JsonValue>, path: String): Read<List<FocusShare>> =
        collect(
            elements.mapIndexed { index, element -> allocation(element, "$path[$index]") }
        )

    private fun allocation(value: JsonValue, path: String): Read<FocusShare> {
        val record = value as? JsonValue.JsonObject
            ?: return Read.failed(ProgramTransferIssue.WrongType(path, "a JSON object", kindOf(value)))
        val focus = fieldOf(record, path, "focus").then { field ->
            token(field, "$path.focus", Focus.entries.map { it.name }) { token ->
                Focus.entries.firstOrNull { each -> each.name == token }
            }
        }
        val percent = intField(record, path, "percent")
        val issues = unknownFields(record, path, ALLOCATION_FIELDS) + focus.issues + percent.issues
        val share = if (focus.value != null && percent.value != null) {
            FocusShare(focus.value, percent.value)
        } else {
            null
        }
        return Read(share, issues)
    }

    // ------------------------------------------------------------------ the primitives

    /**
     * What one step of the reading produced: the value, or nothing, plus what was wrong with the way.
     *
     * A value and its findings travel together rather than as an exception, which is what lets a document
     * with several problems report all of them — a user fixing a file by hand should not have to run the
     * import once per mistake. Nothing here *throws* for a finding; the top of [read] is where a
     * document with any finding is refused.
     */
    private class Read<T>(val value: T?, val issues: List<ProgramTransferIssue>) {

        /** This reading, then [next] on its value, with both sets of findings. */
        fun <R> then(next: (T) -> Read<R>): Read<R> = value
            ?.let { present -> next(present).let { after -> Read(after.value, issues + after.issues) } }
            ?: Read(null, issues)

        companion object {

            fun <T> of(value: T): Read<T> = Read(value, emptyList())

            fun <T> failed(issue: ProgramTransferIssue): Read<T> = Read(null, listOf(issue))
        }
    }

    /** The one list value, or nothing, from a list of readings of the same thing. */
    private fun <T> collect(readings: List<Read<T>>): Read<List<T>> {
        val issues = readings.flatMap { reading -> reading.issues }
        val values = readings.map { reading -> reading.value }
        return if (values.any { it == null }) {
            Read(null, issues)
        } else {
            Read(values.filterNotNull(), issues)
        }
    }

    private fun unknownFields(
        record: JsonValue.JsonObject,
        path: String,
        allowed: List<String>
    ): List<ProgramTransferIssue> = record.names
        .filterNot { name -> name in allowed }
        .map { name -> ProgramTransferIssue.UnknownField(path, name) }

    private fun fieldOf(record: JsonValue.JsonObject, path: String, name: String): Read<JsonValue> =
        record.named(name)?.let { value -> Read.of(value) }
            ?: Read.failed(ProgramTransferIssue.MissingField(path, name))

    private fun recordOf(value: JsonValue, path: String): Read<JsonValue.JsonObject> =
        if (value is JsonValue.JsonObject) {
            Read.of(value)
        } else {
            Read.failed(ProgramTransferIssue.WrongType(path, "a JSON object", kindOf(value)))
        }

    private fun arrayOf(value: JsonValue, path: String): Read<List<JsonValue>> =
        if (value is JsonValue.JsonArray) {
            Read.of(value.elements)
        } else {
            Read.failed(ProgramTransferIssue.WrongType(path, "a JSON array", kindOf(value)))
        }

    private fun requiredText(record: JsonValue.JsonObject, path: String, name: String): Read<String> {
        val value = record.named(name)
            ?: return Read.failed(ProgramTransferIssue.MissingField(path, name))
        return if (value is JsonValue.JsonString) {
            Read.of(value.value)
        } else {
            Read.failed(ProgramTransferIssue.WrongType("$path.$name", "a text value", kindOf(value)))
        }
    }

    /**
     * An optional text field.
     *
     * Absent is a value here — an unnamed day states no name — so the reading answers `null` with no
     * finding when the field is not there, and a finding when it is there and is not text. The caller
     * cannot tell the two apart from the value alone, and does not need to: the top of [read] refuses the
     * document whenever *any* finding exists, so a malformed optional field can never be silently read as
     * an absent one.
     */
    private fun optionalText(record: JsonValue.JsonObject, path: String, name: String): Read<String?> {
        val value = record.named(name) ?: return Read.of(null)
        return if (value is JsonValue.JsonString) {
            Read.of(value.value)
        } else {
            Read.failed(ProgramTransferIssue.WrongType("$path.$name", "a text value", kindOf(value)))
        }
    }

    private fun booleanField(record: JsonValue.JsonObject, path: String, name: String): Read<Boolean> {
        val value = record.named(name)
            ?: return Read.failed(ProgramTransferIssue.MissingField(path, name))
        return if (value is JsonValue.JsonBoolean) {
            Read.of(value.value)
        } else {
            Read.failed(ProgramTransferIssue.WrongType("$path.$name", "a boolean", kindOf(value)))
        }
    }

    private fun intField(record: JsonValue.JsonObject, path: String, name: String): Read<Int> {
        val value = record.named(name)
            ?: return Read.failed(ProgramTransferIssue.MissingField(path, name))
        return intAt(value, "$path.$name")
    }

    private fun intAt(value: JsonValue, path: String): Read<Int> {
        if (value !is JsonValue.JsonNumber) {
            return Read.failed(ProgramTransferIssue.WrongType(path, "a whole number", kindOf(value)))
        }
        return if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            Read.of(value.value.toInt())
        } else {
            Read.failed(
                ProgramTransferIssue.WrongType(
                    path,
                    "a whole number",
                    "a number this format does not carry"
                )
            )
        }
    }

    private fun <T> token(
        value: JsonValue,
        path: String,
        allowed: List<String>,
        parse: (String) -> T?
    ): Read<T> {
        if (value !is JsonValue.JsonString) {
            return Read.failed(ProgramTransferIssue.WrongType(path, "a text token", kindOf(value)))
        }
        val parsed = parse(value.value)
            ?: return Read.failed(ProgramTransferIssue.UnknownToken(path, value.value, allowed))
        return Read.of(parsed)
    }

    private fun kindOf(value: JsonValue): String = when (value) {
        is JsonValue.JsonString -> "a text value"
        is JsonValue.JsonNumber -> "a number"
        is JsonValue.JsonBoolean -> "a boolean"
        JsonValue.JsonNull -> "null"
        is JsonValue.JsonArray -> "an array"
        is JsonValue.JsonObject -> "an object"
    }
}
