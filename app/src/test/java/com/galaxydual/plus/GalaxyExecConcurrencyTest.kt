// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #54 Phase1: JVM tests for the GalaxyExecService concurrency gate. The
// Binder/Parcel onTransact round-trip is a framework stub on the JVM and is
// NOT covered here -- only the pure semaphore gate (ServiceExecGate) plus
// the frozen serviceBusy definite-reply mapping are pinned.
package com.galaxydual.plus

import com.galaxydual.core.TRUNCATION_MARKER
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyExecConcurrencyTest {
    // The cap must stay in the 2..4 band and mirror the client pool so the
    // server never admits more concurrent execs than the client can issue.
    @Test fun serviceGate_capIsWithinTwoToFour() {
        assertTrue("service cap must be >= 2, got $SERVICE_EXEC_MAX_CONCURRENT", SERVICE_EXEC_MAX_CONCURRENT >= 2)
        assertTrue("service cap must be <= 4, got $SERVICE_EXEC_MAX_CONCURRENT", SERVICE_EXEC_MAX_CONCURRENT <= 4)
    }

    // tryAcquire caps at the permit count: every permit can be held at once
    // and the next acquire fails fast (never blocks a Binder thread), then a
    // single release admits exactly one more.
    @Test fun serviceGate_tryAcquireCapsAtMaxAndRecovers() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
        val acquired = mutableListOf<Boolean>()
        repeat(SERVICE_EXEC_MAX_CONCURRENT) { acquired += gate.tryAcquire() }
        assertTrue("every permit must acquire, got $acquired", acquired.all { it })
        assertEquals(0, gate.availablePermits())
        assertFalse("acquire past the cap must fail fast", gate.tryAcquire())
        gate.release()
        assertEquals(1, gate.availablePermits())
        assertTrue("a single release must admit one more", gate.tryAcquire())
        repeat(SERVICE_EXEC_MAX_CONCURRENT) { gate.release() }
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
    }

    // Concurrent hammer on an isolated gate: 20 threads x 20 rounds of
    // acquire-hold-release must never exceed the cap at once, and every
    // permit must be back afterwards (no leak, no over-release past the cap).
    @Test fun serviceGate_concurrentHammer_peakNeverExceedsCap() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val threads = (0 until 20).map { idx ->
            thread(isDaemon = true, name = "service-gate-hammer-$idx") {
                try {
                    repeat(20) {
                        if (gate.tryAcquire()) {
                            try {
                                val now = active.incrementAndGet()
                                peak.updateAndGet { p -> maxOf(p, now) }
                                Thread.sleep(5L)
                            } finally {
                                active.decrementAndGet()
                                gate.release()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.join(10_000L) }
        assertTrue(threads.none { it.isAlive })
        assertTrue("hammer threads must not throw, got: ${errors.firstOrNull()}", errors.isEmpty())
        assertTrue("peak concurrency must stay capped (peak=${peak.get()})", peak.get() <= SERVICE_EXEC_MAX_CONCURRENT)
        assertTrue("hammer must have held at least one permit at a time (peak=${peak.get()})", peak.get() >= 1)
        assertEquals("all permits must be released after hammer", SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
        assertEquals("no holder may remain", 0, active.get())
    }

    // The refusal reply is a pre-dispatch definite failure: exit != 0,
    // names the concurrency limit, and never hits an unknown-after-send
    // backstop (the command was never dispatched, so the repository must
    // judge it definite, never reconcile read-only).
    @Test fun serviceBusy_isPreDispatchDefiniteFailure() {
        val res = ShizukuExecErrors.serviceBusy()
        assertNotEquals(0, res.exit)
        assertTrue("serviceBusy err must name the limit, got: ${res.err}", res.err.contains("resource exhausted"))
        assertTrue("serviceBusy err must name concurrency, got: ${res.err}", res.err.contains("concurrency limit"))
        assertFalse(res.timedOut)
        assertFalse(res.transportUnknown)
        assertFalse(res.truncated)
        for (prefix in listOf("service exec failed: ", "interrupted: ", "timeout after ", "shizuku: exec timed out after ", "may have executed")) {
            assertFalse(
                "serviceBusy err must not start with backstop prefix '$prefix', got: ${res.err}",
                res.err.startsWith(prefix),
            )
        }
        assertFalse(res.out.contains(TRUNCATION_MARKER))
        assertFalse(res.err.contains(TRUNCATION_MARKER))
        val (exit, out, err) = encodeExecReply(res)
        val decoded = decodeExecReply(exit, out, err)
        assertEquals(1, decoded.exit)
        assertEquals(res.err, decoded.err)
        assertFalse(decoded.truncated)
        assertFalse(decoded.transportUnknown)
        assertFalse(decoded.timedOut)
    }
}
