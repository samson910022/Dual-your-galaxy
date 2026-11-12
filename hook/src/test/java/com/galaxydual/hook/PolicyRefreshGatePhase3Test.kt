// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// Phase3: breaker self-lock rebuild + contention signalling + custom-executor /
// nowMs-throw / throttled-log / Error-Rejected-Interrupted branches.
class PolicyRefreshGatePhase3Test {

    private fun ignoringReader(sleepMs: Long, value: Set<String>? = setOf("stuck")): () -> Set<String>? =
        {
            val end = System.nanoTime() + sleepMs * 1_000_000L
            var remaining = sleepMs
            while (remaining > 0) {
                try {
                    Thread.sleep(remaining)
                    break
                } catch (_: InterruptedException) {
                    remaining = (end - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) break
                }
            }
            value
        }

    @Test
    fun stuckBusyBeyondThresholdRebuildsWorkerAndProbes() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-rebuild-phase3")
        try {
            val clock = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
            )
            // Trip the breaker with a single timeout (500ms stall vs 50ms budget).
            // Worker stays busy (ignoring reader runs 500ms).
            assertNull(gate.readBounded(ignoringReader(500), timeoutMs = 50))
            assertTrue(gate.isStuck())
            // Busy age beyond min(3*50=150, 200)=150 forces a rebuild.
            clock.addAndGet(200L)
            val before = gate.currentExecutorForTest()
            val r = gate.readBounded({ setOf("ok") }, timeoutMs = 2_000)
            assertEquals(setOf("ok"), r)
            assertEquals(1L, gate.workerRebuildCount())
            assertTrue(gate.currentExecutorForTest() !== before)
            assertFalse(gate.isStuck())
            // Fresh executor must be idle and shut down cleanly.
            gate.currentExecutorForTest().shutdownNow()
        } finally {
            try { executor.shutdownNow() } catch (_: Throwable) {}
        }
    }

    @Test
    fun stuckBusyBelowThresholdCountsWithoutRebuild() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-busy-below")
        try {
            val clock = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 100L,
                halfOpenMaxMs = 10_000L,
            )
            assertNull(gate.readBounded(ignoringReader(2_000), timeoutMs = 50))
            assertTrue(gate.isStuck())
            // Cooldown elapsed (150>=100) but below force threshold
            // min(3*100=300, 10000)=300: no rebuild, counted drop.
            clock.addAndGet(150L)
            assertNull(gate.readBounded({ setOf("never") }, timeoutMs = 50))
            assertEquals(0L, gate.workerRebuildCount())
            assertTrue(gate.busyStuckDropCount() >= 1L)
            assertTrue(gate.isStuck())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun customExecutorIsTreatedAsIdleAndRebuildSkipped() {
        val custom = Executors.newSingleThreadExecutor()
        try {
            val clock = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = custom,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
            )
            // Custom executor: no queue/active introspection.
            assertEquals(-1, gate.queueDepth())
            assertEquals(-1, gate.activeCount())
            // Trip, then advance beyond threshold: custom busy is always false,
            // so the probe proceeds without a rebuild.
            assertNull(gate.readBounded(ignoringReader(300), timeoutMs = 50))
            assertTrue(gate.isStuck())
            Thread.sleep(400)
            clock.addAndGet(500L)
            val r = gate.readBounded({ setOf("ok") }, timeoutMs = 2_000)
            assertEquals(setOf("ok"), r)
            assertEquals(0L, gate.workerRebuildCount())
            assertFalse(gate.isStuck())
        } finally {
            custom.shutdownNow()
        }
    }

    @Test
    fun nowMsThrowingShortCircuitsFailClosed() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-clock-throw")
        try {
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { throw IllegalStateException("clock dead") },
                maxConsecutiveTimeouts = 1,
            )
            // First timeout trips via the count arm (clock failure is caught).
            assertNull(gate.readBounded(ignoringReader(300), timeoutMs = 50))
            Thread.sleep(400)
            assertTrue(gate.isStuck())
            val stuckBefore = gate.stuckShortCircuits.get()
            // Stuck-open path calls nowMs() which throws -> short-circuit null.
            assertNull(gate.readBounded({ setOf("never") }, timeoutMs = 50))
            assertTrue(gate.stuckShortCircuits.get() > stuckBefore)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun throttledLogFiresAtMostOncePerWindow() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-throttle")
        try {
            val clock = AtomicLong(0L)
            var logs = 0
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                log = { logs++ },
            )
            // Occupy the guard with a slow leader, then cause 3 rapid drops.
            val leader = Thread {
                gate.readBounded(ignoringReader(500), timeoutMs = 50)
            }
            leader.start()
            Thread.sleep(50)
            repeat(3) {
                assertNull(gate.readBounded({ setOf("x") }, timeoutMs = 50))
            }
            leader.join()
            // DROP_LOG_THROTTLE_MS=5s with a frozen clock: only the first drop logs.
            assertEquals(1, logs)
            // Past the window: the next drop logs again.
            clock.addAndGet(6_000L)
            val leader2 = Thread {
                gate.readBounded(ignoringReader(500), timeoutMs = 50)
            }
            leader2.start()
            Thread.sleep(50)
            assertNull(gate.readBounded({ setOf("y") }, timeoutMs = 50))
            leader2.join()
            assertEquals(2, logs)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun readBoundedErrorRejectedInterruptedMapToNull() {
        // Error branch.
        var exec = Executors.newSingleThreadExecutor()
        try {
            assertNull(
                PolicyRefreshBudget.readBounded(
                    reader = { throw AssertionError("boom") },
                    timeoutMs = 500,
                    executor = exec,
                ),
            )
        } finally {
            exec.shutdownNow()
        }
        // Rejected branch (shutdown executor).
        exec = Executors.newSingleThreadExecutor()
        exec.shutdownNow()
        assertNull(
            PolicyRefreshBudget.readBounded(
                reader = { setOf("x") },
                timeoutMs = 500,
                executor = exec,
            ),
        )
        // Interrupted branch (reader throws InterruptedException).
        exec = Executors.newSingleThreadExecutor()
        try {
            assertNull(
                PolicyRefreshBudget.readBounded(
                    reader = { throw InterruptedException("cut") },
                    timeoutMs = 500,
                    executor = exec,
                ),
            )
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun orThrowSignalsContentionButNullOnIoFailure() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-orthrow")
        try {
            val gate = PolicyRefreshGate(executor = executor)
            // Guard contention throws.
            val leader = Thread {
                gate.readBoundedOrThrow(ignoringReader(500), timeoutMs = 50)
            }
            leader.start()
            Thread.sleep(50)
            try {
                gate.readBoundedOrThrow({ setOf("x") }, timeoutMs = 50)
                assertTrue("expected contention", false)
            } catch (e: PolicyRefreshContentionException) {
                // Expected.
            }
            leader.join()
            Thread.sleep(600)
            gate.invalidate()
            // IO failure (throwing reader) returns null, not contention.
            assertNull(gate.readBoundedOrThrow({ throw IllegalStateException("io") }, timeoutMs = 500))
            // Error reader also returns null (genuine failed read).
            assertNull(gate.readBoundedOrThrow({ throw AssertionError("err") }, timeoutMs = 500))
        } finally {
            executor.shutdownNow()
        }
    }
}
