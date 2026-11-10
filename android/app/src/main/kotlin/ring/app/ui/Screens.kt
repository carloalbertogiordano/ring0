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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ring.app.Graph
import ring.app.R
import ring.app.alerts.alertText
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.app.data.Thresholds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())
private val PERIODS = listOf(R.string.period_24h to 24L * 3600, R.string.period_7d to 7L * 86_400, R.string.period_30d to 30L * 86_400)

/** Root: first run, system errors (permissions, Bluetooth) or the app with its tab bar. */
@Composable
fun AppRoot(graph: Graph, startService: () -> Unit) {
    var showCheck by remember { mutableStateOf(false) }
    val checkRequested by graph.checkRequested.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(checkRequested) {
        if (checkRequested) {
            showCheck = true
            graph.consumeCheckRequest()
        }
    }
    val ctx = LocalContext.current
    var onboarded by remember { mutableStateOf(graph.settings.onboarded) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val nearby = remember(tick) { Perms.hasNearby(ctx) }
    val btOn = remember(tick) { Perms.bluetoothOn(ctx) }
    Box(Modifier.fillMaxSize().background(Term.bg)) {
        when {
            !onboarded -> Onboarding(graph) {
                graph.settings.onboarded = true
                onboarded = true
                startService()
            }

            !nearby -> PermissionDenied()

            !btOn -> BluetoothOff { tick++ }

            showCheck -> CheckScreen(graph) { showCheck = false }

            else -> MainScaffold(graph, onOpenCheck = { showCheck = true }) { onboarded = false }
        }
    }
}

@Composable
private fun SystemScreen(title: String, body: String, color: Color, action: String, onAction: () -> Unit, extra: @Composable () -> Unit = {}) =
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).border(BorderStroke(1.dp, color)), contentAlignment = Alignment.Center) {
                T("!", size = 28.sp, color = color, weight = FontWeight.Bold)
            }
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
        stringResource(R.string.bt_off_title),
        stringResource(R.string.bt_off_body),
        Term.crit,
        stringResource(R.string.btn_enable_bt),
        { launcher.launch(Intent(Perms.enableBluetoothAction)) },
    )
}

@Composable
private fun PermissionDenied() {
    val ctx = LocalContext.current
    SystemScreen(
        stringResource(R.string.perm_denied_title),
        stringResource(R.string.perm_denied_body),
        Term.crit,
        stringResource(R.string.btn_open_app_settings),
        {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    ) {
        TCard {
            T(stringResource(R.string.perm_denied_step1), size = 12.sp)
            T(stringResource(R.string.perm_denied_step2), size = 12.sp)
            T(stringResource(R.string.perm_denied_step3), size = 12.sp)
        }
        T(stringResource(R.string.perm_denied_graphene), size = 11.sp, color = Term.dim, align = TextAlign.Center)
    }
}

@Composable
private fun MainScaffold(graph: Graph, onOpenCheck: () -> Unit, onForget: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HomeScreen(graph, onOpenCheck)
                1 -> HistoryScreen(graph)
                else -> SettingsScreen(graph, onForget)
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF050C08)).border(BorderStroke(1.dp, Term.line2)).navigationBarsPadding()) {
            listOf(R.string.tab_home, R.string.tab_history, R.string.tab_settings).forEachIndexed { i, label ->
                T(
                    stringResource(label).uppercase(),
                    Modifier.weight(1f).clickable { tab = i }.padding(vertical = 16.dp),
                    11.sp,
                    if (tab == i) Term.accent else Term.faint,
                    if (tab == i) FontWeight.Bold else FontWeight.Normal,
                    TextAlign.Center,
                    1.4.sp,
                )
            }
        }
    }
}

private val PAD = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

