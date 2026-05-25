package dev.mrwick.gixxerbridge.data

import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One persisted ride. Aggregates (max/avg speed, sample count) are maintained
 * in-place by [RideStore.appendSample] as samples land.
 */
@Entity(tableName = "rides")
@Immutable
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val startOdoKm: Int,
    val endOdoKm: Int?,
    val maxSpeedKmh: Int,
    val avgSpeedKmh: Double,
    val sampleCount: Int,
    val fuelBarsStart: Int?,
    val fuelBarsEnd: Int?,
    /**
     * Auto-generated human-readable name (e.g. "Morning commute (Mon)") set
     * when the ride ends. Null while in-progress and for legacy v3 rows after
     * the destructive migration to v4. User can override via TripDetailScreen.
     */
    val name: String? = null,
)

/** One telemetry sample collected during an active ride. */
@Entity(
    tableName = "ride_samples",
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
@Immutable
data class RideSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val tMillis: Long,
    val speedKmh: Int,
    val odometerKm: Int,
    val tripAKm: Double,
    val tripBKm: Double,
    val fuelBars: Int?,
    val fuelEconKml: Double?,
)

/**
 * One GPS sample captured during an active ride, used to export the ride track
 * as a GPX file. Independent of [RideSampleEntity] because telemetry samples
 * arrive on the bike's BLE cadence (~1 Hz) while GPS samples come from the
 * phone's [com.google.android.gms.location.FusedLocationProviderClient]
 * (~0.2 Hz at PRIORITY_BALANCED_POWER_ACCURACY).
 */
@Entity(
    tableName = "ride_locations",
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
@Immutable
data class RideLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val tMillis: Long,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
)

/** Room DAO over [rides], [ride_samples], and [ride_locations]. */
@Dao
interface RideDao {
    /** Insert a ride row; returns the new auto-generated id. */
    @Insert suspend fun insertRide(ride: RideEntity): Long

    /** Update an existing ride row (used for aggregate refresh + end-of-ride). */
    @Update suspend fun updateRide(ride: RideEntity)

    /** Observe all rides, newest-first. */
    @Query("SELECT * FROM rides ORDER BY startedAtMillis DESC")
    fun observeRides(): Flow<List<RideEntity>>

    /** Fetch a single ride by id, or null if it has been deleted. */
    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun getRide(id: Long): RideEntity?

    /** Delete a ride; cascades to its samples. */
    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteRide(id: Long)

    /** Patch the human-readable name on one ride. */
    @Query("UPDATE rides SET name = :name WHERE id = :id")
    suspend fun renameRide(id: Long, name: String?)

    /** Delete every ride row; cascades to samples + locations via FK. */
    @Query("DELETE FROM rides")
    suspend fun deleteAllRides()

    /** Insert one sample row; returns the new auto-generated id. */
    @Insert suspend fun insertSample(sample: RideSampleEntity): Long

    /** Fetch all samples for a ride, oldest-first. */
    @Query("SELECT * FROM ride_samples WHERE rideId = :rideId ORDER BY tMillis ASC")
    suspend fun getSamples(rideId: Long): List<RideSampleEntity>

    /**
     * Fetch up to [limit] evenly-distributed speed samples for a ride,
     * suitable for the inline sparkline in the Trips list. Uses SQLite's
     * modulo trick to pick every Nth row so the sample set spans the full
     * ride duration regardless of ride length.
     *
     * Note: the count sub-query re-scans the table for each row; acceptable
     * cost given that ride sample counts stay under ~3600 (1 Hz × 1 hr).
     */
    @Query("""
        SELECT * FROM ride_samples
        WHERE rideId = :rideId
          AND (id % MAX(1, (SELECT COUNT(*) FROM ride_samples WHERE rideId = :rideId) / :limit)) = 0
        ORDER BY tMillis ASC
        LIMIT :limit
    """)
    suspend fun getSamplesLimited(rideId: Long, limit: Int): List<RideSampleEntity>

    /** Fetch the most-recent ride that has no end timestamp, or null. */
    @Query("SELECT * FROM rides WHERE endedAtMillis IS NULL ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun getRideInProgress(): RideEntity?

    /** Insert one GPS location for a ride; returns the new auto-generated id. */
    @Insert suspend fun insertLocation(loc: RideLocationEntity): Long

    /** Fetch all GPS locations for a ride, oldest-first. */
    @Query("SELECT * FROM ride_locations WHERE rideId = :rideId ORDER BY tMillis ASC")
    suspend fun getLocations(rideId: Long): List<RideLocationEntity>
}

/**
 * Convenience repository over [RideDao].
 *
 * Each [appendSample] call also refreshes the parent ride's running aggregates
 * (max speed, avg speed, sample count). Rides are short enough that the
 * one-extra-write-per-sample cost is negligible.
 */
class RideStore(private val dao: RideDao) {

