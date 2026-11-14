// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Test

// P1-1 contract: no cross-process push on LSPosed API 102.0.0.
//
// Pins the corrected freshness model:
// - IXposedService 102.0.0 exposes no remote-preference callback transaction;
//   RemotePreferences fetches mMap once and only notifies LOCAL listeners;
//   XposedService.getRemotePreferences is computeIfAbsent cached forever.
// - Therefore a same-process listener NEVER fires for an app-process write.
// - Freshness is POLL ONLY: EffectivePolicyCache TTL re-reads the cached
//   instance; even the poll may return stale data until reboot.
// - Explicit invalidate() is the only cross-signal, and it is local: one
//   cache's invalidate() never affects another cache instance.
class CrossProcessFreshnessContractTest {

    @Test
    fun invalidateIsLocalOnly_doesNotAffectPeerCache() {
        val a = EffectivePolicyCache()
        val b = EffectivePolicyCache()
        assertEquals(
            setOf("x"),
            a.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("x") },
        )
        assertEquals(
            setOf("x"),
            b.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("x") },
        )
        // Invalidate A: B stays cached (no cross-instance push).
        a.invalidate()
        var bReads = 0
        assertEquals(
            setOf("x"),
            b.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                bReads++
                setOf("y")
            },
        )
        assertEquals(0, bReads)
        // A re-reads after its own invalidate.
        var aReads = 0
        assertEquals(
            setOf("y"),
            a.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                aReads++
                setOf("y")
            },
        )
        assertEquals(1, aReads)
    }

    @Test
    fun freshnessIsPollOnly_ttlExpiryForcesReread() {
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        assertEquals(
            setOf("a"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("a") },
        )
        // Without invalidate and before TTL, the cached value is served with
        // zero re-reads (no push could have arrived).
        var reads = 0
        assertEquals(
            setOf("a"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("b")
            },
        )
        assertEquals(0, reads)
        // After the TTL the next read polls again (the poll mechanism).
        time = 1_000L + 60_000L
        assertEquals(
            setOf("b"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("b")
            },
        )
        assertEquals(1, reads)
    }

    @Test
    fun staleCachePersistsUntilPollOrInvalidate() {
        // Pins the cache side of the API 102.0.0 contract: without an
        // explicit poll (TTL expiry) or invalidate(), the hook cache serves
        // the stale value forever — no cross-process write can have pushed
        // an update into this process.
        var time = 1_000L
        val cache = EffectivePolicyCache(nowMs = { time })
        assertEquals(
            setOf("stale"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) { setOf("stale") },
        )
        // Simulate "app wrote new value but no push arrived": the hook cache
        // still serves the stale value until TTL/invalidate.
        time = 2_000L
        var reads = 0
        assertEquals(
            setOf("stale"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("fresh")
            },
        )
        assertEquals(0, reads)
        // Advance past the TTL: the next read polls and picks up the fresh
        // value (the only freshness mechanism besides invalidate()).
        time = 1_000L + 60_000L + 1L
        assertEquals(
            setOf("fresh"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("fresh")
            },
        )
        assertEquals(1, reads)
        // T-F8: invalidate() arm — the other freshness signal: after an
        // explicit invalidate the very next read polls again.
        cache.invalidate()
        assertEquals(
            setOf("fresh-2"),
            cache.get(emptySet(), nonEmptyPolicyGateOpen = true) {
                reads++
                setOf("fresh-2")
            },
        )
        assertEquals(2, reads)
    }
}