@Composable
private fun HomeScreen(graph: Graph, onOpenCheck: () -> Unit) {
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
                T(stringResource(R.string.app_name).uppercase(), size = 15.sp, weight = FontWeight.Bold, spacing = 4.sp)
                when {
                    battery != null && status.connected -> TPill(
                        if (battery.charging) stringResource(R.string.pill_charging, battery.level) else "${battery.level}%",
                        if (lowBattery) Term.crit else Term.accent,
                        pulse = true,
                    )

                    status.syncing -> TPill(stringResource(R.string.pill_syncing), Term.info, pulse = true)

                    else -> TPill(stringResource(R.string.pill_offline), Term.warn)
                }
            }
        }
        if (lowBattery && battery != null) {
            item { Banner(stringResource(R.string.low_battery_title, battery.level), stringResource(R.string.low_battery_body), Term.crit) }
        }
        recentAlert?.let { a ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Banner(alertText(a), DAY_TIME.format(Instant.ofEpochSecond(a.ts)), Term.warn)
                    if (a.kind in INCIDENT_ALERT_KINDS) TButton(stringResource(R.string.btn_check_now), onOpenCheck, Modifier.fillMaxWidth(), BtnKind.GHOST)
                }
            }
        }
        if (!status.connected && !status.syncing && status.connectFailures >= 2) item { NotFoundCard(graph) }
        item { CheckCard(onOpenCheck) }
        item { LiveCard(graph) }
        item { ReadinessCard(graph) }
        item { BodyBatteryCard(graph) }
        item { SleepInsightsCard(graph) }
        item { TrainingCard(graph) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(
                        stringResource(R.string.tile_heart),
                        SampleType.HR,
                        "bpm",
                        0,
                        Term.accent,
                        graph,
                        Modifier.weight(1f),
                        warnAbove = thresholds.hrHigh.toDouble(),
                    )
                    MetricTile(
                        stringResource(R.string.tile_temp),
                        SampleType.TEMP,
                        "°C",
                        1,
                        Color(0xFFFF9D3B),
                        graph,
                        Modifier.weight(1f),
                        warnAbove = thresholds.tempHigh,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(
                        stringResource(R.string.tile_stress),
                        SampleType.STRESS,
                        "",
                        0,
                        Term.rem,
                        graph,
                        Modifier.weight(1f),
                        warnAbove = thresholds.stressHigh.toDouble(),
                    )
                    MetricTile(stringResource(R.string.tile_hrv), SampleType.HRV, "ms", 0, Term.info, graph, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(
                        stringResource(R.string.tile_spo2),
                        SampleType.SPO2,
                        "%",
                        0,
                        Term.accent,
                        graph,
                        Modifier.weight(1f),
                        warnBelow = thresholds.spo2Low.toDouble(),
                    )
                    val last = sleep.firstOrNull()
                    Tile(
                        stringResource(R.string.tile_sleep),
                        last?.let {
                            "%dh %02d".format((it.end - it.start) / 3600, (it.end - it.start) / 60 % 60)
                        } ?: "—",
                        "",
                        last?.let { "${clock(it.end)} · ${ago(it.end)}" } ?: stringResource(R.string.no_night),
                        Modifier.weight(1f),
                    ) {}
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T(stringResource(R.string.sync_last, ago(status.lastSyncEpoch)), size = 11.sp, color = Term.dim)
                TButton(if (status.syncing) "…" else stringResource(R.string.btn_sync), {
                    graph.scope.launch { graph.syncNow() }
                }, kind = BtnKind.GHOST, enabled = !status.syncing)
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
            Label(stringResource(R.string.live_title))
            T(
                if (status.measuring) {
                    stringResource(R.string.live_beats_progress, status.liveBeats, status.liveTarget)
                } else {
                    live?.let { stringResource(R.string.live_beats, it.beats) }
                        ?: ""
                },
                size = 11.sp,
                color = Term.dim,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Num(live?.bpm?.toString() ?: "—", "bpm", 44.sp, if (live?.bpm != null) Term.accent else Term.faint)
            Column(horizontalAlignment = Alignment.End) {
                Label(stringResource(R.string.hrv_rmssd))
                Num(live?.rmssd?.let { "%.0f".format(it) } ?: "—", "ms", 22.sp)
            }
        }
        live?.breathsPerMinute?.let {
            T(stringResource(R.string.breathing_line, it, (live.breathConfidence * 100).toInt()), size = 12.sp, color = Term.info)
        }
        Ecg(active = status.measuring)
        if (status.measuring) {
            T(
                if (status.liveBeats == 0) {
                    stringResource(R.string.live_phase1)
                } else {
                    stringResource(R.string.live_phase2, status.liveBeats, status.liveTarget)
                },
                size = 11.sp,
                color = Term.accent,
            )
        }
        TButton(
            if (status.measuring) stringResource(R.string.btn_measuring) else stringResource(R.string.btn_quick),
            {
                graph.measureLive(false)
            },
            Modifier.fillMaxWidth(),
            enabled =
                !status.measuring && !status.spo2Measuring,
        )
        TButton(
            stringResource(R.string.btn_full),
            { graph.measureLive(true) },
            Modifier.fillMaxWidth(),
            BtnKind.GHOST,
            enabled =
                !status.measuring && !status.spo2Measuring,
        )
        T(stringResource(R.string.full_hint), size = 10.sp, color = Term.faint)
        status.liveSpo2?.let { T(stringResource(R.string.spo2_result, it), size = 13.sp, color = Term.info) }
        TButton(
            if (status.spo2Measuring) stringResource(R.string.btn_measuring) else stringResource(R.string.btn_spo2),
            { graph.measureSpo2() },
            Modifier.fillMaxWidth(),
            BtnKind.GHOST,
            enabled = !status.measuring && !status.spo2Measuring && !status.stressMeasuring,
        )
        status.liveStress?.let { T(stringResource(R.string.stress_result, it), size = 13.sp, color = Term.rem) }
        TButton(
            if (status.stressMeasuring) stringResource(R.string.btn_measuring) else stringResource(R.string.btn_stress),
            { graph.measureStress() },
            Modifier.fillMaxWidth(),
            BtnKind.GHOST,
            enabled = !status.measuring && !status.spo2Measuring && !status.stressMeasuring,
        )
        T(stringResource(R.string.spo2_hint), size = 10.sp, color = Term.faint)
    }
}

@Composable
private fun ReadinessCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val report = status.readiness
    val r = report?.readiness
    TCard(hot = r != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(stringResource(R.string.recovery_title))
            if (r != null) T(stringResource(R.string.baseline_pct, (r.confidence * 100).toInt()), size = 10.sp, color = Term.dim)
        }
        if (r == null) {
            T(stringResource(R.string.recovery_need_data), size = 12.sp, color = Term.dim)
            T(stringResource(R.string.hrv_days_so_far, report?.baselineDays ?: 0), size = 11.sp, color = Term.dim)
        } else {
            val color = when (r.level) {
                ring.domain.ReadinessLevel.HIGH -> Term.accent
                ring.domain.ReadinessLevel.GOOD -> Term.accent
                ring.domain.ReadinessLevel.MODERATE -> Term.warn
                ring.domain.ReadinessLevel.LOW -> Term.crit
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Num("${r.score}", "/100", 40.sp, color)
                T(stringResource(levelRes(r.level)), size = 14.sp, color = color, weight = FontWeight.Bold)
            }
            r.components.forEach { (name, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(stringResource(componentRes(name)), Modifier.width(78.dp), 11.sp, Term.dim)
                    Box(Modifier.weight(1f).height(6.dp).background(Term.bg).border(BorderStroke(1.dp, Term.line2))) {
                        Box(Modifier.fillMaxWidth(v / 100f).height(6.dp).background(color.copy(alpha = 0.85f)))
                    }
                    T("$v", Modifier.width(26.dp), 11.sp, align = TextAlign.End)
                }
            }
            T(stringResource(R.string.not_medical), size = 10.sp, color = Term.faint)
        }
    }
}

