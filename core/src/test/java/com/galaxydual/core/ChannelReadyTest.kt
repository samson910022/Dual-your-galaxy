// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM unit tests for the pure channel-readiness gate (H3/F4b).
// Locks the load-bearing routing change: ROOT is satisfied by su OR hook,
// SHELL by Shizuku, NONE never. In particular hook-only + NoOp stays
// read-only (fail closed).
class ChannelReadyTest {

    @Test
    fun rootNeedsSuOrHook() {
        assertTrue(channelReadyFor(Privilege.ROOT, hasRoot = true, hasShizuku = false, hasHook = false))
        assertTrue(channelReadyFor(Privilege.ROOT, hasRoot = false, hasShizuku = false, hasHook = true))
        assertTrue(channelReadyFor(Privilege.ROOT, hasRoot = true, hasShizuku = false, hasHook = true))
        assertFalse(channelReadyFor(Privilege.ROOT, hasRoot = false, hasShizuku = false, hasHook = false))
        // Shizuku alone does not satisfy a ROOT executor.
        assertFalse(channelReadyFor(Privilege.ROOT, hasRoot = false, hasShizuku = true, hasHook = false))
    }

    @Test
    fun shellNeedsShizukuOnly() {
        assertTrue(channelReadyFor(Privilege.SHELL, hasRoot = false, hasShizuku = true, hasHook = false))
        assertFalse(channelReadyFor(Privilege.SHELL, hasRoot = true, hasShizuku = false, hasHook = true))
        assertFalse(channelReadyFor(Privilege.SHELL, hasRoot = false, hasShizuku = false, hasHook = false))
    }

    @Test
    fun noneNeverReadyEvenWithHook() {
        assertFalse(channelReadyFor(Privilege.NONE, hasRoot = true, hasShizuku = true, hasHook = true))
        assertFalse(channelReadyFor(Privilege.NONE, hasRoot = false, hasShizuku = false, hasHook = true))
        assertFalse(channelReadyFor(Privilege.NONE, hasRoot = false, hasShizuku = false, hasHook = false))
    }
}
