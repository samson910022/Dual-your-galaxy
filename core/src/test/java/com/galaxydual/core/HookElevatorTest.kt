// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM unit tests for the LSPosed hook elevator (H3/F4b seam).
class HookElevatorTest {

    // Minimal runBlocking substitute (core has no coroutines dependency):
    // works because HookElevator never actually suspends.
    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(r: Result<T>) {
                    result = r
                }
            },
        )
        return (result ?: error("suspend block never resumed")).getOrThrow()
    }

    @Test
    fun declaresRootLevel() {
        assertEquals(Privilege.ROOT, HookElevator().level)
    }

    @Test
    fun inactiveReportsNoneFailClosed() {
        val res = runSuspend { HookElevator(activeProvider = { false }).probe() }
        assertEquals(Privilege.NONE, res.level)
        assertFalse(res.hasRoot)
        assertFalse(res.hasShizuku)
        assertEquals(-1, res.dualUserId)
    }

    @Test
    fun activeReportsRootWithoutDualId() {
        val res = runSuspend { HookElevator(activeProvider = { true }).probe() }
        assertEquals(Privilege.ROOT, res.level)
        assertTrue(res.hasRoot)
        assertFalse(res.hasShizuku)
        // Hook carries no pm execution; dual id comes from su/Shizuku probes.
        assertEquals(-1, res.dualUserId)
    }

    @Test
    fun defaultProviderIsInactive() {
        val res = runSuspend { HookElevator().probe() }
        assertEquals(Privilege.NONE, res.level)
    }

    @Test
    fun throwingProviderIsInactive() {
        val res = runSuspend { HookElevator(activeProvider = { throw IllegalStateException("boom") }).probe() }
        assertEquals(Privilege.NONE, res.level)
        assertFalse(res.hasRoot)
    }
}
