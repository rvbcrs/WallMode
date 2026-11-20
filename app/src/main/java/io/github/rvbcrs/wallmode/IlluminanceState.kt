package io.github.rvbcrs.wallmode

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Shares the ambient light sensor reading from [PresenceWakeService] (which runs
 * the sensor even while the screen is off) and MainActivity (which owns the
 * MqttManager and does the actual publish). The producer stores the latest lux
 * and emits a [publishRequests] tick when a change passes the delta/debounce
 * gate; MainActivity collects that and publishes the current state.
 */
object IlluminanceState {
    @Volatile
    private var latestLux: Float? = null

    private val _publishRequests = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val publishRequests: SharedFlow<Unit> = _publishRequests.asSharedFlow()

    /** Latest reading, or null if the sensor hasn't reported yet / is absent. */
    fun currentLux(): Float? = latestLux

    /** Store a new reading without requesting a publish (used for the baseline). */
    fun record(lux: Float) {
        latestLux = lux
    }

    /** Store a new reading and ask MainActivity to publish MQTT state now. */
    fun recordAndPublish(lux: Float) {
        latestLux = lux
        _publishRequests.tryEmit(Unit)
    }

    /** Clears state when reporting is turned off or the sensor stops. */
    fun reset() {
        latestLux = null
    }
}
