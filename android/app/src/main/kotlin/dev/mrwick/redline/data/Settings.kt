package dev.mrwick.redline.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mrwick.redline.analytics.JourneyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent app settings backed by DataStore preferences (single file).
 *
 * One injectable [Settings] instance per app — construct from the application
 * [Context] and share. All reads are exposed as cold [Flow]s; all writes are
 * `suspend` and atomic (per-key).
 *
 * Nullable values are represented in the underlying preference store via
 * sentinels (empty string for MAC, [Double.NaN] for coordinates) and converted
 * back to `null` in the public Flows via helpers in [SettingsConversions].
 */
class Settings(context: Context) {
    private val ds: DataStore<Preferences> = context.settingsDataStore

    /** Paired bike MAC address, or null if no bike has been paired. */
    val bikeMac: Flow<String?> =
        ds.data.map { decodeNullableString(it[Keys.BIKE_MAC]) }

    /** Rider display name; defaults to "Rider". */
    val riderName: Flow<String> =
        ds.data.map { it[Keys.RIDER_NAME] ?: DEFAULT_RIDER_NAME }

    /** When true, the bike service auto-starts at boot. */
    val autoStartOnBoot: Flow<Boolean> =
        ds.data.map { it[Keys.AUTO_START_ON_BOOT] ?: false }

    /** When true, the app requires biometric/PIN unlock on launch. */
    val appLockEnabled: Flow<Boolean> =
        ds.data.map { it[Keys.APP_LOCK_ENABLED] ?: false }

    /** Fixed latitude for weather lookup; null means use phone GPS single-shot. */
    val weatherLatitude: Flow<Double?> =
        ds.data.map { decodeNullableDouble(it[Keys.WEATHER_LAT]) }

    /** Fixed longitude for weather lookup; null means use phone GPS single-shot. */
    val weatherLongitude: Flow<Double?> =
        ds.data.map { decodeNullableDouble(it[Keys.WEATHER_LNG]) }

    /** When true, the cluster shows clock + weather when nav is idle. */
    val idleClockEnabled: Flow<Boolean> =
        ds.data.map { it[Keys.IDLE_CLOCK_ENABLED] ?: true }

    /** When true, currently-playing track title is mirrored to the cluster. */
    val nowPlayingOnCluster: Flow<Boolean> =
        ds.data.map { it[Keys.NOW_PLAYING_ON_CLUSTER] ?: true }

    /** When true, estimated range-remaining joins the cluster idle rotation. Off by default. */
    val rangeOnCluster: Flow<Boolean> =
        ds.data.map { it[Keys.RANGE_ON_CLUSTER] ?: false }

    /** When true, the phone enables Do-Not-Disturb on bike connect. Off by default. */
    val autoDndOnConnect: Flow<Boolean> =
        ds.data.map { it[Keys.AUTO_DND_ON_CONNECT] ?: false }

    /**
     * Legacy single-service interval in km; defaults to 5000.
     *
     * Kept as a global override for code paths that still want a single
     * "service threshold" number (e.g. the v0 [dev.mrwick.redline.analytics.BikeHealth.compute]
     * signature). The richer per-item model lives in [serviceSchedule] / the
     * `ServiceItem` enum, mirroring the five real periodic-service items the
     * Suzuki Connect app tracks (see DISCOVERIES.md 2026-05-25).
     */
    val serviceIntervalKm: Flow<Int> =
        ds.data.map { it[Keys.SERVICE_INTERVAL_KM] ?: DEFAULT_SERVICE_INTERVAL_KM }

    /** Legacy single-service "odometer at last service"; defaults to 0. See [serviceIntervalKm] doc. */
    val lastServiceOdoKm: Flow<Int> =
        ds.data.map { it[Keys.LAST_SERVICE_ODO_KM] ?: 0 }

    /**
     * Per-item periodic-service state for all five items the Suzuki Connect
     * app tracks (engine oil / air filter / spark plug / brake oil / battery).
     *
     * Returned as a `Map<ServiceItem, ServiceItemState>` containing exactly
     * one entry per enum value — values fall back to [ServiceItem]'s defaults
     * if the rider hasn't customised them, and [ServiceItemState.lastServiceDateMs]
     * / [ServiceItemState.lastServiceOdoKm] are null when no service has been
     * recorded for that item.
     */
    val serviceSchedule: Flow<Map<ServiceItem, ServiceItemState>> =
        ds.data.map { prefs -> ServiceItem.entries.associateWith { readServiceItem(prefs, it) } }

