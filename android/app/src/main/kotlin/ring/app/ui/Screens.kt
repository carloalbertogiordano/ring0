package ring.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import ring.app.Graph
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.app.data.Thresholds

private val DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())
private val PERIODS = listOf("24 h" to 24L * 3600, "7 gg" to 7L * 86_400, "30 gg" to 30L * 86_400)

/** Radice: primo avvio, errori di sistema (permessi, Bluetooth) oppure l'app con la barra a schede. */
@Composable
fun AppRoot(graph: Graph, startService: () -> Unit) {
    val ctx = LocalContext.current
    var onboarded by remember { mutableStateOf(graph.settings.onboarded) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val nearby = remember(tick) { Perms.hasNearby(ctx) }
    val btOn = remember(tick) { Perms.bluetoothOn(ctx) }
    Box(Modifier.fillMaxSize().background(Term.bg)) {
        when {
            !onboarded -> Onboarding(graph) { graph.settings.onboarded = true; onboarded = true; startService() }
            !nearby -> PermissionDenied()
            !btOn -> BluetoothOff { tick++ }
            else -> MainScaffold(graph) { onboarded = false }
        }
    }
}

@Composable
private fun SystemScreen(title: String, body: String, color: Color, action: String, onAction: () -> Unit, extra: @Composable () -> Unit = {}) =
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).border(BorderStroke(1.dp, color)), contentAlignment = Alignment.Center) { T("!", size = 28.sp, color = color, weight = FontWeight.Bold) }
            T(title, size = 22.sp, weight = FontWeight.Bold, align = TextAlign.Center)
            T(body, size = 12.sp, color = Term.dim, align = TextAlign.Center)
            extra()
        }
        Box(Modifier.weight(1f))
        TButton(action, onAction, Modifier.fillMaxWidth())
    }

@Composable
private fun BluetoothOff(onChanged: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }
    SystemScreen(
        "Bluetooth spento", "Senza Bluetooth l'app non può leggere l'anello. I dati già scaricati restano nell'app.",
        Term.crit, "Attiva Bluetooth", { launcher.launch(Intent(Perms.enableBluetoothAction)) },
    )
}

@Composable
private fun PermissionDenied() {
    val ctx = LocalContext.current
    SystemScreen(
        "Permesso negato", "L'app non vede nessun dispositivo Bluetooth perché \"Dispositivi nelle vicinanze\" è negato.",
        Term.crit, "Apri impostazioni app",
        { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
    ) {
        TCard {
            T("1  Apri le impostazioni dell'app", size = 12.sp)
            T("2  Vai su Permessi", size = 12.sp)
            T("3  Scegli Dispositivi nelle vicinanze e imposta Consenti", size = 12.sp)
        }
        T("Su GrapheneOS il permesso si chiama allo stesso modo. Non serve la posizione.", size = 11.sp, color = Term.dim, align = TextAlign.Center)
    }
}

@Composable
private fun MainScaffold(graph: Graph, onForget: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HomeScreen(graph)
                1 -> HistoryScreen(graph)
                else -> SettingsScreen(graph, onForget)
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF050C08)).border(BorderStroke(1.dp, Term.line2)).navigationBarsPadding()) {
            listOf("Home", "Storico", "Impost.").forEachIndexed { i, label ->
                T(
                    label.uppercase(), Modifier.weight(1f).clickable { tab = i }.padding(vertical = 16.dp), 11.sp,
                    if (tab == i) Term.accent else Term.faint, if (tab == i) FontWeight.Bold else FontWeight.Normal, TextAlign.Center, 1.4.sp,
                )
            }
        }
    }
}

private val PAD = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

