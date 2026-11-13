// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// JVM unit tests for the pure policy source (PolicySource).
class PolicySourceTest {

    // Phase 4:全域 reader 還原 (跨測試防洩漏):每個測試前快照、後還原,
    // 與既有 per-test try/finally 雙層保險 (方法亂序/失敗仍不污染後續)。
    private var savedReader: (() -> Set<String>?)? = null

    @Before
    fun saveReader() {
        savedReader = PolicySource.remotePolicyReader
    }

    @After
    fun restoreReader() {
        val saved = savedReader
        if (saved != null) PolicySource.remotePolicyReader = saved
    }

    @Test
    fun staticSeedIsEmptyObserveOnly() {
        assertTrue(PolicySource.STATIC_SEED.isEmpty())
        assertTrue(PolicySource.effective().isEmpty())
        assertFalse(PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE)
    }

    @Test
    fun validPackagesAccepted() {
        assertTrue(PolicySource.isValidPackage("com.example.app"))
        assertTrue(PolicySource.isValidPackage("com.samsung.android.messaging"))
        assertTrue(PolicySource.isValidPackage("a"))
    }

    @Test
    fun invalidPackagesRejected() {
        assertFalse(PolicySource.isValidPackage(""))
        assertFalse(PolicySource.isValidPackage("has space"))
        assertFalse(PolicySource.isValidPackage("semi;colon"))
        assertFalse(PolicySource.isValidPackage("quote\"x"))
        assertFalse(PolicySource.isValidPackage("slash/x"))
        assertFalse(PolicySource.isValidPackage("dollar\$x"))
    }

    @Test
    fun sanitizeDropsInvalidKeepsValid() {
        assertEquals(
            setOf("com.ok.app"),
            PolicySource.sanitize(setOf("com.ok.app", "", "bad pkg", "a/b")),
        )
    }

    @Test
    fun sanitizeDropsNullDefensively() {
        val raw: Set<String?> = setOf("a", null, "bad pkg")
        assertEquals(setOf("a"), PolicySource.sanitize(raw))
    }

    @Test
    fun effectiveMergesSeedAndRemoteUnionOnly() {
        // Merge/sanitize behavior is covered via the test seam; runtime default
        // gate stays closed (fail-closed) and is covered separately.
        assertEquals(
            setOf("a", "b", "c"),
            PolicySource.effective(setOf("a", "b"), setOf("b", "c"), nonEmptyPolicyGateOpen = true),
        )
    }

    @Test
    fun effectiveSanitizesBothSides() {
        // Merge/sanitize behavior is covered via the test seam; runtime default
        // gate stays closed (fail-closed) and is covered separately.
        assertEquals(
            setOf("a"),
            PolicySource.effective(setOf("a", "bad pkg"), setOf("bad/remote"), nonEmptyPolicyGateOpen = true),
        )
    }

