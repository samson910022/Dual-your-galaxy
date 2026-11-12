// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

// K4 P2#6: bounded refresh (zero-queue + at-most-one guard + stuck breaker).
//
// Regression: newSingleThreadExecutor has an unbounded LinkedBlockingQueue;
// cancel(true) cannot stop an interrupt-ignoring Binder read nor remove a
// queued task, so repeated 5s-cooldown misses behind a stuck worker grew the
// queue without bound. This pins: queue/outstanding bounded (<=1, no growth),
// budget respected, post-invalidate recovery.
class PolicyRefreshGateTest {

    // Reader that ignores interruption (simulates uninterruptible Binder
    // read): swallows InterruptedException and keeps sleeping to the full
    // duration. cancel(true) therefore never frees the worker early.
    private fun ignoringReader(sleepMs: Long, value: Set<String>? = setOf("stuck")): () -> Set<String>? =
        {
            val end = System.nanoTime() + sleepMs * 1_000_000L
            var remaining = sleepMs
            while (remaining > 0) {
                try {
                    Thread.sleep(remaining)
                    break
                } catch (_: InterruptedException) {
                    // Ignore: keep sleeping (uninterruptible).
                    remaining = (end - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) break
                }
            }
            value
        }

    @Test
    fun zeroQueueAndGuardBoundOutstanding() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-gate-1")
        try {
            val gate = PolicyRefreshGate(executor = executor)
            // Occupy the single worker with an interrupt-ignoring 2s read;
            // caller budget is 50ms so this times out but the worker stays busy.
            val start0 = System.nanoTime() / 1_000_000L
            val r0 = gate.readBounded(ignoringReader(2_000), timeoutMs = 50)
            val elapsed0 = System.nanoTime() / 1_000_000L - start0
            assertNull(r0)
            assertTrue("budget respected elapsed=$elapsed0", elapsed0 < 1_500)
            // Worker still busy (ignoring reader runs 2s). Immediate second
            // read must NOT queue: guard contention or SynchronousQueue
            // rejection short-circuits to null, fast, with a drop counted.
            val dropsBefore = gate.droppedRefreshCount.get()
            val start1 = System.nanoTime() / 1_000_000L
            val r1 = gate.readBounded({ setOf("fast") }, timeoutMs = 50)
            val elapsed1 = System.nanoTime() / 1_000_000L - start1
            assertNull("second read while worker stuck must drop to null", r1)
            assertTrue("drop must be fast elapsed=$elapsed1", elapsed1 < 1_500)
            assertTrue(
                "droppedRefreshCount must grow (before=$dropsBefore after=${gate.droppedRefreshCount.get()})",
                gate.droppedRefreshCount.get() > dropsBefore,
            )
            // Zero queue: SynchronousQueue never holds tasks.
            assertEquals(0, gate.queueDepth())
            assertTrue("active<=1 active=${gate.activeCount()}", gate.activeCount() <= 1)
            // Guard released after each call (permits back to 1).
            assertEquals(1, gate.outstandingPermitsAvailable())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun repeatedCooldownMissesDoNotGrowQueue() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-gate-2")
        try {
            val gate = PolicyRefreshGate(executor = executor)
            val now = AtomicLong(0L)
            val cache = EffectivePolicyCache(nowMs = { now.get() })
            val seed = setOf("com.seed.app")
            // First miss: ignoring reader stalls 2s, gate times out at 50ms,
            // cache falls back to seed (fail-closed observe-only).
            var r = cache.get(seed, nonEmptyPolicyGateOpen = true) {
                gate.readBounded(ignoringReader(2_000), timeoutMs = 50)
            }
            assertEquals(seed, r)
            assertEquals(0, gate.queueDepth())
            val dropsAfterFirst = gate.droppedRefreshCount.get() +
                gate.stuckShortCircuits.get() + gate.consecutiveTimeouts.get()
            // Repeated 5s-cooldown misses: advance past the 5s failure
            // cooldown so each iteration is a real miss (reader invoked).
            // Worker is still stuck (2s ignoring read from first miss may have
            // drained; use a fresh long stall each time by keeping the worker
            // busy: submit a background ignoring task directly).
            // Simpler deterministic pin: occupy worker via a raw submit, then
            // issue cache misses that must drop without queueing.
            repeat(5) {
                now.addAndGet(6_000L)
                // Invalidate the breaker between iterations so every miss
                // reaches the guard/executor (breaker would otherwise
                // short-circuit after 3 timeouts — covered separately).
                gate.invalidate()
                r = cache.get(seed, nonEmptyPolicyGateOpen = true) {
                    gate.readBounded(ignoringReader(2_000), timeoutMs = 50)
                }
                assertEquals("iteration $it must fall back to seed", seed, r)
                assertEquals("queue must stay zero at iteration $it", 0, gate.queueDepth())
                assertTrue(
                    "active<=1 at iteration $it active=${gate.activeCount()}",
                    gate.activeCount() <= 1,
                )
                assertEquals(
                    "guard released at iteration $it",
                    1,
                    gate.outstandingPermitsAvailable(),
                )
            }
            // Drops/timeouts recorded, but nothing queued.
            assertTrue(
                "breaker/drops recorded (after=$dropsAfterFirst timeouts=${gate.consecutiveTimeouts.get()} " +
                    "drops=${gate.droppedRefreshCount.get()} stuck=${gate.stuckShortCircuits.get()})",
                gate.consecutiveTimeouts.get() + gate.droppedRefreshCount.get() +
                    gate.stuckShortCircuits.get() > dropsAfterFirst,
            )
            assertEquals(0, gate.queueDepth())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun stuckBreakerShortCircuitsThenInvalidateRecovers() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-gate-3")
        try {
            // Small breaker (3 timeouts) with a controllable clock for the
            // time-since-last-success arm (far future forces age arm off;
            // count arm drives the trip).
            val gate = PolicyRefreshGate(
                executor = executor,
                maxConsecutiveTimeouts = 3,
            )
            // 3 consecutive timeouts (500ms stall vs 50ms budget).
            // Wait for the stuck worker to drain between submits (else the
            // guard/rejection would drop instead of timing out; both map to
            // null but only a timeout increments the breaker streak).
            repeat(3) {
                val r = gate.readBounded(ignoringReader(500), timeoutMs = 50)
                assertNull(r)
                Thread.sleep(600)
            }
            assertTrue("breaker must be open after 3 consecutive timeouts", gate.isStuck())
            assertTrue(gate.stuckShortCircuits.get() >= 1)
            // While open: short-circuit without submit (fast null, no budget wait).
            val start = System.nanoTime() / 1_000_000L
            val shorted = gate.readBounded({ setOf("never-submitted") }, timeoutMs = 50)
            val elapsed = System.nanoTime() / 1_000_000L - start
            assertNull(shorted)
            assertTrue("short-circuit must be fast elapsed=$elapsed", elapsed < 1_500)
            assertEquals("zero queue while stuck", 0, gate.queueDepth())
            // Recovery: invalidate resets the breaker; a good reader succeeds.
            gate.invalidate()
            assertFalse(gate.isStuck())
            // Ensure the stale worker drained (600ms > 500ms stall).
            Thread.sleep(600)
            val good = gate.readBounded({ setOf("ok") }, timeoutMs = 2_000)
            assertEquals(setOf("ok"), good)
            assertFalse(gate.isStuck())
            assertEquals(0L, gate.consecutiveTimeouts.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun factoryIsZeroQueueSingleThread() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-gate-factory")
        try {
            assertEquals(1, executor.maximumPoolSize)
            assertTrue(executor.queue is java.util.concurrent.SynchronousQueue<*>)
            assertTrue(executor.threadFactory.newThread({}).isDaemon)
        } finally {
            executor.shutdownNow()
        }
    }
}