@Composable
private fun HomeScreen(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val thresholds by graph.settings.thresholds.collectAsStateWithLifecycle()
    val alerts by graph.dao.observeAlerts().collectAsStateWithLifecycle(emptyList())
    val sleep by graph.dao.observeSleep(0).collectAsStateWithLifecycle(emptyList())
    val battery = status.battery
    val lowBattery = battery != null && !battery.charging && battery.level <= thresholds.batteryLow
    val recentAlert = alerts.firstOrNull { Instant.now().epochSecond - it.ts < 24 * 3600 && it.kind != "battery_low" }
    LazyColumn(contentPadding = PAD, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("RING", size = 15.sp, weight = FontWeight.Bold, spacing = 4.sp)
                when {
                    battery != null && status.connected -> TPill("${battery.level}%${if (battery.charging) " · carica" else ""}", if (lowBattery) Term.crit else Term.accent, pulse = true)
                    status.syncing -> TPill("sincronizzo…", Term.info, pulse = true)
                    else -> TPill("offline", Term.warn)
                }
            }
        }
        if (lowBattery && battery != null) {
            item { Banner("Ricarica l'anello · ${battery.level}%", "Sotto il 20% le misure automatiche possono fermarsi e l'anello si spegne.", Term.crit) }
        }
        recentAlert?.let { a -> item { Banner(a.message, DAY_TIME.format(Instant.ofEpochSecond(a.ts)), Term.warn) } }
        if (!status.connected && !status.syncing && status.connectFailures >= 2) item { NotFoundCard(graph) }
        item { LiveCard(graph) }
        item { ReadinessCard(graph) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("Cuore", SampleType.HR, "bpm", 0, Term.accent, graph, Modifier.weight(1f), warnAbove = thresholds.hrHigh.toDouble())
                    MetricTile("Temperatura", SampleType.TEMP, "°C", 1, Color(0xFFFF9D3B), graph, Modifier.weight(1f), warnAbove = thresholds.tempHigh)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("Stress", SampleType.STRESS, "", 0, Term.rem, graph, Modifier.weight(1f), warnAbove = thresholds.stressHigh.toDouble())
                    MetricTile("HRV", SampleType.HRV, "ms", 0, Term.info, graph, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("SpO2", SampleType.SPO2, "%", 0, Term.accent, graph, Modifier.weight(1f), warnBelow = thresholds.spo2Low.toDouble())
                    val last = sleep.firstOrNull()
                    Tile("Sonno", last?.let { "%dh %02d".format((it.end - it.start) / 3600, (it.end - it.start) / 60 % 60) } ?: "—", "", last?.let { "${clock(it.end)} · ${ago(it.end)}" } ?: "nessuna notte", Modifier.weight(1f)) {}
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("Ultima sync ${ago(status.lastSyncEpoch)}", size = 11.sp, color = Term.dim)
                TButton(if (status.syncing) "…" else "Sincronizza", { graph.scope.launch { graph.syncNow() } }, kind = BtnKind.GHOST, enabled = !status.syncing)
            }
            status.lastError?.let { T(it, Modifier.padding(top = 6.dp), 11.sp, Term.crit) }
        }
    }
}

@Composable
private fun LiveCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val live = status.live
    TCard(hot = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Cuore · live")
            T(if (status.measuring) "${status.liveBeats}/12 battiti" else live?.let { "${it.beats} battiti" } ?: "", size = 11.sp, color = Term.dim)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Num(live?.bpm?.toString() ?: "—", "bpm", 44.sp, if (live?.bpm != null) Term.accent else Term.faint)
            Column(horizontalAlignment = Alignment.End) {
                Label("HRV RMSSD")
                Num(live?.rmssd?.let { "%.0f".format(it) } ?: "—", "ms", 22.sp)
            }
        }
        live?.breathsPerMinute?.let {
            T("Respiro: %.0f/min (affidabilita' %d%%)".format(it, (live.breathConfidence * 100).toInt()), size = 12.sp, color = Term.info)
        }
        Ecg(active = status.measuring)
        if (status.measuring) T("Misuro… tieni il dito fermo (30-60 s per agganciare, poi i battiti)", size = 11.sp, color = Term.dim)
        TButton(if (status.measuring) "Misuro…" else "Misura ora", { graph.measureLive(false) }, Modifier.fillMaxWidth(), enabled = !status.measuring)
        TButton("Con respirazione (~2 min)", { graph.measureLive(true) }, Modifier.fillMaxWidth(), BtnKind.GHOST, enabled = !status.measuring)
    }
}

