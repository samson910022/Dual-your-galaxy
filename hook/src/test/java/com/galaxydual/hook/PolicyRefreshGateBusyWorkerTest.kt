// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// T4: deterministic regression tests for the handoff-retry busy-worker /
// budget-exhausted case (H1 busy-worker accounting, H2 retry fail-fast, H3
// get-timeout floor).
//
// A permanently busy (or interrupt-ignoring) single worker must surface as
// PolicyRefreshBusyWorkerException — a PolicyRefreshContentionException
// subtype the cache layer already maps to seed-only — while the stuck-worker
// breaker accounts the streak via onTimeout() and opens after
// MAX_CONSECUTIVE_TIMEOUTS consecutive busy/timeout failures, instead of the
// old behavior (streak pinned < MAX, breaker never opens, every query pays
// ~50ms). None of these tests sleep.
class PolicyRefreshGateBusyWorkerTest {

    // T-F10: AlwaysRejectingExecutor comes from TestExecutors.kt (shared
    // with PolicyRefreshGatePhase3Test).

    // T-F11: the H2 fail-fast is now a real-time TIME FLOOR (>=2ms of
    // continuous rejections since the first rejection in the call, effective
    // floor capped at timeoutMs), so busy calls are bounded by the budget
    // rather than by a rejection count. Busy calls below are pinned by the
    // exception type + elapsed bound, not by counting submit attempts.

