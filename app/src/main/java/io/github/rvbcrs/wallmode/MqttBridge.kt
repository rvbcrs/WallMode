package io.github.rvbcrs.wallmode

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between [MainActivity] and [MqttService].
 *
 * The activity is the producer of UI-specific state: it pushes the latest
 * [DashboardState] (current page, display mode, takeover, etc.) via
 * [dashboardState]. [MqttService] merges that with device-level readings it
 * reads independently (battery, screen, network) and publishes the combined
 * [WallModeMqttState] to the broker.
 *
 * Fire-and-forget events (motion, action responses) go via dedicated flows;
 * [MqttManager]'s inbound commands travel in the opposite direction on
 * [commands].
 *
 * [stateRequests] has been removed: the service no longer needs to ask the
 * activity to rebuild state — it can always merge the last [dashboardState]
 * value with its own live device readings.
 */
internal object MqttBridge {
    private val _dashboardState = MutableStateFlow<DashboardState?>(null)
    val dashboardState: StateFlow<DashboardState?> = _dashboardState.asStateFlow()

    private val _commands = MutableSharedFlow<WallModeMqttCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<WallModeMqttCommand> = _commands.asSharedFlow()

    private val _motionEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val motionEvents: SharedFlow<Unit> = _motionEvents.asSharedFlow()

    // (cardId, actionId) pairs for action-card responses.
    private val _actionResponses = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    val actionResponses: SharedFlow<Pair<String, String>> = _actionResponses.asSharedFlow()

    /** Called by the activity each time UI state changes. */
    fun publishDashboardState(state: DashboardState) {
        _dashboardState.value = state
    }

    /** Called by the activity when the presence sensor fires. */
    fun publishMotion() {
        _motionEvents.tryEmit(Unit)
    }

    /** Called by the activity to publish an action-card response. */
    fun publishActionResponse(cardId: String, actionId: String): Boolean {
        return _actionResponses.tryEmit(cardId to actionId)
    }

    /** Called by the service to forward an inbound MQTT command to the activity. */
    fun emitCommand(cmd: WallModeMqttCommand) {
        _commands.tryEmit(cmd)
    }
}
