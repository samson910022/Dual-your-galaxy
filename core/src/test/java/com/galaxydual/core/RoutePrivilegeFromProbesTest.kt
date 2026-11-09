// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

import org.junit.Assert.assertEquals
import org.junit.Test

// JVM unit tests for the pure privilege router.
// Locks the hook-neutral contract: routePrivilegeFromProbes is a pure function of the
// su (hasRoot) and Shizuku (hasShizuku) probes only. LSPosed is display-only
// and never grants privilege, so no hook input exists on this signature.
class RoutePrivilegeFromProbesTest {

    @Test
    fun neitherProbeYieldsNone() {
        assertEquals(Privilege.NONE, routePrivilegeFromProbes(hasShizuku = false, hasRoot = false))
    }

    @Test
    fun shizukuOnlyYieldsShell() {
        assertEquals(Privilege.SHELL, routePrivilegeFromProbes(hasShizuku = true, hasRoot = false))
    }

    @Test
    fun rootOnlyYieldsRoot() {
        assertEquals(Privilege.ROOT, routePrivilegeFromProbes(hasShizuku = false, hasRoot = true))
    }

    @Test
    fun bothProbesYieldRoot() {
        assertEquals(Privilege.ROOT, routePrivilegeFromProbes(hasShizuku = true, hasRoot = true))
    }
}
