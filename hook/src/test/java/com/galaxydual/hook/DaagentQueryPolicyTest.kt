// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Stage1 method-level apply path (JVM-testable pure-logic part).
//
// Pure WhitelistPolicy merge/helper fixtures (policy shapes only; not proof of
// actual Stage 1 hook targets). Models read-query result shapes through the
// shared WhitelistPolicy.apply decision plus the WhitelistPatch static-final
// ObserveOnly rule (DAUtility.getWhiteListPkgs[*] -> List/Set<String>,
// DualAppManager.*WhitelistPackage* -> Boolean; String[] union fixture is
// shape-only). Installed+whitelist composite queries (e.g.
// getAllInstalledWhitelistedPackages) are excluded from Stage 1 hook
// candidates (fail-closed; see docs/HOOK.md §1.3/§1.4). The DualHook
// install/callback itself needs an LSPosed daagent process and is
// DEVICE-UNVERIFIED (not covered here).
class DaagentQueryPolicyTest {

    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { logs.add(it) }

    @Test
    fun booleanQueryFlipsListedPackageOnly() {
        // isWhitelistedPackage("com.listed.app") == false -> true.
        assertEquals(true, WhitelistPolicy.apply(false, listOf("com.listed.app"), setOf("com.listed.app"), "m", log))
        // Unlisted package stays false (extend-only, never shrink/flip-out).
        assertEquals(false, WhitelistPolicy.apply(false, listOf("com.other.app"), setOf("com.listed.app"), "m", log))
        // Original true is never downgraded.
        assertEquals(true, WhitelistPolicy.apply(true, listOf("com.other.app"), setOf("com.listed.app"), "m", log))
    }

    @Test
    fun booleanQueryEmptyPolicyIsObserveOnlyUnchanged() {
        // Empty policy (today's seed): the query result passes through.
        val out = WhitelistPolicy.apply(false, listOf("com.listed.app"), emptySet(), "m", log)
        assertEquals(false, out)
        assertTrue("empty-policy boolean path should stay silent, was: $logs", logs.isEmpty())
    }

    @Test
    fun stringArrayQueryMergesExtrasOriginalUntouched() {
        // String[] merge helper fixture (policy shape only; not a Stage 1 hook
        // target; installed+whitelist composites excluded).
        val current = arrayOf("com.samsung.a")
        val out = WhitelistPolicy.apply(current, emptyList(), setOf("com.samsung.a", "com.listed.app"), "m", log)
        assertTrue(out is Array<*>)
        assertEquals(listOf("com.samsung.a", "com.listed.app"), (out as Array<*>).toList())
        assertEquals(listOf("com.samsung.a"), current.toList())
    }

    @Test
    fun stringArrayQueryEmptyPolicyReturnsSameInstance() {
        val current = arrayOf("com.samsung.a")
        assertSame(current, WhitelistPolicy.apply(current, emptyList(), emptySet(), "m", log))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun listQueryMergesExtras() {
        // DAUtility.getWhiteListPkgs shape: List<String> union, deduped.
        val out = WhitelistPolicy.apply(listOf("a"), emptyList(), setOf("a", "b"), "m", log)
        assertEquals(listOf("a", "b"), out)
    }

    @Test
    fun setQueryMergesAndKeepsSetType() {
        val out = WhitelistPolicy.apply(setOf("a"), emptyList(), setOf("a", "b"), "m", log)
        assertTrue("expected Set, got ${out?.javaClass?.name}", out is Set<*>)
        assertEquals(setOf("a", "b"), out)
    }

    @Test
    fun collectionQueriesEmptyPolicyReturnSameInstance() {
        val list = listOf("a")
        assertSame(list, WhitelistPolicy.apply(list, emptyList(), emptySet(), "m", log))
        val set = setOf("a")
        assertSame(set, WhitelistPolicy.apply(set, emptyList(), emptySet(), "m", log))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun staticFinalHolderPatchIsObserveOnly_methodLevelHookRequiredForPolicy() {
        // Static-final bypass evaluation: even with a non-empty policy the
        // holder patch must NOT write -- the method-level query hook above
        // is the only path that can carry the policy.
        val arr = arrayOf("a")
        val out = WhitelistPatch.apply(arr, true, setOf("b"), "DUAL_APP_WHITELIST_PACKAGES", "C", isFinal = true)
        assertTrue("final field must be ObserveOnly, was: $out", out is WhitelistPatch.Outcome.ObserveOnly)
        assertTrue(
            "reason should carry observe-only marker, was: $out",
            (out as WhitelistPatch.Outcome.ObserveOnly).reason.contains(WhitelistPatch.OBSERVE_ONLY_MARKER),
        )
        assertEquals(listOf("a"), arr.toList())
    }
}
