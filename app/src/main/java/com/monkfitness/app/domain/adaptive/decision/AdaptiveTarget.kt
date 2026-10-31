package com.monkfitness.app.domain.adaptive.decision

import com.monkfitness.app.domain.adaptive.AdaptiveScope

/**
 * What one decision is about. The scope is **derived** from the target rather than stored beside it, so a
 * decision cannot record a scope that disagrees with what it changes.
 *
 * Targets are named by id and stay opaque: the domain owns no catalogue of exercises, families or focuses.
 */
sealed interface AdaptiveTarget {

    val scope: AdaptiveScope

    data class Exercise(val exerciseId: String) : AdaptiveTarget {

        init {
            require(exerciseId.isNotBlank()) { "an exercise target must name its exercise" }
        }

        override val scope: AdaptiveScope
            get() = AdaptiveScope.EXERCISE
    }

    data class Family(val familyId: String) : AdaptiveTarget {

        init {
            require(familyId.isNotBlank()) { "a family target must name its family" }
        }

        override val scope: AdaptiveScope
            get() = AdaptiveScope.FAMILY
    }

    data class Focus(val focusId: String) : AdaptiveTarget {

        init {
            require(focusId.isNotBlank()) { "a focus target must name its focus" }
        }

        override val scope: AdaptiveScope
            get() = AdaptiveScope.FOCUS
    }

    data object Session : AdaptiveTarget {

        override val scope: AdaptiveScope
            get() = AdaptiveScope.SESSION
    }
}
