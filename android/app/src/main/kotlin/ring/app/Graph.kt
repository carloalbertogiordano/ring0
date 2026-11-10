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
import ring.app.data.MIGRATION_1_2
import ring.app.data.MIGRATION_2_3
import ring.app.data.RingDb
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.app.data.SettingsStore
import ring.app.insights.Insights
import ring.app.insights.ReadinessReport
import ring.domain.RespirationRate
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
data class LiveResult(
    val bpm: Int?,
    val rmssd: Double?,
    val sdnn: Double?,
    val beats: Int,
    val breathsPerMinute: Double? = null,
    val breathConfidence: Double = 0.0,
)

/** State observed by the UI. */
data class Status(
    val connected: Boolean = false,
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
    val readiness: ReadinessReport? = null,
    val liveTarget: Int = 12,
)

/** Oggetti condivisi tra UI e servizio: database, connessione BLE, sync, alert. */
class Graph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dao = Room.databaseBuilder(context, RingDb::class.java, "ring.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().dao()
    val settings = SettingsStore(context)
    private val manager = RingBleManager(context)
    val session = RingSession(manager)
    private val sync = SyncEngine(dao)
    val insights = Insights(dao)
    val alerts = AlertEngine(context, dao, settings, insights)
    private val _status = MutableStateFlow(Status(lastSyncEpoch = settings.lastSyncEpoch))
    val status: StateFlow<Status> = _status
    private val syncLock = Mutex()

    init {
        scope.launch { refreshReadiness() }
    }

    private suspend fun ensureConnected() {
        if (manager.isConnected) return
        val address = settings.deviceAddress ?: error(context.getString(R.string.no_ring_selected))
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        try {
            manager.connectTo(adapter.getRemoteDevice(address))
        } catch (e: Exception) {
            _status.update { it.copy(connectFailures = it.connectFailures + 1) }
            throw e
        }
        _status.update { it.copy(connected = true, connectFailures = 0) }
        // a real-time stream left running drains the battery: safety stop on every connection
        runCatching { session.v1.send(RealTimeCommand.CMD_STOP, byteArrayOf(RealTimeCommand.KIND_HEART_RATE.toByte(), 0, 0)) }
    }

    /** A full cycle: connect if needed, sync, evaluate alerts. */
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
            refreshReadiness()
            alerts.evaluate(report.battery?.level, report.battery?.charging ?: false)
        } catch (e: Exception) {
            _status.update { it.copy(connected = manager.isConnected, lastError = e.message ?: e.javaClass.simpleName) }
        } finally {
            _status.update { it.copy(syncing = false, syncProgress = 1f) }
        }
    }

    /** Loads 14 days of synthetic data flagged "demo" (healthy or feeling-ill scenario). */
    fun loadDemo(sick: Boolean) {
        scope.launch {
            val demo = ring.app.insights.DemoData.generate(Instant.now().epochSecond, sick)
            dao.upsertSamples(demo.samples)
            dao.upsertSleep(demo.sleep)
            refreshReadiness()
            alerts.evaluate(null, false)
        }
    }

    fun clearDemo() {
        scope.launch {
            dao.deleteDemoSamples()
            dao.deleteDemoSleep()
            refreshReadiness()
        }
    }

    suspend fun refreshReadiness() {
        val report = runCatching { insights.readiness() }.getOrNull()
        _status.update { it.copy(readiness = report) }
    }

    /** Turns on the default automatic measurements (heart every 5 min, SpO2, stress, HRV, temperature). */
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

    /**
     * Misura live: bpm e HRV (RMSSD/SDNN) dai battiti RR; il sensore impiega ~30-60 s ad agganciarsi.
     * With `withRespiration` it collects more beats (~70) to also estimate the breathing rate.
     */
    fun measureLive(withRespiration: Boolean = false) {
        scope.launch {
            val target = if (withRespiration) RESPIRATION_BEATS else QUICK_BEATS
            _status.update { it.copy(measuring = true, live = null, liveBeats = 0, liveTarget = target, lastError = null) }
            try {
                ensureConnected()
                val m = RealTimeCommand.measure(session.v1, maxPackets = target * PACKETS_PER_BEAT_BUDGET, targetBeats = target) { n ->
                    _status.update { it.copy(liveBeats = n) }
                }
                if (m == null) {
                    _status.update { it.copy(lastError = context.getString(R.string.measure_failed)) }
                } else {
                    val rr = Hrv.clean(m.rr)
                    val breath = if (withRespiration) RespirationRate.fromRr(m.rr) else null
                    val result = LiveResult(m.bpm, Hrv.rmssd(rr), Hrv.sdnn(rr), rr.size, breath?.breathsPerMinute, breath?.confidence ?: 0.0)
                    _status.update { it.copy(live = result) }
                    val now = Instant.now().epochSecond
                    m.bpm?.let { dao.upsertSamples(listOf(Sample(SampleType.HR, now, it.toDouble()))) }
                    result.rmssd?.let { dao.upsertSamples(listOf(Sample(SampleType.HRV_LIVE, now, it))) }
                    breath?.takeIf { it.confidence >= MIN_BREATH_CONFIDENCE }?.let {
                        dao.upsertSamples(listOf(Sample(SampleType.RESP, now, it.breathsPerMinute)))
                    }
                }
            } catch (e: Exception) {
                _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            } finally {
                _status.update { it.copy(measuring = false) }
            }
        }
    }

    /** Reads the state of the ring's automatic monitors. */
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
        const val QUICK_BEATS = 12
        const val RESPIRATION_BEATS = 70
        const val PACKETS_PER_BEAT_BUDGET = 4
        const val MIN_BREATH_CONFIDENCE = 0.3
    }
}
