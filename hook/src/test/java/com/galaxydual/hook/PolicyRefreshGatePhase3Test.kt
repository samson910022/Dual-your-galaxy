// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    // Frozen-clock companion to the interrupt-ignoring readers (mirror of
    // the PolicyRefreshGateProcessCapTest idiom): the rebuild arm re-probes
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
            awaitWorkerBusy(gate)
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
            awaitWorkerBusy(gate)
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
            // so the probe proceeds without a rebuild. Deterministic handshake:
            // wait for the interrupt-ignoring reader to actually drain (not a
            // wall-clock guess) before probing the custom executor.
            val readerDone = CountDownLatch(1)
            assertNull(gate.readBounded({ val v = ignoringReader(300)(); readerDone.countDown(); v }, timeoutMs = 50))
            assertTrue(gate.isStuck())
            assertTrue("stuck reader must drain", readerDone.await(5, TimeUnit.SECONDS))
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
            val readerDone = CountDownLatch(1)
            assertNull(gate.readBounded({ val v = ignoringReader(300)(); readerDone.countDown(); v }, timeoutMs = 50))
            assertTrue("stuck reader must drain", readerDone.await(5, TimeUnit.SECONDS))
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
        val executor = AlwaysRejectingExecutor()
        try {
            val clock = AtomicLong(0L)
            var logs = 0
            // High trip threshold so only the throttle window (not the
            // stuck-breaker) governs emission; frozen clock makes the window
            // deterministic.
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                log = { logs++ },
                maxConsecutiveTimeouts = 100,
            )
            // Three rapid busy-worker drops within the 5s window: only the
            // first logs.
            repeat(3) {
                assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                    gate.readBoundedOrThrow({ setOf("x") }, timeoutMs = 50)
                }
            }
            assertEquals(1, logs)
            assertEquals(3L, gate.consecutiveTimeouts.get())
            // Past the window: the next drop logs again.
            clock.addAndGet(6_000L)
            repeat(2) {
                assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                    gate.readBoundedOrThrow({ setOf("y") }, timeoutMs = 50)
                }
            }
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
    fun zeroTimeoutNeverSubmits() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-zero-timeout")
        try {
            val gate = PolicyRefreshGate(executor = executor)
            var calls = 0
            assertNull(gate.readBounded(reader = { calls++; setOf("x") }, timeoutMs = 0))
            assertEquals(0, calls)
            assertNull(gate.readBounded(reader = { calls++; setOf("x") }, timeoutMs = -1))
            assertEquals(0, calls)
            assertNull(gate.readBoundedOrThrow(reader = { calls++; setOf("x") }, timeoutMs = 0))
            assertEquals(0, calls)
            assertNull(gate.readBoundedOrThrow(reader = { calls++; setOf("x") }, timeoutMs = -1))
            assertEquals(0, calls)
            // Object-helper parity: zero/negative budget never submits either.
            assertNull(
                PolicyRefreshBudget.readBounded(
                    reader = { calls++; setOf("x") },
                    timeoutMs = 0,
                    executor = executor,
                ),
            )
            assertEquals(0, calls)
            assertNull(
                PolicyRefreshBudget.readBounded(
                    reader = { calls++; setOf("x") },
                    timeoutMs = -1,
                    executor = executor,
                ),
            )
            assertEquals(0, calls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun orThrowSignalsContentionButNullOnIoFailure() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-orthrow")
        try {
            val gate = PolicyRefreshGate(executor = executor)
            // Guard contention throws. Deterministic handshake: the contended
            // call only lands after the leader's reader is actually in
            // flight (entered latch), never on a wall-clock guess.
            val readerEntered = CountDownLatch(1)
            val leader = Thread {
                gate.readBoundedOrThrow(
                    { readerEntered.countDown(); ignoringReader(500)() },
                    timeoutMs = 50,
                )
            }
            leader.start()
            assertTrue("leader must enter its reader", readerEntered.await(5, TimeUnit.SECONDS))
            try {
                gate.readBoundedOrThrow({ setOf("x") }, timeoutMs = 50)
                assertTrue("expected contention", false)
            } catch (e: PolicyRefreshContentionException) {
                // Expected.
            }
            leader.join(5_000)
            assertFalse("leader must finish under load", leader.isAlive)
            // Worker drains (ignoring reader runs 500ms) before invalidate —
            // bounded poll, not a wall-clock sleep.
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (gate.activeCount() != 0 && System.nanoTime() < drainDeadline) {
                Thread.yield()
            }
            assertEquals(0, gate.activeCount())
            gate.invalidate()
            // IO failure (throwing reader) returns null, not contention.
            assertNull(gate.readBoundedOrThrow({ throw IllegalStateException("io") }, timeoutMs = 500))
            // Error reader also returns null (genuine failed read).
            assertNull(gate.readBoundedOrThrow({ throw AssertionError("err") }, timeoutMs = 500))
        } finally {
            executor.shutdownNow()
        }
    }

    // T-F3: frozen clock + a guard-holding leader; frozen-window guard
    // drops short-circuit with Contention("guard"), at most one
    // "outstanding in flight" log per throttle window, and — the R1
    // contract — the timeout streak is NOT decayed by guard drops.
    @Test
    fun guardContentionDropsDoNotDecayTimeoutStreak() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-guard-nodecay")
        try {
            val clock = AtomicLong(0L)
            val logs = mutableListOf<String>()
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                log = { logs.add(it) },
                // High trip threshold so only the drop/decay paths are under test.
                maxConsecutiveTimeouts = 100,
            )
            // Seed a non-zero streak: one reader timeout (80ms reader vs 50ms
            // budget — the worker finishes shortly after).
            assertNull(gate.readBounded(ignoringReader(80), timeoutMs = 50))
            assertEquals(1L, gate.consecutiveTimeouts.get())
            // Drain the worker so the leader can submit.
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (gate.activeCount() != 0 && System.nanoTime() < drainDeadline) {
                Thread.yield()
            }
            assertEquals(0, gate.activeCount())
            // Leader occupies the worker and holds the at-most-one guard
            // while parked inside its ignoring reader.
            val readerEntered = CountDownLatch(1)
            val leader = Thread {
                gate.readBoundedOrThrow(
                    { readerEntered.countDown(); ignoringReader(500)() },
                    timeoutMs = 500,
                )
            }
            leader.start()
            assertTrue("leader must enter its reader", readerEntered.await(5, TimeUnit.SECONDS))
            // Frozen clock: the 5s drop-log throttle admits exactly one line.
            repeat(3) { iteration ->
                val e = assertThrows(PolicyRefreshContentionException::class.java) {
                    gate.readBoundedOrThrow({ setOf("x") }, timeoutMs = 50)
                }
                assertEquals("guard", e.message)
            }
            assertEquals(
                1,
                logs.count { it.contains("outstanding in flight") },
            )
            // R1 contract: guard drops never decay the timeout streak.
            assertEquals(1L, gate.consecutiveTimeouts.get())
            leader.join(2_000)
            assertFalse(leader.isAlive)
        } finally {
            executor.shutdownNow()
        }
    }

    // T-F7(a): half-open probe arm — the busy/rejecting executor yields
    // PolicyRefreshBusyWorkerException("probe-busy-worker") and renews streak
    // + half-open backoff.
    @Test
    fun halfOpenProbeBusyWorkerRenewsStreakAndBackoff() {
        val executor = AlwaysRejectingExecutor()
        try {
            val clock = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
            )
            // Trip the breaker via the busy-worker arm.
            assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("x") }, timeoutMs = 50)
            }
            assertTrue(gate.isStuck())
            assertEquals(1L, gate.consecutiveTimeouts.get())
            assertEquals(0L, gate.halfOpenFailureCount())
            // Cooldown elapses: a half-open probe becomes eligible.
            clock.addAndGet(100L)
            val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("probe") }, timeoutMs = 50)
            }
            assertEquals("probe-busy-worker", e.message)
            assertEquals(2L, gate.consecutiveTimeouts.get())
            assertEquals(1L, gate.halfOpenFailureCount())
            assertTrue("backoff must double after one probe failure", gate.halfOpenCooldownMs() >= 100L)
            assertEquals(100L, gate.lastStuckOpenMs())
            assertEquals(1L, gate.halfOpenProbes.get())
            assertTrue(gate.isStuck())
        } finally {
            executor.shutdownNow()
        }
    }

    // T-F7(b): half-open probe arm — a get() that waits below the 5ms
    // GET_TIMEOUT_FLOOR is busy-contention ("thin-get"), NOT a probe failure:
    // no streak increment, no backoff renewal. NB: a real 1ms budget can
    // never leave remainingMs >= 1 after integer-ms division (the deadline
    // minus ~0 elapsed rounds to 0), so a 1ms budget exercises the
    // probe-budget-exhausted arm instead; 5ms normally leaves
    // remainingMs == 4 (< the 5ms floor).
    //
    // TT-P2-2 determinism note (residual, documented honestly): which of the
    // two contention arms fires depends on how long the HANDOFF itself took,
    // measured on a raw real-time nanoTime the test cannot freeze —
    // submitHandoffRetry deliberately bypasses the injectable nowMs breaker
    // clock ("frozen test clocks must not govern a real-time handoff race,
    // or the loop could never terminate", see its doc). With a 5ms budget the
    // handoff normally leaves remainingMs == 4, so the below-floor thin-get
    // arm fires; under a >~4ms mid-submit stall (e.g. worker-thread creation
    // on a loaded CI box) the budget is instead exhausted inside
    // submitHandoffRetry and the contention materializes as
    // "probe-budget-exhausted". Both arms are plain Contention, renew NO
    // streak/backoff, count NO stuck-worker timeout, and account exactly one
    // droppedRefreshCount — so the assertions below (type + every counter
    // pin) hold on either arm. The exception MESSAGE is deliberately not
    // pinned: it is the only observably different outcome between the two
    // arms and there is no seam that can make it deterministic without
    // compromising the fail-closed handoff. Type-only assert + all counter
    // pins kept.
    @Test
    fun halfOpenProbeThinGetYieldsContentionWithoutBackoffRenewal() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-thin-probe")
        try {
            val clock = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
            )
            // Trip the breaker with a timeout; worker then drains.
            assertNull(gate.readBounded(ignoringReader(80), timeoutMs = 50))
            assertTrue(gate.isStuck())
            assertEquals(1L, gate.consecutiveTimeouts.get())
            val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (gate.activeCount() != 0 && System.nanoTime() < drainDeadline) {
                Thread.yield()
            }
            assertEquals(0, gate.activeCount())
            val dropsBefore = gate.droppedRefreshCount.get()
            // Cooldown elapses; the half-open probe runs with a thin budget.
            clock.addAndGet(100L)
            val e = assertThrows(PolicyRefreshContentionException::class.java) {
                gate.readBoundedOrThrow(
                    {
                        try {
                            Thread.sleep(20)
                        } catch (_: InterruptedException) {
                        }
                        setOf("slow")
                    },
                    timeoutMs = 5,
                )
            }
            // Message deliberately unpinned (see the TT-P2-2 note above):
            // thin-get (normal) vs probe-budget-exhausted (mid-submit stall)
            // — both plain Contention, neither a stuck-worker signal.
            assertFalse("thin-get is not a busy-worker rejection", e is PolicyRefreshBusyWorkerException)
            // No streak / backoff renewal.
            assertEquals(1L, gate.consecutiveTimeouts.get())
            assertEquals(0L, gate.halfOpenFailureCount())
            assertEquals(0L, gate.lastStuckOpenMs())
            assertEquals(50L, gate.halfOpenCooldownMs())
            assertEquals(1L, gate.halfOpenProbes.get())
            assertEquals(dropsBefore + 1, gate.droppedRefreshCount.get())
            assertTrue(gate.isStuck())
        } finally {
            executor.shutdownNow()
        }
    }

    // O4(b) / T-F4: H7 rebuild-skipped arm — MAX_WORKER_REBUILDS is spent,
    // the fresh worker re-sticks past the rebuild threshold, and the call
    // renews streak/backoff/lastStuckOpenMs instead of looping rebuilds
    // without cooldown.
    @Test
    fun rebuildSkippedRenewsStreakBackoffAndLastStuckOpen() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-rebuild-skipped")
        try {
            val clock = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
            )
            // Trip the breaker; the (original) worker stays busy.
            assertNull(gate.readBounded(ignoringReader(600), timeoutMs = 50))
            assertTrue(gate.isStuck())
            awaitWorkerBusy(gate)
            // Cooldown elapses and busy age exceeds the rebuild threshold:
            // the single forced rebuild fires; the half-open probe succeeds
            // on the fresh executor, closing the breaker.
            clock.addAndGet(200L)
            assertEquals(setOf("ok"), gate.readBoundedOrThrow({ setOf("ok") }, timeoutMs = 2_000))
            assertEquals(1L, gate.workerRebuildCount())
            assertFalse(gate.isStuck())
            assertEquals(0L, gate.consecutiveTimeouts.get())
            // Re-stick the FRESH worker past the rebuild threshold.
            assertNull(gate.readBounded(ignoringReader(600), timeoutMs = 50))
            assertTrue(gate.isStuck())
            awaitWorkerBusy(gate)
            assertEquals(1L, gate.consecutiveTimeouts.get())
            assertEquals(0L, gate.halfOpenFailureCount())
            val probesBefore = gate.halfOpenProbes.get()
            val dropsBefore = gate.droppedRefreshCount.get()
            val shortCircuitsBefore = gate.stuckShortCircuits.get()
            clock.addAndGet(200L) // now == 400, age == 200 >= forceAt (150)
            val e = assertThrows(PolicyRefreshBusyWorkerException::class.java) {
                gate.readBoundedOrThrow({ setOf("never") }, timeoutMs = 50)
            }
            assertEquals("rebuild-skipped", e.message)
            // H7: onProbeFail semantics — streak++, halfOpenFailures++,
            // lastStuckOpenMs=now, breaker stays open, no second rebuild.
            assertEquals(2L, gate.consecutiveTimeouts.get())
            assertEquals(1L, gate.halfOpenFailureCount())
            assertEquals(clock.get(), gate.lastStuckOpenMs())
            assertTrue("backoff must renew after a skipped rebuild", gate.halfOpenCooldownMs() >= 100L)
            assertTrue(gate.isStuck())
            assertEquals(probesBefore, gate.halfOpenProbes.get())
            assertEquals(dropsBefore + 1, gate.droppedRefreshCount.get())
            assertEquals(shortCircuitsBefore + 1, gate.stuckShortCircuits.get())
            assertEquals(1L, gate.workerRebuildCount())
            assertEquals(1L, gate.busyStuckDropCount())
        } finally {
            executor.shutdownNow()
        }
    }
}
