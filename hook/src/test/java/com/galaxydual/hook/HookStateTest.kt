// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM unit tests for the self-scope active-flag contract (HookState).
class HookStateTest {

    @Test
    fun prefsContractConstants() {
        assertEquals("galaxydual_hook_state", HookState.PREFS_NAME)
        assertEquals("hook_active", HookState.KEY_ACTIVE)
        assertEquals(24L * 60 * 60 * 1000, HookState.FRESH_WINDOW_MS)
    }

    @Test
    fun marksSelfPackagesOnly() {
        assertTrue(HookState.shouldMarkSelf("com.galaxydual.plus"))
        assertTrue(HookState.shouldMarkSelf("com.galaxydual.plus.debug"))
        assertFalse(HookState.shouldMarkSelf("com.samsung.android.da.daagent"))
        assertFalse(HookState.shouldMarkSelf("android"))
        assertFalse(HookState.shouldMarkSelf("system"))
        assertFalse(HookState.shouldMarkSelf(""))
    }

    @Test
    fun freshWindowBoundaries() {
        val now = 1_000_000_000L
        assertTrue(HookState.isFresh(now, now))
        assertTrue(HookState.isFresh(now - HookState.FRESH_WINDOW_MS, now))
        assertFalse(HookState.isFresh(now - HookState.FRESH_WINDOW_MS - 1, now))
        assertFalse(HookState.isFresh(0, now))
        assertFalse(HookState.isFresh(-5, now))
        // Clock skew (stored mark in the future) reads inactive, not active.
        assertFalse(HookState.isFresh(now + 1, now))
    }
}
