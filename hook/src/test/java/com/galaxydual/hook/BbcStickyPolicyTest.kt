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

    // JVM tests have no behavioral seam that distinguishes an old Cleared
    // marker from a reset one after a higher Good (both are != the high-water),
    // so pin the high-water-owner invariant directly.
    private fun clearedMarker(cache: EffectivePolicyCache): Long {
        val field = EffectivePolicyCache::class.java.getDeclaredField("lastBbcClearedVersion")
        field.isAccessible = true
        return field.get(cache) as Long
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
    fun goodSameVersionShrinkThenOldContentReplayRejectedBbc() {
        // Exact replay sequence: Good({A,B},5) -> Good({B},5) (a legitimate
        // same-version shrink) -> replay Good({A,B},5). The final replay must
        // be Unknown and retain {B}; A is never re-attached.
        val (cache, _) = bbcCacheAt(1_000L)
        assertTrue(
            cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
                is EffectivePolicyCache.BbcPolicy.Good,
        )
        cache.invalidate()
        val shrunk = cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 5L) })
        assertTrue(shrunk is EffectivePolicyCache.BbcPolicy.Good)
        assertEquals(setOf("B"), (shrunk as EffectivePolicyCache.BbcPolicy.Good).packages)
        cache.invalidate()
        val replay = cache.getBbc(emptySet(), reader = { Pair(setOf("A", "B"), 5L) })
        assertTrue(replay is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(setOf("B"), (replay as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
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
        val gate = bbcInjectionGateOpen(PolicySource.BBC_AVAILABLE_APPS_INJECTION) { enabled }
        assertFalse(gate)
        var reads = 0
        val (cache, _) = bbcCacheAt(1_000L)
        val res = cache.getBbc(
            emptySet(),
            bbcGateOpen = gate,
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
        // readBbcInjectionEnabled returns null; bbcInjectionGateOpen maps null
        // to true so a transient IPC failure never silently disables the
        // shipped BBC effect ("off" is not a safety guarantee, issue #75).
        val absent = readBbcInjectionEnabled(object : BbcInjectionPrefsView {
            override fun getBoolean(key: String, defValue: Boolean) = defValue
        })
        assertEquals(true, absent)
        assertTrue(bbcInjectionGateOpen(PolicySource.BBC_AVAILABLE_APPS_INJECTION) { absent })

        val thrown = readBbcInjectionEnabled(object : BbcInjectionPrefsView {
            override fun getBoolean(key: String, defValue: Boolean): Boolean =
                throw RuntimeException("transient read failure")
        })
        assertEquals(null, thrown)
        assertTrue(bbcInjectionGateOpen(PolicySource.BBC_AVAILABLE_APPS_INJECTION) { thrown })

        // Full provider failure (null prefs) is also null => default on.
        assertTrue(bbcInjectionGateOpen(PolicySource.BBC_AVAILABLE_APPS_INJECTION) { null })
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
}
