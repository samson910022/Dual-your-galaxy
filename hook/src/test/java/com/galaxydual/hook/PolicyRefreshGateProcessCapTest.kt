// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// PR#60 r6 (Finding #3): the epoch-level MAX_WORKER_REBUILDS budget is a
// recovery throttle (invalidate() resets it); it is NOT a process-lifetime
// bound on abandoned (interrupt-ignoring) daemon workers. These tests pin
// the new hard per-gate process budget MAX_PROCESS_ABANDONED_WORKER_REBUILDS:
// it survives invalidate(), caps forced abandons across epochs, consumes
// nothing when the stuck worker already drained, and short-circuits fail-closed
// (no executor swap, no new threads) once exhausted.
class PolicyRefreshGateProcessCapTest {

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

    private fun newGate(executor: ExecutorService, clock: AtomicLong): PolicyRefreshGate =
        PolicyRefreshGate(
            executor = executor,
            nowMs = { clock.get() },
            maxConsecutiveTimeouts = 1,
            halfOpenInitialMs = 50L,
            halfOpenMaxMs = 200L,
        )

    // One invalidate-epoch that provably abandons its interrupt-ignoring
    // worker and rebuilds: trips the breaker (50ms budget vs a 600ms ignoring
    // reader), advances the frozen clock past cooldown + force threshold,
    // then performs a successful bounded read on a FRESH executor. Abandons
    // [expectedAbandons] workers in total. Returns the post-rebuild executor.
    private fun runAbandonEpoch(gate: PolicyRefreshGate, clock: AtomicLong, expectedAbandons: Long): ExecutorService {
        val before = gate.currentExecutorForTest()
        assertNull(gate.readBounded(ignoringReader(600), timeoutMs = 50))
        assertTrue(gate.isStuck())
        // age=200 must exceed rebuildThresholdMs(cooldown=50) = 150, otherwise
        // the busy arm throws ContentionException("busy") instead of rebuilding.
        clock.addAndGet(200L)
        assertEquals(setOf("ok"), gate.readBounded({ setOf("ok") }, timeoutMs = 2_000))
        // Epoch budget spent; process budget spent too (real abandon).
        assertEquals(1L, gate.workerRebuildCount())
        assertEquals(expectedAbandons, gate.abandonedWorkerRebuildCount())
        val after = gate.currentExecutorForTest()
        assertNotSame("a real abandon must install a fresh executor", before, after)
        gate.invalidate()
        // invalidate() resets the epoch budget only.
        assertEquals(0L, gate.workerRebuildCount())
        assertEquals(expectedAbandons, gate.abandonedWorkerRebuildCount())
        return gate.currentExecutorForTest()
    }

    // Exhausts the process budget (MAX_PROCESS_ABANDONED_WORKER_REBUILDS)
    // via 4 epochs. Returns the 4th (post-rebuild) executor identity.
    private fun exhaustProcessCap(gate: PolicyRefreshGate, clock: AtomicLong): ExecutorService {
        var fresh: ExecutorService = gate.currentExecutorForTest()
        for (i in 1L..PolicyRefreshGate.MAX_PROCESS_ABANDONED_WORKER_REBUILDS) {
            fresh = runAbandonEpoch(gate, clock, i)
        }
        assertEquals(PolicyRefreshGate.MAX_PROCESS_ABANDONED_WORKER_REBUILDS, gate.abandonedWorkerRebuildCount())
        assertEquals(0L, gate.workerRebuildCount())
        return fresh
    }

