package com.watchcoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.watchcoach.app.ui.WatchCoachApp
import com.watchcoach.app.ui.theme.WatchCoachTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchCoachTheme {
                WatchCoachApp()
            }
        }
    }
}
