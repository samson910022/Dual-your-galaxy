// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

// JVM unit tests for the pure effective-policy cache (EffectivePolicyCache).
class EffectivePolicyCacheTest {

    @Test
    fun firstGetComputesAndCaches() {
        var reads = 0
        val cache = EffectivePolicyCache()
        val first = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            setOf("com.remote.app")
        }
        assertEquals(setOf("com.seed.app", "com.remote.app"), first)
        assertEquals(1, reads)
    }

    @Test
    fun secondGetReturnsCachedValue() {
        var reads = 0
        val cache = EffectivePolicyCache()
        cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            setOf("com.remote.app")
        }
        val second = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            setOf("com.remote.app")
        }
        assertEquals(setOf("com.seed.app", "com.remote.app"), second)
        assertEquals(1, reads)
    }

    @Test
    fun invalidateForcesRecompute() {
        var reads = 0
        val cache = EffectivePolicyCache()
        cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
            reads++
            setOf("a")
        }
        cache.invalidate()
        assertEquals(
            setOf("a"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("a")
            },
        )
        assertEquals(2, reads)
    }

    @Test
    fun nullReadIsNotCachedAndReturnsSeed() {
        var reads = 0
        val time = java.util.concurrent.atomic.AtomicLong(0)
        val cache = EffectivePolicyCache(nowMs = { time.addAndGet(6_000) })
        val first = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            null
        }
        assertEquals(setOf("com.seed.app"), first)
        assertEquals(1, reads)

        // A failed read is not cached: the reader is retried on the next get
        // (cooldown elapsed).
        val second = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            null
        }
        assertEquals(setOf("com.seed.app"), second)
        assertEquals(2, reads)
    }

    @Test
    fun nullThenValueCachesOnSuccess() {
        var reads = 0
        val time = java.util.concurrent.atomic.AtomicLong(0)
        val cache = EffectivePolicyCache(nowMs = { time.addAndGet(6_000) })
        assertEquals(
            setOf("com.seed.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
                reads++
                null
            },
        )
        assertEquals(
            setOf("com.seed.app", "com.remote.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("com.remote.app")
            },
        )
        assertEquals(2, reads)

        // Now cached: the reader is not called again.
        assertEquals(
            setOf("com.seed.app", "com.remote.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("com.remote.app")
            },
        )
        assertEquals(2, reads)
    }

    @Test
    fun emptySeedAndRemoteReturnsEmpty() {
        var reads = 0
        val cache = EffectivePolicyCache()
        val result = cache.get(emptySet()) {
            reads++
            emptySet()
        }
        assertEquals(emptySet<String>(), result)
        // Stage C: gate closed short-circuits without touching the reader.
        assertEquals(0, reads)
    }

    @Test
    fun emptyRemoteReadIsCachedLikeAnySuccess() {
        var reads = 0
        val cache = EffectivePolicyCache()
        val first = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            emptySet()
        }
        assertEquals(setOf("com.seed.app"), first)
        // An empty-but-successful read is still a success: it must be cached,
        // not retried (kills `if (remote != null && remote.isNotEmpty())`).
        val second = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            emptySet()
        }
        assertEquals(setOf("com.seed.app"), second)
        assertEquals(1, reads)
    }

    @Test
    fun invalidateUsesNewRemoteValue() {
        var reads = 0
        val cache = EffectivePolicyCache()
        assertEquals(
            setOf("a"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("a")
            },
        )
        cache.invalidate()
        // Kills both "discard new value" (would return "a") and "accumulate old
        // value" (would return "a" + "b").
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
    fun invalidateThenFailedReadReturnsSeedNotStaleCache() {
        val cache = EffectivePolicyCache()
        assertEquals(
            setOf("com.seed.app", "com.remote.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) { setOf("com.remote.app") },
        )
        cache.invalidate()
        // After invalidation a failed read must not resurrect the stale cache.
        assertEquals(
            setOf("com.seed.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) { null },
        )
    }

    @Test
    fun failedReadStillSanitizesSeed() {
        val cache = EffectivePolicyCache()
        assertEquals(
            setOf("com.ok.app"),
            cache.get(setOf("com.ok.app", "bad pkg"), nonEmptyPolicyGateOpen = true) { null },
        )
    }

    @Test
    fun corruptRemoteSetIsSanitizedAndCached() {
        var reads = 0
        val cache = EffectivePolicyCache()
        // A corrupt prefs entry can carry a non-String element; sanitize must
        // drop it instead of throwing out of the hook chain.
        @Suppress("UNCHECKED_CAST")
        val corrupt = setOf("com.ok.app", 42) as Set<String>
        assertEquals(
            setOf("com.ok.app"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                corrupt
            },
        )
        // The sanitized set is a successful read and must be cached.
        assertEquals(
            setOf("com.ok.app"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                corrupt
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun hostileRemoteSetThatThrowsIsFailedRead() {
        var reads = 0
        val time = java.util.concurrent.atomic.AtomicLong(0)
        val cache = EffectivePolicyCache(nowMs = { time.addAndGet(6_000) })
        val hostile = object : AbstractSet<String>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<String> = throw IllegalStateException("boom")
        }
        assertEquals(
            setOf("com.seed.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
                reads++
                hostile
            },
        )
        // The throwing set is treated as a failed read: not cached, retried.
        assertEquals(
            setOf("com.seed.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
                reads++
                hostile
            },
        )
        assertEquals(2, reads)
    }

    // Stage C single-flight: exactly one reader call under concurrency. The
    // leader performs the read (sleep guarantees overlap so followers arrive
    // while it is in flight); followers never call reader and return the
    // seed-only fallback (or the merged snapshot if they arrive after the
    // commit), so each result is either merged or seed-only.
    @Test
    fun concurrentGetsSingleFlightExactlyOnce() {
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val reads = AtomicInteger()
        val cache = EffectivePolicyCache()
        val results = Collections.synchronizedList(mutableListOf<Set<String>>())
        val seed = setOf("com.seed.app")
        val merged = setOf("com.seed.app", "com.remote.app")
        val workers = (1..threads).map {
            Thread {
                barrier.await()
                results.add(
                    cache.get(seed, nonEmptyPolicyGateOpen = true) {
                        reads.incrementAndGet()
                        Thread.sleep(200)
                        setOf("com.remote.app")
                    },
                )
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(threads, results.size)
        for (result in results) {
            assertTrue(result == merged || result == seed)
        }
        assertEquals(1, reads.get())
    }

    // Stage C: after an explicit invalidate, a concurrent herd still performs
    // exactly one read; followers fall back to seed-only (empty here).
    @Test
    fun invalidateHerdSingleFlightExactlyOnce() {
        val cache = EffectivePolicyCache()
        assertEquals(
            setOf("a"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("a") },
        )
        cache.invalidate()
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val reads = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<Set<String>>())
        val workers = (1..threads).map {
            Thread {
                barrier.await()
                results.add(
                    cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                        reads.incrementAndGet()
                        Thread.sleep(200)
                        setOf("b")
                    },
                )
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(threads, results.size)
        for (result in results) {
            assertTrue(result == setOf("b") || result == emptySet<String>())
        }
        assertEquals(1, reads.get())
        // The leader committed: a follow-up get is served cached, no new read.
        assertEquals(
            setOf("b"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads.incrementAndGet()
                setOf("c")
            },
        )
        assertEquals(1, reads.get())
    }

    // Stage C: after TTL expiry, a concurrent herd still performs exactly one
    // read; followers fall back to seed-only (empty here).
    @Test
    fun ttlHerdSingleFlightExactlyOnce() {
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        assertEquals(
            setOf("a"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("a") },
        )
        time = 1_000L + 60_000L
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val reads = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<Set<String>>())
        val workers = (1..threads).map {
            Thread {
                barrier.await()
                results.add(
                    cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                        reads.incrementAndGet()
                        Thread.sleep(200)
                        setOf("b")
                    },
                )
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(threads, results.size)
        for (result in results) {
            assertTrue(result == setOf("b") || result == emptySet<String>())
        }
        assertEquals(1, reads.get())
    }

    // Stage C: gate closed short-circuits to empty with zero reader calls and
    // no state change (a later gate-open read still performs a fresh read).
    @Test
    fun gateClosedShortCircuitsWithZeroReads() {
        var reads = 0
        val cache = EffectivePolicyCache()
        assertEquals(
            emptySet<String>(),
            cache.get(setOf("com.seed.app")) {
                reads++
                setOf("com.remote.app")
            },
        )
        assertEquals(0, reads)
        assertFalse(cache.isCached())
        // No state was populated: the first gate-open read still reads once.
        assertEquals(
            setOf("com.seed.app", "com.remote.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("com.remote.app")
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun cooldownThrottlesFailedReads() {
        var reads = 0
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            setOf("com.remote.app")
        }
        cache.invalidate()
        val r1 = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            null
        }
        assertEquals(setOf("com.seed.app"), r1)
        assertEquals(2, reads)

        val r2 = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            setOf("com.remote.app")
        }
        // After invalidation, the cooldown path must not resurrect the stale
        // pre-invalidation cached value: it falls back to the seed alone.
        assertEquals(setOf("com.seed.app"), r2)
        assertEquals(2, reads)

        time = 7_000
        val r3 = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            setOf("com.remote.app")
        }
        assertEquals(setOf("com.seed.app", "com.remote.app"), r3)
        assertEquals(3, reads)
    }

    @Test
    fun throwingReaderIsFailedReadAndRetried() {
        var reads = 0
        val time = java.util.concurrent.atomic.AtomicLong(0)
        val cache = EffectivePolicyCache(nowMs = { time.addAndGet(6_000) })
        val first = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            throw RuntimeException("boom")
        }
        assertEquals(setOf("com.seed.app"), first)
        assertEquals(1, reads)

        val second = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            throw RuntimeException("boom")
        }
        assertEquals(setOf("com.seed.app"), second)
        assertEquals(2, reads)
    }

    @Test
    fun cooldownWithNoPriorGoodValueReturnsSeed() {
        var reads = 0
        var time = 0L
        val cache = EffectivePolicyCache(nowMs = { time })
        val first = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            null
        }
        assertEquals(setOf("com.seed.app"), first)
        assertEquals(1, reads)

        time = 1_000L
        val second = cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) {
            reads++
            null
        }
        assertEquals(setOf("com.seed.app"), second)
        // Cooldown now triggers even at epoch 0: the second read is throttled.
        assertEquals(1, reads)
    }

    @Test
    fun invalidateResetsCooldownAndAllowsImmediateRetry() {
        var reads = 0
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        // First a successful read, then invalidate, then a failed read that
        // starts a cooldown.
        cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { reads++; setOf("a") }
        cache.invalidate()
        val r1 = cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { reads++; null }
        assertEquals(emptySet<String>(), r1)
        assertEquals(2, reads)
        // Within the cooldown window, a second read would normally be
        // throttled; but an explicit invalidate resets the cooldown and lets
        // the reader run again immediately.
        cache.invalidate()
        val r2 = cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { reads++; setOf("b") }
        assertEquals(setOf("b"), r2)
        assertEquals(3, reads)
    }

    @Test
    fun isCachedAndHadGoodValueTransitions() {
        val cache = EffectivePolicyCache()
        assertFalse(cache.isCached())
        assertFalse(cache.hadGoodValue())
        cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("a") }
        assertTrue(cache.isCached())
        assertTrue(cache.hadGoodValue())
        cache.invalidate()
        assertFalse(cache.isCached())
        assertTrue(cache.hadGoodValue())
        // A failed read with no prior good value keeps both false.
        val c2 = EffectivePolicyCache()
        c2.get(emptySet(), nonEmptyPolicyGateOpen = true) { null }
        assertFalse(c2.isCached())
        assertFalse(c2.hadGoodValue())
    }

    @Test
    fun failureLoggingTransitionsOncePerRecovery() {
        val cache = EffectivePolicyCache()
        // First-ever failure logs.
        assertTrue(cache.shouldLogFailure())
        // Repeated failure does not log again.
        assertFalse(cache.shouldLogFailure())
        // A successful read resets the transition, so the next failure logs.
        cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("a") }
        assertTrue(cache.shouldLogFailure())
        // Invalidate resets the transition as well.
        cache.invalidate()
        assertTrue(cache.shouldLogFailure())
    }

    @Test
    fun cacheExpiresAfterMaxAgeAndRereads() {
        var reads = 0
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        assertEquals(setOf("a"), cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { reads++; setOf("a") })
        assertEquals(1, reads)
        // After the bounded freshness TTL, a valid cache is re-read even
        // without an explicit invalidation.
        time = 1_000L + 60_000L
        assertEquals(setOf("b"), cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { reads++; setOf("b") })
        assertEquals(2, reads)
    }

    @Test
    fun ttlExpiryThenFailedReadFallsBackToSeed() {
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        assertEquals(setOf("a"), cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("a") })
        // Expire the cache, then a failed re-read must not resurrect the stale
        // value: it falls back to the seed alone (fail-closed).
        time = 1_000L + 60_000L
        assertEquals(setOf("com.seed.app"), cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) { null })
    }

    // 冷卻滑動回歸：連續失敗仍節流。四次 get、時間推進跨兩窗口，reads==2。
    // 第一次失敗建冷卻；第二次在窗內被節流；第三次跨窗重試再失敗（刷新冷卻）；
    // 第四次落在「舊窗口外、新窗口內」，唯有滑動刷新才會被節流（舊碼僅首錯
    // 設 lastFailureMs，第四次會誤重試致 reads==3）。
    @Test
    fun cooldownThrottlesConsecutiveFailures() {
        var reads = 0
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        val failing: () -> Set<String>? = { reads++; null }

        assertEquals(setOf("com.seed.app"), cache.get(setOf("com.seed.app"), true, failing))
        assertEquals(1, reads)

        time = 2_000L
        assertEquals(setOf("com.seed.app"), cache.get(setOf("com.seed.app"), true, failing))
        assertEquals(1, reads)

        time = 7_000L
        assertEquals(setOf("com.seed.app"), cache.get(setOf("com.seed.app"), true, failing))
        assertEquals(2, reads)

        // 舊窗口（自 1_000 起 5s）已過，新窗口（自 7_000 起 5s）內：必須節流。
        time = 8_000L
        assertEquals(setOf("com.seed.app"), cache.get(setOf("com.seed.app"), true, failing))
        assertEquals(2, reads)
    }

    @Test
    fun gateClosedNonEmptyRemoteReturnsEmpty() {
        // Default gate (closed): sanitized non-empty merge must not reach
        // callers even through the cache layer (observe-only).
        val cache = EffectivePolicyCache()
        assertEquals(
            emptySet<String>(),
            cache.get(setOf("com.seed.app")) { setOf("com.remote.app") },
        )
        assertEquals(
            emptySet<String>(),
            cache.get(emptySet()) { setOf("a") },
        )
    }

    @Test
    fun gateClosedSeedFallbackReturnsEmpty() {
        // Default gate (closed): seed-only fallback is also blocked while the
        // gate is closed (fail-closed to empty, not the seed alone).
        val cache = EffectivePolicyCache()
        assertEquals(
            emptySet<String>(),
            cache.get(setOf("com.seed.app")) { null },
        )
    }
}
