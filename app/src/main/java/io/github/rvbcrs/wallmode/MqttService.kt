package io.github.rvbcrs.wallmode

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background service that owns the [MqttManager] lifecycle. It survives
 * independently of [MainActivity], so MQTT stays connected even when the OS
 * kills or recreates the activity (e.g. while the screen is off).
 *
 * Communication with the activity is through [MqttBridge]:
 *  - Activity pushes UI state → bridge [MqttBridge.dashboardState] → service merges
 *    with live device readings → broker
 *  - Broker sends commands → service → bridge [MqttBridge.commands] → activity
 *
 * Device-level telemetry (battery, screen, network, illuminance) is read directly
 * by this service so it stays accurate even when the activity is not running.
 */
class MqttService : Service() {

    private lateinit var prefs: KioskPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mqttManager: MqttManager? = null
    private var periodicStateJob: Job? = null
    private var bridgeCollectionJob: Job? = null
    private var networkCallbackRegistered = false

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val powerManager: PowerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available: $network")
            mqttManager?.retryInitialConnection()
            publishNow()
        }
        override fun onLost(network: Network) {
            Log.w(TAG, "Network lost: $network")
            publishNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MQTT service created")
        prefs = KioskPreferences(this)
        registerNetworkCallback()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(
            TAG,
            "Service start requested: action=${intent?.action}, startId=$startId"
        )
        startForegroundNotification()
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Explicit stop requested")
            stopMqttManager(removeDiscovery = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        refreshMqttManager()
        // Sticky: the connection no longer depends on a visible Activity, so if
        // the OS ever kills us we want to come back and reconnect to the broker.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "MQTT service destroying")
        unregisterNetworkCallback()
        stopMqttManager(removeDiscovery = false)
        serviceScope.cancel()
        Log.i(TAG, "MQTT service destroyed")
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mqtt_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.mqtt_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.mqtt_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) {
            Log.d(TAG, "Network callback already registered")
            return
        }

        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )

            networkCallbackRegistered = true
            Log.i(TAG, "Network callback registered")

        }.onFailure {
            Log.e(TAG, "Failed to register network callback", it)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) {
            Log.d(TAG, "Network callback not registered; skipping unregister")
            return
        }

        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.i(TAG, "Network callback unregistered")
        }.onFailure {
            Log.w(TAG, "Failed to unregister network callback", it)
        }

        networkCallbackRegistered = false
    }

    private fun refreshMqttManager() {
        Log.d(TAG, "Refreshing MQTT manager")

        val settings = prefs.load()
        val storedPassword = prefs.mqttPassword()

        Log.d(
            TAG,
            "MQTT settings: enabled=${settings.mqttEnabled}, " +
                    "broker=${settings.mqttBrokerHost}:${settings.mqttBrokerPort}, " +
                    "usernameConfigured=${settings.mqttUsername.isNotBlank()}"
        )

        if (!settings.mqttEnabled) {
            Log.i(TAG, "MQTT disabled in settings")
            stopMqttManager(removeDiscovery = true)
            prefs.recordMqttState("disabled")
            return
        }

        if (!MqttContract.isValidBroker(
                settings.mqttBrokerHost,
                settings.mqttBrokerPort
            ) ||
            (storedPassword.isNotEmpty() && settings.mqttUsername.isBlank())
        ) {
            Log.w(TAG, "Invalid MQTT configuration; stopping manager")
            stopMqttManager(removeDiscovery = true)
            prefs.recordMqttState("configuration error")
            return
        }

        mqttManager?.takeIf { it.matches(settings, storedPassword) }?.let { existing ->
            Log.i(TAG, "Existing MQTT manager matches settings; reusing")

            existing.retryInitialConnection()

            Log.d(TAG, "Publishing state after MQTT manager refresh")
            existing.publishState(buildFullState(MqttBridge.dashboardState.value))
            return
        }

        Log.i(TAG, "Creating new MQTT manager")
        stopMqttManager(removeDiscovery = true)

        val manager = runCatching {
            MqttManager(
                deviceKey = prefs.mqttDeviceKey(),
                settings = settings,
                password = storedPassword,
                appVersion = currentVersionName(),
                onCommand = { cmd ->
                    Log.i(TAG, "MQTT command received: $cmd")
                    MqttBridge.emitCommand(cmd)
                },
                onConnectionState = { state ->
                    Log.i(TAG, "MQTT connection state: $state")
                    prefs.recordMqttState(state)
                },
                onStateRequested = {
                    Log.d(TAG, "MQTT broker requested state update")
                    publishNow()
                }
            )
        }.getOrElse {
            Log.e(TAG, "MQTT manager initialization failed", it)
            prefs.recordMqttState("configuration error")
            return
        }

        mqttManager = manager

        Log.i(TAG, "Starting MQTT manager")
        manager.start()

        Log.i(TAG, "Publishing initial MQTT state")
        manager.publishState(buildFullState(MqttBridge.dashboardState.value))

        bridgeCollectionJob?.cancel()
        bridgeCollectionJob = collectBridgeFlows(manager)

        startPeriodicStatePublish(manager)

        Log.i(TAG, "MQTT manager started successfully")
    }

    private fun collectBridgeFlows(manager: MqttManager): Job {
        return serviceScope.launch {
            launch {
                MqttBridge.dashboardState.collect { dashboard ->
                    manager.publishState(buildFullState(dashboard))
                }
            }
            launch {
                IlluminanceState.publishRequests.collect {
                    manager.publishState(buildFullState(MqttBridge.dashboardState.value))
                }
            }
            launch {
                MqttBridge.motionEvents.collect {
                    manager.publishMotion()
                }
            }
            launch {
                MqttBridge.actionResponses.collect { (cardId, actionId) ->
                    manager.publishActionResponse(cardId, actionId)
                }
            }
        }
    }

    private fun startPeriodicStatePublish(manager: MqttManager) {
        periodicStateJob?.cancel()
        periodicStateJob = serviceScope.launch {
            while (isActive) {
                delay(MQTT_STATE_INTERVAL_MS)
                manager.publishState(buildFullState(MqttBridge.dashboardState.value))
            }
        }
    }

    private fun publishNow() {
        Log.d(TAG, "Immediate state publish requested")

        val manager = mqttManager

        if (manager == null) {
            Log.w(TAG, "Cannot publish state: MQTT manager unavailable")
            return
        }

        manager.publishState(
            buildFullState(MqttBridge.dashboardState.value)
        )
    }

    private fun buildFullState(dashboard: DashboardState?): WallModeMqttState {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            0
        }
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        val networkConnected = connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true

        val screenOn = powerManager.isInteractive

        val settings = prefs.load()
        val displayMode = dashboard?.displayMode ?: MqttContract.DISPLAY_DASHBOARD
        val dashboardProblem = dashboard?.dashboardProblem ?: true
        val health = when {
            !networkConnected -> "offline"
            dashboardProblem -> "recovering"
            else -> "ok"
        }

        return WallModeMqttState(
            batteryPercent = batteryPercent,
            charging = charging,
            screenOn = screenOn,
            screensaverActive = dashboard?.screensaverActive ?: false,
            dashboardProblem = dashboardProblem,
            currentPage = dashboard?.currentPage ?: "-",
            health = health,
            webViewVersion = currentWebViewVersion(),
            appVersion = currentVersionName(),
            ambientBrightness = dashboard?.ambientBrightness ?: settings.ambientBrightnessPercent,
            ambientTimeout = dashboard?.ambientTimeout ?: settings.ambientDimAfterSeconds,
            ambientScene = dashboard?.ambientScene
                ?: MqttContract.ambientSceneName(settings.ambientScene),
            activeProfile = dashboard?.activeProfile ?: MqttContract.profileName(null),
            profileScheduleEnabled = dashboard?.profileScheduleEnabled
                ?: settings.scheduleProfilesEnabled,
            browserEngine = dashboard?.browserEngine ?: if (settings.browserEngine == BrowserEngine.WEBVIEW) {
                MqttContract.ENGINE_WEBVIEW
            } else {
                MqttContract.ENGINE_CHROMIUM
            },
            displayMode = displayMode,
            networkConnected = networkConnected,
            takeoverActive = dashboard?.takeoverActive ?: false,
            takeoverId = dashboard?.takeoverId.orEmpty(),
            takeoverPriority = dashboard?.takeoverPriority ?: 0,
            illuminanceLux = if (settings.illuminanceReportingEnabled) {
                IlluminanceState.currentLux()
            } else {
                null
            }
        )
    }

    private fun stopMqttManager(removeDiscovery: Boolean) {
        periodicStateJob?.cancel()
        periodicStateJob = null
        bridgeCollectionJob?.cancel()
        bridgeCollectionJob = null
        mqttManager?.stop(removeDiscovery = removeDiscovery)
        mqttManager = null
    }

    private fun currentVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }

    private fun currentWebViewVersion(): String {
        return runCatching {
            WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    companion object {
        private const val TAG = "WallModeMqttService"
        private const val MQTT_STATE_INTERVAL_MS = 60_000L
        private const val CHANNEL_ID = "wallmode_mqtt"
        private const val NOTIFICATION_ID = 43
        const val ACTION_STOP = "io.github.rvbcrs.wallmode.action.MQTT_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MqttService::class.java))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MqttService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
