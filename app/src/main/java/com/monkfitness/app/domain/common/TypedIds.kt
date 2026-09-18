package com.monkfitness.app.domain.common

/**
 * The identity vocabulary of the Program System.
 *
 * Every identifier the target architecture names is a **distinct value type**, so an id can only
 * ever be passed where its own type is expected: a [ProgramId] is not a [RevisionId], a [SlotId] is
 * not a [SessionId], and no amount of string equality makes one usable as another. That is the
 * whole point of the file — the failure mode it removes is a mix-up that compiles when every id is
 * a `String` (a revision id handed to a slot lookup, a pause id stored as a session id).
 *
 * The classes are inline value classes, so the type safety costs nothing at runtime: the wrapped
 * string *is* the value, and identity is not re-derived anywhere in the domain.
 *
 * Three rules hold for every id here, and nothing else:
 *
 *  * the wrapped value is **opaque** — the domain never parses it, never assumes its shape, and
 *    never reuses one entity's vocabulary for another's id (a date, a cycle number or a day number
 *    is explicitly *not* an identifier — §1);
 *  * **blankness is not representable** — an id that identifies nothing is a bug at construction
 *    time, not a sentinel to be detected later;
 *  * **generation belongs elsewhere** — uniqueness, formatting and mintage are the storage layer's
 *    (or an injected generator's — §26), so tests can supply deterministic ids.
 */

/** The identity of a saved Program. Stable for the whole life of that Program (§1). */
@JvmInline
value class ProgramId(val value: String) {
    init {
        require(value.isNotBlank()) { "a ProgramId must identify a program" }
    }
}

/** The identity of one immutable ProgramRevision. A structural edit mints a new one (§6). */
@JvmInline
value class RevisionId(val value: String) {
    init {
        require(value.isNotBlank()) { "a RevisionId must identify a revision" }
    }
}

/** The identity of one ProgramDay inside a revision. Never the day's number or date (§1). */
@JvmInline
value class ProgramDayId(val value: String) {
    init {
        require(value.isNotBlank()) { "a ProgramDayId must identify a program day" }
    }
}

/**
 * The identity of one occurrence of an exercise in a day's plan.
 *
 * An exercise used twice in one day is two `ProgramExercise` values with two distinct ids (§9),
 * so pinning, overriding and adjusting one occurrence never affects the other.
 */
@JvmInline
value class ProgramExerciseId(val value: String) {
    init {
        require(value.isNotBlank()) { "a ProgramExerciseId must identify a program exercise" }
    }
}

/** The identity of one planned opportunity to train — the scheduler's unit (§20). */
@JvmInline
value class SlotId(val value: String) {
    init {
        require(value.isNotBlank()) { "a SlotId must identify a workout slot" }
    }
}

/** The identity of one started workout session (§19). */
@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "a SessionId must identify a workout session" }
    }
}

/** The identity of one exercise occurrence inside a session's snapshot (§19). */
@JvmInline
value class SessionExerciseId(val value: String) {
    init {
        require(value.isNotBlank()) { "a SessionExerciseId must identify a session exercise" }
    }
}

/** The identity of one confirmed set — the granular record the adaptive layer reads (§27). */
@JvmInline
value class SetLogId(val value: String) {
    init {
        require(value.isNotBlank()) { "a SetLogId must identify a set log" }
    }
}

/** The identity of one pause interval of a Program (§3). */
@JvmInline
value class PauseId(val value: String) {
    init {
        require(value.isNotBlank()) { "a PauseId must identify a program pause" }
    }
}

/** The identity of one adaptive adjustment — one slot-scoped change (§16). */
@JvmInline
value class AdjustmentId(val value: String) {
    init {
        require(value.isNotBlank()) { "an AdjustmentId must identify an adaptive adjustment" }
    }
}

/**
 * The identity of one adaptive decision, including the decisions the aggregate load guard filtered
 * out and recorded with a `NOT_APPLIED` outcome (§18).
 */
@JvmInline
value class DecisionId(val value: String) {
    init {
        require(value.isNotBlank()) { "a DecisionId must identify an adaptive decision" }
    }
}
