// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase3: sanitize traversal bound (MAX*8), truncation seed priority,
// hostile isEmpty / iterator Error paths already pinned elsewhere.
// Phase3A (H4): policy truncation is no longer silent — truncationDrops
// counts the entries dropped by the caps.
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

    // H4: a draft exceeding MAX_POLICY_ENTRIES yields exactly
    // MAX_POLICY_ENTRIES entries, the drop counter advances (never throws),
    // and effective() counts the exact seed-first extras it truncates.
    @Test
    fun truncationDropsCountCapOverflows() {
        val before = PolicySource.truncationDrops.get()
        val many = (1..600).map { "drop.pkg.$it" }.toSet()
        val out = PolicySource.sanitize(many)
        assertEquals(PolicySource.MAX_POLICY_ENTRIES, out.size)
        assertTrue(PolicySource.truncationDrops.get() > before)
        // effective(): sanitized seed (400) + sanitized remote (400) = 800
        // merged, truncated to the cap with exactly 288 extras dropped.
        val seed = (1..400).map { "merge.seed.$it" }.toSet()
        val remote = (1..400).map { "merge.remote.$it" }.toSet()
        val beforeEffective = PolicySource.truncationDrops.get()
        val merged = PolicySource.effective(seed, remote, nonEmptyPolicyGateOpen = true)
        assertEquals(PolicySource.MAX_POLICY_ENTRIES, merged.size)
        assertEquals(beforeEffective + 288L, PolicySource.truncationDrops.get())
    }

    @Test
    fun effectiveTruncationIsSeedPriority() {
        // Seed added first, so truncation survivors are seed-first by intent
        // (fail-closed: never shrinks the seed to make room for remote).
        val seed = (1..400).map { "pkg.seed.$it" }.toSet()
        val remote = (1..400).map { "pkg.remote.$it" }.toSet()
        val result = PolicySource.effective(seed, remote, nonEmptyPolicyGateOpen = true)
        assertEquals(512, result.size)
        // Property, not order: seed never shrinks; cap holds. (The merged
        // prefix order across HashSet iteration is not a contract.)
        assertTrue(result.containsAll(seed.take(512).toSet()))
        // Pure-seed overflow also truncates seed-ordered — property form.
        val bigSeed = (1..600).map { "s$it" }.toSet()
        val seedOnly = PolicySource.effective(bigSeed, emptySet(), nonEmptyPolicyGateOpen = true)
        assertEquals(512, seedOnly.size)
        assertTrue(seedOnly.containsAll(bigSeed.take(512).toSet()))
    }
}
