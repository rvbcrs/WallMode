package io.github.rvbcrs.wallmode

import kotlin.math.abs

/**
 * Decides whether a new illuminance reading is worth reporting. A reading is
 * reported when it differs from the last reported value by at least
 * [minDeltaLux] AND at least [debounceMillis] has elapsed since the last report.
 * The very first reading after a reset always reports (establishes a baseline in
 * Home Assistant). Pure and clock-injected so it can be unit-tested.
 */
internal class IlluminanceReportGate(
    private val minDeltaLux: Float,
    private val debounceMillis: Long
) {
    private var lastReportedLux: Float? = null
    private var lastReportAtMillis: Long = 0L

    fun reset() {
        lastReportedLux = null
        lastReportAtMillis = 0L
    }

    /** Returns true if [lux] at time [nowMillis] should be reported now. */
    fun shouldReport(lux: Float, nowMillis: Long): Boolean {
        val last = lastReportedLux
        if (last == null) {
            lastReportedLux = lux
            lastReportAtMillis = nowMillis
            return true
        }
        if (abs(lux - last) < minDeltaLux) return false
        if (nowMillis - lastReportAtMillis < debounceMillis) return false
        lastReportedLux = lux
        lastReportAtMillis = nowMillis
        return true
    }
}
