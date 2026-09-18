package com.monkfitness.app.data.mapper

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The stored representations the Program System's mappers translate, and the only place they are
 * spelled.
 *
 * Three rules hold here, and they are the reason this file exists rather than each mapper doing its
 * own thing:
 *
 *  * **vocabulary is the domain enum's own name, and an unknown token is invalid data.** A stored
 *    value that is not a member of the domain's vocabulary is never mapped to `UNKNOWN`, to a default
 *    or to `null`: it fails loudly, naming the column and the value. Quietly defaulting an unknown
 *    token would change what a row means, which is the one thing the mapper boundary must not do.
 *  * **time is epoch milliseconds and dates are ISO `YYYY-MM-DD`**, the convention the schema already
 *    stores (`ProgramTypeConverters`, `SetLog.sessionDate`, `BodyWeightEntry.date`). A second
 *    serialization convention is not introduced here.
 *  * **nothing in this file reads a clock, mints an id or consults a database.** Every function is a
 *    pure translation of a value that was handed in, so a mapper is reproducible and cannot make a
 *    business decision.
 */

/** The number of milliseconds in the epoch representation of one instant. */
internal fun storedInstant(subject: String, millis: Long): Instant =
    Instant.ofEpochMilli(millis).also { require(subject.isNotBlank()) { "a stored instant must name its column" } }

/** The epoch-millisecond representation of one instant. */
internal fun storedMilliseconds(instant: Instant): Long = instant.toEpochMilli()

/**
 * The `LocalDate` of one stored `YYYY-MM-DD` value.
 *
 * @throws IllegalArgumentException when the stored text is not an ISO date, so a malformed row is
 *   reported rather than silently becoming some other day.
 */
internal fun storedDate(subject: String, value: String): LocalDate =
    try {
        LocalDate.parse(value)
    } catch (failure: DateTimeParseException) {
        throw IllegalArgumentException(
            "a stored $subject must be an ISO-8601 date (YYYY-MM-DD), was '$value'",
            failure
        )
    }

/** The `YYYY-MM-DD` representation of one date, the convention the schema stores. */
internal fun storedDateValue(date: LocalDate): String = date.toString()

/**
 * The enum member one stored token names.
 *
 * @throws IllegalArgumentException when the token is not a member of [values], naming the subject,
 *   the value and the vocabulary it was expected to belong to. There is deliberately no default
 *   member: a stored value the domain does not know is invalid data, not a value to guess at.
 */
internal fun <E : Enum<E>> storedToken(token: String, values: Iterable<E>, subject: String): E =
    values.firstOrNull { it.name == token }
        ?: throw IllegalArgumentException(
            "a stored $subject must be one of ${values.map { it.name }}, was '$token'"
        )
