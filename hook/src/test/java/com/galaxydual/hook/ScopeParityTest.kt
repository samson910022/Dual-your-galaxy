// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase H1: scope parity lock.
//
// Fail-closed rule: the recommended LSPosed scopes must stay exactly
// {system, daagent}. Adding an unused scope (e.g. com.android.settings with
// no settings-side hook) only widens the injection surface; removing a needed
// scope silently disables system_server / daagent hooks.
//
// HookScopes is pure JVM (no android.*/LibXposed imports) so it loads on the
// plain unit-test classpath; DualHook itself cannot load here (compileOnly
// LibXposed + android.util.Log). File-level parity with
// META-INF/xposed/scope.list and hook AndroidManifest xposedscope is enforced
// by the CI "Scope parity" step (shell grep, no Android SDK needed).
class ScopeParityTest {

    @Test
    fun supportedScopesStayMinimal() {
        assertEquals(
            setOf(
                HookScopes.SYSTEM_SCOPE,
                HookScopes.DA_AGENT_PACKAGE,
            ),
            HookScopes.SUPPORTED_SCOPES,
        )
    }

    @Test
    fun systemScopeConstantIsLsposedPseudoName() {
        assertEquals("system", HookScopes.SYSTEM_SCOPE)
    }

    @Test
    fun daAgentPackageConstantUnchanged() {
        assertEquals("com.samsung.android.da.daagent", HookScopes.DA_AGENT_PACKAGE)
    }

    @Test
    fun androidFrameworkPackageNeverInSupportedScopes() {
        // "android" is the real framework package LSPosed delivers for the
        // system_server process via onPackageLoaded. It must early-return
        // WITHOUT detach() (see DualHook.onPackageLoaded), never be a scope.
        assertFalse(HookScopes.SUPPORTED_SCOPES.contains(HookScopes.ANDROID_PACKAGE))
        assertEquals("android", HookScopes.ANDROID_PACKAGE)
    }

    @Test
    fun noSettingsScope() {
        // D2: no settings-side hook exists, so scoping settings would only
        // widen injection. Fail the build if it ever comes back.
        assertTrue(
            HookScopes.SUPPORTED_SCOPES.none { it.contains("settings", ignoreCase = true) },
        )
    }
}
