// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import android.content.SharedPreferences

// PR #90 Phase A (P0): PUBLIC, STRICTLY READ-ONLY bridge from the real remote
// preferences to the framework-free reader/gate. The Hook obtains remote prefs
// via libxposed XposedInterface.getRemotePreferences, which is READ-ONLY in
// hooked apps (API 102). This adapter therefore only ever calls getters:
// there is no edit()/put*/commit() call on this path (proved by
// BbcPreactivationReadOnlyTest with a fake whose edit() throws/counts).
//
// PUBLIC so :app tests can drive production `remoteHookPolicyStore` writes and
// then feed the very same prefs through the Hook reader + gate (cross-boundary
// regression test). No Android runtime is needed to call it with a fake.
object BbcPreactivationHookReader {

    /** Reads one snapshot. A null prefs / any read failure fails closed. */
    fun read(prefs: SharedPreferences?): BbcPreactivationPrefs = try {
        if (prefs == null) {
            BbcPreactivationPrefs(null, null, null)
        } else {
            readBbcPreactivationPrefs(object : BbcPreactivationPrefsView {
                override fun getBoolean(key: String, defValue: Boolean): Boolean =
                    prefs.getBoolean(key, defValue)

                override fun getString(key: String): String? =
                    prefs.getString(key, null)

                override fun getStringSet(key: String): Set<String>? =
                    prefs.getStringSet(key, LinkedHashSet<String>())

                override fun contains(key: String): Boolean = prefs.contains(key)

                override fun getLong(key: String, defValue: Long): Long =
                    prefs.getLong(key, defValue)
            })
        }
    } catch (_: Throwable) {
        BbcPreactivationPrefs(null, null, null)
    }

    /** Applies the read-only decision to an already-read snapshot. */
    fun effectOpen(compileTimeGate: Boolean, prefs: BbcPreactivationPrefs): Boolean =
        bbcPreactivationGate(
            compileTimeGate,
            prefs.optIn,
            prefs.classification,
            prefs.migrated,
        ).effectOpen

    /** Convenience: read + decide in one call (still zero writes). */
    fun effectOpen(compileTimeGate: Boolean, prefs: SharedPreferences?): Boolean =
        effectOpen(compileTimeGate, read(prefs))
}
