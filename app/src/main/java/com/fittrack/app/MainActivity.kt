package com.fittrack.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.fittrack.app.ui.FitTrackApp
import com.fittrack.app.ui.FitTrackViewModel
import com.fittrack.app.ui.theme.FitTrackTheme

class MainActivity : ComponentActivity() {
    private val viewModel: FitTrackViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            FitTrackTheme { FitTrackApp(viewModel) }
        }
    }
}
