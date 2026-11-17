// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.content.SharedPreferences
import android.util.Log

// Process-wide volatile snapshot of the remote policy ("galaxydual_policy"
// group, "packages" key, StringSet) written by the module's own app process
// via libxposed RemotePreferences (Vector v2.2, API 102). The hooked side
// reads it here and NEVER performs IPC on the hot path; the app process is
// the only writer, daemon is the source of truth.
//
// Listener rules (Vector delivers changes on a binder thread inside
// InjectedModuleService.onUpdateRemotePreferences): an uncaught exception
// there makes the daemon DROP OUR SUBSCRIPTION for the rest of this boot,
// silently. So:
// - the listener body is a total try/catch, never throws;
// - on ANY failure the previous snapshot is kept (fail-closed keeps the last
//   known-good policy; we never surface partially-read state);
// - reads are served from @Volatile state only (no SharedPreferences touch on
//   the interceptor hot path, no binder, no allocations beyond the snapshot set).
//
// Covered by JVM unit tests (RemotePolicyStateTest, hand-rolled fake
// SharedPreferences). PolicySource.sanitize is also locked by tests.
// Observe-only whenever the group is absent/empty (effective() returns empty
// regardless of the gate). NON_EMPTY_POLICY_EFFECTIVE_GATE ships open; the
// explicit fail-closed seam is locked by PolicySourceTest.
internal object RemotePolicyState {
    private const val TAG = "GalaxyDualHook"
    internal const val GROUP = "galaxydual_policy"
    private const val KEY_PACKAGES = "packages"

    @Volatile
    private var snapshot: Set<String> = emptySet()

    @Volatile
    private var initialized = false

    fun get(): Set<String> = snapshot

    // One-shot per process; called by DualHook at the injection points
    // (onSystemServerStarting / onPackageLoaded) so the first hooked call
    // already sees a warmed cache. [prefsProvider] is invoked at most once.
    fun ensureInitialized(prefsProvider: () -> SharedPreferences?) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val prefs = prefsProvider()
                if (prefs == null) {
                    logi("RemotePreferences provider returned null, staying fail-closed")
                    snapshot = emptySet()
                } else {
                    // First boot stays fail-closed: a failed initial read
                    // yields emptySet(), never a partial snapshot.
                    snapshot = readPackagesOrNull(prefs) ?: emptySet()
                    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        // Total guard: must never throw (daemon drops subscription otherwise).
                        try {
                            if (key == KEY_PACKAGES) {
                                onChanged(prefs)
                            }
                        } catch (_: Throwable) {
                            // Keep previous snapshot.
                        }
                    }
                    try {
                        prefs.registerOnSharedPreferenceChangeListener(listener)
                    } catch (t: Throwable) {
                        logi("RemotePreferences listener registration failed: ${t.message}")
                    }
                    logi("RemotePreferences initial load: ${snapshot.size} package(s)")
                }
            } catch (t: Throwable) {
                // Fail-closed: group unreadable => stay empty (no behavior change).
                snapshot = emptySet()
                try {
                    logi("RemotePreferences init failed, staying fail-closed: ${t.message}")
                } catch (_: Throwable) {
                }
            }
            initialized = true
        }
    }

    // Vector callbacks must not unwind into the daemon. On ANY failure
    // (read error or an unexpected exception) the previous snapshot is
    // kept; we never surface partially-read state.
    private fun onChanged(prefs: SharedPreferences) {
        val read = try {
            readPackagesOrNull(prefs)
        } catch (t: Throwable) {
            logi("RemotePreferences update failed, keeping previous snapshot: ${t.message}")
            return
        }
        if (read == null) {
            logi("RemotePreferences update failed, keeping previous snapshot")
            return
        }
        snapshot = read
        logi("RemotePreferences updated: ${read.size} package(s)")
    }

    private fun readPackagesOrNull(prefs: SharedPreferences): Set<String>? {
        // getStringSet is a platform type (binder hands us the set, and the
        // signature carries no nullability info): never trust it non-null.
        // null is returned ONLY on failure — the caller keeps the previous
        // snapshot (spec §4.3). A genuine clear (raw == null or an empty
        // set) yields emptySet().
        val raw: Set<*>? = try {
            prefs.getStringSet(KEY_PACKAGES, emptySet())
        } catch (t: Throwable) {
            return null // failure signal: caller keeps the previous snapshot
        }
        if (raw == null) return emptySet()
        // Defensive copy: the binder hands us a serialized StringSet; element
        // types and sizes are not trusted beyond the dampening filter.
        val out = LinkedHashSet<String>()
        for (element in raw) {
            if (element is String && PolicySource.isValidPackage(element)) {
                out.add(element)
            }
        }
        return out
    }

    private fun logi(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: Throwable) {
            // Logging must never break the hook path.
        }
    }
}