    @Test
    fun processLifetimeCapBoundsAbandonedWorkersAcrossInvalidations() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-process-cap-epochs")
        var freshFourth: ExecutorService? = null
        try {
            val clock = AtomicLong(0L)
            val gate = newGate(executor, clock)
            val fourth = exhaustProcessCap(gate, clock)
            freshFourth = fourth
            // Fifth epoch: trip again; the 4th executor's worker stays busy.
            assertNull(gate.readBounded(ignoringReader(600), timeoutMs = 50))
            assertTrue(gate.isStuck())
            clock.addAndGet(200L)
            val busyStuckDropsBefore = gate.busyStuckDropCount()
            val denialsBefore = gate.processCapDenials.get()
            val dropsBefore = gate.droppedRefreshCount.get()
            val probesBefore = gate.halfOpenProbes.get()
            val seqBefore = gate.consecutiveTimeouts.get()
            val halfOpenFailsBefore = gate.halfOpenFailureCount()
            val startNanos = System.nanoTime()
            val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("never") }, timeoutMs = 50)
            }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            assertEquals("rebuild-skipped", e.message)
            // H7-style renewal pins for the deny arm (same shared !rebuilt path).
            assertEquals(seqBefore + 1L, gate.consecutiveTimeouts.get())
            assertEquals(halfOpenFailsBefore + 1L, gate.halfOpenFailureCount())
            assertEquals(clock.get(), gate.lastStuckOpenMs())
            assertTrue("backoff must renew after a denied rebuild", gate.halfOpenCooldownMs() >= 100L)
            assertEquals(probesBefore, gate.halfOpenProbes.get())
            assertEquals(dropsBefore + 1L, gate.droppedRefreshCount.get())
            // The cap refusal is fail-closed: no executor swap, no new worker,
            // the process budget stays exhausted-but-unchanged.
            assertEquals(PolicyRefreshGate.MAX_PROCESS_ABANDONED_WORKER_REBUILDS, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.workerRebuildCount())
            assertSame(fourth, gate.currentExecutorForTest())
            assertEquals(busyStuckDropsBefore + 1L, gate.busyStuckDropCount())
            assertEquals(denialsBefore + 1L, gate.processCapDenials.get())
            assertTrue(gate.isStuck())
            // The refusal short-circuits before touching the executor. The
            // 200ms bound is a coarse loaded-CI guard (the deny path is
            // counter-only, in practice microseconds); it deliberately is NOT
            // tightened to the 50ms budget — that would reintroduce flake
            // risk. The denial is proven by the exception type/message, the
            // denials counter delta, and the executor identity pin. Note the
            // +200ms clock step must exceed rebuildThresholdMs(cooldown=50)
            // = 150, otherwise this fails with an opaque type mismatch.
            assertTrue("cap denial must not wait on the busy worker (took ${elapsedMs}ms)", elapsedMs <= 200L)
        } finally {
            try { freshFourth?.shutdownNow() } catch (_: Throwable) {}
            try { executor.shutdownNow() } catch (_: Throwable) {}
        }
    }

    // Drained 路徑：drain 會走 outer isExecutorBusy()==false 直接探針（或 probe
    // 內部的 activeCount<=0 → return true 分支，視窗為 sub-microsecond，無法以
    // JVM 測試確定性釘住——刻意不為其硬造 seam）。此測試釘住「drained 消耗零
    // 預算」的行為層屬性；in-lock reclaimable 臂不在此釘。
    @Test
    fun processCapNotConsumedWhenWorkerAlreadyDrained() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-process-cap-drained")
        try {
            val clock = AtomicLong(0L)
            val gate = newGate(executor, clock)
            // Short reader: the worker drains shortly after the 50ms budget expires.
            assertNull(gate.readBounded(ignoringReader(80), timeoutMs = 50))
            assertTrue(gate.isStuck())
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (gate.activeCount() != 0 && System.nanoTime() < drainDeadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L) // ~1ms; 避免熱輪詢
            }
            assertEquals(0, gate.activeCount())
            clock.addAndGet(200L)
            assertEquals(setOf("ok"), gate.readBoundedOrThrow({ setOf("ok") }, timeoutMs = 2_000))
            // Drained worker: the probe lands on the SAME executor, the breaker
            // closes via onProbeSuccess, and NO budget is consumed.
            assertSame(executor, gate.currentExecutorForTest())
            assertEquals(1L, gate.halfOpenProbes.get())
            assertTrue(!gate.isStuck())
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(0L, gate.processCapDenials.get())
            gate.invalidate()
            assertEquals(0L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.workerRebuildCount())
        } finally {
            try { executor.shutdownNow() } catch (_: Throwable) {}
        }
    }

    // 兩個 gate 各自持有獨立的 process budget（KDoc 中的 policyRefreshGate +
    // capabilityGate 雙例）：一個耗盡不得影響另一個的正常重建能力。
    @Test
    fun processCapIsPerGateInstance_independentBudgets() {
        val executorA = PolicyRefreshBudget.newPolicyRefreshExecutor("test-cap-gate-a")
        val executorB = PolicyRefreshBudget.newPolicyRefreshExecutor("test-cap-gate-b")
        var freshA: ExecutorService? = null
        var freshB: ExecutorService? = null
        try {
            val clockA = AtomicLong(0L)
            val clockB = AtomicLong(0L)
            val gateA = newGate(executorA, clockA)
            val gateB = newGate(executorB, clockB)
            freshA = exhaustProcessCap(gateA, clockA)
            assertEquals(PolicyRefreshGate.MAX_PROCESS_ABANDONED_WORKER_REBUILDS, gateA.abandonedWorkerRebuildCount())
            // Gate B 完全未受影響：可直接讀、卡死後依然能正常升級重建。
            assertEquals(setOf("ok"), gateB.readBoundedOrThrow({ setOf("ok") }, timeoutMs = 2_000))
            assertEquals(0L, gateB.abandonedWorkerRebuildCount())
            freshB = runAbandonEpoch(gateB, clockB, 1L)
            assertEquals(1L, gateB.abandonedWorkerRebuildCount())
            // Epoch counter resets per invalidate(); process budget survives.
            assertEquals(0L, gateB.workerRebuildCount())
        } finally {
            try { freshA?.shutdownNow() } catch (_: Throwable) {}
            try { freshB?.shutdownNow() } catch (_: Throwable) {}
            try { executorA.shutdownNow() } catch (_: Throwable) {}
            try { executorB.shutdownNow() } catch (_: Throwable) {}
        }
    }

    @Test
    fun invalidateResetsEpochBudgetButNotProcessBudget() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-process-cap-invalidate")
        var fresh: ExecutorService? = null
        try {
            val clock = AtomicLong(0L)
            val gate = newGate(executor, clock)
            assertNull(gate.readBounded(ignoringReader(600), timeoutMs = 50))
            assertTrue(gate.isStuck())
            clock.addAndGet(200L)
            assertEquals(setOf("ok"), gate.readBounded({ setOf("ok") }, timeoutMs = 2_000))
            assertEquals(1L, gate.workerRebuildCount())
            assertEquals(1L, gate.abandonedWorkerRebuildCount())
            fresh = gate.currentExecutorForTest()
            gate.invalidate()
            assertSame(fresh, gate.currentExecutorForTest())
            // Epoch budget reset; process budget untouched.
            assertEquals(0L, gate.workerRebuildCount())
            assertEquals(1L, gate.abandonedWorkerRebuildCount())
            assertEquals(0L, gate.processCapDenials.get())
        } finally {
            try { fresh?.shutdownNow() } catch (_: Throwable) {}
            try { executor.shutdownNow() } catch (_: Throwable) {}
        }
    }

    @Test
    fun beyondProcessCapEveryEpochShortCircuitsWithoutNewThreads() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-process-cap-repeat")
        var freshFourth: ExecutorService? = null
        try {
            val clock = AtomicLong(0L)
            val gate = newGate(executor, clock)
            val fourth = exhaustProcessCap(gate, clock)
            freshFourth = fourth
            // Three more invalidate -> trip cycles once the process budget is spent.
            // Cycles 2-3 trip via the zero-success busy-worker rejection arm
            // (worker still busy), not via a real reader timeout — same budget path.
            repeat(3) {
                gate.invalidate()
                // 2s ignoring reader keeps the 4th executor's single worker
                // busy across the whole cycle (epoch trip + denied rebuild).
                assertNull(gate.readBounded(ignoringReader(2_000), timeoutMs = 50))
                assertTrue(gate.isStuck())
                clock.addAndGet(200L)
                val dropsBefore = gate.busyStuckDropCount()
                val shortCircuitsBefore = gate.stuckShortCircuits.get()
                val denialsBefore = gate.processCapDenials.get()
                val refreshDropsBefore = gate.droppedRefreshCount.get()
                val probesBefore = gate.halfOpenProbes.get()
                val seqBefore = gate.consecutiveTimeouts.get()
                val halfOpenFailsBefore = gate.halfOpenFailureCount()
                val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                    gate.readBoundedOrThrow({ setOf("never") }, timeoutMs = 50)
                }
                assertEquals("rebuild-skipped", e.message)
                assertEquals(seqBefore + 1L, gate.consecutiveTimeouts.get())
                assertEquals(halfOpenFailsBefore + 1L, gate.halfOpenFailureCount())
                assertEquals(clock.get(), gate.lastStuckOpenMs())
                assertTrue("backoff must renew after a denied rebuild", gate.halfOpenCooldownMs() >= 100L)
                assertEquals(probesBefore, gate.halfOpenProbes.get())
                assertEquals(refreshDropsBefore + 1L, gate.droppedRefreshCount.get())
                // No new executor, no new abandon, no epoch increment either.
                assertEquals(PolicyRefreshGate.MAX_PROCESS_ABANDONED_WORKER_REBUILDS, gate.abandonedWorkerRebuildCount())
                assertEquals(0L, gate.workerRebuildCount())
                assertSame("no new executor may be created beyond the process cap", fourth, gate.currentExecutorForTest())
                assertEquals(dropsBefore + 1L, gate.busyStuckDropCount())
                assertEquals(shortCircuitsBefore + 1L, gate.stuckShortCircuits.get())
                assertEquals(denialsBefore + 1L, gate.processCapDenials.get())
                assertTrue(gate.isStuck())
            }
        } finally {
            try { freshFourth?.shutdownNow() } catch (_: Throwable) {}
            try { executor.shutdownNow() } catch (_: Throwable) {}
        }
    }
}
