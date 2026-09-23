package dev.local.audiodoctor

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

class MainActivity : ComponentActivity() {
    private lateinit var diagnostics: AudioDiagnostics
    private lateinit var repository: DiagnosticRepository
    private var state: AppState? = null
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = deviceChange("added", addedDevices)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = deviceChange("removed", removedDevices)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = AudioDiagnostics(this)
        repository = DiagnosticRepository(this)
        diagnostics.manager.registerAudioDeviceCallback(deviceCallback, null)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val appState = remember { AppState(repository.load(), diagnostics.snapshot()) }
                state = appState
                AudioDoctorScreen(appState)
            }
        }
    }

    override fun onResume() { super.onResume(); state?.refresh("App resumed") }
    override fun onDestroy() {
        diagnostics.manager.unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }

    private fun deviceChange(action: String, devices: Array<out AudioDeviceInfo>) = runOnUiThread {
        state?.apply {
            val names = devices.joinToString { "type ${it.type}, id ${it.id}" }
            add(LogEntry(type = "audio_device_$action", message = "Audio device(s) $action: $names", snapshot = diagnostics.snapshot()))
            snapshot = diagnostics.snapshot()
        }
    }

    inner class AppState(initial: List<LogEntry>, initialSnapshot: AudioSnapshot) {
        var entries by mutableStateOf(initial)
        var snapshot by mutableStateOf(initialSnapshot)
        var testing by mutableStateOf(false)
        var pendingTest by mutableStateOf<Pair<AudioSnapshot, JSONObject>?>(null)

        fun add(entry: LogEntry) { entries = listOf(entry) + entries; repository.save(entries) }
        fun refresh(reason: String? = null) {
            val old = snapshot
            snapshot = diagnostics.snapshot()
            if (old.mode != snapshot.mode) add(LogEntry(type = "audio_mode_changed", message = "Audio mode changed: ${old.mode} → ${snapshot.mode}", snapshot = snapshot))
            else if (reason == "manual") add(LogEntry(type = "status_refreshed", message = "Status refreshed", snapshot = snapshot))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AudioDoctorScreen(s: AppState) {
        val scope = rememberCoroutineScope()
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) runCatching { contentResolver.openOutputStream(uri)?.use { it.write(repository.export(s.entries).toByteArray()) } }
                .onSuccess { s.add(LogEntry(type = "log_exported", message = "Diagnostic log exported")) }
                .onFailure { s.add(LogEntry(type = "export_error", message = "Export failed: ${it.message}")) }
        }
        Scaffold(topBar = { TopAppBar(title = { Text("Audio Doctor — Stage 1") }) }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { StatusCard(s.snapshot) { s.refresh("manual") } }
                item {
                    TestCard(s.testing) {
                        if (s.snapshot.mode in listOf("IN_CALL", "IN_COMMUNICATION", "CALL_SCREENING")) {
                            s.add(LogEntry(type = "test_blocked", message = "Test not run because Android reports a call/communication audio mode", snapshot = s.snapshot))
                        } else scope.launch {
                            s.testing = true
                            val before = diagnostics.snapshot()
                            s.add(LogEntry(type = "test_started", message = "Controlled audio test started", snapshot = before))
                            val result = withContext(Dispatchers.IO) { diagnostics.runTest() }
                            s.testing = false; s.pendingTest = before to result; s.snapshot = diagnostics.snapshot()
                        }
                    }
                }
                item { MarkerCard { type, label -> s.add(LogEntry(type = type, message = label, snapshot = diagnostics.snapshot())); s.snapshot = diagnostics.snapshot() } }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Diagnostic log", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Button(onClick = { exportLauncher.launch("audio-doctor-${Instant.now().toString().replace(':', '-')}.json") }) { Text("Export JSON") }
                    }
                }
                if (s.entries.isEmpty()) item { Text("No events yet.") }
                items(s.entries, key = { it.timestamp + it.type }) { EntryCard(it) }
            }
        }
        s.pendingTest?.let { pending ->
            AlertDialog(onDismissRequest = {}, title = { Text("Did you hear the sound?") }, text = {
                Column { Text("Choose what you actually heard. Stream progress is not proof that the speaker made sound.")
                    listOf("Yes", "No", "Distorted or static", "Unsure").forEach { answer ->
                        TextButton(onClick = {
                            pending.second.put("audibilityAnswer", answer)
                            s.add(LogEntry(type = "test_result", message = "Test complete — answer: $answer", snapshot = pending.first, details = pending.second))
                            s.pendingTest = null
                        }, modifier = Modifier.fillMaxWidth()) { Text(answer) }
                    }
                }
            }, confirmButton = {})
        }
    }

    @Composable private fun StatusCard(x: AudioSnapshot, refresh: () -> Unit) = ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Audio status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Button(onClick = refresh) { Text("Refresh") } }
            Value("Device", "${Build.MANUFACTURER} ${Build.MODEL}"); Value("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Value("Audio mode", x.mode); Value("Media volume", "${x.volume} / ${x.maxVolume}; muted=${x.muted}")
            Value("Music active", "${x.musicActive} (Android-reported; not proof of audible sound)")
            Value("Connected external audio devices", x.connectedDevices.ifEmpty { listOf("None reported") }.joinToString("\n"))
            Value("Available output devices", x.outputDevices.ifEmpty { listOf("None reported by public API") }.joinToString("\n"))
            Value("Snapshot", x.timestamp)
            Text("Android cannot reliably report whether a physical speaker actually produced sound or whether another app's audio is audible.", color = MaterialTheme.colorScheme.error)
        }
    }

    @Composable private fun TestCard(testing: Boolean, run: () -> Unit) = ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Controlled audio test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Plays a brief, quiet two-note tone on the normal media path. Volume and routing are never changed.")
            Button(onClick = run, enabled = !testing) { Text(if (testing) "Testing…" else "Test Audio") }
        }
    }

    @Composable private fun MarkerCard(mark: (String, String) -> Unit) = ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Manual observation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ mark("audio_failed", "Audio Failed") }, Modifier.weight(1f)) { Text("Audio Failed") }
                Button({ mark("audio_restored", "Audio Restored") }, Modifier.weight(1f)) { Text("Audio Restored") }
            }
            Button({ mark("static_distortion", "Static/Distortion") }, Modifier.fillMaxWidth()) { Text("Static/Distortion") }
        }
    }

    @Composable private fun Value(name: String, value: String) { Text(name, fontWeight = FontWeight.SemiBold); Text(value) }
    @Composable private fun EntryCard(entry: LogEntry) = OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) { Text(entry.message, fontWeight = FontWeight.Bold); Text(entry.timestamp, style = MaterialTheme.typography.bodySmall); Text(entry.type, style = MaterialTheme.typography.labelSmall) }
    }
}
