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
