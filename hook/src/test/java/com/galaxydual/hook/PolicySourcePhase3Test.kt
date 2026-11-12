// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase3: sanitize traversal bound (MAX*8), truncation seed priority,
// hostile isEmpty / iterator Error paths already pinned elsewhere.
class PolicySourcePhase3Test {

    @Test
    fun sanitizeTraversalBoundedAtMaxTimesEight() {
        // 5000 invalid tokens (> 4096 = 512*8) plus one valid token at the very
        // end: traversal must stop at the bound, so the trailing valid token
        // is never reached — but sanitize stays bounded and never throws.
        val raw = LinkedHashSet<Any?>()
        repeat(5_000) { raw.add("bad token $it") }
        raw.add("com.tail.app")
        val out = PolicySource.sanitize(raw)
        assertTrue(out.size <= PolicySource.MAX_POLICY_ENTRIES)
        // Bound holds even for hostile huge invalid payloads (no OOM/hang).
        assertEquals(0, out.size)
    }

    @Test
    fun sanitizeRespectsValidCapWithinBound() {
        val many = (1..600).map { "t$it" }.toSet()
        assertEquals(512, PolicySource.sanitize(many).size)
    }

    @Test
    fun effectiveTruncationIsSeedPriority() {
        // Seed added first, so truncation survivors are seed-first by intent
        // (fail-closed: never shrinks the seed to make room for remote).
        val seed = (1..400).map { "pkg.seed.$it" }.toSet()
        val remote = (1..400).map { "pkg.remote.$it" }.toSet()
        val result = PolicySource.effective(seed, remote, nonEmptyPolicyGateOpen = true)
        assertEquals(512, result.size)
        val list = result.toList()
        assertEquals(seed.toList(), list.take(400))
        assertEquals(remote.toList().take(112), list.drop(400))
        // Pure-seed overflow also truncates seed-ordered.
        val bigSeed = (1..600).map { "s$it" }.toSet()
        val seedOnly = PolicySource.effective(bigSeed, emptySet(), nonEmptyPolicyGateOpen = true)
        assertEquals(512, seedOnly.size)
        assertEquals(bigSeed.toList().take(512), seedOnly.toList())
    }
}
