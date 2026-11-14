package com.watchcoach.app.ui.sync

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchcoach.app.ble.gloryfit.ClientState
import com.watchcoach.app.ble.gloryfit.DistanceEstimate
import com.watchcoach.app.ui.activityViewModel
import com.watchcoach.app.ui.components.ErrorState
import com.watchcoach.app.ui.components.WatchCoachTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSyncScreen(
    onOpenRawLog: () -> Unit,
    onOpenScanner: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: WatchSyncViewModel = activityViewModel(),
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val state by viewModel.clientState.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val liveHr by viewModel.liveHr.collectAsStateWithLifecycle()
    var hasPerms by remember { mutableStateOf(viewModel.hasBlePermissions()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasPerms = viewModel.hasBlePermissions() }

    val busy = state == ClientState.Connecting || state == ClientState.Discovering ||
        state == ClientState.Syncing || ui.liveRunning

    Scaffold(
        topBar = { WatchCoachTopBar(title = "Sync details", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.padding(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Force-stop CB SmartFit first", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The Everest 2.0 only accepts one Bluetooth LE connection. Before you " +
                                "tap Sync, open Android Settings → Apps → CB SmartFit → Force stop. " +
                                "Leave it stopped for the whole sync. If the watch is still bound " +
                                "to CB SmartFit, Watch Coach will fail to connect or will see no data.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Watch", style = MaterialTheme.typography.titleMedium)
                    Text(device?.name ?: "CB SmartFit Everest 2.0", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        device?.address ?: "78:02:B7:55:68:40",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("Link state: ${state.name}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenScanner) { Text("Pick from scanner") }
                        if (state == ClientState.Ready || state == ClientState.Syncing) {
                            TextButton(onClick = { viewModel.disconnect() }, enabled = !busy) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }

            if (!hasPerms) {
                Button(
                    onClick = {
                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        permLauncher.launch(perms)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Allow Bluetooth") }
            }

            Button(
                onClick = { viewModel.syncFromWatch() },
                enabled = hasPerms && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (busy) "Syncing…" else "Sync from watch")
            }
            Text(
                "Nothing is written to the watch until you press this button. The only writes " +
                    "are time-sync and data-query commands (no settings, firmware, or reset).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { viewModel.readLiveHr() },
                enabled = hasPerms && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Read live heart rate (20 s)") }

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            ui.error?.let { message ->
                ErrorState(
                    title = "Sync didn’t finish",
                    body = "$message\n\nIf the watch is busy, force-stop CB SmartFit (Settings → Apps → CB SmartFit → Force stop) and try again.",
                )
            }
            ui.hcWriteResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val currentHr = liveHr ?: ui.liveHr
            if (currentHr != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Current heart rate", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${currentHr.bpm} bpm",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "From unsolicited E5 11 live-HR notifications (the watch may already be measuring).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            val snap = ui.snapshot
            val info = meta
            val summary = snap?.summary
            if (snap != null || info != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Last sync", style = MaterialTheme.typography.titleMedium)
                        val whenTxt = info?.lastSyncEpochMs?.let {
                            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(it))
                        }
                        if (whenTxt != null) Text(whenTxt, style = MaterialTheme.typography.bodySmall)
                        if (summary != null) {
                            Text("Firmware: ${summary.firmware ?: "—"}", fontFamily = FontFamily.Monospace)
                            Text(
                                "Battery: ${summary.batteryPercent?.let { "$it%" } ?: "—"}" +
                                    if (summary.charging == true) " (charging)" else "",
                            )
                            Text("Dialect: ${summary.dialect ?: "—"}")
                            Text(
                                "Steps: ${summary.stepRecords} hourly record(s)" +
                                    (summary.stepDateRange?.let { " (${it})" } ?: ""),
                            )
                            Text(
                                "Today's steps: ${summary.todaySteps}" +
                                    (summary.walkingSteps?.let { ", walking $it" } ?: "") +
                                    (summary.runningSteps?.let { ", running $it" } ?: ""),
                            )
                            if (summary.todaySteps > 0) {
                                Text(
                                    "Distance ≈ ${"%.2f".format(DistanceEstimate.metersFromSteps(summary.todaySteps) / 1000)} km " +
                                        "(phone estimate — B2 bytes 8–17 are walk/run steps, not distance or calories)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                "HR history: ${summary.hrHistorySamples} sample(s)" +
                                    (summary.hrHistoryRange?.let { " (${it})" } ?: ""),
                            )
                            Text(
                                "Live HR: ${summary.liveHrSamples} sample(s)" +
                                    (summary.lastLiveBpm?.let { ", last $it bpm" } ?: ""),
                            )
                            Text("Sleep: ${summary.sleepStatus}")
                            Text("Duration: ${"%.1f".format(summary.durationMs / 1000.0)} s")
                            summary.errors.forEach { err ->
                                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            summary.notes.forEach { note ->
                                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            val batt = snap?.identity?.batteryPercent ?: info?.batteryPercent
                            val fw = snap?.identity?.firmware ?: info?.firmware
                            val dialect = snap?.identity?.dialect?.name ?: info?.dialect
                            if (batt != null) {
                                Text("Battery: $batt%" + if (snap?.identity?.charging == true || info?.charging == true) " (charging)" else "")
                            }
                            if (!fw.isNullOrBlank()) Text("Firmware: $fw", fontFamily = FontFamily.Monospace)
                            if (!dialect.isNullOrBlank()) Text("Dialect: $dialect")
                            if (snap != null) {
                                val today = snap.todaySteps()
                                Text("Today's steps: $today")
                                Text("Hourly step records: ${snap.hourlySteps.size}")
                                Text("Heart-rate samples: ${snap.heartRates.size}")
                                Text("Sleep: ${if (snap.sleepStages.isEmpty()) "none / unsupported" else "${snap.sleepStages.size} stage(s)"}")
                                snap.notes.forEach { note ->
                                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = onOpenRawLog,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Open raw log") }
            TextButton(
                onClick = onOpenScanner,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Open BLE scanner") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
