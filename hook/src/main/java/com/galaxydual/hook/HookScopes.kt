// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Pure (framework-free) scope constants shared by DualHook and JVM tests.
//
// Kept free of android.* / LibXposed types so plain JVM unit tests can lock
// the recommended LSPosed scopes without loading DualHook (which imports
// android.util.Log + LibXposed and is compileOnly at test runtime).
// Authoritative runtime list is META-INF/xposed/scope.list; CI enforces
// parity between that file, these constants, and the manifest xposedscope.
object HookScopes {
    // LSPosed scope pseudo-name for system_server (served exclusively via
    // onSystemServerStarting; never arrives as an onPackageLoaded name).
    const val SYSTEM_SCOPE = "system"

    // Real framework package name LSPosed delivers for the system_server
    // process via onPackageLoaded. Early-return WITHOUT detach() here;
    // it is NOT a hook target.
    const val ANDROID_PACKAGE = "android"
    const val DA_AGENT_PACKAGE = "com.samsung.android.da.daagent"

    // Recommended LSPosed scopes, mirrored in META-INF/xposed/scope.list.
    // H3 self-scope packages were evaluated and dropped (H4): the F4b-P2 file
    // marker (HookMarker, scopes system_server/daagent, proven by installed
    // hook counts + real-device S24 EACCES evidence) is the single hook-active
    // signal; a self-process prefs mark would add injection surface for weaker
    // (self-only) evidence. Keep this set minimal.
    // NOTE: com.android.settings stays out (D2): no settings-side hook
    // exists, so scoping it would only widen the injection surface.
    // SYSTEM_SCOPE never matches onPackageLoaded; it is kept here for
    // scope.list parity.
    val SUPPORTED_SCOPES: Set<String> = setOf(
        SYSTEM_SCOPE,
        DA_AGENT_PACKAGE,
    )
}
