package io.github.rvbcrs.wallmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IlluminanceReportGateTest {
    @Test
    fun firstReadingAlwaysReports() {
        val gate = IlluminanceReportGate(minDeltaLux = 10f, debounceMillis = 1_000L)
        assertTrue(gate.shouldReport(100f, nowMillis = 0L))
    }

    @Test
    fun suppressesChangesBelowDelta() {
        val gate = IlluminanceReportGate(minDeltaLux = 10f, debounceMillis = 1_000L)
        assertTrue(gate.shouldReport(100f, 0L))
        // +5 lux is below the 10 lux delta even after the debounce window.
        assertFalse(gate.shouldReport(105f, 5_000L))
    }

    @Test
    fun suppressesChangesInsideDebounceWindow() {
        val gate = IlluminanceReportGate(minDeltaLux = 10f, debounceMillis = 1_000L)
        assertTrue(gate.shouldReport(100f, 0L))
        // +50 lux passes the delta but only 500ms elapsed (< 1000ms debounce).
        assertFalse(gate.shouldReport(150f, 500L))
    }

    @Test
    fun reportsWhenBothDeltaAndDebounceSatisfied() {
        val gate = IlluminanceReportGate(minDeltaLux = 10f, debounceMillis = 1_000L)
        assertTrue(gate.shouldReport(100f, 0L))
        assertTrue(gate.shouldReport(150f, 1_500L))
        // The baseline advanced to 150 and the clock to 1500.
        assertFalse(gate.shouldReport(155f, 3_000L)) // below delta
        assertTrue(gate.shouldReport(200f, 3_000L))  // delta + debounce ok
    }

    @Test
    fun resetRestoresBaselineReport() {
        val gate = IlluminanceReportGate(minDeltaLux = 10f, debounceMillis = 1_000L)
        assertTrue(gate.shouldReport(100f, 0L))
        assertFalse(gate.shouldReport(101f, 2_000L))
        gate.reset()
        assertTrue(gate.shouldReport(101f, 3_000L))
    }
}