    /** Set of package names whose notifications are mirrored to the bike. */
    val mirrorAllowlist: Flow<Set<String>> =
        ds.data.map { it[Keys.MIRROR_ALLOWLIST] ?: DEFAULT_ALLOWLIST }

    /** When true, the bike service synthesises fake a537 telemetry (no bike required). */
    val demoMode: Flow<Boolean> =
        ds.data.map { it[Keys.DEMO_MODE] ?: false }

    /** When true, [dev.mrwick.redline.nav.ManeuverClassifier] self-trains
     *  its bitmap-hash → maneuver-id table from text classifier results. Default
     *  OFF — see DISCOVERIES.md 2026-05-25 nav-arrow diagnosis: a single text
     *  miss can poison the bitmap table indefinitely. Turn on only once the
     *  bike's maneuver-id semantics have been empirically verified. */
    val maneuverSelfTrainEnabled: Flow<Boolean> =
        ds.data.map { it[Keys.MANEUVER_SELF_TRAIN] ?: false }

    /** Phone number (E.164 or local digits) for the SOS contact; null if not configured. */
    val emergencyContactPhone: Flow<String?> =
        ds.data.map { decodeNullableString(it[Keys.EMERGENCY_CONTACT_PHONE]) }

    /** When true, [dev.mrwick.redline.safety.CrashDetector] runs while the bike service is alive. Default off — opt-in only. */
    val crashDetectionEnabled: Flow<Boolean> =
        ds.data.map { it[Keys.CRASH_DETECTION_ENABLED] ?: false }

    /**
     * True while a crash-SOS countdown is armed. Persisted so the SOS still fires
     * if the process is killed during the countdown (a crash is exactly when the
     * phone may be damaged). Read by the AlarmManager-driven
     * [dev.mrwick.redline.safety.SosAlarmReceiver]; cleared when the rider
     * cancels or after the SOS sends.
     */
    val sosArmed: Flow<Boolean> =
        ds.data.map { it[Keys.SOS_ARMED] ?: false }

    /** When true, the first-run onboarding wizard has been completed. Default false. */
    val onboardingComplete: Flow<Boolean> =
        ds.data.map { it[Keys.ONBOARDING_COMPLETE] ?: false }

    /** When true, the foreground UI keeps the screen awake while the bike is connected. Default off — opt-in. */
    val keepScreenOnWhileConnected: Flow<Boolean> =
        ds.data.map { it[Keys.KEEP_SCREEN_ON] ?: false }

    /** Named accent colour for the app theme; defaults to [DEFAULT_ACCENT] ("cyan"). */
    val themeAccent: Flow<String> =
        ds.data.map { it[Keys.THEME_ACCENT] ?: Settings.DEFAULT_ACCENT }

    /**
     * Chosen metric for the Active-ride overlay lower third. Defaults to [DEFAULT_ACTIVE_RIDE_METRIC].
     * Options: "trip-a", "fuel", "eta", "road-type".
     *
     * Setter: [setActiveRideMetric] (added in Task C / Wave 2).
     */
    val activeRideMetric: Flow<String> =
        ds.data.map { it[Keys.ACTIVE_RIDE_METRIC] ?: DEFAULT_ACTIVE_RIDE_METRIC }

    /** User-set fuel-tank capacity in litres; defaults to [DEFAULT_FUEL_CAPACITY_L]. */
    val fuelCapacityL: Flow<Double> =
        ds.data.map { it[Keys.FUEL_CAPACITY_L] ?: DEFAULT_FUEL_CAPACITY_L }

    /**
     * Odometer (km) of the most-recently logged fuel fill at the time the user
     * last dismissed / snoozed the refuel-co-prompt. Null means the prompt has
     * never been snoozed. When a NEW fill is logged (its odometer differs from
     * this value) the prompt re-arms automatically — recomputed in HomeViewModel,
     * no alarm or Room polling needed.
     *
     * Sentinel: -1 (via [encodeNullableInt] / [decodeNullableInt]) = never snoozed.
     */
    val refuelPromptSnoozedAtFillOdo: Flow<Int?> =
        ds.data.map { decodeNullableInt(it[Keys.REFUEL_PROMPT_SNOOZE_ODO]) }

