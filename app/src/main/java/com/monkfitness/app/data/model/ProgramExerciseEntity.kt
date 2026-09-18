package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One occurrence of one exercise in a day's plan (§23 `ProgramExercise`, §9).
 *
 * It is an **occurrence**, not a library entry: [exerciseId] is the Exercise Library's opaque key and
 * is deliberately not a foreign key, because the library is a global catalogue the data layer does not
 * own. No metadata of that catalogue — name, equipment, muscles, animation — is copied here, and none
 * ever should be: §10 is explicit that editing what a program prescribes must not touch exercise
 * metadata.
 *
 * The identity is the occurrence, which is what makes repeated use legal: the same exercise used twice
 * in a day is two rows with two [programExerciseId]s and two positions, so pinning, overriding or
 * adapting one of them cannot silently change the other. Nothing in this table is unique on
 * `exerciseId` — that constraint would forbid the repetition §9 allows.
 *
 * The prescription belongs to the plan and is stored per set, next to the dimension it progresses in:
 * `12 / 10 / 8 / 6` and `30 / 30 / 45` are one prescription each, not a uniform target with overrides,
 * so [perSetTargets] holds the ordered list of every set's target and a single scalar column would lose
 * information that cannot be recovered (§10). The dimension is one of the five §10 names — including
 * the three this stage deliberately does not implement, which are representable here without any
 * algorithm being invented for them.
 *
 * @property programExerciseId identity of this occurrence.
 * @property programDayId the day that owns it; the foreign key cascades.
 * @property position 1-based order of this occurrence within its day; unique within that day.
 * @property exerciseId the library key of the exercise performed here, opaque to this layer.
 * @property prescriptionDimension the dimension the prescription is written in (token column).
 * @property perSetTargets the prescribed target of every set, in set order, in that dimension's unit.
 * @property origin `GENERATED` when the generator produced it and `USER_AUTHORED` when the user did
 *   (token column) — the two facts regeneration reconciles on, next to the pin.
 * @property isPinned whether the occurrence is exempt from automatic change.
 */
@Entity(
    tableName = "program_exercise",
    foreignKeys = [
        ForeignKey(
            entity = ProgramDayEntity::class,
            parentColumns = ["programDayId"],
            childColumns = ["programDayId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["programDayId", "position"], unique = true)]
)
data class ProgramExerciseEntity(
    @PrimaryKey val programExerciseId: String,
    val programDayId: String,
    val position: Int,
    val exerciseId: String,
    val prescriptionDimension: String,
    val perSetTargets: List<Int>,
    val origin: String,
    val isPinned: Boolean = false
)