@Composable
private fun ReadinessCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val report = status.readiness
    val r = report?.readiness
    TCard(hot = r != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Recupero")
            if (r != null) T("baseline ${(r.confidence * 100).toInt()}%", size = 10.sp, color = Term.dim)
        }
        if (r == null) {
            T("Servono ancora dati: almeno 5 giorni di HRV o una notte di sonno per calcolare il punteggio.", size = 12.sp, color = Term.dim)
            T("Giorni con HRV finora: ${report?.baselineDays ?: 0}", size = 11.sp, color = Term.dim)
        } else {
            val color = when (r.level) { ring.domain.ReadinessLevel.HIGH -> Term.accent; ring.domain.ReadinessLevel.GOOD -> Term.accent; ring.domain.ReadinessLevel.MODERATE -> Term.warn; ring.domain.ReadinessLevel.LOW -> Term.crit }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Num("${r.score}", "/100", 40.sp, color)
                T(r.level.label, size = 14.sp, color = color, weight = FontWeight.Bold)
            }
            r.components.forEach { (name, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(mapOf("hrv" to "HRV", "fc_riposo" to "FC riposo", "sonno" to "Sonno")[name] ?: name, Modifier.width(78.dp), 11.sp, Term.dim)
                    Box(Modifier.weight(1f).height(6.dp).background(Term.bg).border(BorderStroke(1.dp, Term.line2))) {
                        Box(Modifier.fillMaxWidth(v / 100f).height(6.dp).background(color.copy(alpha = 0.85f)))
                    }
                    T("$v", Modifier.width(26.dp), 11.sp, align = TextAlign.End)
                }
            }
            T("Indicativo, non e' un dispositivo medico.", size = 10.sp, color = Term.faint)
        }
    }
}

@Composable
private fun NotFoundCard(graph: Graph) = TCard(accent = Term.warn) {
    T("Anello non trovato", size = 16.sp, weight = FontWeight.Bold, color = Term.warn)
    T("Controlla questi punti:", size = 12.sp, color = Term.dim)
    T("? Fuori dalla custodia di ricarica", size = 12.sp)
    T("? Carico almeno al 20%", size = 12.sp)
    T("? Non collegato ad altro (QRing, altro telefono)", size = 12.sp)
    TButton("Riprova", { graph.scope.launch { graph.syncNow() } }, Modifier.fillMaxWidth())
}

@Composable
private fun MetricTile(
    title: String,
    type: String,
    unit: String,
    decimals: Int,
    color: Color,
    graph: Graph,
    modifier: Modifier,
    warnAbove: Double? = null,
    warnBelow: Double? = null,
) {
    val now = remember { Instant.now().epochSecond }
    val samples by remember(type) { graph.dao.observe(type, now - 86_400, now + 3600) }.collectAsStateWithLifecycle(emptyList())
    val last = samples.lastOrNull()
    val alert = last != null && ((warnAbove != null && last.value > warnAbove) || (warnBelow != null && last.value < warnBelow))
    Tile(
        title, last?.let { "%.${decimals}f".format(it.value) } ?: "—", unit,
        last?.let { "${clock(it.ts)} · ${ago(it.ts)}" } ?: "nessun dato", modifier, alert,
    ) { if (samples.size >= 2) Sparkline(samples.map { it.value }, if (alert) Term.warn else color) }
}

@Composable
private fun Tile(title: String, value: String, unit: String, sub: String, modifier: Modifier, alert: Boolean = false, spark: @Composable () -> Unit) = Column(
    modifier.background(Term.panel).border(BorderStroke(1.dp, if (alert) Term.warn.copy(alpha = 0.6f) else Term.line)).padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    Label(title)
    Num(value, unit, 22.sp, if (alert) Term.warn else Term.text)
    Box(Modifier.height(26.dp)) { spark() }
    T(sub, size = 10.sp, color = Term.dim, maxLines = 1)
}

