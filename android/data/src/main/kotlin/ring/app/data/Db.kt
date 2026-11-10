package ring.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Measurement types stored in the `samples` table. */
object SampleType {
    const val HR = "hr"
    const val TEMP = "temp"
    const val STRESS = "stress"
    const val HRV = "hrv"
    const val HRV_LIVE = "hrv_live"
    const val RESP = "resp"

    /** Rhythm check result: 1 = irregular, 0 = regular (inconclusive checks are not stored). */
    const val RHYTHM = "rhythm"

    const val SPO2 = "spo2"
    const val BATTERY = "battery"
}

/** One measurement: instant (epoch seconds, UTC) and value. Key (type, ts): re-syncs are idempotent. */
@Entity(tableName = "samples", primaryKeys = ["type", "ts"])
data class Sample(val type: String, val ts: Long, val value: Double, val demo: Boolean = false)

@Entity(tableName = "sleep_sessions")
data class SleepEntity(
    @PrimaryKey val start: Long,
    val end: Long,
    val lightMin: Int,
    val deepMin: Int,
    val remMin: Int,
    val awakeMin: Int,
    val demo: Boolean = false,
)

/**
 * A fired alert. Only the kind and the numbers are stored so the text can be shown in the current language;
 * `message` is kept for alerts created by older versions.
 */
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val kind: String,
    val message: String = "",
    val value: Double = 0.0,
    val threshold: Double = 0.0,
    val detail: String = "",
)

/**
 * An incident: something went off (an alert fired, or the user started a check). It stores the context at that
 * moment and, once a check was done, the result. Only codes and numbers are stored, never localized text.
 */
@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** Alert kind that opened it, or "manual". */
    val source: String,
    val triggerValue: Double = 0.0,
    /** TriageLevel name, empty until a check was done. */
    val level: String = "",
    /** Comma-separated TriageCause names. */
    val causes: String = "",
    /** Comma-separated TriageAdvice names. */
    val advice: String = "",
    /** Comma-separated Symptom names. */
    val symptoms: String = "",
    val bpm: Int? = null,
    val rmssd: Double? = null,
    val spo2: Int? = null,
    val stress: Double? = null,
    val skinTemp: Double? = null,
    /** Heart rate of the previous hour as "ts:bpm;ts:bpm". */
    val hrTrend: String = "",
    val checked: Boolean = false,
    /** Comma-separated [IncidentCategory] names chosen by the user. */
    val categories: String = "",
    /** Free text written by the user. */
    val note: String = "",
)

/** Broad, fixed categories the user can tag an incident with. */
enum class IncidentCategory { EXERCISE, STRESS_EMOTION, ILLNESS, SUBSTANCES, SLEEP_FATIGUE, ENVIRONMENT, OTHER }

@Dao
interface RingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSamples(samples: List<Sample>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleep(sessions: List<SleepEntity>)

    @Insert
    suspend fun insertAlert(alert: AlertEntity)

    @Query("SELECT * FROM samples WHERE type = :type AND ts BETWEEN :from AND :to ORDER BY ts")
    fun observe(type: String, from: Long, to: Long): Flow<List<Sample>>

    @Query("SELECT * FROM samples WHERE type = :type ORDER BY ts DESC LIMIT 1")
    fun observeLatest(type: String): Flow<Sample?>

    @Query("SELECT * FROM samples WHERE type = :type AND ts BETWEEN :from AND :to ORDER BY ts")
    suspend fun range(type: String, from: Long, to: Long): List<Sample>

    @Query("SELECT * FROM sleep_sessions WHERE start >= :from ORDER BY start")
    suspend fun sleepSince(from: Long): List<SleepEntity>

    @Query("SELECT * FROM samples WHERE type = :type AND ts > :after ORDER BY ts")
    suspend fun since(type: String, after: Long): List<Sample>

    @Query("SELECT * FROM samples WHERE type = :type ORDER BY ts DESC LIMIT :n")
    suspend fun lastN(type: String, n: Int): List<Sample>

    @Query("SELECT * FROM sleep_sessions WHERE start >= :from ORDER BY start DESC")
    fun observeSleep(from: Long): Flow<List<SleepEntity>>

    @Query("SELECT * FROM alerts ORDER BY ts DESC LIMIT 50")
    fun observeAlerts(): Flow<List<AlertEntity>>

    @Query("DELETE FROM alerts")
    suspend fun clearAlerts()

    @Insert
    suspend fun insertIncident(incident: IncidentEntity): Long

    @androidx.room.Update
    suspend fun updateIncident(incident: IncidentEntity)

    @Query("SELECT * FROM incidents ORDER BY ts DESC LIMIT 50")
    fun observeIncidents(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE id = :id")
    suspend fun incidentById(id: Long): IncidentEntity?

    @Query("SELECT * FROM incidents WHERE ts >= :from ORDER BY ts")
    suspend fun incidentsSince(from: Long): List<IncidentEntity>

    @Query("SELECT * FROM incidents WHERE ts >= :since AND checked = 0 ORDER BY ts DESC LIMIT 1")
    suspend fun latestUnchecked(since: Long): IncidentEntity?

    @Query("DELETE FROM incidents")
    suspend fun clearIncidents()

    @Query("DELETE FROM incidents WHERE id = :id")
    suspend fun deleteIncident(id: Long)

    @Query("DELETE FROM samples WHERE type = :type AND ts >= :from AND ts < :to AND demo = 0")
    suspend fun deleteRange(type: String, from: Long, to: Long)

    @Query("DELETE FROM samples WHERE demo = 1")
    suspend fun deleteDemoSamples()

    @Query("DELETE FROM sleep_sessions WHERE demo = 1")
    suspend fun deleteDemoSleep()
}

@Database(entities = [Sample::class, SleepEntity::class, AlertEntity::class, IncidentEntity::class], version = 8, exportSchema = false)
abstract class RingDb : RoomDatabase() {
    abstract fun dao(): RingDao
}

/** v1 -> v2: demo-data flag. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE samples ADD COLUMN demo INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sleep_sessions ADD COLUMN demo INTEGER NOT NULL DEFAULT 0")
    }
}

/** v2 -> v3: alerts store kind and numbers instead of localized text. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alerts ADD COLUMN value REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE alerts ADD COLUMN threshold REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE alerts ADD COLUMN detail TEXT NOT NULL DEFAULT ''")
    }
}

/** v3 -> v4: incident log. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS incidents (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ts INTEGER NOT NULL, " +
                "source TEXT NOT NULL, triggerValue REAL NOT NULL, level TEXT NOT NULL, causes TEXT NOT NULL, " +
                "advice TEXT NOT NULL, symptoms TEXT NOT NULL, bpm INTEGER, rmssd REAL, spo2 INTEGER, stress REAL, " +
                "skinTemp REAL, hrTrend TEXT NOT NULL, checked INTEGER NOT NULL)",
        )
    }
}

/** v4 -> v5: user categories and note on incidents. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE incidents ADD COLUMN categories TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE incidents ADD COLUMN note TEXT NOT NULL DEFAULT ''")
    }
}

/** v5 -> v6: alarms (feature removed again in v7). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS alarms (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, hour INTEGER NOT NULL, " +
                "minute INTEGER NOT NULL, weekdays INTEGER NOT NULL, enabled INTEGER NOT NULL, name TEXT NOT NULL)",
        )
    }
}

/** v6 -> v7: the R09 has no vibration motor, so the alarms table is dropped. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS alarms")
    }
}

/** v7 -> v8: step counting was removed (the ring's step data is unreliable), so its samples are dropped. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM samples WHERE type = 'steps'")
    }
}
