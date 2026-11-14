// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Phase3: follower grace re-check, loser-drop under invalidate, contention
// bypassing failureCooldown, AssertionError reader, nowMs-throw fail-closed.
class EffectivePolicyCachePhase3Test {

    @Test
    fun loserDropDuringInvalidateFallsBackToSeedOnly() {
        val cache = EffectivePolicyCache()
        assertEquals(setOf("a"), cache.get(emptySet(), true) { setOf("a") })
        cache.invalidate()
        // Leader blocks 200ms; second invalidate fires mid-flight; the stale
        // leader must discard (gen mismatch) and return seed-only.
        // Deterministic handshake: the invalidate only lands once the reader
        // is actually in flight (entered latch), never on a wall-clock guess.
        val readerEntered = CountDownLatch(1)
        var leaderResult: Set<String>? = null
        val leader = Thread {
            leaderResult = cache.get(emptySet(), true) {
                readerEntered.countDown()
                Thread.sleep(200)
                setOf("b")
            }
        }
        leader.start()
        assertTrue("reader must enter before invalidate", readerEntered.await(5, TimeUnit.SECONDS))
        cache.invalidate()
        leader.join(5_000)
        assertFalse("leader must finish under load", leader.isAlive)
        assertEquals(emptySet<String>(), leaderResult)
        // After invalidate the next read is fresh (no stale resurrection).
        assertEquals(setOf("c"), cache.get(emptySet(), true) { setOf("c") })
    }

    @Test
    fun fastLeaderCollapsesFollowersToMergedValue() {
        // Leader commits in ~20ms; followers grace-wait 50ms, then re-check
        // valid and observe the merged value instead of seed-only.
        val cache = EffectivePolicyCache()
        val threads = 6
        val barrier = CyclicBarrier(threads)
        val reads = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<Set<String>>())
        val workers = (1..threads).map {
            Thread {
                barrier.await()
                results.add(
                    cache.get(emptySet(), true) {
                        reads.incrementAndGet()
                        Thread.sleep(20)
                        setOf("b")
                    },
                )
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(5_000) }
        workers.forEach { assertFalse("worker must finish under load", it.isAlive) }
        assertEquals(threads, results.size)
        assertEquals(1, reads.get())
        // At least the leader merged; followers either merged (grace hit) or
        // seed-only — never anything else, and fail-closed holds.
        for (r in results) {
            assertTrue(r == setOf("b") || r == emptySet<String>())
        }
        assertTrue(results.contains(setOf("b")))
    }

    // P1-2 (deterministic in-lock budget recomputation): each nowMs sample
    // advances the fake clock by 25ms. The follower's entry budget is still
    // positive (first sample now0=25, second now1=50 -> remainingGrace=25ms;
    // the leader consumes 25ms too), but when it re-samples nowMs() inside
    // the parking lock its own window has fully lapsed (elapsed2 = 50ms), so
    // it must NOT park at all — followerGraceHits stays 0 and it falls back
    // to seed-only via the graceExpired arm. This pins the in-lock
    // recomputation deterministically (no threads observe timing luck).
    @Test
    fun autoAdvancingClockSkipsGraceWhenBudgetLapsesBeforeParking() {
        val time = java.util.concurrent.atomic.AtomicLong(0)
        val cache = EffectivePolicyCache(nowMs = { time.addAndGet(25) })
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val leader = Thread {
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true, followerGraceMs = 50) {
                leaderStarted.countDown()
                try {
                    leaderRelease.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
                setOf("leader")
            }
        }
        leader.start()
        assertTrue("leader must enter reader", leaderStarted.await(5, TimeUnit.SECONDS))
        val followerResult = cache.get(emptySet(), nonEmptyPolicyGateOpen = true, followerGraceMs = 50) {
            setOf("follower-read")
        }
        assertEquals(emptySet<String>(), followerResult)
        // Never parked: the in-lock now2 re-sample proved the budget had lapsed.
        assertEquals(0L, cache.followerGraceHits.get())
        assertEquals(1L, cache.graceExpiredSeedOnly.get())
        leaderRelease.countDown()
        leader.join(2_000)
        assertFalse(leader.isAlive)
    }

