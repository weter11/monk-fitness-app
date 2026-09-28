package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.ProgramTargetProgramDayBindingEntity
import com.monkfitness.app.data.model.ProgramTargetScheduleRuleEntity
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.usecase.TargetScheduleDefinition
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The revision-owned target schedule source rows ⇄ their domain value.
 *
 * This file is a **translation**, and the translation is the whole claim of the phase: a
 * [com.monkfitness.app.domain.usecase.TargetScheduleSource] written here comes back out of
 * [toTargetScheduleRule] and [toTargetProgramDayBinding] as an equal one, with every rule's identity,
 * its cadence *form*, its cadence payload and its anchor date, and every binding's two identities,
 * exactly as they went in. Each of the mapper's dullnesses is therefore load-bearing:
 *
 *  * `ruleId` and `workoutId` are copied verbatim. Neither is derived from a legacy `ProgramSchedule`,
 *    from a `ProgramDay`'s `position` or `name`, from a date, from a weekday, from a list index or
 *    from a `ProgramDayId`'s own text, and no placeholder is ever substituted for a missing one.
 *  * The cadence is read **form first**. [storedCadence] switches on the stored discriminator and
 *    reads the one payload column that form owns; it never infers a form from a payload's shape, so
 *    `SessionsPerWeek(3)` cannot come back as a `FixedWeekdays` set of three days and `EveryNDays(3)`
 *    cannot come back as the same thing either. A stored token outside the vocabulary fails loudly
 *    rather than defaulting, which is the same rule `storedValues.kt` states for every other column.
 *  * `DerivedExcluding.sourceRuleId` is copied from its own column, so a derived rule reads back
 *    naming the same source rule it was stored naming.
 *  * `anchorDate` goes through [storedDate], so a stored value is either a real date or a loud
 *    failure.
 *  * Nothing here is sorted or deduplicated: the read order is the DAO's `ORDER BY`, and a mapper that
 *    reordered would hide a write that stored the wrong order.
 */

/**
 * The domain cadence of one stored rule row.
 *
 * The discriminator decides the form and the form decides which column is read. There is no third
 * possibility: no payload sniffing, no default form, and no partial cadence — an absent payload for a
 * form that has one is a refusal, because the stored row contradicts itself.
 */
internal fun ProgramTargetScheduleRuleEntity.storedCadence(): ScheduleCadence =
    when (cadenceType) {
        ProgramTargetScheduleRuleEntity.DAILY -> ScheduleCadence.Daily
        ProgramTargetScheduleRuleEntity.EVERY_N_DAYS -> ScheduleCadence.EveryNDays(
            requireNotNull(cadenceDays) {
                "a stored EVERY_N_DAYS cadence has no interval to read"
            }
        )
        ProgramTargetScheduleRuleEntity.SESSIONS_PER_WEEK -> ScheduleCadence.SessionsPerWeek(
            requireNotNull(cadenceSessionsPerWeek) {
                "a stored SESSIONS_PER_WEEK cadence has no frequency to read"
            }
        )
        ProgramTargetScheduleRuleEntity.FIXED_WEEKDAYS -> ScheduleCadence.FixedWeekdays(
            storedWeekdays(
                requireNotNull(cadenceWeekdays) {
                    "a stored FIXED_WEEKDAYS cadence names no weekdays to read"
                }
            )
        )
        ProgramTargetScheduleRuleEntity.DERIVED_EXCLUDING -> ScheduleCadence.DerivedExcluding(
            requireNotNull(cadenceSourceRuleId) {
                "a stored DERIVED_EXCLUDING cadence names no source rule to read"
            }
        )
        else -> throw IllegalArgumentException(
            "a stored cadenceType must be one of " +
                "${CADENCE_TOKENS.joinToString()}, was '$cadenceType'"
        )
    }

/**
 * The stored day names of one `FIXED_WEEKDAYS` cadence, as the set the domain holds.
 *
 * The order is the canonical ascending one the column stores, and it is turned back into a set because
 * a set is what the cadence is: `FixedWeekdays` is membership, not a sequence, so recovering the
 * canonical order into a set loses nothing and re-storing produces the same bytes.
 */
private fun storedWeekdays(stored: String): Set<DayOfWeek> = stored.split(",")
    .map { name ->
        DayOfWeek.entries.firstOrNull { day -> day.name == name.trim() }
            ?: throw IllegalArgumentException(
                "a stored cadenceWeekdays must name days of $DAY_NAMES, was '$name'"
            )
    }
    .toSet()

