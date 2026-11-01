package io.github.rvbcrs.wallmode

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.rvbcrs.wallmode.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

private data class ActiveEventTakeover(
    val id: String,
    val url: String?,
    val actionCard: WallModeMqttCommand.ShowActionCard?,
    val priority: Int,
    val returnUrl: String,
    val returnDisplayMode: String,
    val expiresAtElapsedRealtime: Long,
    val webContentReplaced: Boolean
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: KioskPreferences
    private lateinit var announcementSpeaker: AnnouncementSpeaker
    private lateinit var bannerOverlay: BannerOverlay
    private var bannerTouchGesture = false

    private var webViewConfigured = false
    private var defaultWebViewUserAgent: String? = null
    private var currentEngine: BrowserEngine? = null
    private var activeSettings: KioskSettings? = null
    private var loadedDashboardUrl: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingRetry: Runnable? = null
    private var mainFrameLoadTimeout: Runnable? = null
    private var ambientDimRunnable: Runnable? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var watchdogJob: Job? = null
    private var maintenanceJob: Job? = null
    private var profileJob: Job? = null
    private var autoDiscoveryJob: Job? = null
    private var screensaverWeatherJob: Job? = null
    private var ambientBackgroundJob: Job? = null
    private var activeEventTakeover: ActiveEventTakeover? = null
    private var eventTakeoverTimeout: Runnable? = null
    private var pendingScheduledProfile = false
    private var activeDashboardProfile: DashboardProfile? = null
    private var localControlServer: LocalControlServer? = null
    private var mqttServiceRunning = false
    private var localControlServerPort = -1
    private var pendingPresenceWakePermissionRequest = false
    private var presenceWakePermissionAttempted = false
    private var pendingStartupCameraPermissionRequest = false
    private var presenceWakeServiceRunning = false

    private var tapCount = 0
    private var firstTapAt = 0L
    private var networkWasLost = false
    private var networkCallbackRegistered = false
    private var watchdogFailureCount = 0
    private var isAmbientDimmed = false
    private var isScreenOff = false
    private var updateCheckInProgress = false
    private var autoDiscoveryAttempted = false
    private var consumeAmbientWakeGesture = false
    private var ambientBackgroundRequest = 0
    private var ambientTransitionGeneration = 0
    private val mainFrameLoadState = MainFrameLoadState()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val grantedAll = result.values.all { it }

            pendingWebPermissionRequest?.let { request ->
                if (grantedAll) request.grant(request.resources) else request.deny()
            }
            pendingWebPermissionRequest = null

            if (pendingStartupCameraPermissionRequest) {
                pendingStartupCameraPermissionRequest = false
                if (result[Manifest.permission.CAMERA] == true) {
                    prefs.setPresenceWakeEnabled(true)
                    applyPresenceWakePolicy()
                } else {
                    prefs.recordLastLoadStatus("Startup camera permission denied")
                }
            }

            if (pendingPresenceWakePermissionRequest) {
                pendingPresenceWakePermissionRequest = false
                val cameraGranted = result[Manifest.permission.CAMERA] == true
                if (cameraGranted) {
                    applyPresenceWakePolicy()
                } else {
                    stopPresenceWakeService()
                    prefs.recordLastLoadStatus("Presence wake unavailable: camera permission denied")
                }
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            applyWindowSettings()
            applySchedulers()
            refreshLocalControlServer()
            refreshMqttService()
            if (result.resultCode == RESULT_OK &&
                result.data?.getBooleanExtra(SettingsActivity.EXTRA_EXIT_APP, false) == true
            ) {
                exitKioskNow()
                return@registerForActivityResult
            }

            if (result.resultCode == RESULT_OK &&
                result.data?.getBooleanExtra(SettingsActivity.EXTRA_RELOAD_NOW, false) == true
            ) {
                loadDashboard()
            }
        }

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            mainHandler.post {
                publishDashboardState()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    maybeAutoDiscoverAndConnect()
                }
            }
            if (networkWasLost) {
                networkWasLost = false
                prefs.recordNetworkState("available")
                mainHandler.postDelayed({
                    if (!isDestroyed) {
                        showRecoveryOverlay(getString(R.string.recovery_reconnected), "")
                        reloadCurrentTarget()
                    }
                }, QUICK_RELOAD_DELAY_MS)
            }
        }

        override fun onLost(network: android.net.Network) {
            networkWasLost = true
            prefs.recordNetworkState("lost")
            mainHandler.post {
                if (!isDestroyed) {
                    showRecoveryOverlay(
                        getString(R.string.recovery_network_lost),
                        getString(R.string.recovery_waiting)
                    )
                    scheduleRetry("Network lost")
                    publishDashboardState()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppVisibility.userExited = false
        presenceWakePermissionAttempted = savedInstanceState?.getBoolean(STATE_PRESENCE_PERMISSION_ATTEMPTED) ?: false
        prefs = KioskPreferences(this)
        announcementSpeaker = AnnouncementSpeaker(this)
        prefs.applyThemeMode()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bannerOverlay = BannerOverlay(binding.root) { noticeId, actionId ->
            MqttBridge.publishActionResponse(noticeId, actionId)
        }
        restoreEventTakeover(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(false)

        setupAdminGesture()
        collectWakeSignals()
        collectMqttBridge()
        registerNetworkMonitoring()
        applyWindowSettings()
        maybePromptStartupCameraPermission()
        refreshLocalControlServer()
        refreshMqttService()
        applySchedulers()
        if (!resumeEventTakeover()) loadDashboard()
        maybeRunAutoUpdateCheck()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back is intentionally ignored in kiosk mode.
            }
        })
    }

    override fun onResume() {
        super.onResume()
        prefs.applyThemeMode()
        maybeHandleWakeIntent(intent)
        applyWindowSettings()
        resetAmbientTimer()
        applyPresenceWakePolicy()
        refreshLocalControlServer()
        refreshMqttService()
        maybeAutoDiscoverAndConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleWakeIntent(intent)
    }

    /**
     * Handles the wake intent sent by [PresenceWakeService] when it detects
     * motion while the panel is off and the Activity was not foreground. The
     * WakeSignals collector covers the already-running case; this covers a cold
     * relaunch where the wake emission may have preceded us. Runs before the
     * ambient timer reset so the turn-screen-on flags are applied while
     * isScreenOff is still true.
     */
    private fun maybeHandleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_FROM_MOTION, false) != true) return
        intent.removeExtra(EXTRA_WAKE_FROM_MOTION)
        if (isScreenOff) wakeScreenFromMotion()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PRESENCE_PERMISSION_ATTEMPTED, presenceWakePermissionAttempted)
        outState.putString(STATE_ACTIVE_PROFILE, activeDashboardProfile?.name)
        outState.putBoolean(STATE_PENDING_SCHEDULED_PROFILE, pendingScheduledProfile)
        activeEventTakeover?.let { takeover ->
            outState.putString(STATE_TAKEOVER_ID, takeover.id)
            outState.putString(STATE_TAKEOVER_URL, takeover.url)
            takeover.actionCard?.let { card ->
                outState.putString(STATE_TAKEOVER_CARD, actionCardPayload(card))
            }
            outState.putInt(STATE_TAKEOVER_PRIORITY, takeover.priority)
            outState.putString(STATE_TAKEOVER_RETURN_URL, takeover.returnUrl)
            outState.putString(STATE_TAKEOVER_RETURN_MODE, takeover.returnDisplayMode)
            outState.putLong(STATE_TAKEOVER_EXPIRES_AT, takeover.expiresAtElapsedRealtime)
            outState.putBoolean(STATE_TAKEOVER_REPLACED_WEB, takeover.webContentReplaced)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        ambientDimRunnable?.let(mainHandler::removeCallbacks)
        ambientDimRunnable = null
        if (pendingStartupCameraPermissionRequest && autoDiscoveryJob?.isActive == true) {
            autoDiscoveryAttempted = false
        }
        autoDiscoveryJob?.cancel()
        stopAmbientBackground()
        // The presence-wake camera + proximity sensor now live in
        // PresenceWakeService and intentionally keep running across onPause so
        // motion detection survives a true screen-off (which pauses us).
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && prefs.load().fullscreen) {
            enterImmersiveMode()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
            bannerTouchGesture = bannerOverlay.containsTouch(ev.rawX, ev.rawY)
        }
        if (bannerTouchGesture) {
            // Banner controls do not wake or reset the screensaver underneath.
            val handled = super.dispatchTouchEvent(ev)
            if (ev?.actionMasked == MotionEvent.ACTION_UP || ev?.actionMasked == MotionEvent.ACTION_CANCEL) {
                bannerTouchGesture = false
            }
            return handled
        }
        if (ev != null && ev.actionMasked == MotionEvent.ACTION_DOWN && isAmbientDimmed) {
            consumeAmbientWakeGesture = true
            resetAmbientTimer()
        }
        if (consumeAmbientWakeGesture) {
            if (ev?.actionMasked == MotionEvent.ACTION_UP || ev?.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumeAmbientWakeGesture = false
            }
            return true
        }
        resetAmbientTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        bannerOverlay.dispose()
        announcementSpeaker.shutdown()
        autoDiscoveryJob?.cancel()
        profileJob?.cancel()
        screensaverWeatherJob?.cancel()
        stopAmbientBackground()
        unregisterNetworkMonitoring()
        stopLocalControlServer()
        // MqttService is deliberately left running so MQTT stays connected while the
        // screen is off (the Activity may be destroyed in that state). It will be
        // stopped explicitly from exitKioskNow() or when the settings disable MQTT.
        // PresenceWakeService is likewise left running for motion detection.
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        destroyWebView()
    }

    private fun setupAdminGesture() {
        binding.adminTapZone.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - firstTapAt > ADMIN_GESTURE_WINDOW_MS) {
                firstTapAt = now
                tapCount = 1
            } else {
                tapCount += 1
            }

            if (tapCount >= ADMIN_GESTURE_TAP_COUNT) {
                tapCount = 0
                firstTapAt = 0
                requestAdminAccess()
            }
        }
    }

    /**
     * Collects wake events from [PresenceWakeService] (which runs the camera and
     * proximity sensor even while the screen is off) and runs the UI-side wake
     * reaction. STARTED is enough — a screen-off Activity is STARTED, not
     * RESUMED, when the service relaunches us.
     */
    private fun collectWakeSignals() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WakeSignals.wakeEvents.collect {
                    onPresenceDetected()
                }
            }
        }
    }

    /**
     * Collects [MqttBridge] flows for the lifetime of the activity:
     *  - Inbound commands from [MqttService] → [handleMqttCommand]
     */
    private fun collectMqttBridge() {
        lifecycleScope.launch {
            MqttBridge.commands.collect { cmd ->
                handleMqttCommand(cmd)
            }
        }
    }

    private fun requestAdminAccess() {
        val settings = prefs.load()
        val mustPrompt = prefs.hasAdminPassword() &&
            !settings.requirePasswordForExitOnly && !prefs.isAdminSessionStillValid(settings)

        if (!mustPrompt) {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_admin_password, null)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.input_admin_unlock_password)
        val passwordLayout =
            dialogView.findViewById<TextInputLayout>(R.id.input_layout_admin_unlock_password)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_admin_password)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.unlock, null)
            .create()

        dialog.setOnShowListener {
            passwordInput.requestFocus()
            val unlockButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val unlockLabel = getString(R.string.unlock)
            unlockButton.setOnClickListener {
                val candidate = passwordInput.text?.toString().orEmpty()
                passwordLayout.error = null
                if (candidate.isBlank()) {
                    passwordLayout.error = getString(R.string.invalid_admin_password)
                    return@setOnClickListener
                }
                unlockButton.isEnabled = false
                unlockButton.text = getString(R.string.verifying_password)

                scope.launch {
                    val verifiedCredential = withContext(Dispatchers.Default) {
                        prefs.verifyAdminPasswordForUnlock(candidate)
                    }
                    if (!dialog.isShowing) return@launch

                    unlockButton.isEnabled = true
                    unlockButton.text = unlockLabel

                    if (verifiedCredential != null && prefs.markAdminUnlockedNow(verifiedCredential)) {
                        dialog.dismiss()
                        settingsLauncher.launch(Intent(this@MainActivity, SettingsActivity::class.java))
                    } else {
                        passwordLayout.error = getString(R.string.invalid_admin_password)
                        passwordInput.text?.clear()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun loadDashboard() {
        if (activeEventTakeover != null) {
            reloadCurrentTarget()
            return
        }
        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = null

        val settings = prefs.load()
        val profile = dashboardProfileForLoad(
            scheduleEnabled = settings.scheduleProfilesEnabled,
            activeProfile = activeDashboardProfile,
            scheduledProfile = DashboardProfileSchedule.activeAtHour(
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                settings
            )
        )
        val path = profile?.let { DashboardProfileSchedule.path(it, settings) }
            ?: settings.dashboardPath
        openDashboardUrl(prefs.buildDashboardUrl(settings, path), settings, profile)
    }

    private fun openDashboardUrl(
        url: String,
        settings: KioskSettings = prefs.load(),
        profile: DashboardProfile? = null
    ) {
        val takeoverCancelled = cancelEventTakeover()
        pendingScheduledProfile = false
        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = null
        activeDashboardProfile = profile
        loadWebUrl(url, settings, rememberAsDashboard = true)
        if (takeoverCancelled) resetAmbientTimer()
    }

    private fun loadWebUrl(url: String, settings: KioskSettings, rememberAsDashboard: Boolean) {
        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = null
        activeSettings = settings
        applyLockTaskMode(settings)
        switchEngine(settings.browserEngine)

        if (rememberAsDashboard) {
            loadedDashboardUrl = url
            prefs.recordLastUrl(url)
        }
        prefs.recordLastEngine(settings.browserEngine)
        beginMainFrameLoad(url)

        binding.webView.stopLoading()
        applyWebEngineProfile(settings)
        binding.webView.loadUrl(url)
        publishDashboardState()
    }

    private fun maybeAutoDiscoverAndConnect() {
        val settings = prefs.load()
        if (autoDiscoveryAttempted || !KioskPreferences.shouldAutoDiscoverHomeAssistant(
                settings.autoDiscoverHomeAssistant, settings.homeAssistantUrl
            ) || connectivityManager.activeNetwork == null
        ) {
            return
        }

        autoDiscoveryAttempted = true
        val startupDashboardUrl = loadedDashboardUrl
        autoDiscoveryJob = scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { HomeAssistantDiscovery.discover(this@MainActivity) }.getOrNull()
            } ?: return@launch

            val current = prefs.load()
            // A settings edit, manual navigation or event view wins over a late network result.
            if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                current != settings || loadedDashboardUrl != startupDashboardUrl || activeEventTakeover != null
            ) return@launch
            val normalizedCurrent = KioskPreferences.normalizeBaseUrl(current.homeAssistantUrl)
            if (normalizedCurrent.equals(found.baseUrl, ignoreCase = true)) {
                return@launch
            }

            val updated = current.copy(homeAssistantUrl = found.baseUrl)
            prefs.save(updated)
            prefs.recordLastLoadStatus("Auto discovery connected: ${found.baseUrl} (startup)")
            loadDashboard()
            Toast.makeText(
                this@MainActivity,
                getString(R.string.discovery_connected_toast, found.baseUrl),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun switchEngine(engine: BrowserEngine) {
        currentEngine = engine
        configureWebViewIfNeeded()
    }

    private fun configureWebViewIfNeeded() {
        if (webViewConfigured) {
            return
        }
        webViewConfigured = true

        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.safeBrowsingEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false

            CookieManager.getInstance().setAcceptCookie(true)
            defaultWebViewUserAgent = settings.userAgentString

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        if (!isTrustedOrigin(request.origin?.toString())) {
                            request.deny()
                            return@runOnUiThread
                        }

                        // Presence wake owns the camera for motion detection. A
                        // single-camera device cannot share it, so decline a
                        // dashboard's camera request rather than trigger a
                        // "camera in use" exception. Audio-only requests still go
                        // through the normal grant path below.
                        val requested = request.resources.orEmpty()
                        val grantableResources = if (presenceOwnsCamera()) {
                            requested.filterNot { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                                .toTypedArray()
                        } else {
                            requested
                        }
                        if (grantableResources.isEmpty()) {
                            request.deny()
                            prefs.recordLastLoadStatus(
                                "Dashboard camera denied: in use by presence wake"
                            )
                            return@runOnUiThread
                        }

                        val requiredPermissions =
                            mapWebResourcesToAndroidPermissions(grantableResources)
                        if (requiredPermissions.isEmpty() || hasAllPermissions(requiredPermissions)) {
                            request.grant(grantableResources)
                            return@runOnUiThread
                        }

                        pendingWebPermissionRequest?.deny()
                        pendingWebPermissionRequest = request
                        permissionLauncher.launch(requiredPermissions)
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url.isNullOrBlank()) return
                    if (mainFrameLoadState.isLoading && mainFrameLoadState.isCurrentUrl(url)) {
                        // The explicit load is already tracked; WebView.url can still point at the old page here.
                    } else if (mainFrameCallbackMatches(url, view.url) && mainFrameLoadState.isLoading) {
                        mainFrameLoadState.updateCurrentUrl(url)
                    } else if (mainFrameCallbackMatches(url, view.url)) {
                        beginMainFrameLoad(url)
                    } else {
                        return
                    }
                    prefs.recordLastLoadStatus("loading")
                    publishDashboardState()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (mainFrameLoadState.isVisible && mainFrameLoadState.isCurrentUrl(url)) {
                        if (activeEventTakeover == null && !url.isNullOrBlank()) {
                            loadedDashboardUrl = url
                            prefs.recordLastUrl(url)
                        }
                        prefs.recordLastLoadStatus("loaded")
                        publishDashboardState()
                        return
                    }
                    // Safety net: the load finished while we are still LOADING,
                    // i.e. onPageCommitVisible never arrived (it can be skipped
                    // while the window surface is not visible, e.g. a reload that
                    // fires during a screen-off). Treat a finished main frame as
                    // visible so the overlay clears once the panel wakes.
                    if (mainFrameLoadState.isLoading && !url.isNullOrBlank()) {
                        markMainFrameVisible(url, status = "loaded")
                    }
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    markMainFrameVisible(url, status = "visible")
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    if (!request.isForMainFrame) {
                        return false
                    }
                    return if (isAllowedTopLevelUrl(request.url?.toString())) {
                        beginMainFrameLoad(request.url?.toString())
                        false
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.blocked_navigation),
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        handleMainFrameFailure(
                            "WebView load error: ${error.description}",
                            request.url?.toString()
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        handleMainFrameFailure(
                            "WebView HTTP ${errorResponse.statusCode}",
                            request.url?.toString()
                        )
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    prefs.recordCrash("WebView render process gone")
                    handleMainFrameFailure("WebView render process gone", force = true)
                    mainHandler.post { if (!isDestroyed) recreate() }
                    return true
                }
            }
        }
    }

    private fun applyWebEngineProfile(settings: KioskSettings) {
        val webSettings = binding.webView.settings
        if (defaultWebViewUserAgent.isNullOrBlank()) {
            defaultWebViewUserAgent = webSettings.userAgentString
        }

        webSettings.mediaPlaybackRequiresUserGesture = !settings.autoplayEnabled
        webSettings.mixedContentMode = if (settings.allowMixedContent) {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        } else {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            binding.webView,
            settings.allowThirdPartyCookies
        )

        val defaultUa = defaultWebViewUserAgent ?: webSettings.userAgentString.orEmpty()
        val customUa = settings.customUserAgent.trim()
        webSettings.userAgentString = when {
            customUa.isNotBlank() -> customUa
            settings.desktopMode -> DESKTOP_UA
            settings.browserEngine == BrowserEngine.CHROMIUM_CUSTOM_TAB -> forceChromiumLikeUserAgent(defaultUa)
            else -> defaultUa
        }
    }

    private fun forceChromiumLikeUserAgent(base: String): String {
        if (base.isBlank()) {
            return CHROMIUM_FALLBACK_UA
        }
        return base.replace("; wv", "").replace(" wv", "")
    }

    private fun hasAllPermissions(permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun mapWebResourcesToAndroidPermissions(resources: Array<out String>): Array<String> {
        val permissions = linkedSetOf<String>()
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            permissions += Manifest.permission.CAMERA
        }
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
        return permissions.toTypedArray()
    }

    /**
     * True when presence wake is holding the camera (service bound it, or it is
     * enabled and about to). Used to decline a dashboard's camera request so the
     * two clients never contend for a single-camera device.
     */
    private fun presenceOwnsCamera(): Boolean {
        if (AppVisibility.presenceCameraActive) return true
        val settings = prefs.load()
        return settings.ambientModeEnabled && settings.presenceWakeEnabled
    }

    private fun isAllowedTopLevelUrl(url: String?): Boolean {
        val scheme = parseUri(url)?.scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    private fun isTrustedOrigin(candidateUrl: String?): Boolean {
        val candidateHost = parseUri(candidateUrl)?.host?.lowercase() ?: return false
        val configuredHost = configuredHomeAssistantHost() ?: return false
        return candidateHost == configuredHost
    }

    private fun configuredHomeAssistantHost(): String? {
        val baseUrl = KioskPreferences.normalizeBaseUrl(
            (activeSettings ?: prefs.load()).homeAssistantUrl
        )
        return parseUri(baseUrl)?.host?.lowercase()
    }

    private fun parseUri(url: String?): Uri? {
        if (url.isNullOrBlank()) {
            return null
        }
        return runCatching { Uri.parse(url) }.getOrNull()
    }

    private fun destroyWebView() {
        if (!webViewConfigured) {
            return
        }
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        webViewConfigured = false
    }

    private fun applyWindowSettings() {
        val settings = prefs.load()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (settings.fullscreen) {
            enterImmersiveMode()
        } else {
            exitImmersiveMode()
        }

        val tapZoneParams = binding.adminTapZone.layoutParams as FrameLayout.LayoutParams
        tapZoneParams.gravity = when (settings.adminButtonCorner) {
            AdminButtonCorner.TOP_LEFT -> Gravity.TOP or Gravity.START
            AdminButtonCorner.TOP_RIGHT -> Gravity.TOP or Gravity.END
            AdminButtonCorner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            AdminButtonCorner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        binding.adminTapZone.layoutParams = tapZoneParams

        applyLockTaskMode(settings)
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
    }

    private fun applyLockTaskMode(settings: KioskSettings) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)

        if (settings.lockTaskMode) {
            if (isDeviceOwner) {
                runCatching {
                    dpm.setLockTaskPackages(admin, arrayOf(packageName))
                }.onFailure { error ->
                    prefs.recordWatchdogState("lock-task policy failed: ${error.message}")
                }
            }

            val permitted = dpm.isLockTaskPermitted(packageName) || isDeviceOwner
            if (permitted && !isLockTaskModeRunning()) {
                runCatching { startLockTask() }.onFailure { error ->
                    prefs.recordWatchdogState("lock-task start failed: ${error.message}")
                }
            }
        } else {
            attemptStopLockTaskIfRunning()
        }
    }

    private fun attemptStopLockTaskIfRunning() {
        if (!isLockTaskModeRunning()) {
            return
        }
        runCatching { stopLockTask() }.onFailure {
            // Ignore, app may not own lock task in this state.
        }
    }

    private fun isLockTaskModeRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun applySchedulers() {
        scheduleProfileSwitching()
        scheduleMaintenanceWindow()
        scheduleWatchdog()
        resetAmbientTimer()
        applyPresenceWakePolicy()
    }

    private fun scheduleProfileSwitching() {
        profileJob?.cancel()
        val currentSettings = prefs.load()
        if (!currentSettings.scheduleProfilesEnabled) {
            pendingScheduledProfile = false
            return
        }
        profileJob = scope.launch {
            while (isActive) {
                val settings = prefs.load()
                if (!settings.scheduleProfilesEnabled) break
                delay(millisUntilNextProfile(settings))
                if (!isActive) break
                if (activeEventTakeover == null) {
                    loadDashboard()
                } else {
                    pendingScheduledProfile = true
                }
            }
        }
    }

    private fun millisUntilNextProfile(settings: KioskSettings): Long {
        val now = Calendar.getInstance()
        val next = listOf(
            settings.homeStartHour,
            settings.wallStartHour,
            settings.nightStartHour
        ).distinct().minOf { startHour ->
            Calendar.getInstance().apply {
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }
        return maxOf(1_000L, next - now.timeInMillis)
    }

    private fun scheduleMaintenanceWindow() {
        maintenanceJob?.cancel()
        val settings = prefs.load()
        if (!settings.maintenanceEnabled) {
            return
        }

        maintenanceJob = scope.launch {
            while (isActive) {
                val delayMillis = millisUntilNextMaintenance(settings)
                delay(delayMillis)
                if (!isActive) break

                prefs.recordLastLoadStatus("Maintenance refresh")
                showRecoveryOverlay(
                    getString(R.string.recovery_maintenance),
                    getString(R.string.recovery_maintenance_desc)
                )
                clearActiveEngineCache()
                reloadCurrentTarget()
            }
        }
    }

    private fun millisUntilNextMaintenance(settings: KioskSettings): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, settings.maintenanceHour)
            set(Calendar.MINUTE, settings.maintenanceMinute)
            if (before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return maxOf(60_000L, next.timeInMillis - now.timeInMillis)
    }

    private fun clearActiveEngineCache() {
        binding.webView.clearCache(true)
    }

    private fun scheduleWatchdog() {
        watchdogJob?.cancel()
        watchdogFailureCount = 0
        val settings = prefs.load()
        if (!settings.watchdogEnabled) {
            return
        }

        watchdogJob = scope.launch {
            while (isActive) {
                delay(settings.watchdogPingIntervalSeconds * 1_000L)
                val currentSettings = prefs.load()
                if (!currentSettings.watchdogEnabled) break

                val pingUrl = buildWatchdogUrl(currentSettings)
                val healthy = withContext(Dispatchers.IO) { pingUrl(pingUrl) }
                if (healthy) {
                    watchdogFailureCount = 0
                    prefs.recordWatchdogState("ok")
                    if (binding.recoveryOverlay.visibility == View.VISIBLE) {
                        showRecoveryOverlay(getString(R.string.recovery_back_online), pingUrl)
                        mainHandler.postDelayed({ binding.recoveryOverlay.visibility = View.GONE }, 1_800L)
                    }
                } else {
                    watchdogFailureCount += 1
                    prefs.recordWatchdogState("fail($watchdogFailureCount)")
                    showRecoveryOverlay(getString(R.string.recovery_service_unreachable), pingUrl)
                    if (currentSettings.autoReloadOnFailure && watchdogFailureCount >= 2) {
                        scheduleRetry("Watchdog ping failure")
                    }
                    if (watchdogFailureCount >= 4) {
                        recreateCurrentEngine(deferDuringTakeover = true)
                    }
                }
            }
        }
    }

    private fun buildWatchdogUrl(settings: KioskSettings): String {
        val base = KioskPreferences.normalizeBaseUrl(settings.homeAssistantUrl).trimEnd('/')
        val path = KioskPreferences.normalizeDashboardPath(settings.watchdogPingPath)
        return if (path.isBlank()) base else "$base/$path"
    }

    private fun pingUrl(url: String): Boolean {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = true
            }
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            // Any HTTP response means the server is reachable; treat only transport failures as unreachable.
            code in 100..499
        }.getOrDefault(false)
    }

    private fun recreateCurrentEngine(deferDuringTakeover: Boolean = false) {
        if (deferDuringTakeover && activeEventTakeover != null) return
        recreate()
    }

    private fun resetAmbientTimer() {
        ambientDimRunnable?.let(mainHandler::removeCallbacks)
        ambientDimRunnable = null
        if (isAmbientDimmed) leaveAmbientMode()
        if (activeEventTakeover != null) return
        val settings = prefs.load()
        if (!settings.ambientModeEnabled) {
            return
        }

        ambientDimRunnable = Runnable { enterAmbientMode(settings, settings.ambientScreensaverEnabled) }
        mainHandler.postDelayed(ambientDimRunnable!!, settings.ambientDimAfterSeconds * 1_000L)
    }

    private fun enterAmbientMode(
        settings: KioskSettings,
        showScreensaver: Boolean,
        deepSleep: Boolean = settings.deepSleepEnabled
    ) {
        val transition = ++ambientTransitionGeneration
        binding.ambientScreensaver.animate().cancel()
        applyAmbientBrightness(settings)
        isAmbientDimmed = true
        if (showScreensaver) {
            binding.ambientScreensaver.scene = settings.ambientScene
            binding.ambientScreensaver.photoClockMode =
                settings.ambientBackgroundMode == AmbientBackgroundMode.IMMICH_ALBUM
            startAmbientBackground(settings)
            binding.ambientScreensaver.apply {
                alpha = 0f
                scaleX = 0.985f
                scaleY = 0.985f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(80L)
                    .setDuration(420L)
                    .withEndAction {
                        if (transition == ambientTransitionGeneration) {
                            alpha = 1f
                            scaleX = 1f
                            scaleY = 1f
                        }
                    }
                    .start()
            }
            binding.adminTapZone.bringToFront()
            if (settings.ambientScene == AmbientScene.AURORA_WEATHER &&
                settings.ambientBackgroundMode != AmbientBackgroundMode.IMMICH_ALBUM
            ) {
                startScreensaverWeather(settings.ambientWeatherLocation)
            }
            mainHandler.postDelayed({
                if (isAmbientDimmed) applyAmbientBrightness()
            }, 600L)
        } else {
            stopAmbientBackground()
            binding.ambientScreensaver.visibility = View.GONE
        }
        if (deepSleep) {
            applyScreenOff(settings)
        } else {
            isScreenOff = false
            WakeSignals.setPowerState(DisplayPowerState.ON)
        }
        publishDashboardState()
    }

    /**
     * Powers the physical panel off (device owner) or falls back to a hard
     * brightness-0 "fake off". FLAG_KEEP_SCREEN_ON must be cleared first, else
     * lockNow() is immediately undone by the still-awake window. Called from
     * [enterAmbientMode]; the camera keeps running in [PresenceWakeService].
     */
    private fun applyScreenOff(settings: KioskSettings) {
        isScreenOff = true
        WakeSignals.setPowerState(DisplayPowerState.OFF)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            // Keep the keyguard disabled so a wake lands straight on the kiosk
            // rather than an undismissable lock screen.
            runCatching { dpm.setKeyguardDisabled(admin, true) }
            runCatching { dpm.lockNow() }
                .onFailure { prefs.recordLastLoadStatus("Screen off failed: ${it.message}") }
        } else {
            // No device-owner privilege: can't power the panel off, so drive
            // brightness to 0 and keep the window awake (fake off).
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setWindowBrightness(0f)
        }
    }

    /**
     * Wakes the panel after motion while screen-off. The service has already
     * fired a wake pulse (PowerManager wake lock) and relaunched us; here we add
     * the turn-screen-on window flags and leave ambient mode. Safe to call when
     * not actually off (no-op beyond leaving ambient mode).
     */
    private fun wakeScreenFromMotion() {
        if (!isScreenOff) {
            resetAmbientTimer()
            return
        }
        Log.i(TAG, "Motion detected; waking screen")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        if (prefs.load().keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        isScreenOff = false
        WakeSignals.setPowerState(DisplayPowerState.ON)
        resetAmbientTimer()
    }

    private fun leaveAmbientMode() {
        val wasAmbient = isAmbientDimmed
        if (isScreenOff) {
            // Restore the awake-window policy a screen-off may have cleared.
            isScreenOff = false
            WakeSignals.setPowerState(DisplayPowerState.ON)
            if (prefs.load().keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        screensaverWeatherJob?.cancel()
        screensaverWeatherJob = null
        cancelAmbientBackgroundLoading()
        isAmbientDimmed = false
        val screensaver = binding.ambientScreensaver
        val transition = ++ambientTransitionGeneration
        screensaver.animate().cancel()
        if (wasAmbient && screensaver.visibility == View.VISIBLE) {
            screensaver.animate()
                .alpha(0f)
                .scaleX(0.99f)
                .scaleY(0.99f)
                .setStartDelay(0L)
                .setDuration(280L)
                .withEndAction {
                    if (transition == ambientTransitionGeneration) {
                        screensaver.visibility = View.GONE
                        screensaver.alpha = 1f
                        screensaver.scaleX = 1f
                        screensaver.scaleY = 1f
                        screensaver.photoClockMode = false
                        screensaver.releaseCustomMedia()
                        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                    }
                }
                .start()
        } else {
            screensaver.visibility = View.GONE
            screensaver.alpha = 1f
            screensaver.scaleX = 1f
            screensaver.scaleY = 1f
            screensaver.photoClockMode = false
            screensaver.releaseCustomMedia()
            setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
        if (wasAmbient) publishDashboardState()
    }

    private fun startAmbientBackground(settings: KioskSettings) {
        stopAmbientBackground()
        when (settings.ambientBackgroundMode) {
            AmbientBackgroundMode.BUILT_IN -> binding.ambientScreensaver.useBuiltInBackground()
            AmbientBackgroundMode.BUNDLED_IMAGE -> {
                binding.ambientScreensaver.showBundledImage()
            }
            AmbientBackgroundMode.BUNDLED_VIDEO -> {
                binding.ambientScreensaver.showBundledVideo()
            }
            AmbientBackgroundMode.VIDEO_URL -> {
                binding.ambientScreensaver.showCustomVideo(settings.ambientBackgroundUrl)
            }
            AmbientBackgroundMode.IMAGE_URL -> {
                binding.ambientScreensaver.useBuiltInBackground()
                val request = ++ambientBackgroundRequest
                ambientBackgroundJob = scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        AmbientImageLoader.load(settings.ambientBackgroundUrl)
                    }
                    if (request != ambientBackgroundRequest || !isAmbientDimmed ||
                        binding.ambientScreensaver.visibility != View.VISIBLE
                    ) {
                        result.getOrNull()?.recycle()
                        return@launch
                    }
                    result.onSuccess { binding.ambientScreensaver.showCustomImage(it) }
                        .onFailure { Log.w(TAG, "Ambient image load failed", it) }
                }
            }
            AmbientBackgroundMode.IMMICH_ALBUM -> startImmichSlideshow(settings)
        }
    }

    private fun startImmichSlideshow(settings: KioskSettings) {
        binding.ambientScreensaver.useBuiltInBackground()
        val request = ++ambientBackgroundRequest
        ambientBackgroundJob = scope.launch {
            var previousAsset: String? = null
            var pendingBitmap: Bitmap? = null
            try {
                while (isActive && request == ambientBackgroundRequest && isAmbientDimmed) {
                    val assets = withContext(Dispatchers.IO) {
                        ImmichPhotoSource.loadAssetIds(settings.ambientImmichShareUrl)
                    }.getOrNull()
                    if (assets.isNullOrEmpty()) {
                        Log.w(TAG, "Immich album is temporarily unavailable or empty")
                        delay(IMMICH_RETRY_DELAY_MS)
                        continue
                    }
                    var consecutiveFailures = 0
                    for (assetId in shuffledWithoutImmediateRepeat(assets, previousAsset)) {
                        if (!isActive || request != ambientBackgroundRequest || !isAmbientDimmed) break
                        val loaded = withContext(Dispatchers.IO) {
                            ImmichPhotoSource.loadThumbnail(settings.ambientImmichShareUrl, assetId)
                        }.getOrNull()
                        if (loaded == null) {
                            consecutiveFailures++
                            delay(IMMICH_THUMBNAIL_RETRY_DELAY_MS * consecutiveFailures)
                            if (consecutiveFailures >= 3) break
                            continue
                        }
                        consecutiveFailures = 0
                        pendingBitmap = loaded
                        if (request != ambientBackgroundRequest || !isAmbientDimmed ||
                            binding.ambientScreensaver.visibility != View.VISIBLE
                        ) break

                        binding.ambientScreensaver.showCustomImage(loaded)
                        pendingBitmap = null
                        previousAsset = assetId
                        delay(settings.ambientPhotoIntervalSeconds * 1_000L)
                    }
                }
            } finally {
                pendingBitmap?.takeIf { !it.isRecycled }?.recycle()
            }
        }
    }

    private fun shuffledWithoutImmediateRepeat(
        assets: List<String>,
        previous: String?
    ): List<String> {
        val shuffled = assets.shuffled().toMutableList()
        if (shuffled.size > 1 && shuffled.firstOrNull() == previous) {
            val replacement = shuffled.indexOfFirst { it != previous }
            java.util.Collections.swap(shuffled, 0, replacement)
        }
        return shuffled
    }

    private fun stopAmbientBackground() {
        cancelAmbientBackgroundLoading()
        binding.ambientScreensaver.releaseCustomMedia()
    }

    private fun cancelAmbientBackgroundLoading() {
        ambientBackgroundRequest++
        ambientBackgroundJob?.cancel()
        ambientBackgroundJob = null
    }

    private fun startScreensaverWeather(rawLocation: String) {
        screensaverWeatherJob?.cancel()
        val location = rawLocation.trim()
        if (location.isBlank()) {
            binding.ambientScreensaver.showWeatherError(locationMissing = true)
            return
        }

        binding.ambientScreensaver.showWeatherLoading(location)
        screensaverWeatherJob = scope.launch {
            while (isActive && isAmbientDimmed) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { AmbientWeatherClient.fetch(location) }
                }
                if (!isAmbientDimmed) break
                result.onSuccess(binding.ambientScreensaver::showWeather)
                    .onFailure { binding.ambientScreensaver.showWeatherError(locationMissing = false) }
                delay(SCREENSAVER_WEATHER_REFRESH_MS)
            }
        }
    }

    /**
     * Starts, reconfigures, or stops [PresenceWakeService] based on settings.
     * The service runs when presence-wake OR illuminance reporting is enabled;
     * it owns the camera and both sensors so they survive a true screen-off. The
     * Activity still owns the CAMERA runtime grant (only needed for presence
     * wake) because a service cannot request permissions.
     */
    private fun applyPresenceWakePolicy() {
        if (AppVisibility.userExited) return
        val settings = prefs.load()
        val wantPresence = settings.ambientModeEnabled && settings.presenceWakeEnabled
        val wantIlluminance = settings.illuminanceReportingEnabled
        if (!wantPresence && !wantIlluminance) {
            presenceWakePermissionAttempted = false
            stopPresenceWakeService()
            return
        }

        // Only presence wake needs the camera; illuminance alone does not.
        if (wantPresence && !hasAllPermissions(arrayOf(Manifest.permission.CAMERA))) {
            if (!pendingPresenceWakePermissionRequest && !presenceWakePermissionAttempted) {
                presenceWakePermissionAttempted = true
                pendingPresenceWakePermissionRequest = true
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
            if (!wantIlluminance) {
                stopPresenceWakeService()
                return
            }
            // Fall through: still run the service for illuminance while the
            // camera permission prompt is pending.
        }

        if (wantPresence) presenceWakePermissionAttempted = false
        startPresenceWakeService()
    }

    private fun startPresenceWakeService() {
        // startForegroundService also re-delivers to a running service, which
        // triggers its reconfigure path to pick up changed settings. Must be
        // called from a visible context (onResume) — a cold camera FGS throws on
        // Android 14+.
        runCatching { PresenceWakeService.start(this) }
            .onSuccess { presenceWakeServiceRunning = true }
            .onFailure { prefs.recordLastLoadStatus("Presence wake service start failed: ${it.message}") }
    }

    private fun stopPresenceWakeService() {
        if (!presenceWakeServiceRunning) return
        runCatching { PresenceWakeService.stop(this) }
        presenceWakeServiceRunning = false
    }

    private fun maybePromptStartupCameraPermission() {
        val hasCameraPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            prefs.markStartupCameraPromptShown()
            return
        }
        if (!prefs.shouldShowStartupCameraPrompt()) {
            return
        }

        prefs.markStartupCameraPromptShown()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.startup_camera_prompt_title)
            .setMessage(R.string.startup_camera_prompt_message)
            .setNegativeButton(R.string.not_now, null)
            .setPositiveButton(R.string.allow_camera) { _, _ ->
                pendingStartupCameraPermissionRequest = true
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
            .show()
    }

    private fun startLocalControlServer(port: Int) {
        val callbacks = object : LocalControlCallbacks {
            override fun status(): LocalControlStatus {
                val task = FutureTask { currentLocalControlStatus() }
                mainHandler.post(task)
                return try {
                    task.get(LOCAL_CONTROL_UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Status read interrupted", error)
                } catch (error: Exception) {
                    task.cancel(false)
                    throw IllegalStateException("Status read timed out", error)
                }
            }

            override fun capturePreview(): LocalControlPreviewResult = captureCurrentPreview()

            override fun reloadDashboard() {
                mainHandler.post { loadDashboard() }
            }

            override fun restartEngine() {
                mainHandler.post { recreateCurrentEngine() }
            }

            override fun openSettings() {
                mainHandler.post {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }

            override fun openUrl(url: String) {
                mainHandler.post {
                    openDashboardUrl(url)
                }
            }

            override fun showTakeover(url: String, seconds: Int) {
                mainHandler.post {
                    showEventTakeover(
                        WallModeMqttCommand.ShowTakeover(
                            id = "local-control",
                            url = url,
                            seconds = seconds,
                            priority = MqttContract.DEFAULT_TAKEOVER_PRIORITY
                        )
                    )
                }
            }

            override fun dismissTakeover() {
                mainHandler.post { dismissEventTakeover() }
            }

            override fun discoverHomeAssistantServers(): Result<List<HomeAssistantDiscoveryResult>> {
                return runCatching {
                    HomeAssistantDiscovery.discoverCandidates(this@MainActivity)
                }
            }

            override fun connectToHomeAssistantServer(baseUrl: String): Result<Unit> {
                return runLocalSettingsChange {
                    val updated = prefs.load().copy(
                        homeAssistantUrl = KioskPreferences.normalizeBaseUrl(baseUrl)
                    )
                    prefs.save(updated)
                    prefs.recordLastLoadStatus("Control discovery connected: ${updated.homeAssistantUrl}")
                    loadDashboard()
                }
            }

            override fun saveSettings(section: String, params: Map<String, String>): Result<String> {
                return runLocalSettingsChange {
                    val current = prefs.load()
                    if (params["_revision"] != LocalControlSettings.revision(section, current)) {
                        throw LocalControlSettingsException(
                            "These settings changed since this page was opened. Reload this page before saving."
                        )
                    }
                    val updated = try {
                        LocalControlSettings.apply(section, params, current)
                    } catch (error: IllegalArgumentException) {
                        throw LocalControlSettingsException(error.message ?: "Invalid settings")
                    }
                    val mqttPassword = if (section == "device") params["mqttPassword"].orEmpty() else ""
                    val clearPassword = section == "device" && params["clearMqttPassword"] == "on"
                    if (mqttPassword.isNotEmpty() && clearPassword) {
                        throw LocalControlSettingsException("Choose either a new MQTT password or Clear password")
                    }
                    val keepPassword = !clearPassword && prefs.hasMqttPassword() &&
                        MqttContract.brokerIdentity(current.mqttBrokerHost, current.mqttBrokerPort,
                            current.mqttUseTls, current.mqttUsername) ==
                        MqttContract.brokerIdentity(updated.mqttBrokerHost, updated.mqttBrokerPort,
                            updated.mqttUseTls, updated.mqttUsername)
                    if (updated.mqttEnabled && updated.mqttUsername.isBlank() &&
                        (mqttPassword.isNotEmpty() || keepPassword)) {
                        throw LocalControlSettingsException("An MQTT password requires a username")
                    }
                    if (updated.localControlEnabled && updated.localControlPort != current.localControlPort) {
                        try {
                            java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress(updated.localControlPort)) }
                        } catch (_: Exception) {
                            throw LocalControlSettingsException("That control panel port is unavailable; choose another port")
                        }
                    }
                    try {
                        prefs.save(updated)
                    } catch (error: IllegalArgumentException) {
                        throw LocalControlSettingsException(error.message ?: "Invalid settings")
                    }
                    if (clearPassword) prefs.clearMqttPassword()
                    if (mqttPassword.isNotEmpty()) prefs.setMqttPassword(mqttPassword)

                    val saved = prefs.load()
                    activeSettings = saved
                    val wasAmbient = isAmbientDimmed
                    applyWindowSettings()
                    applySchedulers()
                    refreshMqttService()
                    if (section == "dashboard" || section == "browser") loadDashboard()
                    if (wasAmbient && saved.ambientModeEnabled) {
                        ambientDimRunnable?.let(mainHandler::removeCallbacks)
                        enterAmbientMode(saved, saved.ambientScreensaverEnabled)
                    }
                    // Let the HTTP response arrive before closing its server or recreating the theme.
                    mainHandler.postDelayed({
                        if (!isDestroyed) {
                            refreshLocalControlServer()
                            prefs.applyThemeMode()
                            if (section == "system") maybeRunAutoUpdateCheck()
                        }
                    }, 750L)
                    when {
                        !saved.localControlEnabled ->
                            "Settings saved. The browser panel is now disabled; re-enable it on the tablet if needed."
                        saved.localControlPort != current.localControlPort ->
                            "Settings saved. Reconnect and sign in at ${LocalControlServer.discoverPrimaryUrl(saved.localControlPort)}"
                        saved.themeMode != current.themeMode ->
                            "Settings saved. The theme is updating; sign in again if the panel reconnects."
                        else -> "Settings saved and applied."
                    }
                }
            }
        }

        localControlServer = LocalControlServer(prefs, callbacks, port).also { server ->
            server.startSafely()
                .onSuccess {
                    localControlServerPort = server.activePort()
                    val localAddress = server.primaryLocalUrl()
                    prefs.recordLastLoadStatus("Local control: $localAddress")
                }
                .onFailure { error ->
                    prefs.recordLastLoadStatus("Local control start failed: ${error.message}")
                    Log.w(TAG, "Failed to start local control server", error)
                    localControlServer = null
                    localControlServerPort = -1
                }
        }
    }

    private fun <T> runLocalSettingsChange(change: () -> T): Result<T> {
        val task = FutureTask {
            runCatching {
                if (lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) {
                    throw LocalControlSettingsException("Close WallMode settings on the tablet, then save again")
                }
                if (activeEventTakeover != null) {
                    throw LocalControlSettingsException("Wait for the current event or camera view to finish, then save again")
                }
                change()
            }
        }
        mainHandler.post(task)
        return try {
            task.get(LOCAL_CONTROL_UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Exception) {
            task.cancel(false)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Result.failure(LocalControlSettingsException("No save confirmation received. Reload this page before retrying."))
        }
    }

    private fun refreshLocalControlServer() {
        val settings = prefs.load()
        if (!settings.localControlEnabled) {
            stopLocalControlServer()
            return
        }

        val desiredPort = KioskPreferences.clampInt(
            settings.localControlPort,
            KioskPreferences.MIN_LOCAL_CONTROL_PORT,
            KioskPreferences.MAX_LOCAL_CONTROL_PORT
        )
        val shouldRestart = localControlServer == null || localControlServerPort != desiredPort
        if (!shouldRestart) {
            return
        }

        stopLocalControlServer()
        startLocalControlServer(desiredPort)
    }

    private fun stopLocalControlServer() {
        localControlServer?.stopSafely()
        localControlServer = null
        localControlServerPort = -1
    }

    private fun refreshMqttService() {
        val settings = prefs.load()
        if (!settings.mqttEnabled) {
            stopMqttService()
            return
        }
        startMqttService()
        publishDashboardState()
    }

    private fun startMqttService() {
        runCatching { MqttService.start(this) }
            .onSuccess { mqttServiceRunning = true }
            .onFailure { Log.w(TAG, "MQTT service start failed", it) }
    }

    private fun stopMqttService() {
        if (!mqttServiceRunning) return
        runCatching { MqttService.stop(this) }
        mqttServiceRunning = false
    }

    internal fun handleMqttCommand(command: WallModeMqttCommand) {
        when (command) {
            WallModeMqttCommand.Reload -> reloadCurrentTarget()
            WallModeMqttCommand.RestartEngine -> recreateCurrentEngine()
            is WallModeMqttCommand.OpenUrl -> {
                leaveAmbientMode()
                openDashboardUrl(command.url)
                resetAmbientTimer()
            }
            is WallModeMqttCommand.ShowTakeover -> showEventTakeover(command)
            is WallModeMqttCommand.ShowActionCard -> showActionCard(command)
            is WallModeMqttCommand.ShowBanner -> bannerOverlay.show(command.notice, prefs.load().adminButtonCorner)
            is WallModeMqttCommand.ClearBanner -> bannerOverlay.dismiss(command.id)
            is WallModeMqttCommand.Announce -> {
                announcementSpeaker.announce(command.text, command.volume)
            }
            WallModeMqttCommand.StopAnnouncement -> announcementSpeaker.stop()
            is WallModeMqttCommand.ClearTakeover -> dismissEventTakeover(command.id)
            is WallModeMqttCommand.SetAmbientBrightness -> {
                val updated = prefs.load().copy(ambientBrightnessPercent = command.percent)
                prefs.save(updated)
                if (isAmbientDimmed) applyAmbientBrightness(updated)
                publishDashboardState()
            }
            is WallModeMqttCommand.SetAmbientTimeout -> {
                prefs.save(prefs.load().copy(ambientDimAfterSeconds = command.seconds))
                resetAmbientTimer()
                publishDashboardState()
            }
            is WallModeMqttCommand.SetAmbientScene -> {
                val updated = prefs.load().copy(ambientScene = command.scene)
                prefs.save(updated)
                binding.ambientScreensaver.scene = command.scene
                screensaverWeatherJob?.cancel()
                screensaverWeatherJob = null
                if (isAmbientDimmed &&
                    binding.ambientScreensaver.visibility == View.VISIBLE &&
                    command.scene == AmbientScene.AURORA_WEATHER
                ) {
                    startScreensaverWeather(updated.ambientWeatherLocation)
                }
                publishDashboardState()
            }
            is WallModeMqttCommand.OpenProfile -> openDashboardProfile(command.profile)
            is WallModeMqttCommand.SetProfileSchedule -> {
                val settings = prefs.load()
                if (command.enabled && !DashboardProfileSchedule.hasValidStartHours(
                        settings.homeStartHour,
                        settings.wallStartHour,
                        settings.nightStartHour
                    )
                ) {
                    prefs.recordMqttState("profile schedule rejected: invalid start hours")
                    publishDashboardState()
                    return
                }
                prefs.save(settings.copy(scheduleProfilesEnabled = command.enabled))
                scheduleProfileSwitching()
                if (command.enabled) {
                    if (activeEventTakeover == null) loadDashboard() else pendingScheduledProfile = true
                } else {
                    publishDashboardState()
                }
            }
            is WallModeMqttCommand.SetBrowserEngine -> {
                val current = prefs.load()
                if (current.browserEngine != command.engine) {
                    prefs.save(current.copy(browserEngine = command.engine))
                    recreateCurrentEngine()
                } else {
                    publishDashboardState()
                }
            }
            is WallModeMqttCommand.SetDisplayMode -> {
                dismissEventTakeover(restoreDisplayMode = false)
                ambientDimRunnable?.let(mainHandler::removeCallbacks)
                ambientDimRunnable = null
                leaveAmbientMode()
                val settings = prefs.load()
                when (command.mode) {
                    MqttContract.DISPLAY_DIM ->
                        enterAmbientMode(settings, showScreensaver = false, deepSleep = false)
                    MqttContract.DISPLAY_SCREENSAVER ->
                        enterAmbientMode(settings, showScreensaver = true, deepSleep = false)
                    MqttContract.DISPLAY_OFF ->
                        enterAmbientMode(settings, showScreensaver = false, deepSleep = true)
                    else -> resetAmbientTimer()
                }
                publishDashboardState()
            }
        }
    }

    private fun publishDashboardState() {
        val settings = prefs.load()
        val displayMode = currentDisplayMode()
        val takeover = activeEventTakeover
        val dashboardProblem = mainFrameLoadState.hasProblem ||
            binding.recoveryOverlay.visibility == View.VISIBLE
        MqttBridge.publishDashboardState(
            DashboardState(
                currentPage = binding.webView.url ?: loadedDashboardUrl,
                displayMode = displayMode,
                screensaverActive = displayMode == MqttContract.DISPLAY_SCREENSAVER,
                dashboardProblem = dashboardProblem,
                ambientBrightness = settings.ambientBrightnessPercent,
                ambientTimeout = settings.ambientDimAfterSeconds,
                ambientScene = MqttContract.ambientSceneName(settings.ambientScene),
                activeProfile = MqttContract.profileName(activeDashboardProfile),
                profileScheduleEnabled = settings.scheduleProfilesEnabled,
                browserEngine = if (settings.browserEngine == BrowserEngine.WEBVIEW) {
                    MqttContract.ENGINE_WEBVIEW
                } else {
                    MqttContract.ENGINE_CHROMIUM
                },
                takeoverActive = takeover != null,
                takeoverId = takeover?.id.orEmpty(),
                takeoverPriority = takeover?.priority ?: 0
            )
        )
    }

    private fun currentDisplayMode(): String =
        DisplayModeResolver.displayMode(
            isAmbientDimmed = isAmbientDimmed,
            isScreenOff = isScreenOff,
            screensaverVisible = binding.ambientScreensaver.visibility == View.VISIBLE
        )

    private fun reloadCurrentTarget() {
        val takeover = activeEventTakeover
        if (takeover == null) {
            loadDashboard()
        } else if (takeover.actionCard != null) {
            showActionCardUi(takeover.actionCard)
        } else {
            loadWebUrl(takeover.url ?: return, prefs.load(), rememberAsDashboard = false)
        }
    }

    private fun openDashboardProfile(profile: DashboardProfile) {
        if (activeEventTakeover != null) return
        val settings = prefs.load()
        val path = DashboardProfileSchedule.path(profile, settings)
        openDashboardUrl(prefs.buildDashboardUrl(settings, path), settings, profile)
    }

    private fun showEventTakeover(command: WallModeMqttCommand.ShowTakeover) {
        val current = activeEventTakeover
        if (current != null && current.id != command.id && command.priority < current.priority) return

        val returnUrl = current?.returnUrl
            ?: binding.webView.url?.takeIf(KioskPreferences::isHttpOrHttpsUrl)
            ?: loadedDashboardUrl.takeIf(KioskPreferences::isHttpOrHttpsUrl)
            ?: prefs.buildDashboardUrl(prefs.load())
        val returnMode = current?.returnDisplayMode ?: currentDisplayMode()
        activeEventTakeover = ActiveEventTakeover(
            id = command.id,
            url = command.url,
            actionCard = null,
            priority = if (current?.id == command.id) maxOf(current.priority, command.priority) else command.priority,
            returnUrl = returnUrl,
            returnDisplayMode = returnMode,
            expiresAtElapsedRealtime = SystemClock.elapsedRealtime() + command.seconds * 1_000L,
            webContentReplaced = true
        )
        eventTakeoverTimeout?.let(mainHandler::removeCallbacks)
        ambientDimRunnable?.let(mainHandler::removeCallbacks)
        ambientDimRunnable = null
        leaveAmbientMode()
        hideActionCardUi()
        loadWebUrl(command.url, prefs.load(), rememberAsDashboard = false)
        scheduleEventTakeoverTimeout(activeEventTakeover!!)
        publishDashboardState()
    }

    private fun showActionCard(command: WallModeMqttCommand.ShowActionCard) {
        val current = activeEventTakeover
        if (current != null && current.id != command.id && command.priority < current.priority) return

        val returnUrl = current?.returnUrl
            ?: binding.webView.url?.takeIf(KioskPreferences::isHttpOrHttpsUrl)
            ?: loadedDashboardUrl.takeIf(KioskPreferences::isHttpOrHttpsUrl)
            ?: prefs.buildDashboardUrl(prefs.load())
        val returnMode = current?.returnDisplayMode ?: currentDisplayMode()
        activeEventTakeover = ActiveEventTakeover(
            id = command.id,
            url = null,
            actionCard = command,
            priority = if (current?.id == command.id) maxOf(current.priority, command.priority) else command.priority,
            returnUrl = returnUrl,
            returnDisplayMode = returnMode,
            expiresAtElapsedRealtime = SystemClock.elapsedRealtime() + command.seconds * 1_000L,
            webContentReplaced = current?.webContentReplaced == true
        )
        eventTakeoverTimeout?.let(mainHandler::removeCallbacks)
        ambientDimRunnable?.let(mainHandler::removeCallbacks)
        ambientDimRunnable = null
        leaveAmbientMode()
        showActionCardUi(command)
        scheduleEventTakeoverTimeout(activeEventTakeover!!)
        publishDashboardState()
    }

    private fun showActionCardUi(card: WallModeMqttCommand.ShowActionCard) {
        binding.actionCardTitle.text = card.title
        binding.actionCardMessage.apply {
            text = card.message
            visibility = if (card.message.isBlank()) View.GONE else View.VISIBLE
        }
        binding.actionCardError.visibility = View.GONE
        val buttons = listOf<MaterialButton>(
            binding.actionCardButton1,
            binding.actionCardButton2,
            binding.actionCardButton3
        )
        buttons.forEachIndexed { index, button ->
            val action = card.actions.getOrNull(index)
            button.visibility = if (action == null) View.GONE else View.VISIBLE
            button.isEnabled = true
            button.text = action?.label.orEmpty()
            button.setOnClickListener(
                action?.let { selected ->
                    View.OnClickListener { sendActionCardResponse(card.id, selected.id, buttons) }
                }
            )
        }
        binding.actionCardOverlay.visibility = View.VISIBLE
        binding.adminTapZone.bringToFront()
    }

    private fun sendActionCardResponse(
        cardId: String,
        actionId: String,
        buttons: List<MaterialButton>
    ) {
        buttons.forEach { it.isEnabled = false }
        if (MqttBridge.publishActionResponse(cardId, actionId)) {
            dismissEventTakeover(cardId)
        } else {
            binding.actionCardError.visibility = View.VISIBLE
            buttons.filter { it.visibility == View.VISIBLE }.forEach { it.isEnabled = true }
        }
    }

    private fun hideActionCardUi() {
        binding.actionCardOverlay.visibility = View.GONE
    }

    private fun restoreEventTakeover(state: Bundle?) {
        if (state == null) return
        activeDashboardProfile = DashboardProfile.entries.firstOrNull {
            it.name == state.getString(STATE_ACTIVE_PROFILE)
        }
        pendingScheduledProfile = state.getBoolean(STATE_PENDING_SCHEDULED_PROFILE, false)
        val id = state.getString(STATE_TAKEOVER_ID).orEmpty()
        val url = state.getString(STATE_TAKEOVER_URL).orEmpty()
        val actionCard = state.getString(STATE_TAKEOVER_CARD)?.let { payload ->
            MqttContract.parseCommand("takeover", payload, retained = false)
                as? WallModeMqttCommand.ShowActionCard
        }
        val returnUrl = state.getString(STATE_TAKEOVER_RETURN_URL).orEmpty()
        val returnMode = state.getString(STATE_TAKEOVER_RETURN_MODE).orEmpty()
        val expiresAt = state.getLong(STATE_TAKEOVER_EXPIRES_AT)
        if (id.isBlank() ||
            (actionCard != null && actionCard.id != id) ||
            (actionCard == null && !KioskPreferences.isHttpOrHttpsUrl(url)) ||
            !KioskPreferences.isHttpOrHttpsUrl(returnUrl) ||
            expiresAt <= SystemClock.elapsedRealtime()
        ) return
        activeEventTakeover = ActiveEventTakeover(
            id = id,
            url = url.takeIf { actionCard == null },
            actionCard = actionCard,
            priority = state.getInt(STATE_TAKEOVER_PRIORITY),
            returnUrl = returnUrl,
            returnDisplayMode = returnMode,
            expiresAtElapsedRealtime = expiresAt,
            webContentReplaced = state.getBoolean(STATE_TAKEOVER_REPLACED_WEB, actionCard == null)
        )
    }

    private fun resumeEventTakeover(): Boolean {
        val takeover = activeEventTakeover ?: return false
        takeover.actionCard?.let(::showActionCardUi)
            ?: loadWebUrl(takeover.url ?: return false, prefs.load(), rememberAsDashboard = false)
        scheduleEventTakeoverTimeout(takeover)
        return true
    }

    private fun scheduleEventTakeoverTimeout(takeover: ActiveEventTakeover) {
        eventTakeoverTimeout?.let(mainHandler::removeCallbacks)
        val remaining = takeover.expiresAtElapsedRealtime - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            dismissEventTakeover(takeover.id)
            return
        }
        eventTakeoverTimeout = Runnable { dismissEventTakeover(takeover.id) }
            .also { mainHandler.postDelayed(it, remaining) }
    }

    private fun dismissEventTakeover(id: String? = null, restoreDisplayMode: Boolean = true) {
        val takeover = activeEventTakeover ?: return
        if (id != null && takeover.id != id) return
        cancelEventTakeover()
        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = null
        if (pendingScheduledProfile) {
            pendingScheduledProfile = false
            loadDashboard()
        } else if (takeover.webContentReplaced) {
            loadWebUrl(takeover.returnUrl, prefs.load(), rememberAsDashboard = true)
        }
        if (restoreDisplayMode) {
            when (takeover.returnDisplayMode) {
                MqttContract.DISPLAY_DIM ->
                    enterAmbientMode(prefs.load(), showScreensaver = false, deepSleep = false)
                MqttContract.DISPLAY_SCREENSAVER ->
                    enterAmbientMode(prefs.load(), showScreensaver = true, deepSleep = false)
                MqttContract.DISPLAY_OFF ->
                    enterAmbientMode(prefs.load(), showScreensaver = false, deepSleep = true)
                else -> resetAmbientTimer()
            }
        }
        publishDashboardState()
    }

    private fun cancelEventTakeover(): Boolean {
        val hadTakeover = activeEventTakeover != null
        eventTakeoverTimeout?.let(mainHandler::removeCallbacks)
        eventTakeoverTimeout = null
        activeEventTakeover = null
        hideActionCardUi()
        return hadTakeover
    }

    private fun actionCardPayload(card: WallModeMqttCommand.ShowActionCard): String {
        return JSONObject().apply {
            put("action", "show")
            put("kind", "card")
            put("id", card.id)
            put("title", card.title)
            put("message", card.message)
            put("priority", card.priority)
            put("actions", JSONArray().apply {
                card.actions.forEach { action ->
                    put(JSONObject().put("id", action.id).put("label", action.label))
                }
            })
        }.toString()
    }

    /**
     * UI reaction to a motion detection. Invoked on the main thread from the
     * [WakeSignals] collector (fed by [PresenceWakeService]) and directly from
     * instrumentation tests. When the panel is off it is woken via
     * [wakeScreenFromMotion]; otherwise this just undims / resets the idle timer.
     * The hardware wake pulse, the screen-off cooldown, and the MQTT motion
     * publish (with its own cooldown) live in the service.
     */
    internal fun onPresenceDetected() {
        mainHandler.post {
            val settings = prefs.load()
            if (!settings.presenceWakeEnabled || !settings.ambientModeEnabled) return@post
            if (isScreenOff) {
                wakeScreenFromMotion()
                return@post
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@post
            if (isAmbientDimmed) {
                Log.i(TAG, "Motion detected; leaving ambient mode")
            }
            resetAmbientTimer()
        }
    }

    private fun setWindowBrightness(value: Float) {
        val params = window.attributes
        params.screenBrightness = value
        window.attributes = params
    }

    private fun applyAmbientBrightness(settings: KioskSettings = prefs.load()) {
        setWindowBrightness(
            if (settings.ambientFollowSystemBrightness) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                settings.ambientBrightnessPercent / 100f
            }
        )
    }

    private fun currentLocalControlStatus(): LocalControlStatus {
        val settings = prefs.load()
        val diagnostics = prefs.diagnosticsSnapshot()
        val dashboard = loadedDashboardUrl.ifBlank { prefs.buildDashboardUrl(settings) }
        val takeover = activeEventTakeover
        val currentPage = binding.webView.url ?: takeover?.url ?: dashboard
        val networkConnected = connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
        val dashboardProblem = mainFrameLoadState.hasProblem ||
            binding.recoveryOverlay.visibility == View.VISIBLE
        val health = when {
            !networkConnected -> "offline"
            dashboardProblem -> "recovering"
            else -> "ok"
        }
        return LocalControlStatus(
            appVersion = currentVersionName(),
            engine = (currentEngine ?: settings.browserEngine).name,
            dashboardUrl = dashboard,
            homeAssistantUrl = settings.homeAssistantUrl,
            dashboardPath = settings.dashboardPath,
            appendKiosk = settings.appendKiosk,
            reloadIntervalSeconds = settings.reloadIntervalSeconds,
            autoReloadOnFailure = settings.autoReloadOnFailure,
            fullscreen = settings.fullscreen,
            keepScreenOn = settings.keepScreenOn,
            mqttEnabled = settings.mqttEnabled,
            takeoverActive = takeover != null,
            diagnostics = diagnostics,
            health = health,
            displayMode = if (takeover != null) "Temporary view" else currentDisplayMode(),
            currentPage = currentPage,
            lastError = when (health) {
                "offline" -> diagnostics.lastNetworkState
                "recovering" -> diagnostics.lastLoadStatus
                else -> "-"
            }
        )
    }

    private fun captureCurrentPreview(): LocalControlPreviewResult {
        val capture = CompletableFuture<Bitmap?>()
        mainHandler.post {
            val bitmap = runCatching {
                val source = binding.root
                if (isDestroyed || !hasWindowFocus() || !source.isAttachedToWindow ||
                    source.width <= 0 || source.height <= 0
                ) {
                    null
                } else {
                    val scale = minOf(
                        1f,
                        PREVIEW_MAX_DIMENSION_PX.toFloat() / maxOf(source.width, source.height)
                    )
                    Bitmap.createBitmap(
                        maxOf(1, (source.width * scale).toInt()),
                        maxOf(1, (source.height * scale).toInt()),
                        Bitmap.Config.RGB_565
                    ).also { preview ->
                        Canvas(preview).apply {
                            scale(scale, scale)
                            source.draw(this)
                            if (binding.ambientScreensaver.visibility == View.VISIBLE) {
                                binding.ambientScreensaver.drawPreview(this)
                            }
                        }
                    }
                }
            }.getOrNull()
            if (!capture.complete(bitmap)) {
                bitmap?.recycle()
            }
        }
        val bitmap = try {
            capture.get(PREVIEW_CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            capture.complete(null)
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            capture.complete(null)
            null
        } ?: return LocalControlPreviewResult.Unavailable("WallMode is not visible")

        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, output)) {
                    return LocalControlPreviewResult.Unavailable("Preview encoding failed")
                }
                output.toByteArray()
            }
            LocalControlPreviewResult.Jpeg(bytes)
        } finally {
            bitmap.recycle()
        }
    }

    private fun registerNetworkMonitoring() {
        if (networkCallbackRegistered) {
            return
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
            prefs.recordNetworkState("registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
            prefs.recordNetworkState("register_failed")
        }
    }

    private fun unregisterNetworkMonitoring() {
        if (!networkCallbackRegistered) {
            return
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Callback may already be unregistered.
        } finally {
            networkCallbackRegistered = false
        }
    }

    private fun scheduleRetry(reason: String) {
        val settings = prefs.load()
        if (!settings.autoReloadOnFailure) {
            prefs.recordLastLoadStatus("Auto-reload disabled ($reason)")
            showRecoveryOverlay(
                getString(R.string.recovery_auto_reload_disabled),
                getString(R.string.recovery_open_settings_hint)
            )
            return
        }

        val delayMs = settings.reloadIntervalSeconds * 1_000L
        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = Runnable { reloadCurrentTarget() }
        mainHandler.postDelayed(pendingRetry!!, delayMs)
        prefs.recordLastLoadStatus("Reload in ${settings.reloadIntervalSeconds}s ($reason)")

        showRecoveryOverlay(
            getString(R.string.recovery_retry_scheduled),
            getString(R.string.recovery_retry_details, settings.reloadIntervalSeconds)
        )

        Log.w(TAG, "Scheduled reload in ${settings.reloadIntervalSeconds}s. Reason: $reason")
    }

    /**
     * Shared "main frame became visible" path used by onPageCommitVisible and
     * the onPageFinished safety net. Marks the load state visible (adopting any
     * redirect target), cancels the timeout/retry, hides the loading and recovery
     * overlays, and records the final URL. No-op when not currently LOADING.
     */
    private fun markMainFrameVisible(url: String?, status: String) {
        if (!mainFrameLoadState.markVisible(url)) return
        cancelMainFrameLoadTimeout()
        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = null
        binding.dashboardFallback.visibility = View.GONE
        binding.recoveryOverlay.visibility = View.GONE
        if (activeEventTakeover == null && !url.isNullOrBlank()) {
            loadedDashboardUrl = url
            prefs.recordLastUrl(url)
        }
        prefs.recordLastLoadStatus(status)
        publishDashboardState()
    }

    private fun beginMainFrameLoad(url: String?) {
        val attempt = mainFrameLoadState.begin(url)
        cancelMainFrameLoadTimeout()
        binding.dashboardFallbackMessage.setText(R.string.dashboard_loading)
        binding.dashboardFallback.visibility = View.VISIBLE
        binding.recoveryOverlay.visibility = View.GONE
        mainFrameLoadTimeout = Runnable {
            if (!mainFrameLoadState.markFailed(attempt)) return@Runnable
            binding.webView.stopLoading()
            showMainFrameFailure("WebView load timeout")
        }.also { mainHandler.postDelayed(it, MAIN_FRAME_LOAD_TIMEOUT_MS) }
    }

    private fun handleMainFrameFailure(
        reason: String,
        url: String? = null,
        force: Boolean = false
    ) {
        val marked = when {
            force -> mainFrameLoadState.forceFailed()
            url != null -> mainFrameLoadState.markFailed(url)
            else -> mainFrameLoadState.markFailed()
        }
        if (!marked) return
        cancelMainFrameLoadTimeout()
        showMainFrameFailure(reason)
    }

    private fun showMainFrameFailure(reason: String) {
        binding.dashboardFallbackMessage.setText(R.string.dashboard_unavailable)
        binding.dashboardFallback.visibility = View.VISIBLE
        prefs.recordLastLoadStatus(reason)
        scheduleRetry(reason)
        publishDashboardState()
    }

    private fun cancelMainFrameLoadTimeout() {
        mainFrameLoadTimeout?.let(mainHandler::removeCallbacks)
        mainFrameLoadTimeout = null
    }

    private fun showRecoveryOverlay(title: String, details: String) {
        binding.recoveryOverlay.visibility = View.VISIBLE
        binding.recoveryMessage.text = title
        binding.recoveryDetails.text = details
    }

    private fun exitKioskNow() {
        AppVisibility.userExited = true
        stopPresenceWakeService()
        stopMqttService()
        attemptStopLockTaskIfRunning()
        finishAndRemoveTask()
    }

    private fun maybeRunAutoUpdateCheck() {
        val settings = prefs.load()
        if (!settings.updatesEnabled || updateCheckInProgress || !KioskPreferences.isValidRepoSlug(settings.updatesRepo)) {
            return
        }

        val now = System.currentTimeMillis()
        val rawPrefs = getSharedPreferences(RAW_PREFS_NAME, MODE_PRIVATE)
        val lastCheck = rawPrefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
        val cooldown = settings.updateCheckIntervalHours * 3_600_000L
        if (now - lastCheck < cooldown) {
            return
        }

        updateCheckInProgress = true
        rawPrefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, now).apply()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateManager.fetchLatestRelease(settings.updatesRepo)
            }
            updateCheckInProgress = false

            result.onSuccess { release ->
                val currentLabel = UpdateManager.normalizedVersionLabel(currentVersionName())
                val latestLabel = UpdateManager.normalizedVersionLabel(release.tagName)
                val hasUpdate = UpdateManager.isNewerRelease(currentVersionName(), release.tagName)
                prefs.recordUpdateState(
                    if (hasUpdate) {
                        "update $latestLabel available (installed $currentLabel)"
                    } else {
                        "installed $currentLabel is up-to-date (latest $latestLabel)"
                    }
                )
                if (hasUpdate) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_available_toast, latestLabel),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { error ->
                prefs.recordUpdateState("update check failed: ${error.message}")
            }
        }
    }

    companion object {
        const val EXTRA_WAKE_FROM_MOTION = "io.github.rvbcrs.wallmode.extra.WAKE_FROM_MOTION"
        private const val TAG = "WallModeMain"
        private const val ADMIN_GESTURE_TAP_COUNT = 5
        private const val ADMIN_GESTURE_WINDOW_MS = 2_500L
        private const val IMMICH_RETRY_DELAY_MS = 30_000L
        private const val IMMICH_THUMBNAIL_RETRY_DELAY_MS = 5_000L
        private const val QUICK_RELOAD_DELAY_MS = 1_200L
        private const val SCREENSAVER_WEATHER_REFRESH_MS = 30 * 60_000L
        private const val MAIN_FRAME_LOAD_TIMEOUT_MS = 30_000L
        private const val PREVIEW_MAX_DIMENSION_PX = 720
        private const val PREVIEW_CAPTURE_TIMEOUT_SECONDS = 3L
        private const val PREVIEW_JPEG_QUALITY = 65
        private const val LOCAL_CONTROL_UI_TIMEOUT_SECONDS = 3L
        private const val CHROMIUM_FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 15; Tablet) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private const val RAW_PREFS_NAME = "wallmode_prefs"
        private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
        private const val STATE_TAKEOVER_ID = "takeover_id"
        private const val STATE_TAKEOVER_URL = "takeover_url"
        private const val STATE_TAKEOVER_CARD = "takeover_card"
        private const val STATE_TAKEOVER_PRIORITY = "takeover_priority"
        private const val STATE_TAKEOVER_RETURN_URL = "takeover_return_url"
        private const val STATE_TAKEOVER_RETURN_MODE = "takeover_return_mode"
        private const val STATE_TAKEOVER_EXPIRES_AT = "takeover_expires_at"
        private const val STATE_TAKEOVER_REPLACED_WEB = "takeover_replaced_web"
        private const val STATE_ACTIVE_PROFILE = "active_profile"
        private const val STATE_PENDING_SCHEDULED_PROFILE = "pending_scheduled_profile"
        private const val STATE_PRESENCE_PERMISSION_ATTEMPTED = "presence_permission_attempted"
    }

    private fun currentVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }
}

