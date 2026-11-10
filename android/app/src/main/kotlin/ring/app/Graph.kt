package ring.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.room.Room
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
import ring.app.data.MIGRATION_3_4
import ring.app.data.MIGRATION_4_5
import ring.app.data.MIGRATION_5_6
import ring.app.data.MIGRATION_6_7
import ring.app.data.MIGRATION_7_8
import ring.app.data.RingDb
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.app.data.SettingsStore
import ring.app.insights.BodyBatteryReport
import ring.app.insights.Insights
import ring.app.insights.ReadinessReport
import ring.app.insights.TrainingReport
import ring.app.sync.AlertEngine
import ring.app.sync.IncidentRecorder
import ring.app.sync.SyncEngine
import ring.domain.BatteryEstimate
import ring.domain.RespirationRate
import ring.domain.Symptom
import ring.domain.Triage
import ring.domain.TriageInput
import ring.domain.TriageResult
import ring.protocol.commands.AutoMonitorCommand
import ring.protocol.commands.Battery
import ring.protocol.commands.Capabilities
import ring.protocol.commands.Feature
import ring.protocol.commands.FindRingCommand
import ring.protocol.commands.HeartRateSettings
import ring.protocol.commands.HrSettingsCommand
import ring.protocol.commands.Hrv
import ring.protocol.commands.RealTimeCommand
import java.time.Instant

class RingApp : Application() {
    val graph: Graph by lazy { Graph(this) }
}

/** Result of a live measurement: bpm and HRV computed from the RR beats. */
data class LiveResult(
    val bpm: Int?,
    val rmssd: Double?,
    val sdnn: Double?,
    val beats: Int,
    val breathsPerMinute: Double? = null,
    val breathConfidence: Double = 0.0,
)

/** State observed by the UI. */
enum class CheckPhase { IDLE, HEART, SPO2, DONE, FAILED }

/** Progress and outcome of the check. */
data class CheckState(
    val phase: CheckPhase = CheckPhase.IDLE,
    val result: TriageResult? = null,
    val bpm: Int? = null,
    val spo2: Int? = null,
    val stress: Int? = null,
    val skinTemp: Double? = null,
    val rmssd: Double? = null,
    val incidentId: Long? = null,
)

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
    val training: TrainingReport? = null,
    val batteryEstimate: BatteryEstimate? = null,
    val bodyBattery: BodyBatteryReport? = null,
    val liveTarget: Int = 12,
    val spo2Measuring: Boolean = false,
    val liveSpo2: Int? = null,
    val stressMeasuring: Boolean = false,
    val liveStress: Int? = null,
    val check: CheckState = CheckState(),
)

