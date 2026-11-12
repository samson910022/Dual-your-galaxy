// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
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
        var leaderResult: Set<String>? = null
        val leader = Thread {
            leaderResult = cache.get(emptySet(), true) {
                Thread.sleep(200)
                setOf("b")
            }
        }
        leader.start()
        Thread.sleep(50)
        cache.invalidate()
        leader.join()
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
        workers.forEach { it.join() }
        assertEquals(threads, results.size)
        assertEquals(1, reads.get())
        // At least the leader merged; followers either merged (grace hit) or
        // seed-only — never anything else, and fail-closed holds.
        for (r in results) {
            assertTrue(r == setOf("b") || r == emptySet<String>())
        }
        assertTrue(results.contains(setOf("b")))
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

    @Test
    fun nowMsThrowingFailsClosedToCachedOrSeed() {
        val cache = EffectivePolicyCache(nowMs = { throw IllegalStateException("clock") })
        // No cached value + broken clock -> seed-only, no throw.
        assertEquals(setOf("s"), cache.get(setOf("s"), true) { setOf("r") })
    }
}
