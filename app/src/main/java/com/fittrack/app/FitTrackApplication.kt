package com.fittrack.app

import android.app.Application
import com.fittrack.app.data.FitTrackRepository
import com.fittrack.app.data.local.FitTrackDatabase
import com.fittrack.app.reminders.ReminderScheduler
import com.fittrack.app.sync.BackgroundWork
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
            ReminderScheduler.rescheduleAll(this@FitTrackApplication, database.dao())
        }
    }
}

