// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        val leaderStarted = CountDownLatch(1)
        // Leader occupies the single-flight with a 200ms read.
        var leaderResult: Set<String>? = null
        val leader = Thread {
            leaderResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = 0L,
            ) {
                reads.incrementAndGet()
                leaderStarted.countDown()
                sleepInterruptible(200)
                setOf("b")
            }
        }
        leader.start()
        // Ensure the leader claimed inFlightGen before followers arrive.
        assertTrue("leader must start read within 5s", leaderStarted.await(5, TimeUnit.SECONDS))
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

    @Test
    fun fastLeaderNotifiesAndWakesFollowersImmediately() {
        // Notify semantics without an absolute hot-path SLA: the follower must
        // observe the leader's commit *before* its grace expires (notifyAll
        // wakeup, not the ~grace timeout). All bounds are relative to
        // [graceMs]; thread-start latency is excluded by timestamping inside
        // the follower thread, so CI thread-scheduling jitter cannot flake
        // the pin. A broken notify would park the follower for the full grace
        // (~50ms in-get, ~45ms post-commit) and fail both timing pins below.
        val graceMs = EffectivePolicyCache.DEFAULT_FOLLOWER_GRACE_MS
        val leaderWorkMs = 5L
        val cache = EffectivePolicyCache()
        val reads = AtomicInteger()
        val leaderStarted = CountDownLatch(1)
        val leaderCommitNanos = AtomicLong(-1L)
        var leaderResult: Set<String>? = null
        val leader = Thread {
            leaderResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = graceMs,
            ) {
                reads.incrementAndGet()
                leaderStarted.countDown()
                sleepInterruptible(leaderWorkMs)
                setOf("fast-leader")
            }
            leaderCommitNanos.set(DeadlineBudget.nowNanos())
        }
        leader.start()
        assertTrue("leader must start", leaderStarted.await(5, TimeUnit.SECONDS))
        var followerResult: Set<String>? = null
        val followerEnterNanos = AtomicLong(-1L)
        val followerExitNanos = AtomicLong(-1L)
        val follower = Thread {
            followerEnterNanos.set(DeadlineBudget.nowNanos())
            followerResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = graceMs,
            ) {
                reads.incrementAndGet()
                setOf("unexpected")
            }
            followerExitNanos.set(DeadlineBudget.nowNanos())
        }
        follower.start()
        follower.join()
        leader.join()

        assertEquals(1, reads.get())
        assertEquals(setOf("fast-leader"), leaderResult)
        assertEquals(setOf("fast-leader"), followerResult)
        // Follower time spent inside get() (excludes thread-start): must
        // return before the grace expires — i.e. woken by the leader's
        // notifyAll, not by the grace timeout.
        val inGetElapsedMs = (followerExitNanos.get() - followerEnterNanos.get()) / 1_000_000L
        assertTrue(
            "follower must wake on leader commit before grace expiry, inGet=${inGetElapsedMs}ms grace=${graceMs}ms",
            inGetElapsedMs < graceMs,
        )
        // Immediacy relative to grace (wakeup << grace): the follower must
        // return shortly after the leader commits, not linger until the
        // timeout. Clamped at 0: a follower that arrives after the commit
        // observes the cached value with no wait at all.
        val wakeLatencyMs = ((followerExitNanos.get() - leaderCommitNanos.get()) / 1_000_000L).coerceAtLeast(0L)
        assertTrue(
            "follower wakeup must be prompt after leader commit (<< grace), wake=${wakeLatencyMs}ms grace=${graceMs}ms",
            wakeLatencyMs < graceMs / 2,
        )
    }

    @Test
    fun followerTotalElapsedNeverExceedsBudgetUnderContention() {
        // Bounded-budget semantics with environment tolerance: followers must
        // fall back to seed after their grace (not block on the 200ms leader)
        // and must return within budget + generous CI slack. The primary pin
        // is thread-state based (no wall clock): all followers complete while
        // the slow leader is still in flight. The wall-time bound is a
        // secondary hang-guard with generous tolerance, not a hot-path SLA.
        val graceMs = EffectivePolicyCache.DEFAULT_FOLLOWER_GRACE_MS
        val budgetMs = PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS
        // Generous scheduler tolerance for 8 contending followers; still well
        // below the 200ms leader hold, so a wait-on-the-leader regression is
        // caught by both the isAlive pin and this bound.
        val envSlackMs = 100L
        val cache = EffectivePolicyCache()
        val reads = AtomicInteger()
        val leaderStarted = CountDownLatch(1)
        // Leader takes long (200ms) to ensure followers hit their grace budget timeout.
        val leader = Thread {
            cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = graceMs,
            ) {
                reads.incrementAndGet()
                leaderStarted.countDown()
                sleepInterruptible(200)
                setOf("slow-leader")
            }
        }
        leader.start()
        assertTrue("leader must start", leaderStarted.await(5, TimeUnit.SECONDS))

        val followerCount = 8
        val followerResults = Collections.synchronizedList(mutableListOf<Set<String>>())
        val followerElapsedList = Collections.synchronizedList(mutableListOf<Long>())
        val followers = (1..followerCount).map {
            Thread {
                val start = DeadlineBudget.nowNanos()
                val res = cache.get(
                    emptySet(),
                    nonEmptyPolicyGateOpen = true,
                    followerGraceMs = graceMs,
                ) {
                    reads.incrementAndGet()
                    setOf("unexpected")
                }
                val elapsed = (DeadlineBudget.nowNanos() - start) / 1_000_000L
                followerResults.add(res)
                followerElapsedList.add(elapsed)
            }
        }
        followers.forEach { it.start() }
        followers.forEach { it.join() }
        // Semantic pin without a wall clock: every follower returned within
        // its grace while the slow leader was still in flight (followers did
        // not block on the leader's 200ms commit). Strictly looser than the
        // wall-time bound below (150ms cap < 200ms hold), so it adds signal
        // without adding flake surface.
        assertTrue(
            "followers must return within grace while slow leader still in flight",
            leader.isAlive,
        )
        leader.join()

        assertEquals(1, reads.get())
        assertEquals(followerCount, followerResults.size)
        // All followers timed out after their grace budget (50ms) and fell back to seed (emptySet)
        for (res in followerResults) {
            assertEquals(emptySet<String>(), res)
        }
        // Hang-guard with generous environment tolerance (budget + 100ms),
        // not a hot-path SLA: catches unbounded waits while absorbing CI
        // lock-contention/scheduling jitter across the 8 followers.
        for (elapsed in followerElapsedList) {
            assertTrue(
                "follower elapsed must stay within budget+generous slack, elapsed=$elapsed budget=$budgetMs slack=$envSlackMs",
                elapsed <= budgetMs + envSlackMs,
            )
        }
    }
}
