package io.github.rvbcrs.wallmode

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class MqttBrokerIdentity(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String
)

internal data class ActionCardAction(
    val id: String,
    val label: String
)

internal data class DashboardState(
    val currentPage: String,
    val displayMode: String,
    val screensaverActive: Boolean,
    val dashboardProblem: Boolean,
    val ambientBrightness: Int,
    val ambientTimeout: Int,
    val ambientScene: String,
    val activeProfile: String,
    val profileScheduleEnabled: Boolean,
    val browserEngine: String,
    val takeoverActive: Boolean,
    val takeoverId: String,
    val takeoverPriority: Int
)

internal data class WallModeMqttState(
    val batteryPercent: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    val screensaverActive: Boolean,
    val dashboardProblem: Boolean,
    val currentPage: String,
    val health: String,
    val webViewVersion: String,
    val appVersion: String,
    val ambientBrightness: Int,
    val ambientTimeout: Int,
    val ambientScene: String,
    val activeProfile: String,
    val profileScheduleEnabled: Boolean,
    val browserEngine: String,
    val displayMode: String,
    val networkConnected: Boolean,
    val takeoverActive: Boolean,
    val takeoverId: String,
    val takeoverPriority: Int,
    val illuminanceLux: Float?
)

internal sealed interface WallModeMqttCommand {
    data object Reload : WallModeMqttCommand
    data object RestartEngine : WallModeMqttCommand
    data class OpenUrl(val url: String) : WallModeMqttCommand
    data class ShowTakeover(
        val id: String,
        val url: String,
        val seconds: Int,
        val priority: Int
    ) : WallModeMqttCommand
    data class ShowActionCard(
        val id: String,
        val title: String,
        val message: String,
        val actions: List<ActionCardAction>,
        val seconds: Int,
        val priority: Int
    ) : WallModeMqttCommand
    data class Announce(val text: String, val volume: Int) : WallModeMqttCommand
    data class ShowBanner(val notice: BannerNotice) : WallModeMqttCommand
    data class ClearBanner(val id: String) : WallModeMqttCommand
    data object StopAnnouncement : WallModeMqttCommand
    data class ClearTakeover(val id: String) : WallModeMqttCommand
    data class SetAmbientBrightness(val percent: Int) : WallModeMqttCommand
    data class SetAmbientTimeout(val seconds: Int) : WallModeMqttCommand
    data class SetAmbientScene(val scene: AmbientScene) : WallModeMqttCommand
    data class OpenProfile(val profile: DashboardProfile) : WallModeMqttCommand
    data class SetProfileSchedule(val enabled: Boolean) : WallModeMqttCommand
    data class SetBrowserEngine(val engine: BrowserEngine) : WallModeMqttCommand
    data class SetDisplayMode(val mode: String) : WallModeMqttCommand
}

internal object MqttContract {
    const val ACTION_EVENT_SUFFIX = "event/action"
    const val ACTION_EVENT_TYPE = "action"
    // HA's MQTT notify supplies the message as `value`; JSON encoding preserves quotes and Unicode.
    const val ANNOUNCEMENT_COMMAND_TEMPLATE = "{{ {'text': value} | to_json }}"
    const val DISPLAY_DASHBOARD = "Dashboard"
    const val DISPLAY_DIM = "Dim"
    const val DISPLAY_SCREENSAVER = "Clock + weather"
    const val DISPLAY_OFF = "Off"
    const val ENGINE_WEBVIEW = "Android WebView"
    const val ENGINE_CHROMIUM = "Chromium-like"
    const val SCENE_AURORA_WEATHER = "Aurora weather"
    const val SCENE_GLOW_CLOCK = "Glow clock"
    const val PROFILE_MAIN = "Main"
    const val PROFILE_HOME = "Home"
    const val PROFILE_WALL = "Wall"
    const val PROFILE_NIGHT = "Night"

    fun ambientSceneName(scene: AmbientScene): String = when (scene) {
        AmbientScene.AURORA_WEATHER -> SCENE_AURORA_WEATHER
        AmbientScene.GLOW_CLOCK -> SCENE_GLOW_CLOCK
    }

    fun profileName(profile: DashboardProfile?): String = when (profile) {
        DashboardProfile.HOME -> PROFILE_HOME
        DashboardProfile.WALL -> PROFILE_WALL
        DashboardProfile.NIGHT -> PROFILE_NIGHT
        null -> PROFILE_MAIN
    }

