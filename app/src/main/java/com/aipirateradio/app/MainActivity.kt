package com.aipirateradio.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.aipirateradio.app.ui.PirateRadioApp

class MainActivity : ComponentActivity() {
    private var reportAudioPermissionStatus: ((String) -> Unit)? = null
    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reportAudioPermissionStatus?.invoke(
            if (granted) "Local music access granted." else "Local music access denied."
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PirateRadioApp(
                requestLocalAudioAccess = { statusReporter ->
                    reportAudioPermissionStatus = statusReporter
                    val permission = if (Build.VERSION.SDK_INT >= 33) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    audioPermissionLauncher.launch(permission)
                }
            )
        }
    }
}
