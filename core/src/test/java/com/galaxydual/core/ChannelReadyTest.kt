// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM unit tests for the pure channel-readiness gate (H2/H4).
// Locks the F4b-P2 principle (#19): the hook-enable signal is display-only
// and never satisfies readiness. A ROOT executor needs its own su probe;
// hook presence (or absence) changes nothing about readiness.
class ChannelReadyTest {

    @Test
    fun rootNeedsSuProbe() {
        assertTrue(channelReadyFor(Privilege.ROOT, hasRoot = true, hasShizuku = false))
        assertFalse(channelReadyFor(Privilege.ROOT, hasRoot = false, hasShizuku = false))
        // Shizuku alone does not satisfy a ROOT executor.
        assertFalse(channelReadyFor(Privilege.ROOT, hasRoot = false, hasShizuku = true))
    }

    @Test
    fun shellNeedsShizukuOnly() {
        assertTrue(channelReadyFor(Privilege.SHELL, hasRoot = false, hasShizuku = true))
        assertFalse(channelReadyFor(Privilege.SHELL, hasRoot = true, hasShizuku = false))
        assertFalse(channelReadyFor(Privilege.SHELL, hasRoot = false, hasShizuku = false))
    }

    @Test
    fun noneNeverReady() {
        assertFalse(channelReadyFor(Privilege.NONE, hasRoot = true, hasShizuku = true))
        assertFalse(channelReadyFor(Privilege.NONE, hasRoot = false, hasShizuku = false))
    }
}
