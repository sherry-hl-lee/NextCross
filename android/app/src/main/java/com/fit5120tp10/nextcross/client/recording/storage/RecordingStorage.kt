package com.fit5120tp10.nextcross.client.recording.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** One trip row. [endedAtMillis] is null while recording. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val tripId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?
)

/** GPS points for a trip. Speed is null if the provider did not report it. */
@Entity(
    tableName = "gps_points",
    indices = [Index("tripId")]
)
data class GpsPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val recordedAtMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMetersPerSecond: Float?,
    val accuracyMeters: Float?
)

/**
 * Local driving events for a trip.
 * [type] is [com.fit5120tp10.nextcross.client.detection.DrivingEventType]:
 * HardAcceleration / HardBraking / SpeedLimitExceedance.
 *
 * Accel/brake: [accelerationMagnitude] = |a| m/s²; speed columns are null.
 * SpeedLimitExceedance: [recordedAtMillis] = segment start; [accelerationMagnitude] = peak
 * exceedance km/h; speed columns hold limit / peak / span for Trip Summary.
 */
@Entity(
    tableName = "driving_events",
    indices = [Index("tripId")]
)
data class DrivingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val type: String,
    val recordedAtMillis: Long,
    val accelerationMagnitude: Float,
    val endedAtMillis: Long? = null,
    val startGpsPointId: Long? = null,
    val endGpsPointId: Long? = null,
    val limitKmh: Int? = null,
    val limitStatus: String? = null,
    val peakSpeedMps: Float? = null,
    val averageExceedanceKmh: Double? = null,
    val severity: String? = null
)

/**
 * Compact local Trip Report metadata.
 * Route geometry stays in [gps_points]; do not store map screenshots here.
 */
@Entity(
    tableName = "trip_reports",
    indices = [Index("expiresAtMillis"), Index("endedAtMillis")]
)
data class TripReportEntity(
    /** Recording trip id. */
    @PrimaryKey val tripId: String,
    val plannedTripId: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val startingPointLabel: String,
    val destinationLabel: String,
    val durationSeconds: Long?,
    val distanceMeters: Int?,
    val hardAccelerationCount: Int,
    val hardBrakingCount: Int,
    val includeDetailedRouteByDefault: Boolean,
    val includeReflectionByDefault: Boolean,
    val finalAiText: String?,
    /** Parent email captured at End Trip; null for legacy rows — never substitute Home default. */
    val parentEmail: String? = null,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
)

/** A completed AI reflection, kept separately so one event cannot overwrite another. */
@Entity(
    tableName = "trip_ai_reflections",
    primaryKeys = ["tripId", "eventId"],
    indices = [Index("tripId")]
)
data class TripAiReflectionEntity(
    val tripId: String,
    val eventId: String,
    val text: String,
    val completedAtMillis: Long
)

@Dao
interface RecordingDao {
    @Insert
    fun insertTrip(trip: TripEntity)

    @Query("UPDATE trips SET endedAtMillis = :endedAtMillis WHERE tripId = :tripId")
    fun endTrip(tripId: String, endedAtMillis: Long)

    @Insert
    fun insertGpsPoint(point: GpsPointEntity)

    @Query("SELECT * FROM trips WHERE tripId = :tripId")
    fun tripById(tripId: String): TripEntity?

    // Preserve acquisition/insertion order, including duplicate or reversed wall-clock times.
    @Query("SELECT * FROM gps_points WHERE tripId = :tripId ORDER BY id ASC")
    fun gpsPointsForTrip(tripId: String): List<GpsPointEntity>

    @Insert
    fun insertDrivingEvent(event: DrivingEventEntity): Long

    /** Events for this trip, for the summary detail list. */
    @Query("SELECT * FROM driving_events WHERE tripId = :tripId ORDER BY recordedAtMillis ASC")
    fun drivingEventsForTrip(tripId: String): List<DrivingEventEntity>

    @Query(
        "SELECT * FROM driving_events WHERE tripId = :tripId AND type = :type " +
            "ORDER BY recordedAtMillis ASC, id ASC"
    )
    fun drivingEventsForTripByType(tripId: String, type: String): List<DrivingEventEntity>

    /** Event count for this trip and type, for the summary totals. */
    @Query("SELECT COUNT(*) FROM driving_events WHERE tripId = :tripId AND type = :type")
    fun countDrivingEvents(tripId: String, type: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTripReport(report: TripReportEntity)

    @Query("SELECT * FROM trip_reports WHERE tripId = :tripId LIMIT 1")
    fun tripReportById(tripId: String): TripReportEntity?

    @Query(
        "SELECT * FROM trip_reports WHERE expiresAtMillis >= :nowMillis " +
            "ORDER BY endedAtMillis DESC"
    )
    fun unexpiredTripReports(nowMillis: Long): List<TripReportEntity>

    @Query("SELECT tripId FROM trip_reports WHERE expiresAtMillis < :nowMillis")
    fun expiredTripReportIds(nowMillis: Long): List<String>

    @Query("UPDATE trip_reports SET finalAiText = :finalAiText WHERE tripId = :tripId")
    fun updateTripReportAiText(tripId: String, finalAiText: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTripAiReflection(reflection: TripAiReflectionEntity)

    @Query("SELECT * FROM trip_ai_reflections WHERE tripId = :tripId ORDER BY completedAtMillis ASC")
    fun tripAiReflectionsForTrip(tripId: String): List<TripAiReflectionEntity>

    @Query("DELETE FROM trip_ai_reflections WHERE tripId = :tripId")
    fun deleteTripAiReflectionsForTrip(tripId: String)

    @Query("DELETE FROM trip_reports WHERE tripId = :tripId")
    fun deleteTripReport(tripId: String)

    @Query("DELETE FROM gps_points WHERE tripId = :tripId")
    fun deleteGpsPointsForTrip(tripId: String)

    @Query("DELETE FROM driving_events WHERE tripId = :tripId")
    fun deleteDrivingEventsForTrip(tripId: String)

    @Query("DELETE FROM trips WHERE tripId = :tripId")
    fun deleteTrip(tripId: String)
}

@Database(
    entities = [
        TripEntity::class,
        GpsPointEntity::class,
        DrivingEventEntity::class,
        TripReportEntity::class,
        TripAiReflectionEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class RecordingDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip_reports ADD COLUMN parentEmail TEXT")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS trip_ai_reflections (tripId TEXT NOT NULL, eventId TEXT NOT NULL, text TEXT NOT NULL, completedAtMillis INTEGER NOT NULL, PRIMARY KEY(tripId, eventId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_ai_reflections_tripId ON trip_ai_reflections (tripId)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE driving_events ADD COLUMN endedAtMillis INTEGER")
                db.execSQL("ALTER TABLE driving_events ADD COLUMN startGpsPointId INTEGER")
                db.execSQL("ALTER TABLE driving_events ADD COLUMN endGpsPointId INTEGER")
                db.execSQL("ALTER TABLE driving_events ADD COLUMN limitKmh INTEGER")
                db.execSQL("ALTER TABLE driving_events ADD COLUMN limitStatus TEXT")
                db.execSQL("ALTER TABLE driving_events ADD COLUMN peakSpeedMps REAL")
                db.execSQL("ALTER TABLE driving_events ADD COLUMN averageExceedanceKmh REAL")
                db.execSQL("ALTER TABLE driving_events ADD COLUMN severity TEXT")
            }
        }

        fun create(context: Context): RecordingDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                RecordingDatabase::class.java,
                "nextcross-recording.db"
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }
    }
}
