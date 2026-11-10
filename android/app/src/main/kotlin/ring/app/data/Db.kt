package ring.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Tipi di misura salvati nella tabella `samples`. */
object SampleType {
    const val HR = "hr"
    const val TEMP = "temp"
    const val STRESS = "stress"
    const val HRV = "hrv"
    const val HRV_LIVE = "hrv_live"
    const val SPO2 = "spo2"
    const val BATTERY = "battery"
}

/** Una misura: istante (epoch secondi, UTC) e valore. Chiave (type, ts): le risincronizzazioni sono idempotenti. */
@Entity(tableName = "samples", primaryKeys = ["type", "ts"])
data class Sample(val type: String, val ts: Long, val value: Double)

@Entity(tableName = "sleep_sessions")
data class SleepEntity(
    @PrimaryKey val start: Long,
    val end: Long,
    val lightMin: Int,
    val deepMin: Int,
    val remMin: Int,
    val awakeMin: Int,
)

@Entity(tableName = "alerts")
data class AlertEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val ts: Long, val kind: String, val message: String)

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
}

@Database(entities = [Sample::class, SleepEntity::class, AlertEntity::class], version = 1, exportSchema = false)
abstract class RingDb : RoomDatabase() {
    abstract fun dao(): RingDao
}
