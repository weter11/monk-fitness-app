package com.monkfitness.app.domain.program

/**
 * The **Goals** a Program can be built for: exactly three, with no fourth mode and no hidden goal the
 * generator may fall back to.
 *
 * The goal is a *planning* vocabulary, not an amount of work: it says how the planning horizon should
 * be distributed over the focuses, and nothing about volume, intensity or difficulty. Each goal is a
 * different **form of the focus configuration**, which [FocusPlan] expresses — so this enum is
 * *derived*: [FocusPlan.goal] answers it, and nothing stores a second, separately writable goal that
 * could disagree with the configuration it describes.
 *
 * No coefficient, weight or physiological score is attached to any value: where a number is open, it
 * lives in the planner's own explicit policy and is documented there as an owner decision rather than
 * presented as a fact.
 */
enum class Goal {

    /** Every focus is eligible and the user stated no share. */
    BALANCED,

    /** The user named the focuses to train and stated no share. */
    FOCUSED,

    /** The user stated every focus's share; the shares sum to 100%. */
    CUSTOM
}

/**
 * The **Focus** vocabulary of the Focus Planner — exactly seven values.
 *
 * Focus is a planning dimension of its own, and the one thing this type must never be confused with is
 * a *day type*. `ProgramDayType.TRAINING`, `MOBILITY` and `POSTURE_MOBILITY` say what kind of work a
 * day is for; a day is a place in the plan, a focus is what the slot planned for it emphasizes. The
 * two are related only by what the caller states explicitly — never by a rule inferred here from a day
 * type, a category name or an exercise's presence in a list.
 *
 * The declaration order is the **canonical order** of the vocabulary: ties are broken by it, stored
 * collections are written in it, and two equal configurations therefore compare equal whatever order a
 * caller built them in. Iterating a `Set<Focus>` is never an ordering decision anywhere in this domain.
 * Values are persisted by name, never by ordinal.
 */
enum class Focus {

    /** Pressing and pushing work: shoulders, chest, triceps. */
    PUSH,

    /** Pulling work: back, biceps, grip. */
    PULL,

    /** Squatting, hinging and lunging work. */
    LEGS,

    /** Trunk and midline work. */
    CORE,

    /** Mobility and flexibility work. */
    MOBILITY,

    /** Posture work. */
    POSTURE,

    /** Conditioning work. */
    CONDITIONING
}

/**
 * One focus's share of a [FocusPlan.Custom] configuration, in whole percent.
 *
 * The unit is a **percentage of the plan's focus allocation** — the user's own statement of how the
 * planning horizon should be split. It is not a volume, not a load and not a physiological quantity:
 * nothing here converts it into sets, repetitions or minutes. A share is positive, because a focus
 * stated as `0%` is a focus the user did not ask for; the way to say that is to leave it out.
 */
data class FocusAllocation(
    val focus: Focus,
    val percent: Int
) {

    init {
        require(percent > 0) {
            "a focus allocation states a share of the plan, so ${focus.name} at $percent% is not an " +
                "allocation: a focus the user did not ask for is left out of the configuration (§8)"
        }
    }
}

/**
 * A Program's **Goals & Focus** configuration — the goal and focus vocabulary as one value.
 *
 * It is a structural fact of a revision, so it is a value with no identity: two revisions that differ
 * only here differ structurally, and a draft's configuration is what `Save` would persist.
 *
 * Each form states exactly what the user stated and nothing more. [Balanced] and [Focused] state no
 * share, so none is stored and none is invented — how the horizon is spread over the eligible focuses
 * is the planner's policy, not the user's configuration. [Custom] stores the user's percentages
 * verbatim, and they must sum to exactly [FULL_ALLOCATION], enforced at construction so an invalid
 * allocation cannot be represented, let alone saved. Storing percentages for every goal with defaults
 * for the two that state none was rejected deliberately: a default share is a number the user never
 * chose, presented as if they had.
 *
 * No ranking is modelled. [Focused] carries a **set** of focuses in canonical order, not a priority
 * order: naming the focuses a plan should be built around *is* the priority, and inventing a rank
 * between them would decide something the user did not.
 */
sealed interface FocusPlan {

    /** The goal this configuration is an instance of. Derived, never stored twice. */
    val goal: Goal

