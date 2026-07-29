package tk.horiuchi.hashirimizumaru

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "waypoints")
data class Waypoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val memo: String = "",
    val latitude: Double,
    val longitude: Double,
    val depth: Double? = null,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis()
)

@Entity(tableName = "catches")
data class CatchRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val fish: String,
    val size: Double? = null,
    val photoUri: String? = null,
    val memo: String = ""
)

@Entity(tableName = "tracks", indices = [Index("time")])
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double
)

@Dao
interface BoatDao {
    @Query("SELECT * FROM waypoints ORDER BY updated DESC")
    fun waypoints(): Flow<List<Waypoint>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWaypoint(value: Waypoint): Long
    @Delete suspend fun deleteWaypoint(value: Waypoint)

    @Query("SELECT * FROM catches ORDER BY time DESC")
    fun catches(): Flow<List<CatchRecord>>
    @Insert suspend fun saveCatch(value: CatchRecord)
    @Delete suspend fun deleteCatch(value: CatchRecord)

    @Query("SELECT * FROM tracks ORDER BY time ASC")
    fun tracks(): Flow<List<TrackPoint>>
    @Insert suspend fun saveTracks(values: List<TrackPoint>)
    @Query("DELETE FROM tracks")
    suspend fun clearTracks()
}

@Database(
    entities = [Waypoint::class, CatchRecord::class, TrackPoint::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BoatDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context, AppDatabase::class.java, "hashirimizumaru.db"
        ).build()
    }
}
