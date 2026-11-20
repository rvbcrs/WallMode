package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class LocalControlSettingsTest {
    private val sections = listOf("dashboard", "display", "browser", "device", "system")
    private val properties = KioskSettings::class.java.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .associateBy { it.name }
        .onEach { (_, field) -> field.isAccessible = true }

    private val current = KioskSettings(
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
        ambientModeEnabled = false, ambientScreensaverEnabled = false,
        ambientScene = AmbientScene.GLOW_CLOCK, ambientBackgroundMode = AmbientBackgroundMode.BUNDLED_VIDEO,
        ambientBackgroundUrl = "", ambientImmichShareUrl = "", ambientPhotoIntervalSeconds = 30,
        ambientWeatherLocation = "Warnsveld", ambientDimAfterSeconds = 60, ambientBrightnessPercent = 25,
        ambientFollowSystemBrightness = false, presenceWakeEnabled = true, presenceWakeCooldownSeconds = 10,
        respectManualScreenOff = true,
        deepSleepEnabled = false,
        illuminanceReportingEnabled = false, illuminanceMinDeltaLux = 10, illuminanceDebounceSeconds = 10,
        autoDiscoverHomeAssistant = false, mqttEnabled = true, mqttBrokerHost = "mqtt.local",
        mqttBrokerPort = 1883, mqttUseTls = false, mqttUsername = "tablet",
        localControlEnabled = true, localControlPort = 8099, localControlPreviewEnabled = false
    )

    private fun params(section: String, settings: KioskSettings = current): MutableMap<String, String> =
        LocalControlSettings.fieldNames(section).mapNotNull { name ->
            when (val value = properties.getValue(name).get(settings)) {
                false -> null // Unchecked HTML checkboxes are not submitted.
                true -> name to "on"
                is Enum<*> -> name to value.name
                else -> name to requireNotNull(value).toString()
            }
        }.toMap().toMutableMap()

    @Test
    fun everySettingAppearsExactlyOnceAndEverySectionRoundTrips() {
        val names = sections.flatMap { LocalControlSettings.fieldNames(it) }
        assertEquals(properties.keys, names.toSet())
        assertEquals(names.size, names.toSet().size)
        sections.forEach { section ->
            assertTrue(LocalControlSettings.isSection(section))
            assertEquals(current, LocalControlSettings.apply(section, params(section), current))
            val html = LocalControlSettings.render(section, current)
            LocalControlSettings.fieldNames(section).forEach { name ->
                assertEquals(1, Regex("name=\"$name\"").findAll(html).count())
            }
        }
        assertFalse(LocalControlSettings.isSection("security"))
        assertTrue(runCatching { LocalControlSettings.apply("unknown", emptyMap(), current) }.isFailure)
    }

    @Test
    fun everyFieldUpdatesItselfAndNothingElse() {
        sections.forEach { section ->
            LocalControlSettings.fieldNames(section).forEach { name ->
                val oldValue = properties.getValue(name).get(current)
                val newValue = when (oldValue) {
                    is Boolean -> !oldValue
                    is Int -> oldValue + 1
                    is Enum<*> -> requireNotNull(oldValue.javaClass.enumConstants).first { it != oldValue }
                    else -> requireNotNull(oldValue).toString() + "x"
                }
                val post = params(section)
                when (newValue) {
                    false -> post.remove(name)
                    true -> post[name] = "on"
                    is Enum<*> -> post[name] = newValue.name
                    else -> post[name] = newValue.toString()
                }
                val updated = LocalControlSettings.apply(section, post, current)
                properties.forEach { (propertyName, field) ->
                    assertEquals("Editing $name must only affect itself ($propertyName)",
                        if (propertyName == name) newValue else field.get(current), field.get(updated))
                }
                assertNotEquals(LocalControlSettings.revision(section, current), LocalControlSettings.revision(section, updated))
                (sections - section).forEach { other ->
                    assertEquals(LocalControlSettings.revision(other, current), LocalControlSettings.revision(other, updated))
                }
            }
        }
    }

    @Test
    fun unrelatedSubmittedFieldsAreIgnoredAndMissingNonCheckboxesFail() {
        val post = params("browser").also {
            it["ambientModeEnabled"] = "on"
            it["localControlPort"] = "12345"
        }
        assertEquals(current, LocalControlSettings.apply("browser", post, current))
        post.remove("customUserAgent")
        assertTrue(runCatching { LocalControlSettings.apply("browser", post, current) }.isFailure)
    }

    @Test
    fun rejectsBoundsMalformedNumbersEnumsAndCheckboxesWithoutEchoingValues() {
        listOf(
            Triple("display", "ambientBrightnessPercent", "101"),
            Triple("display", "ambientPhotoIntervalSeconds", "9"),
            Triple("dashboard", "homeStartHour", "24"),
            Triple("system", "maintenanceMinute", "60"),
            Triple("device", "localControlPort", "1023"),
            Triple("device", "mqttBrokerPort", "65536"),
            Triple("browser", "browserEngine", "SECRET_WRONG_ENGINE"),
            Triple("display", "themeMode", "SECRET_WRONG_THEME"),
            Triple("display", "ambientScene", "SECRET_WRONG_SCENE"),
            Triple("display", "ambientBackgroundMode", "SECRET_WRONG_BACKGROUND"),
            Triple("display", "adminButtonCorner", "SECRET_WRONG_CORNER"),
            Triple("display", "ambientModeEnabled", "SECRET_BOOLEAN"),
            Triple("browser", "customUserAgent", "SECRET\u0000CONTROL")
        ).forEach { (section, name, invalid) ->
            val failure = runCatching {
                LocalControlSettings.apply(section, params(section).also { it[name] = invalid }, current)
            }.exceptionOrNull()
            assertTrue("$name must reject invalid input", failure is IllegalArgumentException)
            assertFalse(failure!!.message.orEmpty().contains("SECRET"))
        }
        listOf("-1", "1.5", "99999999999999999", "not-a-number", "").forEach { value ->
            assertTrue(runCatching {
                LocalControlSettings.apply("display", params("display").also { it["ambientDimAfterSeconds"] = value }, current)
            }.isFailure)
        }
    }

    @Test
    fun escapesAllHtmlValuesWhileKeepingTheImmichLinkReadable() {
        val payload = "https://immich.local/share/\"><img src=x onerror=alert('x')>&key=1"
        val html = LocalControlSettings.render("display", current.copy(ambientImmichShareUrl = payload))
        assertTrue(html.contains("type=\"url\" id=\"setting-ambientImmichShareUrl\""))
        assertTrue(html.contains("https://immich.local/share/&quot;&gt;&lt;img src=x onerror=alert(&#39;x&#39;)&gt;&amp;key=1"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("type=\"password\""))
        assertTrue(LocalControlSettings.render("browser", current.copy(customUserAgent = "<script>\"'&</script>"))
            .contains("&lt;script&gt;&quot;&#39;&amp;&lt;/script&gt;"))
    }
}