internal class MotionWakeDetector {
    private var reference: IntArray? = null
    private var warmupFrames = 0
    private var motionFrames = 0

    @Synchronized
    fun reset() {
        reference = null
        warmupFrames = 0
        motionFrames = 0
    }

    @Synchronized
    fun detect(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Boolean {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return false

        val current = IntArray(SAMPLE_COLUMNS * SAMPLE_ROWS)
        var sampleIndex = 0
        for (row in 0 until SAMPLE_ROWS) {
            val y = ((row + 0.5) * height / SAMPLE_ROWS).toInt().coerceAtMost(height - 1)
            for (column in 0 until SAMPLE_COLUMNS) {
                val x = ((column + 0.5) * width / SAMPLE_COLUMNS).toInt().coerceAtMost(width - 1)
                val bufferIndex = y * rowStride + x * pixelStride
                if (bufferIndex >= buffer.limit()) {
                    reset()
                    return false
                }
                current[sampleIndex++] = buffer.get(bufferIndex).toInt() and 0xff
            }
        }

        if (warmupFrames < WARMUP_FRAME_COUNT) {
            reference = current
            warmupFrames += 1
            motionFrames = 0
            return false
        }
        val previous = reference ?: return false

        val averageBrightnessChange = current.indices
            .sumOf { current[it] - previous[it] }
            .toDouble() / current.size
        val changedSamples = current.indices.count {
            kotlin.math.abs((current[it] - previous[it]) - averageBrightnessChange) >= PIXEL_DELTA
        }
        val motion = changedSamples >= current.size * MIN_CHANGED_RATIO

        if (!motion) {
            reference = current
            motionFrames = 0
            return false
        }

        motionFrames += 1
        if (motionFrames < REQUIRED_MOTION_FRAMES) return false

        reference = current
        motionFrames = 0
        return true
    }

    private companion object {
        const val SAMPLE_COLUMNS = 32
        const val SAMPLE_ROWS = 24
        const val PIXEL_DELTA = 16
        const val MIN_CHANGED_RATIO = 0.08
        const val REQUIRED_MOTION_FRAMES = 2
        const val WARMUP_FRAME_COUNT = 8
    }
}

internal class ProximityWakeGate {
    private var farSeen = false

