package com.monkfitness.app.domain.adaptive.decision

import com.monkfitness.app.domain.adaptive.AdaptiveScope

/**
 * What one decision is about (§15, §18).
 *
 * A decision changes one thing at one granularity, so the target and the scope are the same fact
 * stated twice — and this type keeps them from drifting apart: the scope is derived from the target
 * rather than stored beside it, so there is no way to record a decision whose scope disagrees with
 * what it changes.
 *
 * Targets are named by id and stay opaque: an exercise is an `exerciseId`, a family is a `familyId`, a
 * focus is a `focusId`. The domain owns no catalogue of exercises or families, and the focus
 * vocabulary belongs to the Focus Planner (§8) — which is why `FOCUS` names a scope without
 * enumerating what a focus can be.
 */
sealed interface AdaptiveTarget {

    /** The granularity of the change. */
    val scope: AdaptiveScope

    /** One exercise occurrence's exercise. */
    data class Exercise(val exerciseId: String) : AdaptiveTarget {

        init {
            require(exerciseId.isNotBlank()) { "an exercise target must name its exercise" }
        }

        override val scope: AdaptiveScope
            get() = AdaptiveScope.EXERCISE
    }

    /** One progression family. */
    data class Family(val familyId: String) : AdaptiveTarget {

        init {
            require(familyId.isNotBlank()) { "a family target must name its family" }
        }

        override val scope: AdaptiveScope
            get() = AdaptiveScope.FAMILY
    }

    /** One focus area of the plan. */
    data class Focus(val focusId: String) : AdaptiveTarget {

        init {
            require(focusId.isNotBlank()) { "a focus target must name its focus" }
        }

        override val scope: AdaptiveScope
            get() = AdaptiveScope.FOCUS
    }

    /** The session as a whole — the level the aggregate load guard compares at (§18). */
    data object Session : AdaptiveTarget {

        override val scope: AdaptiveScope
            get() = AdaptiveScope.SESSION
    }
}