@Composable
private fun NotFoundCard(graph: Graph) = TCard(accent = Term.warn) {
    T(stringResource(R.string.not_found_title), size = 16.sp, weight = FontWeight.Bold, color = Term.warn)
    T(stringResource(R.string.not_found_intro), size = 12.sp, color = Term.dim)
    T(stringResource(R.string.not_found_1), size = 12.sp)
    T(stringResource(R.string.not_found_2), size = 12.sp)
    T(stringResource(R.string.not_found_3), size = 12.sp)
    TButton(stringResource(R.string.btn_retry), { graph.scope.launch { graph.syncNow() } }, Modifier.fillMaxWidth())
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
        title,
        last?.let { "%.${decimals}f".format(it.value) } ?: "—",
        unit,
        last?.let { "${clock(it.ts)} · ${ago(it.ts)}" } ?: stringResource(R.string.no_data),
        modifier,
        alert,
    ) { if (samples.size >= 2) Sparkline(samples.map { it.value }, if (alert) Term.warn else color) }
}

@Composable
private fun Tile(title: String, value: String, unit: String, sub: String, modifier: Modifier, alert: Boolean = false, spark: @Composable () -> Unit) = Column(
    modifier.background(
        Term.panel,
    ).border(BorderStroke(1.dp, if (alert) Term.warn.copy(alpha = 0.6f) else Term.line)).padding(horizontal = 10.dp, vertical = 8.dp),
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
    val incidents by graph.dao.observeIncidents().collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<ring.app.data.IncidentEntity?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    editing?.let { current ->
        IncidentEditor(
            current,
            onSave = {
                scope.launch {
                    graph.incidents.save(it)
                    editing = null
                }
            },
            onDelete = if (current.id != 0L) {
                (
                    {
                        scope.launch {
                            graph.incidents.delete(current.id)
                            editing = null
                        }
                    }
                )
            } else {
                null
            },
            onDismiss = { editing = null },
        )
    }
    LazyColumn(contentPadding = PAD, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T(stringResource(R.string.history_title), size = 15.sp, weight = FontWeight.Bold, spacing = 4.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PERIODS.forEachIndexed {
                        i,
                        (label, _),
                        ->
                        TChip(stringResource(label), period == i) {
                            period =
                                i
                        }
                    }
                }
            }
        }
        item { HistoryPanel(graph, stringResource(R.string.chart_hr), SampleType.HR, " bpm", from, to, Term.accent, 20.0, 0, thresholds.hrHigh.toDouble()) }
        item { HistoryPanel(graph, stringResource(R.string.chart_temp), SampleType.TEMP, " °C", from, to, Color(0xFFFF9D3B), 1.0, 1, thresholds.tempHigh) }
        item {
            HistoryPanel(graph, stringResource(R.string.chart_stress), SampleType.STRESS, "", from, to, Term.rem, 20.0, 0, thresholds.stressHigh.toDouble())
        }
        item { HistoryPanel(graph, stringResource(R.string.chart_hrv_ring), SampleType.HRV, " ms", from, to, Term.info, 20.0, 0, null) }
        item { HistoryPanel(graph, stringResource(R.string.chart_hrv_live), SampleType.HRV_LIVE, " ms", from, to, Term.rem, 20.0, 0, null) }
        item { HistoryPanel(graph, stringResource(R.string.chart_resp), SampleType.RESP, " /min", from, to, Term.info, 4.0, 0, null) }
        item { HistoryPanel(graph, stringResource(R.string.chart_spo2), SampleType.SPO2, " %", from, to, Term.accent, 4.0, 0, thresholds.spo2Low.toDouble()) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.incidents_title))
                TButton(stringResource(R.string.btn_add_incident), {
                    scope.launch { editing = graph.incidents.manualDraft(Instant.now().epochSecond) }
                }, kind = BtnKind.GHOST)
            }
        }
        items(incidents) { IncidentCard(it) { editing = it } }
        if (sleep.isNotEmpty()) {
            item { Label(stringResource(R.string.sleep_label)) }
            items(sleep) { s -> SleepCard(s.start, s.end, s.lightMin, s.deepMin, s.remMin, s.awakeMin) }
        }
    }
}