/** Oggetti condivisi tra UI e servizio: database, connessione BLE, sync, alert. */
class Graph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dao = Room.databaseBuilder(
        context,
        RingDb::class.java,
        "ring.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().dao()
    val settings = SettingsStore(context)
    private val manager = RingBleManager(context)
    val session = RingSession(manager)
    private val sync = SyncEngine(dao)
    val insights = Insights(dao)
    val incidents = IncidentRecorder(dao)
    val alerts = AlertEngine(context, dao, settings, insights, incidents)
    private val _checkRequested = MutableStateFlow(false)
    val checkRequested: StateFlow<Boolean> = _checkRequested
    private val _status = MutableStateFlow(Status(lastSyncEpoch = settings.lastSyncEpoch))
    val status: StateFlow<Status> = _status
    private val syncLock = Mutex()

    init {
        scope.launch { refreshInsights() }
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
            refreshInsights()
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
            refreshInsights()
            alerts.evaluate(null, false)
        }
    }

    fun clearDemo() {
        scope.launch {
            dao.deleteDemoSamples()
            dao.deleteDemoSleep()
            refreshInsights()
        }
    }

    suspend fun refreshReadiness() {
        val report = runCatching { insights.readiness() }.getOrNull()
        _status.update { it.copy(readiness = report) }
    }

    /** Training numbers need the profile; without it they stay empty. */
    suspend fun refreshTraining() {
        val profile = settings.profile.value
        val report = profile?.let { runCatching { insights.training(it) }.getOrNull() }
        _status.update { it.copy(training = report) }
    }

    suspend fun refreshInsights() {
        refreshReadiness()
        refreshTraining()
        val battery = runCatching { insights.batteryForecast() }.getOrNull()
        val energy = runCatching { insights.bodyBattery(settings.profile.value) }.getOrNull()
        _status.update { it.copy(batteryEstimate = battery, bodyBattery = energy) }
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
     * Live heart measurement: bpm and HRV (RMSSD/SDNN) from the RR beats; the sensor needs ~30-60 s to lock on.
     * With `withRespiration` it collects more beats (~70) to also estimate the breathing rate.
     */
    private suspend fun heartMeasure(withRespiration: Boolean): LiveResult? {
        val target = if (withRespiration) RESPIRATION_BEATS else QUICK_BEATS
        _status.update { it.copy(measuring = true, live = null, liveBeats = 0, liveTarget = target, lastError = null) }
        try {
            ensureConnected()
            val m = RealTimeCommand.measure(session.v1, maxPackets = target * PACKETS_PER_BEAT_BUDGET, targetBeats = target) { n ->
                _status.update { it.copy(liveBeats = n) }
            }
            if (m == null) {
                _status.update { it.copy(lastError = context.getString(R.string.measure_failed)) }
                return null
            }
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
            return result
        } catch (e: Exception) {
            _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            return null
        } finally {
            _status.update { it.copy(measuring = false) }
        }
    }

    /** SpO2 on demand (the ring has one sensor, so this runs after, not together with, the heart measurement). */
    private suspend fun spo2Measure(): Int? {
        _status.update { it.copy(spo2Measuring = true, liveSpo2 = null) }
        try {
            ensureConnected()
            val value = RealTimeCommand.measureValue(session.v1, RealTimeCommand.KIND_SPO2)
            _status.update { it.copy(liveSpo2 = value) }
            if (value == null) {
                _status.update { it.copy(lastError = context.getString(R.string.spo2_failed)) }
            } else {
                dao.upsertSamples(listOf(Sample(SampleType.SPO2, Instant.now().epochSecond, value.toDouble())))
            }
            return value
        } catch (e: Exception) {
            _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            return null
        } finally {
            _status.update { it.copy(spo2Measuring = false) }
        }
    }

    /** Experimental: on-demand stress ("pressure" real-time kind). Not verified on the R09. */
    private suspend fun stressMeasure(): Int? {
        _status.update { it.copy(stressMeasuring = true, liveStress = null) }
        try {
            ensureConnected()
            val value = RealTimeCommand.measureValue(session.v1, RealTimeCommand.KIND_STRESS)
            _status.update { it.copy(liveStress = value) }
            if (value == null) {
                _status.update { it.copy(lastError = context.getString(R.string.stress_failed)) }
            } else {
                dao.upsertSamples(listOf(Sample(SampleType.STRESS, Instant.now().epochSecond, value.toDouble())))
            }
            return value
        } catch (e: Exception) {
            _status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            return null
        } finally {
            _status.update { it.copy(stressMeasuring = false) }
        }
    }

    fun measureLive(withRespiration: Boolean = false) {
        scope.launch {
            // the full measure also takes SpO2 afterwards (same sensor, so one after the other)
            if (heartMeasure(withRespiration) != null && withRespiration) spo2Measure()
        }
    }

    fun measureSpo2() {
        scope.launch { spo2Measure() }
    }

    fun measureStress() {
        scope.launch { stressMeasure() }
    }

    /** Asks the UI to open the check screen (from a notification). */
    fun requestCheck() {
        _checkRequested.value = true
    }

    fun consumeCheckRequest() {
        _checkRequested.value = false
    }

    fun resetCheck() {
        _status.update { it.copy(check = CheckState()) }
    }

    /**
     * The check: quick heart + HRV, then SpO2, then the recent context and the symptoms go through the triage rules.
     * The outcome is stored in the incident log.
     */
    fun runCheck(symptoms: Set<Symptom>) {
        scope.launch {
            _status.update { it.copy(check = CheckState(phase = CheckPhase.HEART)) }
            val live = heartMeasure(false)
            if (live == null) {
                _status.update { it.copy(check = CheckState(phase = CheckPhase.FAILED)) }
                return@launch
            }
            _status.update { it.copy(check = it.check.copy(phase = CheckPhase.SPO2, bpm = live.bpm)) }
            val spo2 = spo2Measure()
            val now = Instant.now().epochSecond
            val ctx = insights.checkContext(now)
            val input = TriageInput(
                bpm = live.bpm,
                restingHr = ctx.restingHr,
                spo2 = spo2,
                stress = ctx.stress,
                hrvZ = ctx.hrvZ,
                skinTemp = ctx.skinTemp,
                tempZ = ctx.tempZ,
                symptoms = symptoms,
            )
            val result = Triage.assess(input)
            val incidentId = incidents.saveCheck(now, input, live.rmssd, result)
            _status.update {
                it.copy(check = CheckState(CheckPhase.DONE, result, live.bpm, spo2, ctx.stress, ctx.skinTemp, rmssd = live.rmssd, incidentId = incidentId))
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

    /** Lights the ring's green LED for about 10 seconds so it can be located. */
    fun findRing() {
        scope.launch {
            try {
                ensureConnected()
                FindRingCommand.find(session.v1)
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
