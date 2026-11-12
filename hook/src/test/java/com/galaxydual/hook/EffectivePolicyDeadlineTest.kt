// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

// Phase7 P2 end-to-end deadline (gate-open).
//
// Locks the 50ms END-TO-END total semantics (capability + prefs share one
// budget; follower grace is min(50, remaining)):
// - dual 40ms readers truncate the prefs segment fail-closed (seed-only) with
//   elapsed < 80ms and <= total + slack;
// - fast/slow combo within budget merges;
// - contention returns seed-only WITHOUT renewing failureCooldown;
// - deadline-exhausted null DOES renew failureCooldown (next retry throttled);
// - grace 0 followers return fast (< 65ms).
// Uses real System.nanoTime (no mocked clock) and dedicated executors/gates
// mirroring DualHook's split gates (capability vs policy, never shared).
// Remaining/grace arithmetic goes through pure DeadlineBudget (the exact
// production function), never a test-local duplicate.
class EffectivePolicyDeadlineTest {

    private fun totalBudgetMs(): Long = PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS

    private fun remainingMs(t0Nanos: Long): Long =
        DeadlineBudget.remainingMs(t0Nanos, DeadlineBudget.nowNanos())

    private fun graceAtEntry(t0Nanos: Long): Long =
        DeadlineBudget.graceAtEntry(t0Nanos, DeadlineBudget.nowNanos())

