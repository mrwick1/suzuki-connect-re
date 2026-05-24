package dev.mrwick.gixxerbridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    /** When true, the phone enables Do-Not-Disturb on bike connect. Off by default. */
    val autoDndOnConnect: Flow<Boolean> =
        ds.data.map { it[Keys.AUTO_DND_ON_CONNECT] ?: false }

    /** Service interval in km between scheduled services; defaults to 5000. */
    val serviceIntervalKm: Flow<Int> =
        ds.data.map { it[Keys.SERVICE_INTERVAL_KM] ?: DEFAULT_SERVICE_INTERVAL_KM }

    /** Odometer reading (km) at last service; defaults to 0. */
    val lastServiceOdoKm: Flow<Int> =
        ds.data.map { it[Keys.LAST_SERVICE_ODO_KM] ?: 0 }

    /** Set of package names whose notifications are mirrored to the bike. */
    val mirrorAllowlist: Flow<Set<String>> =
        ds.data.map { it[Keys.MIRROR_ALLOWLIST] ?: DEFAULT_ALLOWLIST }

    /** When true, the bike service synthesises fake a537 telemetry (no bike required). */
    val demoMode: Flow<Boolean> =
        ds.data.map { it[Keys.DEMO_MODE] ?: false }

    /** Phone number (E.164 or local digits) for the SOS contact; null if not configured. */
    val emergencyContactPhone: Flow<String?> =
        ds.data.map { decodeNullableString(it[Keys.EMERGENCY_CONTACT_PHONE]) }

    /** When true, [dev.mrwick.gixxerbridge.safety.CrashDetector] runs while the bike service is alive. Default off — opt-in only. */
    val crashDetectionEnabled: Flow<Boolean> =
        ds.data.map { it[Keys.CRASH_DETECTION_ENABLED] ?: false }

    /** When true, the first-run onboarding wizard has been completed. Default false. */
    val onboardingComplete: Flow<Boolean> =
        ds.data.map { it[Keys.ONBOARDING_COMPLETE] ?: false }

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

    /** Replace the notification-mirror allowlist. */
    suspend fun setMirrorAllowlist(packages: Set<String>) {
        ds.edit { it[Keys.MIRROR_ALLOWLIST] = packages }
    }

    /** Toggle demo-mode (synthetic telemetry stream). */
    suspend fun setDemoMode(v: Boolean) {
        ds.edit { it[Keys.DEMO_MODE] = v }
    }

    /** Set the emergency-contact phone number; pass null/empty to clear. */
    suspend fun setEmergencyContactPhone(v: String?) {
        ds.edit { it[Keys.EMERGENCY_CONTACT_PHONE] = encodeNullableString(v) }
    }

    /** Toggle crash detection (opt-in; default false). */
    suspend fun setCrashDetectionEnabled(v: Boolean) {
        ds.edit { it[Keys.CRASH_DETECTION_ENABLED] = v }
    }

    /** Mark the first-run onboarding wizard as complete (true) or reset (false). */
    suspend fun setOnboardingComplete(v: Boolean) {
        ds.edit { it[Keys.ONBOARDING_COMPLETE] = v }
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
        val AUTO_DND_ON_CONNECT = booleanPreferencesKey("auto_dnd_on_connect")
        val SERVICE_INTERVAL_KM = intPreferencesKey("service_interval_km")
        val LAST_SERVICE_ODO_KM = intPreferencesKey("last_service_odo_km")
        val MIRROR_ALLOWLIST = stringSetPreferencesKey("mirror_allowlist")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val EMERGENCY_CONTACT_PHONE = stringPreferencesKey("emergency_contact_phone")
        val CRASH_DETECTION_ENABLED = booleanPreferencesKey("crash_detection_enabled")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    companion object {
        /** Default rider display name when none has been set. */
        const val DEFAULT_RIDER_NAME: String = "Rider"

        /** Default kilometres between scheduled services. */
        const val DEFAULT_SERVICE_INTERVAL_KM: Int = 5000

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
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "au.com.shiftyjelly.pocketcasts",
            "com.discord",
            "com.slack",
        )
    }
}

// ---------- Conversion helpers (extracted so they can be unit tested) ----------
//
// DataStore preference values themselves cannot be null, so nullable public
// fields are encoded via sentinels:
//   - null String <=> "" (empty string)
//   - null Double <=> Double.NaN

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

// ASSUMED: a single DataStore file named "gixxer_settings" is sufficient for all
// app preferences. The extension property below is the canonical instance.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "gixxer_settings")
