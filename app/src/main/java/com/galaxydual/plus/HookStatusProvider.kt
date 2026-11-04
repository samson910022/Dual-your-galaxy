// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

import android.content.Context
import com.galaxydual.hook.HookState

// App-side reader for the hook's self-scope active mark (H3/F4b).
//
// The hook writes HookState prefs from inside this app's own process when
// LSPosed injects it (self scope); this provider reads the same private
// file. Per-user automatically (each Android user has its own app data).
// Fail-closed: missing file/keys/stale mark/exception all read inactive.
// No IPC, no extra permissions. DEVICE-UNVERIFIED: round-trip never ran.
class HookStatusProvider(private val appContext: Context) {

    fun isHookActive(): Boolean {
        return try {
            val prefs = appContext.getSharedPreferences(HookState.PREFS_NAME, Context.MODE_PRIVATE)
            val active = prefs.getBoolean(HookState.KEY_ACTIVE, false)
            if (!active) return false
            val ts = prefs.getLong(HookState.KEY_TIMESTAMP_MS, 0L)
            HookState.isFresh(ts, System.currentTimeMillis())
        } catch (_: Exception) {
            false
        }
    }
}