    private fun sleepInterruptible(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            try {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun deadlineBudgetPureArithmetic() {
        // Pins the shared production function (no clock read inside).
        val t0 = 1_000_000_000L
        assertEquals(50L, DeadlineBudget.remainingMs(t0, t0))
        assertEquals(40L, DeadlineBudget.remainingMs(t0, t0 + 10_000_000L))
        assertEquals(-10L, DeadlineBudget.remainingMs(t0, t0 + 60_000_000L))
        assertEquals(50L, DeadlineBudget.graceAtEntry(t0, t0))
        assertEquals(40L, DeadlineBudget.graceAtEntry(t0, t0 + 10_000_000L))
        assertEquals(0L, DeadlineBudget.graceAtEntry(t0, t0 + 60_000_000L))
        assertEquals(50L, DeadlineBudget.remainingMs(0L, t0 + 999_000_000L))
    }

    @Test
    fun dualSlowTruncatedToSeedWithTotalBound() {
        val capExec = PolicyRefreshBudget.newPolicyRefreshExecutor("deadline-cap-1")
        val prefsExec = PolicyRefreshBudget.newPolicyRefreshExecutor("deadline-prefs-1")
        val capGate = PolicyRefreshGate(executor = capExec)
        val prefsGate = PolicyRefreshGate(executor = prefsExec)
        val cache = EffectivePolicyCache()
        try {
            val t0 = DeadlineBudget.nowNanos()
            val grace = graceAtEntry(t0)
            val result = cache.get(emptySet(), nonEmptyPolicyGateOpen = true, followerGraceMs = grace) {
                var rem = remainingMs(t0)
                if (rem <= 0) return@get null
                val cap = capGate.readBoundedOrThrow(
                    reader = {
                        sleepInterruptible(40)
                        setOf("capable")
                    },
                    timeoutMs = minOf(PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS, rem),
                ) ?: return@get null
                if (cap.isEmpty()) return@get emptySet()
                rem = remainingMs(t0)
                if (rem <= 0) return@get null
                prefsGate.readBoundedOrThrow(
                    reader = {
                        sleepInterruptible(40)
                        setOf("b")
                    },
                    timeoutMs = rem,
                )
            }
            val elapsed = (DeadlineBudget.nowNanos() - t0) / 1_000_000L
            // Prefs segment truncated by the shared total: fail-closed seed-only.
            assertEquals(emptySet<String>(), result)
            assertTrue("dual 40ms must stay under sequential 80ms, elapsed=$elapsed", elapsed < 80)
            assertTrue(
                "elapsed must respect total+slack elapsed=$elapsed",
                elapsed <= totalBudgetMs() + 30,
            )
        } finally {
            try {
                capExec.shutdownNow()
            } catch (_: Throwable) {
            }
            try {
                prefsExec.shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun fastSlowComboMergesWithinBudget() {
        val capExec = PolicyRefreshBudget.newPolicyRefreshExecutor("deadline-cap-2")
        val prefsExec = PolicyRefreshBudget.newPolicyRefreshExecutor("deadline-prefs-2")
        val capGate = PolicyRefreshGate(executor = capExec)
        val prefsGate = PolicyRefreshGate(executor = prefsExec)
        val cache = EffectivePolicyCache()
        try {
            val t0 = DeadlineBudget.nowNanos()
            val grace = graceAtEntry(t0)
            val result = cache.get(emptySet(), nonEmptyPolicyGateOpen = true, followerGraceMs = grace) {
                var rem = remainingMs(t0)
                if (rem <= 0) return@get null
                val cap = capGate.readBoundedOrThrow(
                    reader = {
                        sleepInterruptible(5)
                        setOf("capable")
                    },
                    timeoutMs = minOf(PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS, rem),
                ) ?: return@get null
                if (cap.isEmpty()) return@get emptySet()
                rem = remainingMs(t0)
                if (rem <= 0) return@get null
                prefsGate.readBoundedOrThrow(
                    reader = {
                        sleepInterruptible(10)
                        setOf("b")
                    },
                    timeoutMs = rem,
                )
            }
            val elapsed = (DeadlineBudget.nowNanos() - t0) / 1_000_000L
            assertEquals(setOf("b"), result)
            assertTrue("fast/slow must fit in total, elapsed=$elapsed", elapsed <= totalBudgetMs() + 30)
        } finally {
            try {
                capExec.shutdownNow()
            } catch (_: Throwable) {
            }
            try {
                prefsExec.shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun contentionSeedOnlyDoesNotRenewCooldown() {
        // Real nanoTime clock (no mock): rapid back-to-back calls.
        val cache = EffectivePolicyCache()
        var reads = 0
        assertEquals(
            emptySet<String>(),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                throw PolicyRefreshContentionException("guard")
            },
        )
        assertEquals(1, reads)
        // Contention never sets lastFailureMs: immediate retry must invoke
        // the reader again (no 5s throttle).
        assertEquals(
            setOf("b"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("b")
            },
        )
        assertEquals(2, reads)
    }

    @Test
    fun deadlineSkipNullRenewsFailureCooldown() {
        // A deadline-exhausted skip (DualHook returns null when remaining <= 0)
        // is a genuine failed read for the cache: unlike contention, it MUST
        // renew the 5s failureCooldown so the next query does not retry IPC.
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        var reads = 0
        assertEquals(
            setOf("com.seed.app"),
            cache.get(setOf("com.seed.app"), true, reader = {
                reads++
                null
            }),
        )
        assertEquals(1, reads)
        // Within cooldown: reader must NOT run again (throttled).
        time = 2_000L
        assertEquals(
            setOf("com.seed.app"),
            cache.get(setOf("com.seed.app"), true, reader = {
                reads++
                setOf("b")
            }),
        )
        assertEquals(1, reads)
        // After cooldown: retry resumes (union of seed + remote).
        time = 7_000L
        assertEquals(
            setOf("com.seed.app", "b"),
            cache.get(setOf("com.seed.app"), true, reader = {
                reads++
                setOf("b")
            }),
        )
        assertEquals(2, reads)
    }

    @Test
    fun graceZeroFollowersReturnFast() {
        val cache = EffectivePolicyCache()
        val reads = AtomicInteger()
        // Leader occupies the single-flight with a 200ms read.
        var leaderResult: Set<String>? = null
        val leader = Thread {
            leaderResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = 0L,
            ) {
                reads.incrementAndGet()
                sleepInterruptible(200)
                setOf("b")
            }
        }
        leader.start()
        // Ensure the leader claimed inFlightGen before followers arrive.
        sleepInterruptible(20)
        val followerElapsed = AtomicInteger(-1)
        var followerResult: Set<String>? = null
        val followerStart = DeadlineBudget.nowNanos()
        val follower = Thread {
            followerResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = 0L,
            ) {
                reads.incrementAndGet()
                setOf("unexpected")
            }
        }
        follower.start()
        follower.join()
        val felapsed = (DeadlineBudget.nowNanos() - followerStart) / 1_000_000L
        followerElapsed.set(felapsed.toInt())
        leader.join()
        // Follower skipped the grace sleep: fast seed-only (< 65ms) while the
        // leader was still in flight; exactly one reader call total.
        assertEquals(emptySet<String>(), followerResult)
        assertEquals(1, reads.get())
        assertEquals(setOf("b"), leaderResult)
        assertTrue("grace 0 follower must return fast, elapsed=$felapsed", felapsed < 65)
    }
}
