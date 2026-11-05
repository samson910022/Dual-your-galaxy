// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// P1-2 (PR-32 round 2 Phase B): JVM tests for the bounded client-side await.
// A fake blocking Binder is modelled by a lambda that sleeps / never returns
// / returns late / throws -- no Android classes involved.
// P2 (issue #24): awaitBounded is now a suspend cancellable wait on a shared
// bounded pool, so tests also lock cancellation handover (a cancelled hung
// wait ends at once with CancellationException; the next call proceeds
// immediately -- the selectChannel latest-wins handover) and pool boundedness
// (repeated hung calls never accumulate one thread per call).
package com.galaxydual.plus

import com.galaxydual.core.CmdResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedAwaitTest {
    // Non-cooperative hung stand-in for binder.transact: ignores cancel
    // (no interrupt delivered) and keeps running, exactly like a hung
    // transact ignores it.
    // The REMOTE side is untouched by cancellation; only the caller stops
    // waiting. Signals `started` (if given) so the test can cancel/await
    // while the block is genuinely in flight.
    private fun hungBlock(started: CountDownLatch? = null, hangMs: Long = 30_000L): CmdResult {
        started?.countDown()
        val endMs = System.currentTimeMillis() + hangMs
        while (System.currentTimeMillis() < endMs) {
            try {
                Thread.sleep(10L)
            } catch (_: InterruptedException) {
                // Swallowed on purpose: the late reply still materializes.
            }
        }
        return CmdResult(exit = 0, out = "late", err = "")
    }

    private fun liveTransactThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith("shizuku-transact-") && it.isAlive }

    // A never-returning block must not hold the caller past the deadline:
    // 100ms budget against a 30s sleep must return null far under 5s. The
    // 2000ms bound is deliberately loose to avoid flakiness on slow CI.
    // Division of labor: this test only proves the wait is bounded (an
    // interruptible sleep would also return fast under the old
    // withTimeoutOrNull wrapper via cooperative cancellation); the regression
    // test that falsifies the old bug is lateReturn_... below, whose
    // swallow-the-interrupt busy-wait models a non-cooperative transact that
    // the old wrapper could not bound.
    @Test fun neverReturns_returnsNullWithinBound() = runBlocking {
        val startNs = System.nanoTime()
        val res = awaitBounded(100L) {
            Thread.sleep(30_000L)
            CmdResult(exit = 0, out = "never", err = "")
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertNull("never-returning block must time out, got: $res", res)
        assertTrue("client wait must be bounded (elapsed=${elapsedMs}ms)", elapsedMs < 2_000L)
    }

    // A reply that arrives after the deadline is discarded and must not touch
    // external state: the block simulates a non-cancellable transact with a
    // busy wait that ignores cancel (no interrupt delivered), produces its
    // value late, and only records it locally. This busy-wait (not neverReturns_... above)
    // is the regression test for the old withTimeoutOrNull bug: the busy wait
    // models binder.transact ignoring cancellation and running to completion,
    // which the old wrapper could not bound.
    // Scope limit: awaitExternalTouched below only locks the await-layer null
    // semantic (late value discarded, no shared-state write). The Parcel-layer
    // dropBinding/fresh-rebind behavior lives in transactExec and is not
    // observable from this JVM test (no Android Parcel/binder); it is
    // guaranteed by the gateway structure, not asserted here.
    @Test fun lateReturn_discardedAndExternalStateUntouched() = runBlocking {
        // Named for scope: await-layer external-state isolation only, not the
        // Parcel-layer dropBinding (see comment above).
        val awaitExternalTouched = AtomicBoolean(false)
        val lateCapture = AtomicReference<CmdResult?>(null)
        val startNs = System.nanoTime()
        val res = awaitBounded(100L) {
            hungBlock(hangMs = 300L).also { lateCapture.set(it) }
            // Deliberately does NOT touch awaitExternalTouched: the late value
            // lives only in the local capture.
            lateCapture.get()!!
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertNull("late reply must be discarded (await returns null), got: $res", res)
        assertTrue("late-reply wait must still be bounded (elapsed=${elapsedMs}ms)", elapsedMs < 2_000L)
        // The block outlives the 100ms budget (~300ms of work), so poll for
        // its late completion before locking the isolation assertions.
        val deadlineMs = System.currentTimeMillis() + 2_000L
        while (lateCapture.get() == null && System.currentTimeMillis() < deadlineMs) {
            delay(10L)
        }
        assertNotNull("late block should have completed", lateCapture.get())
        assertFalse("late result must not overwrite external state", awaitExternalTouched.get())
    }

    // A fast block passes its value straight through.
    @Test fun fastBlock_passthrough() = runBlocking {
        val expected = CmdResult(exit = 0, out = "ok", err = "")
        val res = awaitBounded(5_000L) { expected }
        assertEquals(expected, res)
    }

    // A non-cancellation exception from the block propagates to the caller
    // (transactExec maps it to transportUnknown; the await layer never swallows).
    @Test fun blockException_propagates() = runBlocking {
        try {
            awaitBounded(5_000L) { throw IllegalStateException("boom") }
            fail("block exception must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    // P2: cancelling a hung wait stops the CALLER at once with
    // CancellationException instead of holding it to the deadline. This
    // falsifies the old blocking Future.get design, where the awaiting
    // thread could not be released by Job cancellation (the old select job
    // stuck in a hung Binder wait forced the next select to wait out the
    // whole deadline on opMutex). The 30s timeout vs 2s bound proves the
    // wait ends by cancellation, not by expiry.
    @Test fun cancelHungAwait_stopsWaitAtOnceWithCancellation() = runBlocking {
        val started = CountDownLatch(1)
        // Dispatchers.Default: the waiter must run off the runBlocking event
        // loop, which is about to block in started.await() below.
        val job = async(Dispatchers.Default) {
            awaitBounded(30_000L) { hungBlock(started) }
        }
        assertTrue("hung block must be in flight before cancelling", started.await(5, TimeUnit.SECONDS))
        val startNs = System.nanoTime()
        job.cancel()
        try {
            job.await()
            fail("cancelled hung await must throw CancellationException")
        } catch (_: CancellationException) {
            // Expected: the caller stops waiting at once; the remote side
            // keeps running on its pool thread, untouched.
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue(
            "cancelled wait must end at once, not at the deadline (elapsed=${elapsedMs}ms)",
            elapsedMs < 2_000L,
        )
    }

    // P2: the selectChannel latest-wins handover at the await layer -- after
    // the old hung wait is cancelled, a fresh call proceeds immediately on
    // the still-healthy pool instead of wedging behind the hung worker.
    @Test fun cancelHungThenFastCall_handsOverQuickly() = runBlocking {
        val started = CountDownLatch(1)
        // Dispatchers.Default: see cancelHungAwait_... above.
        val hung = async(Dispatchers.Default) {
            awaitBounded(30_000L) { hungBlock(started) }
        }
        assertTrue("hung block must be in flight before cancelling", started.await(5, TimeUnit.SECONDS))
        hung.cancel()
        try {
            hung.await()
        } catch (_: CancellationException) {
            // Expected: the old wait is gone.
        }
        val startNs = System.nanoTime()
        val res = awaitBounded(5_000L) { CmdResult(exit = 0, out = "ok", err = "") }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertEquals("handover call must complete", "ok", res?.out)
        assertTrue("handover must be immediate (elapsed=${elapsedMs}ms)", elapsedMs < 2_000L)
    }

    // P2: repeated hung calls must not accumulate one blocked thread per
    // call (the old per-call single-thread executor did exactly that:
    // 12 hung calls left 12 live "shizuku-transact-N" threads). The shared
    // pool caps live transact threads at TRANSACT_MAX_THREADS, and
    // cancelled/timeout tasks leave a no-op shell in the queue (supplier
    // never runs) until a worker dequeues and discards it.
    @Test fun repeatedHungCalls_poolStaysBounded() = runBlocking {
        repeat(12) {
            val res = awaitBounded(100L) { hungBlock(hangMs = 300L) }
            assertNull("hung call must time out, got: $res", res)
        }
        val deadlineMs = System.currentTimeMillis() + 5_000L
        while (transactExecutor.queue.isNotEmpty() && System.currentTimeMillis() < deadlineMs) {
            delay(10L)
        }
        assertEquals(
            "cancelled/timeout tasks must not pile in the queue",
            0,
            transactExecutor.queue.size,
        )
        assertTrue(
            "pool must never exceed its cap (poolSize=${transactExecutor.poolSize})",
            transactExecutor.poolSize <= TRANSACT_MAX_THREADS,
        )
        assertTrue(
            "live transact threads must stay bounded, got ${liveTransactThreads()}",
            liveTransactThreads() <= TRANSACT_MAX_THREADS,
        )
    }

    // P2: N CONCURRENT hung calls must never run more than
    // TRANSACT_MAX_THREADS blocks at once (AtomicInteger peak over the
    // burst, short timeouts). The semaphore admits at most 4 submissions;
    // the rest suspend on permits holding no thread and still time out by
    // their own deadline, so the whole burst settles ~together instead of
    // serializing or piling futures. Permits must be fully released after.
    @Test fun concurrentHungCalls_activePeakNeverExceedsCap() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val startNs = System.nanoTime()
        val results = (1..10).map {
            async {
                awaitBounded(400L) {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { p -> maxOf(p, now) }
                    try {
                        hungBlock(hangMs = 1_500L)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }.awaitAll()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("every hung call must time out, got: $results", results.all { it == null })
        assertTrue(
            "burst must settle ~together, not serialize (elapsed=${elapsedMs}ms)",
            elapsedMs < 4_000L,
        )
        assertTrue(
            "concurrent active blocks must stay capped (peak=${peak.get()})",
            peak.get() <= TRANSACT_MAX_THREADS,
        )
        val deadlineMs = System.currentTimeMillis() + 5_000L
        while ((transactExecutor.queue.isNotEmpty() ||
            transactPermits.availablePermits != TRANSACT_MAX_THREADS) &&
            System.currentTimeMillis() < deadlineMs
        ) {
            delay(10L)
        }
        assertEquals("queue must drain after burst", 0, transactExecutor.queue.size)
        assertEquals(
            "all permits must be released after burst",
            TRANSACT_MAX_THREADS,
            transactPermits.availablePermits,
        )
    }

    // P2: with all permits held by hung calls, one more waiter must still be
    // bounded by ITS OWN timeout (the permit gate sits inside
    // withTimeoutOrNull) -- it returns null fast instead of queueing past
    // its deadline behind the hung holders.
    @Test fun permitExhausted_waiterStillBoundedByItsOwnTimeout() = runBlocking {
        // Holders occupy every permit; the waiter assertion only needs the
        // permits HELD (not the blocks running -- pool threads may still be
        // busy with other tests' hung leftovers), so poll availablePermits
        // rather than block-entry latches.
        val holders = (1..TRANSACT_MAX_THREADS).map {
            async { awaitBounded(10_000L) { hungBlock(hangMs = 1_000L) } }
        }
        val permitDeadlineMs = System.currentTimeMillis() + 5_000L
        while (transactPermits.availablePermits != 0 && System.currentTimeMillis() < permitDeadlineMs) {
            delay(10L)
        }
        assertEquals(
            "all permits must be held before the waiter arrives",
            0,
            transactPermits.availablePermits,
        )
        val startNs = System.nanoTime()
        val res = awaitBounded(300L) { CmdResult(exit = 0, out = "should-not-run", err = "") }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertNull("permit-starved waiter must time out, got: $res", res)
        assertTrue(
            "permit waiter must respect its own deadline (elapsed=${elapsedMs}ms)",
            elapsedMs < 2_000L,
        )
        holders.forEach { it.cancel() }
        holders.forEach {
            // Holders end either way (CE if still hung, value if the 1s
            // block already finished); awaiting each one guarantees its
            // withPermit exited before the permit assertion below.
            try {
                it.await()
            } catch (_: CancellationException) {
            }
        }
        assertEquals(
            "permits must be released after holders are cancelled",
            TRANSACT_MAX_THREADS,
            transactPermits.availablePermits,
        )
    }
}
