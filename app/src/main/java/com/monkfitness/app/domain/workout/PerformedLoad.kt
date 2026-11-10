package com.monkfitness.app.domain.workout

/**
 * The heaviest external load one set may record, in kilograms.
 *
 * A ceiling rather than a claim about equipment: no single-set load a person performs reaches it, so a
 * larger number is a typo — a body weight, a target, a stuck digit — and storing it would multiply
 * every volume and record derived from that set.
 */
const val MAX_PERFORMED_LOAD_KG: Double = 500.0

/**
 * The most repetitions one set may record.
 *
 * Above any single set a person performs, so a larger entry is a mistyped target rather than work; the
 * runtime's own placeholder bound ([PLACEHOLDER_PERFORMED_AMOUNT]) is a different rule and stays as it
 * is, because it also bounds time-based sets.
 */
const val MAX_PERFORMED_REPS: Int = 500

/**
 * Whether [loadKg] is a load a person can have put on a set: a positive, finite number no heavier than
 * [MAX_PERFORMED_LOAD_KG].
 *
 * `null` is a valid absence — *no load was recorded* — and is never read as zero kilograms.
 */
fun isPerformedLoad(loadKg: Double?): Boolean =
    loadKg == null || (loadKg.isFinite() && loadKg > 0.0 && loadKg <= MAX_PERFORMED_LOAD_KG)
