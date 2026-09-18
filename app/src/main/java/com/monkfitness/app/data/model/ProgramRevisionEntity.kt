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
 *    frequency. The frequency itself is chosen by the Scheduler and is not stored here.
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
    val createdAt: Long
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
            FIXED_WEEKDAYS -> require(!scheduleWeekdays.isNullOrEmpty()) {
                "a $FIXED_WEEKDAYS schedule names its weekdays: scheduleWeekdays was $scheduleWeekdays"
            }
            FLEXIBLE_PER_WEEK -> require(scheduleWeekdays == null) {
                "a $FLEXIBLE_PER_WEEK schedule is a deterministic frequency, not named weekdays, " +
                    "but scheduleWeekdays was $scheduleWeekdays"
            }
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
    }
}
