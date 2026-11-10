package ring.app.ui

import ring.app.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import ring.app.Graph
import ring.app.ble.FoundDevice
import ring.app.ble.scanDevices

private const val STEPS = 5

@Composable
private fun OnboardingFrame(step: Int, title: String, content: @Composable ColumnScope.() -> Unit, footer: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Term.bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(title, size = 15.sp, weight = FontWeight.Bold, spacing = 2.sp)
            T("${step + 1} / $STEPS", size = 11.sp, color = Term.dim)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
    }
}

/** Procedura guidata del primo avvio: benvenuto, permessi, ricerca, collegamento, fine. */
@Composable
fun Onboarding(graph: Graph, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    when (step) {
        0 -> Welcome { step = 1 }
        1 -> PermissionsStep(onBack = { step = 0 }) { step = 2 }
        2 -> ScanStep(graph, onBack = { step = 1 }) { step = 3 }
        3 -> ConnectStep(graph, onBack = { step = 2 }) { step = 4 }
        else -> DoneStep(graph, onDone)
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) = OnboardingFrame(
    0, "RING",
    content = {
        Box(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LauncherMark(120.dp)
            T("RING", size = 30.sp, weight = FontWeight.Bold, spacing = 8.sp)
            T(stringResource(R.string.ob_tagline), color = Term.accent)
            TCard {
                KeyValue(stringResource(R.string.ob_account), stringResource(R.string.value_none))
                KeyValue(stringResource(R.string.ob_cloud), stringResource(R.string.value_none))
                KeyValue(stringResource(R.string.ob_internet), stringResource(R.string.value_not_requested), Term.accent)
            }
        }
    },
    footer = { TButton(stringResource(R.string.btn_start), onNext, Modifier.fillMaxWidth()) },
)

@Composable
private fun LauncherMark(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Term.bg).border(BorderStroke(1.dp, Term.line2), androidx.compose.foundation.shape.RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
        Radar(size * 0.7f, found = false)
        Ecg(Modifier.padding(horizontal = 18.dp), active = true)
    }
}

@Composable
private fun PermissionsStep(onBack: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val nearby = remember(tick) { Perms.hasNearby(ctx) }
    val notif = remember(tick) { Perms.hasNotifications(ctx) }
    val battery = remember(tick) { Perms.batteryUnrestricted(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    OnboardingFrame(
        1, stringResource(R.string.ob_perm_title),
        content = {
            T(stringResource(R.string.ob_perm_intro), size = 12.sp, color = Term.dim)
            PermCard(stringResource(R.string.perm_nearby), stringResource(R.string.perm_nearby_why), nearby, required = true)
            PermCard(stringResource(R.string.perm_notif), stringResource(R.string.perm_notif_why), notif)
            PermCard(stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_why), battery)
        },
        footer = {
            if (!nearby || !notif) {
                TButton(stringResource(R.string.btn_grant), {
                    launcher.launch(buildList { if (!nearby) addAll(Perms.nearby); if (!notif) add(Perms.NOTIFICATIONS) }.toTypedArray())
                }, Modifier.fillMaxWidth())
            }
            if (!battery) {
                TButton(stringResource(R.string.btn_battery_unrestricted), {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Modifier.fillMaxWidth(), BtnKind.GHOST)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TButton(stringResource(R.string.btn_back), onBack, Modifier.weight(1f), BtnKind.GHOST)
                TButton(stringResource(R.string.btn_next), onNext, Modifier.weight(1f), enabled = nearby)
            }
        },
    )
}

@Composable
private fun PermCard(title: String, why: String, ok: Boolean, required: Boolean = false) = TCard(hot = ok) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        T(title, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TPill(if (ok) stringResource(R.string.perm_state_granted) else if (required) stringResource(R.string.perm_state_required) else stringResource(R.string.perm_state_to_grant), if (ok) Term.accent else Term.warn)
    }
    T(why, size = 12.sp, color = Term.dim)
}

private fun looksLikeRing(d: FoundDevice) = d.name.contains("ring", ignoreCase = true) || d.name.startsWith("R0") || d.name.contains("colmi", ignoreCase = true)

/** Ricerca dei dispositivi BLE; mostra gli anelli e, a richiesta, tutti i dispositivi con un nome. */
@Composable
fun RingPicker(onPick: (FoundDevice) -> Unit) {
    val ctx = LocalContext.current
    val scanFailedDefault = stringResource(R.string.scan_failed_default)
    val found = remember { mutableStateListOf<FoundDevice>() }
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        scanDevices(ctx).catch { error = it.message ?: scanFailedDefault }.collect { d ->
            val i = found.indexOfFirst { it.address == d.address }
            if (i >= 0) found[i] = d else found += d
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    val rings = found.filter(::looksLikeRing).sortedByDescending { it.rssi }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Radar(150.dp, found = rings.isNotEmpty())
        T(if (rings.isEmpty()) stringResource(R.string.scan_in_progress, seconds) else stringResource(R.string.scan_found), color = Term.accent)
        error?.let { Banner(stringResource(R.string.scan_failed_title), it, Term.crit) }
        rings.forEach { d -> DeviceCard(d, onPick) }
        if (rings.isEmpty() && seconds >= 10) {
            Banner(
                stringResource(R.string.scan_help_title),
                stringResource(R.string.scan_help_body),
                Term.dim,
            )
        }
        TButton(if (showAll) stringResource(R.string.scan_hide_others) else stringResource(R.string.scan_show_all), { showAll = !showAll }, kind = BtnKind.GHOST)
        if (showAll) found.filterNot(::looksLikeRing).sortedByDescending { it.rssi }.forEach { d -> DeviceCard(d, onPick) }
    }
}

@Composable
private fun DeviceCard(d: FoundDevice, onPick: (FoundDevice) -> Unit) = TCard(hot = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(d.name, weight = FontWeight.Bold)
        T("${d.rssi} dBm", size = 11.sp, color = Term.accent)
    }
    T(d.address, size = 11.sp, color = Term.dim)
    TButton(stringResource(R.string.btn_connect), { onPick(d) }, Modifier.fillMaxWidth())
}

