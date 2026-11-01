package io.github.rvbcrs.wallmode

import java.security.MessageDigest
import kotlin.reflect.KProperty1

/** The same fields drive rendering, parsing and stale-form detection; never reflects over settings. */
internal object LocalControlSettings {
    private data class Field(
        val name: String,
        val label: String,
        val type: String,
        val read: (KioskSettings) -> String,
        val write: (KioskSettings, String) -> KioskSettings,
        val range: IntRange? = null,
        val choices: Map<String, String> = emptyMap(),
        val maxLength: Int = KioskPreferences.MAX_AMBIENT_MEDIA_URL_LENGTH
    )

    private data class Group(val title: String, val fields: List<Field>, val help: String = "")

    private fun text(
        property: KProperty1<KioskSettings, String>, label: String, type: String = "text",
        write: (KioskSettings, String) -> KioskSettings
    ) = Field(property.name, label, type, { property.get(it) }, write)

    private fun toggle(
        property: KProperty1<KioskSettings, Boolean>, label: String,
        write: (KioskSettings, Boolean) -> KioskSettings
    ) = Field(property.name, label, "checkbox", { property.get(it).toString() }, { settings, value ->
        write(settings, value == "on")
    })

    private fun number(
        property: KProperty1<KioskSettings, Int>, label: String, range: IntRange,
        write: (KioskSettings, Int) -> KioskSettings
    ) = Field(property.name, label, "number", { property.get(it).toString() }, { settings, value ->
        write(settings, value.toInt())
    }, range = range)

    private fun <T : Enum<T>> choice(
        property: KProperty1<KioskSettings, T>, label: String, options: List<Pair<T, String>>,
        write: (KioskSettings, T) -> KioskSettings
    ) = Field(property.name, label, "select", { property.get(it).name }, { settings, value ->
        write(settings, options.first { it.first.name == value }.first)
    }, choices = options.associate { it.first.name to it.second })

