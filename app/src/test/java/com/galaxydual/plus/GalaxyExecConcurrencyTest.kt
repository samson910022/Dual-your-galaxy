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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // tryAcquireLease caps at the permit count: every permit can be held at
    // once and the next acquire fails fast (null, never blocks a Binder
    // thread), then a single close admits exactly one more.
    @Test fun serviceGate_tryAcquireLeaseCapsAtMaxAndRecovers() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
        val leases = mutableListOf<ServiceExecGate.Lease>()
        repeat(SERVICE_EXEC_MAX_CONCURRENT) {
            val lease = gate.tryAcquireLease()
            assertNotNull("every permit must acquire", lease)
            leases += lease!!
        }
        assertEquals(0, gate.availablePermits())
        assertNull("acquire past the cap must fail fast", gate.tryAcquireLease())
        leases.removeAt(0).close()
        assertEquals(1, gate.availablePermits())
        val admitted = gate.tryAcquireLease()
        assertNotNull("a single close must admit one more", admitted)
        leases += admitted!!
        leases.forEach { it.close() }
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
    }

    // Concurrent hammer on an isolated gate: 20 threads x 20 rounds of
    // lease acquire-hold-close must never exceed the cap at once, and every
    // permit must be back afterwards (no leak, no double-close past the cap).
    // A CyclicBarrier(21) start gate (20 workers + main) releases all
    // workers together. First-wave holders block on the releaseHold latch
    // until every worker has made its first tryAcquireLease attempt, so the
    // first wave deterministically fills the cap and the rest are rejected
    // (null lease) while the permits are held; later rounds hold for 10ms inside.
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
                        val lease = gate.tryAcquireLease()
                        if (lease != null) {
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
                                lease.close()
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

    // Double-close idempotency: closing the same lease twice (or N times)
    // is a no-op after the first close and must never inflate the permit
    // count past the cap, and the gate stays fully usable afterwards.
    @Test fun serviceGate_doubleCloseNeverInflatesCap() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        val lease = gate.tryAcquireLease()
        assertNotNull(lease)
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT - 1, gate.availablePermits())
        lease!!.close()
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
        lease.close()
        lease.close()
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
        // Still functional after double-close: the full cap can be held again.
        val leases = mutableListOf<ServiceExecGate.Lease>()
        repeat(SERVICE_EXEC_MAX_CONCURRENT) {
            val next = gate.tryAcquireLease()
            assertNotNull("gate must still admit the full cap after double-close", next)
            leases += next!!
        }
        assertEquals(0, gate.availablePermits())
        leases.forEach { it.close() }
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
    }

    // Permit restoration: a holder that throws (including Error) still
    // returns its permit via lease.use close. This pins the gate-level
    // contract behind the onTransact single finally lease.close().
    @Test fun serviceGate_permitRestoredWhenHolderThrows() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        var caught: Throwable? = null
        try {
            val lease = gate.tryAcquireLease()
            assertNotNull(lease)
            assertEquals(SERVICE_EXEC_MAX_CONCURRENT - 1, gate.availablePermits())
            lease!!.use { throw Error("boom") }
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue("use must propagate the Error, caught=$caught", caught is Error)
        assertEquals("boom", caught?.message)
        assertEquals(
            "permit must be restored after a throwing holder",
            SERVICE_EXEC_MAX_CONCURRENT,
            gate.availablePermits(),
        )
    }

    // Two-holder double-close regression: closing one holder's lease twice
    // must return only its own permit (availablePermits == cap-1 while the
    // other holder is still live, never cap). With B still live, C/D/E fill
    // to exactly cap (peak 4, never 5) and F is rejected.
    @Test fun serviceGate_twoHoldersDoubleCloseNeverExceedsCap() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        val a = gate.tryAcquireLease()
        assertNotNull(a)
        val b = gate.tryAcquireLease()
        assertNotNull(b)
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT - 2, gate.availablePermits())
        a!!.close()
        a.close()
        assertEquals(
            "double-close of A must return only A's permit while B is still live",
            SERVICE_EXEC_MAX_CONCURRENT - 1,
            gate.availablePermits(),
        )
        val c = gate.tryAcquireLease()
        assertNotNull(c)
        val d = gate.tryAcquireLease()
        assertNotNull(d)
        val e = gate.tryAcquireLease()
        assertNotNull(e)
        assertEquals("B+C+D+E must fill the cap exactly (peak 4, never 5)", 0, gate.availablePermits())
        assertNull("a fifth live lease must be rejected (peak never 5)", gate.tryAcquireLease())
        b!!.close()
        c!!.close()
        d!!.close()
        e!!.close()
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT, gate.availablePermits())
    }

    // Same-lease concurrent closes: 2 threads x 10 closes of one lease must
    // return only one permit (AtomicBoolean CAS on Lease.close).
    @Test fun serviceGate_sameLeaseConcurrentClosesOnlyReturnsOnePermit() {
        val gate = ServiceExecGate(SERVICE_EXEC_MAX_CONCURRENT)
        val lease = gate.tryAcquireLease()
        assertNotNull(lease)
        assertEquals(SERVICE_EXEC_MAX_CONCURRENT - 1, gate.availablePermits())
        val errors = CopyOnWriteArrayList<Throwable>()
        val startBarrier = CyclicBarrier(2)
        val threads = (0 until 2).map { idx ->
            thread(isDaemon = true, name = "service-gate-close-$idx") {
                try {
                    startBarrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    repeat(10) { lease!!.close() }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.join(10_000L) }
        assertTrue(threads.none { it.isAlive })
        assertTrue("close threads must not throw, got: ${errors.firstOrNull()}", errors.isEmpty())
        assertEquals(
            "2x10 concurrent closes of one lease must return only one permit",
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
