package dev.mrwick.gixxerbridge.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One manually-logged service event (oil change, brake pad swap, general
 * service, custom). Independent of [RideEntity] / [FuelFillEntity] — service
 * events happen on the bike's maintenance schedule, not on a per-ride basis.
 *
 * [type] is a free-form string so the user can record custom services beyond
 * the canonical Oil change / Brake pads / General service options.
 * [rupees] and [notes] are optional.
 */
@Entity(tableName = "service_logs")
data class ServiceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tMillis: Long,
    val odometerKm: Int,
    val type: String,
    val rupees: Double?,
    val notes: String?,
)

/** Room DAO over [service_logs]. */
@Dao
interface ServiceLogDao {
    /** Insert a service entry; returns the new auto-generated id. */
    @Insert suspend fun insert(e: ServiceLogEntity): Long

    /** Observe all service entries, newest-first. */
    @Query("SELECT * FROM service_logs ORDER BY tMillis DESC")
    fun observe(): Flow<List<ServiceLogEntity>>

    /** Snapshot read of all service entries, newest-first. */
    @Query("SELECT * FROM service_logs ORDER BY tMillis DESC")
    suspend fun all(): List<ServiceLogEntity>

    /** Delete a service entry by id. No-op if already gone. */
    @Query("DELETE FROM service_logs WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * Thin repository over [ServiceLogDao]. Kept separate from other stores
 * because the service log is its own domain (maintenance history) with no
 * cross-table joins required.
 */
class ServiceLogStore(private val dao: ServiceLogDao) {
    /**
     * Insert one service entry with `tMillis = now`. Returns the new id.
     *
     * The caller is also expected to bump [Settings.setLastServiceOdoKm] so
     * the home-screen "service due" banner clears — that's a UI concern,
     * not a data-layer one, so it isn't done here.
     */
    suspend fun add(odo: Int, type: String, rupees: Double?, notes: String?): Long =
        dao.insert(
            ServiceLogEntity(
                tMillis = System.currentTimeMillis(),
                odometerKm = odo,
                type = type,
                rupees = rupees,
                notes = notes,
            )
        )

    /** Delete a service entry by id. */
    suspend fun delete(id: Long) = dao.delete(id)

    /** Observe all service entries, newest-first. */
    fun observe(): Flow<List<ServiceLogEntity>> = dao.observe()

    /** Snapshot read of all service entries, newest-first. */
    suspend fun all(): List<ServiceLogEntity> = dao.all()
}
