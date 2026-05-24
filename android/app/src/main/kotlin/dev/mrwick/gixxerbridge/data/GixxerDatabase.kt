package dev.mrwick.gixxerbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Singleton Room database for ride history.
 *
 * Schema version is 3 (v2 adds [RideLocationEntity] for GPS tracks; v3 adds
 * [FuelFillEntity] for the manual fuel-fill log). Destructive migration is
 * enabled — this is acceptable pre-1.0 because the only persisted user data
 * here is ride history + fuel log (re-capturable / re-enterable). Settings /
 * profile live in DataStore, not in Room.
 *
 * ASSUMED: destroying the user's fuel-fill log on a schema bump is acceptable
 * pre-1.0 since the feature is brand-new and the rider can re-enter from
 * receipts. Revisit before any external release.
 */
@Database(
    entities = [
        RideEntity::class,
        RideSampleEntity::class,
        RideLocationEntity::class,
        FuelFillEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class GixxerDatabase : RoomDatabase() {
    /** DAO for ride + sample CRUD. */
    abstract fun rideDao(): RideDao

    /** DAO for manual fuel-fill log. */
    abstract fun fuelFillDao(): FuelFillDao

    companion object {
        // ASSUMED: a single process-wide DB instance is sufficient. The app has
        // no multi-process services, so the standard double-checked singleton
        // pattern is safe.
        @Volatile private var INSTANCE: GixxerDatabase? = null

        /** Return the process-wide [GixxerDatabase], constructing it on first call. */
        fun get(context: Context): GixxerDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                GixxerDatabase::class.java,
                "gixxer.db",
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