    fun reset() {
        farSeen = false
    }

    fun update(near: Boolean): Boolean {
        if (!near) {
            farSeen = true
            return false
        }
        if (!farSeen) return false
        farSeen = false
        return true
    }
}

internal class MainFrameLoadState {
    private enum class Phase { IDLE, LOADING, VISIBLE, FAILED }

    private var phase = Phase.IDLE
    private var attempt = 0
    private var currentUrl: String? = null

    val isVisible: Boolean get() = phase == Phase.VISIBLE
    val isLoading: Boolean get() = phase == Phase.LOADING
    val hasProblem: Boolean get() = phase != Phase.VISIBLE

    fun begin(url: String? = null): Int {
        attempt += 1
        phase = Phase.LOADING
        currentUrl = url
        return attempt
    }

    fun updateCurrentUrl(url: String?) {
        if (phase == Phase.LOADING && !url.isNullOrBlank()) currentUrl = url
    }

    fun isCurrentUrl(url: String?): Boolean {
        return currentUrl == null || mainFrameCallbackMatches(url, currentUrl)
    }

    fun markVisible(url: String? = currentUrl): Boolean {
        // A main-frame commit-visible (or finished) during our active load is
        // authoritative, even when the server redirected to a different URL than
        // we requested (http->https, trailing slash, /lovelace -> /lovelace/0,
        // auth round-trips). Adopt the final URL rather than rejecting the match,
        // so the loading overlay reliably clears once the page actually renders.
        if (phase != Phase.LOADING) return false
        if (!url.isNullOrBlank()) currentUrl = url
        phase = Phase.VISIBLE
        return true
    }

    fun markFailed(expectedAttempt: Int = attempt): Boolean {
        if (phase != Phase.LOADING || expectedAttempt != attempt) return false
        phase = Phase.FAILED
        return true
    }

    fun markFailed(url: String?): Boolean {
        if (!isCurrentUrl(url)) return false
        return markFailed()
    }

    fun forceFailed(): Boolean {
        if (phase == Phase.FAILED) return false
        phase = Phase.FAILED
        return true
    }
}

internal fun mainFrameCallbackMatches(callbackUrl: String?, currentUrl: String?): Boolean {
    if (callbackUrl.isNullOrBlank() || currentUrl.isNullOrBlank()) return false
    return callbackUrl.substringBefore('#') == currentUrl.substringBefore('#')
}

internal fun dashboardProfileForLoad(
    scheduleEnabled: Boolean,
    activeProfile: DashboardProfile?,
    scheduledProfile: DashboardProfile
): DashboardProfile? = if (scheduleEnabled) scheduledProfile else activeProfile