    /** Last persisted telemetry snapshot (odo/bars/km-L); null until first frame. */
    val lastTelemetry: Flow<LastTelemetry?> =
        ds.data.map { p ->
            val odo = p[Keys.LAST_TELEM_ODO] ?: return@map null
            LastTelemetry(
                odometerKm = odo,
                fuelBars = decodeNullableInt(p[Keys.LAST_TELEM_BARS]),
                kmPerL = decodeNullableDouble(p[Keys.LAST_TELEM_KMPL]),
                tMillis = p[Keys.LAST_TELEM_TMS] ?: 0L,
            )
        }

    /**
     * Tunable thresholds for [dev.mrwick.redline.analytics.JourneyDetector],
     * exposed as one [JourneyConfig] snapshot. Defaults mirror the detector's own
     * defaults (gap ≤ 120 min, ≥ 3 segments, ≥ 80 km). Edited from the Developer
     * settings screen; consumed by `TripsViewModel` (Task 9).
     */
    val journeyConfig: Flow<JourneyConfig> =
        ds.data.map { p ->
            JourneyConfig(
                gapMaxMin = p[Keys.JOURNEY_GAP_MAX_MIN] ?: DEFAULT_JOURNEY_GAP_MAX_MIN,
                minSegments = p[Keys.JOURNEY_MIN_SEGMENTS] ?: DEFAULT_JOURNEY_MIN_SEGMENTS,
                minTotalKm = p[Keys.JOURNEY_MIN_TOTAL_KM] ?: DEFAULT_JOURNEY_MIN_TOTAL_KM,
            )
        }

    /** Set the paired bike MAC; pass null to clear. */
    suspend fun setBikeMac(mac: String?) {
        ds.edit { it[Keys.BIKE_MAC] = encodeNullableString(mac) }
    }

    /** Set the rider display name. */
    suspend fun setRiderName(name: String) {
        ds.edit { it[Keys.RIDER_NAME] = name }
    }

    /** Toggle whether the bike service auto-starts at boot. */
    suspend fun setAutoStartOnBoot(v: Boolean) {
        ds.edit { it[Keys.AUTO_START_ON_BOOT] = v }
    }

    /** Toggle whether the app requires biometric/PIN unlock. */
    suspend fun setAppLockEnabled(v: Boolean) {
        ds.edit { it[Keys.APP_LOCK_ENABLED] = v }
    }

    /** Set the fixed weather coordinate; pass nulls to fall back to phone GPS. */
    suspend fun setWeatherLatLng(lat: Double?, lng: Double?) {
        ds.edit {
            it[Keys.WEATHER_LAT] = encodeNullableDouble(lat)
            it[Keys.WEATHER_LNG] = encodeNullableDouble(lng)
        }
    }

    /** Toggle the cluster clock+weather idle screen. */
    suspend fun setIdleClockEnabled(v: Boolean) {
        ds.edit { it[Keys.IDLE_CLOCK_ENABLED] = v }
    }

    /** Toggle mirroring of now-playing track to the cluster. */
    suspend fun setNowPlayingOnCluster(v: Boolean) {
        ds.edit { it[Keys.NOW_PLAYING_ON_CLUSTER] = v }
    }

    /** Toggle range-remaining on the cluster idle rotation. */
    suspend fun setRangeOnCluster(enabled: Boolean) {
        ds.edit { it[Keys.RANGE_ON_CLUSTER] = enabled }
    }

    /** Toggle whether the phone auto-enables DND on bike connect. */
    suspend fun setAutoDndOnConnect(v: Boolean) {
        ds.edit { it[Keys.AUTO_DND_ON_CONNECT] = v }
    }

    /** Set the service interval (km between scheduled services). */
    suspend fun setServiceIntervalKm(v: Int) {
        ds.edit { it[Keys.SERVICE_INTERVAL_KM] = v }
    }

    /** Set the odometer reading at the most recent service. */
    suspend fun setLastServiceOdoKm(v: Int) {
        ds.edit { it[Keys.LAST_SERVICE_ODO_KM] = v }
    }

    /**
     * Update the km / days thresholds for one service item. Pass null for
     * [kmThreshold] only on items whose [ServiceItem.defaultKm] is null
     * (brake oil) — passing null on a km-gated item disables its km gate
     * entirely, which is almost never what the rider means.
     */
    suspend fun setServiceItemThresholds(item: ServiceItem, kmThreshold: Int?, daysThreshold: Int) {
        ds.edit {
            it[Keys.kmThresholdKey(item)] = encodeNullableInt(kmThreshold)
            it[Keys.daysThresholdKey(item)] = daysThreshold.coerceAtLeast(1)
        }
    }

