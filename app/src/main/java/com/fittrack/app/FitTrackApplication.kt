package com.fittrack.app

import android.app.Application
import com.fittrack.app.data.FitTrackRepository
import com.fittrack.app.data.local.FitTrackDatabase
import com.fittrack.platform.reminders.BackgroundWork
import com.fittrack.platform.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FitTrackApplication : Application() {
    lateinit var database: FitTrackDatabase
        private set
    lateinit var repository: FitTrackRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = FitTrackDatabase.create(this)
        repository = FitTrackRepository(this, database)
        ReminderScheduler.createChannel(this)
        BackgroundWork.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repository.seedDefaultPlanIfNeeded()
            ReminderScheduler.rescheduleAll(this@FitTrackApplication)
        }
    }
}

