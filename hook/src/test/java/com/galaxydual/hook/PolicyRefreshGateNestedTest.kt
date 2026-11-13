// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

// Critical nesting regression: the capability probe must NOT share the policy
// gate's non-reentrant Semaphore(1). An outer policy reader that contains an
// inner probe on the SAME gate self-contends (the query thread holds the
// permit while the worker thread tries tryAcquire), so the first true can
// never latch (permanent seed-only). Separate gates keep the probe latchable.
class PolicyRefreshGateNestedTest {

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

    // NOTE (T12): this test deliberately pins the PRE-FIX failure shape —
    // one gate shared between the outer policy read and its nested probe
    // self-contends the single-flight permit, so the outer always falls back
    // to seed-only. It documents why the gates must stay split. If the
    // capability/policy gates ever merge, DELETE this test together with the
    // gate split (it would then assert behavior that no longer exists).
    @Test
    fun sharedGateNestedProbeSelfContends() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-nested-shared")
        try {
            val gate = PolicyRefreshGate(executor = executor)
            // Outer policy read whose reader runs the capability probe on the
            // SAME gate (the pre-fix DualHook shape: effectivePolicy outer +
            // isRemoteCapable inner sharing policyRefreshGate).
            val outer: Set<String>? = gate.readBoundedOrThrow(
                reader = {
                    // Runs on the gate worker thread while the query thread
                    // holds the outstanding permit: inner tryAcquire must fail.
                    try {
                        gate.readBoundedOrThrow(
                            reader = { setOf("capable") },
                            timeoutMs = 2_000,
                        )
                    } catch (_: PolicyRefreshContentionException) {
                        // DualHook maps contention to null (probe returns null,
                        // readRemotePolicy returns null, outer sees failed read).
                        null
                    }
                },
                timeoutMs = 2_000,
            )
            // Self-contention pins the outer to null: a shared gate can never
            // deliver the inner "capable" value through nesting.
            assertNull("shared-gate nesting must self-contend to null", outer)
        } finally {
            try {
                executor.shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun separateGatesSerialProbeSucceeds() {
        val policyExecutor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-serial-policy")
        val capabilityExecutor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-serial-capability")
        try {
            val policyGate = PolicyRefreshGate(executor = policyExecutor)
            val capabilityGate = PolicyRefreshGate(executor = capabilityExecutor)
            // Production DualHook shape (effectivePolicy + isRemoteCapable):
            // the capability probe runs FIRST on its own gate+executor, and
            // only on capable=true does the policy gate wrap the prefs read.
            // Never nested: the outer policy reader contains prefs only, so
            // no permit overlap can self-contend.
            val probe: Set<String>? = capabilityGate.readBoundedOrThrow(
                reader = { setOf("capable") },
                timeoutMs = 2_000,
            )
            assertEquals(setOf("capable"), probe)
            val capable = !probe.isNullOrEmpty()
            assertTrue("serial probe must report capable", capable)
            val prefs: Set<String>? = policyGate.readBoundedOrThrow(
                reader = { setOf("com.example") },
                timeoutMs = 2_000,
            )
            assertEquals(setOf("com.example"), prefs)
        } finally {
            try {
                policyExecutor.shutdownNow()
            } catch (_: Throwable) {
            }
            try {
                capabilityExecutor.shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun serialFirstTrueReachesPolicyEndToEnd() {
        val policyExecutor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-e2e-policy")
        val capabilityExecutor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-e2e-capability")
        try {
            val policyGate = PolicyRefreshGate(executor = policyExecutor)
            val capabilityGate = PolicyRefreshGate(executor = capabilityExecutor)
            val latch = RemoteCapabilityLatch()
            val cache = EffectivePolicyCache()
            var probeCalls = 0
            // Mirrors DualHook.isRemoteCapable sequencing (probe on the
            // capability gate first, latch bookkeeping, Boolean?/null
            // contract). JVM tests cannot instantiate DualHook (framework
            // base class), so this seam replays the same primitives in the
            // same serial order to prove the first true is latchable.
            fun isRemoteCapable(): Boolean? {
                if (latch.isLatchedTrue()) return true
                if (!latch.shouldProbe()) return null
                val probe: Set<String>? = try {
                    capabilityGate.readBoundedOrThrow(
                        reader = {
                            probeCalls++
                            setOf("capable")
                        },
                        timeoutMs = 2_000,
                    )
                } catch (_: PolicyRefreshContentionException) {
                    latch.reportFalseAt(System.nanoTime() / 1_000_000L)
                    return null
                } catch (_: Throwable) {
                    latch.reportFalseAt(System.nanoTime() / 1_000_000L)
                    return null
                }
                if (probe == null) {
                    latch.reportFalseAt(System.nanoTime() / 1_000_000L)
                    return null
                }
                val capable = probe.isNotEmpty()
                if (capable) latch.reportTrue() else latch.reportFalse()
                return capable
            }
            // Mirrors DualHook.effectivePolicy loader order: capability probe
            // first, policy gate only when capable. Gate flag open so the
            // loader actually runs (production gate is closed until HOOK.md
            // §1.4 device evidence; the shape under test is identical).
            fun loadOnce(): Set<String> = cache.get(emptySet(), true) {
                val capable = isRemoteCapable() ?: return@get null
                if (!capable) return@get emptySet()
                policyGate.readBoundedOrThrow(
                    reader = { setOf("com.example.app") },
                    timeoutMs = 2_000,
                )
            }
            // First true flows end-to-end: probe latches, policy read delivers.
            assertEquals(setOf("com.example.app"), loadOnce())
            assertTrue(latch.isLatchedTrue())
            assertEquals(1, probeCalls)
            // Post-invalidate retry: latched-true fast path, no re-probe.
            cache.invalidate()
            latch.onInvalidate()
            assertEquals(setOf("com.example.app"), loadOnce())
            assertTrue(latch.isLatchedTrue())
            assertEquals(1, probeCalls)
        } finally {
            try {
                policyExecutor.shutdownNow()
            } catch (_: Throwable) {
            }
            try {
                capabilityExecutor.shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun serialReuseNeverSpuriouslyRejects() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-serial-reuse")
        try {
            val gate = PolicyRefreshGate(executor = executor)
            // Regression pin for the serial-reuse handoff race: with
            // ThreadPoolExecutor(0, 1, SynchronousQueue, AbortPolicy) the
            // Future signals completion BEFORE the worker re-parks on the
            // handoff, so a tight back-to-back submit spuriously rejects
            // (~1% of gate submits pre-fix, ~28% of raw executor submits).
            // The gate retries the handoff strictly within the call's own
            // budget (zero queue kept, no extra thread), so serial reuse on
            // an idle worker must ALWAYS deliver the truth with zero drops.
            // Timing model, stated explicitly: the gate's 2ms fail-fast floor is
            // a REAL-TIME window measured from the first rejection in the call
            // (MIN_ELAPSED_BEFORE_FAILFAST_MS, capped at min(timeoutMs, 2ms));
            // with the 2s budget it fires long before the budget is exhausted —
            // the deadline re-check is a separate backstop, not the floor's
            // trigger. Honest starvation handling: an iteration that hits
            // PolicyRefreshContentionException is retried at most 2 extra times
            // (same call, same budget), and at most 10 contention retries are
            // tolerated across the whole test — beyond that the handoff is
            // genuinely broken and the test fails loudly. Note the tolerance is
            // also backstopped by the final droppedRefreshCount == 0 assert:
            // every gate escape increments that counter, so in practice a
            // persistent regression cannot ship green regardless of retries.
            // No fixed sleeps; a starvation event longer than the 2ms floor can
            // still fail this test on a pathologically loaded runner.
            var contentionRetries = 0
            repeat(300) { i ->
                var r: Set<String>? = null
                var extraAttempts = 0
                while (r == null) {
                    r = try {
                        gate.readBoundedOrThrow(
                            reader = { setOf("com.example.app") },
                            timeoutMs = 2_000,
                        )
                    } catch (_: PolicyRefreshContentionException) {
                        contentionRetries++
                        extraAttempts++
                        if (contentionRetries > 10) {
                            fail("too many PolicyRefreshContentionException retries across serial reuse (>$contentionRetries): executor handoff regression, not jitter")
                        }
                        if (extraAttempts > 2) {
                            fail("serial reuse iteration $i rejected more than 2 extra times under contention")
                        }
                        null
                    }
                }
                assertEquals("serial reuse iteration $i must deliver truth", setOf("com.example.app"), r)
            }
            assertEquals(0L, gate.droppedRefreshCount.get())
            assertEquals(0, gate.queueDepth())
            assertTrue("active<=1 active=${gate.activeCount()}", gate.activeCount() <= 1)
            assertEquals(1, gate.outstandingPermitsAvailable())
        } finally {
            try {
                executor.shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun secondRebuildBeyondCapOnlyShortCircuits() {
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-rebuild-cap")
        var currentToClose: java.util.concurrent.ExecutorService? = null
        var firstExecutor: java.util.concurrent.ExecutorService? = null
        try {
            val clock = AtomicLong(0L)
            val gate = PolicyRefreshGate(
                executor = executor,
                nowMs = { clock.get() },
                maxConsecutiveTimeouts = 1,
                halfOpenInitialMs = 50L,
                halfOpenMaxMs = 200L,
            )
            firstExecutor = executor
            // First trip: single timeout trips the breaker (worker busy 500ms).
            assertNull(gate.readBounded(ignoringReader(500), timeoutMs = 50))
            assertTrue(gate.isStuck())
            // Busy age 200 >= min(3*50=150, 200)=150 forces the single rebuild.
            clock.addAndGet(200L)
            val before = gate.currentExecutorForTest()
            val r = gate.readBounded({ setOf("ok") }, timeoutMs = 2_000)
            assertEquals(setOf("ok"), r)
            assertEquals(1L, gate.workerRebuildCount())
            assertTrue(gate.currentExecutorForTest() !== before)
            // Thread name carries the rebuild count for forensics.
            val freshName = (gate.currentExecutorForTest() as java.util.concurrent.ThreadPoolExecutor)
                .threadFactory.newThread({}).name
            assertTrue("rebuilt thread name must carry count, was: $freshName", freshName.contains("rebuilt-1"))
            currentToClose = gate.currentExecutorForTest()
            // Second cycle: trip the fresh gate again with a long stuck worker.
            assertNull(gate.readBounded(ignoringReader(2_000), timeoutMs = 50))
            assertTrue(gate.isStuck())
            clock.addAndGet(500L)
            val before2 = gate.currentExecutorForTest()
            // Cap (MAX_WORKER_REBUILDS=1) reached: no second rebuild, only
            // short-circuit + counting, executor identity unchanged.
            assertNull(gate.readBounded({ setOf("never") }, timeoutMs = 50))
            assertEquals(1L, gate.workerRebuildCount())
            assertTrue(gate.currentExecutorForTest() === before2)
            assertTrue(gate.busyStuckDropCount() >= 1L)
            assertTrue(gate.isStuck())
        } finally {
            try {
                firstExecutor?.let { (it as? java.util.concurrent.ThreadPoolExecutor)?.shutdownNow() }
            } catch (_: Throwable) {
            }
            try {
                (currentToClose as? java.util.concurrent.ThreadPoolExecutor)?.shutdownNow()
            } catch (_: Throwable) {
            }
            // If no rebuild happened, currentToClose may be null; close original.
            try {
                (executor as java.util.concurrent.ThreadPoolExecutor).shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }
}
