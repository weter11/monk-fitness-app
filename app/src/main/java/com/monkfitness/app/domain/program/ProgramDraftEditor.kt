package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.prescription.Prescription

/**
 * Where a draft's working identities come from (§26: *identity generation is injectable*).
 *
 * A draft addresses its days and its plan elements by id, so the editor's operations that *add*
 * something need one. The generator is a port rather than a call so that the editor stays pure — it
 * imports nothing but the domain and `kotlin.` — and so that a test can supply a sequence it can read
 * back. Identity minted here is the draft's own handle and is **never** persisted: `Save` re-mints
 * every day and element identity into the revision it writes (§6, §23).
 */
fun interface DraftIdSource {

    /** A new identity for one draft day or one draft plan element. */
    fun newId(): String
}

/**
 * The editor's **edit** step, as a value: every operation answers with the next draft.
 *
 * The editor is draft-first in the literal sense (§6, §7): `Generate`, `Regenerate`, and here every
 * manual change, alter only a draft, and a revision appears only when `Save` is asked for one. That
 * property is not a convention about how the code is written — it is this class's shape. There is no
 * mutating method, no `var`, and no reference to a `Program`, a `ProgramRevision` or a repository
 * anywhere in it: an operation takes a draft and returns a draft, so the only way to reach storage is
 * through [com.monkfitness.app.domain.usecase.ProgramEditorService.save].
 *
 * The operations are *total* for every addressable target and *partial* only where the domain itself
 * is: a draft that does not hold the day an operation names is a caller bug and fails loudly, and a
 * rest day may not be produced with elements still in it (§20) — the user removes them first, one
 * deliberate action per element, instead of losing them to an implicit tidy-up (§33).
 *
 * Adding an exercise never checks it against anything: a manual program is free-form, its
 * prescriptions are unrestricted by generator ranges, and the same exercise may appear as often as
 * the user wants as long as every occurrence keeps its own identity (§2, §9, §10). That is why
 * [duplicatingExercise] exists at all: repeating an exercise is an ordinary plan edit, not a
 * conflict.
 *
 * @param draft the draft this editor is editing.
 * @param ids where the identities of newly added days and elements come from.
 */
class ProgramDraftEditor(val draft: ProgramEditorDraft, private val ids: DraftIdSource) {

    // ---------------------------------------------------------------- the Program's own facts

    /** The working name. A name that is blank is a draft state, not an error — validation's job. */
    fun renamed(name: String): ProgramDraftEditor = next(draft.copy(name = name))

    /** The working description. Not a structural change (§6). */
    fun described(description: String): ProgramDraftEditor = next(draft.copy(description = description))

    /** The working mode. Changing it *is* structural, and §2 makes the switch explicit. */
    fun withMode(mode: ProgramMode): ProgramDraftEditor = next(draft.copy(mode = mode))

    /** The working duration (§20). */
    fun withDuration(duration: ProgramDuration): ProgramDraftEditor = next(draft.copy(duration = duration))

    /** The working schedule (§20). */
    fun withSchedule(schedule: ProgramSchedule): ProgramDraftEditor = next(draft.copy(schedule = schedule))

    // ---------------------------------------------------------------- days

    /**
     * Appends a day, or inserts it at [at] (1-based, `1..dayCount + 1`).
     *
     * A day added as [ProgramDayType.REST] starts empty, which is the only shape a rest day may have
     * (§20); any other type starts empty too and is what validation refuses to save until it plans
     * something.
     */
    fun addingDay(
        type: ProgramDayType,
        name: String? = null,
        at: Int? = null
    ): ProgramDraftEditor {
        val plan = draft.days
        val index = insertionIndex(at, plan.size, what = "day")
        val added = ProgramDay(
            programDayId = ProgramDayId(ids.newId()),
            position = index + 1,
            type = type,
            name = name
        )
        return next(draft.copy(days = plan.insertedAt(index, added).renumbered()))
    }

    /** Removes the day with [programDayId]. Its elements go with it — the user removed the day. */
    fun removingDay(programDayId: ProgramDayId): ProgramDraftEditor {
        planDayOrFail(programDayId)
        return next(draft.copy(days = draft.days.removing { it.programDayId == programDayId }.renumbered()))
    }

    /** Moves the day with [programDayId] to [toPosition] (1-based). */
    fun movingDay(programDayId: ProgramDayId, toPosition: Int): ProgramDraftEditor {
        val plan = draft.days
        val day = planDayOrFail(programDayId)
        val index = insertionIndex(toPosition, plan.size, what = "day")
        return next(draft.copy(days = plan.removing { it.programDayId == programDayId }.insertedAt(index, day).renumbered()))
    }