@Composable
private fun HistoryScreen(graph: Graph) {
    var period by remember { mutableIntStateOf(0) }
    val thresholds by graph.settings.thresholds.collectAsStateWithLifecycle()
    val to = remember(period) { Instant.now().epochSecond }
    val from = to - PERIODS[period].second
    val sleep by remember(period) { graph.dao.observeSleep(from) }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PAD, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("STORICO", size = 15.sp, weight = FontWeight.Bold, spacing = 4.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PERIODS.forEachIndexed { i, (label, _) -> TChip(label, period == i) { period = i } } }
            }
        }
        item { HistoryPanel(graph, "Frequenza cardiaca", SampleType.HR, " bpm", from, to, Term.accent, 20.0, 0, thresholds.hrHigh.toDouble()) }
        item { HistoryPanel(graph, "Temperatura cutanea", SampleType.TEMP, " °C", from, to, Color(0xFFFF9D3B), 1.0, 1, thresholds.tempHigh) }
        item { HistoryPanel(graph, "Stress", SampleType.STRESS, "", from, to, Term.rem, 20.0, 0, thresholds.stressHigh.toDouble()) }
        item { HistoryPanel(graph, "HRV (anello)", SampleType.HRV, " ms", from, to, Term.info, 20.0, 0, null) }
        item { HistoryPanel(graph, "HRV live (RMSSD)", SampleType.HRV_LIVE, " ms", from, to, Term.rem, 20.0, 0, null) }
        item { HistoryPanel(graph, "Respirazione", SampleType.RESP, " /min", from, to, Term.info, 4.0, 0, null) }
        item { HistoryPanel(graph, "SpO2", SampleType.SPO2, " %", from, to, Term.accent, 4.0, 0, thresholds.spo2Low.toDouble()) }
        if (sleep.isNotEmpty()) {
            item { Label("Sonno") }
            items(sleep) { s -> SleepCard(s.start, s.end, s.lightMin, s.deepMin, s.remMin, s.awakeMin) }
        }
    }
}

@Composable
private fun HistoryPanel(graph: Graph, title: String, type: String, unit: String, from: Long, to: Long, color: Color, minSpan: Double, decimals: Int, threshold: Double?) {
    val samples by remember(type, from) { graph.dao.observe(type, from, to) }.collectAsStateWithLifecycle(emptyList())
    val fmt = { v: Double -> "%.${decimals}f".format(v) }
    TCard {
        Label(title)
        if (samples.isNotEmpty()) {
            T(
                "ultimo ${fmt(samples.last().value)}$unit (${ago(samples.last().ts)}) · min ${fmt(samples.minOf { it.value })} · " +
                    "media ${fmt(samples.map(Sample::value).average())} · max ${fmt(samples.maxOf { it.value })}",
                size = 10.sp, color = Term.dim,
            )
        }
        TermChart(samples, from, to, color, minSpan = minSpan, threshold = threshold, format = fmt)
    }
}

@Composable
private fun SleepCard(start: Long, end: Long, light: Int, deep: Int, rem: Int, awake: Int) = TCard {
    val total = ((end - start) / 60).toInt()
    T("${DAY_TIME.format(Instant.ofEpochSecond(start))} → ${DAY_TIME.format(Instant.ofEpochSecond(end))}  (${total / 60}h ${total % 60}m)", size = 12.sp)
    val sum = (light + deep + rem + awake).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(10.dp)) {
        listOf(deep to Term.info, light to Term.accent, rem to Term.rem, awake to Term.crit).forEach { (m, c) ->
            if (m > 0) Box(Modifier.weight(m.toFloat() / sum).fillMaxSize().background(c.copy(alpha = 0.85f)))
        }
    }
    T("profondo ${deep}m · leggero ${light}m · REM ${rem}m · sveglio ${awake}m", size = 10.sp, color = Term.dim)
}

