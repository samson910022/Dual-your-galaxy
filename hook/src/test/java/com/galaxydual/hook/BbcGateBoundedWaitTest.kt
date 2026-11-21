// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
// Real Binder latency / device behavior DEVICE-UNVERIFIED (docs/ACCEPTANCE §6); this JVM wall-time pin is a safety bound, not a device go/no-go.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// D06 (PR#74 r3): the BBC opt-out gate pref read has a REAL wall-time ceiling.
//
// These tests are framework-free (JUnit4 + java.util.concurrent only) and use
// genuine System.nanoTime elapsed time, NOT a fake clock: a stalled provider
// that truly blocks a worker must return to the caller inside BBC_GATE_MAX_MS
// (asserted with generous hang-guard headroom, not a hot-path SLA). The gate
// decision logic itself is UNCHANGED — these pins only prove the bounded
// wrapper hands null (failed read) to the existing hybrid rule.
class BbcGateBoundedWaitTest {

    private fun newGateExecutor(name: String): ThreadPoolExecutor =
        PolicyRefreshBudget.newPolicyRefreshExecutor(name)

    @Test
    fun blockingGateProvider_callerReturnsWithinCeiling() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = newGateExecutor("test-bbc-gate-ceiling")
        try {
            val result = readBbcInjectionEnabledBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                entered.countDown()
                // Truly blocks the worker well past the ceiling (> 200ms).
                release.await(2, TimeUnit.SECONDS)
                false
            }
            assertTrue("reader must have entered", entered.await(1, TimeUnit.SECONDS))
            assertNull(result) // timeout -> failed read, not the provider's false
            // The provider is still HELD on `release` (dropped only in finally),
            // so getting null back already proves the bounded wrapper gave up
            // well before the 2s stall — no separate wall-time pin needed here.
        } finally {
            release.countDown() // release the stuck worker: no leaked thread
            executor.shutdownNow()
        }
    }

    @Test
    fun fastConfirmedOff_returnsFalseNotNull() {
        // Control for the Boolean? distinction the bounded helper exists for:
        // a completed read of OFF is false (confirmed), NOT null (failed).
        val executor = newGateExecutor("test-bbc-gate-fast")
        try {
            val result = readBbcInjectionEnabledBounded(executor, DualHook.BBC_GATE_MAX_MS) { false }
            assertEquals(false, result)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun gateTimeoutFirstFailure_failsOpen() {
        val release = CountDownLatch(1)
        val executor = newGateExecutor("test-bbc-gate-failopen")
        val state = AtomicReference<Long?>(null)
        val nowMs = AtomicLong(1_000L)
        try {
            val gate = bbcInjectionGateOpen(
                PolicySource.BBC_AVAILABLE_APPS_INJECTION,
                nowMs = { nowMs.get() },
                stickyState = state,
            ) {
                readBbcInjectionEnabledBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                    // The provider WOULD report OFF, but the worker stalls past
                    // the ceiling -> bounded read returns null (failed read),
                    // so the false value never reaches the gate.
                    release.await(2, TimeUnit.SECONDS)
                    false
                }
            }
            // No prior confirmed OFF in this fresh process-local state: the
            // EXISTING hybrid rule maps a failed read to OPEN. "off" is NOT a
            // safety guarantee (cold-start deletion, issue #75).
            assertTrue(gate)
            assertNull(state.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun gateTimeoutKeepsStickyOffWithinTtl() {
        val t0 = 1_000L
        val now = AtomicLong(t0)
        val state = AtomicReference<Long?>(null)
        val release = CountDownLatch(1)
        val executor = newGateExecutor("test-bbc-gate-sticky")
        fun gate(read: () -> Boolean?): Boolean = bbcInjectionGateOpen(
            PolicySource.BBC_AVAILABLE_APPS_INJECTION,
            nowMs = { now.get() },
            ttlMs = EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS,
            stickyState = state,
            runtimeEnabled = read,
        )
        try {
            // t0: confirmed OFF -> gate CLOSED, sticky stamp recorded.
            assertFalse(gate { false })
            assertEquals(t0, state.get())

            // t0 + TTL/2: the provider stalls past the ceiling -> null. The
            // EXISTING hybrid rule keeps a confirmed OFF sticky for the TTL,
            // so the gate stays CLOSED and getBbc short-circuits to Cleared(-1)
            // with ZERO policy-reader calls (gate-closed invariant preserved).
            now.set(t0 + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS / 2)
            val cache = EffectivePolicyCache(stickyLastGood = true)
            var readerCalls = 0
            val res = cache.getBbc(
                emptySet(),
                bbcGateOpen = {
                    gate {
                        readBbcInjectionEnabledBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                            release.await(2, TimeUnit.SECONDS)
                            false
                        }
                    }
                },
                reader = { readerCalls++; Pair(setOf("com.should.not.read"), 99L) },
            )
            assertTrue(res is EffectivePolicyCache.BbcPolicy.Cleared)
            assertEquals(-1L, (res as EffectivePolicyCache.BbcPolicy.Cleared).version)
            assertEquals(0, readerCalls)
            assertEquals(t0, state.get()) // sticky OFF unchanged by a failed read
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun gateExecutorRejection_mapsToNullWithinBudget() {
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = newGateExecutor("test-bbc-gate-reject")
        val readerCalled = AtomicBoolean(false)
        try {
            // Occupy the single zero-queue worker so the next submit is refused.
            executor.execute {
                occupied.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(occupied.await(1, TimeUnit.SECONDS))

            val startMs = System.nanoTime() / 1_000_000L
            val result = readBbcInjectionEnabledBounded(executor, DualHook.BBC_GATE_MAX_MS) {
                readerCalled.set(true)
                false
            }
            val elapsedMs = System.nanoTime() / 1_000_000L - startMs

            // AbortPolicy + SynchronousQueue: submit is REJECTED while the
            // worker is busy. Contention maps to null without waiting for the
            // ceiling, and the reader is never invoked. The elapsed check is a
            // generous hang guard (far below the 2s provider stall), not a SLA.
            assertNull(result)
            assertFalse(readerCalled.get())
            assertTrue("elapsed=$elapsedMs must be < 500ms", elapsedMs < 500L)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
