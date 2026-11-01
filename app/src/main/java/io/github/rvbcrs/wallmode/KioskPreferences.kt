package io.github.rvbcrs.wallmode

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class BrowserEngine {
    WEBVIEW,
    CHROMIUM_CUSTOM_TAB;

    companion object {
        fun fromStoredValue(value: String?): BrowserEngine {
            return entries.firstOrNull { it.name == value } ?: WEBVIEW
        }
    }
}

enum class ThemeMode(val appCompatMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromStoredValue(value: String?): ThemeMode {
            return entries.firstOrNull { it.name == value } ?: SYSTEM
        }
    }
}

enum class AdminButtonCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    companion object {
        fun fromStoredValue(value: String?): AdminButtonCorner {
            return entries.firstOrNull { it.name == value } ?: TOP_RIGHT
        }
    }
}

enum class AmbientScene {
    AURORA_WEATHER,
    GLOW_CLOCK;

    companion object {
        fun fromStoredValue(value: String?): AmbientScene {
            return entries.firstOrNull { it.name == value } ?: AURORA_WEATHER
        }
    }
}

enum class AmbientBackgroundMode(val requiresUrl: Boolean) {
    BUILT_IN(false),
    BUNDLED_IMAGE(false),
    BUNDLED_VIDEO(false),
    IMAGE_URL(true),
    VIDEO_URL(true),
    IMMICH_ALBUM(false);

    companion object {
        fun fromStoredValue(value: String?): AmbientBackgroundMode {
            return entries.firstOrNull { it.name == value } ?: BUILT_IN
        }
    }
}

enum class DashboardProfile {
    HOME,
    WALL,
    NIGHT
}

internal object DashboardProfileSchedule {
    fun hasValidStartHours(homeStartHour: Int, wallStartHour: Int, nightStartHour: Int): Boolean {
        val hours = listOf(homeStartHour, wallStartHour, nightStartHour)
        return hours.all { it in KioskPreferences.MIN_HOUR..KioskPreferences.MAX_HOUR } &&
            hours.distinct().size == hours.size
    }

    fun activeAtHour(
        hour: Int,
        homeStartHour: Int,
        wallStartHour: Int,
        nightStartHour: Int
    ): DashboardProfile {
        require(hour in 0..23)
        val slots = listOf(
            homeStartHour to DashboardProfile.HOME,
            wallStartHour to DashboardProfile.WALL,
            nightStartHour to DashboardProfile.NIGHT
        )
        return slots.filter { (startHour, _) -> startHour <= hour }
            .maxByOrNull { (startHour, _) -> startHour }
            ?.second
            ?: slots.maxBy { (startHour, _) -> startHour }.second
    }

    fun activeAtHour(hour: Int, settings: KioskSettings): DashboardProfile {
        return activeAtHour(
            hour,
            settings.homeStartHour,
            settings.wallStartHour,
            settings.nightStartHour
        )
    }

    fun path(profile: DashboardProfile, settings: KioskSettings): String {
        return path(
            profile,
            settings.dashboardPath,
            settings.profileHomePath,
            settings.profileWallPath,
            settings.profileNightPath
        )
    }

    fun path(
        profile: DashboardProfile,
        dashboardPath: String,
        homePath: String,
        wallPath: String,
        nightPath: String
    ): String {
        return when (profile) {
            DashboardProfile.HOME -> homePath
            DashboardProfile.WALL -> wallPath
            DashboardProfile.NIGHT -> nightPath
        }.ifBlank { dashboardPath }
    }
}

data class KioskSettings(
    val browserEngine: BrowserEngine,
    val homeAssistantUrl: String,
    val dashboardPath: String,
    val appendKiosk: Boolean,
    val reloadIntervalSeconds: Int,
    val keepScreenOn: Boolean,
    val autoStartOnBoot: Boolean,
    val fullscreen: Boolean,
    val themeMode: ThemeMode,
    val lockTaskMode: Boolean,
    val autoReloadOnFailure: Boolean,
    val watchdogEnabled: Boolean,
    val watchdogPingPath: String,
    val watchdogPingIntervalSeconds: Int,
    val updatesEnabled: Boolean,
    val updatesRepo: String,
    val updateCheckIntervalHours: Int,
    val allowMixedContent: Boolean,
    val allowThirdPartyCookies: Boolean,
    val autoplayEnabled: Boolean,
    val desktopMode: Boolean,
    val customUserAgent: String,
    val requirePasswordForExitOnly: Boolean,
    val adminUnlockTimeoutMinutes: Int,
    val adminButtonCorner: AdminButtonCorner,
    val scheduleProfilesEnabled: Boolean,
    val homeStartHour: Int,
    val wallStartHour: Int,
    val nightStartHour: Int,
    val profileHomePath: String,
    val profileWallPath: String,
    val profileNightPath: String,
    val maintenanceEnabled: Boolean,
    val maintenanceHour: Int,
    val maintenanceMinute: Int,
    val ambientModeEnabled: Boolean,
    val ambientScreensaverEnabled: Boolean,
    val ambientScene: AmbientScene,
    val ambientBackgroundMode: AmbientBackgroundMode,
    val ambientBackgroundUrl: String,
    val ambientImmichShareUrl: String,
    val ambientPhotoIntervalSeconds: Int,
    val ambientWeatherLocation: String,
    val ambientDimAfterSeconds: Int,
    val ambientBrightnessPercent: Int,
    val ambientFollowSystemBrightness: Boolean,
    val presenceWakeEnabled: Boolean,
    val presenceWakeCooldownSeconds: Int,
    val respectManualScreenOff: Boolean,
    val deepSleepEnabled: Boolean,
    val illuminanceReportingEnabled: Boolean,
    val illuminanceMinDeltaLux: Int,
    val illuminanceDebounceSeconds: Int,
    val autoDiscoverHomeAssistant: Boolean,
    val mqttEnabled: Boolean,
    val mqttBrokerHost: String,
    val mqttBrokerPort: Int,
    val mqttUseTls: Boolean,
    val mqttUsername: String,
    val localControlEnabled: Boolean,
    val localControlPort: Int,
    val localControlPreviewEnabled: Boolean
)

data class DiagnosticsSnapshot(
    val lastEngine: String,
    val lastUrl: String,
    val lastLoadStatus: String,
    val lastCrashReason: String,
    val lastCrashAtMillis: Long,
    val lastNetworkState: String,
    val lastWatchdogState: String,
    val lastUpdateState: String,
    val lastMqttState: String
)

internal data class AdminUnlockSession(
    val credentialIdentity: String,
    val unlockedAtElapsedRealtime: Long
) {
    fun isValid(currentCredentialIdentity: String, now: Long, timeoutMinutes: Int): Boolean {
        val age = now - unlockedAtElapsedRealtime
        return credentialIdentity.isNotBlank() && credentialIdentity == currentCredentialIdentity &&
            unlockedAtElapsedRealtime >= 0 && age >= 0 && age < timeoutMinutes * 60_000L
    }
}

class KioskPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun shouldShowStartupCameraPrompt(): Boolean {
        return !prefs.getBoolean(KEY_STARTUP_CAMERA_PROMPT_SHOWN, false)
    }

    fun markStartupCameraPromptShown() {
        prefs.edit().putBoolean(KEY_STARTUP_CAMERA_PROMPT_SHOWN, true).apply()
    }

    fun setPresenceWakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRESENCE_WAKE_ENABLED, enabled).apply()
    }

    fun load(): KioskSettings {
        return KioskSettings(
            browserEngine = BrowserEngine.fromStoredValue(
                prefs.getString(KEY_BROWSER_ENGINE, DEFAULT_BROWSER_ENGINE.name)
            ),
            homeAssistantUrl = prefs.getString(KEY_HOME_ASSISTANT_URL, DEFAULT_HOME_ASSISTANT_URL)
                ?: DEFAULT_HOME_ASSISTANT_URL,
            dashboardPath = prefs.getString(KEY_DASHBOARD_PATH, DEFAULT_DASHBOARD_PATH)
                ?: DEFAULT_DASHBOARD_PATH,
            appendKiosk = prefs.getBoolean(KEY_APPEND_KIOSK, DEFAULT_APPEND_KIOSK),
            reloadIntervalSeconds = clampInt(
                prefs.getInt(KEY_RELOAD_INTERVAL_SECONDS, DEFAULT_RELOAD_INTERVAL_SECONDS),
                MIN_RELOAD_INTERVAL_SECONDS,
                MAX_RELOAD_INTERVAL_SECONDS
            ),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTOSTART_ON_BOOT, DEFAULT_AUTOSTART_ON_BOOT),
            fullscreen = prefs.getBoolean(KEY_FULLSCREEN, DEFAULT_FULLSCREEN),
            themeMode = ThemeMode.fromStoredValue(
                prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE.name)
            ),
            lockTaskMode = prefs.getBoolean(KEY_LOCK_TASK_MODE, DEFAULT_LOCK_TASK_MODE),
            autoReloadOnFailure = prefs.getBoolean(KEY_AUTO_RELOAD_ON_FAILURE, DEFAULT_AUTO_RELOAD_ON_FAILURE),
            watchdogEnabled = prefs.getBoolean(KEY_WATCHDOG_ENABLED, DEFAULT_WATCHDOG_ENABLED),
            watchdogPingPath = prefs.getString(KEY_WATCHDOG_PING_PATH, DEFAULT_WATCHDOG_PING_PATH)
                ?: DEFAULT_WATCHDOG_PING_PATH,
            watchdogPingIntervalSeconds = clampInt(
                prefs.getInt(KEY_WATCHDOG_PING_INTERVAL_SECONDS, DEFAULT_WATCHDOG_PING_INTERVAL_SECONDS),
                MIN_WATCHDOG_PING_INTERVAL_SECONDS,
                MAX_WATCHDOG_PING_INTERVAL_SECONDS
            ),
            updatesEnabled = prefs.getBoolean(KEY_UPDATES_ENABLED, DEFAULT_UPDATES_ENABLED),
            updatesRepo = migrateRepoSlug(
                prefs.getString(KEY_UPDATES_REPO, DEFAULT_UPDATES_REPO) ?: DEFAULT_UPDATES_REPO
            ),
            updateCheckIntervalHours = clampInt(
                prefs.getInt(KEY_UPDATE_CHECK_INTERVAL_HOURS, DEFAULT_UPDATE_CHECK_INTERVAL_HOURS),
                MIN_UPDATE_CHECK_INTERVAL_HOURS,
                MAX_UPDATE_CHECK_INTERVAL_HOURS
            ),
            allowMixedContent = prefs.getBoolean(KEY_ALLOW_MIXED_CONTENT, DEFAULT_ALLOW_MIXED_CONTENT),
            allowThirdPartyCookies = prefs.getBoolean(
                KEY_ALLOW_THIRD_PARTY_COOKIES,
                DEFAULT_ALLOW_THIRD_PARTY_COOKIES
            ),
            autoplayEnabled = prefs.getBoolean(KEY_AUTOPLAY_ENABLED, DEFAULT_AUTOPLAY_ENABLED),
            desktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, DEFAULT_DESKTOP_MODE),
            customUserAgent = prefs.getString(KEY_CUSTOM_USER_AGENT, DEFAULT_CUSTOM_USER_AGENT)
                ?: DEFAULT_CUSTOM_USER_AGENT,
            requirePasswordForExitOnly = prefs.getBoolean(
                KEY_REQUIRE_PASSWORD_FOR_EXIT_ONLY,
                DEFAULT_REQUIRE_PASSWORD_FOR_EXIT_ONLY
            ),
            adminUnlockTimeoutMinutes = clampInt(
                prefs.getInt(KEY_ADMIN_UNLOCK_TIMEOUT_MINUTES, DEFAULT_ADMIN_UNLOCK_TIMEOUT_MINUTES),
                MIN_ADMIN_UNLOCK_TIMEOUT_MINUTES,
                MAX_ADMIN_UNLOCK_TIMEOUT_MINUTES
            ),
            adminButtonCorner = AdminButtonCorner.fromStoredValue(
                prefs.getString(KEY_ADMIN_BUTTON_CORNER, DEFAULT_ADMIN_BUTTON_CORNER.name)
            ),
            scheduleProfilesEnabled = prefs.getBoolean(
                KEY_SCHEDULE_PROFILES_ENABLED,
                DEFAULT_SCHEDULE_PROFILES_ENABLED
            ),
            homeStartHour = clampInt(
                prefs.getInt(KEY_HOME_START_HOUR, DEFAULT_HOME_START_HOUR),
                MIN_HOUR,
                MAX_HOUR
            ),
            wallStartHour = clampInt(
                prefs.getInt(KEY_WALL_START_HOUR, DEFAULT_WALL_START_HOUR),
                MIN_HOUR,
                MAX_HOUR
            ),
            nightStartHour = clampInt(
                prefs.getInt(KEY_NIGHT_START_HOUR, DEFAULT_NIGHT_START_HOUR),
                MIN_HOUR,
                MAX_HOUR
            ),
            profileHomePath = prefs.getString(KEY_PROFILE_HOME_PATH, DEFAULT_PROFILE_HOME_PATH)
                ?: DEFAULT_PROFILE_HOME_PATH,
            profileWallPath = prefs.getString(KEY_PROFILE_WALL_PATH, DEFAULT_PROFILE_WALL_PATH)
                ?: DEFAULT_PROFILE_WALL_PATH,
            profileNightPath = prefs.getString(KEY_PROFILE_NIGHT_PATH, DEFAULT_PROFILE_NIGHT_PATH)
                ?: DEFAULT_PROFILE_NIGHT_PATH,
            maintenanceEnabled = prefs.getBoolean(KEY_MAINTENANCE_ENABLED, DEFAULT_MAINTENANCE_ENABLED),
            maintenanceHour = clampInt(
                prefs.getInt(KEY_MAINTENANCE_HOUR, DEFAULT_MAINTENANCE_HOUR),
                MIN_HOUR,
                MAX_HOUR
            ),
            maintenanceMinute = clampInt(
                prefs.getInt(KEY_MAINTENANCE_MINUTE, DEFAULT_MAINTENANCE_MINUTE),
                MIN_MINUTE,
                MAX_MINUTE
            ),
            ambientModeEnabled = prefs.getBoolean(KEY_AMBIENT_MODE_ENABLED, DEFAULT_AMBIENT_MODE_ENABLED),
            ambientScreensaverEnabled = prefs.getBoolean(
                KEY_AMBIENT_SCREENSAVER_ENABLED,
                DEFAULT_AMBIENT_SCREENSAVER_ENABLED
            ),
            ambientScene = AmbientScene.fromStoredValue(
                prefs.getString(KEY_AMBIENT_SCENE, DEFAULT_AMBIENT_SCENE.name)
            ),
            ambientBackgroundMode = AmbientBackgroundMode.fromStoredValue(
                prefs.getString(KEY_AMBIENT_BACKGROUND_MODE, DEFAULT_AMBIENT_BACKGROUND_MODE.name)
            ),
            ambientBackgroundUrl = prefs.getString(
                KEY_AMBIENT_BACKGROUND_URL,
                DEFAULT_AMBIENT_BACKGROUND_URL
            ) ?: DEFAULT_AMBIENT_BACKGROUND_URL,
            ambientImmichShareUrl = prefs.getString(
                KEY_AMBIENT_IMMICH_SHARE_URL,
                DEFAULT_AMBIENT_IMMICH_SHARE_URL
            ) ?: DEFAULT_AMBIENT_IMMICH_SHARE_URL,
            ambientPhotoIntervalSeconds = clampInt(
                prefs.getInt(
                    KEY_AMBIENT_PHOTO_INTERVAL_SECONDS,
                    DEFAULT_AMBIENT_PHOTO_INTERVAL_SECONDS
                ),
                MIN_AMBIENT_PHOTO_INTERVAL_SECONDS,
                MAX_AMBIENT_PHOTO_INTERVAL_SECONDS
            ),
            ambientWeatherLocation = prefs.getString(
                KEY_AMBIENT_WEATHER_LOCATION,
                DEFAULT_AMBIENT_WEATHER_LOCATION
            ) ?: DEFAULT_AMBIENT_WEATHER_LOCATION,
            ambientDimAfterSeconds = clampInt(
                prefs.getInt(KEY_AMBIENT_DIM_AFTER_SECONDS, DEFAULT_AMBIENT_DIM_AFTER_SECONDS),
                MIN_AMBIENT_DIM_AFTER_SECONDS,
                MAX_AMBIENT_DIM_AFTER_SECONDS
            ),
            ambientBrightnessPercent = clampInt(
                prefs.getInt(KEY_AMBIENT_BRIGHTNESS_PERCENT, DEFAULT_AMBIENT_BRIGHTNESS_PERCENT),
                MIN_AMBIENT_BRIGHTNESS_PERCENT,
                MAX_AMBIENT_BRIGHTNESS_PERCENT
            ),
            ambientFollowSystemBrightness = prefs.getBoolean(
                KEY_AMBIENT_FOLLOW_SYSTEM_BRIGHTNESS,
                DEFAULT_AMBIENT_FOLLOW_SYSTEM_BRIGHTNESS
            ),
            presenceWakeEnabled = prefs.getBoolean(
                KEY_PRESENCE_WAKE_ENABLED,
                DEFAULT_PRESENCE_WAKE_ENABLED
            ),
            presenceWakeCooldownSeconds = clampInt(
                prefs.getInt(
                    KEY_PRESENCE_WAKE_COOLDOWN_SECONDS,
                    DEFAULT_PRESENCE_WAKE_COOLDOWN_SECONDS
                ),
                MIN_PRESENCE_WAKE_COOLDOWN_SECONDS,
                MAX_PRESENCE_WAKE_COOLDOWN_SECONDS
            ),
            respectManualScreenOff = prefs.getBoolean(
                KEY_RESPECT_MANUAL_SCREEN_OFF,
                DEFAULT_RESPECT_MANUAL_SCREEN_OFF
            ),
            deepSleepEnabled = prefs.getBoolean(KEY_DEEP_SLEEP_ENABLED, DEFAULT_DEEP_SLEEP_ENABLED),
            illuminanceReportingEnabled = prefs.getBoolean(
                KEY_ILLUMINANCE_REPORTING_ENABLED,
                DEFAULT_ILLUMINANCE_REPORTING_ENABLED
            ),
            illuminanceMinDeltaLux = clampInt(
                prefs.getInt(KEY_ILLUMINANCE_MIN_DELTA_LUX, DEFAULT_ILLUMINANCE_MIN_DELTA_LUX),
                MIN_ILLUMINANCE_MIN_DELTA_LUX,
                MAX_ILLUMINANCE_MIN_DELTA_LUX
            ),
            illuminanceDebounceSeconds = clampInt(
                prefs.getInt(KEY_ILLUMINANCE_DEBOUNCE_SECONDS, DEFAULT_ILLUMINANCE_DEBOUNCE_SECONDS),
                MIN_ILLUMINANCE_DEBOUNCE_SECONDS,
                MAX_ILLUMINANCE_DEBOUNCE_SECONDS
            ),
            autoDiscoverHomeAssistant = prefs.getBoolean(
                KEY_AUTO_DISCOVER_HOME_ASSISTANT,
                DEFAULT_AUTO_DISCOVER_HOME_ASSISTANT
            ),
            mqttEnabled = prefs.getBoolean(KEY_MQTT_ENABLED, DEFAULT_MQTT_ENABLED),
            mqttBrokerHost = prefs.getString(KEY_MQTT_BROKER_HOST, DEFAULT_MQTT_BROKER_HOST)
                ?: DEFAULT_MQTT_BROKER_HOST,
            mqttBrokerPort = clampInt(
                prefs.getInt(KEY_MQTT_BROKER_PORT, DEFAULT_MQTT_BROKER_PORT),
                MIN_MQTT_BROKER_PORT,
                MAX_MQTT_BROKER_PORT
            ),
            mqttUseTls = prefs.getBoolean(KEY_MQTT_USE_TLS, DEFAULT_MQTT_USE_TLS),
            mqttUsername = prefs.getString(KEY_MQTT_USERNAME, DEFAULT_MQTT_USERNAME)
                ?: DEFAULT_MQTT_USERNAME,
            localControlEnabled = prefs.getBoolean(
                KEY_LOCAL_CONTROL_ENABLED,
                DEFAULT_LOCAL_CONTROL_ENABLED
            ),
            localControlPort = clampInt(
                prefs.getInt(KEY_LOCAL_CONTROL_PORT, DEFAULT_LOCAL_CONTROL_PORT),
                MIN_LOCAL_CONTROL_PORT,
                MAX_LOCAL_CONTROL_PORT
            ),
            localControlPreviewEnabled = prefs.getBoolean(
                KEY_LOCAL_CONTROL_PREVIEW_ENABLED,
                DEFAULT_LOCAL_CONTROL_PREVIEW_ENABLED
            )
        )
    }

    fun save(settings: KioskSettings) {
        require(isHttpOrHttpsUrl(normalizeBaseUrl(settings.homeAssistantUrl))) {
            "Dashboard URL must be a valid HTTP(S) address"
        }
        require(!settings.scheduleProfilesEnabled || DashboardProfileSchedule.hasValidStartHours(
            settings.homeStartHour, settings.wallStartHour, settings.nightStartHour
        )) { "Profile start hours must be different and between 0 and 23" }
        require(!settings.updatesEnabled || isValidRepoSlug(settings.updatesRepo)) {
            "Update repository must use owner/repository format"
        }
        require(settings.localControlPort in MIN_LOCAL_CONTROL_PORT..MAX_LOCAL_CONTROL_PORT) {
            "Control panel port must be between 1024 and 65535"
        }
        require(
            !settings.ambientBackgroundMode.requiresUrl ||
                isValidAmbientMediaUrl(settings.ambientBackgroundUrl)
        ) { "Invalid ambient media URL" }
        require(
            settings.ambientBackgroundMode != AmbientBackgroundMode.IMMICH_ALBUM ||
                parseImmichShareUrl(settings.ambientImmichShareUrl) != null
        ) { "Invalid Immich album share URL" }
        require(!settings.mqttEnabled || MqttContract.isValidBroker(
            settings.mqttBrokerHost,
            settings.mqttBrokerPort
        )) { "Invalid MQTT broker host or port" }
        val storedMqttIdentity = MqttContract.brokerIdentity(
            prefs.getString(KEY_MQTT_BROKER_HOST, DEFAULT_MQTT_BROKER_HOST).orEmpty(),
            prefs.getInt(KEY_MQTT_BROKER_PORT, DEFAULT_MQTT_BROKER_PORT),
            prefs.getBoolean(KEY_MQTT_USE_TLS, DEFAULT_MQTT_USE_TLS),
            prefs.getString(KEY_MQTT_USERNAME, DEFAULT_MQTT_USERNAME).orEmpty()
        )
        val newMqttIdentity = MqttContract.brokerIdentity(
            settings.mqttBrokerHost,
            settings.mqttBrokerPort,
            settings.mqttUseTls,
            settings.mqttUsername
        )
        val editor = prefs.edit()
            .putString(KEY_BROWSER_ENGINE, settings.browserEngine.name)
            .putString(KEY_HOME_ASSISTANT_URL, normalizeBaseUrl(settings.homeAssistantUrl))
            .putString(KEY_DASHBOARD_PATH, normalizeDashboardPath(settings.dashboardPath))
            .putBoolean(KEY_APPEND_KIOSK, settings.appendKiosk)
            .putInt(
                KEY_RELOAD_INTERVAL_SECONDS,
                clampInt(
                    settings.reloadIntervalSeconds,
                    MIN_RELOAD_INTERVAL_SECONDS,
                    MAX_RELOAD_INTERVAL_SECONDS
                )
            )
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putBoolean(KEY_AUTOSTART_ON_BOOT, settings.autoStartOnBoot)
            .putBoolean(KEY_FULLSCREEN, settings.fullscreen)
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putBoolean(KEY_LOCK_TASK_MODE, settings.lockTaskMode)
            .putBoolean(KEY_AUTO_RELOAD_ON_FAILURE, settings.autoReloadOnFailure)
            .putBoolean(KEY_WATCHDOG_ENABLED, settings.watchdogEnabled)
            .putString(KEY_WATCHDOG_PING_PATH, normalizeDashboardPath(settings.watchdogPingPath))
            .putInt(
                KEY_WATCHDOG_PING_INTERVAL_SECONDS,
                clampInt(
                    settings.watchdogPingIntervalSeconds,
                    MIN_WATCHDOG_PING_INTERVAL_SECONDS,
                    MAX_WATCHDOG_PING_INTERVAL_SECONDS
                )
            )
            .putBoolean(KEY_UPDATES_ENABLED, settings.updatesEnabled)
            .putString(KEY_UPDATES_REPO, migrateRepoSlug(settings.updatesRepo))
            .putInt(
                KEY_UPDATE_CHECK_INTERVAL_HOURS,
                clampInt(
                    settings.updateCheckIntervalHours,
                    MIN_UPDATE_CHECK_INTERVAL_HOURS,
                    MAX_UPDATE_CHECK_INTERVAL_HOURS
                )
            )
            .putBoolean(KEY_ALLOW_MIXED_CONTENT, settings.allowMixedContent)
            .putBoolean(KEY_ALLOW_THIRD_PARTY_COOKIES, settings.allowThirdPartyCookies)
            .putBoolean(KEY_AUTOPLAY_ENABLED, settings.autoplayEnabled)
            .putBoolean(KEY_DESKTOP_MODE, settings.desktopMode)
            .putString(KEY_CUSTOM_USER_AGENT, settings.customUserAgent.trim())
            .putBoolean(KEY_REQUIRE_PASSWORD_FOR_EXIT_ONLY, settings.requirePasswordForExitOnly)
            .putInt(
                KEY_ADMIN_UNLOCK_TIMEOUT_MINUTES,
                clampInt(
                    settings.adminUnlockTimeoutMinutes,
                    MIN_ADMIN_UNLOCK_TIMEOUT_MINUTES,
                    MAX_ADMIN_UNLOCK_TIMEOUT_MINUTES
                )
            )
            .putString(KEY_ADMIN_BUTTON_CORNER, settings.adminButtonCorner.name)
            .putBoolean(KEY_SCHEDULE_PROFILES_ENABLED, settings.scheduleProfilesEnabled)
            .putInt(KEY_HOME_START_HOUR, clampInt(settings.homeStartHour, MIN_HOUR, MAX_HOUR))
            .putInt(KEY_WALL_START_HOUR, clampInt(settings.wallStartHour, MIN_HOUR, MAX_HOUR))
            .putInt(KEY_NIGHT_START_HOUR, clampInt(settings.nightStartHour, MIN_HOUR, MAX_HOUR))
            .putString(KEY_PROFILE_HOME_PATH, normalizeDashboardPath(settings.profileHomePath))
            .putString(KEY_PROFILE_WALL_PATH, normalizeDashboardPath(settings.profileWallPath))
            .putString(KEY_PROFILE_NIGHT_PATH, normalizeDashboardPath(settings.profileNightPath))
            .putBoolean(KEY_MAINTENANCE_ENABLED, settings.maintenanceEnabled)
            .putInt(KEY_MAINTENANCE_HOUR, clampInt(settings.maintenanceHour, MIN_HOUR, MAX_HOUR))
            .putInt(
                KEY_MAINTENANCE_MINUTE,
                clampInt(settings.maintenanceMinute, MIN_MINUTE, MAX_MINUTE)
            )
            .putBoolean(KEY_AMBIENT_MODE_ENABLED, settings.ambientModeEnabled)
            .putBoolean(KEY_AMBIENT_SCREENSAVER_ENABLED, settings.ambientScreensaverEnabled)
            .putString(KEY_AMBIENT_SCENE, settings.ambientScene.name)
            .putString(KEY_AMBIENT_BACKGROUND_MODE, settings.ambientBackgroundMode.name)
            .putString(
                KEY_AMBIENT_BACKGROUND_URL,
                settings.ambientBackgroundUrl.trim()
                    .takeIf { settings.ambientBackgroundMode.requiresUrl }
                    .orEmpty()
            )
            .putString(KEY_AMBIENT_IMMICH_SHARE_URL, settings.ambientImmichShareUrl.trim())
            .putInt(
                KEY_AMBIENT_PHOTO_INTERVAL_SECONDS,
                clampInt(
                    settings.ambientPhotoIntervalSeconds,
                    MIN_AMBIENT_PHOTO_INTERVAL_SECONDS,
                    MAX_AMBIENT_PHOTO_INTERVAL_SECONDS
                )
            )
            .putString(KEY_AMBIENT_WEATHER_LOCATION, settings.ambientWeatherLocation.trim())
            .putInt(
                KEY_AMBIENT_DIM_AFTER_SECONDS,
                clampInt(
                    settings.ambientDimAfterSeconds,
                    MIN_AMBIENT_DIM_AFTER_SECONDS,
                    MAX_AMBIENT_DIM_AFTER_SECONDS
                )
            )
            .putInt(
                KEY_AMBIENT_BRIGHTNESS_PERCENT,
                clampInt(
                    settings.ambientBrightnessPercent,
                    MIN_AMBIENT_BRIGHTNESS_PERCENT,
                    MAX_AMBIENT_BRIGHTNESS_PERCENT
                )
            )
            .putBoolean(
                KEY_AMBIENT_FOLLOW_SYSTEM_BRIGHTNESS,
                settings.ambientFollowSystemBrightness
            )
            .putBoolean(KEY_PRESENCE_WAKE_ENABLED, settings.presenceWakeEnabled)
            .putInt(
                KEY_PRESENCE_WAKE_COOLDOWN_SECONDS,
                clampInt(
                    settings.presenceWakeCooldownSeconds,
                    MIN_PRESENCE_WAKE_COOLDOWN_SECONDS,
                    MAX_PRESENCE_WAKE_COOLDOWN_SECONDS
                )
            )
            .putBoolean(KEY_RESPECT_MANUAL_SCREEN_OFF, settings.respectManualScreenOff)
            .putBoolean(KEY_DEEP_SLEEP_ENABLED, settings.deepSleepEnabled)
            .putBoolean(KEY_ILLUMINANCE_REPORTING_ENABLED, settings.illuminanceReportingEnabled)
            .putInt(
                KEY_ILLUMINANCE_MIN_DELTA_LUX,
                clampInt(
                    settings.illuminanceMinDeltaLux,
                    MIN_ILLUMINANCE_MIN_DELTA_LUX,
                    MAX_ILLUMINANCE_MIN_DELTA_LUX
                )
            )
            .putInt(
                KEY_ILLUMINANCE_DEBOUNCE_SECONDS,
                clampInt(
                    settings.illuminanceDebounceSeconds,
                    MIN_ILLUMINANCE_DEBOUNCE_SECONDS,
                    MAX_ILLUMINANCE_DEBOUNCE_SECONDS
                )
            )
            .putBoolean(KEY_AUTO_DISCOVER_HOME_ASSISTANT, settings.autoDiscoverHomeAssistant)
            .putBoolean(KEY_MQTT_ENABLED, settings.mqttEnabled)
            .putString(KEY_MQTT_BROKER_HOST, settings.mqttBrokerHost.trim())
            .putInt(
                KEY_MQTT_BROKER_PORT,
                clampInt(
                    settings.mqttBrokerPort,
                    MIN_MQTT_BROKER_PORT,
                    MAX_MQTT_BROKER_PORT
                )
            )
            .putBoolean(KEY_MQTT_USE_TLS, settings.mqttUseTls)
            .putString(KEY_MQTT_USERNAME, settings.mqttUsername.trim())
            .putBoolean(KEY_LOCAL_CONTROL_ENABLED, settings.localControlEnabled)
            .putInt(
                KEY_LOCAL_CONTROL_PORT,
                clampInt(
                    settings.localControlPort,
                    MIN_LOCAL_CONTROL_PORT,
                    MAX_LOCAL_CONTROL_PORT
                )
            )
            .putBoolean(
                KEY_LOCAL_CONTROL_PREVIEW_ENABLED,
                settings.localControlPreviewEnabled
            )
        if (storedMqttIdentity != newMqttIdentity) {
            editor.remove(KEY_MQTT_PASSWORD)
        }
        editor.apply()
    }

    fun resetToDefaults(): KioskSettings {
        val defaults = KioskSettings(
            browserEngine = DEFAULT_BROWSER_ENGINE,
            homeAssistantUrl = DEFAULT_HOME_ASSISTANT_URL,
            dashboardPath = DEFAULT_DASHBOARD_PATH,
            appendKiosk = DEFAULT_APPEND_KIOSK,
            reloadIntervalSeconds = DEFAULT_RELOAD_INTERVAL_SECONDS,
            keepScreenOn = DEFAULT_KEEP_SCREEN_ON,
            autoStartOnBoot = DEFAULT_AUTOSTART_ON_BOOT,
            fullscreen = DEFAULT_FULLSCREEN,
            themeMode = DEFAULT_THEME_MODE,
            lockTaskMode = DEFAULT_LOCK_TASK_MODE,
            autoReloadOnFailure = DEFAULT_AUTO_RELOAD_ON_FAILURE,
            watchdogEnabled = DEFAULT_WATCHDOG_ENABLED,
            watchdogPingPath = DEFAULT_WATCHDOG_PING_PATH,
            watchdogPingIntervalSeconds = DEFAULT_WATCHDOG_PING_INTERVAL_SECONDS,
            updatesEnabled = DEFAULT_UPDATES_ENABLED,
            updatesRepo = DEFAULT_UPDATES_REPO,
            updateCheckIntervalHours = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS,
            allowMixedContent = DEFAULT_ALLOW_MIXED_CONTENT,
            allowThirdPartyCookies = DEFAULT_ALLOW_THIRD_PARTY_COOKIES,
            autoplayEnabled = DEFAULT_AUTOPLAY_ENABLED,
            desktopMode = DEFAULT_DESKTOP_MODE,
            customUserAgent = DEFAULT_CUSTOM_USER_AGENT,
            requirePasswordForExitOnly = DEFAULT_REQUIRE_PASSWORD_FOR_EXIT_ONLY,
            adminUnlockTimeoutMinutes = DEFAULT_ADMIN_UNLOCK_TIMEOUT_MINUTES,
            adminButtonCorner = DEFAULT_ADMIN_BUTTON_CORNER,
            scheduleProfilesEnabled = DEFAULT_SCHEDULE_PROFILES_ENABLED,
            homeStartHour = DEFAULT_HOME_START_HOUR,
            wallStartHour = DEFAULT_WALL_START_HOUR,
            nightStartHour = DEFAULT_NIGHT_START_HOUR,
            profileHomePath = DEFAULT_PROFILE_HOME_PATH,
            profileWallPath = DEFAULT_PROFILE_WALL_PATH,
            profileNightPath = DEFAULT_PROFILE_NIGHT_PATH,
            maintenanceEnabled = DEFAULT_MAINTENANCE_ENABLED,
            maintenanceHour = DEFAULT_MAINTENANCE_HOUR,
            maintenanceMinute = DEFAULT_MAINTENANCE_MINUTE,
            ambientModeEnabled = DEFAULT_AMBIENT_MODE_ENABLED,
            ambientScreensaverEnabled = DEFAULT_AMBIENT_SCREENSAVER_ENABLED,
            ambientScene = DEFAULT_AMBIENT_SCENE,
            ambientBackgroundMode = DEFAULT_AMBIENT_BACKGROUND_MODE,
            ambientBackgroundUrl = DEFAULT_AMBIENT_BACKGROUND_URL,
            ambientImmichShareUrl = DEFAULT_AMBIENT_IMMICH_SHARE_URL,
            ambientPhotoIntervalSeconds = DEFAULT_AMBIENT_PHOTO_INTERVAL_SECONDS,
            ambientWeatherLocation = DEFAULT_AMBIENT_WEATHER_LOCATION,
            ambientDimAfterSeconds = DEFAULT_AMBIENT_DIM_AFTER_SECONDS,
            ambientBrightnessPercent = DEFAULT_AMBIENT_BRIGHTNESS_PERCENT,
            ambientFollowSystemBrightness = DEFAULT_AMBIENT_FOLLOW_SYSTEM_BRIGHTNESS,
            presenceWakeEnabled = DEFAULT_PRESENCE_WAKE_ENABLED,
            presenceWakeCooldownSeconds = DEFAULT_PRESENCE_WAKE_COOLDOWN_SECONDS,
            respectManualScreenOff = DEFAULT_RESPECT_MANUAL_SCREEN_OFF,
            deepSleepEnabled = DEFAULT_DEEP_SLEEP_ENABLED,
            illuminanceReportingEnabled = DEFAULT_ILLUMINANCE_REPORTING_ENABLED,
            illuminanceMinDeltaLux = DEFAULT_ILLUMINANCE_MIN_DELTA_LUX,
            illuminanceDebounceSeconds = DEFAULT_ILLUMINANCE_DEBOUNCE_SECONDS,
            autoDiscoverHomeAssistant = DEFAULT_AUTO_DISCOVER_HOME_ASSISTANT,
            mqttEnabled = DEFAULT_MQTT_ENABLED,
            mqttBrokerHost = DEFAULT_MQTT_BROKER_HOST,
            mqttBrokerPort = DEFAULT_MQTT_BROKER_PORT,
            mqttUseTls = DEFAULT_MQTT_USE_TLS,
            mqttUsername = DEFAULT_MQTT_USERNAME,
            localControlEnabled = DEFAULT_LOCAL_CONTROL_ENABLED,
            localControlPort = DEFAULT_LOCAL_CONTROL_PORT,
            localControlPreviewEnabled = DEFAULT_LOCAL_CONTROL_PREVIEW_ENABLED
        )
        save(defaults)
        clearMqttPassword()
        return defaults
    }

    fun hasAdminPassword(): Boolean {
        return !prefs.getString(KEY_ADMIN_PASSWORD_HASH, "").isNullOrBlank()
    }

    internal fun adminCredentialIdentity(): String {
        return prefs.getString(KEY_ADMIN_PASSWORD_HASH, "").orEmpty()
    }

    fun verifyAdminPassword(candidate: String): Boolean {
        val stored = prefs.getString(KEY_ADMIN_PASSWORD_HASH, "").orEmpty()
        if (stored.isBlank()) {
            return candidate.isBlank()
        }

        val normalized = candidate.trim()
        return if (stored.startsWith(PASSWORD_HASH_PREFIX)) {
            val matches = verifyPbkdf2Password(stored, normalized)
            if (matches) {
                val iterationCount = parsePbkdf2Iterations(stored)
                if (iterationCount != null && iterationCount > PBKDF2_ITERATIONS) {
                    // Re-save with the current work factor so future unlocks are faster on older tablets.
                    setAdminPassword(normalized)
                }
            }
            matches
        } else {
            val legacyMatches = stored == hashLegacyPassword(normalized)
            if (legacyMatches) {
                setAdminPassword(normalized)
            }
            legacyMatches
        }
    }

    internal fun verifyAdminPasswordForUnlock(candidate: String): String? = synchronized(prefs) {
        if (verifyAdminPassword(candidate)) adminCredentialIdentity().takeIf { it.isNotBlank() } else null
    }

    fun setAdminPassword(rawPassword: String): Unit = synchronized(prefs) {
        val normalized = rawPassword.trim()
        if (normalized.isEmpty()) {
            clearAdminPassword()
            return@synchronized
        }
        prefs.edit()
            .putString(KEY_ADMIN_PASSWORD_HASH, createPbkdf2Hash(normalized))
            .remove(KEY_LAST_ADMIN_UNLOCK_AT_MILLIS)
            .apply()
        adminUnlockSessions.remove(prefs)
        Unit
    }

    fun clearAdminPassword(): Unit = synchronized(prefs) {
        adminUnlockSessions.remove(prefs)
        prefs.edit()
            .remove(KEY_ADMIN_PASSWORD_HASH)
            .remove(KEY_LAST_ADMIN_UNLOCK_AT_MILLIS)
            .apply()
    }

    fun hasMqttPassword(): Boolean = !prefs.getString(KEY_MQTT_PASSWORD, null).isNullOrEmpty()

    fun mqttPassword(): String = prefs.getString(KEY_MQTT_PASSWORD, "").orEmpty()

    @Synchronized
    fun mqttDeviceKey(): String {
        prefs.getString(KEY_MQTT_DEVICE_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString(KEY_MQTT_DEVICE_KEY, it).apply()
        }
    }

    fun setMqttPassword(password: String) {
        prefs.edit().putString(KEY_MQTT_PASSWORD, password).apply()
    }

    fun clearMqttPassword() {
        prefs.edit().remove(KEY_MQTT_PASSWORD).apply()
    }

    fun markAdminUnlockedNow(verifiedCredential: String): Boolean = synchronized(prefs) {
        if (verifiedCredential.isBlank() || verifiedCredential != adminCredentialIdentity()) {
            return@synchronized false
        }
        adminUnlockSessions[prefs] = AdminUnlockSession(verifiedCredential, SystemClock.elapsedRealtime())
        true
    }

    fun isAdminSessionStillValid(settings: KioskSettings = load()): Boolean {
        if (!hasAdminPassword()) {
            return true
        }
        return adminUnlockSessions[prefs]?.isValid(
            adminCredentialIdentity(), SystemClock.elapsedRealtime(), settings.adminUnlockTimeoutMinutes
        ) == true
    }

    fun recordLastEngine(engine: BrowserEngine) {
        prefs.edit().putString(KEY_DIAG_LAST_ENGINE, engine.name).apply()
    }

    fun recordLastUrl(url: String) {
        prefs.edit().putString(KEY_DIAG_LAST_URL, url).apply()
    }

    fun recordLastLoadStatus(status: String) {
        prefs.edit().putString(KEY_DIAG_LAST_LOAD_STATUS, status).apply()
    }

    fun recordCrash(reason: String) {
        prefs.edit()
            .putString(KEY_DIAG_LAST_CRASH_REASON, reason)
            .putLong(KEY_DIAG_LAST_CRASH_AT_MILLIS, System.currentTimeMillis())
            .apply()
    }

    fun recordNetworkState(state: String) {
        prefs.edit().putString(KEY_DIAG_LAST_NETWORK_STATE, state).apply()
    }

    fun recordWatchdogState(state: String) {
        prefs.edit().putString(KEY_DIAG_LAST_WATCHDOG_STATE, state).apply()
    }

    fun recordUpdateState(state: String) {
        prefs.edit().putString(KEY_DIAG_LAST_UPDATE_STATE, state).apply()
    }

    fun recordMqttState(state: String) {
        prefs.edit().putString(KEY_DIAG_LAST_MQTT_STATE, state).apply()
    }

    fun diagnosticsSnapshot(): DiagnosticsSnapshot {
        return DiagnosticsSnapshot(
            lastEngine = prefs.getString(KEY_DIAG_LAST_ENGINE, "unknown").orEmpty(),
            lastUrl = prefs.getString(KEY_DIAG_LAST_URL, "-").orEmpty(),
            lastLoadStatus = prefs.getString(KEY_DIAG_LAST_LOAD_STATUS, "-").orEmpty(),
            lastCrashReason = prefs.getString(KEY_DIAG_LAST_CRASH_REASON, "-").orEmpty(),
            lastCrashAtMillis = prefs.getLong(KEY_DIAG_LAST_CRASH_AT_MILLIS, 0L),
            lastNetworkState = prefs.getString(KEY_DIAG_LAST_NETWORK_STATE, "unknown").orEmpty(),
            lastWatchdogState = prefs.getString(KEY_DIAG_LAST_WATCHDOG_STATE, "unknown").orEmpty(),
            lastUpdateState = prefs.getString(KEY_DIAG_LAST_UPDATE_STATE, "unknown").orEmpty(),
            lastMqttState = prefs.getString(KEY_DIAG_LAST_MQTT_STATE, "disabled").orEmpty()
        )
    }

    fun exportSettingsJson(settings: KioskSettings = load()): String {
        val json = JSONObject().apply {
            put("browserEngine", settings.browserEngine.name)
            put("homeAssistantUrl", settings.homeAssistantUrl)
            put("dashboardPath", settings.dashboardPath)
            put("appendKiosk", settings.appendKiosk)
            put("reloadIntervalSeconds", settings.reloadIntervalSeconds)
            put("keepScreenOn", settings.keepScreenOn)
            put("autoStartOnBoot", settings.autoStartOnBoot)
            put("fullscreen", settings.fullscreen)
            put("themeMode", settings.themeMode.name)
            put("lockTaskMode", settings.lockTaskMode)
            put("autoReloadOnFailure", settings.autoReloadOnFailure)
            put("watchdogEnabled", settings.watchdogEnabled)
            put("watchdogPingPath", settings.watchdogPingPath)
            put("watchdogPingIntervalSeconds", settings.watchdogPingIntervalSeconds)
            put("updatesEnabled", settings.updatesEnabled)
            put("updatesRepo", settings.updatesRepo)
            put("updateCheckIntervalHours", settings.updateCheckIntervalHours)
            put("allowMixedContent", settings.allowMixedContent)
            put("allowThirdPartyCookies", settings.allowThirdPartyCookies)
            put("autoplayEnabled", settings.autoplayEnabled)
            put("desktopMode", settings.desktopMode)
            put("customUserAgent", settings.customUserAgent)
            put("requirePasswordForExitOnly", settings.requirePasswordForExitOnly)
            put("adminUnlockTimeoutMinutes", settings.adminUnlockTimeoutMinutes)
            put("adminButtonCorner", settings.adminButtonCorner.name)
            put("scheduleProfilesEnabled", settings.scheduleProfilesEnabled)
            put("homeStartHour", settings.homeStartHour)
            put("wallStartHour", settings.wallStartHour)
            put("nightStartHour", settings.nightStartHour)
            put("profileHomePath", settings.profileHomePath)
            put("profileWallPath", settings.profileWallPath)
            put("profileNightPath", settings.profileNightPath)
            put("maintenanceEnabled", settings.maintenanceEnabled)
            put("maintenanceHour", settings.maintenanceHour)
            put("maintenanceMinute", settings.maintenanceMinute)
            put("ambientModeEnabled", settings.ambientModeEnabled)
            put("ambientScreensaverEnabled", settings.ambientScreensaverEnabled)
            put("ambientScene", settings.ambientScene.name)
            put("ambientBackgroundMode", settings.ambientBackgroundMode.name)
            put("ambientBackgroundUrl", settings.ambientBackgroundUrl)
            put("ambientPhotoIntervalSeconds", settings.ambientPhotoIntervalSeconds)
            put("ambientWeatherLocation", settings.ambientWeatherLocation)
            put("ambientDimAfterSeconds", settings.ambientDimAfterSeconds)
            put("ambientBrightnessPercent", settings.ambientBrightnessPercent)
            put("ambientFollowSystemBrightness", settings.ambientFollowSystemBrightness)
            put("presenceWakeEnabled", settings.presenceWakeEnabled)
            put("presenceWakeCooldownSeconds", settings.presenceWakeCooldownSeconds)
            put("respectManualScreenOff", settings.respectManualScreenOff)
            put("deepSleepEnabled", settings.deepSleepEnabled)
            put("illuminanceReportingEnabled", settings.illuminanceReportingEnabled)
            put("illuminanceMinDeltaLux", settings.illuminanceMinDeltaLux)
            put("illuminanceDebounceSeconds", settings.illuminanceDebounceSeconds)
            put("autoDiscoverHomeAssistant", settings.autoDiscoverHomeAssistant)
            put("mqttEnabled", settings.mqttEnabled)
            put("mqttBrokerHost", settings.mqttBrokerHost)
            put("mqttBrokerPort", settings.mqttBrokerPort)
            put("mqttUseTls", settings.mqttUseTls)
            put("mqttUsername", settings.mqttUsername)
            put("localControlEnabled", settings.localControlEnabled)
            put("localControlPort", settings.localControlPort)
            put("localControlPreviewEnabled", settings.localControlPreviewEnabled)
        }
        return json.toString(2)
    }

    fun importSettingsJson(rawJson: String): KioskSettings {
        val current = load()
        val json = JSONObject(rawJson)
        val importedBackgroundMode = AmbientBackgroundMode.fromStoredValue(
            json.optString("ambientBackgroundMode", current.ambientBackgroundMode.name)
        ).let { mode ->
            if (mode == AmbientBackgroundMode.IMMICH_ALBUM &&
                parseImmichShareUrl(current.ambientImmichShareUrl) == null
            ) {
                AmbientBackgroundMode.BUILT_IN
            } else {
                mode
            }
        }
        val merged = current.copy(
            browserEngine = BrowserEngine.fromStoredValue(json.optString("browserEngine")),
            homeAssistantUrl = json.optString("homeAssistantUrl", current.homeAssistantUrl),
            dashboardPath = json.optString("dashboardPath", current.dashboardPath),
            appendKiosk = json.optBoolean("appendKiosk", current.appendKiosk),
            reloadIntervalSeconds = json.optInt("reloadIntervalSeconds", current.reloadIntervalSeconds),
            keepScreenOn = json.optBoolean("keepScreenOn", current.keepScreenOn),
            autoStartOnBoot = json.optBoolean("autoStartOnBoot", current.autoStartOnBoot),
            fullscreen = json.optBoolean("fullscreen", current.fullscreen),
            themeMode = ThemeMode.fromStoredValue(json.optString("themeMode", current.themeMode.name)),
            lockTaskMode = json.optBoolean("lockTaskMode", current.lockTaskMode),
            autoReloadOnFailure = json.optBoolean("autoReloadOnFailure", current.autoReloadOnFailure),
            watchdogEnabled = json.optBoolean("watchdogEnabled", current.watchdogEnabled),
            watchdogPingPath = json.optString("watchdogPingPath", current.watchdogPingPath),
            watchdogPingIntervalSeconds = json.optInt(
                "watchdogPingIntervalSeconds",
                current.watchdogPingIntervalSeconds
            ),
            updatesEnabled = json.optBoolean("updatesEnabled", current.updatesEnabled),
            updatesRepo = json.optString("updatesRepo", current.updatesRepo),
            updateCheckIntervalHours = json.optInt("updateCheckIntervalHours", current.updateCheckIntervalHours),
            allowMixedContent = json.optBoolean("allowMixedContent", current.allowMixedContent),
            allowThirdPartyCookies = json.optBoolean(
                "allowThirdPartyCookies",
                current.allowThirdPartyCookies
            ),
            autoplayEnabled = json.optBoolean("autoplayEnabled", current.autoplayEnabled),
            desktopMode = json.optBoolean("desktopMode", current.desktopMode),
            customUserAgent = json.optString("customUserAgent", current.customUserAgent),
            requirePasswordForExitOnly = json.optBoolean(
                "requirePasswordForExitOnly",
                current.requirePasswordForExitOnly
            ),
            adminUnlockTimeoutMinutes = json.optInt(
                "adminUnlockTimeoutMinutes",
                current.adminUnlockTimeoutMinutes
            ),
            adminButtonCorner = AdminButtonCorner.fromStoredValue(
                json.optString("adminButtonCorner", current.adminButtonCorner.name)
            ),
            scheduleProfilesEnabled = json.optBoolean(
                "scheduleProfilesEnabled",
                current.scheduleProfilesEnabled
            ),
            homeStartHour = json.optInt("homeStartHour", current.homeStartHour),
            wallStartHour = json.optInt("wallStartHour", current.wallStartHour),
            nightStartHour = json.optInt("nightStartHour", current.nightStartHour),
            profileHomePath = json.optString("profileHomePath", current.profileHomePath),
            profileWallPath = json.optString("profileWallPath", current.profileWallPath),
            profileNightPath = json.optString("profileNightPath", current.profileNightPath),
            maintenanceEnabled = json.optBoolean("maintenanceEnabled", current.maintenanceEnabled),
            maintenanceHour = json.optInt("maintenanceHour", current.maintenanceHour),
            maintenanceMinute = json.optInt("maintenanceMinute", current.maintenanceMinute),
            ambientModeEnabled = json.optBoolean("ambientModeEnabled", current.ambientModeEnabled),
            ambientScreensaverEnabled = json.optBoolean(
                "ambientScreensaverEnabled",
                current.ambientScreensaverEnabled
            ),
            ambientScene = AmbientScene.fromStoredValue(
                json.optString("ambientScene", current.ambientScene.name)
            ),
            ambientBackgroundMode = importedBackgroundMode,
            ambientBackgroundUrl = json.optString(
                "ambientBackgroundUrl",
                current.ambientBackgroundUrl
            ),
            // The Immich share URL is intentionally device-local and never imported/exported.
            ambientImmichShareUrl = current.ambientImmichShareUrl,
            ambientPhotoIntervalSeconds = json.optInt(
                "ambientPhotoIntervalSeconds",
                current.ambientPhotoIntervalSeconds
            ),
            ambientWeatherLocation = json.optString(
                "ambientWeatherLocation",
                current.ambientWeatherLocation
            ),
            ambientDimAfterSeconds = json.optInt("ambientDimAfterSeconds", current.ambientDimAfterSeconds),
            ambientBrightnessPercent = json.optInt(
                "ambientBrightnessPercent",
                current.ambientBrightnessPercent
            ),
            ambientFollowSystemBrightness = json.optBoolean(
                "ambientFollowSystemBrightness",
                current.ambientFollowSystemBrightness
            ),
            presenceWakeEnabled = json.optBoolean("presenceWakeEnabled", current.presenceWakeEnabled),
            presenceWakeCooldownSeconds = json.optInt(
                "presenceWakeCooldownSeconds",
                current.presenceWakeCooldownSeconds
            ),
            respectManualScreenOff = json.optBoolean(
                "respectManualScreenOff",
                current.respectManualScreenOff
            ),
            deepSleepEnabled = json.optBoolean("deepSleepEnabled", current.deepSleepEnabled),
            illuminanceReportingEnabled = json.optBoolean(
                "illuminanceReportingEnabled",
                current.illuminanceReportingEnabled
            ),
            illuminanceMinDeltaLux = json.optInt(
                "illuminanceMinDeltaLux",
                current.illuminanceMinDeltaLux
            ),
            illuminanceDebounceSeconds = json.optInt(
                "illuminanceDebounceSeconds",
                current.illuminanceDebounceSeconds
            ),
            autoDiscoverHomeAssistant = json.optBoolean(
                "autoDiscoverHomeAssistant",
                current.autoDiscoverHomeAssistant
            ),
            mqttEnabled = json.optBoolean("mqttEnabled", current.mqttEnabled),
            mqttBrokerHost = json.optString("mqttBrokerHost", current.mqttBrokerHost),
            mqttBrokerPort = json.optInt("mqttBrokerPort", current.mqttBrokerPort),
            mqttUseTls = json.optBoolean("mqttUseTls", current.mqttUseTls),
            mqttUsername = json.optString("mqttUsername", current.mqttUsername),
            localControlEnabled = json.optBoolean(
                "localControlEnabled",
                current.localControlEnabled
            ),
            localControlPort = json.optInt(
                "localControlPort",
                current.localControlPort
            ),
            localControlPreviewEnabled = json.optBoolean(
                "localControlPreviewEnabled",
                current.localControlPreviewEnabled
            )
        )
        require(
            !merged.scheduleProfilesEnabled || DashboardProfileSchedule.hasValidStartHours(
                merged.homeStartHour,
                merged.wallStartHour,
                merged.nightStartHour
            )
        ) { "Profile start hours must be unique and between 0 and 23" }
        save(merged)
        return load()
    }

    fun buildDashboardUrl(
        settings: KioskSettings = load(),
        dashboardPath: String = settings.dashboardPath
    ): String {
        val normalizedBase = normalizeBaseUrl(settings.homeAssistantUrl)
        val base = if (isHttpOrHttpsUrl(normalizedBase)) {
            normalizedBase
        } else {
            DEFAULT_HOME_ASSISTANT_URL
        }.trimEnd('/')
        val path = normalizeDashboardPath(dashboardPath)

        var url = if (path.isBlank()) {
            base
        } else {
            "$base/$path"
        }

        if (settings.appendKiosk && !KIOSK_QUERY_REGEX.containsMatchIn(url)) {
            url += if (url.contains("?")) "&kiosk" else "?kiosk"
        }
        return url
    }

    fun applyThemeMode() {
        AppCompatDelegate.setDefaultNightMode(load().themeMode.appCompatMode)
    }

    fun shouldUseDesktopUa(settings: KioskSettings = load()): Boolean {
        return settings.desktopMode
    }

    companion object {
        // Local unlock survives Activity recreation, never a process restart or reboot.
        // Monotonic time prevents a clock correction from extending administrator access.
        private val adminUnlockSessions = Collections.synchronizedMap(
            WeakHashMap<SharedPreferences, AdminUnlockSession>()
        )

        private const val PREF_FILE = "wallmode_prefs"

        private const val KEY_BROWSER_ENGINE = "browser_engine"
        private const val KEY_ADMIN_PASSWORD_HASH = "admin_password_hash"
        private const val KEY_HOME_ASSISTANT_URL = "home_assistant_url"
        private const val KEY_DASHBOARD_PATH = "dashboard_path"
        private const val KEY_APPEND_KIOSK = "append_kiosk"
        private const val KEY_RELOAD_INTERVAL_SECONDS = "reload_interval_seconds"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_AUTOSTART_ON_BOOT = "autostart_on_boot"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LOCK_TASK_MODE = "lock_task_mode"
        private const val KEY_AUTO_RELOAD_ON_FAILURE = "auto_reload_on_failure"
        private const val KEY_WATCHDOG_ENABLED = "watchdog_enabled"
        private const val KEY_WATCHDOG_PING_PATH = "watchdog_ping_path"
        private const val KEY_WATCHDOG_PING_INTERVAL_SECONDS = "watchdog_ping_interval_seconds"
        private const val KEY_UPDATES_ENABLED = "updates_enabled"
        private const val KEY_UPDATES_REPO = "updates_repo"
        private const val KEY_UPDATE_CHECK_INTERVAL_HOURS = "update_check_interval_hours"
        private const val KEY_ALLOW_MIXED_CONTENT = "allow_mixed_content"
        private const val KEY_ALLOW_THIRD_PARTY_COOKIES = "allow_third_party_cookies"
        private const val KEY_AUTOPLAY_ENABLED = "autoplay_enabled"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_CUSTOM_USER_AGENT = "custom_user_agent"
        private const val KEY_REQUIRE_PASSWORD_FOR_EXIT_ONLY = "require_password_for_exit_only"
        private const val KEY_ADMIN_UNLOCK_TIMEOUT_MINUTES = "admin_unlock_timeout_minutes"
        private const val KEY_ADMIN_BUTTON_CORNER = "admin_button_corner"
        private const val KEY_LAST_ADMIN_UNLOCK_AT_MILLIS = "last_admin_unlock_at_millis"
        private const val KEY_SCHEDULE_PROFILES_ENABLED = "schedule_profiles_enabled"
        private const val KEY_HOME_START_HOUR = "home_start_hour"
        private const val KEY_WALL_START_HOUR = "wall_start_hour"
        private const val KEY_NIGHT_START_HOUR = "night_start_hour"
        private const val KEY_PROFILE_HOME_PATH = "profile_home_path"
        private const val KEY_PROFILE_WALL_PATH = "profile_wall_path"
        private const val KEY_PROFILE_NIGHT_PATH = "profile_night_path"
        private const val KEY_MAINTENANCE_ENABLED = "maintenance_enabled"
        private const val KEY_MAINTENANCE_HOUR = "maintenance_hour"
        private const val KEY_MAINTENANCE_MINUTE = "maintenance_minute"
        private const val KEY_AMBIENT_MODE_ENABLED = "ambient_mode_enabled"
        private const val KEY_AMBIENT_SCREENSAVER_ENABLED = "ambient_screensaver_enabled"
        private const val KEY_AMBIENT_SCENE = "ambient_scene"
        private const val KEY_AMBIENT_BACKGROUND_MODE = "ambient_background_mode"
        private const val KEY_AMBIENT_BACKGROUND_URL = "ambient_background_url"
        private const val KEY_AMBIENT_IMMICH_SHARE_URL = "ambient_immich_share_url"
        private const val KEY_AMBIENT_PHOTO_INTERVAL_SECONDS = "ambient_photo_interval_seconds"
        private const val KEY_AMBIENT_WEATHER_LOCATION = "ambient_weather_location"
        private const val KEY_AMBIENT_DIM_AFTER_SECONDS = "ambient_dim_after_seconds"
        private const val KEY_AMBIENT_BRIGHTNESS_PERCENT = "ambient_brightness_percent"
        private const val KEY_AMBIENT_FOLLOW_SYSTEM_BRIGHTNESS =
            "ambient_follow_system_brightness"
        private const val KEY_PRESENCE_WAKE_ENABLED = "presence_wake_enabled"
        private const val KEY_PRESENCE_WAKE_COOLDOWN_SECONDS = "presence_wake_cooldown_seconds"
        private const val KEY_RESPECT_MANUAL_SCREEN_OFF = "respect_manual_screen_off"
        private const val KEY_DEEP_SLEEP_ENABLED = "deep_sleep_enabled"
        private const val KEY_ILLUMINANCE_REPORTING_ENABLED = "illuminance_reporting_enabled"
        private const val KEY_ILLUMINANCE_MIN_DELTA_LUX = "illuminance_min_delta_lux"
        private const val KEY_ILLUMINANCE_DEBOUNCE_SECONDS = "illuminance_debounce_seconds"
        private const val KEY_AUTO_DISCOVER_HOME_ASSISTANT = "auto_discover_home_assistant"
        private const val KEY_MQTT_ENABLED = "mqtt_enabled"
        private const val KEY_MQTT_BROKER_HOST = "mqtt_broker_host"
        private const val KEY_MQTT_BROKER_PORT = "mqtt_broker_port"
        private const val KEY_MQTT_USE_TLS = "mqtt_use_tls"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_DEVICE_KEY = "mqtt_device_key"
        private const val KEY_LOCAL_CONTROL_ENABLED = "local_control_enabled"
        private const val KEY_LOCAL_CONTROL_PORT = "local_control_port"
        private const val KEY_LOCAL_CONTROL_PREVIEW_ENABLED = "local_control_preview_enabled"
        private const val KEY_STARTUP_CAMERA_PROMPT_SHOWN = "startup_camera_prompt_shown"

        private const val KEY_DIAG_LAST_ENGINE = "diag_last_engine"
        private const val KEY_DIAG_LAST_URL = "diag_last_url"
        private const val KEY_DIAG_LAST_LOAD_STATUS = "diag_last_load_status"
        private const val KEY_DIAG_LAST_CRASH_REASON = "diag_last_crash_reason"
        private const val KEY_DIAG_LAST_CRASH_AT_MILLIS = "diag_last_crash_at_millis"
        private const val KEY_DIAG_LAST_NETWORK_STATE = "diag_last_network_state"
        private const val KEY_DIAG_LAST_WATCHDOG_STATE = "diag_last_watchdog_state"
        private const val KEY_DIAG_LAST_UPDATE_STATE = "diag_last_update_state"
        private const val KEY_DIAG_LAST_MQTT_STATE = "diag_last_mqtt_state"

        private val KIOSK_QUERY_REGEX = Regex("([?&])kiosk(=|&|$)")
        private const val PASSWORD_HASH_PREFIX = "pbkdf2_sha256"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_LENGTH_BITS = 256
        private const val PBKDF2_SALT_BYTES = 16

        val DEFAULT_BROWSER_ENGINE = BrowserEngine.WEBVIEW
        const val DEFAULT_HOME_ASSISTANT_URL = "http://homeassistant.local:8123"
        const val DEFAULT_DASHBOARD_PATH = ""
        const val DEFAULT_APPEND_KIOSK = false
        const val DEFAULT_RELOAD_INTERVAL_SECONDS = 20
        const val DEFAULT_KEEP_SCREEN_ON = true
        const val DEFAULT_AUTOSTART_ON_BOOT = true
        const val DEFAULT_FULLSCREEN = true
        val DEFAULT_THEME_MODE = ThemeMode.SYSTEM
        const val DEFAULT_LOCK_TASK_MODE = false
        const val DEFAULT_AUTO_RELOAD_ON_FAILURE = true
        const val DEFAULT_WATCHDOG_ENABLED = true
        const val DEFAULT_WATCHDOG_PING_PATH = "api/"
        const val DEFAULT_WATCHDOG_PING_INTERVAL_SECONDS = 45
        const val DEFAULT_UPDATES_ENABLED = false
        const val DEFAULT_UPDATES_REPO = "rvbcrs/WallMode"
        private const val LEGACY_UPDATES_REPO = "exraaaa/KioskZen"
        const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12
        const val DEFAULT_ALLOW_MIXED_CONTENT = true
        const val DEFAULT_ALLOW_THIRD_PARTY_COOKIES = false
        const val DEFAULT_AUTOPLAY_ENABLED = true
        const val DEFAULT_DESKTOP_MODE = false
        const val DEFAULT_CUSTOM_USER_AGENT = ""
        const val DEFAULT_REQUIRE_PASSWORD_FOR_EXIT_ONLY = false
        const val DEFAULT_ADMIN_UNLOCK_TIMEOUT_MINUTES = 15
        val DEFAULT_ADMIN_BUTTON_CORNER = AdminButtonCorner.TOP_RIGHT
        const val DEFAULT_SCHEDULE_PROFILES_ENABLED = false
        const val DEFAULT_HOME_START_HOUR = 6
        const val DEFAULT_WALL_START_HOUR = 10
        const val DEFAULT_NIGHT_START_HOUR = 21
        const val DEFAULT_PROFILE_HOME_PATH = DEFAULT_DASHBOARD_PATH
        const val DEFAULT_PROFILE_WALL_PATH = DEFAULT_DASHBOARD_PATH
        const val DEFAULT_PROFILE_NIGHT_PATH = DEFAULT_DASHBOARD_PATH
        const val DEFAULT_MAINTENANCE_ENABLED = false
        const val DEFAULT_MAINTENANCE_HOUR = 3
        const val DEFAULT_MAINTENANCE_MINUTE = 0
        const val DEFAULT_AMBIENT_MODE_ENABLED = false
        const val DEFAULT_AMBIENT_SCREENSAVER_ENABLED = false
        val DEFAULT_AMBIENT_SCENE = AmbientScene.AURORA_WEATHER
        val DEFAULT_AMBIENT_BACKGROUND_MODE = AmbientBackgroundMode.BUILT_IN
        const val DEFAULT_AMBIENT_BACKGROUND_URL = ""
        const val DEFAULT_AMBIENT_IMMICH_SHARE_URL = ""
        const val DEFAULT_AMBIENT_PHOTO_INTERVAL_SECONDS = 30
        const val DEFAULT_AMBIENT_WEATHER_LOCATION = ""
        const val DEFAULT_AMBIENT_DIM_AFTER_SECONDS = 120
        const val DEFAULT_AMBIENT_BRIGHTNESS_PERCENT = 25
        const val DEFAULT_AMBIENT_FOLLOW_SYSTEM_BRIGHTNESS = false
        const val DEFAULT_PRESENCE_WAKE_ENABLED = false
        const val DEFAULT_PRESENCE_WAKE_COOLDOWN_SECONDS = 8
        const val DEFAULT_RESPECT_MANUAL_SCREEN_OFF = true
        const val DEFAULT_DEEP_SLEEP_ENABLED = false
        const val DEFAULT_ILLUMINANCE_REPORTING_ENABLED = false
        const val DEFAULT_ILLUMINANCE_MIN_DELTA_LUX = 10
        const val MIN_ILLUMINANCE_MIN_DELTA_LUX = 1
        const val MAX_ILLUMINANCE_MIN_DELTA_LUX = 10000
        const val DEFAULT_ILLUMINANCE_DEBOUNCE_SECONDS = 10
        const val MIN_ILLUMINANCE_DEBOUNCE_SECONDS = 1
        const val MAX_ILLUMINANCE_DEBOUNCE_SECONDS = 3600
        const val DEFAULT_AUTO_DISCOVER_HOME_ASSISTANT = false
        const val DEFAULT_MQTT_ENABLED = false
        const val DEFAULT_MQTT_BROKER_HOST = ""
        const val DEFAULT_MQTT_BROKER_PORT = 1883
        const val DEFAULT_MQTT_USE_TLS = false
        const val DEFAULT_MQTT_USERNAME = ""
        const val DEFAULT_LOCAL_CONTROL_ENABLED = false
        const val DEFAULT_LOCAL_CONTROL_PORT = 8099
        const val DEFAULT_LOCAL_CONTROL_PREVIEW_ENABLED = false

        const val MIN_RELOAD_INTERVAL_SECONDS = 5
        const val MAX_RELOAD_INTERVAL_SECONDS = 600
        const val MIN_WATCHDOG_PING_INTERVAL_SECONDS = 15
        const val MAX_WATCHDOG_PING_INTERVAL_SECONDS = 1800
        const val MIN_UPDATE_CHECK_INTERVAL_HOURS = 1
        const val MAX_UPDATE_CHECK_INTERVAL_HOURS = 168
        const val MIN_ADMIN_UNLOCK_TIMEOUT_MINUTES = 1
        const val MAX_ADMIN_UNLOCK_TIMEOUT_MINUTES = 240
        const val MIN_HOUR = 0
        const val MAX_HOUR = 23
        const val MIN_MINUTE = 0
        const val MAX_MINUTE = 59
        const val MIN_AMBIENT_DIM_AFTER_SECONDS = 15
        const val MAX_AMBIENT_DIM_AFTER_SECONDS = 3600
        const val MIN_AMBIENT_BRIGHTNESS_PERCENT = 5
        const val MAX_AMBIENT_BRIGHTNESS_PERCENT = 100
        const val MIN_AMBIENT_PHOTO_INTERVAL_SECONDS = 10
        const val MAX_AMBIENT_PHOTO_INTERVAL_SECONDS = 3600
        const val MIN_PRESENCE_WAKE_COOLDOWN_SECONDS = 2
        const val MAX_PRESENCE_WAKE_COOLDOWN_SECONDS = 60
        const val MIN_LOCAL_CONTROL_PORT = 1024
        const val MAX_LOCAL_CONTROL_PORT = 65535
        const val MIN_MQTT_BROKER_PORT = 1
        const val MAX_MQTT_BROKER_PORT = 65535
        const val MAX_AMBIENT_MEDIA_URL_LENGTH = 2_048

        internal fun shouldAutoDiscoverHomeAssistant(enabled: Boolean, baseUrl: String): Boolean {
            // Discovery is startup setup, not permission to replace a chosen GlassHome/HA server.
            return enabled && normalizeBaseUrl(baseUrl).trimEnd('/')
                .equals(DEFAULT_HOME_ASSISTANT_URL, ignoreCase = true)
        }

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().ifBlank { DEFAULT_HOME_ASSISTANT_URL }
            val withScheme = if (
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return try {
                Uri.parse(withScheme).normalizeScheme().toString()
            } catch (_: Exception) {
                withScheme
            }
        }

        fun normalizeDashboardPath(value: String): String {
            return value.trim().trimStart('/')
        }

        fun isHttpOrHttpsUrl(url: String): Boolean {
            return try {
                val uri = URI(url)
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank() && uri.port in -1..65535
            } catch (_: Exception) {
                false
            }
        }

        fun isValidAmbientMediaUrl(value: String): Boolean {
            val candidate = value.trim()
            if (candidate.isBlank() || candidate.length > MAX_AMBIENT_MEDIA_URL_LENGTH) return false
            return runCatching {
                val uri = URI(candidate)
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            }.getOrDefault(false)
        }

        fun isValidRepoSlug(value: String): Boolean {
            return Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(value.trim())
        }

        private fun migrateRepoSlug(value: String): String {
            val normalized = value.trim()
            return if (normalized.equals(LEGACY_UPDATES_REPO, ignoreCase = true)) {
                DEFAULT_UPDATES_REPO
            } else {
                normalized
            }
        }

        fun clampInt(value: Int, minValue: Int, maxValue: Int): Int {
            return value.coerceIn(minValue, maxValue)
        }

        fun parseSettingsJson(rawJson: String): Result<JSONObject> {
            return runCatching { JSONObject(rawJson) }
        }

        private fun createPbkdf2Hash(rawPassword: String): String {
            val salt = ByteArray(PBKDF2_SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val derived = pbkdf2(rawPassword, salt, PBKDF2_ITERATIONS)
            val encodedSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
            val encodedHash = Base64.encodeToString(derived, Base64.NO_WRAP)
            return "$PASSWORD_HASH_PREFIX:$PBKDF2_ITERATIONS:$encodedSalt:$encodedHash"
        }

        private fun verifyPbkdf2Password(stored: String, rawPassword: String): Boolean {
            val parts = stored.split(':')
            if (parts.size != 4 || parts[0] != PASSWORD_HASH_PREFIX) {
                return false
            }

            val iterations = parts[1].toIntOrNull() ?: return false
            val salt = runCatching { Base64.decode(parts[2], Base64.DEFAULT) }.getOrNull()
                ?: return false
            val expected = runCatching { Base64.decode(parts[3], Base64.DEFAULT) }.getOrNull()
                ?: return false

            val candidate = pbkdf2(rawPassword, salt, iterations)
            return MessageDigest.isEqual(candidate, expected)
        }

        private fun parsePbkdf2Iterations(stored: String): Int? {
            val parts = stored.split(':')
            if (parts.size != 4 || parts[0] != PASSWORD_HASH_PREFIX) {
                return null
            }
            return parts[1].toIntOrNull()
        }

        private fun pbkdf2(rawPassword: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(rawPassword.toCharArray(), salt, iterations, PBKDF2_KEY_LENGTH_BITS)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun hashLegacyPassword(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