    /**
     * Record a service event for one item: stamps "now" as the last-service
     * date and pins the current odometer as the new baseline. Pass null for
     * [odoKm] when the current odo isn't known (rider hasn't connected the
     * bike yet) — the days gate will still tick, the km gate will dim.
     */
    suspend fun markServiceDone(item: ServiceItem, odoKm: Int?, atMillis: Long = System.currentTimeMillis()) {
        ds.edit {
            it[Keys.lastServiceDateKey(item)] = atMillis
            it[Keys.lastServiceOdoKey(item)] = encodeNullableInt(odoKm)
        }
    }

    /** Wipe one item's recorded last-service so the gauge resets to "no baseline". */
    suspend fun clearServiceItemBaseline(item: ServiceItem) {
        ds.edit {
            it.remove(Keys.lastServiceDateKey(item))
            it.remove(Keys.lastServiceOdoKey(item))
        }
    }

    /** Replace the notification-mirror allowlist. */
    suspend fun setMirrorAllowlist(packages: Set<String>) {
        ds.edit { it[Keys.MIRROR_ALLOWLIST] = packages }
    }

    /** Toggle demo-mode (synthetic telemetry stream). */
    suspend fun setDemoMode(v: Boolean) {
        ds.edit { it[Keys.DEMO_MODE] = v }
    }

    suspend fun setManeuverSelfTrainEnabled(v: Boolean) {
        ds.edit { it[Keys.MANEUVER_SELF_TRAIN] = v }
    }

    /** Set the emergency-contact phone number; pass null/empty to clear. */
    suspend fun setEmergencyContactPhone(v: String?) {
        ds.edit { it[Keys.EMERGENCY_CONTACT_PHONE] = encodeNullableString(v) }
    }

    /** Toggle crash detection (opt-in; default false). */
    suspend fun setCrashDetectionEnabled(v: Boolean) {
        ds.edit { it[Keys.CRASH_DETECTION_ENABLED] = v }
    }

    /** Arm/disarm the persisted crash-SOS countdown (see [sosArmed]). */
    suspend fun setSosArmed(v: Boolean) {
        ds.edit { it[Keys.SOS_ARMED] = v }
    }

    /** Mark the first-run onboarding wizard as complete (true) or reset (false). */
    suspend fun setOnboardingComplete(v: Boolean) {
        ds.edit { it[Keys.ONBOARDING_COMPLETE] = v }
    }

    /** Toggle whether the foreground UI keeps the screen awake while connected. */
    suspend fun setKeepScreenOnWhileConnected(v: Boolean) {
        ds.edit { it[Keys.KEEP_SCREEN_ON] = v }
    }

    /** Persist the named theme accent (must be a key in `ACCENT_PALETTE`). */
    suspend fun setThemeAccent(name: String) {
        ds.edit { it[Keys.THEME_ACCENT] = name }
    }

    /**
     * Persist the active-ride bottom-metric choice.
     * Valid values: "trip-a", "fuel", "eta", "road-type".
     */
    suspend fun setActiveRideMetric(name: String) {
        ds.edit { it[Keys.ACTIVE_RIDE_METRIC] = name }
    }

    /** Set the fuel-tank capacity in litres. */
    suspend fun setFuelCapacityL(litres: Double) {
        ds.edit { it[Keys.FUEL_CAPACITY_L] = litres }
    }

    /** Persist the latest telemetry snapshot for the offline fuel estimate. */
    suspend fun setLastTelemetry(
        odometerKm: Int,
        fuelBars: Int?,
        kmPerL: Double?,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        ds.edit {
            it[Keys.LAST_TELEM_ODO] = odometerKm
            it[Keys.LAST_TELEM_BARS] = encodeNullableInt(fuelBars)
            it[Keys.LAST_TELEM_KMPL] = encodeNullableDouble(kmPerL)
            it[Keys.LAST_TELEM_TMS] = atMillis
        }
    }