@Composable
private fun HistoryPanel(
    graph: Graph,
    title: String,
    type: String,
    unit: String,
    from: Long,
    to: Long,
    color: Color,
    minSpan: Double,
    decimals: Int,
    threshold: Double?,
) {
    val samples by remember(type, from) { graph.dao.observe(type, from, to) }.collectAsStateWithLifecycle(emptyList())
    val fmt = { v: Double -> "%.${decimals}f".format(v) }
    TCard {
        Label(title)
        if (samples.isNotEmpty()) {
            T(
                stringResource(
                    R.string.stats_line,
                    fmt(samples.last().value),
                    unit,
                    ago(samples.last().ts),
                    fmt(samples.minOf { it.value }),
                    fmt(samples.map(Sample::value).average()),
                    fmt(samples.maxOf { it.value }),
                ),
                size = 10.sp,
                color = Term.dim,
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
    T(stringResource(R.string.sleep_stages, deep, light, rem, awake), size = 10.sp, color = Term.dim)
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
    LaunchedEffect(status.connected) { if (status.connected && status.monitors.isEmpty()) graph.refreshMonitors() }
    LazyColumn(contentPadding = PAD, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { T(stringResource(R.string.settings_title), size = 15.sp, weight = FontWeight.Bold, spacing = 4.sp) }
        item {
            TCard(hot = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T(stringResource(R.string.ring_label), weight = FontWeight.Bold)
                    TPill(
                        if (status.connected) stringResource(R.string.connected) else stringResource(R.string.pill_offline),
                        if (status.connected) Term.accent else Term.warn,
                    )
                }
                KeyValue(stringResource(R.string.address_label), graph.settings.deviceAddress ?: "—")
                status.battery?.let {
                    KeyValue(stringResource(R.string.battery_label), if (it.charging) stringResource(R.string.battery_charging, it.level) else "${it.level}%")
                }
                status.batteryEstimate?.takeIf { status.battery?.charging != true }?.let {
                    KeyValue(stringResource(R.string.battery_forecast_label), batteryLeftText(it.hoursLeft))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TButton(stringResource(R.string.btn_change_ring), { picking = !picking }, Modifier.weight(1f), BtnKind.GHOST)
                    TButton(stringResource(R.string.btn_find_ring), { graph.findRing() }, Modifier.weight(1f), BtnKind.GHOST, enabled = status.connected)
                }
                TButton(
                    stringResource(R.string.btn_forget),
                    {
                        graph.disconnect()
                        graph.settings.deviceAddress = null
                        graph.settings.lastSyncEpoch = 0
                        onForget()
                    },
                    Modifier.fillMaxWidth(),
                    BtnKind.CRIT,
                )
                if (picking) {
                    RingPicker { d ->
                        graph.settings.deviceAddress = d.address
                        graph.settings.lastSyncEpoch = 0
                        picking = false
                        graph.disconnect()
                    }
                }
            }
        }
        item { ProfileCard(graph) }
        item {
            TCard {
                Label(stringResource(R.string.sync_every))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 15, 30, 60).forEach { m ->
                        TChip(
                            stringResource(R.string.minutes_n, m),
                            interval == m,
                        ) {
                            interval = m
                            graph.settings.syncIntervalMin = m
                        }
                    }
                }
            }
        }
        item {
            TCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Label(stringResource(R.string.monitors_title))
                    TButton(stringResource(R.string.btn_read), { graph.refreshMonitors() }, kind = BtnKind.GHOST)
                }
                listOf(
                    "hr" to R.string.monitor_hr,
                    "SPO2" to R.string.monitor_spo2,
                    "STRESS" to R.string.monitor_stress,
                    "HRV" to R.string.monitor_hrv,
                    "TEMPERATURE" to R.string.monitor_temp,
                ).forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        T(stringResource(label), size = 12.sp)
                        TSwitch(status.monitors[key] == true, { graph.setMonitor(key, it) }, enabled = status.monitors.containsKey(key))
                    }
                }
                if (status.monitors.isEmpty()) T(stringResource(R.string.monitors_hint), size = 11.sp, color = Term.dim)
            }
        }
        item {
            TCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Label(stringResource(R.string.alerts_title))
                    TSwitch(alertsOn, {
                        alertsOn = it
                        graph.settings.alertsEnabled = it
                    })
                }
                Stepper(stringResource(R.string.thr_hr_high), "${thresholds.hrHigh}", { set(thresholds.copy(hrHigh = thresholds.hrHigh - 5)) }, {
                    set(
                        thresholds.copy(
                            hrHigh =
                                thresholds.hrHigh + 5,
                        ),
                    )
                })
                Stepper(stringResource(R.string.thr_hr_low), "${thresholds.hrLow}", { set(thresholds.copy(hrLow = thresholds.hrLow - 5)) }, {
                    set(
                        thresholds.copy(
                            hrLow =
                                thresholds.hrLow + 5,
                        ),
                    )
                })
                Stepper(stringResource(R.string.thr_temp), "%.1f".format(thresholds.tempHigh), { set(thresholds.copy(tempHigh = thresholds.tempHigh - 0.1)) }, {
                    set(
                        thresholds.copy(
                            tempHigh =
                                thresholds.tempHigh + 0.1,
                        ),
                    )
                })
                Stepper(stringResource(R.string.thr_stress), "${thresholds.stressHigh}", { set(thresholds.copy(stressHigh = thresholds.stressHigh - 5)) }, {
                    set(
                        thresholds.copy(
                            stressHigh =
                                thresholds.stressHigh + 5,
                        ),
                    )
                })
                Stepper(stringResource(R.string.thr_spo2), "${thresholds.spo2Low}", { set(thresholds.copy(spo2Low = thresholds.spo2Low - 1)) }, {
                    set(
                        thresholds.copy(
                            spo2Low =
                                thresholds.spo2Low + 1,
                        ),
                    )
                })
                Stepper(stringResource(R.string.thr_battery), "${thresholds.batteryLow}", { set(thresholds.copy(batteryLow = thresholds.batteryLow - 5)) }, {
                    set(
                        thresholds.copy(
                            batteryLow =
                                thresholds.batteryLow + 5,
                        ),
                    )
                })
                TButton(stringResource(R.string.btn_clear_alerts, alerts.size), {
                    scope.launch { graph.dao.clearAlerts() }
                }, Modifier.fillMaxWidth(), BtnKind.GHOST, enabled = alerts.isNotEmpty())
            }
        }
        item {
            TCard {
                Label(stringResource(R.string.demo_title))
                T(stringResource(R.string.demo_body), size = 11.sp, color = Term.dim)
                TButton(stringResource(R.string.btn_demo_healthy), { graph.loadDemo(false) }, Modifier.fillMaxWidth(), BtnKind.GHOST)
                TButton(stringResource(R.string.btn_demo_sick), { graph.loadDemo(true) }, Modifier.fillMaxWidth(), BtnKind.GHOST)
                TButton(stringResource(R.string.btn_demo_remove), { graph.clearDemo() }, Modifier.fillMaxWidth(), BtnKind.CRIT)
            }
        }
        item {
            TCard {
                Label(stringResource(R.string.privacy_title))
                KeyValue(stringResource(R.string.internet_permission), stringResource(R.string.value_absent), Term.accent)
                KeyValue(stringResource(R.string.data_label), stringResource(R.string.data_only_here))
            }
        }
    }
}

