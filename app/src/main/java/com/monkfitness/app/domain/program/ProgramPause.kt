package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.PauseId
import com.monkfitness.app.domain.common.ProgramId
import java.time.Instant

/**
 * One pause interval of a Program (§3).
 *
 * A pause is an interval, not a state replay: pausing freezes active program time and
 * missed-opportunity logic, and resuming closes the interval. Keeping the intervals — rather than
 * only the current status — is what makes "the program was paused" explainable afterwards without
 * rewriting the plan or the history, and it is why the intervals are owned by the Program (§1).
 *
 * An open interval ([endedAt] `null`) is a pause that is currently in effect; the Program's own
 * `lifecycleStatus` says whether that is the state the program is in.
 *
 * @property pauseId identity of this interval.
 * @property programId the Program that was paused.
 * @property startedAt when the pause began.
 * @property endedAt when it ended, or `null` while it is still in effect.
 */
data class ProgramPause(
    val pauseId: PauseId,
    val programId: ProgramId,
    val startedAt: Instant,
    val endedAt: Instant? = null
) {

    init {
        require(endedAt == null || endedAt >= startedAt) {
            "a pause cannot end before it started: started=$startedAt ended=$endedAt"
        }
    }

    /** Whether this pause is currently in effect. */
    val isOpen: Boolean
        get() = endedAt == null
}
