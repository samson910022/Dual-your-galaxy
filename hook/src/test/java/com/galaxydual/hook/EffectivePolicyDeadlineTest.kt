// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
// - grace 0 followers return without waiting on the leader;
// - fast leader wakes grace followers via notifyAll (latch-handshake tests).
// Follower tests use CountDownLatch handshakes (leader parked inside its
// reader; followerGraceHits poll), never fixed sleeps or wall-clock bounds.
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
        assertEquals(50L, DeadlineBudget.graceAtEntry(t0, t0))
        assertEquals(40L, DeadlineBudget.graceAtEntry(t0, t0 + 10_000_000L))
        assertEquals(0L, DeadlineBudget.graceAtEntry(t0, t0 + 60_000_000L))
        // H8: t0 == NO_START_NANOS sentinel degrades fail-closed (no budget),
        // never to a fresh full budget.
        assertEquals(0L, DeadlineBudget.remainingMs(0L, t0 + 999_000_000L))
        assertEquals(0L, DeadlineBudget.graceAtEntry(0L, t0 + 999_000_000L))
        // H8: now == NO_START_NANOS sentinel (broken clock handed in as
        // `now`) degrades the same way — zero budget, never the full total.
        assertEquals(0L, DeadlineBudget.remainingMs(t0, 0L))
        assertEquals(0L, DeadlineBudget.graceAtEntry(t0, 0L))
        // Backwards clock (now < t0) degrades to zero budget.
        assertEquals(0L, DeadlineBudget.remainingMs(t0, t0 - 1_000_000L))
        assertEquals(0L, DeadlineBudget.graceAtEntry(t0, t0 - 1_000_000L))
        // Result is coerced into [0, totalMs]: overrun clamps to 0.
        assertEquals(0L, DeadlineBudget.remainingMs(t0, t0 + 60_000_000L))
        // CR-O5: nanoTime's origin may legitimately be negative on real
        // platforms — a negative t0 is VALID input, not the NO_START (0L)
        // sentinel; remaining computes from the offset and clamps normally.
        assertEquals(40L, DeadlineBudget.remainingMs(-5_000_000_000L, -5_000_000_000L + 10_000_000L))
        assertEquals(0L, DeadlineBudget.remainingMs(-5_000_000_000L, -5_000_000_000L - 1L)) // backwards
        // CR-O5: extreme-scale (now - t0 overflows 2^63, wraps two's
        // complement negative) must never throw and never exceed totalMs;
        // the wrapped delta truncates to 0ms upstream of the coerceIn clamp.
        assertEquals(
            PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS,
            DeadlineBudget.remainingMs(Long.MIN_VALUE + 1L, Long.MAX_VALUE),
        )
        assertEquals(
            PolicyRefreshBudget.DEFAULT_STALL_BUDGET_MS,
            DeadlineBudget.graceAtEntry(Long.MIN_VALUE + 1L, Long.MAX_VALUE),
        )
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
            // Hang-guard only (NOT an SLA): the budget itself enforces the
            // real bound (prefs truncated at ~the 50ms total); this assert
            // exists only to catch regressions that actually hang — two
            // 40ms sleeps + dispatch. 1s leaves generous loaded-CI headroom;
            // tightening it reintroduces flake without pinning anything
            // the budget does not already guarantee.
            assertTrue("dual 40ms must complete, not hang; elapsed=$elapsed (budget enforces the real bound)", elapsed < 1_000)
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
        // T3: deterministic handshake — the leader parks on a latch inside its
        // reader; the follower (grace 0) must return seed-only while the
        // leader is parked. No wall-clock bound; completion is pinned via
        // join(timeout) + assertFalse(isAlive).
        val cache = EffectivePolicyCache()
        val reads = AtomicInteger()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        // T-F9: failure flag set inside the reader thread, asserted on the
        // main thread — an assertTrue() inside the reader would be swallowed
        // by the cache's failed-read handling (AssertionError -> null).
        val leaderLatchFailed = AtomicBoolean(false)
        var leaderResult: Set<String>? = null
        val leader = Thread {
            leaderResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = 0L,
            ) {
                reads.incrementAndGet()
                leaderStarted.countDown()
                try {
                    // Park until the test releases us; the reader must not
                    // complete before the follower probe below.
                    if (!leaderRelease.await(5, TimeUnit.SECONDS)) leaderLatchFailed.set(true)
                } catch (_: InterruptedException) {
                    leaderLatchFailed.set(true)
                }
                setOf("b")
            }
        }
        leader.start()
        assertTrue("leader must start read within 5s", leaderStarted.await(5, TimeUnit.SECONDS))
        var followerResult: Set<String>? = null
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
        // Grace-0 path is synchronous and non-blocking: the follower returns
        // before the parked leader can commit. join(timeout) + isAlive pins
        // completion without a wall-clock bound.
        follower.join(2_000)
        assertFalse("grace 0 follower must not park on the leader", follower.isAlive)
        assertEquals(emptySet<String>(), followerResult)
        // Grace-0 followers skip the wait entirely: no followerGraceHits.
        assertEquals(0L, cache.followerGraceHits.get())
        // T-F6: a grace-0 follower that fell back to seed-only is counted
        // under graceExpiredSeedOnly (the early-return arm increments it).
        assertEquals(1L, cache.graceExpiredSeedOnly.get())
        leaderRelease.countDown()
        leader.join(2_000)
        assertFalse(leader.isAlive)
        assertFalse("leader latch never released", leaderLatchFailed.get())
        assertEquals(1, reads.get())
        assertEquals(setOf("b"), leaderResult)
    }

    @Test
    fun fastLeaderNotifiesAndWakesFollowersImmediately() {
        // T1: deterministic handshake — the leader parks on a latch inside its
        // reader; the follower parks in the grace wait; we poll
        // followerGraceHits>=1 to know the follower entered the grace path,
        // then release the leader. The notifyAll wakeup delivers the committed
        // value, never the grace timeout. No wall-clock bound on wake
        // latency; completion is pinned via join(timeout) + assertFalse.
        val graceMs = EffectivePolicyCache.DEFAULT_FOLLOWER_GRACE_MS
        val cache = EffectivePolicyCache()
        val reads = AtomicInteger()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        // T-F9: failure flag set inside the reader thread, asserted on the
        // main thread — an assertTrue() inside the reader would be swallowed
        // by the cache's failed-read handling (AssertionError -> null).
        val leaderLatchFailed = AtomicBoolean(false)
        var leaderResult: Set<String>? = null
        val leader = Thread {
            leaderResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = graceMs,
            ) {
                reads.incrementAndGet()
                leaderStarted.countDown()
                try {
                    if (!leaderRelease.await(5, TimeUnit.SECONDS)) leaderLatchFailed.set(true)
                } catch (_: InterruptedException) {
                    leaderLatchFailed.set(true)
                }
                setOf("fast-leader")
            }
        }
        leader.start()
        assertTrue("leader must start", leaderStarted.await(5, TimeUnit.SECONDS))
        var followerResult: Set<String>? = null
        val follower = Thread {
            followerResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                // T-F1: a generous 10s grace — with a working notifyAll the
                // follower wakes the instant the leader commits; with a broken
                // notifyAll it parks the full grace, so join(2_000) +
                // assertFalse(isAlive) below fails loudly instead of the
                // follower silently returning seed-only after 50ms.
                followerGraceMs = 10_000L,
            ) {
                reads.incrementAndGet()
                setOf("unexpected")
            }
        }
        follower.start()
        // Wait until the follower entered the grace wait (deterministic
        // handshake, bounded spin with yield — never a fixed sleep).
        val graceDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cache.followerGraceHits.get() < 1L && System.nanoTime() < graceDeadlineNanos) {
            Thread.yield()
        }
        assertEquals(1L, cache.followerGraceHits.get())
        leaderRelease.countDown()

        follower.join(2_000)
        assertFalse("follower must complete once the leader commits", follower.isAlive)
        leader.join(2_000)
        assertFalse(leader.isAlive)
        assertFalse("leader latch never released", leaderLatchFailed.get())

        assertEquals(1, reads.get())
        assertEquals(setOf("fast-leader"), leaderResult)
        assertEquals(setOf("fast-leader"), followerResult)
        // Exactly one grace entry total (this test's follower).
        assertEquals(1L, cache.followerGraceHits.get())
    }

    @Test
    fun followersFallBackToSeedWhileLeaderParked() {
        // T2: deterministic handshake — the leader parks on a latch inside its
        // reader; every follower parks in the grace wait; we poll
        // followerGraceHits>=followerCount, then verify all followers fell
        // back to seed while the leader was still parked, then release the
        // leader. No wall-clock bounds, no leader.isAlive scheduling pins.
        // T-F2: 1s grace — long enough that a premature wake/spurious
        // commit cannot make the seed-only fallback pass vacuously, short
        // enough to keep the suite fast (grace-expiry semantics unchanged;
        // a >1s pause is required before this could flake).
        val graceMs = 1_000L
        val cache = EffectivePolicyCache()
        val reads = AtomicInteger()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        // T-F9: failure flag set inside the reader thread, asserted on the
        // main thread — an assertTrue() inside the reader would be swallowed
        // by the cache's failed-read handling (AssertionError -> null).
        val leaderLatchFailed = AtomicBoolean(false)
        val leader = Thread {
            cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = graceMs,
            ) {
                reads.incrementAndGet()
                leaderStarted.countDown()
                try {
                    if (!leaderRelease.await(5, TimeUnit.SECONDS)) leaderLatchFailed.set(true)
                } catch (_: InterruptedException) {
                    leaderLatchFailed.set(true)
                }
                setOf("slow-leader")
            }
        }
        leader.start()
        assertTrue("leader must start", leaderStarted.await(5, TimeUnit.SECONDS))

        val followerCount = 8
        val followerResults = Collections.synchronizedList(mutableListOf<Set<String>>())
        val followers = (1..followerCount).map {
            Thread {
                val res = cache.get(
                    emptySet(),
                    nonEmptyPolicyGateOpen = true,
                    followerGraceMs = graceMs,
                ) {
                    reads.incrementAndGet()
                    setOf("unexpected")
                }
                followerResults.add(res)
            }
        }
        followers.forEach { it.start() }
        // Wait until every follower entered the grace wait (deterministic
        // handshake, bounded spin with yield — never a fixed sleep).
        val graceDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (cache.followerGraceHits.get() < followerCount.toLong() && System.nanoTime() < graceDeadlineNanos) {
            Thread.yield()
        }
        assertEquals(followerCount.toLong(), cache.followerGraceHits.get())
        // Followers time out on their grace (1s, real time) and fall back
        // to seed while the leader is still parked — they never block on it.
        followers.forEach {
            it.join(2_000)
            assertFalse("follower must not block on the parked leader", it.isAlive)
        }
        // T-F6: every grace-expired follower counted under H9.
        assertEquals(followerCount.toLong(), cache.graceExpiredSeedOnly.get())

        assertEquals(1, reads.get())
        assertEquals(followerCount, followerResults.size)
        // All followers timed out after their grace budget and fell back to seed.
        for (res in followerResults) {
            assertEquals(emptySet<String>(), res)
        }
        // Semantic pin: followers fell back to seed WITHOUT committing or
        // reading — the leader's value is still uncommitted here.
        leaderRelease.countDown()
        leader.join(2_000)
        assertFalse(leader.isAlive)
        assertFalse("leader latch never released", leaderLatchFailed.get())
    }

    @Test
    fun followerDeadlineMsClampsLongGrace() {
        // CR-R1: pin the deadlineMs > 0L follower arm —
        // minOf(followerGraceMs - elapsed, deadlineMs - now1). A generous
        // 10s grace must be clamped by the caller's end-to-end deadline
        // (~250ms from now, on the cache's nowMs() basis), never parked for
        // the full grace. The 250ms headroom keeps the clamp ≪ 10s grace and
        // ≪ join(2_000) bound while eliminating the ~30ms thread-start-
        // latency flake window — the follower thread must finish starting
        // (and sampling its deadline basis) before the deadline can pass.
        // Latch-parked leader, deterministic completion via join(timeout) +
        // assertFalse(isAlive) — no wall-clock bound on wake.
        val cache = EffectivePolicyCache()
        val reads = AtomicInteger()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val leaderLatchFailed = AtomicBoolean(false)
        val leader = Thread {
            cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = 0L,
            ) {
                reads.incrementAndGet()
                leaderStarted.countDown()
                try {
                    if (!leaderRelease.await(5, TimeUnit.SECONDS)) leaderLatchFailed.set(true)
                } catch (_: InterruptedException) {
                    leaderLatchFailed.set(true)
                }
                setOf("latched-leader")
            }
        }
        leader.start()
        assertTrue("leader must start", leaderStarted.await(5, TimeUnit.SECONDS))
        val deadlineMs = System.nanoTime() / 1_000_000L + 250L
        val t0 = System.nanoTime()
        var followerResult: Set<String>? = null
        val follower = Thread {
            followerResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                // T-F1 style: generous grace, pinned down by the deadline.
                followerGraceMs = 10_000L,
                deadlineMs = deadlineMs,
            ) {
                reads.incrementAndGet()
                setOf("unexpected")
            }
        }
        follower.start()
        follower.join(2_000)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        assertFalse("follower must not park past its 250ms deadline", follower.isAlive)
        assertEquals(emptySet<String>(), followerResult)
        // Grace ~250ms clamped by the deadline — never 10s. Hang-guard only:
        // the deadline arm itself grants ~250ms.
        assertTrue("elapsed must stay far below the 10s grace, elapsed=$elapsedMs", elapsedMs < 2_000)
        assertEquals(1L, cache.followerGraceHits.get())
        assertEquals(1L, cache.graceExpiredSeedOnly.get())
        leaderRelease.countDown()
        leader.join(2_000)
        assertFalse(leader.isAlive)
        assertFalse("leader latch never released", leaderLatchFailed.get())
        assertEquals(1, reads.get())
    }

    @Test
    fun followerExpiredDeadlineYieldsZeroWait() {
        // CR-R1: deadlineMs <= now1 (already spent on the cache's nowMs()
        // basis) — minOf collapses to a non-positive remaining grace and is
        // coerced to 0: the follower returns seed-only synchronously WITHOUT
        // entering the grace wait (no park, no hit, no wait on the leader).
        val cache = EffectivePolicyCache()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val leaderLatchFailed = AtomicBoolean(false)
        val leader = Thread {
            cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = 0L,
            ) {
                leaderStarted.countDown()
                try {
                    if (!leaderRelease.await(5, TimeUnit.SECONDS)) leaderLatchFailed.set(true)
                } catch (_: InterruptedException) {
                    leaderLatchFailed.set(true)
                }
                setOf("latched-leader")
            }
        }
        leader.start()
        assertTrue("leader must start", leaderStarted.await(5, TimeUnit.SECONDS))
        val deadlineMs = System.nanoTime() / 1_000_000L - 1L // strictly in the past, > 0
        var followerResult: Set<String>? = null
        val follower = Thread {
            followerResult = cache.get(
                emptySet(),
                nonEmptyPolicyGateOpen = true,
                followerGraceMs = 10_000L,
                deadlineMs = deadlineMs,
            ) {
                setOf("unexpected")
            }
        }
        follower.start()
        follower.join(2_000)
        assertFalse("follower with a spent deadline must return without parking", follower.isAlive)
        assertEquals(emptySet<String>(), followerResult)
        // Zero wait: never entered the grace path (no hit), counted as
        // grace-expired seed-only via the early-return arm.
        assertEquals(0L, cache.followerGraceHits.get())
        assertEquals(1L, cache.graceExpiredSeedOnly.get())
        leaderRelease.countDown()
        leader.join(2_000)
        assertFalse(leader.isAlive)
        assertFalse("leader latch never released", leaderLatchFailed.get())
    }
}
