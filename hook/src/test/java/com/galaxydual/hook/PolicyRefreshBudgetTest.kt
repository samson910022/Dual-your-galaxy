// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

// P1-2: hot-path blocking binder IPC has a bounded stall budget.
//
// Pins that PolicyRefreshBudget.readBounded never blocks the caller beyond
// the timeout: a slow reader (simulated binder stall) returns null
// (failed-read signal -> seed/last-known-good fallback) within the budget,
// while a fast reader returns its value.
class PolicyRefreshBudgetTest {

    @Test
    fun slowReaderTimesOutWithinBudget() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val start = System.nanoTime() / 1_000_000L
            val result = PolicyRefreshBudget.readBounded(
                reader = {
                    Thread.sleep(2_000)
                    setOf("slow")
                },
                timeoutMs = 50,
                executor = executor,
            )
            val elapsed = System.nanoTime() / 1_000_000L - start
            assertNull(result)
            // Must return near the budget, not after the 2s stall.
            assertTrue("elapsed=$elapsed", elapsed < 1_500)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun fastReaderReturnsValue() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = PolicyRefreshBudget.readBounded(
                reader = { setOf("a", "b") },
                timeoutMs = 500,
                executor = executor,
            )
            assertEquals(setOf("a", "b"), result)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun throwingReaderMapsToNull() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = PolicyRefreshBudget.readBounded(
                reader = { throw IllegalStateException("binder dead") },
                timeoutMs = 500,
                executor = executor,
            )
            assertNull(result)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun leaderTimeoutFallsBackToSeedViaCache() {
        // End-to-end through the cache: a stalled leader read falls back to
        // the seed alone (fail-closed observe-only) instead of blocking.
        val executor = Executors.newSingleThreadExecutor()
        try {
            val cache = EffectivePolicyCache()
            val result = cache.get(
                setOf("com.seed.app"),
                nonEmptyPolicyGateOpen = true,
            ) {
                PolicyRefreshBudget.readBounded(
                    reader = {
                        Thread.sleep(2_000)
                        setOf("com.remote.app")
                    },
                    timeoutMs = 50,
                    executor = executor,
                )
            }
            assertEquals(setOf("com.seed.app"), result)
        } finally {
            executor.shutdownNow()
        }
    }
}
