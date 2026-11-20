package io.github.rvbcrs.wallmode

import android.app.DownloadManager
import android.app.Dialog
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rvbcrs.wallmode.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: KioskPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeDownloadId: Long = -1L
    private var downloadReceiverRegistered = false
    private var isBindingUi = false
    private var hasUnsavedChanges = false
    private var activeSectionId = R.id.nav_display
    private var sections: List<SectionNav> = emptyList()
    private var selectedAmbientBackgroundMode = AmbientBackgroundMode.BUILT_IN
    private var ambientPreviewDialog: Dialog? = null
    private var ambientPreviewImageJob: Job? = null
    private var ambientPreviewWeatherJob: Job? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId <= 0 || downloadId != activeDownloadId) return
            activeDownloadId = -1L
            installDownloadedApk(downloadId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPreferences(this)
        prefs.applyThemeMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureResponsiveLayout()
        bindSettingsToUi(prefs.load())
        bindActions()
        bindDirtyState()
        val initialSection = savedInstanceState?.getInt(STATE_SELECTED_SECTION, R.id.nav_display)
            ?: R.id.nav_display
        setupSectionNavigation(initialSection)
        binding.railDeviceValue.text = getString(
            R.string.this_device_value,
            Build.MODEL,
            Build.VERSION.RELEASE
        )
        binding.railVersion.text = getString(R.string.wallmode_version, currentVersionName())
        markSaved()
        ensureDownloadReceiver()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_SECTION, activeSectionId)
        outState.putBoolean(STATE_HAS_UNSAVED_CHANGES, hasUnsavedChanges)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(STATE_HAS_UNSAVED_CHANGES)) {
            markDirty()
        } else {
            markSaved()
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.settingsOpen = true
    }

    override fun onPause() {
        super.onPause()
        AppVisibility.settingsOpen = false
    }

    override fun onDestroy() {
        ambientPreviewDialog?.dismiss()
        super.onDestroy()
        scope.cancel()
        unregisterDownloadReceiverIfNeeded()
    }

    private fun bindActions() {
        binding.radioThemeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBindingUi) return@addOnButtonCheckedListener
            val selectedTheme = when (checkedId) {
                R.id.radio_theme_light -> ThemeMode.LIGHT
                R.id.radio_theme_dark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            applyThemeInstantly(selectedTheme)
        }

        binding.switchLocalControlEnabled.setOnCheckedChangeListener { _, _ ->
            if (isBindingUi) return@setOnCheckedChangeListener
            updateLocalControlUiState()
            updateLocalControlAccessText()
            markDirty()
        }
        binding.inputLocalControlPort.doAfterTextChanged {
            if (isBindingUi) return@doAfterTextChanged
            updateLocalControlAccessText()
        }

        binding.switchMqttEnabled.setOnCheckedChangeListener { _, _ ->
            if (isBindingUi) return@setOnCheckedChangeListener
            updateMqttUiState()
            markDirty()
        }

        binding.btnClearMqttPassword.setOnClickListener {
            prefs.clearMqttPassword()
            binding.inputMqttPassword.text?.clear()
            updateMqttStatus()
            Toast.makeText(this, getString(R.string.mqtt_password_cleared), Toast.LENGTH_SHORT).show()
        }

        binding.btnDiscoverHomeAssistant.setOnClickListener {
            discoverHomeAssistantNow()
        }

        binding.btnApplyLocalControl.setOnClickListener {
            if (saveSettings(reloadNow = false)) {
                setResult(RESULT_OK, intentWithControlPanelApply())
                Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.btnSaveSettings.setOnClickListener {
            if (saveSettings(reloadNow = false)) {
                markSaved()
                Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnReloadDashboard.setOnClickListener {
            if (saveSettings(reloadNow = true)) {
                setResult(RESULT_OK, intentWithReload())
                finish()
            }
        }

        binding.btnResetDefaults.setOnClickListener { showResetDefaultsDialog() }

        binding.btnTestUrl.setOnClickListener {
            val candidate = prefs.buildDashboardUrl(readSettingsFromUi())
            testUrl(candidate)
        }

        ambientBackgroundChoices().forEach { (mode, card) ->
            card.setOnClickListener { selectAmbientBackgroundMode(mode, markChanged = true) }
        }

        binding.btnPreviewAmbient.setOnClickListener { showAmbientPreview() }

        binding.btnSaveAdminPassword.setOnClickListener { saveAdminPassword() }

        binding.btnClearAdminPassword.setOnClickListener {
            prefs.clearAdminPassword()
            binding.inputAdminPassword.text?.clear()
            binding.inputAdminPasswordConfirm.text?.clear()
            updateAdminPasswordStatus()
            Toast.makeText(this, getString(R.string.password_cleared_success), Toast.LENGTH_SHORT).show()
        }

        binding.btnCheckUpdates.setOnClickListener {
            val uiSettings = readSettingsFromUi()
            if (!KioskPreferences.isValidRepoSlug(uiSettings.updatesRepo)) {
                Toast.makeText(this, getString(R.string.invalid_repo_slug), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkForUpdates(uiSettings.updatesRepo)
        }

        binding.btnExportConfig.setOnClickListener {
            val json = prefs.exportSettingsJson(readSettingsFromUi())
            shareConfigJson(json)
        }

        binding.btnImportConfig.setOnClickListener {
            showImportDialog()
        }

        binding.btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.btnSupportCoffee.setOnClickListener {
            showSupportLink(R.string.support_coffee, SupportLinks.COFFEE)
        }
        binding.btnSupportGithub.setOnClickListener {
            showSupportLink(R.string.support_github, SupportLinks.GITHUB)
        }

        binding.btnExitKiosk.setOnClickListener {
            maybeExitKiosk()
        }
        binding.btnRequestDeviceOwner.setOnClickListener {
            requestDeviceOwner()
        }
        binding.btnReleaseDeviceOwner.setOnClickListener {
            confirmReleaseDeviceOwner()
        }
    }

    private fun confirmReleaseDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, R.string.release_device_owner_not_owner, Toast.LENGTH_SHORT).show()
            refreshDeviceOwnerStatus()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.release_device_owner_confirm_title)
            .setMessage(R.string.release_device_owner_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.release_device_owner) { _, _ -> releaseDeviceOwner(dpm) }
            .show()
    }

    private fun releaseDeviceOwner(dpm: DevicePolicyManager) {
        // Clearing the keyguard/lock-task policies first avoids leaving a
        // half-applied device-owner state behind.
        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        runCatching { if (isLockTaskRunning()) stopLockTask() }
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        runCatching { dpm.setKeyguardDisabled(admin, false) }
        val result = runCatching { dpm.clearDeviceOwnerApp(packageName) }
        if (result.isSuccess) {
            Toast.makeText(this, R.string.release_device_owner_done, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                getString(R.string.release_device_owner_failed, result.exceptionOrNull()?.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
        refreshDeviceOwnerStatus()
    }
    private fun requestDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (dpm.isDeviceOwnerApp(packageName)) {
            Toast.makeText(
                this,
                "App is already Device Owner",
                Toast.LENGTH_SHORT
            ).show()
            refreshDeviceOwnerStatus()
            return
        }

        val component = "$packageName/.KioskDeviceAdminReceiver"
        val command = "adb shell dpm set-device-owner --user 0 $component"

        MaterialAlertDialogBuilder(this)
            .setTitle("Request Device Owner")
            .setMessage(
                "Device Owner cannot be enabled directly by the app.\n\n" +
                        "Connect your device to a computer with ADB enabled " +
                        "and execute the following command:\n\n$command\n\n" +
                        "This generally requires a freshly reset device " +
                        "that has not completed device provisioning."
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Copy command") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(
                        ClipData.newPlainText("ADB command", command)
                    )

                Toast.makeText(
                    this,
                    "ADB command copied",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun isLockTaskRunning(): Boolean =
        getSystemService(ActivityManager::class.java).lockTaskModeState !=
            ActivityManager.LOCK_TASK_MODE_NONE

    private fun refreshDeviceOwnerStatus() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                as DevicePolicyManager

        val isOwner = dpm.isDeviceOwnerApp(packageName)

        binding.deviceOwnerStatus.setText(
            if (isOwner) {
                R.string.device_owner_status_active
            } else {
                R.string.device_owner_status_inactive
            }
        )

        binding.btnRequestDeviceOwner.visibility =
            if (isOwner) View.GONE else View.VISIBLE

        binding.btnReleaseDeviceOwner.visibility =
            if (isOwner) View.VISIBLE else View.GONE
    }

    internal fun showSupportLink(providerName: Int, url: String): AlertDialog {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(providerName)
            .setMessage(getString(R.string.support_link_message, url))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.support_copy_link) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(getString(providerName), url))
                Toast.makeText(this, R.string.support_link_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.support_open_browser, null)
            .create()
        fun showMessage(message: Int) {
            dialog.setMessage(getString(message, url))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
        fun isKioskLocked() = getSystemService(ActivityManager::class.java)
            .lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        dialog.setOnShowListener {
            showMessage(if (isKioskLocked()) R.string.support_link_kiosk else R.string.support_link_message)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Do not leave lock-task mode or navigate the kiosk dashboard to a donation page.
                if (isKioskLocked()) {
                    showMessage(R.string.support_link_kiosk)
                    return@setOnClickListener
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    dialog.dismiss()
                } catch (_: ActivityNotFoundException) {
                    showMessage(R.string.support_link_unavailable)
                } catch (_: SecurityException) {
                    showMessage(R.string.support_link_unavailable)
                }
            }
        }
        dialog.show()
        return dialog
    }

    private fun bindDirtyState() {
        binding.radioEngineGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked && !isBindingUi) markDirty()
        }
        binding.radioAmbientModeGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                updateAmbientSceneUiState()
                if (!isBindingUi) markDirty()
            }
        }

        listOf(
            binding.switchAppendKiosk,
            binding.switchAutoReloadOnFailure,
            binding.switchAutoDiscoverHomeAssistant,
            binding.switchKeepScreenOn,
            binding.switchAutoStartBoot,
            binding.switchFullscreen,
            binding.switchLockTaskMode,
            binding.switchWatchdogEnabled,
            binding.switchUpdatesEnabled,
            binding.switchAllowMixedContent,
            binding.switchAllowThirdPartyCookies,
            binding.switchAutoplay,
            binding.switchDesktopMode,
            binding.switchRequirePasswordForExitOnly,
            binding.switchScheduleProfiles,
            binding.switchMaintenanceEnabled,
            binding.switchAmbientFollowSystemBrightness,
            binding.switchPresenceWake,
            binding.switchDeepSleep,
            binding.switchRespectManualScreenOff,
            binding.switchIlluminanceReporting,
            binding.switchLocalControlPreview,
            binding.switchMqttTls
        ).forEach { control ->
            control.setOnCheckedChangeListener { _, _ ->
                if (control === binding.switchAmbientFollowSystemBrightness) {
                    updateAmbientBrightnessUiState()
                }
                if (control === binding.switchScheduleProfiles) {
                    updateProfileScheduleUiState()
                }
                if (!isBindingUi) markDirty()
            }
        }

        listOf(
            binding.inputHomeAssistantUrl,
            binding.inputDashboardPath,
            binding.inputReloadInterval,
            binding.inputWatchdogPath,
            binding.inputWatchdogInterval,
            binding.inputUpdatesRepo,
            binding.inputUpdateCheckInterval,
            binding.inputCustomUserAgent,
            binding.inputAdminUnlockTimeout,
            binding.inputProfileHomePath,
            binding.inputHomeStartHour,
            binding.inputProfileWallPath,
            binding.inputWallStartHour,
            binding.inputProfileNightPath,
            binding.inputNightStartHour,
            binding.inputMaintenanceHour,
            binding.inputMaintenanceMinute,
            binding.inputAmbientWeatherLocation,
            binding.inputAmbientBackgroundUrl,
            binding.inputAmbientImmichShareUrl,
            binding.inputAmbientPhotoInterval,
            binding.inputAmbientDimAfter,
            binding.inputAmbientBrightness,
            binding.inputPresenceWakeCooldown,
            binding.inputIlluminanceMinDelta,
            binding.inputIlluminanceDebounce,
            binding.inputLocalControlPort,
            binding.inputMqttBrokerHost,
            binding.inputMqttBrokerPort,
            binding.inputMqttUsername,
            binding.inputMqttPassword
        ).forEach { control ->
            control.doAfterTextChanged {
                if (!isBindingUi) markDirty()
            }
        }

        var lastCornerPosition = binding.spinnerAdminButtonCorner.selectedItemPosition
        binding.spinnerAdminButtonCorner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position != lastCornerPosition) {
                        lastCornerPosition = position
                        if (!isBindingUi) markDirty()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        var lastAmbientScenePosition = binding.spinnerAmbientScene.selectedItemPosition
        binding.spinnerAmbientScene.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    updateAmbientSceneUiState()
                    if (position != lastAmbientScenePosition) {
                        lastAmbientScenePosition = position
                        if (!isBindingUi) markDirty()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

    }

    private fun markDirty() {
        hasUnsavedChanges = true
        binding.settingsSaveState.setText(R.string.settings_unsaved_state)
        binding.settingsSaveState.setTextColor(ContextCompat.getColor(this, R.color.settings_amber))
        binding.btnSaveSettings.isEnabled = true
    }

    private fun markSaved() {
        hasUnsavedChanges = false
        binding.settingsSaveState.setText(R.string.settings_saved_state)
        binding.settingsSaveState.setTextColor(
            ContextCompat.getColor(this, R.color.settings_text_secondary)
        )
        binding.btnSaveSettings.isEnabled = false
    }

    private fun showResetDefaultsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_defaults_title)
            .setMessage(R.string.reset_defaults_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.reset_defaults_confirm) { _, _ ->
                val previousTheme = prefs.load().themeMode
                val defaults = prefs.resetToDefaults()
                bindSettingsToUi(defaults)
                markSaved()
                setResult(RESULT_OK, intentWithReload())
                if (previousTheme != defaults.themeMode) {
                    prefs.applyThemeMode()
                    recreate()
                }
                Toast.makeText(this, getString(R.string.defaults_restored), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun setupSectionNavigation(initialSectionId: Int) {
        sections = listOf(
            SectionNav(
                R.id.nav_dashboard,
                binding.navDashboard,
                R.string.nav_dashboard,
                R.string.section_subtitle_dashboard,
                binding.sectionDashboard
            ),
            SectionNav(
                R.id.nav_display,
                binding.navDisplay,
                R.string.section_title_display,
                R.string.section_subtitle_display,
                binding.sectionDisplay
            ),
            SectionNav(
                R.id.nav_browser,
                binding.navBrowser,
                R.string.nav_browser,
                R.string.section_subtitle_browser,
                binding.sectionBrowser
            ),
            SectionNav(
                R.id.nav_device,
                binding.navDevice,
                R.string.section_title_device,
                R.string.section_subtitle_device,
                binding.sectionDevice
            ),
            SectionNav(
                R.id.nav_system,
                binding.navSystem,
                R.string.nav_system,
                R.string.section_subtitle_system,
                binding.sectionSystem
            )
        )

        sections.forEach { section ->
            section.button.isCheckable = true
            section.button.setOnClickListener { showOnlySelectedSection(section.buttonId) }
        }
        showOnlySelectedSection(
            initialSectionId.takeIf { candidate -> sections.any { it.buttonId == candidate } }
                ?: R.id.nav_display
        )
    }

    private fun showOnlySelectedSection(selectedId: Int) {
        val selected = sections.firstOrNull { it.buttonId == selectedId } ?: return
        activeSectionId = selectedId
        sections.forEach { section ->
            section.button.isChecked = section.buttonId == selectedId
            section.view.visibility = if (section.buttonId == selectedId) View.VISIBLE else View.GONE
        }
        binding.settingsSectionTitle.setText(selected.titleRes)
        binding.settingsSectionSubtitle.setText(selected.subtitleRes)
        binding.settingsScroll.post { binding.settingsScroll.scrollTo(0, 0) }
    }

    private fun configureResponsiveLayout() {
        val configuration = resources.configuration
        val useRail = configuration.screenWidthDp >= 600
        val useColumns = configuration.screenWidthDp >= 900
        val showRailDetails = useRail && configuration.screenHeightDp >= 600

        binding.settingsChrome.orientation = if (useRail) {
            LinearLayout.HORIZONTAL
        } else {
            LinearLayout.VERTICAL
        }
        binding.settingsRailCard.layoutParams = LinearLayout.LayoutParams(
            if (useRail) dp(190) else ViewGroup.LayoutParams.MATCH_PARENT,
            if (useRail) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        )
        binding.settingsWorkspace.layoutParams = LinearLayout.LayoutParams(
            if (useRail) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            if (useRail) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            1f
        ).apply {
            if (useRail) {
                marginStart = resources.getDimensionPixelSize(R.dimen.settings_page_padding)
            } else {
                topMargin = resources.getDimensionPixelSize(R.dimen.settings_section_gap)
            }
        }

        binding.settingsBrand.visibility = if (useRail) View.VISIBLE else View.GONE
        binding.settingsRailContent.layoutParams.height = if (useRail) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.settingsRailContent.requestLayout()
        binding.navSectionRail.orientation = if (useRail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        (binding.navSectionRail.layoutParams as LinearLayout.LayoutParams).topMargin =
            if (useRail) dp(8) else 0
        binding.railFlexibleSpace.layoutParams = LinearLayout.LayoutParams(
            1,
            0,
            if (showRailDetails) 1f else 0f
        )
        binding.railDeviceCard.visibility = if (showRailDetails) View.VISIBLE else View.GONE
        binding.railVersion.visibility = if (showRailDetails) View.VISIBLE else View.GONE
        binding.settingsChangesHint.visibility = if (configuration.screenWidthDp >= 760) {
            View.VISIBLE
        } else {
            View.GONE
        }

        val navItems = listOf(
            binding.navDashboard to R.string.nav_dashboard,
            binding.navDisplay to R.string.nav_display,
            binding.navBrowser to R.string.nav_browser,
            binding.navDevice to R.string.nav_device,
            binding.navSystem to R.string.nav_system
        )
        navItems.forEachIndexed { index, (button, labelRes) ->
            button.text = if (useRail) getString(labelRes) else ""
            button.iconSize = dp(if (useRail) 24 else 28)
            button.iconGravity = if (useRail) {
                MaterialButton.ICON_GRAVITY_START
            } else {
                MaterialButton.ICON_GRAVITY_TEXT_START
            }
            button.iconPadding = if (useRail) dp(10) else 0
            button.gravity = if (useRail) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            button.layoutParams = LinearLayout.LayoutParams(
                if (useRail) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                if (useRail) dp(52) else dp(48),
                if (useRail) 0f else 1f
            ).apply {
                if (useRail && index > 0) topMargin = dp(4)
            }
        }

        listOf(
            binding.dashboardColumns,
            binding.displayColumns,
            binding.browserColumns,
            binding.deviceColumns,
            binding.systemColumns
        ).forEach { configureCardColumns(it, useColumns) }
    }

    private fun configureCardColumns(row: LinearLayout, useColumns: Boolean) {
        row.orientation = if (useColumns) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val firstCard = row.getChildAt(0)
        val gap = row.getChildAt(1)
        val secondCard = row.getChildAt(2)
        firstCard.layoutParams = LinearLayout.LayoutParams(
            if (useColumns) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (useColumns) 1f else 0f
        )
        gap.layoutParams = LinearLayout.LayoutParams(
            if (useColumns) dp(12) else 1,
            if (useColumns) 1 else resources.getDimensionPixelSize(R.dimen.settings_section_gap)
        )
        secondCard.layoutParams = LinearLayout.LayoutParams(
            if (useColumns) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (useColumns) 1f else 0f
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bindSettingsToUi(settings: KioskSettings) {
        isBindingUi = true
        when (settings.browserEngine) {
            BrowserEngine.WEBVIEW -> binding.radioEngineGroup.check(R.id.radio_engine_webview)
            BrowserEngine.CHROMIUM_CUSTOM_TAB -> binding.radioEngineGroup.check(R.id.radio_engine_chromium)
        }
        when (settings.themeMode) {
            ThemeMode.SYSTEM -> binding.radioThemeGroup.check(R.id.radio_theme_system)
            ThemeMode.LIGHT -> binding.radioThemeGroup.check(R.id.radio_theme_light)
            ThemeMode.DARK -> binding.radioThemeGroup.check(R.id.radio_theme_dark)
        }

        binding.inputHomeAssistantUrl.setText(settings.homeAssistantUrl)
        binding.inputDashboardPath.setText(settings.dashboardPath)
        binding.inputReloadInterval.setText(settings.reloadIntervalSeconds.toString())
        binding.switchAppendKiosk.isChecked = settings.appendKiosk
        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchAutoStartBoot.isChecked = settings.autoStartOnBoot
        binding.switchFullscreen.isChecked = settings.fullscreen
        binding.switchLockTaskMode.isChecked = settings.lockTaskMode
        refreshDeviceOwnerStatus()
        binding.switchAutoReloadOnFailure.isChecked = settings.autoReloadOnFailure
        binding.switchAutoDiscoverHomeAssistant.isChecked = settings.autoDiscoverHomeAssistant

        binding.switchWatchdogEnabled.isChecked = settings.watchdogEnabled
        binding.inputWatchdogPath.setText(settings.watchdogPingPath)
        binding.inputWatchdogInterval.setText(settings.watchdogPingIntervalSeconds.toString())

        binding.switchUpdatesEnabled.isChecked = settings.updatesEnabled
        binding.inputUpdatesRepo.setText(settings.updatesRepo)
        binding.inputUpdateCheckInterval.setText(settings.updateCheckIntervalHours.toString())
        binding.updateStatusValue.text = composeUpdateStatusText()

        binding.switchAllowMixedContent.isChecked = settings.allowMixedContent
        binding.switchAllowThirdPartyCookies.isChecked = settings.allowThirdPartyCookies
        binding.switchAutoplay.isChecked = settings.autoplayEnabled
        binding.switchDesktopMode.isChecked = settings.desktopMode
        binding.inputCustomUserAgent.setText(settings.customUserAgent)

        binding.switchRequirePasswordForExitOnly.isChecked = settings.requirePasswordForExitOnly
        binding.inputAdminUnlockTimeout.setText(settings.adminUnlockTimeoutMinutes.toString())
        binding.spinnerAdminButtonCorner.setSelection(settings.adminButtonCorner.ordinal)

        binding.switchScheduleProfiles.isChecked = settings.scheduleProfilesEnabled
        binding.inputHomeStartHour.setText(settings.homeStartHour.toString())
        binding.inputWallStartHour.setText(settings.wallStartHour.toString())
        binding.inputNightStartHour.setText(settings.nightStartHour.toString())
        binding.inputProfileHomePath.setText(settings.profileHomePath)
        binding.inputProfileWallPath.setText(settings.profileWallPath)
        binding.inputProfileNightPath.setText(settings.profileNightPath)

        binding.switchMaintenanceEnabled.isChecked = settings.maintenanceEnabled
        binding.inputMaintenanceHour.setText(settings.maintenanceHour.toString())
        binding.inputMaintenanceMinute.setText(settings.maintenanceMinute.toString())

        when {
            settings.ambientScreensaverEnabled -> binding.radioAmbientModeGroup.check(R.id.radio_ambient_screensaver)
            settings.ambientModeEnabled -> binding.radioAmbientModeGroup.check(R.id.radio_ambient_dim)
            else -> binding.radioAmbientModeGroup.check(R.id.radio_ambient_off)
        }
        binding.spinnerAmbientScene.setSelection(settings.ambientScene.ordinal)
        selectAmbientBackgroundMode(settings.ambientBackgroundMode, markChanged = false)
        binding.inputAmbientBackgroundUrl.setText(settings.ambientBackgroundUrl)
        binding.inputAmbientImmichShareUrl.setText(settings.ambientImmichShareUrl)
        binding.inputAmbientPhotoInterval.setText(settings.ambientPhotoIntervalSeconds.toString())
        binding.inputAmbientWeatherLocation.setText(settings.ambientWeatherLocation)
        binding.inputAmbientDimAfter.setText(settings.ambientDimAfterSeconds.toString())
        binding.inputAmbientBrightness.setText(settings.ambientBrightnessPercent.toString())
        binding.switchAmbientFollowSystemBrightness.isChecked =
            settings.ambientFollowSystemBrightness
        binding.switchPresenceWake.isChecked = settings.presenceWakeEnabled
        binding.inputPresenceWakeCooldown.setText(settings.presenceWakeCooldownSeconds.toString())
        binding.switchDeepSleep.isChecked = settings.deepSleepEnabled
        binding.switchRespectManualScreenOff.isChecked = settings.respectManualScreenOff
        binding.switchIlluminanceReporting.isChecked = settings.illuminanceReportingEnabled
        binding.inputIlluminanceMinDelta.setText(settings.illuminanceMinDeltaLux.toString())
        binding.inputIlluminanceDebounce.setText(settings.illuminanceDebounceSeconds.toString())
        binding.switchLocalControlEnabled.isChecked = settings.localControlEnabled
        binding.switchLocalControlPreview.isChecked = settings.localControlPreviewEnabled
        binding.inputLocalControlPort.setText(settings.localControlPort.toString())
        binding.switchMqttEnabled.isChecked = settings.mqttEnabled
        binding.inputMqttBrokerHost.setText(settings.mqttBrokerHost)
        binding.inputMqttBrokerPort.setText(settings.mqttBrokerPort.toString())
        binding.switchMqttTls.isChecked = settings.mqttUseTls
        binding.inputMqttUsername.setText(settings.mqttUsername)
        binding.inputMqttPassword.text?.clear()

        binding.currentUrlValue.text = prefs.buildDashboardUrl(settings)
        binding.discoveryStatusValue.text = getString(R.string.discovery_status_idle)
        updateLocalControlUiState()
        updateLocalControlAccessText()
        updateProfileScheduleUiState()
        updateAmbientSceneUiState()
        updateAmbientBrightnessUiState()
        updateMqttUiState()
        updateMqttStatus()
        updateAdminPasswordStatus()
        isBindingUi = false
    }

    private fun applyThemeInstantly(targetTheme: ThemeMode) {
        val current = prefs.load()
        if (current.themeMode == targetTheme) return

        prefs.save(current.copy(themeMode = targetTheme))
        prefs.applyThemeMode()
        setResult(RESULT_OK, intentWithReload())
        recreate()
    }

    private fun readSettingsFromUi(): KioskSettings {
        val current = prefs.load()
        val reloadInterval = binding.inputReloadInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: current.reloadIntervalSeconds
        val watchdogInterval = binding.inputWatchdogInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: current.watchdogPingIntervalSeconds
        val updateInterval = binding.inputUpdateCheckInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: current.updateCheckIntervalHours
        val unlockTimeout = binding.inputAdminUnlockTimeout.text?.toString()?.trim()?.toIntOrNull()
            ?: current.adminUnlockTimeoutMinutes
        val homeStartHour = binding.inputHomeStartHour.text?.toString()?.trim()?.toIntOrNull()
            ?: current.homeStartHour
        val wallStartHour = binding.inputWallStartHour.text?.toString()?.trim()?.toIntOrNull()
            ?: current.wallStartHour
        val nightStartHour = binding.inputNightStartHour.text?.toString()?.trim()?.toIntOrNull()
            ?: current.nightStartHour
        val maintenanceHour = binding.inputMaintenanceHour.text?.toString()?.trim()?.toIntOrNull()
            ?: current.maintenanceHour
        val maintenanceMinute = binding.inputMaintenanceMinute.text?.toString()?.trim()?.toIntOrNull()
            ?: current.maintenanceMinute
        val ambientDimAfter = binding.inputAmbientDimAfter.text?.toString()?.trim()?.toIntOrNull()
            ?: current.ambientDimAfterSeconds
        val ambientBrightness = binding.inputAmbientBrightness.text?.toString()?.trim()?.toIntOrNull()
            ?: current.ambientBrightnessPercent
        val ambientPhotoInterval = binding.inputAmbientPhotoInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: current.ambientPhotoIntervalSeconds
        val presenceWakeCooldown = binding.inputPresenceWakeCooldown.text?.toString()?.trim()?.toIntOrNull()
            ?: current.presenceWakeCooldownSeconds
        val illuminanceMinDelta = binding.inputIlluminanceMinDelta.text?.toString()?.trim()?.toIntOrNull()
            ?: current.illuminanceMinDeltaLux
        val illuminanceDebounce = binding.inputIlluminanceDebounce.text?.toString()?.trim()?.toIntOrNull()
            ?: current.illuminanceDebounceSeconds
        val localControlPort = binding.inputLocalControlPort.text?.toString()?.trim()?.toIntOrNull()
            ?: current.localControlPort
        val mqttBrokerPort = binding.inputMqttBrokerPort.text?.toString()?.trim()?.toIntOrNull()
            ?: current.mqttBrokerPort

        val selectedEngine = when (binding.radioEngineGroup.checkedButtonId) {
            R.id.radio_engine_webview -> BrowserEngine.WEBVIEW
            R.id.radio_engine_chromium -> BrowserEngine.CHROMIUM_CUSTOM_TAB
            else -> BrowserEngine.WEBVIEW
        }
        val selectedTheme = when (binding.radioThemeGroup.checkedButtonId) {
            R.id.radio_theme_light -> ThemeMode.LIGHT
            R.id.radio_theme_dark -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        val ambientMode = binding.radioAmbientModeGroup.checkedButtonId

        return current.copy(
            browserEngine = selectedEngine,
            themeMode = selectedTheme,
            homeAssistantUrl = binding.inputHomeAssistantUrl.text?.toString().orEmpty(),
            dashboardPath = binding.inputDashboardPath.text?.toString().orEmpty(),
            appendKiosk = binding.switchAppendKiosk.isChecked,
            reloadIntervalSeconds = reloadInterval,
            keepScreenOn = binding.switchKeepScreenOn.isChecked,
            autoStartOnBoot = binding.switchAutoStartBoot.isChecked,
            fullscreen = binding.switchFullscreen.isChecked,
            lockTaskMode = binding.switchLockTaskMode.isChecked,
            autoReloadOnFailure = binding.switchAutoReloadOnFailure.isChecked,
            autoDiscoverHomeAssistant = binding.switchAutoDiscoverHomeAssistant.isChecked,
            watchdogEnabled = binding.switchWatchdogEnabled.isChecked,
            watchdogPingPath = binding.inputWatchdogPath.text?.toString().orEmpty(),
            watchdogPingIntervalSeconds = watchdogInterval,
            updatesEnabled = binding.switchUpdatesEnabled.isChecked,
            updatesRepo = binding.inputUpdatesRepo.text?.toString().orEmpty(),
            updateCheckIntervalHours = updateInterval,
            allowMixedContent = binding.switchAllowMixedContent.isChecked,
            allowThirdPartyCookies = binding.switchAllowThirdPartyCookies.isChecked,
            autoplayEnabled = binding.switchAutoplay.isChecked,
            desktopMode = binding.switchDesktopMode.isChecked,
            customUserAgent = binding.inputCustomUserAgent.text?.toString().orEmpty(),
            requirePasswordForExitOnly = binding.switchRequirePasswordForExitOnly.isChecked,
            adminUnlockTimeoutMinutes = unlockTimeout,
            adminButtonCorner = AdminButtonCorner.entries.getOrElse(
                binding.spinnerAdminButtonCorner.selectedItemPosition
            ) { AdminButtonCorner.TOP_RIGHT },
            scheduleProfilesEnabled = binding.switchScheduleProfiles.isChecked,
            homeStartHour = homeStartHour,
            wallStartHour = wallStartHour,
            nightStartHour = nightStartHour,
            profileHomePath = binding.inputProfileHomePath.text?.toString().orEmpty(),
            profileWallPath = binding.inputProfileWallPath.text?.toString().orEmpty(),
            profileNightPath = binding.inputProfileNightPath.text?.toString().orEmpty(),
            maintenanceEnabled = binding.switchMaintenanceEnabled.isChecked,
            maintenanceHour = maintenanceHour,
            maintenanceMinute = maintenanceMinute,
            ambientModeEnabled = ambientMode != R.id.radio_ambient_off,
            ambientScreensaverEnabled = ambientMode == R.id.radio_ambient_screensaver,
            ambientScene = AmbientScene.entries.getOrElse(
                binding.spinnerAmbientScene.selectedItemPosition
            ) { AmbientScene.AURORA_WEATHER },
            ambientBackgroundMode = selectedAmbientBackgroundMode,
            ambientBackgroundUrl = binding.inputAmbientBackgroundUrl.text?.toString().orEmpty(),
            ambientImmichShareUrl = binding.inputAmbientImmichShareUrl.text?.toString().orEmpty(),
            ambientPhotoIntervalSeconds = ambientPhotoInterval,
            ambientWeatherLocation = binding.inputAmbientWeatherLocation.text?.toString().orEmpty(),
            ambientDimAfterSeconds = ambientDimAfter,
            ambientBrightnessPercent = ambientBrightness,
            ambientFollowSystemBrightness = binding.switchAmbientFollowSystemBrightness.isChecked,
            presenceWakeEnabled = binding.switchPresenceWake.isChecked,
            presenceWakeCooldownSeconds = presenceWakeCooldown,
            respectManualScreenOff = binding.switchRespectManualScreenOff.isChecked,
            deepSleepEnabled = binding.switchDeepSleep.isChecked,
            illuminanceReportingEnabled = binding.switchIlluminanceReporting.isChecked,
            illuminanceMinDeltaLux = illuminanceMinDelta,
            illuminanceDebounceSeconds = illuminanceDebounce,
            mqttEnabled = binding.switchMqttEnabled.isChecked,
            mqttBrokerHost = binding.inputMqttBrokerHost.text?.toString().orEmpty(),
            mqttBrokerPort = mqttBrokerPort,
            mqttUseTls = binding.switchMqttTls.isChecked,
            mqttUsername = binding.inputMqttUsername.text?.toString().orEmpty(),
            localControlEnabled = binding.switchLocalControlEnabled.isChecked,
            localControlPort = localControlPort,
            localControlPreviewEnabled = binding.switchLocalControlPreview.isChecked
        )
    }

    private fun saveSettings(reloadNow: Boolean): Boolean {
        val previousSettings = prefs.load()
        val rawMqttPort = binding.inputMqttBrokerPort.text?.toString()?.trim().orEmpty()
        val parsedMqttPort = rawMqttPort.toIntOrNull()
        if (binding.switchMqttEnabled.isChecked &&
            (parsedMqttPort == null || parsedMqttPort !in KioskPreferences.MIN_MQTT_BROKER_PORT..
                KioskPreferences.MAX_MQTT_BROKER_PORT)
        ) {
            Toast.makeText(this, getString(R.string.invalid_mqtt_broker), Toast.LENGTH_SHORT).show()
            return false
        }
        if (binding.switchScheduleProfiles.isChecked) {
            val profileHours = listOf(
                binding.inputHomeStartHour,
                binding.inputWallStartHour,
                binding.inputNightStartHour
            ).mapNotNull { it.text?.toString()?.trim()?.toIntOrNull() }
            if (profileHours.size != 3 || !DashboardProfileSchedule.hasValidStartHours(
                    profileHours[0],
                    profileHours[1],
                    profileHours[2]
                )
            ) {
                Toast.makeText(this, getString(R.string.profile_hours_invalid), Toast.LENGTH_SHORT).show()
                return false
            }
        }
        val settings = readSettingsFromUi()
        val normalizedBaseUrl = KioskPreferences.normalizeBaseUrl(settings.homeAssistantUrl)
        if (!KioskPreferences.isHttpOrHttpsUrl(normalizedBaseUrl)) {
            Toast.makeText(this, getString(R.string.invalid_home_assistant_url), Toast.LENGTH_SHORT)
                .show()
            return false
        }

        if (!validateAmbientBackground(settings)) return false

        if (settings.localControlPort !in KioskPreferences.MIN_LOCAL_CONTROL_PORT..KioskPreferences.MAX_LOCAL_CONTROL_PORT) {
            Toast.makeText(
                this,
                getString(
                    R.string.invalid_local_control_port,
                    KioskPreferences.MIN_LOCAL_CONTROL_PORT,
                    KioskPreferences.MAX_LOCAL_CONTROL_PORT
                ),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        if (settings.mqttEnabled && !MqttContract.isValidBroker(
                settings.mqttBrokerHost,
                settings.mqttBrokerPort
            )
        ) {
            Toast.makeText(this, getString(R.string.invalid_mqtt_broker), Toast.LENGTH_SHORT).show()
            return false
        }

        val enteredMqttPassword = binding.inputMqttPassword.text?.toString().orEmpty()
        val keepsStoredMqttPassword = prefs.hasMqttPassword() &&
            MqttContract.brokerIdentity(
                previousSettings.mqttBrokerHost,
                previousSettings.mqttBrokerPort,
                previousSettings.mqttUseTls,
                previousSettings.mqttUsername
            ) == MqttContract.brokerIdentity(
                settings.mqttBrokerHost,
                settings.mqttBrokerPort,
                settings.mqttUseTls,
                settings.mqttUsername
            )
        if (settings.mqttEnabled && settings.mqttUsername.isBlank() &&
            (enteredMqttPassword.isNotEmpty() || keepsStoredMqttPassword)
        ) {
            Toast.makeText(
                this,
                getString(R.string.mqtt_password_requires_username),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        if (settings.updatesEnabled && !KioskPreferences.isValidRepoSlug(settings.updatesRepo)) {
            Toast.makeText(this, getString(R.string.invalid_repo_slug), Toast.LENGTH_SHORT).show()
            return false
        }

        prefs.save(settings)
        if (enteredMqttPassword.isNotEmpty()) {
            prefs.setMqttPassword(enteredMqttPassword)
            binding.inputMqttPassword.text?.clear()
        }
        if (previousSettings.themeMode != settings.themeMode) {
            prefs.applyThemeMode()
            if (!reloadNow) {
                recreate()
            }
        }
        binding.currentUrlValue.text = prefs.buildDashboardUrl(settings)
        updateLocalControlUiState()
        updateLocalControlAccessText()
        updateMqttUiState()
        updateMqttStatus()
        if (normalizedBaseUrl.startsWith("http://", ignoreCase = true)) {
            Toast.makeText(this, getString(R.string.warning_cleartext_http), Toast.LENGTH_LONG).show()
        }
        if (settings.mqttEnabled && !settings.mqttUseTls &&
            (settings.mqttUsername.isNotBlank() || prefs.hasMqttPassword())
        ) {
            Toast.makeText(this, getString(R.string.warning_mqtt_cleartext), Toast.LENGTH_LONG).show()
        }

        if (reloadNow) {
            setResult(RESULT_OK, intentWithReload())
        }
        return true
    }

    private fun discoverHomeAssistantNow() {
        val candidateSettings = readSettingsFromUi()
        val baseSettings = prefs.load().copy(
            autoDiscoverHomeAssistant = candidateSettings.autoDiscoverHomeAssistant
        )
        prefs.save(baseSettings)

        binding.btnDiscoverHomeAssistant.isEnabled = false
        binding.btnDiscoverHomeAssistant.text = getString(R.string.discovering_home_assistant)
        binding.discoveryStatusValue.text = getString(R.string.discovering_home_assistant)

        scope.launch {
            val discovered = withContext(Dispatchers.IO) {
                HomeAssistantDiscovery.discoverCandidates(this@SettingsActivity)
            }

            binding.btnDiscoverHomeAssistant.isEnabled = true
            binding.btnDiscoverHomeAssistant.text = getString(R.string.discover_home_assistant_now)

            if (discovered.isEmpty()) {
                binding.discoveryStatusValue.text = getString(R.string.discovery_status_not_found)
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.discovery_status_not_found),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            showDiscoveredServersDialog(discovered)
        }
    }

    private fun showDiscoveredServersDialog(
        candidates: List<HomeAssistantDiscoveryResult>
    ) {
        val labels = candidates.map { candidate ->
            "${candidate.baseUrl} (${candidate.source})"
        }.toTypedArray()

        val currentUrl = KioskPreferences.normalizeBaseUrl(
            readSettingsFromUi().homeAssistantUrl
        )
        var selectedIndex = candidates.indexOfFirst {
            it.baseUrl.equals(currentUrl, ignoreCase = true)
        }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discovery_select_title)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.connect_selected_server) { _, _ ->
                val selected = candidates.getOrNull(selectedIndex) ?: return@setPositiveButton
                val merged = prefs.load().copy(homeAssistantUrl = selected.baseUrl)
                prefs.save(merged)
                bindSettingsToUi(prefs.load())
                markSaved()
                binding.discoveryStatusValue.text =
                    getString(R.string.discovery_status_found, selected.baseUrl)
                setResult(RESULT_OK, intentWithReload())
                Toast.makeText(
                    this,
                    getString(R.string.discovery_connected_toast, selected.baseUrl),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun updateLocalControlUiState() {
        val panelEnabled = binding.switchLocalControlEnabled.isChecked
        binding.inputLocalControlPort.isEnabled = panelEnabled
        binding.switchLocalControlPreview.isEnabled = panelEnabled
        binding.btnApplyLocalControl.isEnabled = true
    }

    private fun updateAmbientBrightnessUiState() {
        binding.inputAmbientBrightness.isEnabled =
            !binding.switchAmbientFollowSystemBrightness.isChecked
    }

    private fun updateProfileScheduleUiState() {
        binding.profileScheduleDetails.visibility =
            if (binding.switchScheduleProfiles.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateAmbientSceneUiState() {
        val screensaverSelected =
            binding.radioAmbientModeGroup.checkedButtonId == R.id.radio_ambient_screensaver
        binding.ambientSceneLabel.visibility = if (screensaverSelected) View.VISIBLE else View.GONE
        binding.spinnerAmbientScene.visibility = if (screensaverSelected) View.VISIBLE else View.GONE
        binding.ambientBackgroundLabel.visibility = if (screensaverSelected) View.VISIBLE else View.GONE
        binding.ambientBackgroundPickerHint.visibility = if (screensaverSelected) View.VISIBLE else View.GONE
        binding.ambientBackgroundPicker.visibility = if (screensaverSelected) View.VISIBLE else View.GONE
        val customBackgroundSelected = selectedAmbientBackgroundMode.requiresUrl
        binding.ambientCustomBackgroundSettings.visibility =
            if (screensaverSelected && customBackgroundSelected) View.VISIBLE else View.GONE
        binding.ambientImmichSettings.visibility =
            if (screensaverSelected && selectedAmbientBackgroundMode == AmbientBackgroundMode.IMMICH_ALBUM) {
                View.VISIBLE
            } else {
                View.GONE
            }
        binding.btnPreviewAmbient.visibility = if (screensaverSelected) View.VISIBLE else View.GONE
        val weatherSelected = binding.spinnerAmbientScene.selectedItemPosition ==
            AmbientScene.AURORA_WEATHER.ordinal
        binding.ambientWeatherSettings.visibility =
            if (screensaverSelected && weatherSelected) View.VISIBLE else View.GONE
    }

    private fun ambientBackgroundChoices(): List<Pair<AmbientBackgroundMode, MaterialCardView>> =
        listOf(
            AmbientBackgroundMode.BUILT_IN to binding.ambientBackgroundBuiltIn,
            AmbientBackgroundMode.BUNDLED_IMAGE to binding.ambientBackgroundBundledImage,
            AmbientBackgroundMode.BUNDLED_VIDEO to binding.ambientBackgroundBundledVideo,
            AmbientBackgroundMode.IMAGE_URL to binding.ambientBackgroundImageUrl,
            AmbientBackgroundMode.VIDEO_URL to binding.ambientBackgroundVideoUrl,
            AmbientBackgroundMode.IMMICH_ALBUM to binding.ambientBackgroundImmich
        )

    private fun selectAmbientBackgroundMode(
        mode: AmbientBackgroundMode,
        markChanged: Boolean
    ) {
        selectedAmbientBackgroundMode = mode
        val activeStroke = ContextCompat.getColor(this, R.color.brand_primary)
        val idleStroke = ContextCompat.getColor(this, R.color.card_stroke)
        val activeSurface = ContextCompat.getColor(this, R.color.brand_primary_faint)
        val idleSurface = ContextCompat.getColor(this, R.color.card_surface)
        val selectedCard = ambientBackgroundChoices().first { it.first == mode }.second
        ambientBackgroundChoices().forEach { (candidate, card) ->
            val selected = candidate == mode
            card.isCheckable = true
            card.isChecked = selected
            card.isSelected = selected
            card.strokeWidth = dp(if (selected) 2 else 1)
            card.setStrokeColor(if (selected) activeStroke else idleStroke)
            card.setCardBackgroundColor(if (selected) activeSurface else idleSurface)
        }
        binding.ambientBackgroundPicker.post {
            binding.ambientBackgroundPicker.smoothScrollTo(maxOf(0, selectedCard.left - dp(8)), 0)
        }
        updateAmbientSceneUiState()
        if (markChanged && !isBindingUi) markDirty()
    }

    private fun validateAmbientBackground(settings: KioskSettings): Boolean {
        if (settings.ambientBackgroundMode.requiresUrl &&
            !KioskPreferences.isValidAmbientMediaUrl(settings.ambientBackgroundUrl)
        ) {
            Toast.makeText(
                this,
                getString(R.string.invalid_ambient_background_url),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        if (settings.ambientBackgroundMode != AmbientBackgroundMode.IMMICH_ALBUM) return true
        if (parseImmichShareUrl(settings.ambientImmichShareUrl) == null) {
            Toast.makeText(
                this,
                getString(R.string.invalid_ambient_immich_share_url),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        val interval = binding.inputAmbientPhotoInterval.text?.toString()?.trim()?.toIntOrNull()
        if (interval == null || interval !in KioskPreferences.MIN_AMBIENT_PHOTO_INTERVAL_SECONDS..
            KioskPreferences.MAX_AMBIENT_PHOTO_INTERVAL_SECONDS
        ) {
            Toast.makeText(
                this,
                getString(R.string.invalid_ambient_photo_interval),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        return true
    }

    private fun showAmbientPreview() {
        val settings = readSettingsFromUi()
        if (!validateAmbientBackground(settings)) return

        ambientPreviewDialog?.dismiss()
        val preview = AmbientScreensaverView(this).apply {
            scene = settings.ambientScene
            photoClockMode = settings.ambientBackgroundMode == AmbientBackgroundMode.IMMICH_ALBUM
            alpha = 0f
            isClickable = true
            contentDescription = getString(R.string.ambient_preview_close)
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(preview)
            setCanceledOnTouchOutside(false)
        }
        ambientPreviewDialog = dialog
        preview.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            ambientPreviewImageJob?.cancel()
            ambientPreviewWeatherJob?.cancel()
            ambientPreviewImageJob = null
            ambientPreviewWeatherJob = null
            preview.animate().cancel()
            preview.visibility = View.GONE
            preview.releaseCustomMedia()
            if (ambientPreviewDialog === dialog) ambientPreviewDialog = null
        }
        dialog.show()
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            attributes = attributes.apply {
                screenBrightness = if (settings.ambientFollowSystemBrightness) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    settings.ambientBrightnessPercent / 100f
                }
            }
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        when (settings.ambientBackgroundMode) {
            AmbientBackgroundMode.BUILT_IN -> preview.useBuiltInBackground()
            AmbientBackgroundMode.BUNDLED_IMAGE -> preview.showBundledImage()
            AmbientBackgroundMode.BUNDLED_VIDEO -> preview.showBundledVideo()
            AmbientBackgroundMode.VIDEO_URL -> preview.showCustomVideo(settings.ambientBackgroundUrl)
            AmbientBackgroundMode.IMAGE_URL -> {
                preview.useBuiltInBackground()
                ambientPreviewImageJob = scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        AmbientImageLoader.load(settings.ambientBackgroundUrl)
                    }
                    if (!dialog.isShowing) {
                        result.getOrNull()?.recycle()
                        return@launch
                    }
                    result.onSuccess { preview.showCustomImage(it) }.onFailure {
                        preview.useBuiltInBackground()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.ambient_preview_media_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            AmbientBackgroundMode.IMMICH_ALBUM -> {
                preview.useBuiltInBackground()
                ambientPreviewImageJob = scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val firstAsset = ImmichPhotoSource
                                .loadAssetIds(settings.ambientImmichShareUrl)
                                .getOrThrow()
                                .randomOrNull()
                                ?: error("Immich album contains no photos")
                            ImmichPhotoSource
                                .loadThumbnail(settings.ambientImmichShareUrl, firstAsset)
                                .getOrThrow()
                        }
                    }
                    if (!dialog.isShowing) {
                        result.getOrNull()?.recycle()
                        return@launch
                    }
                    result.onSuccess { preview.showCustomImage(it) }.onFailure {
                        preview.useBuiltInBackground()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.ambient_preview_media_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        if (settings.ambientScene == AmbientScene.AURORA_WEATHER &&
            settings.ambientBackgroundMode != AmbientBackgroundMode.IMMICH_ALBUM
        ) {
            val location = settings.ambientWeatherLocation.trim()
            if (location.isBlank()) {
                preview.showWeatherError(locationMissing = true)
            } else {
                preview.showWeatherLoading(location)
                ambientPreviewWeatherJob = scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { AmbientWeatherClient.fetch(location) }
                    }
                    if (!dialog.isShowing) return@launch
                    result.onSuccess(preview::showWeather)
                        .onFailure { preview.showWeatherError(locationMissing = false) }
                }
            }
        }
        preview.animate().alpha(1f).setDuration(400L).start()
        Toast.makeText(this, R.string.ambient_preview_close, Toast.LENGTH_SHORT).show()
    }

    private fun updateLocalControlAccessText() {
        if (!binding.switchLocalControlEnabled.isChecked) {
            binding.localControlAccessValue.text = getString(R.string.local_control_access_disabled)
            return
        }
        if (!prefs.hasAdminPassword()) {
            binding.localControlAccessValue.text = getString(R.string.local_control_password_required)
            return
        }

        val rawPort = binding.inputLocalControlPort.text?.toString()?.trim()?.toIntOrNull()
            ?: KioskPreferences.DEFAULT_LOCAL_CONTROL_PORT
        val clampedPort = KioskPreferences.clampInt(
            rawPort,
            KioskPreferences.MIN_LOCAL_CONTROL_PORT,
            KioskPreferences.MAX_LOCAL_CONTROL_PORT
        )
        val url = LocalControlServer.discoverPrimaryUrl(clampedPort)
        binding.localControlAccessValue.text = getString(R.string.local_control_access_url, url)
    }

    private fun updateMqttUiState() {
        val enabled = binding.switchMqttEnabled.isChecked
        binding.inputMqttBrokerHost.isEnabled = enabled
        binding.inputMqttBrokerPort.isEnabled = enabled
        binding.switchMqttTls.isEnabled = enabled
        binding.inputMqttUsername.isEnabled = enabled
        binding.inputMqttPassword.isEnabled = enabled
        binding.btnClearMqttPassword.isEnabled = prefs.hasMqttPassword()
    }

    private fun updateMqttStatus() {
        val state = prefs.diagnosticsSnapshot().lastMqttState
        binding.mqttStatusValue.text = getString(
            if (prefs.hasMqttPassword()) {
                R.string.mqtt_password_set
            } else {
                R.string.mqtt_password_not_set
            },
            state
        )
        binding.btnClearMqttPassword.isEnabled = prefs.hasMqttPassword()
    }

    private fun saveAdminPassword() {
        val password = binding.inputAdminPassword.text?.toString().orEmpty().trim()
        val confirm = binding.inputAdminPasswordConfirm.text?.toString().orEmpty().trim()

        if (password.length < MIN_ADMIN_PASSWORD_LENGTH) {
            Toast.makeText(this, getString(R.string.password_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        if (password != confirm) {
            Toast.makeText(this, getString(R.string.password_mismatch), Toast.LENGTH_SHORT).show()
            return
        }

        prefs.setAdminPassword(password)
        binding.inputAdminPassword.text?.clear()
        binding.inputAdminPasswordConfirm.text?.clear()
        updateAdminPasswordStatus()
        Toast.makeText(this, getString(R.string.password_set_success), Toast.LENGTH_SHORT).show()
    }

    private fun updateAdminPasswordStatus() {
        binding.adminPasswordStatus.text = if (prefs.hasAdminPassword()) {
            getString(R.string.admin_password_set)
        } else {
            getString(R.string.admin_password_not_set)
        }
    }

    private fun testUrl(url: String) {
        if (!KioskPreferences.isHttpOrHttpsUrl(url)) {
            Toast.makeText(this, getString(R.string.invalid_home_assistant_url), Toast.LENGTH_SHORT)
                .show()
            return
        }

        binding.btnTestUrl.isEnabled = false
        binding.btnTestUrl.text = getString(R.string.testing_url)

        scope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 6_000
                    connection.readTimeout = 6_000
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = true
                    connection.connect()
                    val code = connection.responseCode
                    connection.disconnect()
                    code
                }
            }

            binding.btnTestUrl.isEnabled = true
            binding.btnTestUrl.text = getString(R.string.test_url)

            response.onSuccess { code ->
                val ok = code in 200..399
                val message = if (ok) {
                    getString(R.string.reachable_http, code)
                } else {
                    getString(R.string.responded_http, code)
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.test_failed, error.message ?: getString(R.string.unknown_error)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun checkForUpdates(repoSlug: String) {
        binding.btnCheckUpdates.isEnabled = false
        binding.updateStatusValue.text = getString(R.string.checking_updates)

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateManager.fetchLatestRelease(repoSlug)
            }

            binding.btnCheckUpdates.isEnabled = true
            result.onSuccess { release ->
                val currentVersion = currentVersionName()
                val currentLabel = UpdateManager.normalizedVersionLabel(currentVersion)
                val latestLabel = UpdateManager.normalizedVersionLabel(release.tagName)
                val hasUpdate = UpdateManager.isNewerRelease(currentVersion, release.tagName)
                if (!hasUpdate) {
                    val message = getString(R.string.up_to_date_version_detailed, currentLabel, latestLabel)
                    binding.updateStatusValue.text = composeUpdateStatusText(message)
                    prefs.recordUpdateState(message)
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }

                val asset = release.apkAsset
                if (asset == null) {
                    val message = getString(R.string.update_found_no_apk, latestLabel)
                    binding.updateStatusValue.text = composeUpdateStatusText(message)
                    prefs.recordUpdateState(message)
                    return@onSuccess
                }

                val status = getString(R.string.update_available_status, latestLabel, asset.name)
                binding.updateStatusValue.text = composeUpdateStatusText(status)
                prefs.recordUpdateState(status)
                showDownloadUpdatePrompt(release)
            }.onFailure { error ->
                val message = getString(R.string.update_check_failed, error.message ?: getString(R.string.unknown_error))
                binding.updateStatusValue.text = composeUpdateStatusText(message)
                prefs.recordUpdateState(message)
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun composeUpdateStatusText(lastStatus: String? = prefs.diagnosticsSnapshot().lastUpdateState): String {
        val installed = UpdateManager.normalizedVersionLabel(currentVersionName())
        val status = lastStatus?.trim().orEmpty()
        if (status.isBlank() || status.equals("never checked", ignoreCase = true)) {
            return getString(R.string.update_status_installed, installed)
        }
        return getString(R.string.update_status_with_last, installed, status)
    }

    private fun showDownloadUpdatePrompt(release: ReleaseInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title, release.tagName))
            .setMessage(getString(R.string.update_download_prompt, release.apkAsset?.name ?: "APK"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_update) { _, _ ->
                val downloadId = UpdateManager.enqueueDownload(
                    context = this,
                    releaseInfo = release,
                    appLabel = getString(R.string.app_name)
                )
                activeDownloadId = downloadId
                Toast.makeText(this, getString(R.string.update_download_started), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun installDownloadedApk(downloadId: Long) {
        val uri = UpdateManager.queryDownloadedUri(this, downloadId)
        if (uri == null) {
            Toast.makeText(this, getString(R.string.update_download_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(installIntent)
    }

    private fun shareConfigJson(json: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "WallMode config")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_config)))
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.paste_config_json)
            minLines = 8
            maxLines = 12
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_config)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_config) { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                if (raw.isBlank()) {
                    Toast.makeText(this, getString(R.string.empty_config), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val previousTheme = prefs.load().themeMode
                runCatching { prefs.importSettingsJson(raw) }
                    .onSuccess { imported ->
                        bindSettingsToUi(imported)
                        markSaved()
                        if (previousTheme != imported.themeMode) {
                            prefs.applyThemeMode()
                            recreate()
                        }
                        setResult(RESULT_OK, intentWithReload())
                        Toast.makeText(this, getString(R.string.config_imported), Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            this,
                            getString(R.string.import_failed, error.message ?: getString(R.string.unknown_error)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
            .show()
    }

    private fun maybeExitKiosk() {
        val requiresPassword = prefs.hasAdminPassword()
        if (!requiresPassword) {
            setResult(RESULT_OK, intentWithExit())
            finish()
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.admin_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_kiosk)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.unlock, null)
            .create()

        dialog.setOnShowListener {
            val unlockButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val unlockLabel = getString(R.string.unlock)
            unlockButton.setOnClickListener {
                val candidate = input.text?.toString().orEmpty()
                if (candidate.isBlank()) {
                    input.error = getString(R.string.invalid_admin_password)
                    return@setOnClickListener
                }

                unlockButton.isEnabled = false
                unlockButton.text = getString(R.string.verifying_password)
                input.error = null

                scope.launch {
                    val verifiedCredential = withContext(Dispatchers.Default) {
                        prefs.verifyAdminPasswordForUnlock(candidate)
                    }
                    if (!dialog.isShowing) return@launch

                    unlockButton.isEnabled = true
                    unlockButton.text = unlockLabel
                    if (verifiedCredential != null && prefs.markAdminUnlockedNow(verifiedCredential)) {
                        dialog.dismiss()
                        setResult(RESULT_OK, intentWithExit())
                        finish()
                    } else {
                        input.error = getString(R.string.invalid_admin_password)
                        input.text?.clear()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun ensureDownloadReceiver() {
        if (downloadReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        downloadReceiverRegistered = true
    }

    private fun unregisterDownloadReceiverIfNeeded() {
        if (!downloadReceiverRegistered) return
        runCatching { unregisterReceiver(downloadReceiver) }
        downloadReceiverRegistered = false
    }

    private fun intentWithReload() = Intent().apply {
        putExtra(EXTRA_RELOAD_NOW, true)
    }

    private fun intentWithExit() = Intent().apply {
        putExtra(EXTRA_EXIT_APP, true)
    }

    private fun intentWithControlPanelApply() = Intent().apply {
        putExtra(EXTRA_APPLY_CONTROL_PANEL, true)
    }

    private data class SectionNav(
        val buttonId: Int,
        val button: MaterialButton,
        val titleRes: Int,
        val subtitleRes: Int,
        val view: View
    )

    companion object {
        const val EXTRA_RELOAD_NOW = "reload_now"
        const val EXTRA_EXIT_APP = "exit_app"
        const val EXTRA_APPLY_CONTROL_PANEL = "apply_control_panel"
        private const val MIN_ADMIN_PASSWORD_LENGTH = 4
        private const val STATE_SELECTED_SECTION = "state_selected_section"
        private const val STATE_HAS_UNSAVED_CHANGES = "state_has_unsaved_changes"
    }

    private fun currentVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }
}
