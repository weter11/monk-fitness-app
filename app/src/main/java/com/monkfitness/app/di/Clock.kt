package com.monkfitness.app.di

import java.time.Instant

/**
 * The clock the composition root injects — §26: "*`Clock` and ID generation are injectable*".
 *
 * Nothing in the persistence layer reads the device's time on its own: the repositories take the
 * moment they stamp a row as a value (`ProgramAdaptiveRepository` takes `now: () -> Instant`), which
 * is what makes "the state stamp comes from the injected clock" a claim a test can measure instead of
 * a promise about a hidden call. This interface is the production side of that contract: it is what
 * the composition root hands over, so the clock a process runs on is decided in one place.
 *
 * It is deliberately a *port*, not a utility: an implementation returns an instant and decides
 * nothing else. It reads no calendar, resolves no program day, validates no window and starts no
 * timer — a program day is `domain.usecase.ProgramCalendar`'s decision, and the moment a row was
 * written is persistence's. A clock with a rule in it would put a scheduling decision in the wiring.
 *
 * The domain stays clock-free (§11): this port lives beside the composition root and the layers that
 * receive a clock receive it as a plain value they were given.
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

        /**
         * The production clock: the device's own time.
         *
         * It reads `java.time`, which this app already uses in production sources
         * (`domain/usecase/ProgramCalendar`, `MainViewModel`'s `LocalDate.now()`), so it introduces no
         * dependency the app did not already carry. It is a real-time clock and nothing more: it does
         * not advance itself, snap to a minute boundary or compensate for anything.
         */
        fun system(): Clock = Clock { Instant.now() }
    }
}
