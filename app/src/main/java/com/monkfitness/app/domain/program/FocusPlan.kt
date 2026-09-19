package com.monkfitness.app.domain.program

/**
 * The **Goals** a Program can be built for (§8).
 *
 * §8 names exactly three, and this enum is exactly those three: there is no fourth mode, no
 * "semi-automatic" variant and no hidden goal the generator may fall back to. The goal is a
 * *planning* vocabulary, not an amount of work: it says how the planning horizon should be
 * distributed over the focuses, and nothing about volume, intensity or difficulty.
 *
 * The three goals are not three flavours of the same thing — each is a different **form of the
 * focus configuration**, and [FocusPlan] is where that form is expressed:
 *
 * ```text
 * BALANCED  the whole focus vocabulary is eligible, with no share stated by the user
 * FOCUSED   the user names the focuses to train, with no share stated by the user
 * CUSTOM    the user states every focus's share as a percentage
 * ```
 *
 * Because the form and the goal are the same fact, this enum is *derived*: [FocusPlan.goal] answers
 * it, and nothing stores a second, separately writable goal that could disagree with the
 * configuration it is supposed to describe.
 *
 * No coefficient, weight or physiological score is attached to any value here. Where §8 leaves a
 * number open, the number lives in the planner's own explicit policy (see `GenerationPolicy`) and
 * is documented there as an owner decision rather than being presented as a fact.
 */
enum class Goal {

    /** Every focus is eligible and the user stated no share (§8's `BALANCED`). */
    BALANCED,

    /** The user named the focuses to train and stated no share (§8's `FOCUSED`). */
    FOCUSED,

    /** The user stated every focus's share; the shares sum to 100% (§8's `CUSTOM`). */
    CUSTOM
}

/**
 * The **Focus** vocabulary of the Focus Planner (§8) — exactly the seven values §8 names.
 *
 * Focus is a planning dimension of its own, and the one thing this type must never be confused with
 * is a *day type*. `ProgramDayType.TRAINING`, `MOBILITY` and `POSTURE_MOBILITY` say what kind of
 * work a day is for; they are not seven values and they are not this vocabulary. A day is a place in
 * the plan; a focus is what the slot planned for it emphasizes. The two are related only by whatever
 * the caller states explicitly (the exercise's own focus membership, supplied as an input) — never by
 * a rule inferred here from a day type, a category name or an exercise's presence in a list.
 *
 * The declaration order is the **canonical order** of the vocabulary: ties are broken by it, stored
 * collections are written in it, and two equal configurations therefore compare equal whatever order
 * a caller happened to build them in (the same determinism rule `ProgramTypeConverters` applies to
 * weekday sets). Iterating a `Set<Focus>` is never an ordering decision anywhere in this domain.
 *
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
 * One focus's share of a [FocusPlan.Custom] configuration, in whole percent (§8).
 *
 * The unit is a **percentage of the plan's focus allocation** — the user's own statement of how the
 * planning horizon should be split. It is not a volume, not a load and not a physiological quantity:
 * nothing here converts it into sets, repetitions or minutes, and no conversion is invented later
 * either. A share is positive, because a focus stated as `0%` is a focus the user did not ask for;
 * the way to say that is to leave it out of the configuration.
 *
 * @property focus the focus this share belongs to.
 * @property percent the share of the plan, in whole percent, strictly positive.
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
 * A Program's **Goals & Focus** configuration — §8's goal and focus vocabulary as one value (§7's
 * *Goals & Focus* editor section).
 *
 * It is a structural fact of a revision (§6 lists *goals/focus* among the changes that create one),
 * so it is a value with no identity: two revisions that differ only here differ structurally, and a
 * draft's configuration is what `Save` would persist.
 *
 * ### The three forms, and why there is no fourth
 *
 * Each form states exactly what the user stated and nothing more:
 *
 *  * [Balanced] — *"spread the plan over everything"*. No share is stated, so none is stored, and
 *    no share is invented for it. The planner's own even spread is its policy, not the user's
 *    configuration.
 *  * [Focused] — *"train these focuses"*. The user names focuses and states no share, so none is
 *    invented: the named focuses are the ones eligible for allocation and the planner's own policy
 *    decides how the horizon is spread over them.
 *  * [Custom] — *"here is every share"*. The percentages are the user's, they are stored verbatim,
 *    and they must sum to exactly [FULL_ALLOCATION] — §8's rule, enforced at construction so an
 *    invalid allocation cannot be represented, let alone saved.
 *
 * The alternative reading — storing percentages for every goal, with defaults for the two that do
 * not state any — was rejected deliberately: a default share is a number the user never chose,
 * presented as if they had. That is the failure mode §8's *"custom percentages"* wording and §33's
 * *"silently overwrite user choices"* prohibition both point at.
 *
 * ### No ranking is modelled
 *
 * [Focused] carries a **set** of focuses in canonical order, not a priority order. §8 lists
 * *"explicit priority"* among the things the Focus Planner considers, and naming the focuses a plan
 * should be built around *is* that priority; a rank order between them is not stated by the
 * blueprint, and inventing one would decide something the user did not. Whether a future stage wants
 * a ranked form is an owner decision, and it would be an additive form here rather than a re-reading
 * of this one.
 */
