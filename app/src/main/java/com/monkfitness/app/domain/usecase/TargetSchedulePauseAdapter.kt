package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.ProgramPause
import com.monkfitness.app.domain.program.ProgramPauseWindow
import java.time.ZoneId

/**
 * The target-owned adapter from the *persisted* pause interval (`ProgramPause`, Instants) to the date
 * window the target temporal stage suppresses on (`ProgramPauseWindow`, LocalDates).
 *
 * Two facts make this a policy rather than a formality: an instant is not a date, so the zone is an
 * explicit argument and never read from the device here; and a closed interval has both ends, so an
 * open pause has no honest conversion — this refuses it rather than inventing a last date (not
 * `today`, not the run window's last date), all of which would suppress occurrences the model says
 * were not paused.
 *
 * The refusal is not a duplicate of the lifecycle rule: it observes that the invariant already holds.
 * A pause opens only from `RUNNING`, and `RUNNING` is entered only through `START` or `RESUME`, both
 * of which close the interval — so an open one here is stored data contradicting the lifecycle.
 */
object TargetSchedulePauseAdapter {

    /**
     * Converts the persisted intervals into the date windows a target pass suppresses on, in [zone].
     *
     * @throws IllegalArgumentException when an interval is still open — unreachable from the start
     *   point this adapter serves, where an open interval would contradict the lifecycle.
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
