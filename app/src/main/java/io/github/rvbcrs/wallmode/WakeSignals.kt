package io.github.rvbcrs.wallmode

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between [PresenceWakeService] (which owns the camera and
 * proximity sensor and runs even while the screen is off) and [MainActivity]
 * (which owns the window and therefore the only code that can power the panel
 * back on).
 *
 * The service is the producer: it emits a [wakeEvents] tick whenever motion is
 * detected and the screen should turn on. The Activity collects those ticks and
 * runs the wake sequence. [powerState] lets the service observe whether the
 * Activity has reported the panel off, so it can decide whether a detection
 * warrants a wake at all.
 */
object WakeSignals {
    // replay = 0: a wake tick is only meaningful live; extraBufferCapacity = 1
    // so the service never suspends emitting even if the Activity is momentarily
    // not collecting (e.g. mid screen-on transition).
    private val _wakeEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val wakeEvents: SharedFlow<Unit> = _wakeEvents.asSharedFlow()

    private val _powerState = MutableStateFlow(DisplayPowerState.ON)
    val powerState: StateFlow<DisplayPowerState> = _powerState.asStateFlow()

    /** Called by the service when motion should wake the screen. */
    fun requestWake() {
        _wakeEvents.tryEmit(Unit)
    }

    /** Called by the Activity as it drives the display between on and off. */
    fun setPowerState(state: DisplayPowerState) {
        _powerState.value = state
    }
}

enum class DisplayPowerState {
    ON,
    OFF
}
