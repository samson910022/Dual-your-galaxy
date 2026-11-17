// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM unit tests for the pure policy source (PolicySource).
class PolicySourceTest {

    @Test
    fun staticSeedIsEmptyObserveOnly() {
        assertTrue(PolicySource.STATIC_SEED.isEmpty())
        assertTrue(PolicySource.effective().isEmpty())
        assertTrue(PolicySource.NON_EMPTY_POLICY_EFFECTIVE_GATE)
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
        assertFalse(PolicySource.isValidPackage("com.openai.chatgpt\$top!"))
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
        // Merge/sanitize behavior is covered via the test seam; the runtime
        // default is now open (gate=true, verified on-device; see
        // docs/TAB7_BBC_INJECTION_PLAN.md) and is covered separately below.
        assertEquals(
            setOf("a", "b", "c"),
            PolicySource.effective(setOf("a", "b"), setOf("b", "c")),
        )
        assertEquals(
            setOf("a", "b", "c"),
            PolicySource.effective(setOf("a", "b"), setOf("b", "c"), nonEmptyPolicyGateOpen = true),
        )
    }

    @Test
    fun effectiveSanitizesBothSides() {
        // Merge/sanitize behavior is covered via the test seam; the runtime
        // default is now open (gate=true, verified on-device; see
        // docs/TAB7_BBC_INJECTION_PLAN.md).
        assertEquals(
            setOf("a"),
            PolicySource.effective(setOf("a", "bad pkg"), setOf("bad/remote")),
        )
    }

    @Test
    fun effectiveEmptyStaysEmpty() {
        assertTrue(PolicySource.effective(emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun nonEmptyPolicyAllowedWhenGateOpenDefault() {
        // Default gate (omitted) is now open after on-device verification on
        // Galaxy Tab S7 (One UI 5 / Android 13) and Galaxy S23 Ultra
        // (One UI 9 / Android 17): sanitized non-empty merge MUST reach callers.
        assertEquals(
            setOf("a", "b", "c"),
            PolicySource.effective(setOf("a", "b"), setOf("c")),
        )
    }

    @Test
    fun nonEmptyPolicyBlockedWhenGateClosedFailClosed() {
        // The fail-closed seam stays: with the gate explicitly closed, a
        // sanitized non-empty merge must NOT reach callers.
        assertTrue(
            PolicySource.effective(setOf("a", "b"), setOf("c"), nonEmptyPolicyGateOpen = false).isEmpty(),
        )
    }

    @Test
    fun sanitizedNonEmptyPolicyBlockedWhenGateClosedFailClosed() {
        // Even when one side sanitizes to empty, a remaining sanitized non-empty
        // result is still blocked while the gate is closed (explicit seam).
        assertTrue(
            PolicySource.effective(setOf("a", "bad pkg"), setOf("bad/remote"), nonEmptyPolicyGateOpen = false).isEmpty(),
        )
    }
}
