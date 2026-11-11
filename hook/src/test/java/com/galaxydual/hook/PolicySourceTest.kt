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
}