/** The domain rule one stored rule row states. */
internal fun ProgramTargetScheduleRuleEntity.toTargetScheduleRule(): TargetScheduleDefinition =
    TargetScheduleDefinition(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = storedCadence(),
        anchorDate = storedDate("program_target_schedule_rule.anchorDate", anchorDate)
    )

/** The stored rule row for one explicitly stated domain rule, under the revision that states it. */
internal fun TargetScheduleDefinition.toTargetScheduleRuleEntity(
    revisionId: RevisionId
): ProgramTargetScheduleRuleEntity {
    val stored = storedCadenceColumns(cadence)
    return ProgramTargetScheduleRuleEntity(
        revisionId = revisionId.value,
        ruleId = ruleId,
        workoutId = workoutId,
        cadenceType = stored.type,
        cadenceDays = stored.days,
        cadenceSessionsPerWeek = stored.sessionsPerWeek,
        cadenceWeekdays = stored.weekdays,
        cadenceSourceRuleId = stored.sourceRuleId,
        anchorDate = storedDateValue(anchorDate)
    )
}

/** The domain binding one stored binding row states. */
internal fun ProgramTargetProgramDayBindingEntity.toTargetProgramDayBinding(): TargetProgramDayBinding =
    TargetProgramDayBinding(
        workoutId = workoutId,
        programDayId = ProgramDayId(programDayId)
    )

/** The stored binding row for one explicitly stated domain binding, under the revision that states it. */
internal fun TargetProgramDayBinding.toTargetProgramDayBindingEntity(
    revisionId: RevisionId
): ProgramTargetProgramDayBindingEntity = ProgramTargetProgramDayBindingEntity(
    revisionId = revisionId.value,
    workoutId = workoutId,
    programDayId = programDayId.value
)

/** The four stored columns one cadence form occupies: its discriminator and its own payload. */
private data class StoredCadenceColumns(
    val type: String,
    val days: Int? = null,
    val sessionsPerWeek: Int? = null,
    val weekdays: String? = null,
    val sourceRuleId: String? = null
)

/**
 * The stored columns of one cadence: the form's own discriminator, and exactly the payload that form
 * owns.
 *
 * Each branch fills one payload and leaves the rest `null`. Nothing is defaulted and nothing is
 * substituted: `SessionsPerWeek` keeps its frequency as a frequency (never as a weekday set or an
 * interval), and `DerivedExcluding` keeps its source rule identity verbatim.
 */
private fun storedCadenceColumns(cadence: ScheduleCadence): StoredCadenceColumns = when (cadence) {
    ScheduleCadence.Daily ->
        StoredCadenceColumns(type = ProgramTargetScheduleRuleEntity.DAILY)
    is ScheduleCadence.EveryNDays -> StoredCadenceColumns(
        type = ProgramTargetScheduleRuleEntity.EVERY_N_DAYS,
        days = cadence.days
    )
    is ScheduleCadence.SessionsPerWeek -> StoredCadenceColumns(
        type = ProgramTargetScheduleRuleEntity.SESSIONS_PER_WEEK,
        sessionsPerWeek = cadence.sessionsPerWeek
    )
    is ScheduleCadence.FixedWeekdays -> StoredCadenceColumns(
        type = ProgramTargetScheduleRuleEntity.FIXED_WEEKDAYS,
        weekdays = cadence.weekdays.sortedBy { day -> day.value }.joinToString(",") { it.name }
    )
    is ScheduleCadence.DerivedExcluding -> StoredCadenceColumns(
        type = ProgramTargetScheduleRuleEntity.DERIVED_EXCLUDING,
        sourceRuleId = cadence.sourceRuleId
    )
}

/** Every cadence discriminator this schema stores, in the order the entity declares them. */
internal val CADENCE_TOKENS: List<String> = listOf(
    ProgramTargetScheduleRuleEntity.DAILY,
    ProgramTargetScheduleRuleEntity.EVERY_N_DAYS,
    ProgramTargetScheduleRuleEntity.SESSIONS_PER_WEEK,
    ProgramTargetScheduleRuleEntity.FIXED_WEEKDAYS,
    ProgramTargetScheduleRuleEntity.DERIVED_EXCLUDING
)

/** The weekday names a stored `cadenceWeekdays` column may hold, for a failure message to name. */
private val DAY_NAMES: List<String> = DayOfWeek.entries.map { it.name }
