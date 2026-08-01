package com.fittrack.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fittrack.app.ui.FitTrackApp
import com.fittrack.core.designsystem.FitTrackTheme
import com.fittrack.platform.reminders.EXTRA_PLAN_ID
import com.fittrack.platform.reminders.EXTRA_REMINDER_ACTION

data class AppCommand(val nonce: Long, val action: String?, val planId: String?)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var command by mutableStateOf<AppCommand?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        command = intent.toAppCommand()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val container = (application as FitTrackApplication).container
        setContent {
            FitTrackTheme {
                FitTrackApp(container, command, onCommandConsumed = { command = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        command = intent.toAppCommand()
    }
}

private fun Intent.toAppCommand(): AppCommand? {
    val action = getStringExtra(EXTRA_REMINDER_ACTION) ?: return null
    return AppCommand(System.nanoTime(), action, getStringExtra(EXTRA_PLAN_ID))
}