    /** Begin a new ride and return its id. */
    suspend fun startRide(startedAtMillis: Long, startOdoKm: Int, fuelBars: Int?): Long {
        val entity = RideEntity(
            startedAtMillis = startedAtMillis,
            endedAtMillis = null,
            startOdoKm = startOdoKm,
            endOdoKm = null,
            maxSpeedKmh = 0,
            avgSpeedKmh = 0.0,
            sampleCount = 0,
            fuelBarsStart = fuelBars,
            fuelBarsEnd = null,
        )
        return dao.insertRide(entity)
    }

    /**
     * Append one sample to a ride and refresh the parent's aggregates.
     *
     * Throws [IllegalStateException] if [rideId] does not exist (e.g. deleted
     * mid-ride). Callers should treat this as a fatal logic error.
     */
    suspend fun appendSample(
        rideId: Long,
        tMillis: Long,
        speedKmh: Int,
        odometerKm: Int,
        tripA: Double,
        tripB: Double,
        fuelBars: Int?,
        fuelEconKml: Double?,
    ) {
        dao.insertSample(
            RideSampleEntity(
                rideId = rideId,
                tMillis = tMillis,
                speedKmh = speedKmh,
                odometerKm = odometerKm,
                tripAKm = tripA,
                tripBKm = tripB,
                fuelBars = fuelBars,
                fuelEconKml = fuelEconKml,
            )
        )
        val ride = dao.getRide(rideId)
            ?: error("appendSample: ride id=$rideId not found")
        val newCount = ride.sampleCount + 1
        // Running average: avg' = (avg*n + x) / (n+1)
        val newAvg = (ride.avgSpeedKmh * ride.sampleCount + speedKmh) / newCount
        val newMax = if (speedKmh > ride.maxSpeedKmh) speedKmh else ride.maxSpeedKmh
        dao.updateRide(
            ride.copy(
                maxSpeedKmh = newMax,
                avgSpeedKmh = newAvg,
                sampleCount = newCount,
            )
        )
    }

    /** Mark a ride as ended. No-op if the ride has been deleted. */
    suspend fun endRide(
        rideId: Long,
        endedAtMillis: Long,
        endOdoKm: Int,
        fuelBarsEnd: Int?,
        name: String? = null,
    ) {
        val ride = dao.getRide(rideId) ?: return
        dao.updateRide(
            ride.copy(
                endedAtMillis = endedAtMillis,
                endOdoKm = endOdoKm,
                fuelBarsEnd = fuelBarsEnd,
                // Only overwrite the existing name if the caller passed one.
                // Lets manual renames survive a later re-end (defensive — the
                // RideLogger never re-ends a closed ride today).
                name = name ?: ride.name,
            )
        )
    }

    /** Update a ride's human-readable name. No-op if the ride is gone. */
    suspend fun renameRide(rideId: Long, name: String?) = dao.renameRide(rideId, name)

    /** Delete a ride and its samples (cascade). */
    suspend fun deleteRide(id: Long) = dao.deleteRide(id)

    /**
     * Delete every ride and its samples / locations (cascade).
     *
     * Used by the "Reset all data" action in [dev.mrwick.gixxerbridge.ui.about.AboutScreen].
     * Does NOT touch the manual fuel-fill log — that lives in a separate table
     * and is wiped by [dev.mrwick.gixxerbridge.data.FuelFillDao] separately.
     */
    suspend fun deleteAllRides() = dao.deleteAllRides()

    /** Return the most-recent in-progress ride, or null. */
    suspend fun rideInProgress(): RideEntity? = dao.getRideInProgress()

    /** Observe all rides, newest-first. */
    fun observeRides(): Flow<List<RideEntity>> = dao.observeRides()

    /** Fetch all samples for a ride, oldest-first. */
    suspend fun getSamples(rideId: Long): List<RideSampleEntity> = dao.getSamples(rideId)

    /**
     * Fetch up to [limit] evenly-distributed samples for the sparkline
     * shown in the Trips list row. Keeps memory low — a 60-point polyline
     * is enough resolution to see the ride shape.
     */
    suspend fun getSamplesLimited(rideId: Long, limit: Int = 60): List<RideSampleEntity> =
        dao.getSamplesLimited(rideId, limit)

    /**
     * Append one GPS location to a ride. Independent of [appendSample]; no
     * aggregate refresh because GPS sampling cadence is irrelevant to the
     * speed/odometer aggregates already maintained by [appendSample].
     */
    suspend fun appendLocation(
        rideId: Long,
        tMillis: Long,
        lat: Double,
        lng: Double,
        altitudeM: Double?,
        accuracyM: Float?,
    ) {
        dao.insertLocation(
            RideLocationEntity(
                rideId = rideId,
                tMillis = tMillis,
                lat = lat,
                lng = lng,
                altitudeM = altitudeM,
                accuracyM = accuracyM,
            )
        )
    }

    /** Fetch all GPS locations for a ride, oldest-first. */
    suspend fun getLocations(rideId: Long): List<RideLocationEntity> = dao.getLocations(rideId)
}
