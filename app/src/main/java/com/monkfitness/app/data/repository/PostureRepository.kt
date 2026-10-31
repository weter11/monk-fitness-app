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
 * The optional **posture / mobility** track, as a retained global feature with its own DAO and its own
 * calendar: a track day is resolved by [TrackCalendar] from the track's own start date (handed over as a
 * flow), so nothing here reads a Program, a revision, an opportunity or a session. The clock and the zone
 * are injected, so a caller that owns its own time gets its own day.
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

    /** Every row of the cycle the current date is in. */
    fun progressOfCurrentCycle(): Flow<List<PostureSessionProgress>> =
        trackStartDate.flatMapLatest { anchor ->
            dao.progressOfCycle(TrackCalendar.cycleAndDay(anchor, clock.now().atZone(zone).toLocalDate()).first)
        }

    fun completedCountOfCurrentCycle(): Flow<Int> =
        trackStartDate.flatMapLatest { anchor ->
            dao.completedCountOfCycle(
                TrackCalendar.cycleAndDay(anchor, clock.now().atZone(zone).toLocalDate()).first
            )
        }

    fun completedCount(): Flow<Int> = dao.completedCount()

    /** The stored row for the given date's track day, or `null` when that day has no row yet. */
    suspend fun progressOn(date: LocalDate): PostureSessionProgress? {
        val (trackCycle, trackDay) = positionOf(date)
        return dao.progressOn(trackCycle, trackDay)
    }

    /** Every row, oldest first. */
    fun allProgress(): Flow<List<PostureSessionProgress>> = dao.allProgress()

    /**
     * Records the given day's session as completed — the one write the track has. It touches a row of
     * the track's own table and nothing else: no set log, no session, no opportunity and no Program,
     * because a mobility session is not a Program workout and has no plan element to attribute a set to.
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
     * Records **today's** session as completed. Today is a separate entry point rather than a default
     * argument, because the default would have to read the clock and the calendar — and a default
     * parameter cannot suspend.
     */
    suspend fun markCompleted(focusArea: String = "") = markCompletedOn(today(), focusArea)

    suspend fun currentCycleSnapshot(): List<PostureSessionProgress> =
        dao.progressOfCycleSnapshot(positionOf(today()).first)

    /** Empty source used before the anchor has been read, kept explicit rather than implicit. */
    internal val noProgress: Flow<List<PostureSessionProgress>> = flowOf(emptyList())
}