    /**
     * The whole focus vocabulary eligible for allocation under this configuration, in canonical order.
     *
     * It states *what may be trained* and nothing about how much of each — that is the planner's policy
     * for [Balanced] and [Focused], and the user's percentages for [Custom].
     */
    val eligibleFocuses: List<Focus>

    /** *"Spread the plan over everything."* The default for a Program whose user has not configured Goals & Focus. */
    data object Balanced : FocusPlan {

        override val goal: Goal
            get() = Goal.BALANCED

        override val eligibleFocuses: List<Focus>
            get() = Focus.entries.toList()
    }

    /** *"Train these focuses."* A non-empty set of focuses, with no share stated. */
    data class Focused(val focuses: List<Focus>) : FocusPlan {

        init {
            require(focuses.isNotEmpty()) {
                "a FOCUSED plan names the focuses it is built around; a plan that names none is the " +
                    "BALANCED configuration (§8)"
            }
            require(focuses.distinct().size == focuses.size) {
                "a focus is named at most once in a FOCUSED plan, found $focuses (§8)"
            }
            require(focuses == FocusPlan.canonical(focuses)) {
                "the focuses of a FOCUSED plan are held in the vocabulary's own order, found " +
                    "$focuses instead of ${FocusPlan.canonical(focuses)}: equality of two equal " +
                    "plans may not depend on the order a caller built them in"
            }
        }

        override val goal: Goal
            get() = Goal.FOCUSED

        override val eligibleFocuses: List<Focus>
            get() = focuses
    }

    /**
     * *"Here is every share."* Whole percentages over the focus vocabulary, summing to exactly
     * [FULL_ALLOCATION].
     */
    data class Custom(val allocations: List<FocusAllocation>) : FocusPlan {

        init {
            require(allocations.isNotEmpty()) {
                "a CUSTOM plan states at least one share; a plan that states none is the BALANCED " +
                    "configuration (§8)"
            }
            val focuses = allocations.map { it.focus }
            require(focuses.distinct().size == focuses.size) {
                "a focus carries one share, found ${allocations.map { it.focus.name }} (§8)"
            }
            require(focuses == FocusPlan.canonical(focuses)) {
                "the shares of a CUSTOM plan are held in the vocabulary's own order, found " +
                    "${allocations.map { it.focus.name }} instead of " +
                    "${FocusPlan.canonical(focuses).map { it.name }}: equality of two equal plans " +
                    "may not depend on the order a caller built them in"
            }
            val total = allocations.sumOf { it.percent }
            require(total == FocusPlan.FULL_ALLOCATION) {
                "custom focus percentages must sum to ${FocusPlan.FULL_ALLOCATION}%, was " +
                    "$total%: " +
                    allocations.joinToString(", ") { "${it.focus.name}=${it.percent}%" }
            }
        }

        override val goal: Goal
            get() = Goal.CUSTOM

        override val eligibleFocuses: List<Focus>
            get() = allocations.map { it.focus }

        /** The share of [focus], or `0` when this configuration does not allocate it. */
        fun percentFor(focus: Focus): Int = allocations.firstOrNull { it.focus == focus }?.percent ?: 0
    }

    /** The focuses this configuration states a percentage for, in canonical order. Empty unless [Custom]. */
    val statedAllocations: List<FocusAllocation>
        get() = (this as? Custom)?.allocations ?: emptyList()

    companion object {

        /** The total a [Custom] configuration's shares must sum to. */
        const val FULL_ALLOCATION: Int = 100

        /** The configuration a Program starts with, and the one a revision written before Goals & Focus existed reads as. */
        val DEFAULT: FocusPlan = Balanced

        fun focused(focuses: Collection<Focus>): Focused = Focused(canonical(focuses))

        fun custom(allocations: Collection<FocusAllocation>): Custom =
            Custom(canonical(allocations.map { it.focus }).map { focus ->
                allocations.first { it.focus == focus }
            })

        /**
         * [focuses] in the vocabulary's own declaration order — the canonical order every stored and
         * compared collection here is written in.
         *
         * It reads `Focus.entries` rather than the argument's iteration order on purpose: a `Set` has no
         * meaningful order, and letting one decide the plan would make generation depend on something
         * the user cannot see.
         */
        fun canonical(focuses: Collection<Focus>): List<Focus> {
            val present = focuses.toSet()
            return Focus.entries.filter { it in present }
        }
    }
}