private fun levelRes(level: ring.domain.ReadinessLevel): Int = when (level) {
    ring.domain.ReadinessLevel.HIGH -> R.string.level_high
    ring.domain.ReadinessLevel.GOOD -> R.string.level_good
    ring.domain.ReadinessLevel.MODERATE -> R.string.level_moderate
    ring.domain.ReadinessLevel.LOW -> R.string.level_low
}

private fun componentRes(component: ring.domain.ReadinessComponent): Int = when (component) {
    ring.domain.ReadinessComponent.HRV -> R.string.comp_hrv
    ring.domain.ReadinessComponent.RESTING_HR -> R.string.comp_resting_hr
    ring.domain.ReadinessComponent.SLEEP -> R.string.comp_sleep
}

private val INCIDENT_ALERT_KINDS = setOf("hr_high", "hr_low", "spo2_low", "temp_high", "stress_high", "illness")

@Composable
private fun CheckCard(onOpen: () -> Unit) = TCard {
    Label(stringResource(R.string.home_check_title))
    T(stringResource(R.string.home_check_body), size = 12.sp, color = Term.dim)
    TButton(stringResource(R.string.btn_check_now), onOpen, Modifier.fillMaxWidth())
}

private const val HOURS_PER_DAY = 24

@Composable
private fun batteryLeftText(hoursLeft: Double): String =
    if (hoursLeft >= 2 * HOURS_PER_DAY) {
        stringResource(R.string.battery_left_days, hoursLeft / HOURS_PER_DAY)
    } else {
        stringResource(R.string.battery_left_hours, hoursLeft.toInt())
    }
