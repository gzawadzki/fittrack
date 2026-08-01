package com.fittrack.app

import android.app.Application
import com.fittrack.app.di.AppContainer

class FitTrackApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
