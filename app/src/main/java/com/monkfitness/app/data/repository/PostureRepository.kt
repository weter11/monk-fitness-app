package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.PostureProgressDao
import com.monkfitness.app.data.model.PostureSessionProgress
import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.track.TrackCalendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZoneId

/**
 * The optional **posture / mobility** track, as a retained global feature (§4).
 *
 * It used to be three methods on the retired `WorkoutRepository`, which read the track's rows out of
 * the `ProgressDao` that also held the shipped 56-day program's tables. §30 step 15 split both: the
 * program's methods went with the program, and what is left is this track's own storage, reached
 * through its own DAO.
 *
 * ### Its calendar is its own
 *
 * A track day is resolved by [TrackCalendar] from the track's own start date, which the composition
 * root hands over as a flow. Nothing here reads a Program, a Program revision, an opportunity or a
 * session: the track is a daily practice that runs on a 56-day rhythm, and "which track day is today"
 * is a fact about a date and an anchor.
 *
 * The clock is injected (§26) rather than read inside, and the zone is the one the composition root
 * decided, so a caller that owns its own time gets its own day.
 *
 * @param dao the track's own rows.
 * @param trackStartDate the day the track's first cycle began — the same anchor the track has always
 *   used, read as a value so this class never acquires one of its own.
 * @param zone the calendar a date is read in.
 * @param clock the moment "today" is asked for.
 */
class PostureRepository(
    private val dao: PostureProgressDao,
    private val trackStartDate: Flow<LocalDate>,
    private val zone: ZoneId,
    private val clock: Clock
) {

    /** The track cycle the given date falls in, and the day within it. */
    private suspend fun positionOf(date: LocalDate): Pair<Int, Int> =
        TrackCalendar.cycleAndDay(trackStartDate.first(), date)

    private suspend fun today(): LocalDate = clock.now().atZone(zone).toLocalDate()

    /** Every row of the cycle the current date is in, in day order. */
    fun progressOfCurrentCycle(): Flow<List<PostureSessionProgress>> =
        trackStartDate.flatMapLatest { anchor ->
            dao.progressOfCycle(TrackCalendar.cycleAndDay(anchor, clock.now().atZone(zone).toLocalDate()).first)
        }

    /** How many days of the current cycle are completed. The home card's number. */
    fun completedCountOfCurrentCycle(): Flow<Int> =
        trackStartDate.flatMapLatest { anchor ->
            dao.completedCountOfCycle(
                TrackCalendar.cycleAndDay(anchor, clock.now().atZone(zone).toLocalDate()).first
            )
        }

    /** How many track days were completed in total, across every cycle. */
    fun completedCount(): Flow<Int> = dao.completedCount()

    /** The stored row for the given date's track day, or `null` when that day has no row yet. */
    suspend fun progressOn(date: LocalDate): PostureSessionProgress? {
        val (trackCycle, trackDay) = positionOf(date)
        return dao.progressOn(trackCycle, trackDay)
    }

    /** Every row, oldest first — the Progress screen's track history. */
    fun allProgress(): Flow<List<PostureSessionProgress>> = dao.allProgress()

    /**
     * Records the given day's session as completed — the one write the track has.
     *
     * It is a row of the track's own table and nothing else: no set log, no session, no opportunity
     * and no Program is written, because a mobility session is not a Program workout and has no plan
     * element to attribute a set to.
     */
    suspend fun markCompletedOn(date: LocalDate, focusArea: String = "") {
        val (trackCycle, trackDay) = positionOf(date)
        dao.upsert(
            PostureSessionProgress(
                trackCycle = trackCycle,
                trackDay = trackDay,
                isCompleted = true,
                completionDate = clock.now().toEpochMilli(),
                focusArea = focusArea
            )
        )
    }

    /**
     * Records **today's** session as completed.
     *
     * Today is a separate entry point rather than a default argument, because the default would have to
     * read the clock and the calendar — and a default parameter cannot suspend.
     */
    suspend fun markCompleted(focusArea: String = "") = markCompletedOn(today(), focusArea)

    /** The rows of the current cycle, as a one-shot read. */
    suspend fun currentCycleSnapshot(): List<PostureSessionProgress> =
        dao.progressOfCycleSnapshot(positionOf(today()).first)

    /** Empty source used before the anchor has been read, kept explicit rather than implicit. */
    internal val noProgress: Flow<List<PostureSessionProgress>> = flowOf(emptyList())
}
