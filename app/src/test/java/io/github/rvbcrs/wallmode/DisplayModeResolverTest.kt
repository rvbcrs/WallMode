package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModeResolverTest {
    @Test
    fun dashboardWhenNotDimmed() {
        assertEquals(
            MqttContract.DISPLAY_DASHBOARD,
            DisplayModeResolver.displayMode(
                isAmbientDimmed = false,
                isScreenOff = false,
                screensaverVisible = true
            )
        )
    }

    @Test
    fun screenOffWinsOverScreensaverAndDim() {
        assertEquals(
            MqttContract.DISPLAY_OFF,
            DisplayModeResolver.displayMode(
                isAmbientDimmed = true,
                isScreenOff = true,
                screensaverVisible = true
            )
        )
    }

    @Test
    fun screensaverWhenDimmedAndVisible() {
        assertEquals(
            MqttContract.DISPLAY_SCREENSAVER,
            DisplayModeResolver.displayMode(
                isAmbientDimmed = true,
                isScreenOff = false,
                screensaverVisible = true
            )
        )
    }

    @Test
    fun dimWhenDimmedWithoutScreensaver() {
        assertEquals(
            MqttContract.DISPLAY_DIM,
            DisplayModeResolver.displayMode(
                isAmbientDimmed = true,
                isScreenOff = false,
                screensaverVisible = false
            )
        )
    }

    @Test
    fun shouldScreenOffFollowsPrefOrForce() {
        assertFalse(DisplayModeResolver.shouldScreenOff(settings(deepSleep = false), forceDeepSleep = false))
        assertTrue(DisplayModeResolver.shouldScreenOff(settings(deepSleep = true), forceDeepSleep = false))
        // An explicit DISPLAY_OFF command overrides a disabled pref.
        assertTrue(DisplayModeResolver.shouldScreenOff(settings(deepSleep = false), forceDeepSleep = true))
    }

    private fun settings(deepSleep: Boolean) = KioskSettings(
        browserEngine = BrowserEngine.WEBVIEW, homeAssistantUrl = "http://dashboard.local",
        dashboardPath = "/main", appendKiosk = false, reloadIntervalSeconds = 90,
        keepScreenOn = true, autoStartOnBoot = true, fullscreen = true,
        themeMode = ThemeMode.DARK, lockTaskMode = false, autoReloadOnFailure = true,
        watchdogEnabled = true, watchdogPingPath = "/", watchdogPingIntervalSeconds = 30,
        updatesEnabled = false, updatesRepo = "test/repo", updateCheckIntervalHours = 24,
        allowMixedContent = true, allowThirdPartyCookies = true, autoplayEnabled = true,
        desktopMode = false, customUserAgent = "", requirePasswordForExitOnly = false,
        adminUnlockTimeoutMinutes = 5, adminButtonCorner = AdminButtonCorner.BOTTOM_LEFT,
        scheduleProfilesEnabled = true, homeStartHour = 6, wallStartHour = 10, nightStartHour = 21,
        profileHomePath = "/home", profileWallPath = "/wall", profileNightPath = "/night",
        maintenanceEnabled = false, maintenanceHour = 4, maintenanceMinute = 15,
        ambientModeEnabled = true, ambientScreensaverEnabled = false,
        ambientScene = AmbientScene.GLOW_CLOCK, ambientBackgroundMode = AmbientBackgroundMode.BUNDLED_VIDEO,
        ambientBackgroundUrl = "", ambientImmichShareUrl = "", ambientPhotoIntervalSeconds = 30,
        ambientWeatherLocation = "Warnsveld", ambientDimAfterSeconds = 60, ambientBrightnessPercent = 25,
        ambientFollowSystemBrightness = false, presenceWakeEnabled = true, presenceWakeCooldownSeconds = 10,
        respectManualScreenOff = true,
        deepSleepEnabled = deepSleep,
        illuminanceReportingEnabled = false, illuminanceMinDeltaLux = 10, illuminanceDebounceSeconds = 10,
        autoDiscoverHomeAssistant = false, mqttEnabled = true, mqttBrokerHost = "mqtt.local",
        mqttBrokerPort = 1883, mqttUseTls = false, mqttUsername = "tablet",
        localControlEnabled = true, localControlPort = 8099, localControlPreviewEnabled = false
    )
}