    // P1-2 (herd serialization, latch-frozen leader): N=4 followers arrive at
    // staggered injected-clock times (0 / 30 / 45 / 60) against a leader that
    // never commits inside the grace window. Each follower computes its OWN
    // bounded budget on entry AND re-checks it inside the parking lock; none
    // may park meaningfully beyond its own window.
    // Honest limit: exact per-follower park duration cannot be asserted with
    // sub-millisecond determinism (wait() wakes on wall-clock granularity and
    // legal spurious wakeups), so the per-follower wall-clock bound is asserted
    // with a generous slack rather than equality; the tight in-lock
    // recomputation itself is pinned deterministically by
    // autoAdvancingClockSkipsGraceWhenBudgetLapsesBeforeParking. Also unpinned
    // here: positive-budget (elapsed2 > 0, budget2 > 0) re-clamp amounts — the
    // recompute is exercised only at its skip-edge; the positive-budget arm
    // (partial park, not full skip) has no suite-wide pin yet.
    @Test
    fun herdFollowersParkOnlyTheirOwnBoundedWindow() {
        val time = java.util.concurrent.atomic.AtomicLong(0)
        val cache = EffectivePolicyCache(nowMs = { time.get() })
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val deadline = 80L // shared end-to-end deadline (fake-clock basis)
        val leader = Thread {
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true, followerGraceMs = 50, deadlineMs = deadline) {
                leaderStarted.countDown()
                try {
                    leaderRelease.await(10, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
                setOf("leader")
            }
        }
        leader.start()
        assertTrue("leader must enter reader", leaderStarted.await(5, TimeUnit.SECONDS))
        // (arrival fake-time ms, expected budget after entry + in-lock recomputation)
        val arrivals = listOf(0L to 50L, 30L to 50L, 45L to 35L, 60L to 20L)
        val durations = LongArray(arrivals.size)
        for ((index, arrival) in arrivals.withIndex()) {
            val (arrivalMs, _) = arrival
            time.set(arrivalMs)
            val start = System.nanoTime()
            val result = cache.get(emptySet(), nonEmptyPolicyGateOpen = true, followerGraceMs = 50, deadlineMs = deadline) {
                setOf("follower-read")
            }
            durations[index] = (System.nanoTime() - start) / 1_000_000L
            // Fail-closed: never the leader's (never-committed) value, seed only.
            assertEquals(emptySet<String>(), result)
        }
        // Stagger serialized arrivals => each follower entered with a full
        // grace window on the frozen clock, budget clamped only by the shared
        // deadline: 50/50/35/20ms. None may park much beyond its own budget.
        for (i in arrivals.indices) {
            val budget = arrivals[i].second
            val bound = 2 * budget + 250L // ~2x budget + generous CI slack (documented)
            assertTrue(
                "follower #$i stagger-budget ${budget}ms but parked ${durations[i]}ms (bound ${bound}ms)",
                durations[i] < bound,
            )
        }
        assertEquals(4L, cache.followerGraceHits.get())
        assertEquals(4L, cache.graceExpiredSeedOnly.get())
        leaderRelease.countDown()
        leader.join(2_000)
        assertFalse(leader.isAlive)
    }

    @Test
    fun contentionDoesNotRenewFailureCooldown() {
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        var reads = 0
        // First: contention (OrThrow-style) returns seed without cooldown.
        assertEquals(
            emptySet<String>(),
            cache.get(emptySet(), true) {
                reads++
                throw PolicyRefreshContentionException("guard")
            },
        )
        assertEquals(1, reads)
        // Immediate retry (still inside the 5s window) must invoke the reader
        // again — contention never set lastFailureMs.
        time = 1_100L
        assertEquals(
            setOf("b"),
            cache.get(emptySet(), true) {
                reads++
                setOf("b")
            },
        )
        assertEquals(2, reads)
    }

    @Test
    fun ioFailureStillEntersCooldown() {
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        var reads = 0
        assertEquals(
            emptySet<String>(),
            cache.get(emptySet(), true) {
                reads++
                null
            },
        )
        assertEquals(1, reads)
        // Inside the window: throttled, reader not invoked.
        time = 1_100L
        assertEquals(
            emptySet<String>(),
            cache.get(emptySet(), true) {
                reads++
                setOf("b")
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun assertionErrorReaderIsFailedReadAndRetried() {
        var reads = 0
        val time = java.util.concurrent.atomic.AtomicLong(0)
        val cache = EffectivePolicyCache(nowMs = { time.addAndGet(6_000) })
        assertEquals(
            setOf("com.seed.app"),
            cache.get(setOf("com.seed.app"), true) {
                reads++
                throw AssertionError("boom")
            },
        )
        assertEquals(1, reads)
        assertFalse(cache.isCached())
        assertEquals(
            setOf("com.seed.app", "com.remote.app"),
            cache.get(setOf("com.seed.app"), true) {
                reads++
                setOf("com.remote.app")
            },
        )
        assertEquals(2, reads)
    }

    // P3-7 (interrupt-restore, cache arm): a reader throwing
    // InterruptedException is a failed read (fail-closed seed-only) AND the
    // interrupt bit must be restored on the calling thread so callers can
    // observe cancellation. try/finally saves/restores the test thread's
    // interrupt status so no other test in this JVM fork observes a poisoned
    // bit.
    @Test
    fun interruptRestore_readerInterruptedFallsBackToSeedAndRestoresInterruptBit() {
        val wasInterrupted = Thread.interrupted() // save + clear
        try {
            var reads = 0
            val cache = EffectivePolicyCache()
            val result = cache.get(setOf("com.seed.app"), true) {
                reads++
                throw InterruptedException("cut")
            }
            assertEquals(setOf("com.seed.app"), result)
            assertEquals(1, reads)
            assertFalse(cache.isCached())
            assertTrue(
                "interrupt bit must be restored by the cache",
                Thread.currentThread().isInterrupted,
            )
        } finally {
            Thread.interrupted() // clear the bit production restored
            if (wasInterrupted) Thread.currentThread().interrupt() // restore pre-test state
        }
    }

    // P3-7 (interrupt-restore, bounded-read arm): PolicyRefreshBudget
    // .readBoundedOrThrow restores the CALLER's interrupt bit when the
    // calling thread is interrupted while blocked in future.get().
    //
    // Precise scope — what this test pins: the caller-interrupted-during-get()
    // restore arm of the OBJECT-LEVEL helper (PolicyRefreshBudget.readBoundedOrThrow).
    // The production gate paths (PolicyRefreshGate.doSubmitOrThrow /
    // doProbeSubmitOrThrow) share the same shape: a future.get() wrapped in
    // catch (t: Throwable) with the interrupt bit restored iff
    // t is InterruptedException.
    //
    // Honest scope note (what is NOT pinnable here): a READER throwing
    // InterruptedException on the executor worker surfaces to the caller via
    // FutureTask as ExecutionException(cause=InterruptedException) — NOT as
    // InterruptedException — so the `t is InterruptedException` restore arm
    // never fires for that shape and the bit is INTENTIONALLY never restored
    // by production (the caller was never interrupted; restoring would
    // fabricate a cancellation). This test pins the REACHABLE half
    // deterministically: caller interrupted with the reader latch-blocked:
    // get() parks in awaitDone, observes the interrupted flag, and
    // production's restore arm re-sets the bit.
    @Test
    fun interruptRestore_readBoundedOrThrowInterruptedGetRestoresCallerInterruptBit() {
        val wasInterrupted = Thread.interrupted() // save + clear
        val hold = CountDownLatch(1)
        val executor = PolicyRefreshBudget.newPolicyRefreshExecutor("test-interrupt-restore")
        try {
            Thread.currentThread().interrupt() // caller interrupted before/during the call
            val result = PolicyRefreshBudget.readBoundedOrThrow(
                reader = { hold.await() ; setOf("x") },
                timeoutMs = 5_000,
                executor = executor,
            )
            assertNull(result) // caller interrupted -> failed read, fail-closed
            assertTrue(
                "interrupt bit must be restored by readBoundedOrThrow",
                Thread.currentThread().isInterrupted,
            )
        } finally {
            hold.countDown()
            Thread.interrupted() // clear whatever state remains
            if (wasInterrupted) Thread.currentThread().interrupt() // restore pre-test state
            try {
                executor.shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun nowMsThrowingFailsClosedToCachedOrSeed() {
        val cache = EffectivePolicyCache(nowMs = { throw IllegalStateException("clock") })
        // No cached value + broken clock -> seed-only, no throw.
        assertEquals(setOf("s"), cache.get(setOf("s"), true) { setOf("r") })
    }
}
