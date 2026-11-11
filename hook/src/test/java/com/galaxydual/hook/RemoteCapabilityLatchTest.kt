// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// P2-1: remoteCapable=false must not latch forever.
//
// Pins the RemoteCapabilityLatch seam: only true latches; false is
// cooldown-gated and re-probed, and invalidate() allows an immediate retry.
class RemoteCapabilityLatchTest {

    @Test
    fun falseThenTrueFlipsAfterCooldown() {
        var time = 0L
        val latch = RemoteCapabilityLatch(nowMs = { time }, cooldownMs = 5_000L)
        assertTrue(latch.shouldProbe())
        latch.reportFalse()
        // Within cooldown: no re-probe.
        time = 1_000L
        assertFalse(latch.shouldProbe())
        // After cooldown: re-probe allowed.
        time = 6_000L
        assertTrue(latch.shouldProbe())
        latch.reportTrue()
        assertTrue(latch.isLatchedTrue())
        assertFalse(latch.shouldProbe())
    }

    @Test
    fun trueLatchesForever_falseNeverOverwritesTrue() {
        var time = 0L
        val latch = RemoteCapabilityLatch(nowMs = { time })
        latch.reportTrue()
        assertTrue(latch.isLatchedTrue())
        time = 1_000_000L
        latch.reportFalse()
        assertTrue(latch.isLatchedTrue())
        assertFalse(latch.shouldProbe())
    }

    @Test
    fun invalidateClearsTransientFalseForImmediateRetry() {
        var time = 0L
        val latch = RemoteCapabilityLatch(nowMs = { time })
        latch.reportFalse()
        time = 1_000L
        assertFalse(latch.shouldProbe())
        latch.onInvalidate()
        assertTrue(latch.shouldProbe())
    }

    @Test
    fun invalidateDoesNotClearLatchedTrue() {
        val latch = RemoteCapabilityLatch()
        latch.reportTrue()
        latch.onInvalidate()
        assertTrue(latch.isLatchedTrue())
        assertFalse(latch.shouldProbe())
    }
}
