package dev.mrwick.gixxerbridge.data

import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One manually-recorded fuel fill, used to compute true km/L over fill-to-fill
 * intervals. Independent of [RideEntity] because fills happen on the rider's
 * schedule (at the gas station) and reference the bike odometer at the moment
 * of the fill, not any particular ride.
 *
 * [rupees] and [note] are optional — the user can log a bare-bones fill with
 * just odometer + litres when standing at the pump in a hurry.
 */
@Entity(tableName = "fuel_fills")
@Immutable
data class FuelFillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tMillis: Long,
    val odometerKm: Int,
    val litres: Double,
    val rupees: Double?,
    val note: String?,
)

/** Room DAO over [fuel_fills]. */
@Dao
interface FuelFillDao {
    /** Insert a fill; returns the new auto-generated id. */
    @Insert suspend fun insert(f: FuelFillEntity): Long

    /** Observe all fills, newest-first. */
    @Query("SELECT * FROM fuel_fills ORDER BY tMillis DESC")
    fun observe(): Flow<List<FuelFillEntity>>

    /** Delete a fill by id. No-op if already gone. */
    @Query("DELETE FROM fuel_fills WHERE id = :id")
    suspend fun delete(id: Long)

    /** Snapshot (non-flow) read for one-off computations. */
    @Query("SELECT * FROM fuel_fills ORDER BY tMillis DESC")
    suspend fun all(): List<FuelFillEntity>
}

/**
 * Thin repository over [FuelFillDao]. Kept separate from [RideStore] because
 * fuel fills are not tied to ride lifecycle; mixing them in [RideStore] would
 * conflate two unrelated domains (real-time ride aggregation vs. manual log).
 */
class FuelStore(private val dao: FuelFillDao) {
    /** Insert a fill; returns the new auto-generated id. */
    suspend fun add(
        tMillis: Long,
        odometerKm: Int,
        litres: Double,
        rupees: Double?,
        note: String?,
    ): Long = dao.insert(
        FuelFillEntity(
            tMillis = tMillis,
            odometerKm = odometerKm,
            litres = litres,
            rupees = rupees,
            note = note,
        )
    )

    /** Observe all fills, newest-first. */
    fun observe(): Flow<List<FuelFillEntity>> = dao.observe()

    /** Delete a fill by id. */
    suspend fun delete(id: Long) = dao.delete(id)

    /** Snapshot read of all fills, newest-first. */
    suspend fun all(): List<FuelFillEntity> = dao.all()
}