    fun isValidBroker(host: String, port: Int): Boolean {
        val normalized = host.trim()
        if (normalized.isBlank() || normalized.length > 253 || port !in 1..65_535) return false
        if (normalized.any(Char::isWhitespace) || normalized.contains('/') || normalized.contains('@')) {
            return false
        }
        return runCatching {
            val uri = URI("mqtt://$normalized")
            !uri.host.isNullOrBlank() && uri.port == -1 && uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null
        }.getOrDefault(false)
    }

    fun serverUri(host: String, port: Int, tls: Boolean): String {
        require(isValidBroker(host, port)) { "Invalid MQTT broker" }
        return "${if (tls) "ssl" else "tcp"}://${host.trim()}:$port"
    }

    fun brokerIdentity(host: String, port: Int, tls: Boolean, username: String): MqttBrokerIdentity {
        return MqttBrokerIdentity(host.trim().lowercase(), port, tls, username.trim())
    }

    fun deviceId(rawAndroidId: String?): String {
        val suffix = rawAndroidId.orEmpty()
            .lowercase()
            .filter(Char::isLetterOrDigit)
            .takeLast(16)
        require(suffix.isNotBlank()) { "Missing MQTT device identifier" }
        return "wallmode_$suffix"
    }

    fun safePage(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        return runCatching {
            val source = URI(url)
            require(source.scheme.equals("http", true) || source.scheme.equals("https", true))
            require(!source.host.isNullOrBlank())
            URI(
                source.scheme.lowercase(),
                null,
                source.host,
                source.port,
                source.path,
                null,
                null
            ).toString().take(MAX_PAGE_LENGTH)
        }.getOrDefault("-")
    }

    fun parseCommand(suffix: String, payload: String, retained: Boolean): WallModeMqttCommand? {
        if (retained || payload.length > MAX_COMMAND_LENGTH) return null
        return when (suffix) {
            "reload" -> WallModeMqttCommand.Reload.takeIf { payload == "PRESS" }
            "restart_engine" -> WallModeMqttCommand.RestartEngine.takeIf { payload == "PRESS" }
            "open_url" -> parseUrl(payload)?.let(WallModeMqttCommand::OpenUrl)
            "takeover" -> parseTakeover(payload)
            "announce" -> parseAnnouncement(payload)
            "banner" -> BannerNotice.parseCommand(payload)
            "stop_announcement" -> WallModeMqttCommand.StopAnnouncement.takeIf { payload == "PRESS" }
            "ambient_brightness" -> payload.toIntOrNull()
                ?.takeIf { it in KioskPreferences.MIN_AMBIENT_BRIGHTNESS_PERCENT..
                    KioskPreferences.MAX_AMBIENT_BRIGHTNESS_PERCENT }
                ?.let(WallModeMqttCommand::SetAmbientBrightness)
            "ambient_timeout" -> payload.toIntOrNull()
                ?.takeIf { it in KioskPreferences.MIN_AMBIENT_DIM_AFTER_SECONDS..
                    KioskPreferences.MAX_AMBIENT_DIM_AFTER_SECONDS }
                ?.let(WallModeMqttCommand::SetAmbientTimeout)
            "ambient_scene" -> when (payload) {
                SCENE_AURORA_WEATHER -> WallModeMqttCommand.SetAmbientScene(AmbientScene.AURORA_WEATHER)
                SCENE_GLOW_CLOCK -> WallModeMqttCommand.SetAmbientScene(AmbientScene.GLOW_CLOCK)
                else -> null
            }
            "profile" -> when (payload) {
                PROFILE_HOME -> WallModeMqttCommand.OpenProfile(DashboardProfile.HOME)
                PROFILE_WALL -> WallModeMqttCommand.OpenProfile(DashboardProfile.WALL)
                PROFILE_NIGHT -> WallModeMqttCommand.OpenProfile(DashboardProfile.NIGHT)
                else -> null
            }
            "profile_schedule" -> when (payload) {
                "ON" -> WallModeMqttCommand.SetProfileSchedule(true)
                "OFF" -> WallModeMqttCommand.SetProfileSchedule(false)
                else -> null
            }
            "browser_engine" -> when (payload) {
                ENGINE_WEBVIEW -> WallModeMqttCommand.SetBrowserEngine(BrowserEngine.WEBVIEW)
                ENGINE_CHROMIUM -> WallModeMqttCommand.SetBrowserEngine(BrowserEngine.CHROMIUM_CUSTOM_TAB)
                else -> null
            }
            "display_mode" -> payload
                .takeIf {
                    it == DISPLAY_DASHBOARD || it == DISPLAY_DIM ||
                        it == DISPLAY_SCREENSAVER || it == DISPLAY_OFF
                }
                ?.let(WallModeMqttCommand::SetDisplayMode)
            else -> null
        }
    }

