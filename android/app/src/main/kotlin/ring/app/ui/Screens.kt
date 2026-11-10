package ring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ring.app.Graph
import ring.app.ble.FoundDevice
import ring.app.ble.scanDevices
import ring.app.data.SampleType
import ring.app.data.Thresholds

private val TIME = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())
private val PERIODS = listOf("24 ore" to 24L * 3600, "7 giorni" to 7L * 86_400, "30 giorni" to 30L * 86_400)

private fun ago(epoch: Long): String {
    if (epoch == 0L) return "mai"
    val min = (Instant.now().epochSecond - epoch) / 60
    return if (min < 1) "adesso" else if (min < 60) "$min min fa" else "${min / 60} h fa"
}

@Composable
fun RingApp(graph: Graph) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Home", "Storico", "Impostazioni").forEachIndexed { i, label ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(graph)
                1 -> HistoryScreen(graph)
                else -> SettingsScreen(graph)
            }
        }
    }
}

@Composable
private fun HomeScreen(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val hr by graph.dao.observeLatest(SampleType.HR).collectAsStateWithLifecycle(null)
    val temp by graph.dao.observeLatest(SampleType.TEMP).collectAsStateWithLifecycle(null)
    val stress by graph.dao.observeLatest(SampleType.STRESS).collectAsStateWithLifecycle(null)
    val hrv by graph.dao.observeLatest(SampleType.HRV).collectAsStateWithLifecycle(null)
    val spo2 by graph.dao.observeLatest(SampleType.SPO2).collectAsStateWithLifecycle(null)
    val sleep by graph.dao.observeSleep(0).collectAsStateWithLifecycle(emptyList())
    val alerts by graph.dao.observeAlerts().collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (status.connected) "Ring connesso" else status.connectionText, style = MaterialTheme.typography.titleLarge)
                    status.battery?.let {
                        Text("Batteria ${it.level}%${if (it.charging) " · in carica" else ""}")
                        LinearProgressIndicator(progress = { it.level / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Ultima sincronizzazione: ${ago(status.lastSyncEpoch)}", style = MaterialTheme.typography.bodySmall)
                    status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { graph.scope.launchSync(graph) }, enabled = !status.syncing) {
                        Text(if (status.syncing) "Sincronizzazione…" else "Sincronizza ora")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Frequenza cardiaca live", style = MaterialTheme.typography.titleMedium)
                    val live = status.live
                    Text(
                        when {
                            status.measuring -> "Misuro… battiti ${status.liveBeats}/12 (30-60 s, dito fermo)"
                            live?.bpm != null -> "${live.bpm} bpm"
                            else -> "—"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    if (!status.measuring && live != null) {
                        Text(
                            "HRV: RMSSD ${live.rmssd?.let { "%.0f ms".format(it) } ?: "—"} · SDNN ${live.sdnn?.let { "%.0f ms".format(it) } ?: "—"} · ${live.beats} battiti",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(onClick = { graph.measureLive() }, enabled = !status.measuring) { Text("Misura ora") }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Cuore", hr?.let { "${it.value.toInt()} bpm" }, hr?.ts, Modifier.weight(1f))
                    Tile("Temperatura", temp?.let { "%.1f °C".format(it.value) }, temp?.ts, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Stress", stress?.let { "${it.value.toInt()}" }, stress?.ts, Modifier.weight(1f))
                    Tile("HRV", hrv?.let { "${it.value.toInt()} ms" }, hrv?.ts, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("SpO2", spo2?.let { "${it.value.toInt()} %" }, spo2?.ts, Modifier.weight(1f))
                    val last = sleep.firstOrNull()
                    Tile("Sonno", last?.let { "%dh %02dm".format((it.end - it.start) / 3600, (it.end - it.start) / 60 % 60) }, last?.end, Modifier.weight(1f))
                }
            }
        }
        if (alerts.isNotEmpty()) {
            item { Text("Alert recenti", style = MaterialTheme.typography.titleMedium) }
            items(alerts.take(5)) { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(a.message)
                        Text(TIME.format(Instant.ofEpochSecond(a.ts)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchSync(graph: Graph) {
    launch { graph.syncNow() }
}

@Composable
private fun Tile(title: String, value: String?, ts: Long?, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value ?: "—", style = MaterialTheme.typography.titleLarge)
            Text(ts?.let { ago(it) } ?: "", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryScreen(graph: Graph) {
    var period by remember { mutableIntStateOf(0) }
    val to = remember(period) { Instant.now().epochSecond }
    val from = to - PERIODS[period].second
    val hr by remember(period) { graph.dao.observe(SampleType.HR, from, to) }.collectAsStateWithLifecycle(emptyList())
    val temp by remember(period) { graph.dao.observe(SampleType.TEMP, from, to) }.collectAsStateWithLifecycle(emptyList())
    val stress by remember(period) { graph.dao.observe(SampleType.STRESS, from, to) }.collectAsStateWithLifecycle(emptyList())
    val hrv by remember(period) { graph.dao.observe(SampleType.HRV, from, to) }.collectAsStateWithLifecycle(emptyList())
    val hrvLive by remember(period) { graph.dao.observe(SampleType.HRV_LIVE, from, to) }.collectAsStateWithLifecycle(emptyList())
    val spo2 by remember(period) { graph.dao.observe(SampleType.SPO2, from, to) }.collectAsStateWithLifecycle(emptyList())
    val sleep by remember(period) { graph.dao.observeSleep(from) }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PERIODS.forEachIndexed { i, (label, _) -> FilterChip(selected = period == i, onClick = { period = i }, label = { Text(label) }) }
            }
        }
        item { MetricCard("Frequenza cardiaca", " bpm", hr, from, to, Color(0xFFE53935), 20.0) }
        item { MetricCard("Temperatura cutanea", " °C", temp, from, to, Color(0xFFFB8C00), 1.0, decimals = 1) }
        item { MetricCard("Stress", "", stress, from, to, Color(0xFF8E24AA), 20.0) }
        item { MetricCard("HRV", " ms", hrv, from, to, Color(0xFF3949AB), 20.0) }
        item { MetricCard("HRV live (RMSSD)", " ms", hrvLive, from, to, Color(0xFF5E35B1), 20.0) }
        item { MetricCard("SpO2", " %", spo2, from, to, Color(0xFF00897B), 4.0) }
        if (sleep.isNotEmpty()) {
            item { Text("Sonno", style = MaterialTheme.typography.titleMedium) }
            items(sleep) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        val total = (s.end - s.start) / 60
                        Text("${TIME.format(Instant.ofEpochSecond(s.start))} → ${TIME.format(Instant.ofEpochSecond(s.end))}  (${total / 60}h ${total % 60}m)")
                        Text("leggero ${s.lightMin}m · profondo ${s.deepMin}m · REM ${s.remMin}m · sveglio ${s.awakeMin}m", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(graph: Graph) {
    val context = LocalContext.current
    val status by graph.status.collectAsStateWithLifecycle()
    val thresholds by graph.settings.thresholds.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(graph.settings.deviceAddress) }
    var interval by remember { mutableIntStateOf(graph.settings.syncIntervalMin) }
    var alertsOn by remember { mutableStateOf(graph.settings.alertsEnabled) }
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<FoundDevice>() }
    val scope = rememberCoroutineScope()
    val alerts by graph.dao.observeAlerts().collectAsStateWithLifecycle(emptyList())

    LaunchedEffect(scanning) {
        if (scanning) {
            found.clear()
            scanDevices(context).collect { d -> if (found.none { it.address == d.address }) found += d }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ring", style = MaterialTheme.typography.titleMedium)
                    Text(address ?: "nessun ring selezionato")
                    OutlinedButton(onClick = { scanning = !scanning }) { Text(if (scanning) "Ferma ricerca" else "Cerca ring") }
                    if (scanning) {
                        found.sortedByDescending { it.rssi }.forEach { d ->
                            OutlinedButton(onClick = {
                                graph.settings.deviceAddress = d.address
                                address = d.address
                                scanning = false
                            }) { Text("${d.name}  ${d.address}  (${d.rssi} dBm)") }
                        }
                    }
                    Text("Sincronizza ogni", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30, 60).forEach { m ->
                            FilterChip(selected = interval == m, onClick = { interval = m; graph.settings.syncIntervalMin = m }, label = { Text("$m min") })
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Monitoraggio automatico del ring", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { graph.refreshMonitors() }) { Text("Leggi stato dal ring") }
                    listOf("hr" to "Frequenza cardiaca (ogni 5 min)", "SPO2" to "SpO2", "STRESS" to "Stress", "HRV" to "HRV", "TEMPERATURE" to "Temperatura").forEach { (key, label) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label)
                            Switch(checked = status.monitors[key] == true, onCheckedChange = { graph.setMonitor(key, it) }, enabled = status.monitors.containsKey(key))
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Alert", style = MaterialTheme.typography.titleMedium)
                        Switch(checked = alertsOn, onCheckedChange = { alertsOn = it; graph.settings.alertsEnabled = it })
                    }
                    Stepper("FC alta (bpm)", thresholds.hrHigh.toDouble(), 5.0) { graph.settings.setThresholds(thresholds.copy(hrHigh = it.toInt())) }
                    Stepper("FC bassa (bpm)", thresholds.hrLow.toDouble(), 5.0) { graph.settings.setThresholds(thresholds.copy(hrLow = it.toInt())) }
                    Stepper("Temperatura alta (°C)", thresholds.tempHigh, 0.1) { graph.settings.setThresholds(thresholds.copy(tempHigh = it)) }
                    Stepper("Stress alto", thresholds.stressHigh.toDouble(), 5.0) { graph.settings.setThresholds(thresholds.copy(stressHigh = it.toInt())) }
                    Stepper("SpO2 bassa (%)", thresholds.spo2Low.toDouble(), 1.0) { graph.settings.setThresholds(thresholds.copy(spo2Low = it.toInt())) }
                    Stepper("Batteria bassa (%)", thresholds.batteryLow.toDouble(), 5.0) { graph.settings.setThresholds(thresholds.copy(batteryLow = it.toInt())) }
                    OutlinedButton(onClick = { scope.launch { graph.dao.clearAlerts() } }, enabled = alerts.isNotEmpty()) { Text("Cancella storico alert (${alerts.size})") }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Double, step: Double, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(value - step) }) { Text("−") }
            Text(if (step < 1) "%.1f".format(value) else "%.0f".format(value), modifier = Modifier.padding(top = 10.dp))
            OutlinedButton(onClick = { onChange(value + step) }) { Text("+") }
        }
    }
}
