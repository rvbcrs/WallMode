package io.github.rvbcrs.wallmode

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Always-on foreground service that owns the presence-wake camera pipeline and
 * the proximity sensor. It is deliberately decoupled from [MainActivity]'s
 * lifecycle so motion detection keeps running while the screen is physically
 * off (the Activity is paused/stopped at that point and can no longer host a
 * [ProcessCameraProvider.bindToLifecycle] owner).
 *
 * On motion it briefly acquires a wake lock to power the panel back on and
 * signals [MainActivity] via [WakeSignals] to run the actual screen-on
 * sequence. Detection logic itself is unchanged — it reuses [MotionWakeDetector]
 * and [ProximityWakeGate].
 */
class PresenceWakeService : LifecycleService(), SensorEventListener {

    private lateinit var prefs: KioskPreferences

    private val sensorManager: SensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val powerManager: PowerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val motionDetector = MotionWakeDetector()
    private val proximityGate = ProximityWakeGate()
    private var illuminanceGate: IlluminanceReportGate? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    private var startPending = false
    private var running = false
    private var presenceActive = false
    @Volatile private var intentionallyStopped = false

    private var lastAnalysisAtMillis = 0L
    private var lastWakeAtMillis: Long? = null
    private var lastMotionPublishAtMillis: Long? = null

    override fun onCreate() {
        super.onCreate()
        prefs = KioskPreferences(this)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            intentionallyStopped = true
            stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (running) {
            // Already up: a repeat start means settings changed — reconfigure
            // sensors/camera from the latest prefs without a full teardown.
            reconfigure()
        } else {
            start()
        }
        // Not sticky: the Activity re-starts us from onResume, and a camera FGS
        // may not be relaunched cold by the system on Android 14+ anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun start() {
        if (running) return
        running = true
        val settings = prefs.load()
        presenceActive = settings.ambientModeEnabled && settings.presenceWakeEnabled
        registerSensors(settings)
        if (presenceActive) startCameraPipeline()
    }

    /** Re-reads prefs while already running (settings changed). */
    private fun reconfigure() {
        val settings = prefs.load()
        val wantPresence = settings.ambientModeEnabled && settings.presenceWakeEnabled
        val wasPresence = presenceActive
        // Update the flag before registerSensors so it registers proximity for
        // the desired state.
        presenceActive = wantPresence
        registerSensors(settings)
        if (wantPresence && !wasPresence) {
            startCameraPipeline()
        } else if (!wantPresence && wasPresence) {
            stopCameraPipeline()
        }
    }