    @Test
    fun zeroSubmitSuccessSurfacesBusyWorkerAndTripsBreaker() {
        val executor = AlwaysRejectingExecutor()
        try {
            // T-F12: pin the trip threshold explicitly — do not depend on
            // the production default.
            val gate = PolicyRefreshGate(executor = executor, maxConsecutiveTimeouts = 3)
            val dropsBefore = gate.droppedRefreshCount.get()
            val t0 = System.nanoTime()
            val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("x") }, timeoutMs = 50)
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            // Busy-worker subtype, hence also plain contention for the cache.
            assertTrue(e is PolicyRefreshContentionException)
            // Budget respected (time-floor fail-fast, far under budget + slack).
            assertTrue(
                "elapsed must stay <= budget + generous slack, elapsed=$elapsedMs",
                elapsedMs <= 50 + 1_000,
            )
            // One drop accounted, zero queue, no worker occupied.
            assertEquals(dropsBefore + 1, gate.droppedRefreshCount.get())
            assertEquals(0, gate.queueDepth())
            assertEquals(0, gate.activeCount())
            assertEquals(1, gate.outstandingPermitsAvailable())
            // H1: the streak is accounted as a timeout for the breaker.
            assertEquals(1L, gate.consecutiveTimeouts.get())
            assertFalse(gate.isStuck())
            // Two more zero-success calls trip the breaker (MAX=3).
            assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("x") }, timeoutMs = 50)
            }
            assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("x") }, timeoutMs = 50)
            }
            assertEquals(3L, gate.consecutiveTimeouts.get())
            assertTrue("breaker must open after 3 busy-worker rejections", gate.isStuck())
            // Guard is back to available after every call.
            assertEquals(1, gate.outstandingPermitsAvailable())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun busyWorkerFailsFastNoStalledCallsAndRecoversDeterministically() {
        // Latch-held, interrupt-ignoring reader occupying the single worker
        // (models an uninterruptible Binder read).
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        // T-F11/O7: the H2 fail-fast is a real-time time floor now, not a
        // rejection count, so busy calls are pinned by exception type +
        // elapsed bound instead of a submit-attempt count.
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("t4-busy-worker")
        try {
            // T-F12: pin the trip threshold explicitly (match the sibling
            // test) — do not depend on the production default.
            val gate = PolicyRefreshGate(executor = executor, maxConsecutiveTimeouts = 3)
            val stuckReader: () -> Set<String>? = {
                started.countDown()
                // Interrupt-ignoring park: swallow InterruptedException, poll
                // the release latch. cancel(true) never frees the worker.
                while (!try {
                        release.await(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        false
                    }
                ) {
                    // Keep waiting (interrupts ignored).
                }
                setOf("stuck")
            }
            // Call #1: submit succeeds (worker free at entry), get() times
            // out at the 50ms budget — the reader-timeout arm.
            assertNull(gate.readBoundedOrThrow(stuckReader, timeoutMs = 50))
            assertTrue("worker must be occupied before follow-up calls", started.await(5, TimeUnit.SECONDS))
            assertEquals(1L, gate.consecutiveTimeouts.get())
            // Calls #2/#3: worker still busy -> every handoff attempt
            // rejects -> H2 time-floor fail-fast (>=2ms of continuous
            // rejections, capped at the 50ms budget). Pinned by the busy-
            // worker exception type + elapsed bound, not a rejection count.
            for (iteration in 2..3) {
                val iterStart = System.nanoTime()
                try {
                    gate.readBoundedOrThrow({ setOf("never") }, timeoutMs = 50)
                    fail("expected PolicyRefreshBusyWorkerException on iteration $iteration")
                } catch (e: PolicyRefreshBusyWorkerException) {
                    assertTrue(e is PolicyRefreshContentionException)
                    val iterElapsedMs = (System.nanoTime() - iterStart) / 1_000_000L
                    assertTrue(
                        "busy call must stay within budget + slack, elapsed=$iterElapsedMs",
                        iterElapsedMs <= 50 + 1_000,
                    )
                }
            }
            assertEquals(3L, gate.consecutiveTimeouts.get())
            assertTrue("breaker must be open after timeout + 2 busy-worker rejections", gate.isStuck())
            // While open: short-circuit throws plain Contention (not the
            // busy-worker subtype) without touching the executor, and fast.
            try {
                gate.readBoundedOrThrow({ setOf("short-circuit") }, timeoutMs = 50)
                fail("expected stuck short-circuit")
            } catch (e: PolicyRefreshContentionException) {
                assertFalse(
                    "stuck short-circuit is not a busy-worker rejection",
                    e is PolicyRefreshBusyWorkerException,
                )
            }
            // Recovery: invalidate() resets the breaker; the stuck worker is
            // released and drains; back-to-back submissions succeed
            // immediately (fresh worker, no handoff-race drops).
            gate.invalidate()
            assertFalse(gate.isStuck())
            release.countDown()
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (gate.activeCount() != 0 && System.nanoTime() < drainDeadline) {
                Thread.yield()
            }
            assertEquals(0, gate.activeCount())
            assertEquals(0L, gate.consecutiveTimeouts.get())
            assertEquals(setOf("ok-1"), gate.readBoundedOrThrow({ setOf("ok-1") }, timeoutMs = 2_000))
            assertEquals(setOf("ok-2"), gate.readBoundedOrThrow({ setOf("ok-2") }, timeoutMs = 2_000))
            assertFalse(gate.isStuck())
            assertEquals(0, gate.queueDepth())
        } finally {
            // Safety release for any assertion failure path.
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun thinGetTimeoutCountsAsContentionNotReaderTimeout() {
        // H3: a future.get() that waited below the floor (handoff ate the
        // budget) is busy-contention, never a reader-timeout for the breaker.
        // With a 1ms budget every outcome is thin by definition: the call
        // must throw plain Contention (or the budget-exhausted contention),
        // must NOT throw the busy-worker subtype, and consecutiveTimeouts
        // must stay untouched. No sleeps: the 1ms get times out immediately.
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("t4-thin-get")
        try {
            val gate = PolicyRefreshGate(executor = executor)
            val before = gate.consecutiveTimeouts.get()
            var thrown: PolicyRefreshContentionException? = null
            try {
                val sleeping = {
                    try {
                        Thread.sleep(30)
                    } catch (_: InterruptedException) {
                        // Best effort.
                    }
                    setOf("slow") as Set<String>?
                }
                gate.readBoundedOrThrow(sleeping, timeoutMs = 1)
            } catch (e: PolicyRefreshContentionException) {
                thrown = e
            }
            assertNotNull("thin budget must surface as contention", thrown)
            assertFalse(
                "thin-get is not a busy-worker rejection",
                thrown is PolicyRefreshBusyWorkerException,
            )
            assertEquals("thin-get must not count as a reader timeout", before, gate.consecutiveTimeouts.get())
            assertFalse(gate.isStuck())
        } finally {
            executor.shutdownNow()
        }
    }
}
