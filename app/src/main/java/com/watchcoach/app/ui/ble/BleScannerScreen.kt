
package com.watchcoach.app.ui.ble

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchcoach.app.ble.BleConnectionState
import com.watchcoach.app.ble.GattInspection
import com.watchcoach.app.ble.GattServiceInfo
import com.watchcoach.app.ble.GloryFitUuids
import com.watchcoach.app.ble.ScannedDevice
import com.watchcoach.app.ui.components.EmptyState
import com.watchcoach.app.ui.components.WatchCoachTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleScannerScreen(
    onBack: (() -> Unit)? = null,
    viewModel: BleScannerViewModel = viewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val inspection by viewModel.inspection.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var hasPermissions by remember { mutableStateOf(viewModel.hasPermissions()) }
    val context = LocalContext.current

    val connected = connectionState == BleConnectionState.Connected ||
        connectionState == BleConnectionState.Connecting ||
        connectionState == BleConnectionState.Discovering
    var devicesExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(connected) {
        devicesExpanded = !connected
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPermissions = viewModel.hasPermissions()
        if (hasPermissions) viewModel.startScan()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    val inspect = inspection
    val serviceCount = inspect?.services?.size ?: 0
    LaunchedEffect(inspect?.address, serviceCount, connectionState) {
        if (inspect != null && serviceCount > 0 && connectionState == BleConnectionState.Connected) {
            viewModel.logInspectionIfNew(inspect, connectionState)
        }
    }

    Scaffold(
        topBar = { WatchCoachTopBar(title = "BLE scanner", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Scan for nearby watches, then connect to list every GATT service. " +
                        "This list scrolls, including while you are connected. " +
                        "Names containing “Everest” or “CB” are highlighted. " +
                        "Reads only — tap Sync on the Dashboard to fetch activity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!hasPermissions) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Bluetooth permission needed", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Android 12+ requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT to find " +
                                    "and inspect nearby watches.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { permissionLauncher.launch(blePermissions()) }) {
                                Text("Grant Bluetooth permissions")
                            }
                        }
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!viewModel.isBluetoothEnabled()) {
                            Text(
                                "Bluetooth is off. Turn it on in system settings, then scan again.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (scanning) {
                                Button(onClick = { viewModel.stopScan() }) { Text("Stop scan") }
                            } else {
                                Button(onClick = { viewModel.startScan() }) { Text("Scan nearby") }
                            }
                            if (connected) {
                                OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
                            }
                        }
                        Text(
                            if (scanning) "Scanning…" else "${devices.size} device(s) found",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            error?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (inspect != null) {
                item {
                    GattHeader(
                        inspection = inspect,
                        state = connectionState,
                        onCopy = {
                            val text = viewModel.copyAndLogUuids()
                            Toast.makeText(
                                context,
                                if (text != null) "UUIDs copied and written to the raw log" else "Nothing to copy yet",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
                items(
                    items = inspect.services,
                    key = { "svc-" + it.uuid.toString() },
                ) { service ->
                    GattServiceBlock(service, onRead = { charUuid ->
                        viewModel.read(service.uuid, charUuid)
                    })
                }
            }

            item {
                DeviceListHeader(
                    connected = connected,
                    expanded = devicesExpanded,
                    count = devices.size,
                    inspection = inspect,
                    onToggle = { devicesExpanded = !devicesExpanded },
                )
            }

            if (devicesExpanded && devices.isEmpty() && !scanning && hasPermissions) {
                item {
                    EmptyState(
                        title = "No watches in the list",
                        body = "Tap Scan nearby and keep the Everest 2.0 close. If it never appears, turn Bluetooth on and force-stop CB SmartFit, then scan again.",
                    )
                }
            }

            if (devicesExpanded) {
                items(devices, key = { "dev-" + it.address }) { device ->
                    DeviceRow(
                        device = device,
                        connecting = inspect?.address == device.address && connected,
                        onConnect = { viewModel.connect(device.address, device.name) },
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DeviceListHeader(
    connected: Boolean,
    expanded: Boolean,
    count: Int,
    inspection: GattInspection?,
    onToggle: () -> Unit,
) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse devices" else "Expand devices",
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (connected && inspection != null) {
                val label = inspection.address
                if (expanded) "Hide nearby devices ($count) · connected to $label"
                else "Nearby devices hidden · connected to $label — tap to show"
            } else {
                if (expanded) "Nearby devices ($count)" else "Show nearby devices ($count)"
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: ScannedDevice,
    connecting: Boolean,
    onConnect: () -> Unit,
) {
    val colors = if (device.isLikelyEverest) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (device.isLikelyEverest) Icons.Filled.Star else Icons.Filled.Bluetooth,
                contentDescription = if (device.isLikelyEverest) {
                    "Possible Everest or CB watch"
                } else {
                    "Bluetooth device"
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                    if (device.isLikelyEverest) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "possible Everest / CB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text("RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onConnect, enabled = !connecting) {
                Text(if (connecting) "Selected" else "Connect")
            }
        }
    }
}

@Composable
private fun GattHeader(
    inspection: GattInspection,
    state: BleConnectionState,
    onCopy: () -> Unit,
) {
    val container = if (inspection.gloryFitUuidsPresent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("GATT inspection", style = MaterialTheme.typography.titleMedium)
            Text(
                "State: ${state.name} · ${inspection.address}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                inspection.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (inspection.gloryFitUuidsPresent) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                "${inspection.services.size} service(s) — scroll this screen to see all of them",
                style = MaterialTheme.typography.labelLarge,
            )
            Button(onClick = onCopy, enabled = inspection.services.isNotEmpty()) {
                Text("Copy all UUIDs")
            }
        }
    }
}

@Composable
private fun GattServiceBlock(
    service: GattServiceInfo,
    onRead: (java.util.UUID) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val short = GloryFitUuids.shortLabel(service.uuid)
            Text(
                "Service $short" + if (service.isGloryFit) "  · GloryFit" else "",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(service.uuid.toString(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            service.characteristics.forEach { ch ->
                val chShort = GloryFitUuids.shortLabel(ch.uuid)
                Text(
                    "  $chShort  [${ch.properties.joinToString(", ").ifEmpty { "none" }}]" +
                        if (ch.isGloryFit) "  · GloryFit" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "  ${ch.uuid}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ch.lastReadHex != null) {
                    Text(
                        "  value: ${ch.lastReadHex}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                ch.readError?.let {
                    Text("  $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (ch.properties.contains("READ")) {
                    TextButton(onClick = { onRead(ch.uuid) }) {
                        Text("Read value (no write)")
                    }
                }
            }
        }
    }
}

private fun blePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
