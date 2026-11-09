// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// #54 Phase1: JVM tests for the GalaxyExecService concurrency gate. The
// Binder/Parcel onTransact round-trip is a framework stub on the JVM and is
// NOT covered here -- only the pure semaphore gate (ServiceExecGate) plus
// the frozen serviceBusy definite-reply mapping are pinned.
package com.galaxydual.plus

import com.galaxydual.core.TRUNCATION_MARKER
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyExecConcurrencyTest {
    // The cap must stay in the 2..4 band and mirror the client pool so the
    // server never admits more concurrent execs than the client can issue.
    @Test fun serviceGate_capIsWithinTwoToFour() {
        assertTrue("service cap must be >= 2, got $SERVICE_EXEC_MAX_CONCURRENT", SERVICE_EXEC_MAX_CONCURRENT >= 2)
        assertTrue("service cap must be <= 4, got $SERVICE_EXEC_MAX_CONCURRENT", SERVICE_EXEC_MAX_CONCURRENT <= 4)
        assertEquals(
            "service cap must mirror the client transact pool",
            TRANSACT_MAX_THREADS,
            SERVICE_EXEC_MAX_CONCURRENT,
        )
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
    // A CyclicBarrier(21) start gate (20 workers + main) releases all
    // workers together. First-wave holders block on the releaseHold latch
    // until every worker has made its first tryAcquire attempt, so the
    // first wave deterministically fills the cap and the rest are rejected
    // while the permits are held; later rounds hold for 10ms inside.
    @Test fun serviceGate_concurrentHammer_peakNeverExceedsCap() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val rejections = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val startBarrier = CyclicBarrier(21)
        val firstAttempted = CountDownLatch(20)
        val releaseHold = CountDownLatch(1)
        val threads = (0 until 20).map { idx ->
            thread(isDaemon = true, name = "service-gate-hammer-$idx") {
                try {
                    startBarrier.await()
                    repeat(20) { round ->
                        if (gate.tryAcquire()) {
                            try {
                                val now = active.incrementAndGet()
                                peak.updateAndGet { p -> maxOf(p, now) }
                                if (round == 0) {
                                    // First-wave holder: signal the attempt
                                    // and keep the permit held until main
                                    // has seen every worker's first attempt.
                                    firstAttempted.countDown()
                                    releaseHold.await(10, java.util.concurrent.TimeUnit.SECONDS)
                                } else {
                                    Thread.sleep(10L)
                                }
                            } finally {
                                active.decrementAndGet()
                                gate.release()
                            }
                        } else {
                            rejections.incrementAndGet()
                            if (round == 0) firstAttempted.countDown()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        try {
            startBarrier.await()
            firstAttempted.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            releaseHold.countDown()
        }
        threads.forEach { it.join(10_000L) }
        System.out.println("serviceGateHammer peak=${peak.get()} rejections=${rejections.get()}")
        assertTrue(threads.none { it.isAlive })
        assertTrue("hammer threads must not throw, got: ${errors.firstOrNull()}", errors.isEmpty())
        assertTrue("peak concurrency must stay capped (peak=${peak.get()})", peak.get() <= SERVICE_EXEC_MAX_CONCURRENT)
        assertEquals(
            "hammer must fill the cap exactly under the start barrier (peak=${peak.get()}, rejections=${rejections.get()})",
            SERVICE_EXEC_MAX_CONCURRENT,
            peak.get(),
        )
        assertTrue("hammer must have rejected excess acquires (rejections=${rejections.get()})", rejections.get() >= 1)
        assertEquals("all permits must be released after hammer", SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
        assertEquals("no holder may remain", 0, active.get())
    }

    // Over-release guard: releasing without a matching acquire is a no-op
    // and must never inflate the permit count past the cap, and the gate
    // stays fully usable afterwards (the held counter clamps at zero).
    @Test fun serviceGate_overReleaseNeverInflatesCap() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        gate.release()
        gate.release()
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
        assertTrue(gate.tryAcquire())
        gate.release()
        gate.release()
        gate.release()
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
        // Still functional after over-release: the full cap can be held again.
        repeat(SERVICE_EXEC_MAX_CONCURRENT) {
            assertTrue("gate must still admit the full cap after over-release", gate.tryAcquire())
        }
        assertEquals(0, gate.availablePermits())
        repeat(SERVICE_EXEC_MAX_CONCURRENT) { gate.release() }
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
    }

    // Permit restoration: a holder that throws still returns its permit via
    // try/finally-release. This pins the gate-level contract behind the
    // onTransact single finally release (release count == acquire count).
    @Test fun serviceGate_permitRestoredWhenHolderThrows() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        var caught: Throwable? = null
        try {
            assertTrue(gate.tryAcquire())
            assertEquals(SERVICE_EXEC_MAX_CONCURRENT - 1, gate.availablePermits())
            throw RuntimeException("boom")
        } catch (t: Throwable) {
            caught = t
        } finally {
            gate.release()
        }
        assertEquals("boom", caught?.message)
        assertEquals(
            "permit must be restored after a throwing holder",
            SERVICE_EXEC_MAX_CONCURRENT,
            gate.availablePermits(),
        )
    }

    // The refusal reply is a pre-dispatch definite failure: exit == 1,
    // names the concurrency limit, and never hits an unknown-after-send
    // backstop (the command was never dispatched, so the repository must
    // judge it definite, never reconcile read-only).
    // Backstop note: the prefix list below must stay aligned with
    // CmdGateway.isPostSpawnTransportText / isServiceTimeoutText (core) and
    // DualRepository.isUnknownAfterSend, which match these anchored
    // prefixes by text once the transport flags are lost on the wire -- if
    // a new producer prefix is added there, it must also be pinned here.
    @Test fun serviceBusy_isPreDispatchDefiniteFailure() {
        val res = ShizukuExecErrors.serviceBusy()
        assertEquals(1, res.exit)
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
        assertEquals(res.out, decoded.out)
        assertEquals(res.err, decoded.err)
        assertFalse(decoded.truncated)
        assertFalse(decoded.transportUnknown)
        assertFalse(decoded.timedOut)
    }
}
