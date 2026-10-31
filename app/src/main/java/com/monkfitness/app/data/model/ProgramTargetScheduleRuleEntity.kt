package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One stored **target schedule rule**: the explicit rule a revision states, field for field.
 *
 * A revision's legacy `ProgramSchedule` states *when its slots fall*. A target rule states something
 * wider — which rule it is, which workout it produces, how often it recurs, the date it is anchored to
 * and, for a derived rule, the rule it derives from — so the two are stored separately and neither is
 * derived from the other: no column here could mirror a legacy schedule.
 *
 * A cadence is a closed choice of five forms and each form has exactly one payload, stored in its own
 * column so that reconstructing a cadence is a column fetch per field rather than a parse, and so that
 * a form whose payload is absent cannot be confused with a form whose payload is zero. The init guard
 * holds the discriminator and its payload consistent — the form that requires a payload requires it,
 * the forms that have none carry none — exactly as `program_revision`'s guard holds `scheduleType` and
 * its weekday set.
 *
 * `cadenceWeekdays` is a deterministic `TEXT` value in canonical order (ascending ISO weekday), because
 * a `Set<DayOfWeek>` has no order of its own: the same set always writes the same bytes and two equal
 * cadences compare equal. This follows the schema's existing `program_revision.scheduleWeekdays`
 * convention.
 *
 * Membership is `(revisionId, ruleId)` and that pair **is** the primary key: one revision may state a
 * rule once, two revisions may state the same rule identity independently, and a stored rule can never
 * be re-pointed at another revision by a write. The revision's foreign key cascades, so a rule row
 * cannot outlive the immutable revision that states it.
 *
 * Nothing here is updated or deleted: a revision's target semantics are written once, and a structural
 * change saves a new revision instead.
 *
 * @property cadenceType the cadence form, spelled as the domain subtype's own name in enum convention.
 * @property cadenceDays `EVERY_N_DAYS`' interval, or `null` for every other form.
 * @property cadenceSessionsPerWeek `SESSIONS_PER_WEEK`' frequency, or `null` for every other form.
 * @property cadenceWeekdays `FIXED_WEEKDAYS`' canonical day names, or `null` for every other form.
 * @property cadenceSourceRuleId `DERIVED_EXCLUDING`' source rule identity, verbatim, or `null`.
 * @property anchorDate the date the rule is anchored to, `YYYY-MM-DD`.
 */
@Entity(
    tableName = "program_target_schedule_rule",
    primaryKeys = ["revisionId", "ruleId"],
    foreignKeys = [
        ForeignKey(
            entity = ProgramRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
    // No declared index: the `(revisionId, ruleId)` primary key already indexes `revisionId` as its
    // leading column, which is the only column this table is ever read by.
)
data class ProgramTargetScheduleRuleEntity(
    val revisionId: String,
    val ruleId: String,
    val workoutId: String,
    val cadenceType: String,
    val cadenceDays: Int? = null,
    val cadenceSessionsPerWeek: Int? = null,
    val cadenceWeekdays: String? = null,
    val cadenceSourceRuleId: String? = null,
    val anchorDate: String
) {

    init {
        require(revisionId.isNotBlank()) {
            "a stored target schedule rule needs the revision that states it"
        }
        require(ruleId.isNotBlank()) { "a stored target schedule rule needs a rule identity" }
        require(workoutId.isNotBlank()) { "a stored target schedule rule needs a workout identity" }
        when (cadenceType) {
            DAILY -> requireNoCadencePayload("DAILY")
            EVERY_N_DAYS -> requireOnlyPayload(
                form = EVERY_N_DAYS,
                present = cadenceDays != null,
                absent = listOf(cadenceSessionsPerWeek, cadenceWeekdays, cadenceSourceRuleId)
            )
            SESSIONS_PER_WEEK -> requireOnlyPayload(
                form = SESSIONS_PER_WEEK,
                present = cadenceSessionsPerWeek != null,
                absent = listOf(cadenceDays, cadenceWeekdays, cadenceSourceRuleId)
            )
            FIXED_WEEKDAYS -> requireOnlyPayload(
                form = FIXED_WEEKDAYS,
                present = !cadenceWeekdays.isNullOrBlank(),
                absent = listOf(cadenceDays, cadenceSessionsPerWeek, cadenceSourceRuleId)
            )
            DERIVED_EXCLUDING -> requireOnlyPayload(
                form = DERIVED_EXCLUDING,
                present = !cadenceSourceRuleId.isNullOrBlank(),
                absent = listOf(cadenceDays, cadenceSessionsPerWeek, cadenceWeekdays)
            )
            // An unknown token is refused by the mapper, which owns the vocabulary — the same rule
            // `program_revision` follows for `scheduleType`.
            else -> Unit
        }
    }

    private fun requireNoCadencePayload(form: String) {
        require(cadenceDays == null && cadenceSessionsPerWeek == null && cadenceWeekdays == null &&
            cadenceSourceRuleId == null
        ) {
            "a $form cadence carries no payload, but this stored rule has one"
        }
    }

    private fun requireOnlyPayload(form: String, present: Boolean, absent: List<Any?>) {
        require(present) { "a $form cadence names its payload, and this stored rule has none" }
        require(absent.all { it == null }) {
            "a $form cadence stores only its own payload, but this stored rule has another too"
        }
    }

    companion object {
        /** The cadence form that produces an occurrence on every eligible date. */
        const val DAILY: String = "DAILY"

        /** The cadence form that recurs on a fixed interval of days from the anchor. */
        const val EVERY_N_DAYS: String = "EVERY_N_DAYS"

        /** The cadence form that names a sessions-per-week frequency — never an interval alias. */
        const val SESSIONS_PER_WEEK: String = "SESSIONS_PER_WEEK"

        /** The cadence form that names literal weekdays. */
        const val FIXED_WEEKDAYS: String = "FIXED_WEEKDAYS"

        /** The cadence form that subtracts another, named, rule's occurrences. */
        const val DERIVED_EXCLUDING: String = "DERIVED_EXCLUDING"
    }
}
