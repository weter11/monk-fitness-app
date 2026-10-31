package com.monkfitness.app.domain.program

/**
 * What kind of day one [ProgramDay] is.
 *
 * The type describes the **work the day is for**, not how the day is scheduled and not how strong the
 * workout is: the weekly rhythm lives on [ProgramSchedule], and the training emphasis of an individual
 * workout belongs to the slot's focus, which the Focus Planner assigns per slot.
 *
 * `REST` is the only type with a structural consequence in the domain: a rest day may be a slot without a
 * session, so it prescribes no exercises. The remaining values mirror the kinds of day the app already
 * distinguishes, so an existing workout vocabulary maps onto a program day without inventing a new one.
 *
 * Values are persisted by name.
 */
enum class ProgramDayType {

    /** A loaded workout day: the day's plan prescribes training work. */
    TRAINING,

    /** A mobility/flexibility day. */
    MOBILITY,

    /** A posture and mobility day. */
    POSTURE_MOBILITY,

    /** A rest day — an opportunity to rest, never a workout that scored zero. */
    REST
}
