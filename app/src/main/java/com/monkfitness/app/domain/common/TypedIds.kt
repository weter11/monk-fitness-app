package com.monkfitness.app.domain.common

/**
 * Every identifier is a distinct inline value class, so an id can only be passed where its own type
 * is expected — a revision id handed to a slot lookup is unrepresentable rather than a bug that
 * compiles when every id is a `String`.
 *
 * Three rules: the wrapped value is opaque (never parsed, never another entity's vocabulary); a
 * blank id is not representable, because an id that identifies nothing is a construction bug, not
 * a sentinel; generation and uniqueness belong to the storage layer or an injected generator, so
 * tests can supply deterministic ids.
 */

/** Identity of a saved Program; stable for the whole life of that Program. */
@JvmInline
value class ProgramId(val value: String) {
    init {
        require(value.isNotBlank()) { "a ProgramId must identify a program" }
    }
}

/** Identity of one immutable ProgramRevision; a structural edit mints a new one. */
@JvmInline
value class RevisionId(val value: String) {
    init {
        require(value.isNotBlank()) { "a RevisionId must identify a revision" }
    }
}

/** Identity of one ProgramDay inside a revision — never the day's number or date. */
@JvmInline
value class ProgramDayId(val value: String) {
    init {
        require(value.isNotBlank()) { "a ProgramDayId must identify a program day" }
    }
}

/**
 * Identity of one occurrence of an exercise in a day's plan. An exercise used twice in one day is
 * two values with two distinct ids, so adjusting one occurrence never affects the other.
 */
@JvmInline
value class ProgramExerciseId(val value: String) {
    init {
        require(value.isNotBlank()) { "a ProgramExerciseId must identify a program exercise" }
    }
}

/** Identity of one planned opportunity to train — the scheduler's unit. */
@JvmInline
value class SlotId(val value: String) {
    init {
        require(value.isNotBlank()) { "a SlotId must identify a workout slot" }
    }
}

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "a SessionId must identify a workout session" }
    }
}

@JvmInline
value class SessionExerciseId(val value: String) {
    init {
        require(value.isNotBlank()) { "a SessionExerciseId must identify a session exercise" }
    }
}

/** Identity of one confirmed set — the granular record the adaptive layer reads. */
@JvmInline
value class SetLogId(val value: String) {
    init {
        require(value.isNotBlank()) { "a SetLogId must identify a set log" }
    }
}

@JvmInline
value class PauseId(val value: String) {
    init {
        require(value.isNotBlank()) { "a PauseId must identify a program pause" }
    }
}

/** Identity of one adaptive adjustment — one slot-scoped change. */
@JvmInline
value class AdjustmentId(val value: String) {
    init {
        require(value.isNotBlank()) { "an AdjustmentId must identify an adaptive adjustment" }
    }
}

/**
 * Identity of one adaptive decision, including the decisions the aggregate load guard filtered out
 * and still recorded with a `NOT_APPLIED` outcome.
 */
@JvmInline
value class DecisionId(val value: String) {
    init {
        require(value.isNotBlank()) { "a DecisionId must identify an adaptive decision" }
    }
}