    /** Names (or un-names, with `null`) the day with [programDayId]. */
    fun renamingDay(programDayId: ProgramDayId, name: String?): ProgramDraftEditor {
        planDayOrFail(programDayId)
        return next(draft.copy(days = draft.days.mappingDay(programDayId) { it.copy(name = name) }))
    }

    /**
     * Changes what kind of day [programDayId] is.
     *
     * Refused when the day still plans something and the new type is [ProgramDayType.REST], because a
     * rest day prescribes no exercises (§20) and dropping the user's elements to make that true would
     * be exactly the silent overwrite §33 forbids. The user's path is to remove them, one at a time.
     */
    fun retypingDay(programDayId: ProgramDayId, type: ProgramDayType): ProgramDraftEditor {
        val day = planDayOrFail(programDayId)
        require(type != ProgramDayType.REST || day.exercises.isEmpty()) {
            "day '${programDayId.value}' plans ${day.exercises.size} element(s), and a REST day " +
                "prescribes none (§20): remove them first, or keep the day a work day"
        }
        return next(draft.copy(days = draft.days.mappingDay(programDayId) { it.copy(type = type) }))
    }

    // ---------------------------------------------------------------- plan elements

    /**
     * Adds one occurrence of [exerciseId] to the day with [programDayId], at [at] (1-based) or last.
     *
     * The occurrence is [ProgramExerciseOrigin.USER_AUTHORED] by default, because the user is who put
     * it there: a manual plan belongs to its author, and an element the generator produced is the
     * generator's (§7, §9). The exercise is used as the user typed it — no range, no catalogue and no
     * equipment check — and a second occurrence of the same exercise is a second element with its own
     * identity.
     */
    fun addingExercise(
        programDayId: ProgramDayId,
        exerciseId: String,
        prescription: Prescription,
        origin: ProgramExerciseOrigin = ProgramExerciseOrigin.USER_AUTHORED,
        isPinned: Boolean = false,
        at: Int? = null
    ): ProgramDraftEditor {
        val day = planDayOrFail(programDayId)
        val index = insertionIndex(at, day.exercises.size, what = "plan element")
        val element = ProgramExercise(
            programExerciseId = ProgramExerciseId(ids.newId()),
            exerciseId = exerciseId,
            prescription = prescription,
            origin = origin,
            isPinned = isPinned
        )
        return next(
            draft.copy(
                days = draft.days.mappingDay(programDayId) {
                    it.copy(exercises = it.exercises.insertedAt(index, element))
                }
            )
        )
    }

    /**
     * Repeats the element with [programExerciseId] directly after itself, as a new occurrence (§9).
     *
     * The copy keeps the exercise, the prescription and the pin — that is what repeating an element
     * means — and gets its own identity, so changing one of the two later cannot change the other.
     */
    fun duplicatingExercise(
        programDayId: ProgramDayId,
        programExerciseId: ProgramExerciseId
    ): ProgramDraftEditor {
        val day = planDayOrFail(programDayId)
        val index = day.exercises.indexOfFirst { it.programExerciseId == programExerciseId }
        require(index >= 0) {
            "day '${programDayId.value}' holds no plan element '${programExerciseId.value}'"
        }
        val copy = day.exercises[index].copy(
            programExerciseId = ProgramExerciseId(ids.newId())
        )
        return next(
            draft.copy(
                days = draft.days.mappingDay(programDayId) {
                    it.copy(exercises = it.exercises.insertedAt(index + 1, copy))
                }
            )
        )
    }

    /** Removes one occurrence — the one named, never every use of that exercise (§9). */
    fun removingExercise(
        programDayId: ProgramDayId,
        programExerciseId: ProgramExerciseId
    ): ProgramDraftEditor {
        planElementOrFail(programDayId, programExerciseId)
        return next(
            draft.copy(
                days = draft.days.mappingDay(programDayId) {
                    it.copy(exercises = it.exercises.removing { element -> element.programExerciseId == programExerciseId })
                }
            )
        )
    }

    /** Moves one occurrence of the day with [programDayId] to [toPosition] (1-based). */
    fun movingExercise(
        programDayId: ProgramDayId,
        programExerciseId: ProgramExerciseId,
        toPosition: Int
    ): ProgramDraftEditor {
        val element = planElementOrFail(programDayId, programExerciseId)
        val day = planDayOrFail(programDayId)
        val index = insertionIndex(toPosition, day.exercises.size, what = "plan element")
        return next(
            draft.copy(
                days = draft.days.mappingDay(programDayId) {
                    it.copy(
                        exercises = it.exercises
                            .removing { each -> each.programExerciseId == programExerciseId }
                            .insertedAt(index, element)
                    )
                }
            )
        )
    }

