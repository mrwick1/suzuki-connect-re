package dev.mrwick.gixxerbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Singleton Room database for ride history.
 *
 * Schema version is 1; destructive migration is enabled — this is acceptable
 * pre-1.0 because the only persisted user data here is ride history (which can
 * be re-captured). Settings/profile live in DataStore, not in Room.
 */
@Database(
    entities = [RideEntity::class, RideSampleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class GixxerDatabase : RoomDatabase() {
    /** DAO for ride + sample CRUD. */
    abstract fun rideDao(): RideDao

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
