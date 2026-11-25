// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

// R5-01 pins: the ENTRY clock observation and the cache's own clock sample are
// two DIFFERENT observations. An untrusted entry sample must fail closed on its
// own, without relying on (or being laundered by) whatever the cache samples
// later — otherwise a healthy cache clock can serve a cached Good without ever
// reading the coherent consent/opt-out gate. Every test drives the exact
// composition used by production bbcPolicy() (bbcPolicyWithEntryClock), not a
// Boolean stand-in for the coherent gate.
class BbcEntryClockDistrustTest {

    private class Clock(initial: Long) {
        val value = AtomicLong(initial)
        @Volatile var throwing = false

        fun now(): Long {
            if (throwing) throw RuntimeException("clock dead")
            return value.get()
        }
    }

    private fun cacheWith(clock: Clock): EffectivePolicyCache = EffectivePolicyCache(
        nowMs = { clock.now() },
        stickyLastGood = true,
    )

    private fun fieldLong(cache: EffectivePolicyCache, name: String): Long {
        val f = EffectivePolicyCache::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(cache) as Long
    }

    private fun fieldLongNullable(cache: EffectivePolicyCache, name: String): Long? {
        val f = EffectivePolicyCache::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(cache) as Long?
    }

    @Suppress("UNCHECKED_CAST")
    private fun sticky(cache: EffectivePolicyCache): Set<String>? {
        val f = EffectivePolicyCache::class.java.getDeclaredField("lastGoodSticky")
        f.isAccessible = true
        return f.get(cache) as Set<String>?
    }

    @Suppress("UNCHECKED_CAST")
    private fun canonical(cache: EffectivePolicyCache): Set<String>? {
        val f = EffectivePolicyCache::class.java.getDeclaredField("lastAcceptedBbcCanonical")
        f.isAccessible = true
        return f.get(cache) as Set<String>?
    }

    // Counters for the two pref-side effects the bypass must never trigger:
    // executor submissions (one bounded task) and getAll() snapshots.
    private class CoherentReads {
        var submissions = 0
        var getAll = 0

        fun gate(): Boolean {
            submissions++
            getAll++
            return true
        }
    }

    private fun drive(
        cache: EffectivePolicyCache,
        clockOk: Boolean,
        compileTimeGate: Boolean,
        coherent: CoherentReads,
        readerCalls: () -> Unit,
        result: () -> Pair<Set<String>?, Long?> = { Pair(null, null) },
    ): EffectivePolicyCache.BbcPolicy = bbcPolicyWithEntryClock(
        cache = cache,
        clockOk = clockOk,
        compileTimeGate = compileTimeGate,
        seed = emptySet(),
        coherentGate = { coherent.gate() },
        reader = { readerCalls(); result() },
    )

    private fun assertUnknownEmpty(policy: EffectivePolicyCache.BbcPolicy) {
        assertTrue("expected Unknown(emptySet()), got $policy", policy is EffectivePolicyCache.BbcPolicy.Unknown)
        assertEquals(emptySet<String>(), (policy as EffectivePolicyCache.BbcPolicy.Unknown).lastGood)
    }