    private fun stop() {
        running = false
        presenceActive = false
        sensorManager.unregisterListener(this)
        proximityGate.reset()
        illuminanceGate = null
        IlluminanceState.reset()
        stopCameraPipeline()
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    /**
     * Registers the proximity sensor (for presence wake) and/or the ambient
     * light sensor (for illuminance reporting), each gated by its own pref. Both
     * share this service's single SensorEventListener.
     */
    private fun registerSensors(settings: KioskSettings) {
        sensorManager.unregisterListener(this)
        proximityGate.reset()
        illuminanceGate = null
        IlluminanceState.reset()

        if (presenceActive) {
            sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        if (settings.illuminanceReportingEnabled) {
            val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            if (light == null) {
                prefs.recordLastLoadStatus("Illuminance reporting: no light sensor on this device")
            } else {
                illuminanceGate = IlluminanceReportGate(
                    minDeltaLux = settings.illuminanceMinDeltaLux.toFloat(),
                    debounceMillis = settings.illuminanceDebounceSeconds * 1_000L
                )
                sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    // region Camera pipeline (ported from MainActivity, now service-owned)

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCameraPipeline() {
        if (analysis != null || startPending) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // The Activity is responsible for obtaining CAMERA before starting
            // us; a service cannot request runtime permissions.
            prefs.recordLastLoadStatus("Presence wake service: camera permission missing")
            return
        }
        startPending = true

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            startPending = false
            if (!running) return@addListener
            val provider = runCatching { providerFuture.get() }.getOrElse { error ->
                prefs.recordLastLoadStatus("Presence wake start failed: ${error.message}")
                return@addListener
            }
            val selector = selectCamera(provider) ?: run {
                prefs.recordLastLoadStatus("Presence wake unavailable: no camera found")
                return@addListener
            }

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(160, 120),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(15, 15)
            )
            val built = analysisBuilder.build()
            built.setAnalyzer(ensureAnalysisExecutor()) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            runCatching {
                // The service itself is the LifecycleOwner, so the binding
                // survives screen-off (unlike the Activity-bound version).
                provider.bindToLifecycle(this, selector, built)
            }.onSuccess {
                cameraProvider = provider
                analysis = built
                AppVisibility.presenceCameraActive = true
                Log.i(TAG, "Presence wake camera started (service)")
            }.onFailure { error ->
                built.clearAnalyzer()
                prefs.recordLastLoadStatus("Presence wake start failed: ${error.message}")
                Log.w(TAG, "Presence wake camera failed", error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraPipeline() {
        AppVisibility.presenceCameraActive = false
        analysis?.clearAnalyzer()
        analysis?.let { active -> runCatching { cameraProvider?.unbind(active) } }
        analysis = null
        motionDetector.reset()
        lastAnalysisAtMillis = 0L
    }

    private fun selectCamera(provider: ProcessCameraProvider): CameraSelector? {
        return when {
            runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false) ->
                CameraSelector.DEFAULT_FRONT_CAMERA
            runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false) ->
                CameraSelector.DEFAULT_BACK_CAMERA
            else -> null
        }
    }

    private fun ensureAnalysisExecutor(): ExecutorService {
        val existing = analysisExecutor
        if (existing != null && !existing.isShutdown) return existing
        return Executors.newSingleThreadExecutor().also { analysisExecutor = it }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val settings = prefs.load()
        if (!settings.presenceWakeEnabled || !settings.ambientModeEnabled) {
            imageProxy.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisAtMillis < PRESENCE_ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalysisAtMillis = now

        val luminance = imageProxy.planes.firstOrNull()
        if (luminance == null) {
            imageProxy.close()
            return
        }
        val motionDetected = motionDetector.detect(
            luminance.buffer,
            imageProxy.width,
            imageProxy.height,
            luminance.rowStride,
            luminance.pixelStride
        )
        imageProxy.close()
        if (motionDetected) onMotionDetected()
    }

    // endregion

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val near = event.values.firstOrNull()?.let { it < event.sensor.maximumRange } ?: return
                if (proximityGate.update(near)) onMotionDetected()
            }
            Sensor.TYPE_LIGHT -> {
                val lux = event.values.firstOrNull() ?: return
                onIlluminance(lux)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Applies the delta/debounce gate and, when a reading passes, stores it and
     * asks MainActivity to publish MQTT state (MainActivity owns the broker
     * client). Readings that don't pass still update the cached value so a later
     * state publish reports the freshest lux.
     */
    private fun onIlluminance(lux: Float) {
        val gate = illuminanceGate ?: return
        if (gate.shouldReport(lux, SystemClock.elapsedRealtime())) {
            IlluminanceState.recordAndPublish(lux)
        } else {
            IlluminanceState.record(lux)
        }
    }

    /**
     * Converges camera and proximity motion. Classifies the screen-off source
     * using [PowerManager.isInteractive] (true for any panel-off: app deep sleep,
     * physical power button, or system timeout) combined with
     * [WakeSignals.powerState] (only the kiosk's own deep sleep sets it OFF):
     *
     *  - app deep sleep    -> !interactive && powerState == OFF
     *  - user / OS off      -> !interactive && powerState == ON
     *
     * When [KioskSettings.respectManualScreenOff] is on, a user/OS off is left
     * alone (no wake, not even a [WakeSignals.requestWake] tick) until the panel
     * is turned back on manually, so the service never fights the user.
     */
    private fun onMotionDetected() {
        if (intentionallyStopped || AppVisibility.userExited) return
        val settings = prefs.load()
        if (!settings.presenceWakeEnabled || !settings.ambientModeEnabled) return

        // Publish motion to HA from the service (survives Activity death), react
        // fast on the first detection then stay quiet for the cooldown window so a
        // busy room does not spam the broker. Reuses presenceWakeCooldownSeconds.
        val nowMs = SystemClock.elapsedRealtime()
        val lastPublish = lastMotionPublishAtMillis
        if (lastPublish == null || nowMs - lastPublish >= settings.presenceWakeCooldownSeconds * 1_000L) {
            lastMotionPublishAtMillis = nowMs
            MqttBridge.publishMotion()
        }

        val screenIsOff = !powerManager.isInteractive
        val appDrivenOff = WakeSignals.powerState.value == DisplayPowerState.OFF
        val userOrOsOff = screenIsOff && !appDrivenOff

        // Respect a manual/OS screen-off: stay off until turned back on manually.
        if (userOrOsOff && settings.respectManualScreenOff) {
            Log.i(TAG, "Motion detected; screen probably turned off manually, skip waking.")
            return
        }

        if (screenIsOff) {
            val now = SystemClock.elapsedRealtime()
            val lastWake = lastWakeAtMillis
            if (lastWake != null && now - lastWake < settings.presenceWakeCooldownSeconds * 1_000L) {
                return
            }
            lastWakeAtMillis = now
            Log.i(TAG, "Motion detected; waking screen")
            acquireWakePulse()
            bringActivityForward()
        }
        WakeSignals.requestWake()
    }

    /**
     * Brings MainActivity (singleTask) to the foreground so a stopped or
     * destroyed kiosk Activity is restored when the panel wakes. Harmless when
     * it is already foreground. Only called when WakeSignals.powerState == OFF,
     * so the powerManager.isInteractive guard here is redundant and removed.
     */
    private fun bringActivityForward() {
        if (intentionallyStopped || AppVisibility.userExited) return
        if (AppVisibility.settingsOpen) return
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_WAKE_FROM_MOTION, true)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not bring activity forward", it) }
    }

    /**
     * Momentary wake lock that forces the display on. Released automatically by
     * the timeout; the Activity re-adds FLAG_KEEP_SCREEN_ON to keep it on once
     * it is resumed, so this only needs to bridge the gap.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakePulse() {
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            WAKE_LOCK_TAG
        )
        runCatching { wakeLock.acquire(WAKE_PULSE_TIMEOUT_MS) }
            .onFailure { Log.w(TAG, "Wake pulse failed", it) }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.presence_wake_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.presence_wake_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.presence_wake_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "WallModePresenceWakeService"
        private const val CHANNEL_ID = "wallmode_presence_wake"
        private const val NOTIFICATION_ID = 42
        private const val PRESENCE_ANALYSIS_INTERVAL_MS = 250L
        private const val WAKE_LOCK_TAG = "wallmode:presence-wake"
        private const val WAKE_PULSE_TIMEOUT_MS = 5_000L
        const val ACTION_STOP = "io.github.rvbcrs.wallmode.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PresenceWakeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // Send ACTION_STOP so the service can set intentionallyStopped before
            // tearing down — this closes the race where in-flight motion detection
            // would relaunch MainActivity after the user chose to exit the kiosk.
            val intent = Intent(context, PresenceWakeService::class.java).apply {
                action = ACTION_STOP
            }
            // Use startForegroundService to deliver the stop action reliably;
            // the service immediately calls stopSelf() on receipt.
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { context.stopService(Intent(context, PresenceWakeService::class.java)) }
        }
    }
}
