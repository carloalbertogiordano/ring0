package ring.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ring.domain.Profile
import ring.domain.Sex
import java.time.LocalDate

data class Thresholds(
    val hrHigh: Int = 130,
    val hrLow: Int = 40,
    val tempHigh: Double = 37.8,
    val stressHigh: Int = 80,
    val spo2Low: Int = 92,
    val batteryLow: Int = 15,
)

/** App preferences (SharedPreferences): chosen ring, sync interval, alert thresholds. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ring", Context.MODE_PRIVATE)

    private val _thresholds = MutableStateFlow(readThresholds())
    val thresholds: StateFlow<Thresholds> = _thresholds

    private val _profile = MutableStateFlow(readProfile())

    /** Body data for the estimates; null until the user fills it in. Kept on the phone only. */
    val profile: StateFlow<Profile?> = _profile

    var deviceAddress: String?
        get() = prefs.getString("address", null)
        set(value) = prefs.edit().putString("address", value).apply()

    var syncIntervalMin: Int
        get() = prefs.getInt("syncMin", 15)
        set(value) = prefs.edit().putInt("syncMin", value).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    var alertsEnabled: Boolean
        get() = prefs.getBoolean("alerts", true)
        set(value) = prefs.edit().putBoolean("alerts", value).apply()

    var lastSyncEpoch: Long
        get() = prefs.getLong("lastSync", 0)
        set(value) = prefs.edit().putLong("lastSync", value).apply()

    fun setProfile(p: Profile) {
        prefs.edit()
            .putInt("profBirthYear", LocalDate.now().year - p.ageYears)
            .putString("profSex", p.sex.name)
            .putFloat("profWeight", p.weightKg.toFloat())
            .putFloat("profHeight", p.heightCm.toFloat())
            .putInt("profHrMax", p.hrMaxOverride ?: 0)
            .apply()
        _profile.value = p
    }

    private fun readProfile(): Profile? {
        val birthYear = prefs.getInt("profBirthYear", 0)
        val sex = prefs.getString("profSex", null)?.let { runCatching { Sex.valueOf(it) }.getOrNull() }
        if (birthYear == 0 || sex == null) return null
        return Profile(
            ageYears = LocalDate.now().year - birthYear,
            sex = sex,
            weightKg = prefs.getFloat("profWeight", DEFAULT_WEIGHT_KG).toDouble(),
            heightCm = prefs.getFloat("profHeight", DEFAULT_HEIGHT_CM).toDouble(),
            hrMaxOverride = prefs.getInt("profHrMax", 0).takeIf { it > 0 },
        )
    }

    fun lastAlert(kind: String): Long = prefs.getLong("alert_$kind", 0)

    fun markAlert(kind: String, epoch: Long) = prefs.edit().putLong("alert_$kind", epoch).apply()

    fun setThresholds(t: Thresholds) {
        prefs.edit()
            .putInt("hrHigh", t.hrHigh).putInt("hrLow", t.hrLow).putFloat("tempHigh", t.tempHigh.toFloat())
            .putInt("stressHigh", t.stressHigh).putInt("spo2Low", t.spo2Low).putInt("batteryLow", t.batteryLow)
            .apply()
        _thresholds.value = t
    }

    private fun readThresholds(): Thresholds {
        val d = Thresholds()
        return Thresholds(
            hrHigh = prefs.getInt("hrHigh", d.hrHigh),
            hrLow = prefs.getInt("hrLow", d.hrLow),
            tempHigh = prefs.getFloat("tempHigh", d.tempHigh.toFloat()).toDouble(),
            stressHigh = prefs.getInt("stressHigh", d.stressHigh),
            spo2Low = prefs.getInt("spo2Low", d.spo2Low),
            batteryLow = prefs.getInt("batteryLow", d.batteryLow),
        )
    }
}

private const val DEFAULT_WEIGHT_KG = 70f
private const val DEFAULT_HEIGHT_CM = 170f
