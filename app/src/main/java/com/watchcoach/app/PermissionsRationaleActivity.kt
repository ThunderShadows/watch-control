package com.watchcoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.watchcoach.app.ui.theme.WatchCoachTheme

/**
 * Shown when the user opens the privacy-policy link from the Health Connect
 * permission screen (ACTION_SHOW_PERMISSIONS_RATIONALE / VIEW_PERMISSION_USAGE).
 */
class PermissionsRationaleActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchCoachTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("Watch Coach privacy policy") })
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "How Watch Coach uses your health data",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Watch Coach is a local companion for the Crossbeats Everest 2.0 " +
                                "and similar watches. It reads health and fitness data only from " +
                                "Health Connect on this phone so it can show a daily dashboard " +
                                "and optional inactivity or elevated resting-heart-rate alerts.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Data we request",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "• Steps and distance, to show today's activity and detect long idle stretches.\n" +
                                "• Heart rate and resting heart rate, to show recent readings and compare " +
                                "resting heart rate against your 7-day baseline.\n" +
                                "• Sleep sessions, to show last night's sleep and compute a recovery score.\n" +
                                "• Total calories burned, shown as a daily total on the dashboard.\n" +
                                "• Background read access, so a periodic check can post alerts when the app is not open.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "How data is handled",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "All reads stay on this device. Watch Coach does not upload health data, " +
                                "create an account, or share records with a server. Alert thresholds you " +
                                "set are stored in private app preferences on the phone. Bluetooth " +
                                "scanning is used only to list nearby devices and inspect GATT service " +
                                "UUIDs; the app never writes to the watch.\n\n" +
                                "You can revoke Health Connect, Bluetooth, or notification permissions " +
                                "at any time in system settings. Revoking Health Connect access stops " +
                                "dashboard updates and health alerts.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
