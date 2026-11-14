// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// Phase3: reportFalse throttling (fixed window, no slide), shouldProbe fast
// path + clock-throw fail-closed (supports the DualHook gate-budget probe).
class RemoteCapabilityLatchPhase3Test {

    @Test
    fun rapidReportFalseDoesNotSlideWindow() {
        var time = 0L
        val latch = RemoteCapabilityLatch(nowMs = { time }, cooldownMs = 5_000L)
        assertTrue(latch.shouldProbe())
        latch.reportFalse() // t=0
        time = 1_000L
        assertFalse(latch.shouldProbe())
        // High-frequency second report inside the window is ignored (no slide).
        latch.reportFalse() // t=1000 ignored
        time = 5_000L
        // Window measured from the FIRST false (t=0): 5000-0>=5000 probes.
        assertTrue("fixed window must not slide, time=$time", latch.shouldProbe())
    }

    @Test
    fun reportFalseAtInsideWindowIsIgnored() {
        var time = 0L
        val latch = RemoteCapabilityLatch(nowMs = { time }, cooldownMs = 5_000L)
        latch.reportFalseAt(0L)
        time = 1_000L
        assertFalse(latch.shouldProbe())
        latch.reportFalseAt(1_000L) // ignored
        time = 5_000L
        assertTrue(latch.shouldProbe())
    }

    @Test
    fun throwStormThrottledViaReportFalseAt() {
        // Simulates the DualHook probe-throw path: every throw calls
        // reportFalseAt(now), but the window never slides.
        var time = 0L
        val latch = RemoteCapabilityLatch(nowMs = { time }, cooldownMs = 5_000L)
        latch.reportFalseAt(0L)
        time = 500L
        latch.reportFalseAt(500L) // throttled away
        latch.reportFalseAt(800L) // throttled away
        time = 5_000L
        assertTrue(latch.shouldProbe())
    }

    @Test
    fun shouldProbeClockThrowFailsClosedToNoProbe() {
        val latch = RemoteCapabilityLatch(nowMs = { throw IllegalStateException("clock") })
        latch.reportFalseAt(0L)
        // Clock broken: fail-closed to false (observe-only, no probe spin).
        assertFalse(latch.shouldProbe())
    }

    @Test
    fun latchedTrueFastPathDoesNotTouchClock() {
        val calls = AtomicInteger(0)
        val latch = RemoteCapabilityLatch(nowMs = { calls.incrementAndGet(); 0L })
        latch.reportTrue()
        assertFalse(latch.shouldProbe())
        assertFalse(latch.shouldProbe())
        assertTrue("latched-true must not call the clock", calls.get() == 0)
    }

    @Test
    fun noPriorFalseProbesWithoutClock() {
        val calls = AtomicInteger(0)
        val latch = RemoteCapabilityLatch(nowMs = { calls.incrementAndGet(); 0L })
        assertTrue(latch.shouldProbe())
        assertTrue("no-false fast path must not call the clock", calls.get() == 0)
    }
}
