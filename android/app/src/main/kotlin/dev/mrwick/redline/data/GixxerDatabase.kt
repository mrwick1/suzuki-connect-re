package dev.mrwick.redline.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Singleton Room database for ride history.
 *
 * Schema version is 5 (v2 adds [RideLocationEntity] for GPS tracks; v3 adds
 * [FuelFillEntity] for the manual fuel-fill log; v4 adds [RideEntity.name]
 * for auto-generated trip titles + [ServiceLogEntity] for the maintenance
 * history log; v5 adds RideEntity.parentRideId + isMerged for trip merging).
 * Destructive migration is enabled — this is acceptable pre-1.0
 * because the only persisted user data here is ride history + fuel log +
 * service log (all re-capturable / re-enterable). Settings / profile live in
 * DataStore, not in Room.
 *
 * ASSUMED: destroying the user's fuel-fill + service log on a schema bump is
 * acceptable pre-1.0 since both features are brand-new and the rider can
 * re-enter from receipts. Revisit before any external release.
 */
@Database(
    entities = [
        RideEntity::class,
        RideSampleEntity::class,
        RideLocationEntity::class,
        FuelFillEntity::class,
        ServiceLogEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class GixxerDatabase : RoomDatabase() {
    /** DAO for ride + sample CRUD. */
    abstract fun rideDao(): RideDao

    /** DAO for manual fuel-fill log. */
    abstract fun fuelFillDao(): FuelFillDao

    /** DAO for the service history log. */
    abstract fun serviceLogDao(): ServiceLogDao

    companion object {
        // ASSUMED: a single process-wide DB instance is sufficient. The app has
        // no multi-process services, so the standard double-checked singleton
        // pattern is safe.
        @Volatile private var INSTANCE: GixxerDatabase? = null

        /**
         * v4→v5: add merge columns. ALTER ... ADD COLUMN is non-destructive, so
         * existing ride history survives (unlike the destructive fallback).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN parentRideId INTEGER")
                db.execSQL("ALTER TABLE rides ADD COLUMN isMerged INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Return the process-wide [GixxerDatabase], constructing it on first call. */
        fun get(context: Context): GixxerDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                GixxerDatabase::class.java,
                "gixxer.db",
            )
                .addMigrations(MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
