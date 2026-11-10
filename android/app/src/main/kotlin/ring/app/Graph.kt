package ring.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.room.Room
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ring.app.ble.RingBleManager
import ring.app.ble.RingSession
import ring.app.data.RingDb
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.app.data.SettingsStore
import ring.app.sync.AlertEngine
import ring.app.sync.SyncEngine
import ring.protocol.commands.AutoMonitorCommand
import ring.protocol.commands.Battery
import ring.protocol.commands.Capabilities
import ring.protocol.commands.Feature
import ring.protocol.commands.HeartRateSettings
import ring.protocol.commands.HrSettingsCommand
import ring.protocol.commands.Hrv
import ring.protocol.commands.RealTimeCommand

class RingApp : Application() {
    val graph: Graph by lazy { Graph(this) }
}

/** Risultato di una misura live: bpm e HRV calcolata dai battiti RR. */
data class LiveResult(val bpm: Int?, val rmssd: Double?, val sdnn: Double?, val beats: Int)

/** Stato osservabile dalla UI. */
data class Status(
    val connected: Boolean = false,
    val connectionText: String = "Non connesso",
    val battery: Battery? = null,
    val capabilities: Capabilities? = null,
    val syncing: Boolean = false,
    val syncStep: String = "",
    val syncProgress: Float = 0f,
    val connectFailures: Int = 0,
    val lastSyncEpoch: Long = 0,
    val lastError: String? = null,
    val live: LiveResult? = null,
    val liveBeats: Int = 0,
    val measuring: Boolean = false,
    val monitors: Map<String, Boolean> = emptyMap(),
)

/** Oggetti condivisi tra UI e servizio: database, connessione BLE, sync, alert. */
class Graph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dao = Room.databaseBuilder(context, RingDb::class.java, "ring.db").build().dao()
    val settings = SettingsStore(context)
    private val manager = RingBleManager(context)
    val session = RingSession(manager)
    private val sync = SyncEngine(dao)
    val alerts = AlertEngine(context, dao, settings)
    private val _status = MutableStateFlow(Status(lastSyncEpoch = settings.lastSyncEpoch))
    val status: StateFlow<Status> = _status
    private val syncLock = Mutex()

    private suspend fun ensureConnected() {
        if (manager.isConnected) return
        val address = settings.deviceAddress ?: error("Nessun ring selezionato")
        _status.update { it.copy(connectionText = "Connessione…") }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        try {
            manager.connectTo(adapter.getRemoteDevice(address))
        } catch (e: Exception) {
            _status.update { it.copy(connectFailures = it.connectFailures + 1) }
            throw e
        }
        _status.update { it.copy(connected = true, connectionText = "Connesso", connectFailures = 0) }
        // un real-time rimasto attivo consuma batteria: stop di sicurezza a ogni connessione
        runCatching { session.v1.send(RealTimeCommand.CMD_STOP, byteArrayOf(RealTimeCommand.KIND_HEART_RATE.toByte(), 0, 0)) }
    }

    /** Un ciclo completo: connette se serve, sincronizza, valuta gli alert. */
    suspend fun syncNow() = syncLock.withLock {
        _status.update { it.copy(syncing = true, lastError = null) }
        try {
            ensureConnected()
            val days = if (settings.lastSyncEpoch == 0L) FIRST_SYNC_DAYS else REGULAR_SYNC_DAYS
            val report = sync.sync(session, days) { step, fraction -> _status.update { it.copy(syncStep = step, syncProgress = fraction) } }
            if (report.errors.isEmpty()) settings.lastSyncEpoch = Instant.now().epochSecond
            _status.update {
                it.copy(
                    battery = report.battery ?: it.battery,
                    capabilities = report.capabilities ?: it.capabilities,
                    lastSyncEpoch = settings.lastSyncEpoch,
                    lastError = report.errors.takeIf { e -> e.isNotEmpty() }?.joinToString("; "),
                )
            }
            alerts.evaluate(report.battery?.level, report.battery?.charging ?: false)
        } catch (e: Exception) {
            _status.update { it.copy(connected = manager.isConnected, connectionText = if (manager.isConnected) "Connesso" else "Non connesso", lastError = e.message ?: e.javaClass.simpleName) }
        } finally {
            _status.update { it.copy(syncing = false, syncProgress = 1f) }
        }
    }

    /** Attiva le misure automatiche di default (cuore ogni 5 min, SpO2, stress, HRV, temperatura). */
    suspend fun enableDefaultMonitors() {
        try {
            ensureConnected()
            val result = mutableMapOf<String, Boolean>()
            result["hr"] = HrSettingsCommand.write(session.v1, HeartRateSettings(true, HR_INTERVAL_MIN)).enabled
            Feature.entries.forEach { result[it.name] = AutoMonitorCommand.write(session.v1, it, true) }
            _status.update { it.copy(monitors = result) }
        } catch (e: Exception) {
            _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Misura live: bpm e HRV (RMSSD/SDNN) dai battiti RR; il sensore impiega ~30-60 s ad agganciarsi. */
    fun measureLive() {
        scope.launch {
            _status.update { it.copy(measuring = true, live = null, liveBeats = 0, lastError = null) }
            try {
                ensureConnected()
                val m = RealTimeCommand.measure(session.v1) { n -> _status.update { it.copy(liveBeats = n) } }
                if (m == null) {
                    _status.update { it.copy(lastError = "Misura live non riuscita: dito ben aderente? riprova") }
                } else {
                    val rr = Hrv.clean(m.rr)
                    val result = LiveResult(m.bpm, Hrv.rmssd(rr), Hrv.sdnn(rr), rr.size)
                    _status.update { it.copy(live = result) }
                    val now = Instant.now().epochSecond
                    m.bpm?.let { dao.upsertSamples(listOf(Sample(SampleType.HR, now, it.toDouble()))) }
                    result.rmssd?.let { dao.upsertSamples(listOf(Sample(SampleType.HRV_LIVE, now, it))) }
                }
            } catch (e: Exception) {
                _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            } finally {
                _status.update { it.copy(measuring = false) }
            }
        }
    }

    /** Legge lo stato dei monitor automatici del ring. */
    fun refreshMonitors() {
        scope.launch {
            try {
                ensureConnected()
                val result = mutableMapOf<String, Boolean>()
                result["hr"] = HrSettingsCommand.read(session.v1).enabled
                Feature.entries.forEach { result[it.name] = AutoMonitorCommand.read(session.v1, it) }
                _status.update { it.copy(monitors = result) }
            } catch (e: Exception) {
                _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun setMonitor(name: String, enabled: Boolean) {
        scope.launch {
            try {
                ensureConnected()
                val confirmed = if (name == "hr") {
                    HrSettingsCommand.write(session.v1, HeartRateSettings(enabled, HR_INTERVAL_MIN)).enabled
                } else {
                    AutoMonitorCommand.write(session.v1, Feature.valueOf(name), enabled)
                }
                _status.update { it.copy(monitors = it.monitors + (name to confirmed)) }
            } catch (e: Exception) {
                _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun disconnect() {
        scope.launch { manager.disconnect().enqueue() }
    }

    private companion object {
        const val FIRST_SYNC_DAYS = 7
        const val REGULAR_SYNC_DAYS = 2
        const val HR_INTERVAL_MIN = 5
    }
}