    /** Sets what one occurrence prescribes, per set, in its dimension (§10). */
    fun settingPrescription(
        programDayId: ProgramDayId,
        programExerciseId: ProgramExerciseId,
        prescription: Prescription
    ): ProgramDraftEditor {
        planElementOrFail(programDayId, programExerciseId)
        return next(
            draft.copy(
                days = draft.days.mappingDay(programDayId) {
                    it.copy(
                        exercises = it.exercises.map { element ->
                            if (element.programExerciseId == programExerciseId) {
                                element.copy(prescription = prescription)
                            } else {
                                element
                            }
                        }
                    )
                }
            )
        )
    }

    /**
     * Pins or unpins one occurrence (§7).
     *
     * Unpinning does not change the value — it only makes the element eligible for a future
     * automatic change — which is why this is a plan edit and why it is structural (§6).
     */
    fun settingPinned(
        programDayId: ProgramDayId,
        programExerciseId: ProgramExerciseId,
        isPinned: Boolean
    ): ProgramDraftEditor {
        planElementOrFail(programDayId, programExerciseId)
        return next(
            draft.copy(
                days = draft.days.mappingDay(programDayId) {
                    it.copy(
                        exercises = it.exercises.map { element ->
                            if (element.programExerciseId == programExerciseId) {
                                element.copy(isPinned = isPinned)
                            } else {
                                element
                            }
                        }
                    )
                }
            )
        )
    }

    // ---------------------------------------------------------------- the mechanism

    private fun next(draft: ProgramEditorDraft): ProgramDraftEditor = ProgramDraftEditor(draft, ids)

    private fun planDayOrFail(programDayId: ProgramDayId): ProgramDay =
        draft.days.firstOrNull { it.programDayId == programDayId }
            ?: throw IllegalArgumentException(
                "this draft holds no day '${programDayId.value}'; it holds " +
                    "${draft.days.map { it.programDayId.value }}"
            )

    private fun planElementOrFail(
        programDayId: ProgramDayId,
        programExerciseId: ProgramExerciseId
    ): ProgramExercise = planDayOrFail(programDayId).exercises
        .firstOrNull { it.programExerciseId == programExerciseId }
        ?: throw IllegalArgumentException(
            "day '${programDayId.value}' holds no plan element '${programExerciseId.value}'; it " +
                "holds ${planDayOrFail(programDayId).exercises.map { it.programExerciseId.value }}"
        )
}

/**
 * The days renumbered `1..n` in their current order — the numbering a revision must hold (§6).
 *
 * Every edit that adds, removes or moves a day renumbers, and `Save` renumbers again before it mints
 * a revision, so a plan's positions are always a consequence of its order rather than a second fact
 * that can disagree with it.
 */
internal fun List<ProgramDay>.renumbered(): List<ProgramDay> =
    mapIndexed { index, day -> if (day.position == index + 1) day else day.copy(position = index + 1) }

/** This draft with its days renumbered `1..n`, which is the shape `Save` persists. */
internal fun ProgramEditorDraft.withRenumberedDays(): ProgramEditorDraft =
    copy(days = days.renumbered())

/**
 * The index [position] (1-based, counted among `size` items) refers to.
 *
 * A position is offered to the user as "at 2 of 3" or "at the end", so `size + 1` — one past the
 * last item — is a legitimate insert; anything outside that range is a caller bug, and it is
 * reported with the value that was out of range instead of being clamped into a silent surprise.
 */
private fun insertionIndex(position: Int?, size: Int, what: String): Int {
    if (position == null) return size
    require(position in 1..size + 1) {
        "a $what is inserted at 1..${size + 1}, so the plan may grow; got $position"
    }
    return position - 1
}

/**
 * A list with [item] inserted at [index] — built by concatenation, so nothing here is mutable state
 * a caller could later reach into.
 */
private fun <T> List<T>.insertedAt(index: Int, item: T): List<T> =
    take(index) + item + drop(index)

private fun <T> List<T>.removing(predicate: (T) -> Boolean): List<T> =
    filterNot { predicate(it) }

private fun List<ProgramDay>.mappingDay(
    programDayId: ProgramDayId,
    change: (ProgramDay) -> ProgramDay
): List<ProgramDay> = map { day -> if (day.programDayId == programDayId) change(day) else day }