    private val sections = linkedMapOf(
        "dashboard" to listOf(
            Group("Dashboard source", listOf(
                text(KioskSettings::homeAssistantUrl, "Dashboard / Home Assistant URL", "url") { s, v -> s.copy(homeAssistantUrl = v) },
                text(KioskSettings::dashboardPath, "Dashboard path") { s, v -> s.copy(dashboardPath = v) }
            )),
            Group("Loading & recovery", listOf(
                number(KioskSettings::reloadIntervalSeconds, "Reload interval (seconds)",
                    KioskPreferences.MIN_RELOAD_INTERVAL_SECONDS..KioskPreferences.MAX_RELOAD_INTERVAL_SECONDS) { s, v -> s.copy(reloadIntervalSeconds = v) },
                toggle(KioskSettings::appendKiosk, "Append ?kiosk automatically") { s, v -> s.copy(appendKiosk = v) },
                toggle(KioskSettings::autoReloadOnFailure, "Auto-reload when crashes/network failures happen") { s, v -> s.copy(autoReloadOnFailure = v) },
                toggle(KioskSettings::autoDiscoverHomeAssistant, "Auto-find Home Assistant on startup") { s, v -> s.copy(autoDiscoverHomeAssistant = v) }
            ), "Startup discovery only replaces the default homeassistant.local address, never a chosen dashboard."),
            Group("Dashboard profiles", listOf(
                toggle(KioskSettings::scheduleProfilesEnabled, "Enable scheduled profile switching") { s, v -> s.copy(scheduleProfilesEnabled = v) },
                text(KioskSettings::profileHomePath, "Home profile path") { s, v -> s.copy(profileHomePath = v) },
                number(KioskSettings::homeStartHour, "Home hour", KioskPreferences.MIN_HOUR..KioskPreferences.MAX_HOUR) { s, v -> s.copy(homeStartHour = v) },
                text(KioskSettings::profileWallPath, "Wall profile path") { s, v -> s.copy(profileWallPath = v) },
                number(KioskSettings::wallStartHour, "Wall hour", KioskPreferences.MIN_HOUR..KioskPreferences.MAX_HOUR) { s, v -> s.copy(wallStartHour = v) },
                text(KioskSettings::profileNightPath, "Night profile path") { s, v -> s.copy(profileNightPath = v) },
                number(KioskSettings::nightStartHour, "Night hour", KioskPreferences.MIN_HOUR..KioskPreferences.MAX_HOUR) { s, v -> s.copy(nightStartHour = v) }
            ), "Switches at the configured whole hour. Use three different hours. Empty profile paths use the main dashboard path.")
        ),
        "display" to listOf(
            Group("Photo clock & background", listOf(
                toggle(KioskSettings::ambientModeEnabled, "Enable inactivity mode") { s, v -> s.copy(ambientModeEnabled = v) },
                toggle(KioskSettings::ambientScreensaverEnabled, "Show screensaver instead of only dimming") { s, v -> s.copy(ambientScreensaverEnabled = v) },
                choice(KioskSettings::ambientScene, "Screensaver scene", listOf(
                    AmbientScene.AURORA_WEATHER to "Aurora weather", AmbientScene.GLOW_CLOCK to "Glow clock"
                )) { s, v -> s.copy(ambientScene = v) },
                choice(KioskSettings::ambientBackgroundMode, "Ambient background", listOf(
                    AmbientBackgroundMode.BUILT_IN to "Built-in effects",
                    AmbientBackgroundMode.BUNDLED_IMAGE to "Built-in cloud image",
                    AmbientBackgroundMode.BUNDLED_VIDEO to "Built-in cloud video",
                    AmbientBackgroundMode.IMAGE_URL to "Image URL",
                    AmbientBackgroundMode.VIDEO_URL to "Video URL",
                    AmbientBackgroundMode.IMMICH_ALBUM to "Immich album"
                )) { s, v -> s.copy(ambientBackgroundMode = v) },
                text(KioskSettings::ambientBackgroundUrl, "Direct image or video URL", "url") { s, v -> s.copy(ambientBackgroundUrl = v) },
                text(KioskSettings::ambientImmichShareUrl, "Full Immich share link", "url") { s, v -> s.copy(ambientImmichShareUrl = v) },
                number(KioskSettings::ambientPhotoIntervalSeconds, "Change photo every (seconds)",
                    KioskPreferences.MIN_AMBIENT_PHOTO_INTERVAL_SECONDS..KioskPreferences.MAX_AMBIENT_PHOTO_INTERVAL_SECONDS) { s, v -> s.copy(ambientPhotoIntervalSeconds = v) },
                text(KioskSettings::ambientWeatherLocation, "Weather location (city)") { s, v -> s.copy(ambientWeatherLocation = v) }
            ), "For a photo clock, enable inactivity mode and screensaver, choose Immich album and paste its full share link. Custom media uses direct JPEG/PNG or muted H.264 MP4 URLs. Weather is used by Aurora weather."),
            Group("Screen appearance", listOf(
                choice(KioskSettings::themeMode, "Appearance theme", listOf(
                    ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"
                )) { s, v -> s.copy(themeMode = v) },
                toggle(KioskSettings::fullscreen, "Fullscreen") { s, v -> s.copy(fullscreen = v) },
                toggle(KioskSettings::keepScreenOn, "Keep screen awake") { s, v -> s.copy(keepScreenOn = v) },
                choice(KioskSettings::adminButtonCorner, "Settings button corner", listOf(
                    AdminButtonCorner.TOP_LEFT to "Top left", AdminButtonCorner.TOP_RIGHT to "Top right",
                    AdminButtonCorner.BOTTOM_LEFT to "Bottom left", AdminButtonCorner.BOTTOM_RIGHT to "Bottom right"
                )) { s, v -> s.copy(adminButtonCorner = v) }
            )),
            Group("Idle delay & brightness", listOf(
                number(KioskSettings::ambientDimAfterSeconds, "Start after idle (seconds)",
                    KioskPreferences.MIN_AMBIENT_DIM_AFTER_SECONDS..KioskPreferences.MAX_AMBIENT_DIM_AFTER_SECONDS) { s, v -> s.copy(ambientDimAfterSeconds = v) },
                number(KioskSettings::ambientBrightnessPercent, "Fixed brightness (%)",
                    KioskPreferences.MIN_AMBIENT_BRIGHTNESS_PERCENT..KioskPreferences.MAX_AMBIENT_BRIGHTNESS_PERCENT) { s, v -> s.copy(ambientBrightnessPercent = v) },
                toggle(KioskSettings::ambientFollowSystemBrightness, "Adaptive ambient brightness") { s, v -> s.copy(ambientFollowSystemBrightness = v) }
            ), "Adaptive brightness uses the tablet's light sensor and Android automatic brightness; fixed brightness remains the fallback."),
            Group("Presence wake", listOf(
                toggle(KioskSettings::presenceWakeEnabled, "Wake on motion") { s, v -> s.copy(presenceWakeEnabled = v) },
                number(KioskSettings::presenceWakeCooldownSeconds, "Presence wake cooldown (seconds)",
                    KioskPreferences.MIN_PRESENCE_WAKE_COOLDOWN_SECONDS..KioskPreferences.MAX_PRESENCE_WAKE_COOLDOWN_SECONDS) { s, v -> s.copy(presenceWakeCooldownSeconds = v) },
                toggle(KioskSettings::deepSleepEnabled, "Turn screen off (deep sleep)") { s, v -> s.copy(deepSleepEnabled = v) }
            ), "Uses proximity and the front camera to return to the dashboard. Camera permission must be granted on the tablet. Deep sleep powers the panel off (device-owner provisioning) instead of dimming."),
            Group("Ambient light sensor", listOf(
                toggle(KioskSettings::illuminanceReportingEnabled, "Report illuminance to MQTT") { s, v -> s.copy(illuminanceReportingEnabled = v) },
                number(KioskSettings::illuminanceMinDeltaLux, "Minimum change to report (lux)",
                    KioskPreferences.MIN_ILLUMINANCE_MIN_DELTA_LUX..KioskPreferences.MAX_ILLUMINANCE_MIN_DELTA_LUX) { s, v -> s.copy(illuminanceMinDeltaLux = v) },
                number(KioskSettings::illuminanceDebounceSeconds, "Debounce between reports (seconds)",
                    KioskPreferences.MIN_ILLUMINANCE_DEBOUNCE_SECONDS..KioskPreferences.MAX_ILLUMINANCE_DEBOUNCE_SECONDS) { s, v -> s.copy(illuminanceDebounceSeconds = v) }
            ), "Publishes the device's built-in light sensor (lux) to Home Assistant over MQTT. Reports only when the reading changes by at least the minimum and the debounce has elapsed.")
        ),
        "browser" to listOf(
            Group("Browser profile", listOf(
                choice(KioskSettings::browserEngine, "Browser engine", listOf(
                    BrowserEngine.WEBVIEW to "Android WebView",
                    BrowserEngine.CHROMIUM_CUSTOM_TAB to "Chromium-like WebView profile"
                )) { s, v -> s.copy(browserEngine = v) }
            ), "Both profiles use Android WebView; no additional browser engine is installed."),
            Group("Page compatibility", listOf(
                toggle(KioskSettings::allowMixedContent, "Allow mixed content (HTTP inside HTTPS)") { s, v -> s.copy(allowMixedContent = v) },
                toggle(KioskSettings::allowThirdPartyCookies, "Allow third-party cookies") { s, v -> s.copy(allowThirdPartyCookies = v) },
                toggle(KioskSettings::autoplayEnabled, "Allow autoplay media") { s, v -> s.copy(autoplayEnabled = v) }
            )),
            Group("Desktop & user-agent", listOf(
                toggle(KioskSettings::desktopMode, "Force desktop user-agent mode") { s, v -> s.copy(desktopMode = v) },
                text(KioskSettings::customUserAgent, "Custom user-agent override (optional)") { s, v -> s.copy(customUserAgent = v) }
            ))
        ),
        "device" to listOf(
            Group("Startup & lockdown", listOf(
                toggle(KioskSettings::autoStartOnBoot, "Start WallMode on boot") { s, v -> s.copy(autoStartOnBoot = v) },
                toggle(KioskSettings::lockTaskMode, "Enable lock task kiosk mode (device owner)") { s, v -> s.copy(lockTaskMode = v) }
            ), "Device-owner provisioning and Android permission prompts still require the tablet."),
            Group("Admin access", listOf(
                toggle(KioskSettings::requirePasswordForExitOnly, "Require password for Exit only") { s, v -> s.copy(requirePasswordForExitOnly = v) },
                number(KioskSettings::adminUnlockTimeoutMinutes, "Admin unlock timeout (minutes)",
                    KioskPreferences.MIN_ADMIN_UNLOCK_TIMEOUT_MINUTES..KioskPreferences.MAX_ADMIN_UNLOCK_TIMEOUT_MINUTES) { s, v -> s.copy(adminUnlockTimeoutMinutes = v) }
            ), "Change the admin password on the Security page."),
            Group("Home Assistant MQTT device", listOf(
                toggle(KioskSettings::mqttEnabled, "Enable Home Assistant MQTT device") { s, v -> s.copy(mqttEnabled = v) },
                text(KioskSettings::mqttBrokerHost, "Broker host (example: 192.168.0.2)") { s, v -> s.copy(mqttBrokerHost = v) },
                number(KioskSettings::mqttBrokerPort, "Broker port",
                    KioskPreferences.MIN_MQTT_BROKER_PORT..KioskPreferences.MAX_MQTT_BROKER_PORT) { s, v -> s.copy(mqttBrokerPort = v) },
                toggle(KioskSettings::mqttUseTls, "Use TLS") { s, v -> s.copy(mqttUseTls = v) },
                text(KioskSettings::mqttUsername, "MQTT username (optional)") { s, v -> s.copy(mqttUsername = v) }
            ), "In Home Assistant, use notify.send_message with this device's Announcement entity to speak a message. Without TLS, MQTT credentials and messages are sent unencrypted."),
            Group("Browser Control Panel (LAN)", listOf(
                toggle(KioskSettings::localControlEnabled, "Enable browser control panel server") { s, v -> s.copy(localControlEnabled = v) },
                number(KioskSettings::localControlPort, "Control panel port",
                    KioskPreferences.MIN_LOCAL_CONTROL_PORT..KioskPreferences.MAX_LOCAL_CONTROL_PORT) { s, v -> s.copy(localControlPort = v) },
                toggle(KioskSettings::localControlPreviewEnabled, "Allow authenticated screen preview") { s, v -> s.copy(localControlPreviewEnabled = v) }
            ), "Changing the port moves this panel to the new port. Disabling the server disconnects this panel; re-enable it on the tablet.")
        ),
        "system" to listOf(
            Group("Health watchdog", listOf(
                toggle(KioskSettings::watchdogEnabled, "Enable watchdog ping & auto-recovery") { s, v -> s.copy(watchdogEnabled = v) },
                text(KioskSettings::watchdogPingPath, "Watchdog ping path (example: api/)") { s, v -> s.copy(watchdogPingPath = v) },
                number(KioskSettings::watchdogPingIntervalSeconds, "Watchdog interval (seconds)",
                    KioskPreferences.MIN_WATCHDOG_PING_INTERVAL_SECONDS..KioskPreferences.MAX_WATCHDOG_PING_INTERVAL_SECONDS) { s, v -> s.copy(watchdogPingIntervalSeconds = v) }
            )),
            Group("Daily maintenance window", listOf(
                toggle(KioskSettings::maintenanceEnabled, "Enable daily maintenance window") { s, v -> s.copy(maintenanceEnabled = v) },
                number(KioskSettings::maintenanceHour, "Maintenance hour", KioskPreferences.MIN_HOUR..KioskPreferences.MAX_HOUR) { s, v -> s.copy(maintenanceHour = v) },
                number(KioskSettings::maintenanceMinute, "Maintenance minute", KioskPreferences.MIN_MINUTE..KioskPreferences.MAX_MINUTE) { s, v -> s.copy(maintenanceMinute = v) }
            )),
            Group("App updates", listOf(
                toggle(KioskSettings::updatesEnabled, "Enable update checks") { s, v -> s.copy(updatesEnabled = v) },
                text(KioskSettings::updatesRepo, "GitHub repo (owner/repo)") { s, v -> s.copy(updatesRepo = v) },
                number(KioskSettings::updateCheckIntervalHours, "Update check interval (hours)",
                    KioskPreferences.MIN_UPDATE_CHECK_INTERVAL_HOURS..KioskPreferences.MAX_UPDATE_CHECK_INTERVAL_HOURS) { s, v -> s.copy(updateCheckIntervalHours = v) }
            ), "Android may require confirmation on the tablet before installing an update.")
        )
    )

