// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// P2-3: PolicySource.effective / sanitize must be Throwable-safe standalone.
//
// A hostile set view (iterator / hasNext / next / isEmpty throwing,
// including Error) must fail closed instead of propagating out of the hook
// chain: hostile remote -> seed-only; hostile seed -> empty.
class HostileEffectiveTest {

    private fun hostileSet(): Set<String> = object : AbstractSet<String>() {
        override val size: Int get() = throw IllegalStateException("boom-size")
        override fun iterator(): Iterator<String> = throw IllegalStateException("boom-iterator")
    }

    private fun hostileSeedSet(): Set<String?> = object : AbstractSet<String?>() {
        override val size: Int get() = 1
        override fun iterator(): Iterator<String?> = throw AssertionError("boom-seed")
    }

    @Test
    fun hostileRemoteFallsBackToSeedOnly() {
        val result = PolicySource.effective(
            seed = setOf("com.seed.app"),
            remote = hostileSet() as Set<String?>,
            nonEmptyPolicyGateOpen = true,
        )
        assertEquals(setOf("com.seed.app"), result)
    }

    @Test
    fun hostileSeedFallsBackToEmpty() {
        val result = PolicySource.effective(
            seed = hostileSeedSet(),
            remote = setOf("com.remote.app"),
            nonEmptyPolicyGateOpen = true,
        )
        // Seed hostile -> seed contributes nothing; remote alone survives.
        assertEquals(setOf("com.remote.app"), result)
    }

    @Test
    fun bothHostileFallsBackToEmpty() {
        val result = PolicySource.effective(
            seed = hostileSeedSet(),
            remote = hostileSet() as Set<String?>,
            nonEmptyPolicyGateOpen = true,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun hostileSanitizeReturnsWithoutThrowing() {
        val result = PolicySource.sanitize(hostileSet())
        assertTrue(result.isEmpty())
    }

    @Test
    fun hostileIsEmptyDoesNotPropagate() {
        val hostileEmpty: Set<String?> = object : AbstractSet<String?>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<String?> = emptyList<String?>().iterator()
            override fun isEmpty(): Boolean = throw IllegalStateException("boom-isEmpty")
        }
        val result = PolicySource.effective(
            seed = setOf("a"),
            remote = hostileEmpty,
            nonEmptyPolicyGateOpen = true,
        )
        // isEmpty threw -> treated as non-empty -> sanitize runs on the real
        // (empty) iterator -> seed-only.
        assertEquals(setOf("a"), result)
    }
}