@Composable
private fun SettingsScreen(graph: Graph, onForget: () -> Unit) {
    val status by graph.status.collectAsStateWithLifecycle()
    val thresholds by graph.settings.thresholds.collectAsStateWithLifecycle()
    var interval by remember { mutableIntStateOf(graph.settings.syncIntervalMin) }
    var alertsOn by remember { mutableStateOf(graph.settings.alertsEnabled) }
    var picking by remember { mutableStateOf(false) }
    val alerts by graph.dao.observeAlerts().collectAsStateWithLifecycle(emptyList())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun set(t: Thresholds) = graph.settings.setThresholds(t)
    LazyColumn(contentPadding = PAD, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { T("IMPOSTAZIONI", size = 15.sp, weight = FontWeight.Bold, spacing = 4.sp) }
        item {
            TCard(hot = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T("Anello", weight = FontWeight.Bold)
                    TPill(if (status.connected) "connesso" else "offline", if (status.connected) Term.accent else Term.warn)
                }
                KeyValue("Indirizzo", graph.settings.deviceAddress ?: "—")
                status.battery?.let { KeyValue("Batteria", "${it.level}%${if (it.charging) " · in carica" else ""}") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TButton("Cambia anello", { picking = !picking }, kind = BtnKind.GHOST)
                    TButton("Dimentica", { graph.disconnect(); graph.settings.deviceAddress = null; graph.settings.lastSyncEpoch = 0; onForget() }, kind = BtnKind.CRIT)
                }
                if (picking) RingPicker { d -> graph.settings.deviceAddress = d.address; graph.settings.lastSyncEpoch = 0; picking = false; graph.disconnect() }
            }
        }
        item {
            TCard {
                Label("Sincronizza ogni")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(5, 15, 30, 60).forEach { m -> TChip("$m min", interval == m) { interval = m; graph.settings.syncIntervalMin = m } } }
            }
        }
        item {
            TCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Label("Misure automatiche dell'anello")
                    TButton("Leggi", { graph.refreshMonitors() }, kind = BtnKind.GHOST)
                }
                listOf("hr" to "Cuore (ogni 5 min)", "SPO2" to "SpO2", "STRESS" to "Stress", "HRV" to "HRV", "TEMPERATURE" to "Temperatura").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        T(label, size = 12.sp)
                        TSwitch(status.monitors[key] == true, { graph.setMonitor(key, it) }, enabled = status.monitors.containsKey(key))
                    }
                }
                if (status.monitors.isEmpty()) T("Tocca Leggi per vedere lo stato dal ring.", size = 11.sp, color = Term.dim)
            }
        }
        item {
            TCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Label("Alert")
                    TSwitch(alertsOn, { alertsOn = it; graph.settings.alertsEnabled = it })
                }
                Stepper("Cuore alto (bpm)", "${thresholds.hrHigh}", { set(thresholds.copy(hrHigh = thresholds.hrHigh - 5)) }, { set(thresholds.copy(hrHigh = thresholds.hrHigh + 5)) })
                Stepper("Cuore basso (bpm)", "${thresholds.hrLow}", { set(thresholds.copy(hrLow = thresholds.hrLow - 5)) }, { set(thresholds.copy(hrLow = thresholds.hrLow + 5)) })
                Stepper("Temperatura (°C)", "%.1f".format(thresholds.tempHigh), { set(thresholds.copy(tempHigh = thresholds.tempHigh - 0.1)) }, { set(thresholds.copy(tempHigh = thresholds.tempHigh + 0.1)) })
                Stepper("Stress alto", "${thresholds.stressHigh}", { set(thresholds.copy(stressHigh = thresholds.stressHigh - 5)) }, { set(thresholds.copy(stressHigh = thresholds.stressHigh + 5)) })
                Stepper("SpO2 bassa (%)", "${thresholds.spo2Low}", { set(thresholds.copy(spo2Low = thresholds.spo2Low - 1)) }, { set(thresholds.copy(spo2Low = thresholds.spo2Low + 1)) })
                Stepper("Batteria bassa (%)", "${thresholds.batteryLow}", { set(thresholds.copy(batteryLow = thresholds.batteryLow - 5)) }, { set(thresholds.copy(batteryLow = thresholds.batteryLow + 5)) })
                TButton("Cancella storico alert (${alerts.size})", { scope.launch { graph.dao.clearAlerts() } }, Modifier.fillMaxWidth(), BtnKind.GHOST, enabled = alerts.isNotEmpty())
            }
        }
        item {
            TCard {
                Label("Dati di esempio")
                T("Carica 14 giorni di dati finti (marcati demo) per vedere grafici, recupero e allarmi. Si rimuovono con un tocco; i dati veri non vengono toccati.", size = 11.sp, color = Term.dim)
                TButton("Carica demo · sano", { graph.loadDemo(false) }, Modifier.fillMaxWidth(), BtnKind.GHOST)
                TButton("Carica demo · malessere", { graph.loadDemo(true) }, Modifier.fillMaxWidth(), BtnKind.GHOST)
                TButton("Rimuovi dati demo", { graph.clearDemo() }, Modifier.fillMaxWidth(), BtnKind.CRIT)
            }
        }
        item {
            TCard {
                Label("Privacy")
                KeyValue("Permesso internet", "assente", Term.accent)
                KeyValue("Dati", "solo su questo telefono")
            }
        }
    }
}
