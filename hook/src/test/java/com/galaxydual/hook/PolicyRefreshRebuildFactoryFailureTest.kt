// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// PR#60 r2 (K3/T-P2-11): fresh-construction-before-shutdown in
// tryRebuildStuckWorker. A factory that throws (OOM / thread-limit) must
// leave the gate's executor reference pointing at the untouched old pool
// (construct-before-swap; shutdown never attempted on the failure path),
// and a dead (shutdown) pool with nothing active must be freely swapped
// for a healthy one WITHOUT consuming the epoch (workerRebuilds) or
// process (abandonedWorkerRebuilds) budgets.
class PolicyRefreshRebuildFactoryFailureTest {

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

    // Frozen-clock companion to the interrupt-ignoring readers (mirror of
    // the PolicyRefreshGatePhase3Test idiom): the rebuild arm re-probes
    // activeCount() on the REAL scheduler at read time, so a loaded CI box
    // that stalls the worker past the budget could flip activeCount() to 0
    // before the frozen clock is advanced. Park (0.5ms) until the worker has
    // actually started, with a 5s nanos deadline; assert-fail loudly on
    // deadline instead of flaking downstream.
    private fun awaitWorkerBusy(gate: PolicyRefreshGate, deadlineSeconds: Long = 5) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(deadlineSeconds)
        while (gate.activeCount() < 1) {
            if (System.nanoTime() >= deadline) {
                fail("worker did not become busy within ${deadlineSeconds}s (activeCount stayed 0)")
            }
            java.util.concurrent.locks.LockSupport.parkNanos(500_000L)
        }
    }

    // Factory throws once ("thread-limit"), then delegates to the production
    // factory. The forced rebuild after a stuck-busy trip must be REFUSED
    // with the gate's executor reference untouched (old, still-alive pool),
    // no budget consumed, and no permanent lockout: the very next eligible
    // query (clock advanced past the renewed backoff + force threshold)
    // rebuilds successfully and the half-open probe closes the breaker.
    @Test
    fun factoryFailureLeavesExecutorUntouchedBudgetsUnspentAndRecoversOnRetry() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-factory-failure")
        try {
            val clock = AtomicLong(0L)
            val factoryCalls = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
                executorFactory = { name ->
                    if (factoryCalls.incrementAndGet() == 1L) {
                        throw RuntimeException("thread-limit")
                    }
                    PolicyRefreshBudget.newPolicyRefreshExecutor(name)
                },
            )
            // Trip the breaker; the (original) worker stays busy.
            assertNull(gate.readBounded(ignoringReader(2_000), timeoutMs = 50))
            assertTrue(gate.isStuck())
            awaitWorkerBusy(gate)
            // age=200 must exceed rebuildThresholdMs(cooldown=50) = 150.
            clock.addAndGet(200L)
            val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("never") }, timeoutMs = 50)
            }
            assertEquals("rebuild-skipped", e.message)
            // Failing attempt: the factory was consulted exactly once, the
            // executor was NOT replaced and NOT shut down
            // (construct-before-shutdown), no budget was consumed.
            assertEquals(1L, factoryCalls.get())
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertSame("a failed factory must leave the old pool installed", executor, gate.currentExecutorForTest())
            assertFalse("a failed factory must not shut the old pool down", executor.isShutdown)
            assertTrue(gate.isStuck())
            // Factory recovers. The H7 renewal after the skipped rebuild set
            // halfOpenFailures=1 (cooldown 50 -> 100); age must clear both the
            // renewed cooldown (250 >= 100) and the renewed force threshold
            // rebuildThresholdMs(100) = min(3*100, 200) = 200.
            clock.addAndGet(250L)
            assertEquals(setOf("ok"), gate.readBoundedOrThrow({ setOf("ok") }, timeoutMs = 2_000))
            assertEquals(2L, factoryCalls.get())
            // Exactly one rebuild, accounted once, by the successful attempt.
            assertEquals(1L, gate.workerRebuildCount())
            assertEquals(1L, gate.abandonedWorkerRebuildCount())
            assertNotSame(executor, gate.currentExecutorForTest())
            // The gate is not locked permanently: the half-open probe on the
            // fresh pool closed the breaker.
            assertFalse(gate.isStuck())
            gate.currentExecutorForTest().shutdownNow()
        } finally {
            try { executor.shutdownNow() } catch (_: Throwable) {}
        }
    }

    // Busy arm (activeCount > 0): the factory returns an ALREADY-SHUTDOWN
    // fresh pool on the rebuild attempt. The rebuild must be refused BEFORE
    // cur.shutdownNow() and before the swap: cur stays installed and alive,
    // no budget is consumed (workerRebuilds / abandonedWorkerRebuilds /
    // processCapDenials all stay 0), the factory-returned pool is shut down
    // best-effort, and the refusal is NOT a real abandon (H7 onProbeFail
    // renewal at the call site, like the factory-failure arm). A later
    // eligible probe rebuilds successfully — exactly one rebuild, accounted
    // once, by the genuine retry.
    @Test
    fun factoryReturningAlreadyShutdownPoolIsRefusedOnBusyArm() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-busy-shutdown-fresh")
        val refusedFresh = PolicyRefreshBudget.newPolicyRefreshExecutor("test-factory-shutdown-fresh-busy")
        refusedFresh.shutdownNow()
        assertTrue(refusedFresh.isShutdown)
        try {
            val clock = AtomicLong(0L)
            val factoryCalls = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
                executorFactory = { name ->
                    if (factoryCalls.incrementAndGet() == 1L) {
                        refusedFresh
                    } else {
                        PolicyRefreshBudget.newPolicyRefreshExecutor(name)
                    }
                },
            )
            // Trip the breaker; the (original) worker stays busy.
            assertNull(gate.readBounded(ignoringReader(2_000), timeoutMs = 50))
            assertTrue(gate.isStuck())
            awaitWorkerBusy(gate)
            // age=200 must exceed rebuildThresholdMs(cooldown=50) = 150.
            clock.addAndGet(200L)
            val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("never") }, timeoutMs = 50)
            }
            assertEquals("rebuild-skipped", e.message)
            // Refusal: factory consulted exactly once, cur NEVER shut down or
            // swapped, no budgets consumed.
            assertEquals(1L, factoryCalls.get())
            assertSame("a factory-returned shutdown pool must never be installed", executor, gate.currentExecutorForTest())
            assertFalse("cur must never be shut down on the refusal path", executor.isShutdown)
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.processCapDenials.get())
            assertTrue(gate.isStuck())
            // Factory recovers. The H7 renewal after the skipped rebuild set
            // halfOpenFailures=1 (cooldown 50 -> 100); age must clear both the
            // renewed cooldown (250 >= 100) and the renewed force threshold
            // rebuildThresholdMs(100) = min(3*100, 200) = 200.
            clock.addAndGet(250L)
            assertEquals(setOf("ok"), gate.readBoundedOrThrow({ setOf("ok") }, timeoutMs = 2_000))
            assertEquals(2L, factoryCalls.get())
            // Exactly one rebuild, accounted once, by the successful attempt.
            assertEquals(1L, gate.workerRebuildCount())
            assertEquals(1L, gate.abandonedWorkerRebuildCount())
            assertNotSame(executor, gate.currentExecutorForTest())
            assertFalse(gate.isStuck())
            gate.currentExecutorForTest().shutdownNow()
        } finally {
            try { executor.shutdownNow() } catch (_: Throwable) {}
            try { refusedFresh.shutdownNow() } catch (_: Throwable) {}
        }
    }

    // Dead pool wired into the gate (pre-shutdown, activeCount == 0); the
    // factory throws on its first call. The eligible half-open probe must
    // refuse with "rebuild-skipped", the dead pool must stay installed, no
    // budget may be consumed (factoryCalls == 1), and a later eligible probe
    // (clock advanced past the renewed force threshold) must rebuild
    // successfully — the half-open probe closes the breaker, the executor is
    // a NEW pool, and the dead-pool reclaim consumes nothing. Mirrors the
    // busy-arm factory-failure test structure.
    @Test
    fun factoryFailureOnDeadPoolLeavesDeadExecutorRetryableBudgetsUnspent() {
        val dead = PolicyRefreshBudget.newPolicyRefreshExecutor("test-dead-pool-factory-failure")
        dead.shutdownNow()
        assertEquals(0, dead.activeCount)
        assertTrue(dead.isShutdown)
        try {
            val clock = AtomicLong(0L)
            val factoryCalls = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = dead,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
                executorFactory = { name ->
                    if (factoryCalls.incrementAndGet() == 1L) {
                        throw RuntimeException("thread-limit")
                    }
                    PolicyRefreshBudget.newPolicyRefreshExecutor(name)
                },
            )
            // Trip the breaker with the dead pool: every submit is rejected
            // across the retry window -> busy-worker rejection -> streak=1
            // trips the breaker (maxConsecutiveTimeouts=1).
            assertNull(gate.readBounded({ setOf("never") }, timeoutMs = 50))
            assertTrue(gate.isStuck())
            // age=200 must clear rebuildThresholdMs(cooldown=50) = 150; the
            // dead pool reports busy (never healthy-idle), so the query
            // routes to the rebuild arm, whose factory throws on call #1.
            clock.addAndGet(200L)
            val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("never") }, timeoutMs = 50)
            }
            assertEquals("rebuild-skipped", e.message)
            // Failing attempt: factory consulted exactly once, the dead pool
            // is untouched and still installed, no budget consumed.
            assertEquals(1L, factoryCalls.get())
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.processCapDenials.get())
            assertSame("a failed factory must leave the dead pool installed", dead, gate.currentExecutorForTest())
            assertTrue(gate.isStuck())
            // Factory recovers. The H7 renewal after the skipped rebuild set
            // halfOpenFailures=1 (cooldown 50 -> 100); age must clear both the
            // renewed cooldown (250 >= 100) and the renewed force threshold
            // rebuildThresholdMs(100) = min(3*100, 200) = 200.
            clock.addAndGet(250L)
            assertEquals(setOf("ok"), gate.readBoundedOrThrow({ setOf("ok") }, timeoutMs = 2_000))
            assertEquals(2L, factoryCalls.get())
            // Dead-pool reclaim: new pool installed, ZERO budgets consumed.
            assertNotSame(dead, gate.currentExecutorForTest())
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.processCapDenials.get())
            // The half-open probe on the fresh pool closed the breaker.
            assertFalse(gate.isStuck())
            gate.currentExecutorForTest().shutdownNow()
        } finally {
            try { dead.shutdownNow() } catch (_: Throwable) {}
        }
    }

    // The factory returns an ALREADY-SHUTDOWN fresh pool on the dead-pool
    // path (first call): the reclaim must be refused (never install a dead
    // pool), the original dead pool must stay installed, no budget may be
    // consumed, and a later eligible probe must succeed with a fresh pool.
    @Test
    fun factoryReturningAlreadyShutdownPoolIsRefusedOnDeadPoolPathAndRecoversOnRetry() {
        val dead = PolicyRefreshBudget.newPolicyRefreshExecutor("test-dead-pool-shutdown-fresh")
        dead.shutdownNow()
        assertEquals(0, dead.activeCount)
        assertTrue(dead.isShutdown)
        val refusedFresh = PolicyRefreshBudget.newPolicyRefreshExecutor("test-factory-shutdown-fresh")
        refusedFresh.shutdownNow()
        assertTrue(refusedFresh.isShutdown)
        try {
            val clock = AtomicLong(0L)
            val factoryCalls = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = dead,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
                executorFactory = { name ->
                    if (factoryCalls.incrementAndGet() == 1L) {
                        refusedFresh
                    } else {
                        PolicyRefreshBudget.newPolicyRefreshExecutor(name)
                    }
                },
            )
            assertNull(gate.readBounded({ setOf("never") }, timeoutMs = 50))
            assertTrue(gate.isStuck())
            // Eligible half-open probe (age=200 >= rebuildThresholdMs(50)=150):
            // factory returns an already-shutdown pool -> reclaim refused.
            clock.addAndGet(200L)
            val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("never") }, timeoutMs = 50)
            }
            assertEquals("rebuild-skipped", e.message)
            assertEquals(1L, factoryCalls.get())
            assertSame("a factory-returned shutdown pool must never be installed", dead, gate.currentExecutorForTest())
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.processCapDenials.get())
            // H7 renewal after the skipped reclaim: cooldown 50 -> 100,
            // force threshold rebuildThresholdMs(100) = 200; advance past both.
            clock.addAndGet(250L)
            assertEquals(setOf("ok"), gate.readBoundedOrThrow({ setOf("ok") }, timeoutMs = 2_000))
            assertEquals(2L, factoryCalls.get())
            assertNotSame(dead, gate.currentExecutorForTest())
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.processCapDenials.get())
            assertFalse(gate.isStuck())
            gate.currentExecutorForTest().shutdownNow()
        } finally {
            try { dead.shutdownNow() } catch (_: Throwable) {}
            try { refusedFresh.shutdownNow() } catch (_: Throwable) {}
        }
    }

    // A pre-shutdown (dead) pool with activeCount == 0 reaches the
    // drained-but-shutdown arm: the gate swaps in a fresh pool WITHOUT
    // consuming workerRebuilds / abandonedWorkerRebuilds / processCapDenials,
    // and the half-open probe on the fresh pool closes the breaker.
    @Test
    fun deadDrainedPoolIsSwappedWithoutConsumingRebuildBudgets() {
        val dead = PolicyRefreshBudget.newPolicyRefreshExecutor("test-dead-pool")
        dead.shutdownNow()
        assertEquals(0, dead.activeCount)
        assertTrue(dead.isShutdown)
        try {
            val clock = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = dead,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
            )
            // Trip the breaker with the dead pool: every submit is rejected
            // across the retry window -> busy-worker rejection -> streak=1
            // trips the breaker (maxConsecutiveTimeouts=1).
            assertNull(gate.readBounded({ setOf("never") }, timeoutMs = 50))
            assertTrue(gate.isStuck())
            // Cooldown elapses and age clears the rebuild threshold; the
            // dead pool reports busy (never healthy-idle), so the query
            // routes to the rebuild arm, whose dead-pool path swaps freely.
            clock.addAndGet(200L)
            val r = gate.readBoundedOrThrow({ setOf("ok") }, timeoutMs = 2_000)
            assertEquals(setOf("ok"), r)
            assertNotSame("the dead pool must be swapped for a fresh one", dead, gate.currentExecutorForTest())
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.processCapDenials.get())
            assertEquals(1L, gate.halfOpenProbes.get())
            assertFalse(gate.isStuck())
            gate.currentExecutorForTest().shutdownNow()
        } finally {
            try { dead.shutdownNow() } catch (_: Throwable) {}
        }
    }
}