    fun isPayloadSizeAllowed(payload: ByteArray): Boolean {
        return payload.size <= MAX_COMMAND_PAYLOAD_BYTES
    }

    fun actionResponsePayload(cardId: String, actionId: String, eventId: String): String? {
        if (!TAKEOVER_ID.matches(cardId) || !ACTION_ID.matches(actionId) ||
            !EVENT_ID.matches(eventId)
        ) {
            return null
        }
        return """{"event_type":"$ACTION_EVENT_TYPE","card_id":"$cardId","action_id":"$actionId","event_id":"$eventId"}"""
    }

    internal fun validatedTakeover(
        action: String,
        id: String,
        url: String?,
        seconds: Int?,
        priority: Int?
    ): WallModeMqttCommand? {
        if (!TAKEOVER_ID.matches(id)) return null
        return when (action) {
            "show" -> {
                val validUrl = parseUrl(url.orEmpty()) ?: return null
                val validSeconds = seconds ?: DEFAULT_TAKEOVER_SECONDS
                val validPriority = priority ?: DEFAULT_TAKEOVER_PRIORITY
                if (validSeconds !in MIN_TAKEOVER_SECONDS..MAX_TAKEOVER_SECONDS ||
                    validPriority !in MIN_TAKEOVER_PRIORITY..MAX_TAKEOVER_PRIORITY
                ) {
                    null
                } else {
                    WallModeMqttCommand.ShowTakeover(id, validUrl, validSeconds, validPriority)
                }
            }
            "clear" -> WallModeMqttCommand.ClearTakeover(id)
            else -> null
        }
    }

    private fun parseTakeover(payload: String): WallModeMqttCommand? {
        return runCatching {
            val json = JSONObject(payload)
            val action = json.opt("action") as? String ?: return null
            val id = json.opt("id") as? String ?: return null
            val url = json.opt("url") as? String
            val seconds = json.strictIntOrNull("ttl")
            val priority = json.strictIntOrNull("priority")
            if ((json.has("ttl") && seconds == null) ||
                (json.has("priority") && priority == null)
            ) {
                null
            } else if (action == "clear") {
                validatedTakeover(action, id, url, seconds, priority)
            } else if (action == "show" && json.opt("kind") == "card") {
                parseActionCardTakeover(json, id, seconds, priority)
            } else if (json.has("kind")) {
                null
            } else {
                validatedTakeover(action, id, url, seconds, priority)
            }
        }.getOrNull()
    }

    private fun parseAnnouncement(payload: String): WallModeMqttCommand? {
        return runCatching {
            val json = JSONObject(payload)
            val text = json.opt("text") as? String ?: return null
            val volume = if (json.has("volume")) {
                json.strictIntOrNull("volume") ?: return null
            } else {
                null
            }
            validatedAnnouncementCommand(text, volume)
        }.getOrNull()
    }

    internal fun validatedAnnouncementCommand(
        text: String,
        volume: Int?
    ): WallModeMqttCommand.Announce? {
        return validatedAnnouncement(text, volume ?: DEFAULT_ANNOUNCEMENT_VOLUME)?.let {
            WallModeMqttCommand.Announce(it.text, it.volume)
        }
    }

    private fun parseActionCardTakeover(
        json: JSONObject,
        id: String,
        seconds: Int?,
        priority: Int?
    ): WallModeMqttCommand? {
        if (!TAKEOVER_ID.matches(id) || json.has("url")) return null
        val title = json.opt("title") as? String ?: return null
        val message = if (json.has("message")) {
            json.opt("message") as? String ?: return null
        } else {
            ""
        }
        val rawActions = json.opt("actions") as? JSONArray ?: return null
        if (rawActions.length() !in 1..MAX_CARD_ACTIONS) return null
        val actions = mutableListOf<ActionCardAction>()
        repeat(rawActions.length()) { index ->
            val rawAction = rawActions.opt(index) as? JSONObject ?: return null
            val actionId = rawAction.opt("id") as? String ?: return null
            val label = rawAction.opt("label") as? String ?: return null
            actions += ActionCardAction(actionId, label)
        }
        return validatedActionCardTakeover(id, title, message, actions, seconds, priority)
    }

