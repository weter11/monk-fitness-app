package com.monkfitness.app.domain.adaptive

/**
 * The rest a window recorded, summarised as one number for the density channel.
 *
 * Only **known** rests count: a set whose rest was never recorded contributes nothing, so a window that
 * recorded no rest answers `null` rather than `0`. That distinction is what keeps the channel honest —
 * `0` is a measurement ("the user went straight on") while an absent rest is not, and a window with no
 * known rest therefore behaves exactly as it did before rest was stored: no rest signal, not "zero rest".
 *
 * The median rather than the mean because one unusually long pause (a phone call, a walked-away timer)
 * must not move the family's rest, and the value is the family's own comparison basis rather than a
 * statistic anyone reads.
 */
fun medianKnownRestSeconds(restSeconds: List<Int?>): Int? {
    val known = restSeconds.filterNotNull().sorted()
    if (known.isEmpty()) return null
    val middle = known.size / 2
    return if (known.size % 2 == 1) {
        known[middle]
    } else {
        // Rounded down: the channel reports whole seconds, and rounding up would claim a rest nobody took.
        (known[middle - 1] + known[middle]) / 2
    }
}
