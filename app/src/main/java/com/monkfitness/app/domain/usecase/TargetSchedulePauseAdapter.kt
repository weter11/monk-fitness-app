package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.ProgramPause
import com.monkfitness.app.domain.program.ProgramPauseWindow
import java.time.ZoneId

/**
 * The target-owned adapter from the *persisted* pause interval to the date window the target
 * temporal stage reads.
 *
 * ```text
 * ProgramPause            startedAt / endedAt   (Instants, persisted, §3)
 * ProgramPauseWindow      firstDate / lastDate  (LocalDates, what a target pass suppresses on)
 * ```
 *
 * Two facts make this conversion a policy rather than a formality:
 *
 *  * **An instant is not a date.** Which day a pause covers depends on the calendar it is read in, so
 *    the zone is an explicit argument and never read from the device here. A window computed in
 *    UTC and a window computed in the user's calendar are different suppressions on the same row.
 *  * **A closed interval has both ends; an open one does not.** A `ProgramPauseWindow` is a closed
 *    interval by construction, so an open pause has no honest conversion at all. This adapter
 *    therefore refuses it rather than inventing a last date — not `today`, not the run window's
 *    last date, not the far future, all of which would silently suppress occurrences that the model
 *    says were not paused.
 *
 * The refusal is not a duplicate of the lifecycle rule that would have closed the interval. It is
 * the observation that the invariant already holds: a pause can only be opened by `PAUSE`, which is
 * legal only from `RUNNING`, and a Program can only enter `RUNNING` through `START` or `RESUME` —
 * both of which close the open interval. A Program that is starting therefore has no open pause,
 * and an open one here is stored data that contradicts the lifecycle the same Program reports.
 */
object TargetSchedulePauseAdapter {

    /**
     * Converts the persisted intervals of a Program into the date windows a target pass suppresses
     * on, in the calendar named by [zone].
     *
     * @throws IllegalArgumentException when an interval is still open, which the start point this
     *   adapter serves makes unreachable — see the class comment.
     */
    fun windowsOf(pauses: List<ProgramPause>, zone: ZoneId): List<ProgramPauseWindow> =
        pauses.map { pause ->
            val endedAt = pause.endedAt
            require(endedAt != null) {
                "an open pause interval cannot become a target pause window: it has no last date, " +
                    "and inventing one would suppress occurrences the Program was never paused for"
            }
            ProgramPauseWindow(
                firstDate = pause.startedAt.atZone(zone).toLocalDate(),
                lastDate = endedAt.atZone(zone).toLocalDate()
            )
        }
}
