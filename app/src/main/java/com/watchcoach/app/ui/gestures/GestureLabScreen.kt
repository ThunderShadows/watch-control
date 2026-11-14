package com.watchcoach.app.ui.gestures

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.ble.gloryfit.ClientState
import com.watchcoach.app.ble.gloryfit.WatchEvent
import com.watchcoach.app.ble.gloryfit.WatchEventKind
import com.watchcoach.app.data.settings.FeatureSettings
import com.watchcoach.app.data.settings.GestureMapping
import com.watchcoach.app.gestures.MappedAction
import com.watchcoach.app.gestures.PhoneAirMouse
import com.watchcoach.app.gestures.WatchEventService
import com.watchcoach.app.ui.activityViewModel
import com.watchcoach.app.ui.components.EmptyState
import com.watchcoach.app.ui.components.WatchCoachTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class GestureLabViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchCoachApplication
    val settings = app.featureSettings.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), FeatureSettings(),
    )
    val clientState = app.gloryFitClient.state
    val listening = WatchEventService.running
    val events = mutableStateListOf<WatchEvent>()

    init {
        viewModelScope.launch { app.featureSettings.ensureToken() }
        viewModelScope.launch {
            app.gloryFitClient.watchEvents.collect { ev ->
                events.add(0, ev)
                if (events.size > 300) events.removeRange(300, events.size)
            }
        }
    }

    fun startListening() = WatchEventService.start(getApplication())
    fun stopListening() = WatchEventService.stop(getApplication())

    fun setDesktop(host: String, port: Int) {
        viewModelScope.launch { app.featureSettings.setDesktop(host, port) }
    }

    fun rotateToken() {
        viewModelScope.launch { app.featureSettings.rotateToken() }
    }

    fun setMapping(kind: WatchEventKind, action: MappedAction, payload: String) {
        viewModelScope.launch {
            app.featureSettings.upsertMapping(GestureMapping(kind, action, payload))
        }
    }

    fun saveUserInfo(height: Int, weight: Int, timeout: Int, goal: Int, male: Boolean, celsius: Boolean, age: Int) {
        viewModelScope.launch {
            app.featureSettings.setUserInfoDefaults(height, weight, timeout, goal, male, celsius)
            app.featureSettings.setAge(age)
        }
    }

    suspend fun writeRaiseToWake(enable: Boolean, s: FeatureSettings): Result<Unit> = runCatching {
        val paired = app.deviceSettings.current()
        if (app.gloryFitClient.state.value != ClientState.Ready &&
            app.gloryFitClient.state.value != ClientState.Syncing
        ) {
            app.gloryFitClient.connect(paired.address, paired.name).getOrThrow()
        }
        app.gloryFitClient.writeUserInfo(
            heightCm = s.heightCm,
            weightKg = s.weightKg,
            screenTimeoutSec = s.screenTimeoutSec,
            stepsGoal = s.stepsGoal,
            raiseHandToWake = enable,
            hrAlertHigh = null,
            hrAlertLow = null,
            age = s.ageYears,
            male = s.userMale,
            celsius = s.celsius,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureLabScreen(viewModel: GestureLabViewModel = activityViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val state by viewModel.clientState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val airMouse = remember { PhoneAirMouse(context, scope) }
    var airOn by remember { mutableStateOf(false) }
    var airNote by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { airMouse.stop() }
    }

    Scaffold(topBar = { WatchCoachTopBar(title = "Gesture lab") }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What the watch can actually send", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Confirmed in Gadgetbridge: the watch can send events on 33F2 as opcode D1 " +
                            "(music play/pause, next, previous, volume, find phone, call hang-up) and C4 " +
                            "(camera open / shutter / close). After you start listening, use the watch’s music, camera, or find-phone screens.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "This watch does not send raw motion or a wrist-raise event to the phone. " +
                            "Lift-to-wake is a display setting (Settings → Raise-to-wake), not something the phone receives. " +
                            "Air-mouse below uses the phone’s sensors only.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Listen for watch events", style = MaterialTheme.typography.titleMedium)
                    Text("Link: ${linkLabel(state)}    Listener: ${if (listening) "on" else "off"}")
                    Text(
                        "Force-stop CB SmartFit first. Listening starts a connected-device foreground service, " +
                            "connects to the saved watch, and logs every non-zero 33F2/34F2 frame. " +
                            "Recognized D1/C4 events run the actions you map below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (listening) {
                        Button(
                            onClick = { viewModel.stopListening() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Stop listening") }
                    } else {
                        Button(
                            onClick = { viewModel.startListening() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Start listening") }
                    }
                }
            }

            if (viewModel.events.isEmpty()) {
                EmptyState(
                    title = "No frames yet",
                    body = "Start listening, then press a watch button or open camera, music, or find phone. " +
                        "If nothing arrives, force-stop CB SmartFit and start listening again. " +
                        "Unknown frames are still listed, so you can see what this firmware actually sends.",
                )
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Captured frames", style = MaterialTheme.typography.titleMedium)
                        viewModel.events.take(40).forEach { ev ->
                            val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(ev.epochMs))
                            val tag = if (ev.documented) "DOCUMENTED" else "UNKNOWN"
                            Text(
                                "$time  $tag  ${ev.kind.label}  ${ev.characteristic}  ${ev.rawHex}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Map documented events to actions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Laptop keystrokes use the IP and token in Settings → Laptop companion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WatchEventKind.entries.filter { it != WatchEventKind.UNKNOWN }.forEach { kind ->
                        val current = settings.mappings.firstOrNull { it.kind == kind }
                            ?: GestureMapping(kind, MappedAction.NONE)
                        MappingRow(kind, current) { action, payload ->
                            viewModel.setMapping(kind, action, payload)
                        }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Phone sensors (fallback air-mouse)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This uses the phone’s gyroscope, not the watch. Hold the phone and tilt to move the laptop pointer. " +
                            "The companion must already be running, and its address is the one saved in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!airMouse.available()) {
                        Text("This phone has no gyroscope, so air-mouse can’t run here.")
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(
                                if (airOn) "Air-mouse is on — tilt the phone" else "Air-mouse is off",
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = airOn,
                                onCheckedChange = { on ->
                                    if (on && settings.desktopHost.isBlank()) {
                                        airOn = false
                                        airNote = "Set the laptop IP and token in Settings, then try again."
                                        return@Switch
                                    }
                                    airOn = on
                                    airNote = null
                                    if (on) {
                                        airMouse.host = settings.desktopHost
                                        airMouse.port = settings.desktopPort
                                        airMouse.token = settings.desktopToken
                                        airMouse.start()
                                    } else {
                                        airMouse.stop()
                                    }
                                },
                                modifier = Modifier.semantics { contentDescription = "Phone-sensor air-mouse" },
                            )
                        }
                    }
                    airNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingRow(
    kind: WatchEventKind,
    current: GestureMapping,
    onChange: (MappedAction, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var payload by remember(current.payload) { mutableStateOf(current.payload) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kind.label, style = MaterialTheme.typography.bodyMedium)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = current.action.label,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics { contentDescription = "${kind.label} action" },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                label = { Text("Action") },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                MappedAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            expanded = false
                            onChange(action, payload)
                        },
                    )
                }
            }
        }
        if (current.action == MappedAction.OPEN_APP ||
            current.action == MappedAction.INTENT_URI ||
            current.action == MappedAction.DESKTOP_KEY
        ) {
            val label = when (current.action) {
                MappedAction.OPEN_APP -> "App package (e.g. com.android.chrome)"
                MappedAction.INTENT_URI -> "Intent URI (tasker:// or intent:…)"
                else -> "Key (right, left, space, playpause, f5…)"
            }
            OutlinedTextField(
                value = payload,
                onValueChange = {
                    payload = it
                    onChange(current.action, it)
                },
                label = { Text(label) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "${kind.label} $label" },
                singleLine = true,
            )
        }
    }
}