sealed interface FocusPlan {

    /** The goal this configuration is an instance of (§8). Derived, never stored twice. */
    val goal: Goal

    /**
     * The whole focus vocabulary eligible for allocation under this configuration, in canonical
     * order.
     *
     * This is the configuration's own statement of *what may be trained*: every focus for
     * [Balanced], the named ones for [Focused], and the allocated ones for [Custom]. It says nothing
     * about how much of each — that is the planner's own policy for the first two forms and the
     * user's percentages for the third.
     */
    val eligibleFocuses: List<Focus>

    /**
     * *"Spread the plan over everything."* The default for a Program whose user has not configured
     * Goals & Focus (§8's `BALANCED`).
     */
    data object Balanced : FocusPlan {

        override val goal: Goal
            get() = Goal.BALANCED

        override val eligibleFocuses: List<Focus>
            get() = Focus.entries.toList()
    }

    /**
     * *"Train these focuses."* §8's `FOCUSED`: a non-empty set of focuses, with no share stated.
     *
     * @property focuses the focuses the plan is built around, in canonical order, each at most once.
     */
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
     * *"Here is every share."* §8's `CUSTOM`: whole percentages over the focus vocabulary, summing
     * to exactly [FULL_ALLOCATION].
     *
     * @property allocations one share per focus, in canonical order, each focus at most once.
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

        /** The total a [Custom] configuration's shares must sum to (§8: *"must sum to 100%"*). */
        const val FULL_ALLOCATION: Int = 100

        /**
         * The configuration a Program starts with, and the one a revision written before Goals &
         * Focus existed reads as: the whole vocabulary, no share stated (§8 `BALANCED`).
         */
        val DEFAULT: FocusPlan = Balanced

        /** §8's `FOCUSED` configuration, with its focuses held in canonical order. */
        fun focused(focuses: Collection<Focus>): Focused = Focused(canonical(focuses))

        /** §8's `CUSTOM` configuration, with its shares held in canonical order. */
        fun custom(allocations: Collection<FocusAllocation>): Custom =
            Custom(canonical(allocations.map { it.focus }).map { focus ->
                allocations.first { it.focus == focus }
            })

        /**
         * [focuses] in the vocabulary's own declaration order — the canonical order every stored and
         * compared collection here is written in.
         *
         * It reads `Focus.entries` rather than the argument's iteration order on purpose: a `Set` has
         * no meaningful order, and letting one decide the plan would make generation depend on
         * something the user cannot see (§9's *"no unordered Set iteration as implicit ranking"*).
         */
        fun canonical(focuses: Collection<Focus>): List<Focus> {
            val present = focuses.toSet()
            return Focus.entries.filter { it in present }
        }
    }
}
