package com.fittrack.core.model.repository

import com.fittrack.core.model.CoachBriefing
import com.fittrack.core.model.CoachReply

/**
 * Network boundary for AI-generated content. Implementations validate the
 * backend's structured response before returning domain values.
 *
 * Suggestions and their decisions deliberately stay in [WorkoutRepository].
 */
interface CoachGateway {
    suspend fun getBriefing(): CoachBriefing
    suspend fun sendMessage(message: String): CoachReply
    suspend fun getDebrief(workoutSessionId: String): CoachReply
}

/** Local cache of the last-fetched briefing, so the Trener tab can show
 * something immediately on open instead of a blank loading state while a
 * fresh one generates - coach model calls can take seconds to tens of
 * seconds, and re-fetching with nothing to show made the tab feel hung. */
interface CoachBriefingCache {
    fun read(): CoachBriefing?
    fun write(briefing: CoachBriefing)
}
