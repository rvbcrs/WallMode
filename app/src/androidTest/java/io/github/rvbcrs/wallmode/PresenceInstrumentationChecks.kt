package io.github.rvbcrs.wallmode

import android.app.Instrumentation
import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import java.io.File

/** Opt-in emulator check: real camera startup, then deterministic injected presence events. */
internal object PresenceInstrumentationChecks {
    fun run(instrumentation: Instrumentation, permissionDenied: Boolean = false) {
        check(Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE == "ranchu") {
            "Presence fixture requires an emulator"
        }
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        val prefs = KioskPreferences(instrumentation.targetContext)
        val before = prefs.load()
        if (permissionDenied) {
            check(instrumentation.targetContext.checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) { "Revoke emulator camera permission before the denial check" }
            prefs.markStartupCameraPromptShown()
        }
        val fixture = before.copy(homeAssistantUrl = "http://127.0.0.1:9", dashboardPath = "",
            mqttEnabled = false, localControlEnabled = false, autoDiscoverHomeAssistant = false,
            updatesEnabled = false, watchdogEnabled = false, maintenanceEnabled = false,
            scheduleProfilesEnabled = false, autoReloadOnFailure = false, reloadIntervalSeconds = 0,
            lockTaskMode = false, ambientModeEnabled = true, ambientScreensaverEnabled = true,
            ambientBackgroundMode = AmbientBackgroundMode.BUILT_IN, ambientScene = AmbientScene.GLOW_CLOCK,
            ambientDimAfterSeconds = 15, presenceWakeEnabled = true, presenceWakeCooldownSeconds = 30)
        var activity: MainActivity? = null
        var settingsActivity: SettingsActivity? = null
        try {
            onMain {
                val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_WallMode_Main)
                val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
                val fallback = root.findViewById<LinearLayout>(R.id.dashboard_fallback)
                for ((width, height) in listOf(492 to 328, 328 to 492)) {
                    val density = context.resources.displayMetrics.density
                    val w = (width * density).toInt()
                    val h = (height * density).toInt()
                    fallback.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    fallback.layout(0, 0, w, h)
                    check((fallback.getChildAt(0) as ImageView).drawable != null)
                    for (index in 0 until fallback.childCount) {
                        val child = fallback.getChildAt(index)
                        check(child.left >= 0 && child.right <= w && child.top >= 0 && child.bottom <= h)
                        if (child is TextView) for (line in 0 until child.layout.lineCount) {
                            check(child.layout.getEllipsisCount(line) == 0 && child.layout.getLineWidth(line) <= child.width)
                        }
                    }
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    fallback.draw(Canvas(bitmap))
                    File(instrumentation.targetContext.getExternalFilesDir(null), "loading-fixture-${width}x$height.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                root.findViewById<android.webkit.WebView>(R.id.web_view).destroy()
            }
            prefs.save(fixture)
            var main = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = main
            fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).run {
                isAccessible = true; get(main)
            }
            fun ambient() = field("isAmbientDimmed") as Boolean
            fun motion() { main.onPresenceDetected(); instrumentation.waitForIdleSync() }

            if (permissionDenied) {
                val automation = instrumentation.uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                }
                fun denyButton(): AccessibilityNodeInfo? {
                    val window = automation.rootInActiveWindow ?: return null
                    return listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
                        .firstNotNullOfOrNull { owner -> window.findAccessibilityNodeInfosByViewId(
                            "$owner:id/permission_deny_button").firstOrNull() }
                }
                fun awaitReady(predicate: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (predicate()) return
                        SystemClock.sleep(100)
                    }
                    error("Permission denial did not settle")
                }
                fun checkDenied() {
                    awaitReady {
                        var resumed = false
                        onMain { resumed = main.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            field("pendingPresenceWakePermissionRequest") == false }
                        resumed
                    }
                    SystemClock.sleep(500)
                    check(denyButton() == null) { "Camera permission was requested again after denial" }
                    onMain {
                        check(main.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        check(field("presenceWakePermissionAttempted") == true && field("presenceWakeServiceRunning") == false)
                        check(prefs.load().presenceWakeEnabled) { "Camera denial disabled proximity presence" }
                    }
                }
                var deny: AccessibilityNodeInfo? = null
                awaitReady { deny = denyButton(); deny != null }
                check(checkNotNull(deny).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                checkDenied()
                val recreated = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    onMain { main.recreate() }
                    main = checkNotNull(instrumentation.waitForMonitorWithTimeout(recreated, 10_000) as? MainActivity)
                    activity = main
                    checkDenied()
                } finally { instrumentation.removeMonitor(recreated) }
                return
            }

            val serviceDeadline = SystemClock.elapsedRealtime() + 10_000
            while (field("presenceWakeServiceRunning") != true && SystemClock.elapsedRealtime() < serviceDeadline) {
                SystemClock.sleep(250)
            }
            onMain {
                check(field("presenceWakeServiceRunning") == true) { "Activity did not start the presence wake service" }
                check(!ambient()) { "Service startup reached ambient before any awake motion" }
            }
            motion()
            val originalTimer = field("ambientDimRunnable")
            SystemClock.sleep(10_000)
            motion()
            onMain { check(!ambient() && field("ambientDimRunnable") !== originalTimer) }
            SystemClock.sleep(6_000)
            onMain { check(!ambient()) { "Motion did not postpone the original idle deadline" } }
            SystemClock.sleep(9_500)
            onMain { check(ambient()) { "Screensaver did not start after 15 seconds without motion" } }
            motion()
            onMain { check(!ambient()) { "Motion did not wake the screensaver" } }
            val wakeTimer = field("ambientDimRunnable")
            motion()
            onMain {
                check(field("ambientDimRunnable") !== wakeTimer) { "Wake cooldown blocked an awake timer reset" }
                main.handleMqttCommand(WallModeMqttCommand.SetDisplayMode(MqttContract.DISPLAY_SCREENSAVER))
            }
            motion()
            onMain { check(ambient()) { "Wake cooldown was ignored while ambient" } }
            onMain { main.handleMqttCommand(WallModeMqttCommand.SetDisplayMode(MqttContract.DISPLAY_DASHBOARD)) }
            val disabledTimer = field("ambientDimRunnable")
            onMain { main.onPresenceDetected(); prefs.save(fixture.copy(presenceWakeEnabled = false)) }
            instrumentation.waitForIdleSync()
            onMain { check(field("ambientDimRunnable") === disabledTimer) { "Queued motion ignored disabled presence" } }
            prefs.save(fixture)
            settingsActivity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            val pausedTimer = field("ambientDimRunnable")
            motion()
            onMain {
                check(!main.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                check(field("ambientDimRunnable") === pausedTimer) {
                    "Stopped activity accepted motion and reset the idle timer"
                }
            }
        } finally {
            onMain { activity?.finish(); settingsActivity?.finish() }
            instrumentation.waitForIdleSync()
            prefs.save(before)
        }
    }
}
