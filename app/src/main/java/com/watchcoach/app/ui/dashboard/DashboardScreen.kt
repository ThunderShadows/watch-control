package com.watchcoach.app.ui.dashboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchcoach.app.ble.gloryfit.ClientState
import com.watchcoach.app.data.health.HealthAvailability
import com.watchcoach.app.data.health.HealthConnectRepository
import com.watchcoach.app.data.health.RecoveryScore
import com.watchcoach.app.ui.activityViewModel
import com.watchcoach.app.ui.alerts.AlertsViewModel
import com.watchcoach.app.ui.components.EmptyState
import com.watchcoach.app.ui.components.ErrorState
import com.watchcoach.app.ui.components.LoadingState
import com.watchcoach.app.ui.components.StepBarChart
import com.watchcoach.app.ui.components.WatchCoachTopBar
import com.watchcoach.app.ui.sync.WatchSyncViewModel
import java.util.Locale
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSyncDetails: () -> Unit = {},
    viewModel: DashboardViewModel = activityViewModel(),
    syncViewModel: WatchSyncViewModel = activityViewModel(),
    alertsViewModel: AlertsViewModel = activityViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val device by syncViewModel.device.collectAsStateWithLifecycle()
    val link by syncViewModel.clientState.collectAsStateWithLifecycle()
    val meta by syncViewModel.meta.collectAsStateWithLifecycle()
    val ui by syncViewModel.ui.collectAsStateWithLifecycle()
    val features by alertsViewModel.features.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasBle by remember { mutableStateOf(syncViewModel.hasBlePermissions()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        viewModel.onPermissionsResult(granted)
    }
    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasBle = syncViewModel.hasBlePermissions() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
                hasBle = syncViewModel.hasBlePermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lastSyncMs = meta?.lastSyncEpochMs ?: 0L
    LaunchedEffect(lastSyncMs) {
        if (lastSyncMs > 0L) viewModel.refresh()
    }

    val busy = link == ClientState.Connecting || link == ClientState.Discovering ||
        link == ClientState.Syncing || ui.liveRunning
    val goal = features.stepsGoal.coerceAtLeast(1)
    val battery = ui.snapshot?.summary?.batteryPercent
        ?: ui.snapshot?.identity?.batteryPercent
        ?: meta?.batteryPercent
    val charging = ui.snapshot?.summary?.charging
        ?: ui.snapshot?.identity?.charging
        ?: meta?.charging

    Scaffold(
        topBar = {
            WatchCoachTopBar(
                title = "Watch Coach",
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh today’s activity")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HealthConnectNotice(
                availability = state.availability,
                hasPermissions = state.hasPermissions,
                onGrant = { permissionLauncher.launch(viewModel.requiredPermissions) },
                onInstall = {
                    runCatching { context.startActivity(HealthConnectRepository.installOrUpdateIntent()) }
                },
                onOpenSettings = {
                    runCatching { context.startActivity(HealthConnectRepository.settingsIntent()) }
                },
            )

            if (state.loading && !state.hasWatchData && state.data.todaySteps == null) {
                LoadingState("Loading today’s activity…")
            }

            ConnectionCard(
                name = device?.name ?: meta?.name ?: "Crossbeats Everest 2.0",
                address = device?.address ?: meta?.address ?: "No watch saved yet",
                link = link,
                battery = battery,
                charging = charging,
            )

            StepsGoalCard(
                todaySteps = state.data.todaySteps,
                goal = goal,
                distanceMeters = state.data.todayDistanceMeters,
                calories = state.data.todayCaloriesKcal,
                source = state.data.sources.steps,
                distanceSource = state.data.sources.distance,
            )

            HeartRow(
                latest = state.data.heartRate.latestBpm,
                latestTime = state.data.heartRate.latestTime,
                resting = state.data.currentRestingHr,
                baseline = state.data.baselineRestingHr,
                source = state.data.sources.heart,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Last sync",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        formatSyncTime(lastSyncMs),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = {
                            if (!hasBle) {
                                bleLauncher.launch(blePermissions())
                            } else {
                                syncViewModel.syncFromWatch()
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            when {
                                busy -> "Syncing…"
                                !hasBle -> "Allow Bluetooth to sync"
                                else -> "Sync"
                            },
                        )
                    }
                    Text(
                        "If the watch is busy, force-stop CB SmartFit (Settings → Apps → CB SmartFit → Force stop), then tap Sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (busy) {
                        LoadingState("Talking to the watch…")
                    }
                    ui.message?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(
                        onClick = onOpenSyncDetails,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Sync details and live heart rate")
                    }
                }
            }

            ui.error?.let { message ->
                ErrorState(
                    title = "Sync didn’t finish",
                    body = "$message\n\nForce-stop CB SmartFit if it still holds the watch, keep the Everest 2.0 nearby, and tap Sync again.",
                )
            }
            state.error?.let { message ->
                ErrorState(
                    title = "Couldn’t read Health Connect",
                    body = "$message\n\nYou can still sync the watch directly. Open Settings if you want to grant Health Connect again.",
                )
            }

            val showDetails = state.hasPermissions || state.hasWatchData
            if (!showDetails && !state.loading) {
                EmptyState(
                    title = "No activity yet",
                    body = "Force-stop CB SmartFit so it lets go of the watch, then tap Sync. " +
                        "Steps, heart rate, and sleep show up here after a successful sync. " +
                        "Nothing is sent until you tap Sync.",
                )
            }

            if (showDetails) {
                state.data.sources.summary?.let { summary ->
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                RecoveryCard(state.data.recovery)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
                        StepBarChart(days = state.data.last7DaysSteps)
                    }
                }
                SleepCard(
                    duration = state.data.lastNightSleep.duration,
                    start = state.data.lastNightSleep.start,
                    end = state.data.lastNightSleep.end,
                    source = state.data.sources.sleep,
                )
                Text(
                    "Distance and calories from a watch sync are estimates. Sleep from this watch is still unverified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HealthConnectNotice(
    availability: HealthAvailability,
    hasPermissions: Boolean,
    onGrant: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (availability) {
        HealthAvailability.Unavailable -> AvailabilityCard(
            title = "Health Connect isn’t available",
            body = "This phone can’t use Health Connect. Sync still reads steps, heart rate, and sleep straight from the watch.",
        )
        HealthAvailability.NotInstalled, HealthAvailability.UpdateRequired -> AvailabilityCard(
            title = if (availability is HealthAvailability.UpdateRequired) {
                "Health Connect needs an update"
            } else {
                "Health Connect isn’t installed"
            },
            body = "On Android 13 and lower, install Health Connect from the Play Store. " +
                "On Android 14 and later it is built in — open Settings if this message stays. " +
                "You can sync the watch without it.",
            primaryLabel = "Install or update",
            onPrimary = onInstall,
            secondaryLabel = "Open Health Connect",
            onSecondary = onOpenSettings,
        )
        HealthAvailability.Available -> if (!hasPermissions) {
            AvailabilityCard(
                title = "Health Connect is optional",
                body = "Watch Coach can read steps, heart rate, sleep, distance, and calories that another app already wrote. " +
                    "Nothing is uploaded. If CB SmartFit never syncs there, use the Sync button instead.",
                primaryLabel = "Grant permissions",
                onPrimary = onGrant,
                secondaryLabel = "Health Connect settings",
                onSecondary = onOpenSettings,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    name: String,
    address: String,
    link: ClientState,
    battery: Int?,
    charging: Boolean?,
) {
    val linkText = linkLabel(link)
    val batteryText = when {
        battery == null -> "Battery not read yet"
        charging == true -> "$battery% · charging"
        else -> "$battery%"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$name, $linkText, $batteryText" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Watch,
                    contentDescription = "Watch",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Watch",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(linkText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BatteryStd,
                    contentDescription = "Battery",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(batteryText, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun StepsGoalCard(
    todaySteps: Long?,
    goal: Int,
    distanceMeters: Double?,
    calories: Double?,
    source: String?,
    distanceSource: String?,
) {
    val fraction = ((todaySteps ?: 0L).toFloat() / goal.toFloat()).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()
    val stepsLabel = todaySteps?.let { "%,d".format(it) } ?: "—"
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(112.dp)
                    .semantics {
                        contentDescription = if (todaySteps == null) {
                            "No steps yet. Goal $goal"
                        } else {
                            "$stepsLabel of $goal steps today, $percent percent"
                        }
                    },
            ) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(112.dp),
                    strokeWidth = 10.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, contentDescription = "Steps")
                    Spacer(Modifier.width(8.dp))
                    Text("Today’s steps", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (source != null) SourceLabel(source)
                }
                Text(stepsLabel, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "of %,d".format(goal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricChip(
                        icon = {
                            Icon(
                                Icons.Filled.Straighten,
                                contentDescription = "Distance",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = distanceMeters?.let { formatDistance(it) } ?: "—",
                    )
                    MetricChip(
                        icon = {
                            Icon(
                                Icons.Filled.LocalFireDepartment,
                                contentDescription = "Calories",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = calories?.let { "${it.toInt()} kcal" } ?: "—",
                    )
                }
                if (distanceSource != null) {
                    Text(
                        distanceSource,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeartRow(
    latest: Long?,
    latestTime: java.time.Instant?,
    resting: Long?,
    baseline: Long?,
    source: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        HeartStat(
            title = "Latest heart rate",
            value = latest?.let { "$it" } ?: "—",
            unit = "bpm",
            detail = latestTime?.let {
                DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(it)
            } ?: "No reading yet",
            source = source,
            modifier = Modifier.weight(1f),
            icon = {
                Icon(Icons.Filled.Favorite, contentDescription = "Heart rate", tint = MaterialTheme.colorScheme.tertiary)
            },
        )
        HeartStat(
            title = "Resting",
            value = resting?.let { "$it" } ?: "—",
            unit = "bpm",
            detail = baseline?.let { "7-day $it" } ?: "No baseline yet",
            source = null,
            modifier = Modifier.weight(1f),
            icon = {
                Icon(Icons.Filled.Favorite, contentDescription = "Resting heart rate", tint = MaterialTheme.colorScheme.primary)
            },
        )
    }
}

@Composable
private fun HeartStat(
    title: String,
    value: String,
    unit: String,
    detail: String,
    source: String?,
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    Card(
        modifier.semantics { contentDescription = "$title $value $unit. $detail" },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (source != null) SourceLabel(source)
        }
    }
}

@Composable
private fun AvailabilityCard(
    title: String,
    body: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (primaryLabel != null && onPrimary != null) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(primaryLabel) }
            }
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(secondaryLabel) }
            }
        }
    }
}

@Composable
private fun RecoveryCard(recovery: RecoveryScore) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .semantics {
                        contentDescription = "Recovery score ${recovery.value?.toString() ?: "unavailable"} out of 100"
                    },
            ) {
                CircularProgressIndicator(
                    progress = { (recovery.value ?: 0) / 100f },
                    modifier = Modifier.size(88.dp),
                    strokeWidth = 8.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    recovery.value?.toString() ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recovery", style = MaterialTheme.typography.titleMedium)
                Text(
                    recovery.explanation.ifBlank {
                        "60% last night’s sleep (4–8 h) plus 40% resting heart rate versus your 7-day baseline."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricChip(icon: @Composable () -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SleepCard(
    duration: Duration?,
    start: Instant?,
    end: Instant?,
    source: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bedtime, contentDescription = "Sleep")
                Spacer(Modifier.width(8.dp))
                Text("Last night’s sleep", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (source != null) SourceLabel(source)
            }
            Text(
                duration?.let { formatDuration(it) } ?: "No sleep recorded",
                style = MaterialTheme.typography.headlineSmall,
            )
            val formatter = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
            if (start != null && end != null) {
                Text(
                    "${formatter.format(start)} – ${formatter.format(end)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (duration == null) {
                Text(
                    "Sleep sync on this watch is still unverified. A later sync may fill this in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

private fun linkLabel(state: ClientState): String = when (state) {
    ClientState.Idle -> "Not connected"
    ClientState.Connecting -> "Connecting…"
    ClientState.Discovering -> "Finding services…"
    ClientState.Ready -> "Connected"
    ClientState.Syncing -> "Syncing…"
    ClientState.Disconnecting -> "Disconnecting…"
    ClientState.Failed -> "Connection failed"
}

private fun formatSyncTime(epochMs: Long): String {
    if (epochMs <= 0L) return "Not synced yet"
    return DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}

private fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.minusHours(hours).toMinutes()
    return "${hours}h ${minutes}m"
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
    } else {
        "${meters.toInt()} m"
    }
}

private fun blePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
