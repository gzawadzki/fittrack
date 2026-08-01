package com.fittrack.core.model

import java.time.Instant

enum class CoachMessageRole { USER, COACH }

data class CoachMessage(
    val id: String,
    val role: CoachMessageRole,
    val text: String,
    val sentAt: Instant,
    val citations: List<Citation> = emptyList(),
)

data class CoachBriefing(
    val text: String,
    val generatedAt: Instant,
    val citations: List<Citation> = emptyList(),
)

/** Validated AI response before the feature assigns the trusted COACH role. */
data class CoachReply(
    val id: String,
    val text: String,
    val generatedAt: Instant,
    val citations: List<Citation> = emptyList(),
)
