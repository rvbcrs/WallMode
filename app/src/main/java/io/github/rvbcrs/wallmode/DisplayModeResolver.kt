package io.github.rvbcrs.wallmode

/**
 * Pure resolver for the reported display mode and the screen-off decision, so
 * the branching can be unit-tested without an Activity. [MainActivity] holds the
 * live `isAmbientDimmed` / `isScreenOff` / screensaver-visibility state and
 * delegates here.
 */
object DisplayModeResolver {

    /** Maps the current display state to the MQTT display-mode string. */
    fun displayMode(
        isAmbientDimmed: Boolean,
        isScreenOff: Boolean,
        screensaverVisible: Boolean
    ): String = when {
        !isAmbientDimmed -> MqttContract.DISPLAY_DASHBOARD
        isScreenOff -> MqttContract.DISPLAY_OFF
        screensaverVisible -> MqttContract.DISPLAY_SCREENSAVER
        else -> MqttContract.DISPLAY_DIM
    }

    /**
     * Whether entering ambient mode should actually power the panel off.
     * [forceDeepSleep] is set by an explicit DISPLAY_OFF command (overrides the
     * pref); otherwise the [KioskSettings.deepSleepEnabled] pref decides. A
     * device that is not device owner can still request screen-off — the
     * fallback is handled at the call site (brightness 0), so this only reports
     * intent, not capability.
     */
    fun shouldScreenOff(settings: KioskSettings, forceDeepSleep: Boolean): Boolean =
        forceDeepSleep || settings.deepSleepEnabled
}