    /**
     * Record the snooze: stores [fillOdometerKm] as the odometer at which the
     * rider dismissed the co-prompt. Pass null to clear (re-arm unconditionally).
     *
     * Call this from [dev.mrwick.redline.ui.home.HomeViewModel] when the
     * rider taps the dismiss affordance on the FuelTile / HealthTile co-prompt.
     */
    suspend fun setRefuelPromptSnoozedAtFillOdo(fillOdometerKm: Int?) {
        ds.edit { it[Keys.REFUEL_PROMPT_SNOOZE_ODO] = encodeNullableInt(fillOdometerKm) }
    }

    /** Max inter-segment gap (minutes) the journey detector will bridge. See [journeyConfig]. */
    suspend fun setJourneyGapMaxMin(v: Int) {
        ds.edit { it[Keys.JOURNEY_GAP_MAX_MIN] = v }
    }

    /** Minimum segment count for a run to qualify as a suggested journey. See [journeyConfig]. */
    suspend fun setJourneyMinSegments(v: Int) {
        ds.edit { it[Keys.JOURNEY_MIN_SEGMENTS] = v }
    }

    /** Minimum total distance (km) for a run to qualify as a suggested journey. See [journeyConfig]. */
    suspend fun setJourneyMinTotalKm(v: Int) {
        ds.edit { it[Keys.JOURNEY_MIN_TOTAL_KM] = v }
    }

    /** Internal preference keys. */
    private object Keys {
        val BIKE_MAC = stringPreferencesKey("bike_mac")
        val RIDER_NAME = stringPreferencesKey("rider_name")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val WEATHER_LAT = doublePreferencesKey("weather_lat")
        val WEATHER_LNG = doublePreferencesKey("weather_lng")
        val IDLE_CLOCK_ENABLED = booleanPreferencesKey("idle_clock_enabled")
        val NOW_PLAYING_ON_CLUSTER = booleanPreferencesKey("now_playing_on_cluster")
        val RANGE_ON_CLUSTER = booleanPreferencesKey("range_on_cluster")
        val AUTO_DND_ON_CONNECT = booleanPreferencesKey("auto_dnd_on_connect")
        val SERVICE_INTERVAL_KM = intPreferencesKey("service_interval_km")
        val LAST_SERVICE_ODO_KM = intPreferencesKey("last_service_odo_km")
        val MIRROR_ALLOWLIST = stringSetPreferencesKey("mirror_allowlist")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val MANEUVER_SELF_TRAIN = booleanPreferencesKey("maneuver_self_train")
        val EMERGENCY_CONTACT_PHONE = stringPreferencesKey("emergency_contact_phone")
        val CRASH_DETECTION_ENABLED = booleanPreferencesKey("crash_detection_enabled")
        val SOS_ARMED = booleanPreferencesKey("sos_armed")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_while_connected")
        val THEME_ACCENT = stringPreferencesKey("theme_accent")
        val ACTIVE_RIDE_METRIC = stringPreferencesKey("active_ride_metric")
        val FUEL_CAPACITY_L = doublePreferencesKey("fuel_capacity_l")
        val LAST_TELEM_ODO = intPreferencesKey("last_telem_odo_km")
        val LAST_TELEM_BARS = intPreferencesKey("last_telem_fuel_bars")
        val LAST_TELEM_KMPL = doublePreferencesKey("last_telem_kmpl")
        val LAST_TELEM_TMS = longPreferencesKey("last_telem_tmillis")
        /** Odometer of the latest fill at snooze time; -1 sentinel = never snoozed. */
        val REFUEL_PROMPT_SNOOZE_ODO = intPreferencesKey("refuel_prompt_snooze_odo")

        // Journey-detector thresholds (see Settings.journeyConfig).
        val JOURNEY_GAP_MAX_MIN = intPreferencesKey("journey_gap_max_min")
        val JOURNEY_MIN_SEGMENTS = intPreferencesKey("journey_min_segments")
        val JOURNEY_MIN_TOTAL_KM = intPreferencesKey("journey_min_total_km")

        // Per-item service schedule (4 keys × 5 items). Key prefix uses the
        // ServiceItem.id string so rename-the-enum doesn't silently drop data
        // (it'll fall back to defaults loudly instead).
        fun kmThresholdKey(item: ServiceItem) = intPreferencesKey("svc_${item.id}_km_threshold")
        fun daysThresholdKey(item: ServiceItem) = intPreferencesKey("svc_${item.id}_days_threshold")
        fun lastServiceDateKey(item: ServiceItem) = longPreferencesKey("svc_${item.id}_last_date_ms")
        fun lastServiceOdoKey(item: ServiceItem) = intPreferencesKey("svc_${item.id}_last_odo_km")
    }

