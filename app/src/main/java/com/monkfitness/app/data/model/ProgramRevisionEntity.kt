package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.DayOfWeek

/**
 * One immutable revision of a Program's structure (§6, §23 `ProgramRevision`).
 *
 * The revision is the saved plan: its identity ([revisionId]), the Program it belongs to, a
 * human-readable [revisionNumber], the mode, how long it runs, when its slots fall, and when it was
 * saved. The plan itself lives in [ProgramDayEntity] and [ProgramExerciseEntity], one level down,
 * because a day and an occurrence are their own rows with their own identities.
 *
 * The identity rule of §23 is held here: **identity is `revisionId`**, a minted id, never the legacy
 * revision integer and never [revisionNumber] — the number counts from 1 for the user's benefit and is
 * unique only within its Program, which is what the `(programId, revisionNumber)` unique index states.
 *
 * Two structural facts are split into a discriminator plus its payload, because the domain models each
 * as a closed choice of exactly two forms (§20) and a database column cannot hold the choice itself:
 *
 *  * `durationType` — `FIXED_DAYS` with a day count in `durationDays`, or `INDEFINITE` with no total.
 *    The distinction is load-bearing: an indefinite program has no length, and reporting a fake
 *    `N / 30` for it is exactly what §21 forbids, so the two forms may not be pooled into one number.
 *  * `scheduleType` — `FIXED_WEEKDAYS` naming its weekdays, or `FLEXIBLE_PER_WEEK` with a deterministic
 *    frequency in `scheduleSessionsPerWeek`. Which frequency a user should train at is the Scheduler's
 *    decision (§20) — but the number it decided on is revision content, exactly as pattern, duration and
 *    the plan are (§6): a revision that knew it was a flexible-frequency plan without knowing *how
 *    many* sessions a week it holds would not describe the plan it is the record of, and the domain
 *    value it stands for (`ProgramSchedule.FlexiblePerWeek`) carries the number as a required field.
 *    The column is nullable because only one of the two forms has a frequency, and it carries no
 *    default: inventing one would state a weekly frequency the user never chose, for a row whose
 *    schedule is a fixed weekday set and therefore has none at all. The pair is held consistent by the
 *    guard below, the way `durationType` and `durationDays` are.
 *
 * The discriminator tokens are the domain subtypes' own names in enum convention (`FixedDays` →
 * `FIXED_DAYS`); `ProgramSchemaTest` asserts that correspondence rather than trusting it.
 *
 * Nothing about the current cycle, the current day or a session date appears here: a revision is a
 * plan, it is never "current", and §1 forbids those as ownership facts. The row is written once and
 * never updated — a structural change saves a new revision instead (§6).
 *
 * @property revisionId identity of this revision.
 * @property programId the Program that owns it; the foreign key cascades, so the revision is destroyed
 *   with its Program and with nothing else (§29).
 * @property revisionNumber the human-readable ordinal, 1-based, unique within the Program.
 * @property mode `MANUAL` or `GENERATED` (token column). Switching it is a structural change, which is
 *   why it lives on the revision and not on the Program (§2).
 * @property durationType `FIXED_DAYS` or `INDEFINITE` (token column).
 * @property durationDays the length of a `FIXED_DAYS` revision; `null` for an indefinite one.
 * @property scheduleType `FIXED_WEEKDAYS` or `FLEXIBLE_PER_WEEK` (token column).
 * @property scheduleWeekdays the named weekdays of a `FIXED_WEEKDAYS` revision, canonical ascending
 *   order, or `null` when the schedule is a frequency instead.
 * @property createdAt when this revision was saved, in epoch milliseconds.
 * @property scheduleSessionsPerWeek the deterministic sessions-per-week frequency of a
 *   `FLEXIBLE_PER_WEEK` revision, `1..7`, or `null` when the schedule names its weekdays instead.
 *   Declared last on purpose: the column is appended to an existing table by `MIGRATION_8_9`, and
 *   keeping the declaration order equal to the physical order means a freshly created database and an
 *   upgraded one hold byte-identical table definitions.
 * @property focusGoal the Goal of the revision's Goals & Focus configuration (§8), spelled as the
 *   domain enum member's own name — or `null`, which means `BALANCED`. `null` is the honest reading
 *   of a row written before Goals & Focus existed: the user stated no goal and no share, and that is
 *   exactly what `BALANCED` says. The column carries no default for the same reason a default would
 *   state a goal the user never chose; it is appended by `MIGRATION_9_10` and declared last, like the
 *   frequency above.
 * @property focusTargets the focuses the configuration states, as one deterministic `TEXT` value:
 *   the focus names in canonical order for a `FOCUSED` revision, and `NAME:percent` entries for a
 *   `CUSTOM` one — never for a `BALANCED` revision, which states nothing and stores `null`. It is a
 *   `String` and not a converted `List<String>` because Room already declares a `List<String> ⇄
 *   String` conversion for the snapshot's applied adjustments, and a second one would be ambiguous;
 *   the token form therefore belongs to the mapper, which owns the discriminator that gives the
 *   tokens their meaning — exactly as `prescriptionDimension` says what `perSetTargets`' numbers are.
 *   One column and not a table, because a focus configuration is a handful of tokens belonging to
 *   exactly one immutable revision: a second row-shaped entity would model a relation that does not
 *   exist.
 */
