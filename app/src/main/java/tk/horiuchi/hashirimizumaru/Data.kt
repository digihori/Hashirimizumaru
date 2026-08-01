package tk.horiuchi.hashirimizumaru

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "waypoints")
data class Waypoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val memo: String = "",
    val latitude: Double,
    val longitude: Double,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis()
)

@Entity(tableName = "catches")
data class CatchRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val size: Double? = null,
    val photoUri: String? = null,
    val memo: String = ""
)

@Entity(tableName = "track_sessions", indices = [Index("startedAt")])
data class TrackSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val memo: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val startedByNavigation: Boolean = false
)

@Entity(tableName = "tracks", indices = [Index("time"), Index("sessionId")])
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val sessionId: Long,
    val time: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double
)

@Dao
interface BoatDao {
    @Query("SELECT * FROM waypoints ORDER BY id")
    suspend fun waypointSnapshot(): List<Waypoint>
    @Query("SELECT * FROM waypoints ORDER BY updated DESC")
    fun waypoints(): Flow<List<Waypoint>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWaypoint(value: Waypoint): Long
    @Delete suspend fun deleteWaypoint(value: Waypoint)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreWaypoints(values: List<Waypoint>)
    @Query("DELETE FROM waypoints") suspend fun clearWaypoints()

    @Query("SELECT * FROM catches ORDER BY time DESC")
    fun catches(): Flow<List<CatchRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCatch(value: CatchRecord)
    @Delete suspend fun deleteCatch(value: CatchRecord)
    @Query("SELECT * FROM catches ORDER BY id")
    suspend fun catchSnapshot(): List<CatchRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreCatches(values: List<CatchRecord>)
    @Query("DELETE FROM catches") suspend fun clearCatches()

    @Query("SELECT * FROM track_sessions ORDER BY startedAt DESC")
    fun trackSessions(): Flow<List<TrackSession>>
    @Query("SELECT * FROM track_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun activeTrackSession(): Flow<TrackSession?>
    @Query("SELECT * FROM track_sessions WHERE id = :id LIMIT 1")
    suspend fun trackSession(id: Long): TrackSession?
    @Insert suspend fun saveTrackSession(value: TrackSession): Long
    @Update suspend fun updateTrackSession(value: TrackSession)
    @Delete suspend fun deleteTrackSession(value: TrackSession)
    @Query("SELECT * FROM track_sessions ORDER BY id")
    suspend fun trackSessionSnapshot(): List<TrackSession>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTrackSessions(values: List<TrackSession>)
    @Query("DELETE FROM track_sessions") suspend fun clearTrackSessions()

    @Query("SELECT * FROM tracks ORDER BY time ASC")
    fun tracks(): Flow<List<TrackPoint>>
    @Insert suspend fun saveTracks(values: List<TrackPoint>)
    @Query("DELETE FROM tracks WHERE sessionId = :sessionId")
    suspend fun deleteTracksForSession(sessionId: Long)
    @Query("DELETE FROM tracks")
    suspend fun clearTracks()
    @Query("SELECT * FROM tracks ORDER BY id")
    suspend fun trackSnapshot(): List<TrackPoint>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTracks(values: List<TrackPoint>)

    @Transaction
    suspend fun replaceAll(payload: BackupPayload) {
        clearTracks()
        clearTrackSessions()
        clearCatches()
        clearWaypoints()
        if (payload.waypoints.isNotEmpty()) restoreWaypoints(payload.waypoints)
        if (payload.trackSessions.isNotEmpty()) restoreTrackSessions(payload.trackSessions)
        if (payload.tracks.isNotEmpty()) restoreTracks(payload.tracks)
        if (payload.catches.isNotEmpty()) restoreCatches(payload.catches)
    }
}

@Database(
    entities = [Waypoint::class, CatchRecord::class, TrackSession::class, TrackPoint::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BoatDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `waypoints_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `memo` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `created` INTEGER NOT NULL,
                        `updated` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `waypoints_new`
                        (`id`, `name`, `memo`, `latitude`, `longitude`, `created`, `updated`)
                    SELECT `id`, `name`, `memo`, `latitude`, `longitude`, `created`, `updated`
                    FROM `waypoints`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `waypoints`")
                db.execSQL("ALTER TABLE `waypoints_new` RENAME TO `waypoints`")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `track_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `memo` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER,
                        `startedByNavigation` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_track_sessions_startedAt` ON `track_sessions` (`startedAt`)"
                )
                db.execSQL(
                    """
                    INSERT INTO `track_sessions`
                        (`id`, `name`, `memo`, `startedAt`, `endedAt`, `startedByNavigation`)
                    SELECT 1, '以前の航跡', '', MIN(`time`), MAX(`time`), 0
                    FROM `tracks`
                    HAVING COUNT(*) > 0
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `sessionId` INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tracks_sessionId` ON `tracks` (`sessionId`)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `catches_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `time` INTEGER NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `size` REAL,
                        `photoUri` TEXT,
                        `memo` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `catches_new`
                        (`id`, `time`, `latitude`, `longitude`, `size`, `photoUri`, `memo`)
                    SELECT `id`, `time`, `latitude`, `longitude`, `size`, `photoUri`, `memo`
                    FROM `catches`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `catches`")
                db.execSQL("ALTER TABLE `catches_new` RENAME TO `catches`")
            }
        }

        fun create(context: Context) = Room.databaseBuilder(
            context, AppDatabase::class.java, "hashirimizumaru.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
