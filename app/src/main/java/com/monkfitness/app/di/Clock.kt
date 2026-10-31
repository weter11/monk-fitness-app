package com.monkfitness.app.di

import java.time.Instant

/**
 * The injected clock the composition root hands over, so the clock a process runs on is decided in one
 * place. It is a port, not a utility: it returns an instant and decides nothing else — which day a date
 * belongs to is the owning calendar's rule, the moment a row was written is persistence's, and the
 * domain stays clock-free.
 */
fun interface Clock {

    /**
     * The current instant, read at the moment it is asked for.
     *
     * Callers must not capture the result to reuse it as "now" for a later write: the repositories
     * call their clock once per stamp, and a free-running implementation returns a later instant on
     * the next call.
     */
    fun now(): Instant

    companion object {

        /** The production clock: the device's own time, with no rule in it — it does not advance itself
         * or snap to a minute boundary. */
        fun system(): Clock = Clock { Instant.now() }
    }
}
