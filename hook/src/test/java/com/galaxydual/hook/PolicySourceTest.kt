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
        assertEquals(
            setOf("a", "b", "c"),
            PolicySource.effective(setOf("a", "b"), setOf("b", "c")),
        )
    }

    @Test
    fun effectiveSanitizesBothSides() {
        assertEquals(
            setOf("a"),
            PolicySource.effective(setOf("a", "bad pkg"), setOf("bad/remote")),
        )
    }

    @Test
    fun effectiveEmptyStaysEmpty() {
        assertTrue(PolicySource.effective(emptySet(), emptySet()).isEmpty())
    }
}
