package com.internetdeco.decocontrol

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = runCatching { Controller.initialize(this) }.isSuccess
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            var ready by remember { mutableStateOf(initial) }
            if (ready) DecoApp()
            else MaterialTheme(colorScheme = Dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("Saved data is unavailable", style = MaterialTheme.typography.titleLarge)
                        Text("Unlock your phone and try again. Your encrypted data has been kept.")
                        Button(onClick = {
                            ready = runCatching { Controller.initialize(this@MainActivity) }.isSuccess
                            if (ready) Controller.resumed(true)
                        }) { Text("Try again") }
                    }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); if (Controller.ready) Controller.resumed(true) }
    override fun onPause() { if (Controller.ready) Controller.resumed(false); super.onPause() }
}
