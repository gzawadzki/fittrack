package com.fittrack.app.di

import android.content.Context
import com.fittrack.core.model.Citation
import com.fittrack.core.model.CoachBriefing
import com.fittrack.core.model.repository.CoachBriefingCache
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

class SharedPrefsCoachBriefingCache(context: Context) : CoachBriefingCache {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(): CoachBriefing? = runCatching {
        val json = preferences.getString(KEY_BRIEFING, null) ?: return null
        val root = JSONObject(json)
        val citations = root.optJSONArray(KEY_CITATIONS)?.let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Citation(title = item.getString("title"), url = item.getString("url"), claim = item.getString("claim"))
            }
        }.orEmpty()
        CoachBriefing(
            text = root.getString(KEY_TEXT),
            generatedAt = Instant.parse(root.getString(KEY_GENERATED_AT)),
            citations = citations,
        )
    }.getOrNull()

    override fun write(briefing: CoachBriefing) {
        val citations = JSONArray().apply {
            briefing.citations.forEach { citation ->
                put(JSONObject().put("title", citation.title).put("url", citation.url).put("claim", citation.claim))
            }
        }
        val json = JSONObject()
            .put(KEY_TEXT, briefing.text)
            .put(KEY_GENERATED_AT, briefing.generatedAt.toString())
            .put(KEY_CITATIONS, citations)
            .toString()
        preferences.edit().putString(KEY_BRIEFING, json).apply()
    }

    private companion object {
        const val FILE_NAME = "fittrack.coach_briefing_cache"
        const val KEY_BRIEFING = "briefing"
        const val KEY_TEXT = "text"
        const val KEY_GENERATED_AT = "generatedAt"
        const val KEY_CITATIONS = "citations"
    }
}