    @Test
    fun entryClockDistrustWithHealthyCacheClockMustNotServeCachedGood() {
        // Pre-cache Good({A}) + sticky under a trusted clock. The ENTRY sample
        // is then untrusted while the CACHE clock stays healthy: the old
        // fake-open composition let getBbc() serve the cached Good here.
        val clock = Clock(1_000L)
        val cache = cacheWith(clock)
        assertEquals(
            EffectivePolicyCache.BbcPolicy.Good(setOf("A")),
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }),
        )
        val epoch0 = fieldLong(cache, "clockEpoch")
        val highWater0 = fieldLongNullable(cache, "lastTrustedClockMs")
        val expired0 = cache.bbcStickyExpiredCount.get()
        val unknown0 = cache.bbcUnknownCount.get()
        val coherent = CoherentReads()
        var readerCalls = 0

        val result = drive(cache, clockOk = false, compileTimeGate = true, coherent, readerCalls = { readerCalls++ })

        assertUnknownEmpty(result)
        assertEquals("no coherent submission", 0, coherent.submissions)
        assertEquals("no pref getAll", 0, coherent.getAll)
        assertEquals("no policy reader call / no merge", 0, readerCalls)
        assertTrue("epoch must fence in-flight reads", fieldLong(cache, "clockEpoch") > epoch0)
        assertNull("sticky must be dropped", sticky(cache))
        assertFalse("cached freshness must be revoked", cache.isCached())
        assertEquals(1L, cache.bbcStickyExpiredCount.get() - expired0)
        assertEquals(1L, cache.bbcUnknownCount.get() - unknown0)
        // Evidence retained; the decision never consulted the cache's clock.
        assertEquals(setOf("A"), canonical(cache))
        assertEquals(1L, fieldLong(cache, "lastBbcSeenVersion"))
        assertEquals(highWater0, fieldLongNullable(cache, "lastTrustedClockMs"))

        // A healthy recovery must not serve stale Good/sticky without a
        // successful trusted read; a fresh successful read may re-establish it.
        val after = cache.getBbc(emptySet(), reader = { readerCalls++; Pair(null, null) })
        assertUnknownEmpty(after)
        // Past the 5s failure cooldown, a fresh trusted read may re-establish
        // the policy — it is NOT served from the discarded cache/sticky.
        clock.value.set(1_000L + 6_000L)
        val recovered = cache.getBbc(emptySet(), reader = { readerCalls++; Pair(setOf("A"), 2L) })
        assertEquals(EffectivePolicyCache.BbcPolicy.Good(setOf("A")), recovered)
    }

    @Test
    fun entryFalseCacheSkewedStillFailsClosed() {
        // Two DIFFERENT observations: the cache's clock is now BACKWARDS vs its
        // high-water, but that must not be what triggers the fail-closed path.
        val clock = Clock(1_000L)
        val cache = cacheWith(clock)
        assertEquals(
            EffectivePolicyCache.BbcPolicy.Good(setOf("A")),
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }),
        )
        val epoch0 = fieldLong(cache, "clockEpoch")
        val highWater0 = fieldLongNullable(cache, "lastTrustedClockMs")
        clock.value.set(900L)
        val coherent = CoherentReads()
        var readerCalls = 0

        val result = drive(cache, clockOk = false, compileTimeGate = true, coherent, readerCalls = { readerCalls++ })

        assertUnknownEmpty(result)
        assertEquals(0, coherent.submissions)
        assertEquals(0, coherent.getAll)
        assertEquals(0, readerCalls)
        assertTrue(fieldLong(cache, "clockEpoch") > epoch0)
        assertNull(sticky(cache))
        assertEquals("entry path must not sample the cache clock", highWater0, fieldLongNullable(cache, "lastTrustedClockMs"))
        // Recovery at a healthy-but-forward clock still cannot reuse stale data.
        clock.value.set(1_001L)
        assertUnknownEmpty(cache.getBbc(emptySet(), reader = { readerCalls++; Pair(null, null) }))
    }

    @Test
    fun entryFalseCacheThrowingStillFailsClosed() {
        // The cache clock is DEAD at the second observation; the explicit entry
        // path must stay deterministic and clock-independent (no throw).
        val clock = Clock(1_000L)
        val cache = cacheWith(clock)
        assertEquals(
            EffectivePolicyCache.BbcPolicy.Good(setOf("A")),
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }),
        )
        val epoch0 = fieldLong(cache, "clockEpoch")
        val highWater0 = fieldLongNullable(cache, "lastTrustedClockMs")
        clock.throwing = true
        val coherent = CoherentReads()
        var readerCalls = 0

        val result = drive(cache, clockOk = false, compileTimeGate = true, coherent, readerCalls = { readerCalls++ })

        assertUnknownEmpty(result)
        assertEquals(0, coherent.submissions)
        assertEquals(0, coherent.getAll)
        assertEquals(0, readerCalls)
        assertTrue(fieldLong(cache, "clockEpoch") > epoch0)
        assertNull(sticky(cache))
        assertEquals(highWater0, fieldLongNullable(cache, "lastTrustedClockMs"))
        clock.throwing = false
        assertUnknownEmpty(cache.getBbc(emptySet(), reader = { readerCalls++; Pair(null, null) }))
    }

    @Test
    fun entryTrueCacheUntrustedStillFailsClosedOnMainPath() {
        // Existing main-path invariant: healthy ENTRY sample + untrusted cache
        // sample still fails closed inside getBbc(), with no gate/reader work.
        val clock = Clock(1_000L)
        val cache = cacheWith(clock)
        assertEquals(
            EffectivePolicyCache.BbcPolicy.Good(setOf("A")),
            cache.getBbc(emptySet(), reader = { Pair(setOf("A"), 1L) }),
        )
        val epoch0 = fieldLong(cache, "clockEpoch")
        clock.throwing = true
        val coherent = CoherentReads()
        var readerCalls = 0

        val result = drive(cache, clockOk = true, compileTimeGate = true, coherent, readerCalls = { readerCalls++ })

        assertUnknownEmpty(result)
        // getBbc()'s own untrusted sample still evaluates the REAL gate once to
        // preserve its gate-closed sentinel (no fake-open wrapper), then fails
        // closed before any policy read. Only the policy reader must be zero.
        assertEquals(1, coherent.submissions)
        assertEquals(1, coherent.getAll)
        assertEquals(0, readerCalls)
        assertTrue(fieldLong(cache, "clockEpoch") > epoch0)
        assertNull(sticky(cache))
        clock.throwing = false
        assertUnknownEmpty(cache.getBbc(emptySet(), reader = { readerCalls++; Pair(null, null) }))
    }

    @Test
    fun entryFalseSwitchOffIsZeroTouchEvenWithDeadCacheClock() {
        // Compile-time switch OFF: Cleared(-1) with ZERO pref reads, ZERO
        // submissions and ZERO cache mutation — even when the cache's own clock
        // is dead (proving the entry path samples nothing at all).
        val clock = Clock(1_000L)
        val cache = cacheWith(clock)
        assertEquals(
            EffectivePolicyCache.BbcPolicy.Good(setOf("B")),
            cache.getBbc(emptySet(), reader = { Pair(setOf("B"), 1L) }),
        )
        val epoch0 = fieldLong(cache, "clockEpoch")
        val highWater0 = fieldLongNullable(cache, "lastTrustedClockMs")
        val expired0 = cache.bbcStickyExpiredCount.get()
        clock.throwing = true
        val coherent = CoherentReads()
        var readerCalls = 0

        val result = drive(cache, clockOk = false, compileTimeGate = false, coherent, readerCalls = { readerCalls++ })

        assertEquals(EffectivePolicyCache.BbcPolicy.Cleared(-1L), result)
        assertEquals(0, coherent.submissions)
        assertEquals(0, coherent.getAll)
        assertEquals(0, readerCalls)
        assertEquals("no epoch bump", epoch0, fieldLong(cache, "clockEpoch"))
        assertEquals("no sticky discard", expired0, cache.bbcStickyExpiredCount.get())
        assertEquals("no clock sample", highWater0, fieldLongNullable(cache, "lastTrustedClockMs"))
        assertTrue("cached Good untouched", cache.isCached())
        clock.throwing = false
        assertEquals(
            "recovery serves the untouched cached Good with zero reads",
            EffectivePolicyCache.BbcPolicy.Good(setOf("B")),
            cache.getBbc(emptySet(), reader = { readerCalls++; Pair(null, null) }),
        )
        assertEquals(0, readerCalls)
    }

    @Test
    fun healthyEntryUsesTheRealCoherentGate() {
        // The healthy-clock arm must still call getBbc() with the real coherent
        // provider: exactly one bounded submission / getAll snapshot and one
        // policy read; no fake-open shortcut on this path either.
        val clock = Clock(1_000L)
        val cache = cacheWith(clock)
        val coherent = CoherentReads()
        var readerCalls = 0

        val result = drive(
            cache,
            clockOk = true,
            compileTimeGate = true,
            coherent,
            { readerCalls++ },
        ) { Pair(setOf("A"), 1L) }

        assertEquals(EffectivePolicyCache.BbcPolicy.Good(setOf("A")), result)
        assertEquals(1, coherent.submissions)
        assertEquals(1, coherent.getAll)
        assertEquals(1, readerCalls)
    }
}
