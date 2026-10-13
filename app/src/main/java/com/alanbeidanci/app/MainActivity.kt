package com.alanbeidanci.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import com.alanbeidanci.app.ui.AlanVocabRoot
import com.alanbeidanci.app.ui.theme.AlanVocabTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AlanVocabApplication).container
        setContent {
            val settings by container.preferences.settings.collectAsState(initial = com.alanbeidanci.app.data.UserSettings())
            LaunchedEffect(settings.onboardingComplete, settings.reminderEnabled) {
                if (
                    android.os.Build.VERSION.SDK_INT >= 33 &&
                    settings.onboardingComplete &&
                    settings.reminderEnabled &&
                    !settings.notificationPermissionRequested &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    container.preferences.markNotificationPermissionRequested()
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            AlanVocabTheme(darkTheme = settings.darkMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AlanVocabRoot(container = container, settings = settings)
                }
            }
        }
    }
}
