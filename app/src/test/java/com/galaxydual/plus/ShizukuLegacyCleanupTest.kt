// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Phase D1 (P1 legacy fixed-tag migration cleanup): JVM unit tests for the
// one-time legacy record removal. The run() call site (after the
// ping/permission/v13 gate, before the first per-UID v4 bind) is
// device-only: Shizuku statics and Parcels throw "Stub!" on the JVM, so
// these tests drive ensureLegacyCleanupDone() directly through a fake
// ShizukuLegacyCleaner. The production RealShizukuLegacyCleaner is never
// constructed on the JVM (Stub!).
package com.galaxydual.plus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ShizukuLegacyCleanupTest {
    private class FakeLegacyCleaner(var delayMs: Long = 0L) : ShizukuLegacyCleaner {
        val calls = AtomicInteger(0)

        @Volatile
        var toThrow: Throwable? = null

        override fun removeLegacy() {
            calls.incrementAndGet()
            if (delayMs > 0L) Thread.sleep(delayMs)
            toThrow?.let { throw it }
        }
    }

    private fun makeGateway(fake: FakeLegacyCleaner): ShizukuCmdGateway =
        ShizukuCmdGateway("com.example.test", legacyCleaner = fake)

    @Test fun successFirstTry_marksComplete_singleCall() = runBlocking {
        val fake = FakeLegacyCleaner()
        val gw = makeGateway(fake)
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        gw.ensureLegacyCleanupDone()
        assertEquals(1, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
        gw.ensureLegacyCleanupDone()
        assertEquals(1, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
    }

    @Test fun failureRetries_notMarked() = runBlocking {
        val fake = FakeLegacyCleaner()
        fake.toThrow = RuntimeException("boom")
        val gw = makeGateway(fake)
        // Non-cancellation failure is swallowed, flag stays incomplete.
        gw.ensureLegacyCleanupDone()
        assertEquals(1, fake.calls.get())
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        // Next call retries and succeeds.
        fake.toThrow = null
        gw.ensureLegacyCleanupDone()
        assertEquals(2, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
        // Third call skips (already complete).
        gw.ensureLegacyCleanupDone()
        assertEquals(2, fake.calls.get())
    }

    @Test fun cancellationPropagates_flagStaysIncomplete() = runBlocking {
        val fake = FakeLegacyCleaner()
        fake.toThrow = CancellationException("cancel")
        val gw = makeGateway(fake)
        try {
            gw.ensureLegacyCleanupDone()
            fail("CancellationException must propagate, not be swallowed")
        } catch (e: CancellationException) {
            assertEquals("cancel", e.message)
        }
        assertFalse(gw.isLegacyCleanupCompleteForTest())
        assertEquals(1, fake.calls.get())
    }

    @Test fun concurrentFirstCalls_singleFlight() = runBlocking {
        val fake = FakeLegacyCleaner(delayMs = 50L)
        val gw = makeGateway(fake)
        val deferred = List(8) { async(Dispatchers.Default) { gw.ensureLegacyCleanupDone() } }
        deferred.forEach { it.await() }
        assertEquals(1, fake.calls.get())
        assertTrue(gw.isLegacyCleanupCompleteForTest())
    }
}
