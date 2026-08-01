package com.fittrack.platform.reminders

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

internal class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): List<WorkoutReminderPlan> = runCatching {
        val array = JSONArray(prefs.getString(PLANS, "[]"))
        (0 until array.length()).map { index ->
            array.getJSONObject(index).let {
                WorkoutReminderPlan(
                    planId = it.getString("planId"),
                    title = it.getString("title"),
                    dayOfWeek = DayOfWeek.of(it.getInt("dayOfWeek")),
                    minuteOfDay = it.getInt("minuteOfDay"),
                )
            }
        }
    }.getOrElse { emptyList() }

    fun write(plans: List<WorkoutReminderPlan>) {
        val payload = JSONArray().apply {
            plans.forEach { plan ->
                put(JSONObject().put("planId", plan.planId).put("title", plan.title)
                    .put("dayOfWeek", plan.dayOfWeek.value).put("minuteOfDay", plan.minuteOfDay))
            }
        }
        prefs.edit().putString(PLANS, payload.toString()).commit()
    }

    private companion object {
        const val FILE_NAME = "fittrack.reminders"
        const val PLANS = "plans"
    }
}
