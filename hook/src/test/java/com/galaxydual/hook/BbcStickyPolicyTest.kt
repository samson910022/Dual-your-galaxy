// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Phase A P0 BBC sticky last-good JVM pins.
//
// BBC path (getBbc, sticky): failed reads keep the last successful snapshot
// across TTL expiry / invalidate / cooldown / contention / clock / deadline;
// only a successful empty WITH version proof (Cleared) may shrink. All
// fallback arms emit a throttled BBC log + counters; the sticky snapshot
// expires after BBC_STICKY_MAX_AGE_MS (fail-closed to empty).
//
// Whitelist path (get, stickyLastGood=false) is unchanged fail-closed:
// TTL/invalidate discard to seed, gate-closed short-circuits with zero reads.
class BbcStickyPolicyTest {

    private fun bbcCacheAt(time: Long, logs: MutableList<String> = mutableListOf()): Pair<EffectivePolicyCache, java.util.concurrent.atomic.AtomicLong> {
        val t = java.util.concurrent.atomic.AtomicLong(time)
        val c = EffectivePolicyCache(
            nowMs = { t.get() },
            onLog = { logs.add(it) },
            stickyLastGood = true,
        )
        return Pair(c, t)
    }

    @Test
    fun successNonEmptyWithoutVersionIsGoodBbc() {
        // Old-draft backward compat: non-empty WITHOUT version proof stays
        // Good (mergeable); only empty requires version proof for Cleared.
        val (cache, _) = bbcCacheAt(1_000L)
        val res = cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), null) })
        assertTrue(res is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("com.good.a"), (res as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    @Test
    fun ttlExpiryThenNullReturnsLastGoodBbc() {
        val (cache, time) = bbcCacheAt(1_000L)
        val good = cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) })
        assertTrue(good is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("com.good.a"), (good as EffectivePolicyCache.BbcPolicy.Good).packages)
        // Expire the 60s TTL, then a failed re-read must keep last-good.
        time.set(1_000L + 60_000L)
        val unk = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1L, cache.bbcStickyFallbackCount.get())
        assertTrue(cache.bbcUnknownCount.get() >= 1L)
    }

    @Test
    fun invalidateThenNullReturnsLastGoodBbc() {
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val unk = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun nullWithNoGoodReturnsEmptyBbc() {
        val (cache, _) = bbcCacheAt(1_000L)
        val unk = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun successEmptyCachesAndShrinksBbc() {
        var reads = 0
        val (cache, _) = bbcCacheAt(1_000L)
        val good = cache.getBbc(emptySet(), reader = { reads++; Pair(setOf("com.good.a"), 1L) })
        assertTrue(good is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(1, reads)
        // Intentional clear (empty + version): caches and shrinks. Invalidate
        // first to force a re-read (a valid cache serves without IPC).
        cache.invalidate()
        val cleared = cache.getBbc(emptySet(), reader = { reads++; Pair(emptySet(), 2L) })
        assertTrue(cleared is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(2L, (cleared as EffectivePolicyCache.BbcPolicy.Cleared).version)
        assertEquals(2, reads)
        // Now cached: the reader is not retried.
        val again = cache.getBbc(emptySet(), reader = { reads++; Pair(setOf("com.other"), 3L) })
        assertTrue(again is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(2, reads)
    }

    @Test
    fun successEmptyWithoutVersionKeepsLastGoodBbc() {
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        // Empty WITHOUT version proof is Unknown: never shrink, keep last-good.
        cache.invalidate()
        val unk = cache.getBbc(emptySet(), reader = { Pair(emptySet(), null) })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        // A later successful non-empty still merges (sticky intact; invalidate
        // to bypass the failure cooldown from the Unknown above).
        cache.invalidate()
        val good2 = cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) })
        assertTrue(good2 is EffectivePolicyCache.BbcPolicy.Good)
    }

    @Test
    fun contentionKeepsLastGoodBbc() {
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val unk = cache.getBbc(emptySet(), reader = { throw PolicyRefreshContentionException("guard") })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun clockFailureKeepsLastGoodBbc() {
        var throwing = false
        var time = 1_000L
        val cache = EffectivePolicyCache(
            nowMs = { if (throwing) throw RuntimeException("clock dead") else time },
            stickyLastGood = true,
        )
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        throwing = true
        var readerCalled = false
        val unk = cache.getBbc(emptySet(), reader = { readerCalled = true; Pair(null, null) })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertFalse(readerCalled)
    }

    @Test
    fun throwingReaderKeepsLastGoodBbc() {
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val unk = cache.getBbc(emptySet(), reader = { throw RuntimeException("binder dead") })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun stickyCapExpiresToEmptyBbc() {
        val (cache, time) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        time.set(1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS + 1L)
        val unk = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
    }

    @Test
    fun bbcFallbackArmsEmitThrottledLog() {
        val logs = mutableListOf<String>()
        val (cache, _) = bbcCacheAt(1_000L, logs)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        // Three Unknown fallbacks at the same instant: counters advance 3x,
        // but the throttled log fires at most once per 5s window.
        repeat(3) {
            cache.invalidate()
            cache.getBbc(emptySet(), reader = { Pair(null, null) })
        }
        assertTrue(cache.bbcStickyFallbackCount.get() >= 3L)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("BBC sticky fallback"))
    }

    @Test
    fun whitelistGateClosedStillEmptyRegression() {
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
    }

    @Test
    fun whitelistTtlExpiryStillFailClosedToSeed() {
        // Old whitelist semantics pinned: TTL expiry + failed read falls back
        // to seed, never resurrects stale (stickyLastGood=false default).
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        assertEquals(setOf("a"), cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("a") })
        time = 1_000L + 60_000L
        assertEquals(
            setOf("com.seed.app"),
            cache.get(setOf("com.seed.app"), nonEmptyPolicyGateOpen = true) { null },
        )
    }

    @Test
    fun bbcGateClosedReturnsClearedWithoutIo() {
        var reads = 0
        val (cache, _) = bbcCacheAt(1_000L)
        val res = cache.getBbc(
            emptySet(),
            bbcGateOpen = false,
            reader = { reads++; Pair(setOf("com.good.a"), 1L) },
        )
        assertTrue(res is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(0, reads)
    }

    @Test
    fun bbcFollowerExpiredDeadlineKeepsLastGood() {
        val cache = EffectivePolicyCache(stickyLastGood = true)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val reads = AtomicInteger(0)
        val leader = Thread {
            cache.getBbc(emptySet(), followerGraceMs = 0L, reader = {
                reads.incrementAndGet()
                leaderStarted.countDown()
                leaderRelease.await(5, TimeUnit.SECONDS)
                Pair(setOf("leader"), 9L)
            })
        }
        leader.start()
        assertTrue(leaderStarted.await(5, TimeUnit.SECONDS))
        // Follower with an already-spent deadline must not park: immediate
        // sticky fallback (Unknown with last-good), no extra reader call.
        val deadlineMs = System.nanoTime() / 1_000_000L - 1L
        var followerResult: EffectivePolicyCache.BbcPolicy? = null
        val follower = Thread {
            followerResult = cache.getBbc(
                emptySet(),
                followerGraceMs = 10_000L,
                deadlineMs = deadlineMs,
                reader = { reads.incrementAndGet(); Pair(setOf("unexpected"), 10L) },
            )
        }
        follower.start()
        follower.join(2_000)
        assertFalse(follower.isAlive)
        assertTrue(followerResult is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (followerResult as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, reads.get())
        leaderRelease.countDown()
        leader.join(2_000)
        assertFalse(leader.isAlive)
    }

    @Test
    fun cooldownServesStickyWithoutExtraReadBbc() {
        // Good -> failed read arms the 5s cooldown -> immediate second read
        // hits cooldown WITHOUT calling reader (readerCalls==1 for the failing
        // phase) and returns Unknown(sticky). No explicit invalidate between
        // the two failing reads.
        val (cache, time) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        var readerCalls = 0
        val first = cache.getBbc(emptySet(), reader = { readerCalls++; Pair(null, null) })
        assertTrue(first is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (first as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, readerCalls)
        // Immediate second read (same instant, within 5s cooldown): reader
        // must NOT be invoked again.
        val second = cache.getBbc(emptySet(), reader = { readerCalls++; Pair(null, null) })
        assertTrue(second is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (second as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, readerCalls)
        // Cooldown is time-based, not invalidate-based: lastFailureMs advanced
        // on the first failure, so the second call is a pure cooldown hit.
        assertTrue(time.get() - 1_000L < 5_000L)
    }

    @Test
    fun clearedRequiresStrictlyIncreasingVersionBbc() {
        // Security MEDIUM: Cleared with 0 / duplicate / replay is rejected as
        // Unknown (sticky, never shrunk); only strictly increasing accepts.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 5L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        // Reject 0 (never a valid proof): Unknown, sticky intact.
        val zero = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 0L) })
        assertTrue(zero is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (zero as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        // Accept 6 (strictly increasing): Cleared.
        cache.invalidate()
        val ok = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) })
        assertTrue(ok is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(6L, (ok as EffectivePolicyCache.BbcPolicy.Cleared).version)
        // Duplicate 6: rejected as Unknown (cached Cleared is NOT overwritten).
        cache.invalidate()
        val dup = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) })
        assertTrue(dup is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (dup as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        // Replay 5 (older than seen 6): rejected as Unknown.
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 5L) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (replay as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun clearedVersionWraparoundAcceptedBbc() {
        // Writer wraps to 1 after Long.MAX_VALUE-1/MAX: version 1 after a
        // huge seen version is the ONLY accepted decreasing transition.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), Long.MAX_VALUE) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val wrapped = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 1L) })
        assertTrue(wrapped is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(1L, (wrapped as EffectivePolicyCache.BbcPolicy.Cleared).version)
        // But 1 after a small seen version is a replay, not a wraparound.
        val (cache2, _) = bbcCacheAt(1_000L)
        assertTrue(cache2.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 5L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache2.invalidate()
        val replayOne = cache2.getBbc(emptySet(), reader = { Pair(emptySet(), 1L) })
        assertTrue(replayOne is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("com.good.a"), (replayOne as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun bbcInjectorAppendIsIdempotent() {
        val original = listOf("com.samsung.a")
        val policy = linkedSetOf("com.policy.x", "com.policy.y")
        val once = BbcInjector.mergeAvailableList(original, policy, { true }, { true })
        val twice = BbcInjector.mergeAvailableList(once, policy, { true }, { true })
        assertEquals(once, twice)
        assertEquals(listOf("com.samsung.a", "com.policy.x", "com.policy.y"), once)
    }
}
