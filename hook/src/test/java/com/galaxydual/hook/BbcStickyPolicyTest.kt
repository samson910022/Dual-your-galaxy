// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Phase A P0 BBC sticky last-good JVM pins.
//
// BBC path (getBbc, sticky): failed reads keep the last successful snapshot
// across TTL expiry / invalidate / cooldown / contention / deadline; a dead or
// backwards clock sample instead fails closed (sticky discarded,
// Unknown(emptySet())) because freshness cannot be proven. Only a successful
// empty WITH version proof (Cleared) may shrink. All fallback arms emit a
// throttled BBC log + counters; the sticky snapshot also expires after
// BBC_STICKY_MAX_AGE_MS (fail-closed to empty).
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

    // Mutable fake clock: the sample can be made to throw at an arbitrary point
    // (e.g. inside the reader, before the completion sample) or jumped forward
    // to test cap expiry / skew after a read has started.
    private class MutableClock(initial: Long) {
        val value = AtomicLong(initial)
        @Volatile var throwing = false

        fun now(): Long {
            if (throwing) throw RuntimeException("clock dead")
            return value.get()
        }
    }

    private fun bbcCacheWithClock(
        clock: MutableClock,
        logs: MutableList<String> = mutableListOf(),
    ): EffectivePolicyCache = EffectivePolicyCache(
        nowMs = { clock.now() },
        onLog = { logs.add(it) },
        stickyLastGood = true,
    )

    // JVM tests have no behavioral seam that distinguishes an old Cleared
    // marker from a reset one after a higher Good (both are != the high-water),
    // so pin the high-water-owner invariant directly.
    private fun clearedMarker(cache: EffectivePolicyCache): Long {
        val field = EffectivePolicyCache::class.java.getDeclaredField("lastBbcClearedVersion")
        field.isAccessible = true
        return field.get(cache) as Long
    }

    // R3 pins: version high-water and clock-distrust epoch, read reflectively
    // (same seam shape as clearedMarker). Both are intentionally untouched by
    // freshness invalidation / invalidate() respectively.
    private fun seenVersion(cache: EffectivePolicyCache): Long {
        val field = EffectivePolicyCache::class.java.getDeclaredField("lastBbcSeenVersion")
        field.isAccessible = true
        return field.get(cache) as Long
    }

    private fun clockEpoch(cache: EffectivePolicyCache): Long {
        val field = EffectivePolicyCache::class.java.getDeclaredField("clockEpoch")
        field.isAccessible = true
        return field.get(cache) as Long
    }

    // Single-flight owner token, read reflectively (same seam shape as the
    // other pins). Read only while a leader is parked in its reader, so the
    // guarded field is stable and the latch awaits provide the visibility edge.
    private fun inFlightToken(cache: EffectivePolicyCache): Long? {
        val field = EffectivePolicyCache::class.java.getDeclaredField("inFlightToken")
        field.isAccessible = true
        return field.get(cache) as Long?
    }

    // N03: minimal seam for the new version-probe tests — build a view from
    // injected lambdas. Existing anonymous view tests are left untouched.
    private fun versionedView(
        contains: () -> Boolean = { true },
        getLong: (defValue: Long) -> Long = { it },
        getStringSet: () -> MutableSet<String> = { mutableSetOf("A") },
    ): BbcVersionedPrefsView = object : BbcVersionedPrefsView {
        override fun contains(key: String): Boolean = contains()
        override fun getLong(key: String, defValue: Long): Long = getLong(defValue)
        override fun getStringSet(key: String, defValue: MutableSet<String>): MutableSet<String> = getStringSet()
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
    fun clockFailureFailsClosedWithoutTrustedClockBbc() {
        // Without a trusted clock the 30-min sticky freshness cap cannot be
        // proven, so fail closed to empty rather than serve an unverifiable
        // snapshot. The gate-closed short-circuit / no-reader semantics stay
        // unchanged (pinned below by assertFalse(readerCalled)).
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
        assertEquals(emptySet<String>(), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertFalse(readerCalled)
        // R3: clock distrust invalidates freshness; version evidence is kept.
        assertFalse(cache.isCached())
        assertEquals(1L, seenVersion(cache))
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
    fun stickyClockSkewExpiresLastGoodBbc() {
        // A backwards clock (now < lastGoodStickyAtMs) leaves a negative age,
        // so the old forward-only check never expires the cap. Mirror the
        // whitelist TTL rule (:420): treat skew as expired, fail closed.
        val (cache, time) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        time.set(900L)
        val unk = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
    }

    // --- PR 87: trusted-clock sampling for the WHOLE getBbc read ---

    @Test
    fun midReadClockFailureFailsClosed() {
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        // Enter with a still-valid (cap-1) sticky, then kill the clock INSIDE
        // the reader. The completion sample is untrusted, so the read must not
        // publish and the sticky must be discarded.
        clock.value.set(1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS - 1L)
        var reads = 0
        val untrusted = cache.getBbc(emptySet(), reader = {
            reads++
            clock.throwing = true
            Pair(null, null)
        })
        assertTrue(untrusted is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (untrusted as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, reads)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertFalse(cache.isCached())
        assertEquals(1L, seenVersion(cache))
        // A later HEALTHY failing read must not resurrect {A}: distrust dropped
        // the sticky AND invalidated freshness; no fresh value was published.
        clock.throwing = false
        val later = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(later is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (later as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun rollbackWithinCapFailsClosed() {
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        // cap-1: age is under the cap -> sticky served, no expiry.
        clock.value.set(1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS - 1L)
        val within = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(within is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A"), (within as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(0L, cache.bbcStickyExpiredCount.get())
        // A backward jump that stays ABOVE lastGoodStickyAtMs (2000 > 1000) is
        // still below the trusted high-water (1000+cap-1) => untrusted. The old
        // negative-age-only check would have served sticky again here.
        clock.value.set(2_000L)
        var reads = 0
        val skew = cache.getBbc(emptySet(), reader = { reads++; Pair(null, null) })
        assertTrue(skew is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (skew as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertEquals(0, reads) // fails at entry, before any IPC
        assertFalse(cache.isCached())
        assertEquals(1L, seenVersion(cache))
    }

    @Test
    fun stickyCapBoundaryExactlyAtCapExpiresBbc() {
        // cap-1 keeps the sticky; exactly cap expires it (cap+1 is pinned by
        // stickyCapExpiresToEmptyBbc above). Separate caches keep the failure
        // cooldown from masking the boundary.
        val underClock = MutableClock(1_000L)
        val under = bbcCacheWithClock(underClock)
        assertTrue(under.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        under.invalidate()
        underClock.value.set(1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS - 1L)
        val underResult = under.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(underResult is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A"), (underResult as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(0L, under.bbcStickyExpiredCount.get())

        val atClock = MutableClock(1_000L)
        val at = bbcCacheWithClock(atClock)
        assertTrue(at.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        at.invalidate()
        atClock.value.set(1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS)
        val atResult = at.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(atResult is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (atResult as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1L, at.bbcStickyExpiredCount.get())
    }

    @Test
    fun contentionClockCrossesCapFailsClosed() {
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        var reads = 0
        val unk = cache.getBbc(emptySet(), reader = {
            reads++
            // The contended read is slow: the clock crosses the sticky cap
            // while it runs. The contention arm must RESAMPLE (not use now0).
            clock.value.set(1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS)
            throw PolicyRefreshContentionException("guard")
        })
        assertTrue(unk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (unk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, reads)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
    }

    @Test
    fun followerWaitCrossesCapFailsClosed() {
        val clock = MutableClock(1_000L)
        val followerThread = AtomicReference<Thread>()
        // R4/R7 sample phases for a follower that actually parks:
        //   #1 entry, #2 cached probe, #3 now1 (elapsed), #4 pre-wait now2,
        //   #5 POST-WAIT probe. Jump the clock to the cap exactly on #5, so a
        //   mutant that drops the post-wait resample (and uses stale #4) never
        //   sees the jump and wrongly serves sticky — this test must kill it.
        val followerSamples = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val capAt = 1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS
        val cache = EffectivePolicyCache(
            nowMs = {
                if (Thread.currentThread() === followerThread.get()) {
                    val n = followerSamples.size + 1
                    if (n == 5) clock.value.set(capAt)
                    followerSamples.add(clock.value.get())
                }
                clock.now()
            },
            stickyLastGood = true,
        )
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val leader = Thread {
            cache.getBbc(emptySet(), followerGraceMs = 0L, reader = {
                leaderStarted.countDown()
                leaderRelease.await(5, TimeUnit.SECONDS)
                Pair(setOf("leader"), 9L)
            })
        }
        leader.start()
        assertTrue(leaderStarted.await(5, TimeUnit.SECONDS))

        var followerResult: EffectivePolicyCache.BbcPolicy? = null
        var followerReads = 0
        val follower = Thread {
            followerThread.set(Thread.currentThread())
            followerResult = cache.getBbc(
                emptySet(),
                followerGraceMs = 50L,
                reader = { followerReads++; Pair(setOf("unexpected"), 10L) },
            )
        }
        try {
            follower.start()
            follower.join(2_000)
            assertFalse(follower.isAlive)
        } finally {
            leaderRelease.countDown()
            leader.join(2_000)
            assertFalse(leader.isAlive)
        }

        assertTrue(followerResult is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (followerResult as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(0, followerReads)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertTrue("samples=$followerSamples", followerSamples.size >= 5)
        assertEquals(1_000L, followerSamples[3]) // #4 pre-wait now2
        assertEquals(capAt, followerSamples[4]) // #5 post-wait probe
    }

    @Test
    fun gateClosedDeadClockNoStateChange() {
        val clock = MutableClock(1_000L)
        val logs = mutableListOf<String>()
        val cache = bbcCacheWithClock(clock, logs)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        val fallbackBefore = cache.bbcStickyFallbackCount.get()
        val unknownBefore = cache.bbcUnknownCount.get()
        val expiredBefore = cache.bbcStickyExpiredCount.get()
        val logsBefore = logs.size
        clock.throwing = true
        var reads = 0
        val res = cache.getBbc(
            emptySet(),
            bbcGateOpen = { false },
            reader = { reads++; Pair(setOf("B"), 2L) },
        )
        assertTrue(res is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(-1L, (res as EffectivePolicyCache.BbcPolicy.Cleared).version)
        assertEquals(0, reads)
        assertEquals(fallbackBefore, cache.bbcStickyFallbackCount.get())
        assertEquals(unknownBefore, cache.bbcUnknownCount.get())
        assertEquals(expiredBefore, cache.bbcStickyExpiredCount.get())
        assertEquals(logsBefore, logs.size)
        // Sticky was NOT discarded: after emptying the still-valid cache, a
        // later healthy failing read must still see A.
        clock.throwing = false
        cache.invalidate()
        val later = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(later is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A"), (later as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun clockFailureCountsExactAndLogsOnce() {
        val clock = MutableClock(1_000L)
        val logs = mutableListOf<String>()
        val cache = bbcCacheWithClock(clock, logs)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        clock.throwing = true
        repeat(2) {
            var reads = 0
            val res = cache.getBbc(emptySet(), reader = { reads++; Pair(null, null) })
            assertTrue(res is EffectivePolicyCache.BbcPolicy.Unknown)
            assertEquals(emptySet<String>(), (res as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
            assertEquals(0, reads)
        }
        // First call discards the sticky (one expiry + one clock-free log);
        // the second has nothing to discard but still counts fallback/Unknown.
        assertEquals(2L, cache.bbcStickyFallbackCount.get())
        assertEquals(2L, cache.bbcUnknownCount.get())
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertEquals(1, logs.count { it.contains("clock-untrusted-sticky-discarded") })
        assertFalse(cache.isCached())
        assertEquals(1L, seenVersion(cache))
    }

    @Test
    fun successfulReadWithUntrustedCompletionClockNotPublished() {
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        clock.value.set(2_000L)
        var reads = 0
        val res = cache.getBbc(emptySet(), reader = {
            reads++
            // Reader succeeds, but the clock dies before the completion sample:
            // nothing from this read may be published.
            clock.throwing = true
            Pair(setOf("B"), 2L)
        })
        assertTrue(res is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (res as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, reads)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertFalse(cache.isCached())
        assertEquals(1L, seenVersion(cache))
        // Version 2 was never observed: the next healthy v2 read is still Good
        // against the high-water of 1.
        clock.throwing = false
        cache.invalidate()
        val good = cache.getBbc(emptySet(), reader = { Pair(setOf("C"), 2L) })
        assertTrue(good is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("C"), (good as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    @Test
    fun completionClockSkewFailsClosedWithoutPublishBbc() {
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        clock.value.set(5_000L)
        var reads = 0
        val res = cache.getBbc(emptySet(), reader = {
            reads++
            // The read succeeds but the clock jumps BACKWARD below the trusted
            // high-water before the completion sample: same fail-closed route
            // as a throwing clock.
            clock.value.set(2_000L)
            Pair(setOf("B"), 2L)
        })
        assertTrue(res is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (res as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, reads)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertFalse(cache.isCached())
        assertEquals(1L, seenVersion(cache))
        // v2 was never observed; a later healthy forward sample still accepts it.
        clock.value.set(5_001L)
        cache.invalidate()
        val good = cache.getBbc(emptySet(), reader = { Pair(setOf("C"), 2L) })
        assertTrue(good is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("C"), (good as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    // --- R3/R4: clock distrust = freshness invalidation + epoch/token fence ---

    @Test
    fun clockDistrustInvalidatesFreshCacheAndAllowsSameContentRecoveryBbc() {
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)

        // Clock dies on a later hit: no cache resurrection, sticky discarded,
        // version evidence retained.
        clock.throwing = true
        var faultReads = 0
        val fault = cache.getBbc(emptySet(), reader = { faultReads++; Pair(null, null) })
        assertTrue(fault is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (fault as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertEquals(0, faultReads)
        assertFalse(cache.isCached())
        assertEquals(1L, seenVersion(cache))

        // Recovery: a healthy call MUST re-read (it may not serve the old cache
        // just because the clock reset near the old value).
        clock.throwing = false
        clock.value.set(1_001L)
        var recoveryReads = 0
        val after = cache.getBbc(emptySet(), reader = { recoveryReads++; Pair(null, null) })
        assertTrue(after is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (after as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, recoveryReads)
        assertFalse(cache.isCached())

        // Same version + same content is still a legitimate recovery (canonical
        // cached evidence was retained); no permanent block was introduced.
        clock.value.set(1_001L + 5_000L) // clear the 5s failure cooldown
        val recovered = cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) })
        assertTrue(recovered is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("A"), (recovered as EffectivePolicyCache.BbcPolicy.Good).packages)

        // A changed set at the SAME version is still a replay and is rejected.
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 1L) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Unknown)
        assertFalse(cache.isCached())
    }

    @Test
    fun inFlightAcrossClockFaultUsesEpochFenceBbc() {
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate() // force the old leader to enter its reader

        val oldEntered = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        var oldResult: EffectivePolicyCache.BbcPolicy? = null
        val oldLeader = Thread {
            oldResult = cache.getBbc(emptySet(), followerGraceMs = 0L, reader = {
                oldEntered.countDown()
                oldRelease.await(5, TimeUnit.SECONDS)
                Pair(setOf("B"), 2L)
            })
        }
        oldLeader.start()
        assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
        try {
            // A DIFFERENT thread flips the clock and faults. Distrust bumps the
            // epoch, drops freshness/sticky, and releases the old leader's
            // flight claim so a successor may lead.
            clock.throwing = true
            var faultReads = 0
            val fault = cache.getBbc(emptySet(), reader = { faultReads++; Pair(null, null) })
            assertTrue(fault is EffectivePolicyCache.BbcPolicy.Unknown)
            assertEquals(emptySet<String>(), (fault as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
            assertEquals(0, faultReads)
            assertEquals(1L, cache.bbcStickyExpiredCount.get())
            assertFalse(cache.isCached())
            assertEquals(1L, clockEpoch(cache))

            clock.throwing = false
            clock.value.set(1_002L)

            val successorEntered = CountDownLatch(1)
            val successorRelease = CountDownLatch(1)
            var successorResult: EffectivePolicyCache.BbcPolicy? = null
            val successor = Thread {
                successorResult = cache.getBbc(emptySet(), followerGraceMs = 0L, reader = {
                    successorEntered.countDown()
                    successorRelease.await(5, TimeUnit.SECONDS)
                    Pair(setOf("C"), 3L)
                })
            }
            successor.start()
            try {
                assertTrue(successorEntered.await(5, TimeUnit.SECONDS))

                oldRelease.countDown()
                oldLeader.join(2_000)
                assertFalse(oldLeader.isAlive)
                assertTrue(oldResult is EffectivePolicyCache.BbcPolicy.Unknown)
                assertEquals(emptySet<String>(), (oldResult as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
                assertFalse(cache.isCached())

                // The old leader's token-matched finally has now run: it must
                // NOT have cleared the SUCCESSOR's still-in-flight claim. While
                // the successor is parked in its reader, a third caller is a
                // follower — it never reads and fails closed. A mutant that
                // releases on `inFlightToken != null` instead of the matched
                // token clears the successor's claim here, so this caller
                // becomes a new leader and runs its reader (caught below).
                assertTrue(inFlightToken(cache) != null)
                var thirdReads = 0
                val third = cache.getBbc(emptySet(), followerGraceMs = 0L, reader = {
                    thirdReads++; Pair(setOf("D"), 4L)
                })
                assertTrue(third is EffectivePolicyCache.BbcPolicy.Unknown)
                assertEquals(0, thirdReads)
            } finally {
                successorRelease.countDown()
                successor.join(2_000)
                assertFalse(successor.isAlive)
            }
            assertTrue(successorResult is EffectivePolicyCache.BbcPolicy.Good)
            assertEquals(setOf("C"), (successorResult as EffectivePolicyCache.BbcPolicy.Good).packages)
        } finally {
            oldRelease.countDown()
            oldLeader.join(2_000)
            assertFalse(oldLeader.isAlive)
        }
    }

    @Test
    fun gateCallbackClockDistrustFailsClosedBbc() {
        // The gate runs AFTER the entry sample: a callback that kills the clock
        // must be caught by the fresh under-lock probe before any cached hit.
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        var reads = 0
        val res = cache.getBbc(
            emptySet(),
            bbcGateOpen = { clock.throwing = true; true },
            reader = { reads++; Pair(setOf("B"), 2L) },
        )
        assertTrue(res is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (res as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(0, reads)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        assertFalse(cache.isCached())
    }

    @Test
    fun cachedReturnAfterGateRechecksTtlAndCapBbc() {
        // (a) gate advances past the 60s TTL but stays inside the 30m cap:
        // the cached hit is rejected, the reader runs, sticky is still served.
        val c1clock = MutableClock(1_000L)
        val c1 = bbcCacheWithClock(c1clock)
        assertTrue(c1.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        var reads1 = 0
        val r1 = c1.getBbc(
            emptySet(),
            bbcGateOpen = { c1clock.value.set(1_000L + 60_000L); true },
            reader = { reads1++; Pair(null, null) },
        )
        assertTrue(r1 is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A"), (r1 as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, reads1)
        assertEquals(0L, c1.bbcStickyExpiredCount.get())
        assertFalse(c1.isCached())

        // (b) gate advances past the cap: Unknown(empty), one counted expiry.
        val c2clock = MutableClock(1_000L)
        val c2 = bbcCacheWithClock(c2clock)
        assertTrue(c2.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        var reads2 = 0
        val r2 = c2.getBbc(
            emptySet(),
            bbcGateOpen = { c2clock.value.set(1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS); true },
            reader = { reads2++; Pair(null, null) },
        )
        assertTrue(r2 is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (r2 as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1, reads2)
        assertEquals(1L, c2.bbcStickyExpiredCount.get())
        assertFalse(c2.isCached())

        // (c) gate sets a sample below the trusted high-water: Untrusted, no IPC.
        val c3clock = MutableClock(1_000L)
        val c3 = bbcCacheWithClock(c3clock)
        assertTrue(c3.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        var reads3 = 0
        val r3 = c3.getBbc(
            emptySet(),
            bbcGateOpen = { c3clock.value.set(500L); true },
            reader = { reads3++; Pair(null, null) },
        )
        assertTrue(r3 is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (r3 as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(0, reads3)
        assertEquals(1L, c3.bbcStickyExpiredCount.get())
        assertFalse(c3.isCached())
    }

    @Test
    fun postGateProbeUsesFreshSampleNotEntryNowBbc() {
        // T1 starts at 1000 and parks inside its gate, which advances the clock
        // to 5000. T2 invalidates + commits Good({B},2)@5000. On release T1's
        // post-gate probe sees the NEW value at a fresh trusted sample; it must
        // never serve its pre-invalidation Good({A}).
        val clock = MutableClock(1_000L)
        val cache = bbcCacheWithClock(clock)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        val gateEntered = CountDownLatch(1)
        val gateRelease = CountDownLatch(1)
        var result: EffectivePolicyCache.BbcPolicy? = null
        var t1Reads = 0
        val t1 = Thread {
            result = cache.getBbc(
                emptySet(),
                bbcGateOpen = {
                    clock.value.set(5_000L)
                    gateEntered.countDown()
                    gateRelease.await(5, TimeUnit.SECONDS)
                    true
                },
                followerGraceMs = 0L,
                reader = { t1Reads++; Pair(setOf("unexpected-t1"), 99L) },
            )
        }
        t1.start()
        try {
            assertTrue(gateEntered.await(5, TimeUnit.SECONDS))
            cache.invalidate()
            val committed = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 2L) })
            assertTrue(committed is EffectivePolicyCache.BbcPolicy.Good)
            assertEquals(setOf("B"), (committed as EffectivePolicyCache.BbcPolicy.Good).packages)
        } finally {
            gateRelease.countDown()
            t1.join(2_000)
            assertFalse(t1.isAlive)
        }
        assertTrue(result is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("B"), (result as EffectivePolicyCache.BbcPolicy.Good).packages)
        assertEquals(0, t1Reads)
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
            bbcGateOpen = { false },
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

    // --- R06 (P1): versioned Good(non-empty) monotonic gate ---

    @Test
    fun goodRejectsStaleReplayAfterClearedBbc() {
        // Good({A},5) -> Cleared(6) -> stale Good({A},5) must be Unknown:
        // revoked A is never re-attached, sticky stays the Cleared snapshot
        // (empty), and the high-water mark is NOT lowered to 5.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 5L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val cleared = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) })
        assertTrue(cleared is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(6L, (cleared as EffectivePolicyCache.BbcPolicy.Cleared).version)
        val stickyBefore = cache.bbcStickyFallbackCount.get()
        val unknownBefore = cache.bbcUnknownCount.get()
        cache.invalidate()
        val stale = cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 5L) })
        assertTrue(stale is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (stale as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(stickyBefore + 1, cache.bbcStickyFallbackCount.get())
        assertTrue(cache.bbcUnknownCount.get() >= unknownBefore + 1)
        // Seen-version not lowered: Cleared(6) is still a duplicate (rejected),
        // while Cleared(7) is accepted — proving the gate still sits at 6.
        cache.invalidate()
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) }) is EffectivePolicyCache.BbcPolicy.Unknown)
        cache.invalidate()
        val ok = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 7L) })
        assertTrue(ok is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(7L, (ok as EffectivePolicyCache.BbcPolicy.Cleared).version)
    }

    @Test
    fun goodRejectsDuplicateVersionAfterClearedBbc() {
        // Non-increasing rule: a Cleared->Good replay of the SAME version is
        // rejected too (not just strictly older versions).
        val (cache, _) = bbcCacheAt(1_000L)
        val cleared = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) })
        assertTrue(cleared is EffectivePolicyCache.BbcPolicy.Cleared)
        cache.invalidate()
        val dup = cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 6L) })
        assertTrue(dup is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (dup as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        // High-water unchanged at 6.
        cache.invalidate()
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) }) is EffectivePolicyCache.BbcPolicy.Unknown)
    }

    @Test
    fun goodVersionWraparoundAcceptedBbc() {
        // Wraparound semantics match the Cleared path: version 1 after a huge
        // seen version is accepted for Good too.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), Long.MAX_VALUE) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val wrapped = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 1L) })
        assertTrue(wrapped is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("B"), (wrapped as EffectivePolicyCache.BbcPolicy.Good).packages)
        // A normal forward Cleared(2) advances the high-water; the subsequent
        // Good(2) replays the just-Cleared version and is rejected.
        cache.invalidate()
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(emptySet(), 2L) }) is EffectivePolicyCache.BbcPolicy.Cleared)
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 2L) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (replay as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    // --- R07 (P1): self-consistent versioned prefs read (seqlock) ---

    @Test
    fun tornVersionedPrefsReadFailsClosedBbc() {
        val (cache, _) = bbcCacheAt(1_000L)
        // Seed a last-good at version 5 (sticky {A}, high-water 5).
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 5L) }) is EffectivePolicyCache.BbcPolicy.Good)
        var versionReads = 0
        val torn = object : BbcVersionedPrefsView {
            override fun contains(key: String) = true
            override fun getLong(key: String, defValue: Long): Long {
                versionReads++
                // Version changes BETWEEN the two reads -> torn snapshot.
                return if (versionReads == 1) 5L else 6L
            }
            override fun getStringSet(key: String, defValue: MutableSet<String>): MutableSet<String> =
                mutableSetOf("A")
        }
        val read = readBbcVersionedPrefs(torn)
        assertTrue(read.failed)
        assertEquals(null, read.packages)
        assertEquals(null, read.version)
        assertEquals(2, versionReads)
        cache.invalidate()
        val result = cache.getBbc(emptySet(), reader = { Pair(read.packages, read.version) })
        assertTrue(result is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A"), (result as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        // High-water NOT advanced to 6: a Cleared(6) is still accepted.
        cache.invalidate()
        val cleared = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) })
        assertTrue(cleared is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(6L, (cleared as EffectivePolicyCache.BbcPolicy.Cleared).version)
    }

    @Test
    fun consistentVersionedPrefsReadKeepsSemanticsBbc() {
        var versionReads = 0
        val consistent = object : BbcVersionedPrefsView {
            override fun contains(key: String) = true
            override fun getLong(key: String, defValue: Long): Long {
                versionReads++
                return 5L
            }
            override fun getStringSet(key: String, defValue: MutableSet<String>): MutableSet<String> =
                mutableSetOf("A")
        }
        val good = readBbcVersionedPrefs(consistent)
        assertFalse(good.failed)
        assertEquals(setOf("A"), good.packages)
        assertEquals(5L, good.version)
        assertEquals(2, versionReads)

        // empty + version => Cleared candidate; empty + null version => Unknown;
        // non-empty + null version => Good (legacy, no-version draft).
        val clearedRead = readBbcVersionedPrefs(object : BbcVersionedPrefsView {
            override fun contains(key: String) = true
            override fun getLong(key: String, defValue: Long) = 7L
            override fun getStringSet(key: String, defValue: MutableSet<String>) = mutableSetOf<String>()
        })
        assertFalse(clearedRead.failed)
        assertEquals(emptySet<String>(), clearedRead.packages)
        assertEquals(7L, clearedRead.version)

        val absentRead = readBbcVersionedPrefs(object : BbcVersionedPrefsView {
            override fun contains(key: String) = false
            override fun getLong(key: String, defValue: Long) = defValue
            override fun getStringSet(key: String, defValue: MutableSet<String>) = mutableSetOf<String>()
        })
        assertFalse(absentRead.failed)
        assertEquals(emptySet<String>(), absentRead.packages)
        assertEquals(null, absentRead.version)

        val legacyRead = readBbcVersionedPrefs(object : BbcVersionedPrefsView {
            override fun contains(key: String) = false
            override fun getLong(key: String, defValue: Long) = defValue
            override fun getStringSet(key: String, defValue: MutableSet<String>) = mutableSetOf("A")
        })
        assertFalse(legacyRead.failed)
        assertEquals(setOf("A"), legacyRead.packages)
        assertEquals(null, legacyRead.version)
    }

    // --- F1: unversioned Good must not replay after versioned state ---

    @Test
    fun nullVersionGoodAcceptedBeforeAnyVersionedStateBbc() {
        // Control: with NO prior versioned state (lastBbcSeenVersion < 0), a
        // non-empty read with a null version stays the ungated legacy Good,
        // exactly as before.
        val (cache, _) = bbcCacheAt(1_000L)
        val res = cache.getBbc(emptySet(), reader = { Pair(setOf("A"), null) })
        assertTrue(res is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("A"), (res as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    @Test
    fun nullVersionGoodRejectedAfterVersionedClearedBbc() {
        // F1: Good({A},5) -> Cleared(6) -> unversioned Good({A}, null) must be
        // Unknown(sticky): a stale pre-version draft / corrupt (CCE) version
        // key cannot re-attach revoked A after an intentional clear.
        val logs = mutableListOf<String>()
        val (cache, _) = bbcCacheAt(1_000L, logs)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 5L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val cleared = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) })
        assertTrue(cleared is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(6L, (cleared as EffectivePolicyCache.BbcPolicy.Cleared).version)
        val stickyBefore = cache.bbcStickyFallbackCount.get()
        val unknownBefore = cache.bbcUnknownCount.get()
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(setOf("A"), null) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Unknown)
        // Sticky is the empty Cleared snapshot: A is never merged back.
        assertEquals(emptySet<String>(), (replay as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(stickyBefore + 1, cache.bbcStickyFallbackCount.get())
        assertTrue(cache.bbcUnknownCount.get() >= unknownBefore + 1)
        assertTrue(logs.any { it.contains("good-rejected-no-version") })
        // High-water still 6: Cleared(6) is a duplicate (rejected), Cleared(7)
        // is accepted — the rejected unversioned Good overwrote nothing.
        cache.invalidate()
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) }) is EffectivePolicyCache.BbcPolicy.Unknown)
        cache.invalidate()
        val ok = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 7L) })
        assertTrue(ok is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(7L, (ok as EffectivePolicyCache.BbcPolicy.Cleared).version)
    }

    // --- F2: same-version Good is content-aware ---

    @Test
    fun goodSameVersionDifferentContentRejectedBbc() {
        // At the current high-water (set by a Good), a non-empty read whose
        // content differs by RE-ADDING a removed package is a stale replay and
        // must be rejected as Unknown, retaining the last accepted set {B}.
        val logs = mutableListOf<String>()
        val (cache, _) = bbcCacheAt(1_000L, logs)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 5L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("B"), (replay as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertTrue(logs.any { it.contains("good-rejected") })
    }

    @Test
    fun goodSameVersionShrinkRejectedBbc() {
        // N02: a same-version shrink is a content change and MUST be rejected
        // as a stale replay (no version proof); the writer bumps
        // extra_whitelist_version on every successful save/clear. Good({A,B},5)
        // -> Good({B},5) must stay Unknown with lastGood/cached still {A,B},
        // and the unknown counter must increment.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val unknownBefore = cache.bbcUnknownCount.get()
        val shrunk = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 5L) })
        assertTrue(shrunk is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A", "B"), (shrunk as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertTrue(cache.bbcUnknownCount.get() >= unknownBefore + 1)
        // The final old-content replay {A,B}@5 is now an identical re-read of
        // the unchanged canonical, so it is accepted — proving the rejected
        // shrink never shrank cached/sticky to {B} (which would reject it).
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("A", "B"), (replay as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    @Test
    fun goodSameVersionNewerVersionShrinkAcceptedBbc() {
        // N02: a genuine shrink is accepted when it carries a strictly newer
        // version — the writer bumps extra_whitelist_version on every save.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val shrunk = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 6L) })
        assertTrue(shrunk is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("B"), (shrunk as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    @Test
    fun goodSameVersionConcurrentReaderCannotShrinkBbc() {
        // N02: a second same-version reader cannot shrink the canonical
        // snapshot; {B}@5 against {A,B}@5 is a stale partial replay.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val concurrent = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 5L) })
        assertTrue(concurrent is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A", "B"), (concurrent as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        // Canonical unchanged: an identical re-read at v5 is still accepted.
        cache.invalidate()
        val still = cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
        assertTrue(still is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("A", "B"), (still as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    @Test
    fun goodReAddAtHigherVersionAcceptedBbc() {
        // N02: re-adding a previously removed package is legitimate when it
        // carries a strictly newer version (writer bumps on every save).
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 6L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val readded = cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 7L) })
        assertTrue(readded is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("A", "B"), (readded as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    @Test
    fun goodSameVersionSameContentStillAcceptedBbc() {
        // Control: a content-equivalent re-acceptance at the same version
        // (after a failed read / invalidate) stays Good — compares against
        // sticky as well as cached (preserves successEmptyWithoutVersion...).
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 5L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val failed = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(failed is EffectivePolicyCache.BbcPolicy.Unknown)
        cache.invalidate()
        val reaccepted = cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 5L) })
        assertTrue(reaccepted is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("A"), (reaccepted as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    @Test
    fun sameVersionGoodWithoutCanonicalFailsClosedBbc() {
        // H1: a same-version Good can only be content-checked against a cached
        // or sticky canonical snapshot. Once BOTH are gone (sticky cap expired
        // after a failed read, then invalidate empties cached), an arbitrary
        // same-version Good must FAIL CLOSED as Unknown, not be accepted
        // unconditionally just because its version matches the high-water.
        val (cache, time) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        // Advance past the sticky cap; the failed read expires the sticky
        // snapshot (stickyIfValidLocked). invalidate() then empties cached and
        // resets the failure cooldown so the next read reaches the version gate.
        time.set(1_000L + EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS + 1L)
        val failed = cache.getBbc(emptySet(), reader = { Pair(null, null) })
        assertTrue(failed is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (failed as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        assertEquals(1L, cache.bbcStickyExpiredCount.get())
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (replay as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun higherGoodResetsClearedHighWaterBbc() {
        // H2: Cleared(v5) owns the high-water and its Cleared marker; a later
        // higher-version Good must take over ownership so a same-version Good
        // replay is content-checked against the GOOD snapshot, not the earlier
        // Cleared snapshot. A differing same-version set stays Unknown.
        val (cache, _) = bbcCacheAt(1_000L)
        val cleared = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 5L) })
        assertTrue(cleared is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(5L, (cleared as EffectivePolicyCache.BbcPolicy.Cleared).version)
        assertEquals(5L, clearedMarker(cache))
        cache.invalidate()
        val good = cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 6L) })
        assertTrue(good is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("A"), (good as EffectivePolicyCache.BbcPolicy.Good).packages)
        // The higher Good now owns the high-water: the stale Cleared marker is
        // dropped (without the fix it still reads 5).
        assertEquals(-1L, clearedMarker(cache))
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 6L) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A"), (replay as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
        // The high-water itself is unchanged at 6 (the marker reset must not
        // lower it): Cleared(6) is still a duplicate, Cleared(7) advances.
        cache.invalidate()
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(emptySet(), 6L) }) is EffectivePolicyCache.BbcPolicy.Unknown)
        cache.invalidate()
        val next = cache.getBbc(emptySet(), reader = { Pair(emptySet(), 7L) })
        assertTrue(next is EffectivePolicyCache.BbcPolicy.Cleared)
        assertEquals(7L, (next as EffectivePolicyCache.BbcPolicy.Cleared).version)
    }

    // --- F3: documented wraparound replay-window limitation (option (b)) ---

    @Test
    fun bbcVersionWraparoundReopensReplayWindowDocumentedBbc() {
        // PINNED DOCUMENTED BEHAVIOR (F3 option (b)): the version key carries no
        // epoch (key format fixed), so after Good(MAX) -> Good(1) the high-water
        // resets to 1 and a mid-range replay Good(500) is ACCEPTED. This needs
        // ~9.22e18 committed writes to be reachable in production. If an epoch
        // scheme is added later, change this test to expect Unknown.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), Long.MAX_VALUE) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val wrapped = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 1L) })
        assertTrue(wrapped is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("B"), (wrapped as EffectivePolicyCache.BbcPolicy.Good).packages)
        cache.invalidate()
        val midRangeReplay = cache.getBbc(emptySet(), reader = { Pair(setOf("C"), 500L) })
        assertTrue(midRangeReplay is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("C"), (midRangeReplay as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    // --- R07 (cheap): defensive copy + getStringSet failure ---

    @Test
    fun versionedPrefsReadReturnsDefensiveCopyBbc() {
        val live = mutableSetOf("A", "B")
        val view = object : BbcVersionedPrefsView {
            override fun contains(key: String) = true
            override fun getLong(key: String, defValue: Long) = 5L
            override fun getStringSet(key: String, defValue: MutableSet<String>): MutableSet<String> = live
        }
        val read = readBbcVersionedPrefs(view)
        assertFalse(read.failed)
        assertEquals(setOf("A", "B"), read.packages)
        // Mutating the live set after the read must not change the snapshot.
        live.add("C")
        assertEquals(setOf("A", "B"), read.packages)
    }

    @Test
    fun versionedPrefsGetStringSetFailureFailsClosedBbc() {
        val view = object : BbcVersionedPrefsView {
            override fun contains(key: String) = true
            override fun getLong(key: String, defValue: Long) = 5L
            override fun getStringSet(key: String, defValue: MutableSet<String>): MutableSet<String> =
                throw RuntimeException("prefs dead")
        }
        val read = readBbcVersionedPrefs(view)
        assertTrue(read.failed)
        assertEquals(null, read.packages)
        assertEquals(null, read.version)
    }

    // --- PR #74 G1: runtime BBC injection opt-out (default true) ---
    // The compile-time gate-off pin above (bbcGateClosedReturnsClearedWithoutIo)
    // is unchanged. These tests cover only the NEW runtime key semantics.

    @Test
    fun bbcKeyLiteralIsStable() {
        assertEquals("bbc_injection_enabled", PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED)
    }

    @Test
    fun runtimeOptOutFalseClosesGateAndNoMerge() {
        // Runtime false ANDed with the (shipped true) compile-time gate must
        // make getBbc short-circuit to Cleared with ZERO reader calls.
        val enabled = readBbcInjectionEnabled(object : BbcInjectionPrefsView {
            override fun getBoolean(key: String, defValue: Boolean): Boolean {
                assertEquals(PolicySource.POLICY_KEY_BBC_INJECTION_ENABLED, key)
                return false
            }
        })
        assertEquals(false, enabled)
        // Fresh process-local state so this confirmed-OFF pin cannot leak into
        // other tests (the production default is a process-wide singleton).
        val gate = bbcInjectionGateOpen(
            PolicySource.BBC_AVAILABLE_APPS_INJECTION,
            stickyState = AtomicReference(null),
        ) { enabled }
        assertFalse(gate)
        var reads = 0
        val (cache, _) = bbcCacheAt(1_000L)
        val res = cache.getBbc(
            emptySet(),
            bbcGateOpen = { gate },
            reader = { reads++; Pair(setOf("com.good.a"), 1L) },
        )
        assertTrue(res is EffectivePolicyCache.BbcPolicy.Cleared)
        // Gate-closed sentinel specifically: no version proof, no merge, no reads.
        assertEquals(-1L, (res as EffectivePolicyCache.BbcPolicy.Cleared).version)
        assertEquals(0, reads)
    }

    @Test
    fun absentOrThrownRuntimeReadKeepsShippedDefaultOn() {
        // Absent key: the prefs adapter supplies defValue=true. Thrown read:
        // readBbcInjectionEnabled returns null. With NO prior confirmed OFF in
        // this (fresh) process-local state, the hybrid rule still maps null to
        // true so a transient IPC failure never silently disables the shipped
        // BBC effect ("off" is not a safety guarantee, issue #75). Once OFF has
        // been confirmed the failures stay closed for up to 30 min — pinned by
        // failedReadAfterConfirmedOffKeepsGateClosedWithinTtl below.
        val freshState = { AtomicReference<Long?>(null) }
        val absent = readBbcInjectionEnabled(object : BbcInjectionPrefsView {
            override fun getBoolean(key: String, defValue: Boolean) = defValue
        })
        assertEquals(true, absent)
        assertTrue(bbcInjectionGateOpen(PolicySource.BBC_AVAILABLE_APPS_INJECTION, stickyState = freshState()) { absent })

        val thrown = readBbcInjectionEnabled(object : BbcInjectionPrefsView {
            override fun getBoolean(key: String, defValue: Boolean): Boolean =
                throw RuntimeException("transient read failure")
        })
        assertEquals(null, thrown)
        assertTrue(bbcInjectionGateOpen(PolicySource.BBC_AVAILABLE_APPS_INJECTION, stickyState = freshState()) { thrown })

        // Full provider failure (null prefs) is also null => default on.
        assertTrue(bbcInjectionGateOpen(PolicySource.BBC_AVAILABLE_APPS_INJECTION, stickyState = freshState()) { null })
    }

    @Test
    fun compileTimeGateStillCannotBeReopenedByRuntimePreference() {
        // Stage-1 sealed gate and the BBC compile-time gate stay authoritative:
        // a runtime "true" preference can never open a closed compile-time gate.
        assertFalse(bbcInjectionGateOpen(false) { true })
        assertFalse(bbcInjectionGateOpen(false) { null })
        // Lazy runtime read: a compile-time-closed gate never touches prefs.
        var runtimeReads = 0
        assertFalse(bbcInjectionGateOpen(false) {
            runtimeReads++
            true
        })
        assertEquals(0, runtimeReads)
    }

    // --- N06(a): hybrid runtime opt-out — confirmed OFF is sticky for TTL ---
    // The stateful pins below inject a fresh AtomicReference + fake clock: the
    // production state is process-local (not device-verified, not cross-process).

    private val gateTtl = EffectivePolicyCache.BBC_STICKY_MAX_AGE_MS

    private fun throwingRead(): () -> Boolean? = { throw RuntimeException("prefs read failed") }

    // Deterministic stale-CAS interleaving WITHOUT subclassing AtomicReference
    // (both get() and compareAndSet() are final). The injected nowMs() is called
    // AFTER get() captures `cur` but BEFORE compareAndSet, so parking on its
    // first invocation guarantees a stale-cur CAS that must fail and retry.
    // No sleeps and no lock is held around the runtime prefs read.
    private class BlockingClock(private val base: Long) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)

        fun now(): Long {
            val n = calls.incrementAndGet()
            if (n == 1) {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("clock release timeout")
                }
            }
            return base + n
        }
    }

    @Test
    fun hybridRuleHelperPinsDecisionTableWithoutAndroid() {
        val t0 = 1_000L
        val ttl = gateTtl
        // compile-time closed always wins and leaves sticky state untouched.
        val pinned = bbcInjectionGateOpenWithSticky(false, true, t0, t0, ttl)
        assertFalse(pinned.open)
        assertEquals(t0, pinned.confirmedOffAtMs)
        // false stamps OFF; true clears it; null inside TTL keeps it; null
        // before any OFF / after expiry fails open to the shipped default.
        assertTrue(bbcInjectionGateOpenWithSticky(true, false, null, t0, ttl).let {
            !it.open && it.confirmedOffAtMs == t0
        })
        assertTrue(bbcInjectionGateOpenWithSticky(true, true, t0, t0 + 10L, ttl).let {
            it.open && it.confirmedOffAtMs == null
        })
        assertTrue(bbcInjectionGateOpenWithSticky(true, null, t0, t0 + ttl / 2, ttl).let {
            !it.open && it.confirmedOffAtMs == t0
        })
        assertTrue(bbcInjectionGateOpenWithSticky(true, null, null, t0, ttl).open)
        assertTrue(bbcInjectionGateOpenWithSticky(true, null, t0, t0 + ttl + 1, ttl).open)
    }

    @Test
    fun failedReadAfterConfirmedOffKeepsGateClosedWithinTtl() {
        val t0 = 1_000L
        val now = AtomicLong(t0)
        val state = AtomicReference<Long?>(null)
        fun gate(read: () -> Boolean?): Boolean = bbcInjectionGateOpen(
            PolicySource.BBC_AVAILABLE_APPS_INJECTION,
            nowMs = { now.get() },
            ttlMs = gateTtl,
            stickyState = state,
            runtimeEnabled = read,
        )
        // t0: confirmed OFF -> closed, timestamp recorded.
        assertFalse(gate { false })
        assertEquals(t0, state.get())
        // t0 + TTL/2: read throws -> sticky OFF still within TTL -> closed.
        now.set(t0 + gateTtl / 2)
        assertFalse(gate(throwingRead()))
        assertEquals(t0, state.get())
    }

    @Test
    fun failedReadAfterConfirmedOffExpiresAfterTtl_defaultsTrue() {
        val t0 = 1_000L
        val now = AtomicLong(t0)
        val state = AtomicReference<Long?>(null)
        fun gate(read: () -> Boolean?): Boolean = bbcInjectionGateOpen(
            PolicySource.BBC_AVAILABLE_APPS_INJECTION,
            nowMs = { now.get() },
            ttlMs = gateTtl,
            stickyState = state,
            runtimeEnabled = read,
        )
        assertFalse(gate { false })
        assertEquals(t0, state.get())
        // t0 + TTL + 1: sticky OFF expired, failure falls back to shipped ON.
        now.set(t0 + gateTtl + 1)
        assertTrue(gate(throwingRead()))
        assertEquals(null, state.get())
    }

    @Test
    fun failedReadBeforeAnyConfirmedOff_defaultsTrue() {
        val now = AtomicLong(1_000L)
        val state = AtomicReference<Long?>(null)
        assertTrue(bbcInjectionGateOpen(
            PolicySource.BBC_AVAILABLE_APPS_INJECTION,
            nowMs = { now.get() },
            ttlMs = gateTtl,
            stickyState = state,
            runtimeEnabled = throwingRead(),
        ))
        assertEquals(null, state.get())
    }

    @Test
    fun confirmedOffClearedByLaterTrueRead() {
        val t0 = 1_000L
        val now = AtomicLong(t0)
        val state = AtomicReference<Long?>(null)
        fun gate(read: () -> Boolean?): Boolean = bbcInjectionGateOpen(
            PolicySource.BBC_AVAILABLE_APPS_INJECTION,
            nowMs = { now.get() },
            ttlMs = gateTtl,
            stickyState = state,
            runtimeEnabled = read,
        )
        assertFalse(gate { false })
        assertEquals(t0, state.get())
        // A later confirmed ON clears the sticky OFF entirely.
        now.set(t0 + 100L)
        assertTrue(gate { true })
        assertEquals(null, state.get())
        // Therefore a subsequent failure now defaults ON again.
        now.set(t0 + 200L)
        assertTrue(gate(throwingRead()))
        assertEquals(null, state.get())
    }

    @Test
    fun concurrentOffAndOnReads_finalStateMatchesLastConfirmedRead() {
        // "Last confirmed read" is the transition whose CAS commits last
        // (the linearization point), not merely the one whose prefs read ran
        // last — that is the property the CAS loop must guarantee.
        val t0 = 1_000L
        val state = AtomicReference<Long?>(t0) // prior confirmed OFF
        val clock = BlockingClock(t0)
        fun gate(read: () -> Boolean?): Boolean = bbcInjectionGateOpen(
            PolicySource.BBC_AVAILABLE_APPS_INJECTION,
            nowMs = { clock.now() },
            ttlMs = gateTtl,
            stickyState = state,
            runtimeEnabled = read,
        )

        // ON thread captures stale cur=t0, then parks inside nowMs() (call #1)
        // before its CAS. No lock is held, so the other thread can proceed.
        var onGate: Boolean? = null
        val onThread = Thread { onGate = gate { true } }
        onThread.start()
        assertTrue(clock.entered.await(5, TimeUnit.SECONDS))

        // OFF thread commits meanwhile (clock call #2 => state = t0 + 2).
        var offGate: Boolean? = null
        val offThread = Thread { offGate = gate { false } }
        offThread.start()
        offThread.join(5_000L)
        assertEquals(t0 + 2, state.get())

        // Release the ON thread: its stale CAS(t0, null) MUST fail, the loop
        // retries from the fresh value (clock call #3) and commits ON.
        clock.release.countDown()
        onThread.join(5_000L)

        assertEquals(true, onGate)
        assertEquals(false, offGate)
        assertEquals(3, clock.calls.get()) // retry executed (3rd nowMs call)
        assertEquals(null, state.get()) // last committed read (ON) wins

        // A later failing read follows that last confirmed ON => fail open.
        assertTrue(gate(throwingRead()))
        assertEquals(null, state.get())
    }

    // D06 (PR#74 r3): these two gateReadInsideGuardedBudget_* tests are
    // ACCOUNTING PINS only — they drive a fake clock (time.addAndGet(60L)) to
    // prove the gate is evaluated AFTER now0 so its cost is charged to the
    // deadline/grace arithmetic. They do NOT assert wall time and are NOT a
    // stall bound. The real wall-time ceiling is pinned separately by the
    // System.nanoTime-based BbcGateBoundedWaitTest.
    @Test
    fun gateReadInsideGuardedBudget_seesElapsedAfterSlowProvider() {
        val (cache, time) = bbcCacheAt(1_000L)
        // Seed a sticky last-good, then force a re-read so a leader/follower
        // contest is possible.
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val leader = Thread {
            cache.getBbc(emptySet(), reader = {
                leaderStarted.countDown()
                leaderRelease.await(5, TimeUnit.SECONDS)
                Pair(setOf("com.leader"), 9L)
            })
        }
        leader.start()
        assertTrue(leaderStarted.await(5, TimeUnit.SECONDS))

        // Follower's gate provider simulates a slow pref read: it advances the
        // SAME fake clock getBbc sampled for now0 by >50ms (the grace budget).
        // Because the provider is evaluated after now0, the follower's elapsed
        // clamp sees the cost and the 40ms deadline (1_040L) is already spent.
        var followerReads = 0
        var followerResult: EffectivePolicyCache.BbcPolicy? = null
        val follower = Thread {
            followerResult = cache.getBbc(
                emptySet(),
                bbcGateOpen = {
                    time.addAndGet(60L) // slow pref read, charged to the budget
                    true
                },
                followerGraceMs = 50L,
                deadlineMs = 1_040L,
                reader = { followerReads++; Pair(setOf("com.unexpected"), 10L) },
            )
        }
        follower.start()
        follower.join(5_000L)
        leaderRelease.countDown()
        leader.join(5_000L)

        // Deadline clamp/fail-closed sticky fallback, NOT a normal merged Good.
        assertTrue(followerResult is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(
            setOf("com.good.a"),
            (followerResult as EffectivePolicyCache.BbcPolicy.Unknown).lastGood,
        )
        assertEquals(0, followerReads) // no merge, no extra reader call
    }

    @Test
    fun gateReadInsideGuardedBudget_graceOnlySeesElapsedAfterSlowProvider() {
        // Same contest, but with NO absolute deadline: only followerGraceMs -
        // elapsed can fail closed. The absolute-deadline variant above would
        // still pass if the gate were read before now0 (a stale deadline would
        // clamp); here the cost MUST show up in elapsed to beat the 50ms grace.
        val (cache, time) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("com.good.a"), 1L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val leader = Thread {
            cache.getBbc(emptySet(), reader = {
                leaderStarted.countDown()
                leaderRelease.await(5, TimeUnit.SECONDS)
                Pair(setOf("com.leader"), 9L)
            })
        }
        leader.start()
        assertTrue(leaderStarted.await(5, TimeUnit.SECONDS))

        var followerReads = 0
        var followerResult: EffectivePolicyCache.BbcPolicy? = null
        val gateReadDone = CountDownLatch(1)
        val follower = Thread {
            followerResult = cache.getBbc(
                emptySet(),
                bbcGateOpen = {
                    time.addAndGet(60L) // > 50ms grace, charged to elapsed
                    gateReadDone.countDown()
                    true
                },
                followerGraceMs = 50L,
                deadlineMs = -1L,
                reader = { followerReads++; Pair(setOf("com.unexpected"), 10L) },
            )
        }
        follower.start()
        assertTrue(gateReadDone.await(5, TimeUnit.SECONDS))
        // The leader stays blocked: with the old eager ordering the follower
        // would park the full 50ms grace and increment followerGraceHits. With
        // the lazy provider elapsed=60 already spent the grace => no park.
        follower.join(5_000L)
        leaderRelease.countDown()
        leader.join(5_000L)

        assertTrue(followerResult is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(
            setOf("com.good.a"),
            (followerResult as EffectivePolicyCache.BbcPolicy.Unknown).lastGood,
        )
        assertEquals(0, followerReads)
        assertEquals(0, cache.followerGraceHits.get()) // no wait: cost hit elapsed
    }

    // --- N03: version-key read failure must NOT masquerade as absence ---

    @Test
    fun versionContainsThrowsBothProbesFailsClosedBbc() {
        // contains() throwing is a failure (Unavailable), never "key absent":
        // no legacy Good replay, no packages, Unknown on a fresh cache.
        val read = readBbcVersionedPrefs(versionedView(
            contains = { throw RuntimeException("contains dead") },
        ))
        assertTrue(read.failed)
        assertEquals(null, read.packages)
        assertEquals(null, read.version)
        val (cache, _) = bbcCacheAt(1_000L)
        val result = cache.getBbc(emptySet(), reader = { Pair(read.packages, read.version) })
        assertTrue(result is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (result as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun versionGetLongThrowsBothProbesFailsClosedBbc() {
        // Key confirmed present but the value read throws: both probes fail.
        val read = readBbcVersionedPrefs(versionedView(
            getLong = { throw RuntimeException("binder dead") },
        ))
        assertTrue(read.failed)
        assertEquals(null, read.packages)
        assertEquals(null, read.version)
        val (cache, _) = bbcCacheAt(1_000L)
        val result = cache.getBbc(emptySet(), reader = { Pair(read.packages, read.version) })
        assertTrue(result is EffectivePolicyCache.BbcPolicy.Unknown)
    }

    @Test
    fun versionProbeThrowsOnSecondCallFailsClosedBbc() {
        // Seqlock: first probe is Present(5), second fails — mismatch/failure on
        // either read fails closed. Diagnostic fields retain the Present value.
        var probeCalls = 0
        val read = readBbcVersionedPrefs(versionedView(
            getLong = {
                probeCalls++
                if (probeCalls == 1) 5L else throw RuntimeException("binder died mid-read")
            },
        ))
        assertTrue(read.failed)
        assertEquals(2, probeCalls)
        assertEquals(null, read.packages)
        assertEquals(5L, read.versionBefore)
        assertEquals(null, read.versionAfter)
    }

    @Test
    fun versionInvalidPresentValueFailsClosedBbc() {
        // Present but < 1 is Invalid, NOT absent — fails closed even though
        // both probes agree. Covers 0L and -1L; no version diagnostic emitted.
        for (bad in listOf(0L, -1L)) {
            val read = readBbcVersionedPrefs(versionedView(getLong = { bad }))
            assertTrue(read.failed)
            assertEquals(null, read.packages)
            assertEquals(null, read.version)
            assertEquals(null, read.versionBefore)
            assertEquals(null, read.versionAfter)
        }
    }

    @Test
    fun versionProbeClassCastIsInvalidNotAbsentBbc() {
        // The shared model's wrong-type arm: CCE is Invalid (corrupt), not
        // Absent and not a generic Unavailable. Both readers/writers share it.
        val probe = probeVersion(object : VersionPrefsView {
            override fun containsVersion(): Boolean = true
            override fun readVersion(defValue: Long): Long =
                throw ClassCastException("version key held a String")
        })
        assertTrue(probe is VersionProbeResult.Invalid)
        val read = readBbcVersionedPrefs(versionedView(
            getLong = { throw ClassCastException("version key held a String") },
        ))
        assertTrue(read.failed)
        assertEquals(null, read.packages)
    }

    @Test
    fun versionProbeFailureOnPrepopulatedCacheStaysStickyBbc() {
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 5L) }) is EffectivePolicyCache.BbcPolicy.Good)
        cache.invalidate()
        // Version probe throws while the package read still returns {B}: the
        // seqlock must drop those packages (null) and keep the sticky {A}.
        val read = readBbcVersionedPrefs(versionedView(
            contains = { throw RuntimeException("contains dead") },
            getStringSet = { mutableSetOf("B") },
        ))
        assertTrue(read.failed)
        assertEquals(null, read.packages)
        val result = cache.getBbc(emptySet(), reader = { Pair(read.packages, read.version) })
        assertTrue(result is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A"), (result as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun versionConfirmedAbsentStillLegacyGoodBbc() {
        // Control: contains=false on BOTH probes is a genuine absence — the
        // legacy no-version {A} draft stays Good and is not failed closed.
        val read = readBbcVersionedPrefs(versionedView(
            contains = { false },
            getStringSet = { mutableSetOf("A") },
        ))
        assertFalse(read.failed)
        assertEquals(setOf("A"), read.packages)
        assertEquals(null, read.version)
        val (cache, _) = bbcCacheAt(1_000L)
        val result = cache.getBbc(emptySet(), reader = { Pair(read.packages, read.version) })
        assertTrue(result is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("A"), (result as EffectivePolicyCache.BbcPolicy.Good).packages)
    }

    // --- D07 (PR#74 r3): KNOWN LIMITATION — not a pass ---
    // App-side HookPolicyStore repairs a corrupt (Invalid) version key to v1
    // on the next save. That repair is NOT guaranteed to take effect in an
    // ALREADY-RUNNING hook whose lastBbcSeenVersion > 1: the v1 Good is a
    // decreasing version, so the hook's monotonic high-water rejects it and
    // keeps the previous sticky snapshot. A successful App save does NOT prove
    // BBC applied in that process — a HOOK RESTART (fresh cache, high-water
    // reset) is required for reliable application. Documents the limitation;
    // it does not claim the in-process behavior is correct.
    @Test
    fun versionTypeCorruptRepairV1NeedsHookRestartDocumentedBbc() {
        val (cache, _) = bbcCacheAt(1_000L)
        // Running hook: a high-water version is established.
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 50L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        // App repairs the corrupt key and writes v1. The running hook sees
        // Good({B},1) as a stale decreasing version -> Unknown, sticky {A}.
        val rejected = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 1L) })
        assertTrue(rejected is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("A"), (rejected as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)

        // FRESH cache models a hook RESTART: high-water reset, so the very
        // same Good({B},1) is accepted. This is why a restart is required.
        val (freshCache, _) = bbcCacheAt(1_000L)
        val applied = freshCache.getBbc(emptySet(), reader = { Pair(setOf("B"), 1L) })
        assertTrue(applied is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("B"), (applied as EffectivePolicyCache.BbcPolicy.Good).packages)
    }
}
