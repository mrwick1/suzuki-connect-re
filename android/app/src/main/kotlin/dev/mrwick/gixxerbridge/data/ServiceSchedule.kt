package dev.mrwick.gixxerbridge.data

import androidx.compose.runtime.Immutable

/**
 * The five periodic-service items the official Suzuki Connect app tracks for
 * the Gixxer SF 150. Verified in `decompiled/jadx-out/sources/com/suzuki/
 * activity/PeriodicVehicleServiceActivity.java:158-180` and recorded in
 * DISCOVERIES.md 2026-05-25 "Oil change / periodic-service detection".
 *
 * The bike has no oil-life sensor — service detection is purely app-side
 * arithmetic against hardcoded calendar-day + odometer-km thresholds. This
 * enum mirrors that table so REDLINE keeps feature parity with the
 * official app's reminder model.
 *
 * `id` is the stable string used as a DataStore key prefix; never rename
 * without a migration. `defaultDays` and `defaultKm` come straight from the
 * decompiled source (3500-4000 ranged items use the lower bound — earlier is
 * conservative). A `null` `defaultKm` means the item is days-only (brake oil).
 */
enum class ServiceItem(
    val id: String,
    val label: String,
    val defaultKm: Int?,
    val defaultDays: Int,
) {
    PERIODIC_SERVICE(
        id = "periodic_service",
        label = "Periodic service (engine oil)",
        defaultKm = 3500,
        defaultDays = 120,
    ),
    AIR_FILTER(
        id = "air_filter",
        label = "Air filter replacement",
        defaultKm = 12000,
        defaultDays = 365,
    ),
    SPARK_PLUG(
        id = "spark_plug",
        label = "Spark plug change",
        defaultKm = 8000,
        defaultDays = 240,
    ),
    BRAKE_OIL(
        id = "brake_oil",
        label = "Brake oil change",
        defaultKm = null,
        defaultDays = 730,
    ),
    BATTERY_CHECKUP(
        id = "battery_checkup",
        label = "Battery checkup",
        defaultKm = 3500,
        defaultDays = 120,
    ),
}

/**
 * One service item's currently-persisted thresholds and last-service marker.
 *
 * [kmThreshold] is null when the item has no km gate at all (brake oil) — the
 * UI should hide km inputs in that case. [lastServiceDateMs] and
 * [lastServiceOdoKm] are null when the rider hasn't recorded a service yet:
 * the gauge shows the item as "no baseline" rather than "0 km used" so a
 * pristine install doesn't pretend the bike is overdue.
 */
@Immutable
data class ServiceItemState(
    val item: ServiceItem,
    val kmThreshold: Int?,
    val daysThreshold: Int,
    val lastServiceDateMs: Long?,
    val lastServiceOdoKm: Int?,
)