@Entity(
    tableName = "program_revision",
    foreignKeys = [
        ForeignKey(
            entity = ProgramEntity::class,
            parentColumns = ["programId"],
            childColumns = ["programId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["programId", "revisionNumber"], unique = true)]
)
data class ProgramRevisionEntity(
    @PrimaryKey val revisionId: String,
    val programId: String,
    val revisionNumber: Int,
    val mode: String,
    val durationType: String,
    val durationDays: Int? = null,
    val scheduleType: String,
    val scheduleWeekdays: Set<DayOfWeek>? = null,
    val createdAt: Long,
    val scheduleSessionsPerWeek: Int? = null,
    val focusGoal: String? = null,
    val focusTargets: String? = null
) {

    init {
        require(revisionNumber >= FIRST_REVISION_NUMBER) {
            "revision numbering starts at $FIRST_REVISION_NUMBER, was $revisionNumber"
        }
        when (durationType) {
            FIXED_DAYS -> require(durationDays != null) {
                "a $FIXED_DAYS revision names how many days it runs: durationDays was null"
            }
            INDEFINITE -> {
                require(durationDays == null) {
                    "an $INDEFINITE revision has no total, so it carries no durationDays, was " +
                        "$durationDays (§21)"
                }
            }
        }
        when (scheduleType) {
            FIXED_WEEKDAYS -> {
                require(!scheduleWeekdays.isNullOrEmpty()) {
                    "a $FIXED_WEEKDAYS schedule names its weekdays: scheduleWeekdays was " +
                        "$scheduleWeekdays"
                }
                require(scheduleSessionsPerWeek == null) {
                    "a $FIXED_WEEKDAYS schedule has no frequency to store — the frequency belongs to " +
                        "$FLEXIBLE_PER_WEEK — but scheduleSessionsPerWeek was $scheduleSessionsPerWeek"
                }
            }
            FLEXIBLE_PER_WEEK -> {
                require(scheduleWeekdays == null) {
                    "a $FLEXIBLE_PER_WEEK schedule is a deterministic frequency, not named weekdays, " +
                        "but scheduleWeekdays was $scheduleWeekdays"
                }
                val frequency = requireNotNull(scheduleSessionsPerWeek) {
                    "a $FLEXIBLE_PER_WEEK schedule stores the sessions-per-week frequency the " +
                        "Scheduler decided on; scheduleSessionsPerWeek was null (§20)"
                }
                require(frequency in SESSIONS_PER_WEEK_RANGE) {
                    "sessionsPerWeek must be within $SESSIONS_PER_WEEK_RANGE, was $frequency " +
                        "(the range the domain's flexible-weekly-frequency schedule accepts)"
                }
            }
        }
        when (focusGoal) {
            null -> require(focusTargets == null) {
                "a revision that states no goal — $focusGoal, which reads as the domain's " +
                    "$BALANCED_GOAL — stores no focus targets, was $focusTargets (§8)"
            }
            FOCUSED, CUSTOM -> require(!focusTargets.isNullOrEmpty()) {
                "a $focusGoal revision stores the focuses it is built around; focusTargets was " +
                    "$focusTargets (§8)"
            }
            else -> Unit // an unknown token is refused by the mapper, which owns the vocabulary (§25)
        }
    }

    companion object {
        /** The first revision of a Program — every Program is created with one (§27). */
        const val FIRST_REVISION_NUMBER: Int = 1

        /** The discriminator of a revision with a known number of days. */
        const val FIXED_DAYS: String = "FIXED_DAYS"

        /** The discriminator of a revision with no end date. */
        const val INDEFINITE: String = "INDEFINITE"

        /** The discriminator of a schedule naming its weekdays. */
        const val FIXED_WEEKDAYS: String = "FIXED_WEEKDAYS"

        /** The discriminator of a deterministic sessions-per-week schedule. */
        const val FLEXIBLE_PER_WEEK: String = "FLEXIBLE_PER_WEEK"

        /**
         * The discriminator of a revision whose user named the focuses to train (§8's `FOCUSED`),
         * spelled as the domain `Goal` member's own name — `ProgramSchemaTest` asserts that
         * correspondence rather than trusting it, exactly as it does for the duration and schedule
         * discriminators above.
         */
        const val FOCUSED: String = "FOCUSED"

        /** The discriminator of a revision whose user stated every focus's share (§8's `CUSTOM`). */
        const val CUSTOM: String = "CUSTOM"

        /**
         * What a `null` focus discriminator means: §8's `BALANCED`, the configuration that states
         * nothing. Named here so the guard's message says it out loud, and kept as a token rather than
         * as a stored value — nothing is written for a revision that states no goal.
         */
        const val BALANCED_GOAL: String = "BALANCED"

        /**
         * The frequencies a `FLEXIBLE_PER_WEEK` revision may store — the range the domain's
         * `ProgramSchedule.FlexiblePerWeek` accepts, kept identical to it rather than re-decided here.
         * `ProgramSchemaTest` asserts the two agree value for value.
         */
        val SESSIONS_PER_WEEK_RANGE: IntRange = 1..7
    }
}