    fun isSection(section: String): Boolean = section in sections

    internal fun fieldNames(section: String): List<String> = fields(section).map { it.name }

    private fun groups(section: String): List<Group> =
        requireNotNull(sections[section]) { "Unknown settings section." }

    private fun fields(section: String): List<Field> = groups(section).flatMap { it.fields }

    fun render(section: String, current: KioskSettings): String =
        groups(section).joinToString("\n") { group ->
            "<fieldset class=\"settings-group\"><legend>${escape(group.title)}</legend>" +
                (if (group.help.isBlank()) "" else "<p class=\"muted section-help\">${escape(group.help)}</p>") +
                "<div class=\"settings-grid\">" +
                group.fields.joinToString("\n") { field -> renderField(field, current) } + "</div></fieldset>"
        }

    private fun renderField(field: Field, current: KioskSettings): String {
        val value = escape(field.read(current))
        val id = "setting-${field.name}"
        val attributes = "id=\"$id\" name=\"${field.name}\""
        if (field.type == "checkbox") {
            val checked = if (field.read(current) == "true") " checked" else ""
            return "<label class=\"check\" for=\"$id\"><input type=\"checkbox\" $attributes value=\"on\"$checked>${escape(field.label)}</label>"
        }
        val input = when (field.type) {
            "select" -> "<select class=\"field\" $attributes>" + field.choices.entries.joinToString("") { (key, label) ->
                val selected = if (key == field.read(current)) " selected" else ""
                "<option value=\"${escape(key)}\"$selected>${escape(label)}</option>"
            } + "</select>"
            "number" -> "<input class=\"field\" type=\"number\" $attributes value=\"$value\" min=\"${field.range!!.first}\" max=\"${field.range.last}\" step=\"1\" required>"
            else -> "<input class=\"field\" type=\"${field.type}\" $attributes value=\"$value\" maxlength=\"${field.maxLength}\" autocomplete=\"off\" autocapitalize=\"none\" spellcheck=\"false\">"
        }
        val wideClass = if (field.type == "text" || field.type == "url") " setting-field-wide" else ""
        return "<div class=\"setting-field$wideClass\"><label for=\"$id\">${escape(field.label)}</label>$input</div>"
    }

    fun apply(section: String, params: Map<String, String>, current: KioskSettings): KioskSettings =
        fields(section).fold(current) { settings, field ->
            val raw = params[field.name]
            if (field.type == "checkbox") {
                require(raw == null || raw == "on") { "Invalid option for ${field.label}." }
                field.write(settings, raw.orEmpty())
            } else {
                require(raw != null) { "Missing field: ${field.label}. Reload the page and try again." }
                val value = raw.trim()
                when (field.type) {
                    "number" -> require(value.toIntOrNull()?.let { it in field.range!! } == true) {
                        "${field.label} must be between ${field.range!!.first} and ${field.range.last}."
                    }
                    "select" -> require(value in field.choices) { "Choose a valid ${field.label.lowercase()}." }
                    else -> require(value.length <= field.maxLength && value.none { it.isISOControl() }) {
                        "${field.label} is too long or contains unsupported characters."
                    }
                }
                field.write(settings, value)
            }
        }

    fun revision(section: String, current: KioskSettings): String {
        val digest = MessageDigest.getInstance("SHA-256")
        (listOf(section) + fields(section).flatMap { listOf(it.name, it.read(current)) }).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
