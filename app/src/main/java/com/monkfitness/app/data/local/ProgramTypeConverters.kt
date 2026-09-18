package com.monkfitness.app.data.local

import androidx.room.TypeConverter
import java.time.DayOfWeek

/**
 * The stored representation of the three collections the Program System schema declares.
 *
 * Two rules run through all of them. The form must be **deterministic** — the same value always stores
 * the same bytes, whatever order the caller happened to build it in, so a stored row can be compared,
 * diffed and re-derived without ambiguity. And it must be **lossless**: a per-set prescription is a
 * list of targets, one per set, and `12 / 10 / 8 / 6` cannot be recovered from a single `10`, which is
 * precisely why §10 requires the plan to record what each set asks for.
 *
 * These are converters, not a vocabulary: nothing here maps a name to different text, decides what a
 * prescription means, or gives an id a shape. Spelled-out enums are stored as their own names by the
 * mapper layer, and the values here are opaque strings and integers that the data layer does not
 * interpret.
 *
 * Visible to the unit tests on purpose: this is the code that decides whether a per-set prescription
 * survives storage, and it is pinned there token for token.
 */
class ProgramTypeConverters {

    /**
     * Stores a per-set prescription — repetitions or seconds — as its targets in set order.
     *
     * The list is the source of truth for the set count as well, so no separate "how many sets" column
     * can contradict it, and a collapsed scalar is not representable: `[12, 10, 8, 6]` stores four
     * numbers, in the order the sets are presented (§10).
     */
    @TypeConverter
    fun toPerSetTargets(targets: List<Int>): String {
        require(targets.isNotEmpty()) {
            "a prescription composes at least one set; an empty target list is not a prescription"
        }
        return targets.joinToString(SEPARATOR)
    }

    @TypeConverter
    fun fromPerSetTargets(value: String): List<Int> =
        value.split(SEPARATOR).map { target ->
            target.trim().toIntOrNull() ?: throw IllegalArgumentException(
                "a stored prescription target must be an integer, was '$target' in '$value'"
            )
        }

    /**
     * Stores a fixed-weekday schedule as its day names in ascending week order, so Monday-first is the
     * only order this column ever holds. A weekday set has no meaningful insertion order — the same
     * three days are the same schedule whichever way they were added — and storing them in the order a
     * caller happened to build the set in would make two equal schedules compare unequal.
     */
    @TypeConverter
    fun toScheduleWeekdays(weekdays: Set<DayOfWeek>): String =
        weekdays.sortedBy { it.value }.joinToString(SEPARATOR) { it.name }

    @TypeConverter
    fun fromScheduleWeekdays(value: String): Set<DayOfWeek> =
        if (value.isBlank()) {
            emptySet()
        } else {
            value.split(SEPARATOR).map { name ->
                DayOfWeek.entries.firstOrNull { it.name == name.trim() }
                    ?: throw IllegalArgumentException(
                        "a stored weekday must be a day name, was '$name' in '$value'"
                    )
            }.toSet()
        }

    /**
     * Stores the adjustments a snapshot had already applied, in application order.
     *
     * Order is part of the record — two adjustments applied in the other order are a different
     * presentation — and an empty capture is stored as the empty string rather than `null`, so "no
     * adaptive change" is a value and not a missing one.
     */
    @TypeConverter
    fun toAppliedAdjustmentIds(adjustmentIds: List<String>): String {
        require(adjustmentIds.none { it.contains(SEPARATOR) }) {
            "an adjustment id may not contain '$SEPARATOR': $adjustmentIds"
        }
        return adjustmentIds.joinToString(SEPARATOR)
    }

    @TypeConverter
    fun fromAppliedAdjustmentIds(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(SEPARATOR).map { it.trim() }

    private companion object {
        /** The list separator. Names, day names and ids may not contain it. */
        const val SEPARATOR = ","
    }
}