    /** Decode one [ServiceItem]'s persisted thresholds + baseline; missing keys fall back to the item's defaults. */
    private fun readServiceItem(prefs: Preferences, item: ServiceItem): ServiceItemState {
        val km = prefs[Keys.kmThresholdKey(item)]
        val days = prefs[Keys.daysThresholdKey(item)]
        val lastDate = prefs[Keys.lastServiceDateKey(item)]
        val lastOdo = prefs[Keys.lastServiceOdoKey(item)]
        return ServiceItemState(
            item = item,
            kmThreshold = if (km == null) item.defaultKm else decodeNullableInt(km),
            daysThreshold = days ?: item.defaultDays,
            lastServiceDateMs = if (lastDate == null || lastDate <= 0L) null else lastDate,
            lastServiceOdoKm = decodeNullableInt(lastOdo),
        )
    }

    companion object {
        /** Default rider display name when none has been set. */
        const val DEFAULT_RIDER_NAME: String = "Rider"

        /** Default kilometres between scheduled services. */
        const val DEFAULT_SERVICE_INTERVAL_KM: Int = 5000

        /** Default theme accent name; matches the canonical cyan brand colour. */
        const val DEFAULT_ACCENT: String = "cyan"

        /** Default active-ride bottom-metric; shows Trip A distance. */
        const val DEFAULT_ACTIVE_RIDE_METRIC: String = "trip-a"

        /** Default fuel-tank capacity (litres) - Gixxer SF 150, user-editable. */
        const val DEFAULT_FUEL_CAPACITY_L: Double = 12.0

        /** Default journey-detector thresholds; mirror [JourneyConfig]'s own defaults. */
        const val DEFAULT_JOURNEY_GAP_MAX_MIN: Int = 120
        const val DEFAULT_JOURNEY_MIN_SEGMENTS: Int = 3
        const val DEFAULT_JOURNEY_MIN_TOTAL_KM: Int = 80

        /**
         * Default package allowlist for notification mirroring.
         *
         * Conservative starter set: calls, SMS/RCS, common chat and audio apps.
         * Users can add/remove via the settings UI.
         */
        val DEFAULT_ALLOWLIST: Set<String> = setOf(
            "com.google.android.dialer",          // calls
            "com.google.android.apps.messaging",  // SMS/RCS
            "com.whatsapp",
            "org.telegram.messenger",
            "com.microsoft.teams",                // work chat
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "au.com.shiftyjelly.pocketcasts",
            "com.discord",
            "com.slack",
        )
    }
}

/**
 * Last telemetry frame persisted so the Home fuel estimate works while the bike
 * is disconnected. Written (throttled to odometer changes) by BikeBridgeService.
 */
data class LastTelemetry(
    val odometerKm: Int,
    val fuelBars: Int?,
    val kmPerL: Double?,
    val tMillis: Long,
)

// ---------- Conversion helpers (extracted so they can be unit tested) ----------
//
// DataStore preference values themselves cannot be null, so nullable public
// fields are encoded via sentinels:
//   - null String <=> "" (empty string)
//   - null Double <=> Double.NaN
//   - null non-negative Int <=> -1

/** Encode a nullable [String] to its preference-store representation ("" == null). */
internal fun encodeNullableString(s: String?): String = s ?: ""

/** Decode a preference-store [String] back to nullable ("" => null). */
internal fun decodeNullableString(s: String?): String? =
    if (s.isNullOrEmpty()) null else s

/** Encode a nullable [Double] to its preference-store representation (NaN == null). */
internal fun encodeNullableDouble(d: Double?): Double = d ?: Double.NaN

/** Decode a preference-store [Double] back to nullable (NaN or missing => null). */
internal fun decodeNullableDouble(d: Double?): Double? =
    if (d == null || d.isNaN()) null else d

/** Encode a nullable non-negative [Int] to its preference-store representation (`-1` == null). */
internal fun encodeNullableInt(i: Int?): Int = i ?: -1

/** Decode a preference-store [Int] back to nullable (negative or missing => null). */
internal fun decodeNullableInt(i: Int?): Int? =
    if (i == null || i < 0) null else i

// ASSUMED: a single DataStore file named "gixxer_settings" is sufficient for all
// app preferences. The extension property below is the canonical instance.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "gixxer_settings")