@Composable
private fun ScanStep(graph: Graph, onBack: () -> Unit, onNext: () -> Unit) = OnboardingFrame(
    2, stringResource(R.string.ob_scan_title),
    content = { RingPicker { d -> graph.settings.deviceAddress = d.address; onNext() } },
    footer = { TButton("Indietro", onBack, Modifier.fillMaxWidth(), BtnKind.GHOST) },
)

@Composable
private fun ConnectStep(graph: Graph, onBack: () -> Unit, onNext: () -> Unit) {
    val status by graph.status.collectAsStateWithLifecycle()
    var started by rememberSaveable { mutableStateOf(false) }
    var monitorsDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            graph.syncNow()
            if (graph.status.value.lastError == null) {
                graph.enableDefaultMonitors()
                monitorsDone = true
            }
        }
    }
    val failed = !status.syncing && status.lastError != null && started
    val finished = !status.syncing && status.lastError == null && monitorsDone
    OnboardingFrame(
        3, stringResource(R.string.ob_connect_title),
        content = {
            TCard {
                StepRow(if (status.connected) "ok" else if (failed) "x" else "wait", stringResource(R.string.step_connected), status.capabilities?.let { stringResource(R.string.step_caps) } ?: "COLMI Ring")
                StepRow(if (status.capabilities != null) "ok" else "wait", stringResource(R.string.step_clock), "UTC")
                StepRow(if (finished) "ok" else if (failed) "x" else "wait", stringResource(R.string.step_history), status.syncStep.ifEmpty { stringResource(R.string.step_waiting) })
                if (status.syncing || finished) {
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Term.bg).border(BorderStroke(1.dp, Term.line2))) {
                        Box(Modifier.fillMaxWidth(if (finished) 1f else status.syncProgress.coerceIn(0.03f, 1f)).height(6.dp).background(Term.accent))
                    }
                }
            }
            if (finished) {
                TCard {
                    Label(stringResource(R.string.auto_on_title))
                    T(stringResource(R.string.auto_on_body), size = 12.sp, color = Term.dim)
                }
            }
            status.lastError?.takeIf { failed }?.let {
                Banner(stringResource(R.string.connect_failed_title), stringResource(R.string.connect_failed_body, it), Term.crit)
            }
        },
        footer = {
            if (failed) TButton(stringResource(R.string.btn_retry), { started = false; monitorsDone = false }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TButton(stringResource(R.string.btn_back), onBack, Modifier.weight(1f), BtnKind.GHOST)
                TButton(stringResource(R.string.btn_continue), onNext, Modifier.weight(1f), enabled = finished)
            }
        },
    )
}

@Composable
private fun StepRow(state: String, title: String, sub: String) = Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    val c = when (state) { "ok" -> Term.accent; "x" -> Term.crit; else -> Term.faint }
    Box(Modifier.size(20.dp).border(BorderStroke(1.dp, c)), contentAlignment = Alignment.Center) { T(when (state) { "ok" -> "✓"; "x" -> "!"; else -> "…" }, size = 11.sp, color = c, weight = FontWeight.Bold) }
    Column { T(title, weight = FontWeight.Bold); T(sub, size = 11.sp, color = Term.dim) }
}

@Composable
private fun DoneStep(graph: Graph, onDone: () -> Unit) = OnboardingFrame(
    4, stringResource(R.string.ob_done_title),
    content = {
        Box(Modifier.height(40.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp).border(BorderStroke(1.dp, Term.accent)), contentAlignment = Alignment.Center) { T("✓", size = 30.sp, color = Term.accent) }
            T(stringResource(R.string.all_set), size = 24.sp, weight = FontWeight.Bold)
            T(stringResource(R.string.all_set_body, graph.settings.syncIntervalMin), size = 12.sp, color = Term.dim, align = TextAlign.Center)
        }
    },
    footer = { TButton(stringResource(R.string.btn_open_app), onDone, Modifier.fillMaxWidth()) },
)