    internal fun validatedActionCardTakeover(
        id: String,
        title: String,
        message: String,
        actions: List<ActionCardAction>,
        seconds: Int?,
        priority: Int?
    ): WallModeMqttCommand? {
        if (!TAKEOVER_ID.matches(id) || actions.size !in 1..MAX_CARD_ACTIONS) return null
        val validTitle = normalizedText(title, 1, MAX_CARD_TITLE_LENGTH) ?: return null
        val validMessage = normalizedText(message, 0, MAX_CARD_MESSAGE_LENGTH) ?: return null
        val seenIds = mutableSetOf<String>()
        val validActions = actions.map { action ->
            val label = normalizedText(action.label, 1, MAX_CARD_ACTION_LABEL_LENGTH) ?: return null
            if (!ACTION_ID.matches(action.id) || !seenIds.add(action.id)) return null
            ActionCardAction(action.id, label)
        }
        val validSeconds = seconds ?: DEFAULT_TAKEOVER_SECONDS
        val validPriority = priority ?: DEFAULT_TAKEOVER_PRIORITY
        if (validSeconds !in MIN_TAKEOVER_SECONDS..MAX_TAKEOVER_SECONDS ||
            validPriority !in MIN_TAKEOVER_PRIORITY..MAX_TAKEOVER_PRIORITY
        ) {
            return null
        }
        return WallModeMqttCommand.ShowActionCard(
            id = id,
            title = validTitle,
            message = validMessage,
            actions = validActions,
            seconds = validSeconds,
            priority = validPriority
        )
    }

    internal fun normalizedText(raw: String, minLength: Int, maxLength: Int): String? {
        if (raw.any(Char::isISOControl)) return null
        val value = raw.trim()
        return value.takeIf { it.length in minLength..maxLength }
    }

    internal fun JSONObject.strictIntOrNull(name: String): Int? {
        return when (val value = opt(name)) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }
    }

    private fun parseUrl(raw: String): String? {
        val candidate = raw.trim()
        if (candidate.isBlank() || candidate.length > MAX_URL_LENGTH) return null
        return runCatching {
            val uri = URI(candidate)
            candidate.takeIf {
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            }
        }.getOrNull()
    }

    private const val MAX_PAGE_LENGTH = 255
    private const val MAX_URL_LENGTH = 2_048
    private const val MAX_COMMAND_LENGTH = 2_048
    private const val MAX_COMMAND_PAYLOAD_BYTES = MAX_COMMAND_LENGTH * 4
    private const val MAX_CARD_TITLE_LENGTH = 80
    private const val MAX_CARD_MESSAGE_LENGTH = 300
    private const val MAX_CARD_ACTION_LABEL_LENGTH = 24
    private const val MAX_CARD_ACTIONS = 3
    const val MIN_TAKEOVER_SECONDS = 5
    const val MAX_TAKEOVER_SECONDS = 600
    const val DEFAULT_TAKEOVER_SECONDS = 30
    const val MIN_TAKEOVER_PRIORITY = 0
    const val MAX_TAKEOVER_PRIORITY = 100
    const val DEFAULT_TAKEOVER_PRIORITY = 50
    const val DEFAULT_ANNOUNCEMENT_VOLUME = 80
    internal val TAKEOVER_ID = Regex("[A-Za-z0-9_-]{1,64}")
    internal val ACTION_ID = Regex("[A-Za-z0-9_-]{1,32}")
    private val EVENT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