    @Test
    fun effectiveEmptyStaysEmpty() {
        assertTrue(PolicySource.effective(emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun nonEmptyPolicyBlockedWhenGateClosedFailClosed() {
        // Default gate (omitted) is closed: sanitized non-empty merge must not
        // reach callers. This is a code gate only, not device verification.
        assertTrue(
            PolicySource.effective(setOf("a", "b"), setOf("c")).isEmpty(),
        )
        assertTrue(
            PolicySource.effective(setOf("a", "b"), setOf("c"), nonEmptyPolicyGateOpen = false).isEmpty(),
        )
    }

    @Test
    fun sanitizedNonEmptyPolicyBlockedWhenGateClosedFailClosed() {
        // Even when one side sanitizes to empty, a remaining sanitized non-empty
        // result is still blocked while the gate is closed.
        assertTrue(
            PolicySource.effective(setOf("a", "bad pkg"), setOf("bad/remote")).isEmpty(),
        )
    }

    @Test
    fun defaultRemoteSnapshotIsEmptyObserveOnly() {
        // Contract: every test that mutates remotePolicyReader restores the
        // previous value, so this sees the shipped default (empty) regardless
        // of method execution order.
        assertTrue(PolicySource.remotePolicySnapshot().isEmpty())
    }

    @Test
    fun injectedRemoteReaderMergesUnionSanitized() {
        val prev = PolicySource.remotePolicyReader
        try {
            PolicySource.remotePolicyReader = { setOf("com.ok.app", "bad pkg", "") }
            assertEquals(setOf("com.ok.app"), PolicySource.remotePolicySnapshot())
            assertEquals(
                setOf("com.seed.app", "com.ok.app"),
                PolicySource.effective(
                    setOf("com.seed.app", "bad seed"),
                    PolicySource.remotePolicySnapshot(),
                    nonEmptyPolicyGateOpen = true,
                ),
            )
        } finally {
            PolicySource.remotePolicyReader = prev
        }
    }

    @Test
    fun throwingReaderFailsClosedToEmpty() {
        val prev = PolicySource.remotePolicyReader
        try {
            PolicySource.remotePolicyReader = { throw IllegalStateException("boom") }
            assertTrue(PolicySource.remotePolicySnapshot().isEmpty())
            assertTrue(
                PolicySource.effective(emptySet(), PolicySource.remotePolicySnapshot()).isEmpty(),
            )
            // A failing remote side contributes nothing but never shrinks the seed.
            assertEquals(
                setOf("com.seed.app"),
                PolicySource.effective(
                    setOf("com.seed.app"),
                    PolicySource.remotePolicySnapshot(),
                    nonEmptyPolicyGateOpen = true,
                ),
            )
        } finally {
            PolicySource.remotePolicyReader = prev
        }
    }

    // Phase 4:AssertionError (Error,非 Exception) 亦 fail-closed 到空
    // (store/快取層抓 Throwable 對齊;此處鎖定 PolicySource 不拋)。
    @Test
    fun assertionErrorReaderFailsClosedToEmpty() {
        val prev = PolicySource.remotePolicyReader
        try {
            PolicySource.remotePolicyReader = { throw AssertionError("boom") }
            assertTrue(PolicySource.remotePolicySnapshot().isEmpty())
            assertEquals(
                setOf("com.seed.app"),
                PolicySource.effective(
                    setOf("com.seed.app"),
                    PolicySource.remotePolicySnapshot(),
                    nonEmptyPolicyGateOpen = true,
                ),
            )
        } finally {
            PolicySource.remotePolicyReader = prev
        }
    }

    // Phase 4:null reader 結果 (failed read 信號) fail-closed 到空,
    // 不拋、不缓存空政策 (空快照≠成功讀,見 EffectivePolicyCache)。
    @Test
    fun nullReaderFailsClosedToEmpty() {
        val prev = PolicySource.remotePolicyReader
        try {
            PolicySource.remotePolicyReader = { null }
            assertTrue(PolicySource.remotePolicySnapshot().isEmpty())
            assertEquals(
                setOf("com.seed.app"),
                PolicySource.effective(
                    setOf("com.seed.app"),
                    PolicySource.remotePolicySnapshot(),
                    nonEmptyPolicyGateOpen = true,
                ),
            )
        } finally {
            PolicySource.remotePolicyReader = prev
        }
    }

    @Test
    fun effectiveTruncatesMergedSetToGlobalCap() {
        val seed = (1..300).map { "pkg.seed.$it" }.toSet()
        val remote = (1..300).map { "pkg.remote.$it" }.toSet()
        val result = PolicySource.effective(seed, remote, nonEmptyPolicyGateOpen = true)
        assertEquals(512, result.size)
        val expected = (seed.toList() + remote.toList().take(212)).toList()
        assertEquals(expected, result.toList())
    }

    @Test
    fun sanitizeBoundsAndDropsNonString() {
        val maxToken = "a".repeat(256)
        assertEquals(true, PolicySource.isValidPackage(maxToken))
        assertTrue(PolicySource.sanitize(setOf(maxToken)).contains(maxToken))
        val tooLong = "a".repeat(257)
        assertFalse(PolicySource.sanitize(setOf(tooLong)).contains(tooLong))

        val many = (1..600).map { "t$it" }.toSet()
        assertEquals(512, PolicySource.sanitize(many).size)

        assertEquals(setOf("a"), PolicySource.sanitize(setOf("a", 42 as Any?)))
    }

    // S7 (equality-only contract boundary, see the CONTRACT comment at
    // PolicySource.isValidPackage): the token grammar is a deliberate superset
    // of the Android package grammar and is safe ONLY because consumers use
    // pure equality/set membership. Pin the documented boundary: '..', 'a-',
    // 'com..x', '-' parse as valid-but-inert tokens — they survive sanitize()
    // unchanged, can never equal a real PackageManager-shaped package name
    // unless the exact literal was written into the policy, and any future
    // tightening or new non-equality reuse must show up as a test change
    // here (and re-validate against a real package grammar).
    @Test
    fun isValidPackageAdmitsSupersetGrammarAsValidButInertEqualityOnly() {
        assertTrue(PolicySource.isValidPackage(".."))
        assertTrue(PolicySource.isValidPackage("a-"))
        assertTrue(PolicySource.isValidPackage("-"))
        assertTrue(PolicySource.isValidPackage("com..x"))
        // Surviving sanitize() unchanged = accepted-but-useless tokens.
        assertEquals(setOf("..", "a-", "-", "com..x"),
            PolicySource.sanitize(setOf("..", "a-", "-", "com..x")))
        // Equality is exact: a useless token never equals a normal entry.
        assertFalse(PolicySource.sanitize(setOf("a-")).contains("a"))
        assertFalse(PolicySource.sanitize(setOf("com..x")).contains("com.x"))
    }
}
