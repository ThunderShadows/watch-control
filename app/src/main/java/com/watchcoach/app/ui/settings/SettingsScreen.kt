package com.watchcoach.app.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchcoach.app.data.health.HealthAvailability
import com.watchcoach.app.data.health.HealthConnectRepository
import com.watchcoach.app.data.settings.AlertSettings
import com.watchcoach.app.data.settings.FeatureSettings
import com.watchcoach.app.desktop.DesktopCompanionClient
import com.watchcoach.app.ui.activityViewModel
import com.watchcoach.app.ui.alerts.AlertsViewModel
import com.watchcoach.app.ui.components.SectionLabel
import com.watchcoach.app.ui.components.WatchCoachTopBar
import com.watchcoach.app.ui.dashboard.DashboardViewModel
import com.watchcoach.app.ui.gestures.GestureLabViewModel
import com.watchcoach.app.ui.sync.WatchSyncViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenScanner: () -> Unit,
    onOpenLog: () -> Unit,
    alertsViewModel: AlertsViewModel = activityViewModel(),
    gestureViewModel: GestureLabViewModel = activityViewModel(),
    dashboardViewModel: DashboardViewModel = activityViewModel(),
    syncViewModel: WatchSyncViewModel = activityViewModel(),
) {
    val settings by alertsViewModel.settings.collectAsStateWithLifecycle()
    val features by alertsViewModel.features.collectAsStateWithLifecycle()
    val gestureSettings by gestureViewModel.settings.collectAsStateWithLifecycle()
    val health by dashboardViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ageText by remember { mutableStateOf<String?>(null) }
    val shownAge = ageText ?: features.ageYears.toString()
    var notificationsAllowed by remember { mutableStateOf(alertsViewModel.notificationsAllowed) }
    var hasBle by remember { mutableStateOf(syncViewModel.hasBlePermissions()) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var confirmRaise by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val desktop = remember { DesktopCompanionClient() }

    androidx.compose.runtime.LaunchedEffect(gestureSettings.desktopHost, gestureSettings.desktopPort) {
        if (host.isBlank() && gestureSettings.desktopHost.isNotBlank()) host = gestureSettings.desktopHost
        if (port.isBlank() || port == "8765") port = gestureSettings.desktopPort.toString()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = alertsViewModel.notificationsAllowed }
    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasBle = syncViewModel.hasBlePermissions() }
    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted -> dashboardViewModel.onPermissionsResult(granted) }

    Scaffold(topBar = { WatchCoachTopBar(title = "Settings") }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel(
                title = "Alerts & sync",
                subtitle = "Reminders and the hourly watch sync. A background job runs about once an hour.",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Notifications are off", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Android 13 and later needs permission before inactivity or heart-rate alerts can appear.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Allow notifications") }
                    }
                }
            }
            InactivityCard(settings, alertsViewModel)
            ElevatedHrCard(settings, alertsViewModel)
            BackgroundSyncCard(features, alertsViewModel)
            WeeklyReportCard(features, alertsViewModel)

            SectionLabel(
                title = "Permissions",
                subtitle = "Bluetooth is required to sync. Health Connect is optional.",
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (hasBle) "Bluetooth is allowed" else "Bluetooth isn’t allowed yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Scan and sync need Bluetooth. On Android 12 and later that is Nearby devices; on older phones it is location, used only to scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!hasBle) {
                        Button(
                            onClick = { bleLauncher.launch(blePermissions()) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Allow Bluetooth") }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Text(
                            if (notificationsAllowed) "Notifications are allowed" else "Notifications are off",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!notificationsAllowed) {
                            OutlinedButton(
                                onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("Allow notifications") }
                        }
                    }
                    HealthPermissionBlock(
                        availability = health.availability,
                        hasPermissions = health.hasPermissions,
                        onGrant = { healthLauncher.launch(dashboardViewModel.requiredPermissions) },
                        onInstall = {
                            runCatching { context.startActivity(HealthConnectRepository.installOrUpdateIntent()) }
                        },
                        onOpenSettings = {
                            runCatching { context.startActivity(HealthConnectRepository.settingsIntent()) }
                        },
                    )
                }
            }

            SectionLabel(
                title = "Battery optimisation",
                subtitle = "Hourly sync is more reliable when the phone does not freeze Watch Coach.",
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "On a OnePlus, open Battery → Battery optimization → Watch Coach → Don’t optimize, and allow Autostart if that menu is there.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Leave CB SmartFit force-stopped. If it is connected, background sync fails and retries later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { openBatterySettings(context) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Open battery-optimization settings") }
                }
            }

            SectionLabel(
                title = "Age",
                subtitle = "Used for heart-rate zones (220 − age) and for the raise-to-wake profile.",
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = shownAge,
                        onValueChange = {
                            val next = it.filter { ch -> ch.isDigit() }.take(3)
                            ageText = next
                            next.toIntOrNull()?.let(alertsViewModel::setAge)
                        },
                        label = { Text("Age") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Age in years" },
                        singleLine = true,
                    )
                }
            }

            SectionLabel(
                title = "Laptop companion",
                subtitle = "Same Wi-Fi as the phone. On Linux, run the companion in an X11/Xorg session, not Wayland.",
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Start desktop/watch_coach_companion.py with the token below. Then enter the computer’s LAN address.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Laptop IP") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Laptop IP address" },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Companion port" },
                        singleLine = true,
                    )
                    Text("Shared token", style = MaterialTheme.typography.labelLarge)
                    Text(
                        gestureSettings.desktopToken.ifBlank { "Creating a token…" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { gestureViewModel.setDesktop(host, port.toIntOrNull() ?: 8765) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Save") }
                        OutlinedButton(
                            onClick = { gestureViewModel.rotateToken() },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("New token") }
                    }
                    Button(
                        onClick = {
                            val p = port.toIntOrNull() ?: 8765
                            gestureViewModel.setDesktop(host, p)
                            scope.launch {
                                val r = desktop.ping(host, p, gestureSettings.desktopToken)
                                status = r.fold({ "Ping ok: $it" }, { "Ping failed: ${it.message}" })
                                Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Ping laptop") }
                    OutlinedButton(
                        onClick = {
                            val p = port.toIntOrNull() ?: 8765
                            scope.launch {
                                val r = desktop.key(host, p, gestureSettings.desktopToken, "right")
                                status = r.fold({ "Sent Right arrow" }, { it.message ?: "Key failed" })
                                Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Test: next-slide key") }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            SectionLabel(
                title = "Raise-to-wake",
                subtitle = "This rewrites the whole user-profile packet, not a raise-to-wake switch by itself.",
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Gadgetbridge stores raise-hand-to-display as byte 10 of a 19-byte A9 user-info command. " +
                            "Sending it also overwrites height, weight, screen timeout, step goal, heart-rate alerts, age, gender, and temperature units. " +
                            "Save the values below first, and only continue if they are what you want on the watch.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    UserInfoFields(gestureSettings) { height, weight, timeout, goal, male, celsius ->
                        gestureViewModel.saveUserInfo(
                            height, weight, timeout, goal, male, celsius, gestureSettings.ageYears,
                        )
                        Toast.makeText(context, "Profile defaults saved on this phone", Toast.LENGTH_SHORT).show()
                    }
                    Button(
                        onClick = { confirmRaise = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Enable raise-to-wake") }
                    OutlinedButton(
                        onClick = { confirmRaise = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Disable raise-to-wake") }
                }
            }

            SectionLabel(
                title = "Advanced",
                subtitle = "Diagnostics. Day-to-day sync does not need these.",
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The scanner lists nearby watches and GATT services. The raw log is the hex the watch actually sent. Both screens scroll.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onOpenScanner,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("BLE scanner") }
                    OutlinedButton(
                        onClick = onOpenLog,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Raw BLE log") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    val pending = confirmRaise
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmRaise = null },
            title = { Text(if (pending) "Enable raise-to-wake?" else "Disable raise-to-wake?") },
            text = {
                Text(
                    "This sends the 19-byte A9 user-info packet with raise-hand=${if (pending) "1" else "0"} " +
                        "and the height, weight, timeout, goal, age, gender, and units saved above. " +
                        "Heart-rate alerts are sent as off. The write is logged in the raw log. " +
                        "No other settings commands are sent. The whole profile is rewritten.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRaise = null
                    scope.launch {
                        val result = gestureViewModel.writeRaiseToWake(pending, gestureSettings)
                        status = result.fold(
                            {
                                if (pending) "A9 sent (raise-to-wake on). Check the raw log."
                                else "A9 sent (raise-to-wake off). Check the raw log."
                            },
                            { "A9 write failed: ${it.message}" },
                        )
                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Send A9") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRaise = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HealthPermissionBlock(
    availability: HealthAvailability,
    hasPermissions: Boolean,
    onGrant: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (availability) {
        HealthAvailability.Unavailable -> Text(
            "Health Connect isn’t available on this phone. Watch sync still works.",
            style = MaterialTheme.typography.bodyMedium,
        )
        HealthAvailability.NotInstalled, HealthAvailability.UpdateRequired -> {
            Text(
                if (availability is HealthAvailability.UpdateRequired) {
                    "Health Connect needs an update before Watch Coach can read it."
                } else {
                    "Health Connect isn’t installed. On Android 13 and lower, install it from the Play Store."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Install or update Health Connect") }
        }
        HealthAvailability.Available -> {
            Text(
                if (hasPermissions) "Health Connect read access is on."
                else "Health Connect access is off. Grant it only if another app already writes steps or heart rate there.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!hasPermissions) {
                Button(
                    onClick = onGrant,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Grant Health Connect") }
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Open Health Connect settings") }
        }
    }
}

@Composable
private fun BackgroundSyncCard(features: FeatureSettings, viewModel: AlertsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hourly background sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Once an hour, Watch Coach connects to the saved watch, runs the same query and time-sync as Sync, stores the results, and disconnects. " +
                            "A low-priority notification shows while Bluetooth is in use.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = features.backgroundSyncEnabled,
                    onCheckedChange = viewModel::setBackgroundSync,
                    modifier = Modifier.semantics { contentDescription = "Hourly background sync" },
                )
            }
            val last = features.lastBackgroundSyncEpochMs
            if (last > 0) {
                val whenTxt = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(last))
                val ok = features.lastBackgroundSyncOk
                Text(
                    "Last background sync: $whenTxt" + when (ok) {
                        true -> " (ok)"
                        false -> " (failed)"
                        null -> ""
                    },
                )
                features.lastBackgroundSyncMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("No background sync has run yet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WeeklyReportCard(features: FeatureSettings, viewModel: AlertsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Weekly report notification", style = MaterialTheme.typography.titleMedium)
                Text(
                    "On Sunday at 18:00 local time, post a summary of the Monday–Sunday week.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = features.weeklyReportEnabled,
                onCheckedChange = viewModel::setWeeklyReport,
                modifier = Modifier.semantics { contentDescription = "Weekly report notification" },
            )
        }
    }
}

@Composable
private fun InactivityCard(settings: AlertSettings, viewModel: AlertsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Inactivity reminder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "If steps in the last 2 hours stay below the threshold during daytime, Watch Coach nudges you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.inactivityAlertsEnabled,
                    onCheckedChange = viewModel::setInactivityEnabled,
                    modifier = Modifier.semantics { contentDescription = "Inactivity reminder" },
                )
            }
            Text("Threshold: ${settings.inactivityStepThreshold} steps / 2 hours")
            Slider(
                value = settings.inactivityStepThreshold.toFloat(),
                onValueChange = { viewModel.setInactivityThreshold(it.roundToInt()) },
                valueRange = 50f..2000f,
                steps = 38,
                modifier = Modifier.semantics { contentDescription = "Inactivity step threshold" },
            )
            Text("Daytime window: ${hourLabel(settings.daytimeStartHour)} – ${hourLabel(settings.daytimeEndHour)}")
            Text("Start", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = settings.daytimeStartHour.toFloat(),
                onValueChange = { viewModel.setDaytimeHours(it.roundToInt(), settings.daytimeEndHour) },
                valueRange = 0f..23f,
                steps = 22,
                modifier = Modifier.semantics { contentDescription = "Daytime start hour" },
            )
            Text("End", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = settings.daytimeEndHour.toFloat(),
                onValueChange = { viewModel.setDaytimeHours(settings.daytimeStartHour, it.roundToInt()) },
                valueRange = 1f..24f,
                steps = 22,
                modifier = Modifier.semantics { contentDescription = "Daytime end hour" },
            )
        }
    }
}

@Composable
private fun ElevatedHrCard(settings: AlertSettings, viewModel: AlertsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Elevated resting heart rate", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Alert when today’s resting heart rate is more than this many bpm above your 7-day baseline. Default is 10.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.elevatedHrAlertsEnabled,
                    onCheckedChange = viewModel::setElevatedHrEnabled,
                    modifier = Modifier.semantics { contentDescription = "Elevated resting heart rate alert" },
                )
            }
            Text("Delta: +${settings.elevatedHrBpmDelta} bpm over baseline")
            Slider(
                value = settings.elevatedHrBpmDelta.toFloat(),
                onValueChange = { viewModel.setElevatedHrDelta(it.roundToInt()) },
                valueRange = 5f..30f,
                steps = 24,
                modifier = Modifier.semantics { contentDescription = "Resting heart rate alert delta" },
            )
        }
    }
}

@Composable
private fun UserInfoFields(
    settings: FeatureSettings,
    onSave: (Int, Int, Int, Int, Boolean, Boolean) -> Unit,
) {
    var height by remember(settings.heightCm) { mutableStateOf(settings.heightCm.toString()) }
    var weight by remember(settings.weightKg) { mutableStateOf(settings.weightKg.toString()) }
    var timeout by remember(settings.screenTimeoutSec) { mutableStateOf(settings.screenTimeoutSec.toString()) }
    var goal by remember(settings.stepsGoal) { mutableStateOf(settings.stepsGoal.toString()) }
    var male by remember(settings.userMale) { mutableStateOf(settings.userMale) }
    var celsius by remember(settings.celsius) { mutableStateOf(settings.celsius) }
    OutlinedTextField(
        height,
        { height = it.filter { ch -> ch.isDigit() }.take(3) },
        label = { Text("Height cm") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        weight,
        { weight = it.filter { ch -> ch.isDigit() }.take(3) },
        label = { Text("Weight kg") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        timeout,
        { timeout = it.filter { ch -> ch.isDigit() }.take(2) },
        label = { Text("Screen timeout sec (5–15)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        goal,
        { goal = it.filter { ch -> ch.isDigit() }.take(5) },
        label = { Text("Step goal") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Text(
        "Age sent with this packet: ${settings.ageYears}. Change it in the Age section above.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Male", Modifier.weight(1f))
        Switch(
            checked = male,
            onCheckedChange = { male = it },
            modifier = Modifier.semantics { contentDescription = "Male" },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Celsius", Modifier.weight(1f))
        Switch(
            checked = celsius,
            onCheckedChange = { celsius = it },
            modifier = Modifier.semantics { contentDescription = "Celsius" },
        )
    }
    OutlinedButton(
        onClick = {
            onSave(
                height.toIntOrNull() ?: 170,
                weight.toIntOrNull() ?: 70,
                timeout.toIntOrNull() ?: 5,
                goal.toIntOrNull() ?: 8000,
                male,
                celsius,
            )
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Save profile defaults") }
}

private fun hourLabel(hour: Int): String {
    val h = hour.coerceIn(0, 24)
    if (h == 24 || h == 0) return "12:00 AM"
    val suffix = if (h < 12) "AM" else "PM"
    val twelve = when {
        h == 0 || h == 24 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$twelve:00 $suffix"
}

private fun blePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun openBatterySettings(context: android.content.Context) {
    val pkg = context.packageName
    val pm = context.getSystemService(PowerManager::class.java)
    val intent = if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$pkg"))
    } else {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