internal class MqttManager(
    deviceKey: String,
    private val settings: KioskSettings,
    private val password: String,
    private val appVersion: String,
    private val onCommand: (WallModeMqttCommand) -> Unit,
    private val onConnectionState: (String) -> Unit,
    private val onStateRequested: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deviceId = MqttContract.deviceId(deviceKey)
    private val deviceSuffix = deviceId.removePrefix("wallmode_")
    private val baseTopic = "wallmode/$deviceSuffix"
    private val availabilityTopic = "$baseTopic/availability"
    private val stateTopic = "$baseTopic/state"
    private val motionTopic = "$baseTopic/event/motion"
    private val actionEventTopic = "$baseTopic/${MqttContract.ACTION_EVENT_SUFFIX}"
    private val commandPrefix = "$baseTopic/command/"
    private val discoveryTopic = "homeassistant/device/$deviceId/config"
    private val serverUri = MqttContract.serverUri(
        settings.mqttBrokerHost,
        settings.mqttBrokerPort,
        settings.mqttUseTls
    )
    private val client = MqttAsyncClient(serverUri, "wm_$deviceSuffix", MemoryPersistence())
    private val discoveryPayload = buildDiscoveryPayload()
    @Volatile private var stopped = false
    @Volatile private var connecting = false
    @Volatile private var closed = false
    private var retrySeconds = INITIAL_RETRY_SECONDS
    private var retryRunnable: Runnable? = null

    @Volatile
    private var lastState: WallModeMqttState? = null
    // Last state JSON actually published, for whole-payload dedup.
    private var lastStatePayload: String? = null

    init {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (stopped) {
                    closeClient()
                    return
                }
                connecting = false
                retrySeconds = INITIAL_RETRY_SECONDS
                cancelRetry()
                subscribe()
                publish(discoveryTopic, discoveryPayload, retained = true)
                publish(availabilityTopic, "online", retained = true)
                lastState?.let { publishStateInternal(it, force = true) }
                onConnectionState("connected")
                onStateRequested()
            }

            override fun connectionLost(cause: Throwable?) {
                connecting = false
                if (stopped) return
                onConnectionState("reconnecting")
                scheduleRetry()
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                if (stopped) return
                val rawPayload = message.payload
                if (!MqttContract.isPayloadSizeAllowed(rawPayload)) return
                val payload = rawPayload.toString(Charsets.UTF_8)
                if (topic == HA_STATUS_TOPIC) {
                    if (payload == "online") {
                        publish(discoveryTopic, discoveryPayload, retained = true)
                        publish(availabilityTopic, "online", retained = true)
                        onStateRequested()
                    }
                    return
                }
                if (!topic.startsWith(commandPrefix)) return
                MqttContract.parseCommand(
                    topic.removePrefix(commandPrefix),
                    payload,
                    message.isRetained
                )?.let(onCommand)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
    }

    fun matches(candidate: KioskSettings, candidatePassword: String): Boolean {
        return MqttContract.brokerIdentity(
            settings.mqttBrokerHost,
            settings.mqttBrokerPort,
            settings.mqttUseTls,
            settings.mqttUsername
        ) == MqttContract.brokerIdentity(
            candidate.mqttBrokerHost,
            candidate.mqttBrokerPort,
            candidate.mqttUseTls,
            candidate.mqttUsername
        ) &&
            password == candidatePassword
    }

    fun start() {
        stopped = false
        connect()
    }

    fun retryInitialConnection() {
        if (!stopped && !closed && !client.isConnected && !connecting) connect()
    }

    fun publishState(state: WallModeMqttState) {
        publishStateInternal(state, force = false)
    }

    fun publishMotion() {
        publish(motionTopic, "ON", retained = false)
    }

    fun publishActionResponse(cardId: String, actionId: String): Boolean {
        val payload = MqttContract.actionResponsePayload(
            cardId,
            actionId,
            UUID.randomUUID().toString()
        ) ?: return false
        return publish(actionEventTopic, payload, retained = false)
    }

    fun stop(removeDiscovery: Boolean = false) {
        stopped = true
        cancelRetry()
        if (client.isConnected && removeDiscovery) {
            runCatching {
                client.publish(discoveryTopic, byteArrayOf(), QOS, true).waitForCompletion(500)
            }.onFailure { Log.w(TAG, "MQTT discovery cleanup failed", it) }
        }
        if (client.isConnected) {
            runCatching {
                client.publish(
                    availabilityTopic,
                    "offline".toByteArray(),
                    QOS,
                    true
                ).waitForCompletion(500)
            }.onFailure { Log.w(TAG, "MQTT offline publish failed", it) }
        }
        closeClient()
    }

    private fun connect() {
        if (stopped || closed || connecting || client.isConnected) return
        connecting = true
        onConnectionState("connecting")
        val options = MqttConnectOptions().apply {
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            isCleanSession = true
            isAutomaticReconnect = false
            connectionTimeout = 10
            keepAliveInterval = 60
            setWill(availabilityTopic, "offline".toByteArray(), QOS, true)
            if (settings.mqttUsername.isNotBlank()) {
                userName = settings.mqttUsername
                if (this@MqttManager.password.isNotEmpty()) {
                    password = this@MqttManager.password.toCharArray()
                }
            }
        }
        runCatching {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    connecting = false
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    connecting = false
                    if (stopped) return
                    onConnectionState("retry in ${retrySeconds}s")
                    scheduleRetry()
                }
            })
        }.onFailure {
            connecting = false
            if (stopped) return@onFailure
            onConnectionState("retry in ${retrySeconds}s")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (stopped || closed || retryRunnable != null) return
        val delayMillis = retrySeconds * 1_000L
        retrySeconds = (retrySeconds * 2).coerceAtMost(MAX_RETRY_SECONDS)
        retryRunnable = Runnable {
            retryRunnable = null
            connect()
        }.also { mainHandler.postDelayed(it, delayMillis) }
    }

    private fun cancelRetry() {
        retryRunnable?.let(mainHandler::removeCallbacks)
        retryRunnable = null
    }

    private fun subscribe() {
        runCatching {
            client.subscribe(
                arrayOf(HA_STATUS_TOPIC, "$commandPrefix#"),
                intArrayOf(0, QOS)
            )
        }.onFailure { Log.w(TAG, "MQTT subscribe failed", it) }
    }

    /**
     * Serializes a state snapshot to the single retained JSON state payload.
     * Illuminance is omitted when null so HA shows "unavailable" rather than a
     * stale zero.
     */
    private fun stateJson(state: WallModeMqttState): String {
        return JSONObject().apply {
            put("battery", state.batteryPercent)
            put("charging", state.charging)
            put("screen_on", state.screenOn)
            put("screensaver_active", state.screensaverActive)
            put("dashboard_problem", state.dashboardProblem)
            put("current_page", MqttContract.safePage(state.currentPage))
            put("health", state.health.take(255))
            put("webview_version", state.webViewVersion.take(255))
            put("app_version", state.appVersion.take(255))
            put("ambient_brightness", state.ambientBrightness)
            put("ambient_timeout", state.ambientTimeout)
            put("ambient_scene", state.ambientScene)
            put("active_profile", state.activeProfile)
            put("profile_schedule_enabled", state.profileScheduleEnabled)
            put("browser_engine", state.browserEngine)
            put("display_mode", state.displayMode)
            put("network_connected", state.networkConnected)
            put("takeover_active", state.takeoverActive)
            put("takeover_id", state.takeoverId)
            put("takeover_priority", state.takeoverPriority)
            state.illuminanceLux?.let { put("illuminance", (it * 10).toInt() / 10.0) }
        }.toString()
    }

    /**
     * Publishes the full retained state JSON, but only when it differs from the
     * last payload published (dedup) — so an unchanged periodic tick or a
     * no-op dashboard-state update does not republish. [force] always publishes,
     * used on (re)connect to refresh the retained topic after a broker restart.
     *
     * Illuminance throttling happens upstream: [IlluminanceReportGate] only emits
     * a publish request after its delta/debounce gate passes, so lux jitter does
     * not drive this, while discrete flag changes publish as soon as they occur.
     */
    private fun publishStateInternal(state: WallModeMqttState, force: Boolean) {
        val payload = stateJson(state)
        lastState = state
        if (!force && payload == lastStatePayload) return
        if (publish(stateTopic, payload, retained = true)) {
            lastStatePayload = payload
        }
    }

    private fun publish(topic: String, payload: String, retained: Boolean): Boolean {
        if (stopped || !client.isConnected) return false
        return runCatching {
            client.publish(topic, payload.toByteArray(), QOS, retained)
            true
        }.getOrElse {
            Log.w(TAG, "MQTT publish failed for $topic", it)
            false
        }
    }

    @Synchronized
    private fun closeClient() {
        if (closed) return
        runCatching { client.disconnectForcibly(250, 250, false) }
            .onFailure { Log.w(TAG, "MQTT disconnect failed", it) }
        runCatching { client.close(true) }
            .onSuccess { closed = true }
            .onFailure { Log.w(TAG, "MQTT close failed", it) }
    }

    internal fun buildDiscoveryPayload(): String {
        val components = JSONObject()
        fun component(id: String, platform: String, name: String): JSONObject {
            return JSONObject().apply {
                put("platform", platform)
                put("unique_id", "${deviceId}_$id")
                put("name", name)
            }
        }
        fun stateComponent(id: String, platform: String, name: String, value: String): JSONObject {
            return component(id, platform, name).apply {
                put("state_topic", stateTopic)
                put("value_template", "{{ value_json.$value }}")
            }
        }

        components.put("battery", stateComponent("battery", "sensor", "Battery", "battery").apply {
            put("device_class", "battery")
            put("unit_of_measurement", "%")
            put("state_class", "measurement")
        })
        components.put("illuminance", stateComponent("illuminance", "sensor", "Illuminance", "illuminance").apply {
            put("device_class", "illuminance")
            put("unit_of_measurement", "lx")
            put("state_class", "measurement")
            // Absent key → empty state → HA shows "unavailable" rather than 0.
            put("value_template", "{{ value_json.illuminance | default('', true) }}")
        })
        components.put("charging", component("charging", "binary_sensor", "Charging").apply {
            put("device_class", "battery_charging")
            put("entity_category", "diagnostic")
            put("state_topic", stateTopic)
            put("value_template", "{{ 'ON' if value_json.charging else 'OFF' }}")
        })
        components.put("screen_on", component("screen_on", "binary_sensor", "Screen on").apply {
            put("icon", "mdi:monitor")
            put("state_topic", stateTopic)
            put("value_template", "{{ 'ON' if value_json.screen_on else 'OFF' }}")
        })
        components.put(
            "screensaver_active",
            component("screensaver_active", "binary_sensor", "Screensaver active").apply {
                put("icon", "mdi:weather-night")
                put("state_topic", stateTopic)
                put("value_template", "{{ 'ON' if value_json.screensaver_active else 'OFF' }}")
            }
        )
        components.put("dashboard_problem", component("dashboard_problem", "binary_sensor", "Dashboard problem").apply {
            put("device_class", "problem")
            put("state_topic", stateTopic)
            put("value_template", "{{ 'ON' if value_json.dashboard_problem else 'OFF' }}")
        })
        components.put("network", component("network", "binary_sensor", "Network").apply {
            put("device_class", "connectivity")
            put("entity_category", "diagnostic")
            put("state_topic", stateTopic)
            put("value_template", "{{ 'ON' if value_json.network_connected else 'OFF' }}")
        })
        components.put("takeover_active", component("takeover_active", "binary_sensor", "Takeover active").apply {
            put("icon", "mdi:monitor-eye")
            put("state_topic", stateTopic)
            put("value_template", "{{ 'ON' if value_json.takeover_active else 'OFF' }}")
        })
        components.put("motion", component("motion", "binary_sensor", "Motion").apply {
            put("device_class", "motion")
            put("state_topic", motionTopic)
            // Longer than the service-side motion publish cooldown so sustained
            // presence (republished each cooldown window) keeps the HA sensor
            // solidly ON instead of flapping OFF between throttled republishes.
            put("off_delay", 35)
        })
        components.put("current_page", stateComponent("current_page", "sensor", "Current page", "current_page").apply {
            put("icon", "mdi:web")
            put("entity_category", "diagnostic")
        })
        components.put("health", stateComponent("health", "sensor", "Health", "health").apply {
            put("icon", "mdi:heart-pulse")
            put("entity_category", "diagnostic")
        })
        components.put(
            "webview_version",
            stateComponent("webview_version", "sensor", "WebView version", "webview_version").apply {
                put("icon", "mdi:google-chrome")
                put("entity_category", "diagnostic")
            }
        )
        components.put("app_version", stateComponent("app_version", "sensor", "App version", "app_version").apply {
            put("icon", "mdi:application-cog-outline")
            put("entity_category", "diagnostic")
        })
        components.put("active_profile", stateComponent("active_profile", "sensor", "Dashboard profile", "active_profile").apply {
            put("icon", "mdi:view-dashboard-outline")
        })
        components.put("profile_schedule", component("profile_schedule", "switch", "Profile schedule").apply {
            put("icon", "mdi:calendar-clock")
            put("entity_category", "config")
            put("state_topic", stateTopic)
            put("value_template", "{{ 'ON' if value_json.profile_schedule_enabled else 'OFF' }}")
            put("command_topic", "${commandPrefix}profile_schedule")
        })
        listOf(
            "profile_home" to MqttContract.PROFILE_HOME,
            "profile_wall" to MqttContract.PROFILE_WALL,
            "profile_night" to MqttContract.PROFILE_NIGHT
        ).forEach { (id, profile) ->
            components.put(id, component(id, "button", "Open $profile profile").apply {
                put("icon", "mdi:view-dashboard")
                put("command_topic", "${commandPrefix}profile")
                put("payload_press", profile)
            })
        }
        components.put("reload_dashboard", component("reload_dashboard", "button", "Reload dashboard").apply {
            put("icon", "mdi:reload")
            put("entity_category", "config")
            put("command_topic", "${commandPrefix}reload")
            put("payload_press", "PRESS")
        })
        components.put("restart_engine", component("restart_engine", "button", "Restart browser engine").apply {
            put("device_class", "restart")
            put("entity_category", "config")
            put("command_topic", "${commandPrefix}restart_engine")
            put("payload_press", "PRESS")
        })
        components.put(
            "ambient_brightness",
            stateComponent("ambient_brightness", "number", "Ambient brightness", "ambient_brightness").apply {
                put("icon", "mdi:brightness-6")
                put("entity_category", "config")
                put("unit_of_measurement", "%")
                put("min", KioskPreferences.MIN_AMBIENT_BRIGHTNESS_PERCENT)
                put("max", KioskPreferences.MAX_AMBIENT_BRIGHTNESS_PERCENT)
                put("step", 1)
                put("mode", "slider")
                put("command_topic", "${commandPrefix}ambient_brightness")
            }
        )
        components.put(
            "ambient_timeout",
            stateComponent("ambient_timeout", "number", "Ambient timeout", "ambient_timeout").apply {
                put("icon", "mdi:timer-outline")
                put("entity_category", "config")
                put("unit_of_measurement", "s")
                put("min", KioskPreferences.MIN_AMBIENT_DIM_AFTER_SECONDS)
                put("max", KioskPreferences.MAX_AMBIENT_DIM_AFTER_SECONDS)
                put("step", 15)
                put("mode", "box")
                put("command_topic", "${commandPrefix}ambient_timeout")
            }
        )
        components.put("ambient_scene", stateComponent("ambient_scene", "select", "Ambient scene", "ambient_scene").apply {
            put("icon", "mdi:weather-sunset")
            put("entity_category", "config")
            put("options", JSONArray(listOf(
                MqttContract.SCENE_AURORA_WEATHER,
                MqttContract.SCENE_GLOW_CLOCK
            )))
            put("command_topic", "${commandPrefix}ambient_scene")
        })
        components.put("browser_engine", stateComponent("browser_engine", "select", "Browser engine", "browser_engine").apply {
            put("icon", "mdi:web-box")
            put("entity_category", "config")
            put("options", JSONArray(listOf(MqttContract.ENGINE_WEBVIEW, MqttContract.ENGINE_CHROMIUM)))
            put("command_topic", "${commandPrefix}browser_engine")
        })
        components.put("display_mode", stateComponent("display_mode", "select", "Display mode", "display_mode").apply {
            put("icon", "mdi:monitor-dashboard")
            put("options", JSONArray(listOf(
                MqttContract.DISPLAY_DASHBOARD,
                MqttContract.DISPLAY_DIM,
                MqttContract.DISPLAY_SCREENSAVER,
                MqttContract.DISPLAY_OFF
            )))
            put("command_topic", "${commandPrefix}display_mode")
        })
        components.put("open_url", stateComponent("open_url", "text", "Dashboard URL", "current_page").apply {
            put("icon", "mdi:link-variant")
            put("entity_category", "config")
            put("mode", "text")
            put("min", 1)
            put("max", 255)
            put("command_topic", "${commandPrefix}open_url")
        })
        components.put("announce", component("announce", "notify", "Announcement").apply {
            put("icon", "mdi:bullhorn-outline")
            put("command_topic", "${commandPrefix}announce")
            put("command_template", MqttContract.ANNOUNCEMENT_COMMAND_TEMPLATE)
            put("qos", QOS)
            put("retain", false)
        })
        components.put("action_response", component("action_response", "event", "Action response").apply {
            put("device_class", "button")
            put("state_topic", actionEventTopic)
            put("event_types", JSONArray().put(MqttContract.ACTION_EVENT_TYPE))
            put("qos", QOS)
        })

        val manufacturer = Build.MANUFACTURER.takeIf { it.isNotBlank() } ?: "Android"
        val model = Build.MODEL.takeIf { it.isNotBlank() } ?: "Android tablet"
        return JSONObject().apply {
            put("device", JSONObject().apply {
                put("identifiers", JSONArray().put(deviceId))
                put("name", "WallMode $model")
                put("manufacturer", manufacturer)
                put("model", model)
                put("sw_version", appVersion)
                put("hw_version", "Android ${Build.VERSION.RELEASE}")
            })
            put("origin", JSONObject().apply {
                put("name", "WallMode")
                put("sw_version", appVersion)
                put("support_url", SUPPORT_URL)
            })
            put("availability_topic", availabilityTopic)
            put("payload_available", "online")
            put("payload_not_available", "offline")
            put("components", components)
        }.toString()
    }

    private companion object {
        const val TAG = "WallModeMqtt"
        const val QOS = 1
        const val INITIAL_RETRY_SECONDS = 5
        const val MAX_RETRY_SECONDS = 60
        const val HA_STATUS_TOPIC = "homeassistant/status"
        const val SUPPORT_URL = "https://github.com/rvbcrs/WallMode"
    }
}
