// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

// Pure (framework-free) self-scope active-flag contract.
//
// Direction: the hook writes, the app reads. When LSPosed injects this module
// into its own UI process (self scope: com.galaxydual.plus [+ .debug]),
// DualHook.onPackageLoaded marks the private prefs file below; the app-side
// HookStatusProvider reads the same file. Per-user automatically (each Android
// user has its own app data): user 0 and user 95 report independently.
//
// No IPC, no world-readable files, no system properties: plain MODE_PRIVATE
// prefs in the module's own package. Best-effort on the write side (any
// reflection failure just skips marking); fail-closed on the read side
// (missing file/keys = inactive). DEVICE-UNVERIFIED: round-trip never ran.
object HookState {
    const val PREFS_NAME = "galaxydual_hook_state"
    const val KEY_ACTIVE = "hook_active"
    const val KEY_VERSION = "hook_version"
    const val KEY_TIMESTAMP_MS = "hook_timestamp_ms"

    // Freshness window for the self-report: a stale mark (e.g. left behind by
    // an uninstalled module version) must not read as active forever. The app
    // side treats marks older than this as inactive; the hook side rewrites
    // the mark on every process start while injected. 24h bounds a stale
    // "active" display after the user disables the module to one day; exec
    // still needs a live su/Shizuku probe, so the impact is display-only.
    const val FRESH_WINDOW_MS = 24L * 60 * 60 * 1000

    // Own UI packages that may be injected for self-report. Release and debug
    // (applicationIdSuffix ".debug") are both listed; the scope.list,
    // manifest xposedscope, and HookScopes must carry the same entries.
    val SELF_PACKAGES: Set<String> = setOf(
        "com.galaxydual.plus",
        "com.galaxydual.plus.debug",
    )

    // Should this onPackageLoaded event be treated as the self-report mark?
    // Pure so JVM tests can lock it; DualHook delegates.
    fun shouldMarkSelf(packageName: String): Boolean =
        SELF_PACKAGES.contains(packageName)

    // Is a stored mark fresh enough to count as active? [nowMs] is injected
    // for tests; callers pass System.currentTimeMillis().
    fun isFresh(timestampMs: Long, nowMs: Long): Boolean {
        if (timestampMs <= 0 || nowMs < timestampMs) return false
        return nowMs - timestampMs <= FRESH_WINDOW_MS
    }
}
